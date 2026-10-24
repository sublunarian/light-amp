package com.sublunar.amp.data

import com.sublunar.amp.source.PhoneLibrary
import com.sublunar.amp.source.Providers
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider
import kotlinx.serialization.Serializable

/** A fresh source id: never reused, and it names the source's database file. */
fun newSourceId(): String =
    "src-" + System.currentTimeMillis().toString(36) + "-" + (0..0xffff).random().toString(16)

/** One of a server's libraries (a Subsonic music folder, a Plex section…), as last seen. */
@Serializable
data class SourceLibrary(val id: String, val name: String)

/**
 * Where a source's music comes from. Stored by name. What each kind can do
 * comes from its provider — see [MusicSource.supportsLikes] and its neighbours.
 */
enum class SourceKind {
    /** A Subsonic-compatible server: Navidrome, Airsonic, Gonic, … */
    SUBSONIC,

    /** A Plex Media Server's music library. */
    PLEX,

    /** A Jellyfin server's music library. */
    JELLYFIN,

    /** The phone's own music folder — the same files the Light Music app plays. */
    LOCAL,
}

/**
 * One place the library can come from.
 *
 * Sources are a list rather than a single stored server so that two Navidromes,
 * a friend's Airsonic and the phone's own files can all be configured at once and
 * switched between; each keeps its own cache, downloads, sort order and library
 * selection, so switching is instant rather than a re-sync.
 */
@Serializable
data class MusicSource(
    /**
     * Stable, generated once and never reused — it names this source's database
     * file, so changing it would orphan the cache.
     */
    val id: String,
    val kind: SourceKind,
    /** What the Servers list calls it. Defaults to the server's host. */
    val name: String,
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    /**
     * What to stream on Wi-Fi. Persisted by id so an unknown value degrades to
     * the default, not a crash. Named for the single format it once was, so the
     * stored name stays.
     */
    val streamFormatId: String = StreamFormat.DEFAULT.id,
    /** What to stream on cellular. Null follows the Wi-Fi choice. */
    val cellularFormatId: String? = null,
    /** What downloads from this source are fetched as; null until chosen, read as the default. */
    val downloadFormatId: String? = null,
    /** What gets downloaded without being asked for; null until chosen, read as Manual. */
    val offlineModeName: String? = null,
    /**
     * What this source was allowed on its own, before one budget covered the
     * whole tool. Nothing reads it; stored records still carry it.
     */
    val downloadLimitBytes: Long? = null,
    /**
     * What this source reports itself as: Subsonic's `c=` client parameter
     * (Navidrome names its "Player" from it) and, on Plex, the
     * `X-Plex-Product`/`X-Plex-Device` headers. Blank means the app's own
     * default ("Amp" / "Light Phone III").
     */
    val playerName: String = "",
    /** Plex's `X-Plex-Token` or Jellyfin's access token, from signing in. */
    val token: String = "",
    /**
     * Whose library this is, on a server that scopes one per user.
     *
     * Jellyfin needs it on nearly every call — favourites, play state and which
     * libraries are even visible are all per user — so it is captured at login
     * and kept here rather than looked up again each time.
     */
    val userId: String = "",
    /** Plex's server id, needed to build the URIs that fill a playlist. */
    val machineIdentifier: String = "",
    /**
     * The device a sign-in was made as, and its [token] belongs to: Jellyfin's
     * DeviceId, Plex's `X-Plex-Client-Identifier`.
     *
     * One per sign-in, because Jellyfin keeps one sign-in per device and user
     * and ends the old one when the same device signs in again, and because
     * Plex ties a token to the device it was issued to and shows each device
     * as one player. Blank on sources signed in before this existed, which go
     * on as JellyfinClient.LEGACY_DEVICE_ID or PlexClient.LEGACY_CLIENT_ID —
     * the id their tokens were issued to.
     */
    val deviceId: String = "",
    /**
     * Which build's track parsing this source's cache was filled by.
     *
     * A sync only refetches an album whose song count changed, so a fix to how a
     * track is *read* — rather than to what the server holds — would never reach
     * a library that is already cached. Bumping
     * LibrarySync.TRACK_PARSER_GENERATION makes the next sync fetch every
     * album once and then record the new number here. Zero is "filled before this
     * existed", so every library synced by an older build refetches once.
     */
    val parserGeneration: Int = 0,
    /** The library being browsed, or null for All Libraries. */
    val libraryId: String? = null,
    /**
     * The server's libraries, cached from the last successful sync, so the
     * Sources page can offer them instantly and offline.
     */
    val libraries: List<SourceLibrary> = emptyList(),
    /**
     * Libraries the user hid, by id. Hidden everywhere: off the Sources page,
     * out of All Libraries and every list and search, and not fetched by a
     * sync; their cached rows stay, so showing one again is instant.
     *
     * Stored as what to *hide* rather than what to show, so a library the server
     * gains later turns up on its own instead of staying invisible until someone
     * thinks to go and tick it.
     *
     * Nullable only for what older builds stored: `null` was the All Libraries
     * row, which could once be hidden too and is now never a choice — see
     * [showsAllLibraries]. Anything reading this skips it.
     */
    val hiddenLibraryIds: List<String?> = emptyList(),
    /**
     * Every address Plex advertised for this server, best first.
     *
     * [baseUrl] is whichever one answered when the server was linked, usually
     * the LAN address, which stops working the moment the phone leaves that
     * LAN. Keeping the alternatives means a failure can be retried against the
     * next one.
     */
    val connections: List<String> = emptyList(),
    /**
     * What this server turned out to implement, learned after a sync and kept
     * until the server says it is a different version — see [ServerFeatures].
     * Null until it has been asked, which reads as "everything", so a source
     * saved before this existed behaves exactly as it did.
     */
    val features: ServerFeatures? = null,
) {
    /**
     * What this kind of source is and can do — see [SourceProvider].
     *
     * A getter, and so is everything read through it: a property with a backing
     * field would be stored with the source, and would change what two records
     * being equal means.
     */
    val provider: SourceProvider get() = Providers.of(kind)

    private val capabilities: SourceCapabilities get() = provider.capabilities(features)

    /** The phone's own music, when this source is files on this phone rather than a server. */
    val phoneLibrary: PhoneLibrary? get() = provider.phoneLibrary

    /** Whether there is a server behind this source — everything but the phone's own music. */
    val hasServer: Boolean get() = phoneLibrary == null

    /**
     * The formats this source can actually serve — see SourceProvider.streamFormats.
     * Known without a client, so the settings screens offer the right choices
     * for a source that isn't the one connected.
     */
    val streamFormats: List<StreamFormat> get() = provider.streamFormats

    /**
     * Streaming quality, per connection.
     *
     * Two settings rather than one plus a data-saving mode that overrides it:
     * an override is a silent substitution, and the whole point of asking for a
     * format is getting it. Cellular follows Wi-Fi until it is told otherwise.
     */
    val wifiFormat: StreamFormat get() = supported(streamFormatId)
    val cellularFormat: StreamFormat get() = supported(cellularFormatId ?: streamFormatId)

    /**
     * Downloading quality — exactly what was chosen, even where this server
     * turns out not to be able to serve it. Quietly reading a stored Original
     * as something smaller would be a silent substitution: files would come
     * down transcoded while every screen still claimed otherwise. So the
     * choice stands, [downloadFormatAvailable] says whether it can be met, and
     * the downloader stops and says why rather than fetching something else.
     */
    val downloadFormat: StreamFormat get() = supported(downloadFormatId)

    /**
     * Whether this server can serve [downloadFormat] — false for an Original
     * it has no call for, and for anything at all on an account it won't let
     * keep copies ([downloadsRefused]).
     */
    val downloadFormatAvailable: Boolean get() =
        !downloadsRefused && (downloadFormats.isEmpty() || downloadFormat in downloadFormats)

    val offlineMode: OfflineMode
        get() = offlineModeName?.let { name ->
            OfflineMode.entries.firstOrNull { it.name == name }
        } ?: OfflineMode.MANUAL

    /** [hiddenLibraryIds] as every reader means it: without the null older builds stored. */
    val hiddenLibraries: Set<String> get() = hiddenLibraryIds.filterNotNull().toSet()

    /** The libraries this source offers on the Sources page, in order. */
    val visibleLibraries: List<SourceLibrary>
        get() = libraries.filterNot { it.id in hiddenLibraryIds }

    /**
     * This source with [libraryId] shown or hidden.
     *
     * Refuses to hide the last library: the rows under a source are the only
     * way to switch to it. Hiding the library being browsed moves the browsing
     * to All Libraries, rather than leaving it on a library that no longer
     * shows — and so does hiding down to one, where All Libraries *is* that one
     * and the single row left under the source is the one to tick. A stored
     * null (a hidden All Libraries row) goes on the next change.
     */
    fun withLibraryShown(libraryId: String, shown: Boolean): MusicSource {
        val hidden = hiddenLibraryIds.filterNotNull().toMutableList()
        if (shown) return copy(hiddenLibraryIds = hidden - libraryId)
        if (libraryId in hidden) return copy(hiddenLibraryIds = hidden)
        val visible = libraries.count { it.id !in hidden }
        if (visible <= 1) return this
        val left = visible - 1
        return copy(
            hiddenLibraryIds = hidden + libraryId,
            libraryId = if (left <= 1 || this.libraryId == libraryId) null else this.libraryId,
        )
    }

    /**
     * Whether the All Libraries row is offered: whenever two or more libraries
     * are shown, and never as a setting. With one it would be a second row for
     * the same music, and as a tick box beside the real libraries it read as one
     * more of them — tick it, untick the rest, and expect it to hold something.
     */
    val showsAllLibraries: Boolean get() = visibleLibraries.size >= 2

    /**
     * Falls back to the original file, never to something lossier: a source
     * carrying a format it can't serve (chosen before the list narrowed, or on a
     * different kind of server) would otherwise be silently downgraded to
     * whatever the server felt like sending.
     */
    private fun supported(id: String?): StreamFormat {
        val wanted = StreamFormat.fromId(id)
        return if (streamFormats.isEmpty() || wanted in streamFormats) wanted else StreamFormat.RAW
    }

    /**
     * The client that talks to this source, or null for the phone's own music —
     * which has no server behind it at all — and for a source missing what its
     * server needs. See [SourceProvider.createClient].
     */
    fun toClient(): MusicServer? = provider.createClient(this)

    // What this kind of source can do, as its provider answers it. The UI reads
    // these so a feature that can't work is absent rather than present and broken.

    /** A heart that syncs: a favourite of the server's own, which not every kind of server has. */
    val supportsLikes: Boolean get() = capabilities.likes

    /** Star ratings — on a Subsonic server, only where it turned out to have `setRating`. */
    val supportsRatings: Boolean get() = capabilities.ratings

    /**
     * A radio seeded by one song — see [MusicServer.getSimilarSongs]. Every
     * server has one: Subsonic's `getSimilarSongs`, Plex's stations, Jellyfin's
     * instant mix. The phone's own files have no one to ask.
     */
    val supportsRadio: Boolean get() = capabilities.radio

    /** Whether a playlist can be started empty and filled later — not on Plex. */
    val supportsEmptyPlaylists: Boolean get() = capabilities.emptyPlaylists

    /**
     * Downloading only means something when the audio is somewhere else.
     *
     * Whether this source *has* downloads, which it may still have where new
     * ones aren't allowed — see [mayDownload].
     */
    val supportsDownloads: Boolean get() = capabilities.downloads

    /**
     * Whether the server won't let this account keep copies — see
     * ServerFeatures.KEEP_COPIES. Respected rather than routed round: Jellyfin
     * has a call that hands over the file without asking, and using it would
     * be overruling the person who runs the server. What is already on the
     * phone stays, and plays.
     */
    val downloadsRefused: Boolean get() = !features.allows(ServerFeatures.KEEP_COPIES)

    /** Whether a new download may be started here — the Download rows show only where it may. */
    val mayDownload: Boolean get() = supportsDownloads && !downloadsRefused

    /**
     * Whether a download can keep the original file.
     *
     * That is `download`'s job and no other endpoint's: streaming the same song
     * is a *playback* request, which a server may count as a play — see
     * SubsonicClient.downloadUrl. A server without it can still download, in
     * one of the transcoded qualities, so the quality is what narrows rather
     * than downloading itself.
     */
    val supportsOriginalDownload: Boolean get() = capabilities.originalDownload

    /**
     * Whether the server can be asked to go and look for new files.
     *
     * Missing and refusing are different things and the Refresh row says so:
     * a server that has the call but won't run it for this account leaves the
     * library stale, which is worth saying; one without the call at all keeps
     * its library current by itself and has nothing to refuse.
     */
    val supportsServerScan: Boolean get() = capabilities.serverScan

    /**
     * The qualities a download here can be kept in: what can be streamed, less
     * the original where the server has no call that hands one over.
     */
    val downloadFormats: List<StreamFormat> get() =
        if (supportsOriginalDownload) streamFormats else streamFormats - StreamFormat.RAW

    /**
     * Separate libraries to browse: Subsonic's music folders and Plex's library
     * sections are the same idea. Local music is one folder by definition.
     */
    val supportsLibraries: Boolean get() = capabilities.libraries

    companion object {
        /** A readable default name for a server the user just added. */
        fun nameFor(baseUrl: String): String =
            baseUrl.substringAfter("://").substringBefore('/').ifBlank { "Server" }
    }
}

package com.sublunar.amp.data

import kotlinx.serialization.Serializable

/** A fresh source id: never reused, and it names the source's database file. */
fun newSourceId(): String =
    "src-" + System.currentTimeMillis().toString(36) + "-" + (0..0xffff).random().toString(16)

/** One of a Subsonic server's music folders, as last seen. */
@Serializable
data class SourceLibrary(val id: String, val name: String)

/** Where a source's music comes from. */
enum class SourceKind {
    /** A Subsonic-compatible server: Navidrome, Airsonic, Gonic, … */
    SUBSONIC,

    /**
     * A Plex Media Server's music library.
     *
     * Fewer capabilities than Subsonic — no favourites, no popular songs — see
     * the flags below, which the UI reads so that what can't sync isn't offered.
     */
    PLEX,

    /**
     * A Jellyfin server's music library.
     *
     * Closer to Subsonic than Plex is — real favourites, playlists that can be
     * created empty, a transcoder that produces what it is asked for — but with
     * no star ratings and no popular songs. See the flags below.
     */
    JELLYFIN,

    /**
     * The phone's own music folder — the same files the Light Music app plays.
     *
     * There is no server behind it, so everything that lives on one (likes,
     * ratings, play counts, scrobbles, playlists) is unavailable; see
     * [MusicSource.supportsLikes] and its neighbours, which the UI reads rather
     * than testing the kind directly.
     */
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
     * the default, not a crash. (Named for what it was before the two split, so
     * a source saved earlier keeps its choice.)
     */
    val streamFormatId: String = StreamFormat.DEFAULT.id,
    /** What to stream on cellular. Null follows the Wi-Fi choice. */
    val cellularFormatId: String? = null,
    /**
     * What downloads from this source are fetched as.
     *
     * Null means "never chosen here", which is how a source configured before
     * these became per-source inherits the old app-wide setting instead of
     * silently dropping to the default — see AppSettings.sources, which fills
     * every null below in the same way.
     */
    val downloadFormatId: String? = null,
    /** What gets downloaded without being asked for. */
    val offlineModeName: String? = null,
    /** How much of this phone this source's downloads may take. */
    /**
     * What this source was allowed on its own, before the budget became one for
     * the whole tool. Kept only so [AppSettings.migrateDownloadLimit] can carry
     * the largest of them up; nothing reads it to decide anything any more.
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
     * on as [JellyfinClient.LEGACY_DEVICE_ID] or [PlexClient.LEGACY_CLIENT_ID]
     * — the id their tokens were issued to.
     */
    val deviceId: String = "",
    /**
     * Which build's track parsing this source's cache was filled by.
     *
     * A sync only refetches an album whose song count changed, so a fix to how a
     * track is *read* — rather than to what the server holds — would never reach
     * a library that is already cached. Bumping
     * [LibraryRepository.TRACK_PARSER_GENERATION] makes the next sync fetch every
     * album once and then record the new number here. Zero is "filled before this
     * existed", so every library synced by an older build refetches once.
     *
     * Deliberately not a database version: the Room builder destroys and rebuilds
     * on a version change, which would take the downloads table with it.
     */
    val parserGeneration: Int = 0,
    /** Subsonic music folder, or null for all of them. */
    val libraryId: String? = null,
    /**
     * The server's music folders, cached from the last successful sync.
     *
     * Kept on the record so the Sources page can offer them instantly and
     * offline, rather than making a request per source every time it opens.
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
     * [baseUrl] is whichever one answered when the server was linked, and that
     * is usually the LAN address — faster, and it skips Plex's relay. But it is
     * only reachable from that LAN, so a source linked at home stops working
     * the moment the phone is on cellular, with no way to recover: nothing
     * re-resolved it. Keeping the alternatives means a failure can be retried
     * against the next one instead.
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
     * The formats this source can actually serve, asked of the client that talks
     * to it — see [MusicServer.streamFormats], where each server answers for
     * itself. Read straight off the class rather than from a live client so the
     * settings screens can offer the right choices for a source that isn't the
     * one connected. The phone's own music is never transcoded at all.
     */
    val streamFormats: List<StreamFormat> get() = when (kind) {
        SourceKind.SUBSONIC -> SubsonicClient.STREAM_FORMATS
        SourceKind.PLEX -> PlexClient.STREAM_FORMATS
        SourceKind.JELLYFIN -> JellyfinClient.STREAM_FORMATS
        SourceKind.LOCAL -> emptyList()
    }

    /**
     * Falls back to the original file, never to something lossier: a source
     * carrying a format it can't serve (chosen before the list narrowed, or on a
     * different kind of server) would otherwise be silently downgraded to
     * whatever the server felt like sending.
     */
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
     * and the single row left under the source is the one to tick. Older
     * builds could hide the All Libraries row too, as a null; that is no
     * longer a choice, so it goes on the next change.
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


    private fun supported(id: String?): StreamFormat {
        val wanted = StreamFormat.fromId(id)
        return if (streamFormats.isEmpty() || wanted in streamFormats) wanted else StreamFormat.RAW
    }

    /**
     * The client that talks to this source, or null for the phone's own music —
     * which has no server behind it at all.
     */
    fun toClient(): MusicServer? = when (kind) {
        SourceKind.LOCAL -> null
        SourceKind.SUBSONIC -> toConfig()?.let { SubsonicClient(it) }
        SourceKind.PLEX ->
            if (baseUrl.isBlank() || token.isBlank()) {
                null
            } else {
                PlexClient(baseUrl, token, machineIdentifier, playerName.ifBlank { null }, plexClientIdentifier)
            }
        SourceKind.JELLYFIN ->
            if (baseUrl.isBlank() || token.isBlank() || userId.isBlank()) {
                null
            } else {
                JellyfinClient(
                    baseUrl,
                    token,
                    userId,
                    playerName.ifBlank { null },
                    deviceId.ifBlank { JellyfinClient.LEGACY_DEVICE_ID },
                )
            }
    }

    /** Who a Plex link says it is — see [deviceId]. */
    val plexClientIdentifier: String get() = deviceId.ifBlank { PlexClient.LEGACY_CLIENT_ID }

    /** Null unless this is a Subsonic server with somewhere to point. */
    fun toConfig(): SubsonicConfig? =
        if (kind == SourceKind.SUBSONIC && baseUrl.isNotBlank()) {
            SubsonicConfig(baseUrl, username, password, playerName.ifBlank { null })
        } else {
            null
        }

    // What this kind of source can actually do. Read by the UI so a feature that
    // can't work is absent rather than present and broken: a heart on a local
    // file would write a like nothing can store and nothing can restore.
    /**
     * Plex has star ratings but no separate favourite, so nothing for a heart
     * to sync to; Jellyfin has the favourite but not the stars. The two servers
     * are missing opposite halves of the same idea.
     */
    val supportsLikes: Boolean get() = kind == SourceKind.SUBSONIC || kind == SourceKind.JELLYFIN

    /**
     * Subsonic's own `setRating`, which not every server that speaks Subsonic
     * has — asked of this one rather than assumed of the protocol.
     */
    val supportsRatings: Boolean get() = when (kind) {
        SourceKind.SUBSONIC -> implements(ServerFeatures.SET_RATING)
        SourceKind.PLEX -> true
        else -> false
    }

    /**
     * A radio seeded by one song — see [MusicServer.getSimilarSongs]. Every
     * server has one: Subsonic's `getSimilarSongs`, Plex's stations, Jellyfin's
     * instant mix. The phone's own files have no one to ask.
     */
    val supportsRadio: Boolean get() = when (kind) {
        SourceKind.LOCAL -> false
        SourceKind.SUBSONIC -> implements(ServerFeatures.SIMILAR_SONGS)
        else -> true
    }
    /** The phone keeps its own, as m3u8 files — see [LocalPlaylists]. */
    val supportsPlaylists: Boolean get() = true

    /**
     * Whether a playlist can be started empty and filled later.
     *
     * Plex creates a playlist *from* something and has no call for an empty one,
     * which is why PlexAmp makes you pick the songs first. So "New Playlist" on
     * its own isn't offered there — filing songs into a new playlist still is.
     */
    val supportsEmptyPlaylists: Boolean get() = kind != SourceKind.PLEX


    /**
     * Downloading only means something when the audio is somewhere else.
     *
     * Whether this source *has* downloads, which it may still have where new
     * ones aren't allowed — see [mayDownload].
     */
    val supportsDownloads: Boolean get() = kind != SourceKind.LOCAL

    /**
     * Whether the server won't let this account keep copies — see
     * ServerFeatures.KEEP_COPIES. Respected rather than routed round: Jellyfin
     * has a call that hands over the file without asking, and using it would
     * be overruling the person who runs the server. What is already on the
     * phone stays, and plays.
     */
    val downloadsRefused: Boolean get() = !implements(ServerFeatures.KEEP_COPIES)

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
    val supportsOriginalDownload: Boolean get() = when (kind) {
        SourceKind.SUBSONIC -> implements(ServerFeatures.DOWNLOAD)
        else -> true
    }

    /**
     * Whether the server can be asked to go and look for new files.
     *
     * Missing and refusing are different things and the Refresh row says so:
     * a server that has the call but won't run it for this account leaves the
     * library stale, which is worth saying; one without the call at all keeps
     * its library current by itself and has nothing to refuse.
     */
    val supportsServerScan: Boolean get() = when (kind) {
        SourceKind.SUBSONIC -> implements(ServerFeatures.SCAN_STATUS)
        SourceKind.LOCAL -> false
        else -> true
    }

    /** What this source knows it cannot do; unasked reads as "everything". */
    private fun implements(endpoint: String): Boolean = features?.has(endpoint) ?: true

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
    val supportsLibraries: Boolean get() = kind != SourceKind.LOCAL

    companion object {
        /** The id the phone's own music always has — there can only be one. */
        const val LOCAL_ID = "local"

        fun local(): MusicSource =
            MusicSource(id = LOCAL_ID, kind = SourceKind.LOCAL, name = "Local Music")

        /** A readable default name for a server the user just added. */
        fun nameFor(baseUrl: String): String =
            baseUrl.substringAfter("://").substringBefore('/').ifBlank { "Server" }
    }
}

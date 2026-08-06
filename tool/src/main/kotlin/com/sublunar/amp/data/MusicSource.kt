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
    val downloadLimitBytes: Long? = null,
    /** Which of the server's libraries downloads come from; null means all. */
    val downloadLibraryId: String? = null,
    /** Whether a download brings the words with it. */
    val downloadLyrics: Boolean? = null,
    /** Plex's `X-Plex-Token`, from linking or entered by hand. */
    val token: String = "",
    /** Plex's server id, needed to build the URIs that fill a playlist. */
    val machineIdentifier: String = "",
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
     * Libraries kept off the Sources page, by id — `null` being the "All
     * Libraries" row rather than any one of them.
     *
     * Stored as what to *hide* rather than what to show, so a library the server
     * gains later turns up on its own instead of staying invisible until someone
     * thinks to go and tick it.
     */
    val hiddenLibraryIds: List<String?> = emptyList(),
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
    val downloadFormat: StreamFormat get() = supported(downloadFormatId)

    val offlineMode: OfflineMode
        get() = offlineModeName?.let { name ->
            OfflineMode.entries.firstOrNull { it.name == name }
        } ?: OfflineMode.MANUAL

    /** The libraries this source offers on the Sources page, in order. */
    val visibleLibraries: List<SourceLibrary>
        get() = libraries.filterNot { it.id in hiddenLibraryIds }

    /** Whether the whole-server row is offered alongside the individual ones. */
    val showsAllLibraries: Boolean get() = null !in hiddenLibraryIds

    val downloadLimit: Long get() = downloadLimitBytes ?: 0L
    val wantsLyrics: Boolean get() = downloadLyrics ?: true

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
                PlexClient(baseUrl, token, machineIdentifier)
            }
    }

    /** Null unless this is a Subsonic server with somewhere to point. */
    fun toConfig(): SubsonicConfig? =
        if (kind == SourceKind.SUBSONIC && baseUrl.isNotBlank()) {
            SubsonicConfig(baseUrl, username, password)
        } else {
            null
        }

    // What this kind of source can actually do. Read by the UI so a feature that
    // can't work is absent rather than present and broken: a heart on a local
    // file would write a like nothing can store and nothing can restore.
    /** Plex has star ratings but no separate favourite, so nothing to sync. */
    val supportsLikes: Boolean get() = kind == SourceKind.SUBSONIC
    val supportsRatings: Boolean get() = kind != SourceKind.LOCAL
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


    /** Downloading only means something when the audio is somewhere else. */
    val supportsDownloads: Boolean get() = kind != SourceKind.LOCAL

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

package com.sublunar.amp.data

import io.ktor.client.statement.HttpResponse
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A stream's content type, and whether it is served in byte ranges — see [MusicServer.knownStream]. */
data class KnownStream(val mime: String, val ranges: Boolean)

/**
 * A queue the server is holding, from this client or another one.
 *
 * [currentId] rather than an index: the library on this phone may no longer have
 * every row the queue names, and the track that was playing has to survive the
 * ones that drop out.
 */
data class SavedQueue(
    val trackIds: List<String>,
    val currentId: String?,
    val positionMs: Long,
)

/**
 * What came of asking a server to scan.
 *
 * [REFUSED] and [ABSENT] are different things and the Sources page says so:
 * a server that has the call but won't run it for this account leaves the
 * library stale until someone with the rights runs it, which is worth saying;
 * a server without the call keeps its own library and has nothing to refuse.
 */
enum class ScanRequest { STARTED, REFUSED, ABSENT }

/**
 * What the app needs from a music server, whichever kind it is. Callers work
 * in the app's own models ([Track], [Album], [Playlist]); the mapping from
 * whatever the server actually sends lives in the implementations.
 *
 * **Not every server can do everything.** The *source* declares what it can do
 * (see [MusicSource.supportsLikes] and its neighbours) and the UI leaves out
 * what it can't. The reads only some servers implement default to an empty
 * answer. The writes default to [RefusedWrite] instead: a default that did
 * nothing would read as success, and a like the server never heard of would
 * stand on the phone as if it had.
 */
interface MusicServer {

    /** Throws if the server isn't reachable or the credentials are wrong. */
    suspend fun ping()

    /**
     * What this particular server implements, asked of it — see [ServerFeatures].
     *
     * Subsonic probes its endpoints: its servers range from a full Navidrome
     * to Bandcamp, which answers a third of the API, and the protocol has no
     * way to ask which. Jellyfin reads what the account is allowed. Plex is one
     * implementation, known from the kind alone, and [known] comes back untouched.
     */
    suspend fun probeFeatures(known: ServerFeatures?, sampleTrackId: String?): ServerFeatures? = known

    /** Releases the HTTP client. */
    fun close()

    // --- Browsing ------------------------------------------------------------

    /**
     * The server's separate libraries, if it has the idea at all.
     *
     * Subsonic calls them music folders; Plex calls them library sections. Both
     * mean "a shelf you can browse on its own".
     */
    suspend fun getMusicFolders(): List<MusicFolder>

    suspend fun getAllAlbums(musicFolderId: String? = null): List<Album>

    suspend fun getAlbumTracks(albumId: String): List<Track>

    /** The user's favourites. Empty on a server with no such concept. */
    suspend fun getStarred(musicFolderId: String? = null): Starred =
        Starred(songIds = emptySet(), albumIds = emptySet(), artistNames = emptySet())

    /**
     * Ask the server to go and look at its files for anything new.
     *
     * Syncing pulls what the server already knows; it cannot make the server
     * notice a folder it hasn't looked in. Both Subsonic and Plex expose a way
     * to ask for that scan, and it is the only thing that turns a file dropped
     * into the music folder into something this app can see.
     */
    suspend fun startServerScan(musicFolderId: String? = null): ScanRequest = ScanRequest.ABSENT

    /** True while a [startServerScan] is still running. */
    suspend fun serverScanning(musicFolderId: String? = null): Boolean = false

    /**
     * Which albums' songs changed on the server since [mark] — a mark this
     * returned before, null the first time — among [libraries] (null: all).
     *
     * A sync re-reads an album's songs when the album is new or its count of
     * songs moved. A song re-tagged in place moves neither, and nor does one
     * whose tags a server only finished reading after the album was first
     * synced — this is what catches them. The default is [SongChanges.Unknown]:
     * the server can't say, and the sync walks the library a little at a time
     * instead.
     */
    suspend fun songChangesSince(mark: String?, libraries: List<String?>): SongChanges =
        SongChanges.Unknown(mark = null)

    /** Server-side artist ids, for the star calls that need one. */
    suspend fun getArtistIndex(musicFolderId: String? = null): List<ArtistRef> = emptyList()

    /** An artist's best-known songs, as many as the server thinks sensible. Empty where the server doesn't rank them. */
    suspend fun getTopSongs(artistName: String): List<Track> = emptyList()

    /**
     * Songs to follow on from this one: a radio seeded by a track, up to
     * [RADIO_LENGTH] of them.
     *
     * Navidrome answers from its Last.fm agent — the track's artist and artists
     * like them — Plex from the station it builds for the track, and Jellyfin
     * from its instant mix. Empty where the server has no such idea, or nothing
     * to say about this track; the caller tells the user so rather than playing
     * something else instead.
     */
    suspend fun getSimilarSongs(songId: String): List<Track> = emptyList()

    // --- Media ---------------------------------------------------------------

    /**
     * Where to stream [track] from in [format], [timeOffsetSeconds] in.
     *
     * The whole track rather than its id: Plex serves the original bytes only
     * from the path to the file it gave out with the track ([Track.streamPath]).
     */
    fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = true,
        /**
         * A caller-generated id for the playback this stream belongs to,
         * never shared with a download. Optional, and ignored by servers with
         * no idea of a session (Subsonic); Plex uses it to tie the stream to
         * the session its timeline reports, and to keep a download alongside
         * it from tearing it down — see PlexClient.transcodeParams.
         */
        sessionId: String? = null,
    ): String

    /**
     * Where to fetch a *copy* from, as opposed to something to play now.
     *
     * The same URL for most servers, which serve a whole file either way. Plex's
     * transcoder has a streaming mode and a download mode, and the streaming one
     * gives a live segmented encode with no header stating how long it is, which
     * the player then guesses at, wrongly, for the life of the file.
     */
    fun downloadUrl(track: Track, format: StreamFormat): String =
        streamUrl(track, format, estimateContentLength = false)

    /**
     * What [streamUrl] for [track] in [format] will send, where the server
     * knows without being asked. Null — the default — when only fetching it
     * would tell: then a speaker's format and seeking are found out by a
     * first fetch of their own.
     */
    fun knownStream(track: Track, format: StreamFormat): KnownStream? = null

    /**
     * Another address for the same original file, for a server that serves
     * some files on one address and not the other. Null where there is no
     * other, which is every server but Plex — see [PlexClient.alternateOriginalUrl].
     *
     * Asked for only once the usual original stream has failed a song, and
     * never in place of a format the user chose: it is the same bytes,
     * reached another way.
     */
    fun alternateOriginalUrl(track: Track): String? = null

    /**
     * Settle whatever the server needs settling before it will serve a stream.
     *
     * Most servers need nothing and the default says so. Plex is the exception:
     * the stream URL claims its Media Decision Engine, and Plex holds a client
     * to that claim — a session it knows about with no decision recorded is
     * refused the transcode outright, as a bare 400 on the media request. See
     * [PlexClient.prepareStream].
     *
     * Called with the same [sessionId] the stream URL carries, so the answer
     * applies to the request that follows. Returns false when the server
     * refused; playback is attempted anyway, since a server that won't decide
     * may still serve.
     */
    suspend fun prepareStream(
        songId: String,
        format: StreamFormat,
        sessionId: String?,
    ): Boolean = true

    /**
     * Whether this track's stream in [format] has to have [prepareStream]
     * asked for it, under the session it carries, before it is fetched. False
     * by default, and for any stream that is the file itself.
     */
    fun streamNeedsDecision(track: Track, format: StreamFormat): Boolean = false

    /**
     * Whether the server wants one session for a player's whole listening,
     * changing song as it goes, rather than one per song — see
     * PlaybackController.sessionIdFor.
     *
     * Plex does: each session counts against a shared user's "remote streams
     * allowed", so at one stream per user an id per song had the next song
     * refused while the last one's session was still listed. Jellyfin keeps its
     * PlaySessionId per song, and Subsonic has no sessions at all.
     */
    val oneSessionPerPlayer: Boolean get() = false

    fun coverArtUrl(coverArtId: String?): String?

    /**
     * The same cover, asked for no larger than [maxSizePx] on its long edge:
     * servers keep art at whatever size it came in (Plex's is often several
     * megabytes), and the phone has no use for more than its screen.
     *
     * Defaults to the full-size URL, for a server that can't resize. The caller
     * falls back to that anyway if this one doesn't answer — see
     * ArtworkLoader.fetch.
     */
    fun coverArtUrl(coverArtId: String?, maxSizePx: Int): String? = coverArtUrl(coverArtId)

    /** Words for a song, timed where the server has them. */
    suspend fun getLyrics(songId: String): Lyrics? = null

    // --- Writing back --------------------------------------------------------

    /**
     * [submission] false says "this is playing now", true says "this was played".
     *
     * Two different statements, and servers treat them as such: the first is
     * what Navidrome shows as now-playing and hands to its agents when a track
     * starts, the second is the play itself and is what reaches a scrobbling
     * service. A client that only sends the second announces nothing while it
     * plays.
     *
     * The play throws when the server didn't take it, so the caller can keep it
     * for later — see [writeResultOf]. The now-playing notice is only true now,
     * is never kept, and may fail quietly.
     */
    suspend fun scrobble(songId: String, atMs: Long? = null, submission: Boolean = true) = Unit

    /**
     * Tell the server this session is still going, so something watching active
     * sessions — Plex's dashboard, chiefly — has a "Now Playing" to show.
     *
     * [sessionId] ties a run of calls to one playback the way [streamUrl]'s
     * parameter of the same name does: one per song, or one for the whole
     * listening where the server asks for that ([oneSessionPerPlayer]). A
     * server with no notion of a live session (Subsonic) does nothing with
     * any of this.
     */
    suspend fun reportTimeline(
        sessionId: String,
        songId: String,
        state: TimelineState,
        positionMs: Long,
        durationMs: Long,
    ) = Unit

    /**
     * Hand the queue to the server, so another client can pick it up.
     *
     * [currentId] is the track, not its index — see [SavedQueue].
     */
    suspend fun savePlayQueue(trackIds: List<String>, currentId: String?, positionMs: Long) = Unit

    /** The queue this account last left somewhere, or null if the server keeps none. */
    suspend fun getPlayQueue(): SavedQueue? = null

    /**
     * 1–5, or 0 to clear. Throws when the server didn't take it — see
     * [writeResultOf] — and so do the likes below: returning is the server's
     * yes, and nothing else may read as one.
     */
    suspend fun setRating(id: String, stars: Int): Unit = throw RefusedWrite()

    suspend fun starSong(songId: String): Unit = throw RefusedWrite()
    suspend fun unstarSong(songId: String): Unit = throw RefusedWrite()
    suspend fun starAlbum(albumId: String): Unit = throw RefusedWrite()
    suspend fun unstarAlbum(albumId: String): Unit = throw RefusedWrite()
    suspend fun starArtist(artistId: String): Unit = throw RefusedWrite()
    suspend fun unstarArtist(artistId: String): Unit = throw RefusedWrite()

    // --- Playlists -----------------------------------------------------------

    suspend fun getPlaylists(musicFolderId: String? = null): List<Playlist> = emptyList()
    suspend fun getPlaylistTracks(id: String): List<Track> = emptyList()

    /**
     * Make a playlist and return its id.
     *
     * [songIds] are the playlist's opening contents, not a hint: a caller that
     * passes them must not add them again. Plex won't create an empty playlist
     * at all — it has no call for it — so the songs have to go in as part of
     * the same request, and [MusicSource.supportsEmptyPlaylists] says whether
     * leaving them out is even an option.
     *
     * Throws on failure, as the three after it do; a null id is not one. Some
     * servers make the playlist and answer a bare "ok" without saying which.
     */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String? = throw RefusedWrite()
    suspend fun renamePlaylist(id: String, name: String): Unit = throw RefusedWrite()
    suspend fun deletePlaylist(id: String): Unit = throw RefusedWrite()
    suspend fun addToPlaylist(id: String, songId: String): Unit = throw RefusedWrite()

    /**
     * Remove the entries [at] these positions — in the playlist as the server
     * holds it, all of them read against the list as it stands before any is
     * removed. Positions rather than song ids because a playlist can hold a
     * song twice and only one of them is meant. Positions are in the whole list
     * [getPlaylistTracks] returns, never in a page narrowed to one library.
     *
     * Each position comes with the id of the song the caller saw there, and an
     * implementation must find that song there before it removes anything: the
     * playlist may have been edited from somewhere else since the caller read
     * it, and a position that has drifted names a different song — which would
     * go, and be reported as success. A mismatch removes nothing and is false.
     *
     * Whether every one of them went. The caller shows the edit at once and
     * takes it back on a false, so a server that quietly refused must answer
     * false rather than let its own swallowed error read as success — the
     * default here is false for the same reason: a source with no playlist
     * writes never lands one.
     */
    suspend fun removeFromPlaylistAt(id: String, at: Map<Int, String>): Boolean = false

    /**
     * Whether the edit landed — see [removeFromPlaylistAt]. [orderedSongIds] is
     * the whole playlist in its new order: every song it holds, in every library.
     */
    suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>): Boolean = false
}

/** How many songs a radio asks the server for — Subsonic's own default. */
internal const val RADIO_LENGTH = 50

/** Whether [order] holds exactly the songs of [songs], as many times each, in any order. */
internal fun isReorderOf(order: List<String>, songs: List<String>): Boolean =
    order.size == songs.size && order.groupingBy { it }.eachCount() == songs.groupingBy { it }.eachCount()

/**
 * When the server says it is, from a response's `Date` header — a change mark
 * has to be on the server's clock, which the phone's may disagree with by
 * minutes. The phone's own where the header is missing or unreadable.
 */
internal fun serverClock(response: HttpResponse): Instant = serverClock(response.headers["Date"])

internal fun serverClock(date: String?): Instant =
    date?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
        ?: Instant.now()

/** A server's answer to [MusicServer.songChangesSince]; [mark] is what to ask from next time. */
sealed interface SongChanges {
    val mark: String?

    /**
     * These albums' songs changed, and no others' — unless [alsoWalk]: some
     * changes never reach the server's list, so the sync walks the library
     * as well, the way it does for [Unknown].
     */
    data class Albums(
        val ids: Set<String>,
        override val mark: String,
        val alsoWalk: Boolean = false,
    ) : SongChanges

    /** Nothing to compare with yet: every album's songs are read once, and from then on it can say. */
    data class Everything(override val mark: String) : SongChanges

    /** Something may have changed and the server can't say what. */
    data class Unknown(override val mark: String?) : SongChanges

    /** Nothing changed since the mark. */
    data class Nothing(override val mark: String) : SongChanges
}

/** What [MusicServer.reportTimeline] tells the server a session is doing. */
enum class TimelineState {
    PLAYING,
    PAUSED,
    STOPPED,
    BUFFERING,
}

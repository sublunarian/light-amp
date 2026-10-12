package com.sublunar.amp.data

import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import java.time.Instant
import io.ktor.client.statement.HttpResponse

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
 * What the app needs from a music server, whichever kind it is.
 *
 * Extracted from [SubsonicClient] so a second backend — Plex — can sit beside
 * it without the library, the downloader or the player knowing which one they
 * are talking to. Everything above this line works in the app's own models
 * ([Track], [Album], [Playlist]); the mapping from whatever the server actually
 * sends lives in the implementations.
 *
 * **Not every server can do everything.** Rather than have each call fail
 * quietly on a backend that doesn't support it, the *source* declares what it
 * can do (see [MusicSource.supportsLikes] and its neighbours) and the UI leaves
 * out what it can't. The reads only some servers implement default to an empty
 * answer, so a server that has no popular songs needn't write a stub saying so.
 * The writes default to [RefusedWrite] instead: a default that did nothing
 * would read as success, and a like the server never heard of would stand on
 * the phone as if it had.
 */
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
 * What came of a write: a like, a rating, a playlist edit, a play.
 *
 * Two kinds of failure, because they want opposite things done. A server that
 * never answered may well take the same write later, so it is kept and sent
 * again; a server that answered no will say no again, and a write kept for it
 * only stands in the way of everything queued behind it.
 */
sealed interface WriteResult {
    data object Saved : WriteResult

    /** Never answered: worth sending again later. */
    data class Unreachable(val reason: String) : WriteResult

    /** The server answered no: sending it again gets the same answer. */
    data class Refused(val reason: String) : WriteResult
}

/** Refused before anything was sent — no call for it on this server, or nothing to aim it at. */
class RefusedWrite(reason: String = CANT_DO_THAT) : Exception(reason)

internal const val UNREACHABLE = "Couldn't reach the server"
internal const val CANT_DO_THAT = "This server can't do that"
internal const val NO_SUCH_ARTIST = "This artist isn't on the server"

/** A server's own words are shown when they fit on a line; past that they are a log entry. */
private const val SHOWN_MESSAGE_MAX = 60

/**
 * Which of the two a failed write was — see [WriteResult].
 *
 * The same split [Reachability] draws between a server that answered and one
 * that didn't, with one difference: a write that can never land must not count
 * as one that might, so anything not recognised is a refusal. Read as "try
 * again later", a bug that always throws would hold the offline queue shut for
 * good, which is what a refused Subsonic star once did.
 *
 * A 401 is deliberately not called a login problem: Jellyfin answers 401 to a
 * delete the account isn't allowed to make, from a login that works.
 */
internal fun writeResultOf(e: Exception): WriteResult {
    if (e is RefusedWrite) return WriteResult.Refused(e.message ?: CANT_DO_THAT)
    if (e is SubsonicException) {
        // A portal's or a proxy's page: the server itself was never asked.
        if (e.notJson) return WriteResult.Unreachable(UNREACHABLE)
        if (e.endpointMissing) return WriteResult.Refused(CANT_DO_THAT)
        when (e.code) {
            40, 41, 44 -> return WriteResult.Refused("The server refused the login")
            50 -> return WriteResult.Refused("The server doesn't allow that")
            70 -> return WriteResult.Refused("The server doesn't have that")
        }
    }
    if (e is java.io.IOException || e is kotlinx.serialization.SerializationException) {
        return WriteResult.Unreachable(UNREACHABLE)
    }
    val status = when (e) {
        is SubsonicException -> e.status
        is PlexException -> e.status
        is JellyfinException -> e.status
        else -> null
    }
    return when (status) {
        // Cloudflare's own codes for an origin that isn't answering behind it.
        in 520..529 -> WriteResult.Unreachable("Your server isn't responding ($status)")
        in 500..599 -> WriteResult.Unreachable("The server had an error ($status)")
        401, 403 -> WriteResult.Refused("The server doesn't allow that")
        404 -> WriteResult.Refused("The server doesn't have that")
        in 400..499 -> WriteResult.Refused("The server said $status")
        else -> WriteResult.Refused(
            e.message?.takeIf { it.isNotBlank() && it.length < SHOWN_MESSAGE_MAX }
                ?: if (e is SubsonicException) "The server said no" else "Couldn't save",
        )
    }
}

/** Runs one write and says how it went. Cancellation is not a failure, and goes straight through. */
internal suspend fun attemptWrite(block: suspend () -> Unit): WriteResult =
    try {
        block()
        WriteResult.Saved
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        writeResultOf(e)
    }

interface MusicServer {

    /** Throws if the server isn't reachable or the credentials are wrong. */
    suspend fun ping()

    /**
     * What this particular server implements, asked of it — see [ServerFeatures].
     *
     * Only Subsonic needs it: its servers range from a full Navidrome to
     * Bandcamp, which answers a third of the API, and the protocol has no way
     * to ask which. Plex and Jellyfin are one implementation each, so what they
     * do is known from the kind alone and [known] comes back untouched.
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
     * synced — so without this, both stayed as first seen for ever. The
     * default is [SongChanges.Unknown]: the server can't say, and the sync
     * walks the library a little at a time instead.
     */
    suspend fun songChangesSince(mark: String?, libraries: List<String?>): SongChanges =
        SongChanges.Unknown(mark = null)

    /** Server-side artist ids, for the star calls that need one. */
    suspend fun getArtistIndex(musicFolderId: String? = null): List<ArtistRef> = emptyList()

    /**
     * An artist's best-known songs. Empty where the server doesn't rank them.
     *
     * A [count] of 0 means "however many the server thinks sensible" — it is not
     * a request for none, and an implementation must not pass it straight
     * through as a limit.
     */
    suspend fun getTopSongs(artistName: String, count: Int = 0): List<Track> = emptyList()

    /**
     * Songs to follow on from this one: a radio seeded by a track.
     *
     * Navidrome answers from its Last.fm agent — the track's artist and artists
     * like them — Plex from the station it builds for the track, and Jellyfin
     * from its instant mix. Empty where
     * the server has no such idea, or nothing to say about this track; the
     * caller tells the user so rather than playing something else instead. A
     * [count] of 0 means the server's own default, as for [getTopSongs].
     */
    suspend fun getSimilarSongs(songId: String, count: Int = 0): List<Track> = emptyList()

    // --- Media ---------------------------------------------------------------

    fun streamUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = true,
        /**
         * A caller-generated id for the playback this stream belongs to,
         * never shared with a download. Optional, and ignored by servers with
         * no idea of a session (Subsonic); Plex uses it to tie the stream to
         * the session its timeline reports, and to keep a download alongside
         * it from tearing it down — see the comment on [PlexClient.streamUrl].
         */
        sessionId: String? = null,
    ): String

    /**
     * The same, for a caller that has the whole track.
     *
     * Subsonic needs nothing but the id, so this is the id call by default. Plex
     * keeps the path to the file on the track ([Track.streamPath]) and can only
     * serve the original bytes when it has it — which is why the overload exists
     * at all. Prefer it wherever a [Track] is already in hand.
     */
    fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = true,
        sessionId: String? = null,
    ): String = streamUrl(track.id, format, timeOffsetSeconds, estimateContentLength, sessionId)

    /**
     * Where to fetch a *copy* from, as opposed to something to play now.
     *
     * The same URL for most servers, which serve a whole file either way. Plex
     * is the exception and the reason this exists: its transcoder has a
     * streaming mode and a download mode, and asking the streaming one for a
     * file you mean to keep gets you a live segmented encode with no header
     * stating how long it is — which the player then has to guess at, wrongly,
     * for the life of the file. See [PlexClient].
     */
    fun downloadUrl(track: Track, format: StreamFormat): String =
        streamUrl(track, format, estimateContentLength = false)

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
     * changing song as it goes, rather than one per song. Read by the
     * playback controller, which hands out the ids — see
     * PlaybackController.sessionIdFor.
     *
     * Plex does: its dashboard lists a session per id, and each counts
     * against a shared user's "remote streams allowed", so an id per song
     * showed a new entry every song and, at one stream per user, had the
     * next song refused while the last one's session was still listed.
     * Jellyfin keeps its PlaySessionId per song, and Subsonic has no
     * sessions at all.
     */
    val oneSessionPerPlayer: Boolean get() = false

    fun coverArtUrl(coverArtId: String?): String?

    /**
     * The same cover, asked for no larger than [maxSizePx] on its long edge.
     *
     * Servers keep album art at whatever size it came in at — Plex's is often
     * three thousand pixels square and several megabytes — and a phone has no
     * use for any of it beyond the screen's own width. Asking for the original
     * once per row is a lot of bytes to throw away, and it is felt most exactly
     * where it hurts most: a grid, on a connection that leaves the house.
     *
     * Defaults to the full-size URL, for a server that can't resize. The caller
     * is expected to fall back to that anyway if this one doesn't answer — see
     * ArtworkLoader.fetch.
     */
    fun coverArtUrl(coverArtId: String?, maxSizePx: Int): String? = coverArtUrl(coverArtId)

    /**
     * The formats this server will actually deliver.
     *
     * Declared by each implementation rather than assumed by the app, because
     * only the code that builds the URLs knows what comes back down them. A
     * server that is asked for something it cannot produce does not say so — it
     * sends what it has and lets the client believe it got what it wanted — so
     * the list of options the user picks from has to come from here, and a
     * format that is missing from it is one the app must never request.
     */
    val streamFormats: List<StreamFormat>

    /** Words for a song, timed where the server has them. */
    suspend fun getLyrics(songId: String): Lyrics? = null

    // --- Writing back --------------------------------------------------------

    /**
     * [submission] false says "this is playing now", true says "this was played".
     *
     * Two different statements, and servers treat them as such: the first is
     * what Navidrome shows as now-playing and hands to its agents when a track
     * starts, the second is the play itself and is what reaches a scrobbling
     * service. A client that only ever sends the second announces nothing while
     * it plays, and logs a play for every track it was skipped past.
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
    suspend fun getPlaylist(id: String): Playlist =
        Playlist(id = id, name = "", coverArtId = null, createdAt = 0L, updatedAt = 0L, trackIds = emptyList())
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
     * song twice and only one of them is meant.
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

    /** Whether the edit landed — see [removeFromPlaylistAt]. */
    suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>): Boolean = false
}

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

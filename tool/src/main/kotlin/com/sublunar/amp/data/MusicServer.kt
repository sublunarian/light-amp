package com.sublunar.amp.data

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
 * out what it can't. The methods here that only some servers implement are
 * marked, and default to doing nothing — a server that can't star a track
 * shouldn't need to write a stub saying so.
 */
interface MusicServer {

    /** Throws if the server isn't reachable or the credentials are wrong. */
    suspend fun ping()

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
     * into the music folder into something this app can see. Returns false where
     * a server has no such call, or where this token isn't allowed to make it.
     */
    suspend fun startServerScan(musicFolderId: String? = null): Boolean = false

    /** True while a [startServerScan] is still running. */
    suspend fun serverScanning(musicFolderId: String? = null): Boolean = false

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

    // --- Media ---------------------------------------------------------------

    fun streamUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = true,
        /**
         * A caller-generated id for *this* playback, distinct from any other
         * stream or download in flight. Optional, and ignored by servers with
         * no idea of a session (Subsonic); Plex uses it to tell one playing
         * track from another so a download alongside it can't tear it down —
         * see the comment on [PlexClient.streamUrl].
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
     */
    suspend fun scrobble(songId: String, atMs: Long? = null, submission: Boolean = true) = Unit

    /**
     * Tell the server this session is still going, so something watching active
     * sessions — Plex's dashboard, chiefly — has a "Now Playing" to show.
     *
     * [sessionId] ties a run of calls to one playback the way [streamUrl]'s
     * parameter of the same name does; it should be the same string handed to
     * that call for the stream this is reporting on. A server with no notion of
     * a live session (Subsonic) does nothing with any of this.
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

    /** 1–5, or 0 to clear. False when the server wouldn't take it. */
    suspend fun setRating(id: String, stars: Int): Boolean = false

    suspend fun starSong(songId: String) = Unit
    suspend fun unstarSong(songId: String) = Unit
    suspend fun starAlbum(albumId: String) = Unit
    suspend fun unstarAlbum(albumId: String) = Unit
    suspend fun starArtist(artistId: String) = Unit
    suspend fun unstarArtist(artistId: String) = Unit

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
     */
    suspend fun createPlaylist(name: String, songIds: List<String> = emptyList()): String? = null
    suspend fun renamePlaylist(id: String, name: String) = Unit
    suspend fun deletePlaylist(id: String) = Unit
    suspend fun addToPlaylist(id: String, songId: String) = Unit
    suspend fun removeFromPlaylistAt(id: String, index: Int) = Unit
    suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>) = Unit
}

/** What [MusicServer.reportTimeline] tells the server a session is doing. */
enum class TimelineState {
    PLAYING,
    PAUSED,
    STOPPED,
    BUFFERING,
}

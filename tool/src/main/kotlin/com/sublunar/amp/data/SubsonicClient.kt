package com.sublunar.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.time.Instant
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Connection + credentials for a Navidrome/Subsonic server. */
data class SubsonicConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    /** Sent as `c=`; null keeps the app's default ("amp"). */
    val clientName: String? = null,
) {
    companion object {
        /**
         * Base URLs to try for a raw input, in order. When the user omits a
         * scheme, prefer HTTPS and fall back to HTTP (for LAN servers). Drops a
         * trailing slash and a `/rest` suffix.
         */
        fun candidates(raw: String): List<String> {
            var url = raw.trim().trimEnd('/')
            url = url.replace(Regex("/rest$", RegexOption.IGNORE_CASE), "")
            return if (url.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) {
                listOf(url)
            } else {
                listOf("https://$url", "http://$url")
            }
        }
    }
}

/** [status] is the HTTP status when the server gave one — see Reachability, which reads a 5xx as "not there". */
/** An id no server can hold, for a probe that must not touch anything real. */
private const val PROBE_ID = "amp-capability-probe"

class SubsonicException(
    message: String,
    val status: Int? = null,
    /**
     * The server answered, but not in Subsonic's envelope — what Bandcamp sends
     * for an endpoint it doesn't have (`{"error":true,"error_message":"bad
     * version"}`), where a full server would say `status: failed` inside one.
     */
    val notSubsonic: Boolean = false,
) : Exception(message) {
    /**
     * The server has no such endpoint — as opposed to having one and refusing.
     *
     * A Subsonic reply of any kind, an error included, means the endpoint is
     * there; this is the other case: an answer that isn't Subsonic at all, or
     * the HTTP status of a route that doesn't exist. Nothing about the network
     * or the login reaches here — those throw something else entirely.
     */
    val endpointMissing: Boolean get() = notSubsonic || status == 404 || status == 501
}

/**
 * Navidrome / Subsonic API client. Ported from the React Native `navidrome.ts`.
 * Authenticates with the Subsonic token scheme (md5(password + salt)) and
 * returns domain models. Coroutine cancellation propagates to the HTTP call.
 */
class SubsonicClient(val config: SubsonicConfig) : MusicServer {

    private val http = NetworkGate.httpClient {
        expectSuccess = false
    }

    /**
     * A second, small client for the two calls a person is waiting on — the
     * reachability ping and the stream decision. A separate client is a
     * separate request queue: on the shared one they stood behind a launch
     * sync's hundreds of requests (five at a time per host), and a ping that
     * waited out its turn there was read as "the server is gone" — which
     * narrowed the library to downloads in the very minutes the user was
     * trying to start a song. Built on first use; most sessions never need it
     * more than a handful of times.
     */
    private val quickClient = lazy { NetworkGate.httpClient(isolated = true) { expectSuccess = false } }
    private val quick get() = quickClient.value

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val authQuery: String = run {
        val salt = randomSalt()
        val token = md5Hex(config.password + salt)
        buildString {
            append("u=").append(enc(config.username))
            append("&t=").append(token)
            append("&s=").append(salt)
            append("&v=").append(API_VERSION)
            append("&c=").append(enc(config.clientName?.ifBlank { null } ?: CLIENT_NAME))
            append("&f=json")
        }
    }

    override fun close() {
        http.close()
        if (quickClient.isInitialized()) quick.close()
    }

    // --- URL builders --------------------------------------------------------

    private fun restUrl(endpoint: String, params: List<Pair<String, String>>): String =
        buildString {
            append(config.baseUrl).append("/rest/").append(endpoint).append(".view?")
            append(authQuery)
            for ((key, value) in params) {
                append('&').append(key).append('=').append(enc(value))
            }
        }

    /**
     * A copy to keep, rather than something to play.
     *
     * At original quality that is `download`, which is the endpoint for exactly
     * this and hands back the stored file untouched. `stream` would serve the
     * same bytes, but it is a *playback* endpoint — a Subsonic server is
     * entitled to treat a request to it as a play, and filling the phone with a
     * library should not rewrite its play counts.
     *
     * At any other quality there is nothing to switch to: `download` never
     * transcodes, so the streaming endpoint is the only way to a smaller file.
     * That is sound here in a way it was not on Plex — Navidrome and friends
     * transcode mp3 at a constant bitrate, where a player's size ÷ bitrate
     * fallback is arithmetic rather than a guess, so the missing duration
     * header costs nothing. A server configured for a *variable* rate would
     * land in Plex's hole, and this is the seam where that would be fixed.
     */
    override fun downloadUrl(track: Track, format: StreamFormat): String =
        if (format == StreamFormat.RAW) {
            restUrl("download", listOf("id" to track.id))
        } else {
            streamUrl(track, format, estimateContentLength = false)
        }

    /** Streaming URL for a song in the given format, with optional server-side time seek. */
    override fun streamUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        /**
         * Ask the server to declare an estimated Content-Length.
         *
         * On for playback, where it lets the player byte-seek within a transcoded
         * stream instead of reloading from 0:00. **Off for downloads:** the estimate
         * is only an estimate, and when the real encode comes out shorter the client
         * sits waiting for bytes that never arrive and dies with
         * `ProtocolException: unexpected end of stream` partway through the file.
         * A download would rather read to EOF and take whatever length is real.
         */
        estimateContentLength: Boolean,
        /** Subsonic has no session concept; unused here. */
        sessionId: String?,
    ): String {
        val params = mutableListOf("id" to songId)
        if (format == StreamFormat.RAW) {
            params.add("format" to "raw")
        } else {
            params.add("format" to format.id)
            format.maxBitRate?.let { params.add("maxBitRate" to it.toString()) }
            if (estimateContentLength) params.add("estimateContentLength" to "true")
        }
        if (timeOffsetSeconds > 0) {
            params.add("timeOffset" to timeOffsetSeconds.toString())
        }
        return restUrl("stream", params)
    }

    override fun coverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        return restUrl("getCoverArt", listOf("id" to coverArtId))
    }

    /** `getCoverArt` resizes server-side when given a size: the square's edge. */
    override fun coverArtUrl(coverArtId: String?, maxSizePx: Int): String? {
        if (coverArtId.isNullOrBlank()) return null
        if (maxSizePx <= 0) return coverArtUrl(coverArtId)
        return restUrl("getCoverArt", listOf("id" to coverArtId, "size" to maxSizePx.toString()))
    }

    // --- Requests ------------------------------------------------------------

    private suspend fun request(
        endpoint: String,
        params: List<Pair<String, String>> = emptyList(),
        via: HttpClient = http,
    ): SubsonicBody {
        val response = via.get(restUrl(endpoint, params))
        if (!response.status.isSuccess()) {
            throw SubsonicException("Server returned HTTP ${response.status.value}.", response.status.value)
        }
        val text = response.bodyAsText()
        // Not JSON at all (a proxy's HTML, a 200 from a server that routes
        // unknown paths to a page) is as much "not a Subsonic answer" as JSON
        // in the wrong shape, and reads the same to everything above.
        val envelope = try {
            json.decodeFromString<SubsonicEnvelope>(text)
        } catch (e: SerializationException) {
            throw SubsonicException("Unexpected response from server.", notSubsonic = true)
        }
        val body = envelope.response
            ?: throw SubsonicException("Unexpected response from server.", notSubsonic = true)
        if (body.status != "ok") {
            throw SubsonicException(body.error?.message ?: "Request failed.")
        }
        return body
    }

    /** Verify credentials by pinging the server. Throws on failure. */
    override suspend fun ping() {
        request("ping", via = quick)
    }

    /**
     * What this server implements, asked once and kept — see [ServerFeatures].
     *
     * Every probe is a well-formed request that cannot change anything and does
     * not need to succeed, because only the shape of the answer is read. The
     * radio and download probes name a song the library already holds and only
     * read; the ratings probe rates a song id no server can have, so a server
     * that implements it answers "not found" — proof enough — and has nothing
     * to act on. Nothing is asked of a server whose [known] answers still match
     * what it calls itself.
     *
     * Returns [known] unchanged when there is nothing new to learn, and null
     * when the server couldn't be reached — an unreachable server must never
     * read as one missing everything, so the ping goes first and a throw from
     * anywhere here leaves what was known alone.
     */
    override suspend fun probeFeatures(known: ServerFeatures?, sampleTrackId: String?): ServerFeatures? {
        // Doubles as the guard the whole thing rests on: the address and login
        // are working right now, so anything answering "no such endpoint" below
        // is the server saying so, not a proxy in front of it or a dead link.
        val server = request("ping", via = quick)
        val tag = listOfNotNull(server.version, server.type, server.serverVersion)
            .joinToString("/")
            .ifBlank { "unknown" }
        val asked = known != null &&
            known.checkedVersion == tag &&
            known.generation == ServerFeatures.GENERATION
        if (asked) return known

        val missing = mutableListOf<String>()
        if (!implements(ServerFeatures.SCAN_STATUS, emptyList())) {
            missing += ServerFeatures.SCAN_STATUS
        }
        if (!implements(ServerFeatures.SET_RATING, listOf("id" to PROBE_ID, "rating" to "1"))) {
            missing += ServerFeatures.SET_RATING
        }
        if (sampleTrackId != null) {
            val similar = listOf("id" to sampleTrackId, "count" to "1")
            if (!implements(ServerFeatures.SIMILAR_SONGS, similar)) {
                missing += ServerFeatures.SIMILAR_SONGS
            }
            if (!implementsDownload(sampleTrackId)) missing += ServerFeatures.DOWNLOAD
        }
        android.util.Log.i("AmpSync", "features of $tag: missing ${missing.ifEmpty { listOf("nothing") }}")
        return ServerFeatures(
            checkedVersion = tag,
            missing = missing,
            generation = ServerFeatures.GENERATION,
        )
    }

    /** True when the server has this endpoint at all, whatever it answers. */
    private suspend fun implements(endpoint: String, params: List<Pair<String, String>>): Boolean =
        try {
            request(endpoint, params, via = quick)
            true
        } catch (e: SubsonicException) {
            !e.endpointMissing
        }

    /**
     * `download` without downloading: the first byte, and even that is refused
     * by servers that would rather send the whole file, which is why the reply's
     * own type settles it. Bandcamp answers this one in JSON, which no download
     * of a song ever is.
     */
    private suspend fun implementsDownload(trackId: String): Boolean =
        // prepareGet/execute rather than get(): the headers answer the question,
        // and this way the body is never read — a server that ignores the range
        // and starts sending a whole song is hung up on instead of downloaded.
        quick.prepareGet(restUrl("download", listOf("id" to trackId))) {
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { response ->
            when {
                response.status.value == 404 || response.status.value == 501 -> false
                else -> response.contentType()?.match("application/json") != true
            }
        }

    /** The server's libraries (Navidrome exposes each as a music folder). */
    override suspend fun getMusicFolders(): List<MusicFolder> {
        val body = request("getMusicFolders")
        return body.musicFolders?.musicFolder.orEmpty().mapNotNull { dto ->
            val id = dto.id?.content ?: return@mapNotNull null
            MusicFolder(id = id, name = dto.name ?: id)
        }
    }

    private fun musicFolderParam(musicFolderId: String?): List<Pair<String, String>> =
        if (musicFolderId.isNullOrBlank()) emptyList() else listOf("musicFolderId" to musicFolderId)

    /** Every album in the given library (or all libraries when null), paged by name. */
    override suspend fun getAllAlbums(musicFolderId: String?): List<Album> {
        val out = mutableListOf<Album>()
        var offset = 0
        while (true) {
            val body = request(
                "getAlbumList2",
                listOf(
                    "type" to "alphabeticalByName",
                    "size" to ALBUM_PAGE_SIZE.toString(),
                    "offset" to offset.toString(),
                ) + musicFolderParam(musicFolderId),
            )
            val albums = body.albumList2?.album.orEmpty()
            albums.forEach { out.add(it.toAlbum()) }
            if (albums.size < ALBUM_PAGE_SIZE) break
            offset += ALBUM_PAGE_SIZE
        }
        return out
    }

    /** An album's songs. */
    override suspend fun getAlbumTracks(albumId: String): List<Track> {
        val body = request("getAlbum", listOf("id" to albumId))
        // Songs carry the same appended comment in their album tag, and unlike the
        // album they have no version field of their own — so clean them with the
        // parent album's, which this response already includes.
        val albumName = body.album?.let { albumTitle(it.name ?: it.title, it.version) }
        // The record's own artist, taken from the same parent. AlbumID3.artist
        // is the album artist by definition, where a song Child's albumArtist is
        // whatever the file happened to carry — so this is the authoritative
        // answer for every track on the album, and the one the app files them
        // under. Without it a track whose file has no ALBUMARTIST tag fell back
        // to its own artist, which is what gave every guest on a "feat." credit
        // a row of their own in the Artists list.
        val albumArtist = body.album?.let { a ->
            // Joined here, with the separator the app splits on, so a record
            // credited to more than one artist comes apart again cleanly —
            // see Track.albumArtistNames.
            a.artists.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("; ")
                ?: a.displayArtist?.takeIf { it.isNotBlank() }
                ?: a.artist
        }
        // The album's own cover for every track on it — see toTrack.
        return body.album?.song.orEmpty().map { it.toTrack(albumName, albumArtist, body.album?.coverArt) }
    }

    /**
     * What this account has liked, asked for with `getStarred2` — and, only
     * where the server has no such endpoint, the older `getStarred`.
     *
     * Bandcamp is the one found so far: it implements `getStarred`, `star` and
     * `unstar` but not `getStarred2`, and the sync asks for likes before
     * anything else, so without this a Bandcamp library never loaded. Only the
     * not-Subsonic answer falls back; a full server's own error (a failed
     * status inside the envelope) is still an error, so Navidrome and gonic
     * take the path they always did. The two replies differ only in the shape
     * of their album entries, and the ids are all that is read here.
     */
    override suspend fun getStarred(musicFolderId: String?): Starred {
        val params = musicFolderParam(musicFolderId)
        val starred = try {
            request("getStarred2", params).starred2
        } catch (e: SubsonicException) {
            if (!e.notSubsonic) throw e
            request("getStarred", params).starred
        }
        val songs = starred?.song.orEmpty().map { it.id }.toSet()
        val albums = starred?.album.orEmpty().map { it.id }.toSet()
        val artists = starred?.artist.orEmpty().mapNotNull { it.name }.toSet()
        return Starred(songIds = songs, albumIds = albums, artistNames = artists)
    }

    /** Every artist the server knows, flattened out of the alphabetical index. */
    override suspend fun getArtistIndex(musicFolderId: String?): List<ArtistRef> {
        val body = request("getArtists", musicFolderParam(musicFolderId))
        return body.artists?.index.orEmpty().flatMap { it.artist }.mapNotNull { dto ->
            // The artist's own id doubles as a cover id: Navidrome answers
            // getCoverArt for one with the artist's picture. A server that
            // doesn't simply has no art to show, which the loader handles.
            ArtistRef(
                id = dto.id,
                name = dto.name ?: return@mapNotNull null,
                imageId = dto.id,
            )
        }
    }

    /**
     * An artist's most popular songs. Navidrome answers this from its Last.fm
     * agent, matched back onto the local library, so it needs Last.fm configured
     * server-side; it returns an empty list rather than failing when it isn't.
     */
    override val streamFormats: List<StreamFormat> get() = STREAM_FORMATS

    override suspend fun startServerScan(musicFolderId: String?): ScanRequest =
        try {
            request("startScan")
            ScanRequest.STARTED
        } catch (e: SubsonicException) {
            // Told apart at the moment of asking, so the first sync of a new
            // server is already right rather than waiting on what the probes
            // learn at the end of it — see ServerFeatures.
            if (e.endpointMissing) ScanRequest.ABSENT else ScanRequest.REFUSED
        } catch (e: Exception) {
            ScanRequest.REFUSED
        }

    override suspend fun serverScanning(musicFolderId: String?): Boolean =
        runCatching { request("getScanStatus").scanStatus?.scanning }.getOrNull() ?: false

    override suspend fun getTopSongs(artistName: String, count: Int): List<Track> {
        return try {
            val body = request(
                "getTopSongs",
                // Omitted rather than sent as 0: Subsonic reads `count` as the
                // number of songs to return, so a literal 0 asks the server for
                // nothing and it obliges. Leaving it out gets the server's own
                // default, which is what "unspecified" should mean.
                listOf("artist" to artistName) +
                    if (count > 0) listOf("count" to count.toString()) else emptyList(),
            )
            body.topSongs?.song.orEmpty().map { it.toTrack() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * `getSimilarSongs` takes the song's own id — checked against the public
     * demo — and Navidrome resolves it to the artist before asking its Last.fm
     * agent for artists like them. With no agent it still answers from that
     * artist's own catalogue, so the radio narrows rather than vanishes.
     */
    override suspend fun getSimilarSongs(songId: String, count: Int): List<Track> {
        return try {
            val body = request(
                "getSimilarSongs",
                // Omitted rather than sent as 0 — see getTopSongs.
                listOf("id" to songId) +
                    if (count > 0) listOf("count" to count.toString()) else emptyList(),
            )
            body.similarSongs?.song.orEmpty().map { it.toTrack() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun starSong(songId: String) = Unit.also { request("star", listOf("id" to songId)) }
    override suspend fun unstarSong(songId: String) = Unit.also { request("unstar", listOf("id" to songId)) }
    override suspend fun starAlbum(albumId: String) = Unit.also { request("star", listOf("albumId" to albumId)) }
    override suspend fun unstarAlbum(albumId: String) = Unit.also { request("unstar", listOf("albumId" to albumId)) }
    override suspend fun starArtist(artistId: String) =
        Unit.also { request("star", listOf("artistId" to artistId)) }
    override suspend fun unstarArtist(artistId: String) =
        Unit.also { request("unstar", listOf("artistId" to artistId)) }

    /**
     * Register a play, optionally at the time it actually happened.
     *
     * [atMs] matters for plays replayed after an outage: without it Navidrome
     * timestamps the scrobble on arrival, and a week of offline listening lands
     * in one minute of history. Throws rather than swallowing, so the caller can
     * decide whether to keep the play for a later attempt.
     */
    override suspend fun scrobble(songId: String, atMs: Long?, submission: Boolean) {
        val params = mutableListOf("id" to songId, "submission" to submission.toString())
        atMs?.let { params.add("time" to it.toString()) }
        request("scrobble", params)
    }

    /**
     * Store the queue server-side, where another client can find it.
     *
     * The ids repeat as one parameter each, which is how Subsonic takes a list.
     * An empty queue would be read as "clear it", so the caller decides that
     * explicitly rather than arriving here with nothing by accident.
     */
    override suspend fun savePlayQueue(
        trackIds: List<String>,
        currentId: String?,
        positionMs: Long,
    ) {
        if (trackIds.isEmpty()) return
        val params = trackIds.map { "id" to it } +
            listOfNotNull(currentId?.let { "current" to it }) +
            listOf("position" to positionMs.coerceAtLeast(0L).toString())
        request("savePlayQueue", params)
    }

    override suspend fun getPlayQueue(): SavedQueue? {
        // A server that has never been given a queue answers with an error
        // rather than an empty one, and that is not a failure worth raising to
        // the caller — it just means there is nothing waiting.
        val queue = runCatching { request("getPlayQueue").playQueue }.getOrNull() ?: return null
        val ids = queue.entry.map { it.id }
        if (ids.isEmpty()) return null
        return SavedQueue(ids, queue.currentId, queue.position ?: 0L)
    }

    /**
     * Set a song's or album's star rating on the server, 0–5.
     *
     * Navidrome treats 0 as "remove the rating"; Subsonic's `setRating` takes it
     * directly, so no separate unrate call is needed. Returns false when the
     * server refused, so the caller can avoid caching a rating that didn't stick.
     */
    override suspend fun setRating(id: String, stars: Int): Boolean = runCatching {
        request("setRating", listOf("id" to id, "rating" to stars.coerceIn(0, 5).toString()))
        true
    }.getOrDefault(false)

    override suspend fun getLyrics(songId: String): Lyrics? {
        return try {
            val body = request("getLyricsBySongId", listOf("id" to songId))
            val structured = body.lyricsList?.structuredLyrics.orEmpty()

            val synced = structured.firstOrNull { entry ->
                entry.synced && entry.line.any { it.start != null }
            }
            if (synced != null) {
                val lines = synced.line
                    .filter { !it.value.isNullOrBlank() && it.start != null }
                    .map { LyricLine(timeMs = it.start, text = it.value.orEmpty().trim()) }
                    .sortedBy { it.timeMs ?: 0L }
                if (lines.isNotEmpty()) return Lyrics(lines, synced = true)
            }

            val plain = structured.firstOrNull { it.line.isNotEmpty() }
            if (plain != null) {
                val lines = plain.line
                    .mapNotNull { it.value?.trim() }
                    .filter { it.isNotEmpty() }
                    .map { LyricLine(timeMs = null, text = it) }
                if (lines.isNotEmpty()) return Lyrics(lines, synced = false)
            }

            val legacy = body.lyrics?.value
            if (!legacy.isNullOrBlank()) {
                val lines = legacy.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { LyricLine(timeMs = null, text = it) }
                if (lines.isNotEmpty()) return Lyrics(lines, synced = false)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // --- Playlists -----------------------------------------------------------

    override suspend fun getPlaylists(musicFolderId: String?): List<Playlist> {
        val body = request("getPlaylists", musicFolderParam(musicFolderId))
        return body.playlists?.playlist.orEmpty().map { it.toPlaylistSummary() }
    }

    override suspend fun getPlaylist(id: String): Playlist {
        val body = request("getPlaylist", listOf("id" to id))
        val dto = body.playlist ?: throw SubsonicException("Playlist not found.")
        return dto.toPlaylistDetail()
    }

    override suspend fun createPlaylist(name: String, songIds: List<String>): String? {
        val body = request(
            "createPlaylist",
            listOf("name" to name.trim()) + songIds.map { "songId" to it },
        )
        return body.playlist?.id
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        request("updatePlaylist", listOf("playlistId" to id, "name" to name.trim()))
    }

    override suspend fun deletePlaylist(id: String) {
        request("deletePlaylist", listOf("id" to id))
    }

    override suspend fun addToPlaylist(id: String, songId: String) {
        request("updatePlaylist", listOf("playlistId" to id, "songIdToAdd" to songId))
    }

    /**
     * One request however many go: `songIndexToRemove` repeats, each an index
     * into the list as it stands. Subsonic removes by position and nothing
     * else, so the server's copy is read first to see the expected songs are
     * still where the positions say.
     */
    override suspend fun removeFromPlaylistAt(id: String, at: Map<Int, String>): Boolean {
        if (at.isEmpty()) return true
        val known = getPlaylist(id).trackIds
        if (at.any { (index, songId) -> known.getOrNull(index) != songId }) return false
        val params = mutableListOf("playlistId" to id)
        at.keys.forEach { params.add("songIndexToRemove" to it.toString()) }
        request("updatePlaylist", params)
        return true
    }

    /**
     * Subsonic has no reorder; overwrite the playlist with the full ordered id
     * list. Which is why the count is checked against the server's own copy
     * first: an overwrite with a partial list doesn't reorder a playlist, it
     * deletes everything the list left out.
     */
    override suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>): Boolean {
        val known = getPlaylist(id).trackIds
        if (known.size != orderedSongIds.size) return false
        val params = mutableListOf("playlistId" to id)
        orderedSongIds.forEach { params.add("songId" to it) }
        request("createPlaylist", params)
        return true
    }

    // --- Mapping -------------------------------------------------------------

    private fun AlbumDto.toAlbum(): Album = Album(
        id = id,
        title = albumTitle(name ?: title, version),
        artist = artist ?: "Unknown Artist",
        coverArtId = coverArt ?: id,
        durationMs = (duration ?: 0).toLong() * 1000L,
        songCount = songCount ?: 0,
        year = year,
        releaseDate = releaseDateKey(),
        createdMs = parseInstantMs(created),
        playCount = playCount ?: 0,
        lastPlayedMs = parseInstantMs(played),
        rating = userRating ?: 0,
        genre = genre.orEmpty(),
    )

    /**
     * The album's own name, with Navidrome's appended release comment removed.
     *
     * Navidrome sends the MusicBrainz disambiguation both as [AlbumDto.version]
     * and glued onto the end of the name, so "Alive 2007" arrives as
     * "Alive 2007 (printed in EU)". Only an exact match for the version string is
     * stripped — a trailing parenthesis is not evidence by itself, or "Apostrophe
     * (’)" and "Enter the Wu-Tang (36 Chambers)" would lose part of their real
     * titles. No version, or a name that doesn't end with it, means no change.
     */
    private fun albumTitle(raw: String?, version: String?): String {
        val name = raw?.trim().orEmpty().ifEmpty { return "Unknown Album" }
        val comment = version?.trim().orEmpty()
        if (comment.isEmpty()) return name
        for (suffix in listOf(" ($comment)", " [$comment]", " $comment")) {
            if (name.endsWith(suffix, ignoreCase = true)) {
                return name.dropLast(suffix.length).trim().ifEmpty { name }
            }
        }
        return name
    }

    /**
     * Release date flattened to a sortable YYYYMMDD number, 0 when unknown.
     * Prefers the *original* release date so a discography reads chronologically
     * rather than by remaster date; missing month/day sort before the same year's
     * dated releases.
     */
    private fun AlbumDto.releaseDateKey(): Long {
        val date = originalReleaseDate?.takeIf { it.year != null }
            ?: releaseDate?.takeIf { it.year != null }
        val y = date?.year ?: year ?: return 0L
        return y * 10_000L + (date?.month ?: 0) * 100L + (date?.day ?: 0)
    }

    private fun SongDto.toTrack(
        albumName: String? = null,
        albumArtistName: String? = null,
        /**
         * The parent album's cover id, where the caller has the album. Preferred
         * over the song's own: Navidrome hands every file an `mf-` id of its own
         * that resolves to the same sleeve, and keyed by it the cache held one
         * copy per song — thousands of files for a few hundred covers.
         */
        albumCoverArt: String? = null,
    ): Track {
        val coverId = albumCoverArt ?: coverArt ?: albumId ?: id
        return Track(
            id = id,
            title = title ?: "Unknown Title",
            artist = artist ?: "Unknown Artist",
            album = albumName ?: album ?: "Unknown Album",
            // The album's answer first — see getAlbumTracks. The track's own
            // artist is the last resort rather than the second, because it is
            // the one value here that is known not to be an album artist.
            albumArtist = albumArtistName
                ?: displayAlbumArtist?.takeIf { it.isNotBlank() }
                ?: albumArtist
                ?: artist
                ?: "Unknown Artist",
            albumId = albumId,
            coverArtId = coverId,
            durationMs = (duration ?: 0).toLong() * 1000L,
            trackNumber = track,
            discNumber = discNumber,
            year = year,
            playCount = playCount ?: 0,
            lastPlayedMs = parseInstantMs(played),
            liked = starred != null,
            rating = userRating ?: 0,
            genre = genre.orEmpty(),
            composer = displayComposer?.takeIf { it.isNotBlank() } ?: composer.orEmpty(),
            // Track gain first; a file tagged only album-wise still normalises.
            gainDb = replayGain?.trackGain ?: replayGain?.albumGain,
        )
    }

    private fun PlaylistDto.toPlaylistSummary(): Playlist = Playlist(
        id = id,
        name = name ?: "Playlist",
        coverArtId = coverArt,
        createdAt = parseInstantMs(created),
        updatedAt = parseInstantMs(changed),
        trackIds = emptyList(),
    )

    private fun PlaylistDto.toPlaylistDetail(): Playlist = Playlist(
        id = id,
        name = name ?: "Playlist",
        coverArtId = coverArt,
        createdAt = parseInstantMs(created),
        updatedAt = parseInstantMs(changed),
        trackIds = entry.map { it.id },
    )

    /** Songs of a playlist, in order, as full tracks. */
    override suspend fun getPlaylistTracks(id: String): List<Track> {
        val body = request("getPlaylist", listOf("id" to id))
        return body.playlist?.entry.orEmpty().map { it.toTrack() }
    }

    companion object {
        /**
         * Everything the Subsonic API's `format` parameter defines, which a
         * server is expected to transcode to on demand. A deployment missing a
         * transcoder for one of these answers with its default instead — that is
         * a server-side gap, and the honest place to close it is the server.
         */
        val STREAM_FORMATS: List<StreamFormat> = StreamFormat.entries.toList()

        /**
         * Default `c=` value, when a source has no [SubsonicConfig.clientName]
         * of its own. Navidrome registers a Player under whatever this is.
         */
        private const val CLIENT_NAME = "amp"
        private const val API_VERSION = "1.16.1"
        private const val ALBUM_PAGE_SIZE = 500

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun randomSalt(): String {
            val chars = "0123456789abcdef"
            return buildString { repeat(16) { append(chars[Random.nextInt(16)]) } }
        }

        private fun parseInstantMs(value: String?): Long {
            if (value.isNullOrBlank()) return 0L
            return try {
                Instant.parse(value).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }
}

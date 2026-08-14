package com.sublunar.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.time.Instant
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

class SubsonicException(message: String) : Exception(message)

/**
 * Navidrome / Subsonic API client. Ported from the React Native `navidrome.ts`.
 * Authenticates with the Subsonic token scheme (md5(password + salt)) and
 * returns domain models. Coroutine cancellation propagates to the HTTP call.
 */
class SubsonicClient(val config: SubsonicConfig) : MusicServer {

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
    }

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

    override fun close() = http.close()

    // --- URL builders --------------------------------------------------------

    private fun restUrl(endpoint: String, params: List<Pair<String, String>>): String =
        buildString {
            append(config.baseUrl).append("/rest/").append(endpoint).append(".view?")
            append(authQuery)
            for ((key, value) in params) {
                append('&').append(key).append('=').append(enc(value))
            }
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

    // --- Requests ------------------------------------------------------------

    private suspend fun request(
        endpoint: String,
        params: List<Pair<String, String>> = emptyList(),
    ): SubsonicBody {
        val response = http.get(restUrl(endpoint, params))
        if (!response.status.isSuccess()) {
            throw SubsonicException("Server returned HTTP ${response.status.value}.")
        }
        val body = json.decodeFromString<SubsonicEnvelope>(response.bodyAsText()).response
            ?: throw SubsonicException("Unexpected response from server.")
        if (body.status != "ok") {
            throw SubsonicException(body.error?.message ?: "Request failed.")
        }
        return body
    }

    /** Verify credentials by pinging the server. Throws on failure. */
    override suspend fun ping() {
        request("ping")
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
        return body.album?.song.orEmpty().map { it.toTrack(albumName) }
    }

    override suspend fun getStarred(musicFolderId: String?): Starred {
        val body = request("getStarred2", musicFolderParam(musicFolderId))
        val songs = body.starred2?.song.orEmpty().map { it.id }.toSet()
        val albums = body.starred2?.album.orEmpty().map { it.id }.toSet()
        val artists = body.starred2?.artist.orEmpty().mapNotNull { it.name }.toSet()
        return Starred(songIds = songs, albumIds = albums, artistNames = artists)
    }

    /** Every artist the server knows, flattened out of the alphabetical index. */
    override suspend fun getArtistIndex(musicFolderId: String?): List<ArtistRef> {
        val body = request("getArtists", musicFolderParam(musicFolderId))
        return body.artists?.index.orEmpty().flatMap { it.artist }.mapNotNull { dto ->
            ArtistRef(id = dto.id, name = dto.name ?: return@mapNotNull null)
        }
    }

    /**
     * An artist's most popular songs. Navidrome answers this from its Last.fm
     * agent, matched back onto the local library, so it needs Last.fm configured
     * server-side; it returns an empty list rather than failing when it isn't.
     */
    override val streamFormats: List<StreamFormat> get() = STREAM_FORMATS

    override suspend fun startServerScan(musicFolderId: String?): Boolean =
        runCatching { request("startScan") }.isSuccess

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

    override suspend fun removeFromPlaylistAt(id: String, index: Int) {
        request("updatePlaylist", listOf("playlistId" to id, "songIndexToRemove" to index.toString()))
    }

    /** Subsonic has no reorder; overwrite the playlist with the full ordered id list. */
    override suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>) {
        val params = mutableListOf("playlistId" to id)
        orderedSongIds.forEach { params.add("songId" to it) }
        request("createPlaylist", params)
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
        compilation = isCompilation ?: compilation ?: false,
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

    private fun SongDto.toTrack(albumName: String? = null): Track {
        val coverId = coverArt ?: albumId ?: id
        return Track(
            id = id,
            title = title ?: "Unknown Title",
            artist = artist ?: "Unknown Artist",
            album = albumName ?: album ?: "Unknown Album",
            albumArtist = albumArtist ?: artist ?: "Unknown Artist",
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
            composer = composer.orEmpty(),
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

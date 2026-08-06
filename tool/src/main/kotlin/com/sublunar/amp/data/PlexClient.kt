package com.sublunar.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * A Plex Media Server's music library, behind the same [MusicServer] interface
 * Navidrome uses.
 *
 * **What Plex can't do**, and so is left out rather than faked (see the
 * capability flags on [MusicSource], which is what the UI actually reads):
 * - **Favourites.** Plex has star ratings but no separate "loved" flag, so
 *   there is nothing for a heart to sync to.
 * - **Popular songs.** No dependable per-artist ranking over the API.
 * - **Lyrics.** Only where a track happens to carry a lyric stream, which most
 *   libraries don't.
 *
 * Everything else — browsing, artwork, streaming, playlists, play counts and
 * star ratings — round-trips to the server, so what you do here shows up in
 * PlexAmp and vice versa.
 */
class PlexClient(
    /** A server connection URI, e.g. `http://192.168.1.10:32400`. */
    private val baseUrl: String,
    private val token: String,
    /** Needed to build the URIs that add items to a playlist. */
    private val machineIdentifier: String = "",
) : MusicServer {

    private val http = HttpClient(OkHttp) { expectSuccess = false }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override fun close() = http.close()

    // --- Requests ------------------------------------------------------------

    private suspend fun fetch(path: String, params: List<Pair<String, String>> = emptyList()): PlexResponse {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val response = http.get(url) { plexHeaders() }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for $path")
        }
        val text = response.bodyAsText()
        return if (text.isBlank()) PlexResponse() else json.decodeFromString(text)
    }

    private suspend fun send(method: String, path: String, params: List<Pair<String, String>> = emptyList()) {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        runCatching {
            when (method) {
                "GET" -> http.get(url) { plexHeaders() }
                "POST" -> http.post(url) { plexHeaders() }
                "PUT" -> http.put(url) { plexHeaders() }
                "DELETE" -> http.delete(url) { plexHeaders() }
                else -> error("unsupported method $method")
            }
        }
    }

    /** Like [send], but says whether the server took it. */
    private suspend fun sendChecked(path: String, params: List<Pair<String, String>> = emptyList()): Boolean {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        return runCatching { http.get(url) { plexHeaders() }.status.isSuccess() }.getOrDefault(false)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.plexHeaders() {
        header("X-Plex-Token", token)
        header("Accept", "application/json")
        PLEX_IDENTITY.forEach { (k, v) -> header(k, v) }
    }

    override suspend fun ping() {
        // Any authenticated endpoint will do; sections is the cheapest that
        // proves both the address and the token.
        fetch("/library/sections")
    }

    /**
     * The server's own id, which playlist URIs are built from.
     *
     * Comes free with a linked account, but a server reached by address and
     * token has to be asked — and without it nothing can be added to a playlist.
     */
    suspend fun identity(): String? =
        runCatching { fetch("/identity").container.machineIdentifier }.getOrNull()

    // --- Browsing ------------------------------------------------------------

    override suspend fun getMusicFolders(): List<MusicFolder> =
        fetch("/library/sections").container.directories
            .filter { it.type == "artist" }
            .map { MusicFolder(id = it.key, name = it.title) }

    /**
     * Every album, from one section or all of the music ones.
     *
     * Plex asks for a section and a type; `8` is artist, `9` album, `10` track.
     */
    override suspend fun getAllAlbums(musicFolderId: String?): List<Album> {
        val sections = musicFolderId?.let { listOf(it) } ?: getMusicFolders().map { it.id }
        return sections.flatMap { section -> albumsInSection(section) }.distinctBy { it.id }
    }

    /**
     * One section's albums, a page at a time.
     *
     * Plex will cap a large response and say so through `totalSize`; asking for
     * everything in one request quietly returns a prefix on a big library. The
     * loop stops when a page comes back short or the total is reached, so a
     * server that doesn't page at all costs exactly one request.
     *
     * The explicit sort matters as much as the paging: pages are offsets into a
     * result set, and without an ordering the server is free to hand back the
     * rows in a different arrangement each time — which drops some albums and
     * repeats others across the page boundary.
     */
    private suspend fun albumsInSection(section: String): List<Album> =
        pagedSection(section, ALBUM_TYPE).map { it.toAlbum() }

    /** Everything of one type in a section, following the pages to the end. */
    private suspend fun pagedSection(section: String, type: String): List<PlexMetadata> {
        val items = mutableListOf<PlexMetadata>()
        var start = 0
        while (true) {
            val container = fetch(
                "/library/sections/$section/all",
                listOf(
                    "type" to type,
                    "sort" to "titleSort:asc",
                    "X-Plex-Container-Start" to start.toString(),
                    "X-Plex-Container-Size" to PAGE_SIZE.toString(),
                ),
            ).container
            items += container.metadata
            val total = container.totalSize ?: container.size
            start += container.metadata.size
            if (container.metadata.isEmpty() || start >= total) break
        }
        return items
    }

    /**
     * Plex's "Scan Library Files", which is what actually makes it notice a
     * record you copied in. Not forced: the plain refresh looks for what has
     * changed, where `force=1` walks every file again — minutes of disk on a
     * large library, to answer a question the quick pass already answers.
     *
     * Silently does nothing on a server this token doesn't administer, which is
     * the ordinary case for a library someone shared with you.
     */
    override suspend fun startServerScan(musicFolderId: String?): Boolean {
        val sections = musicFolderId?.let { listOf(it) }
            ?: runCatching { getMusicFolders().map { it.id } }.getOrDefault(emptyList())
        if (sections.isEmpty()) return false
        // The answer matters. Scanning is an owner's privilege, so a library
        // shared with you refuses it — and a refusal that goes unreported looks
        // exactly like a server that scanned and found nothing, which is the
        // most confusing way for this to fail.
        return sections.map { sendChecked("/library/sections/$it/refresh") }.any { it }
    }

    override suspend fun serverScanning(musicFolderId: String?): Boolean = runCatching {
        fetch("/library/sections").container.directories
            .filter { musicFolderId == null || it.key == musicFolderId }
            .any { it.isRefreshing }
    }.getOrDefault(false)

    override suspend fun getArtistIndex(musicFolderId: String?): List<ArtistRef> {
        val sections = musicFolderId?.let { listOf(it) } ?: getMusicFolders().map { it.id }
        return sections
            .flatMap { pagedSection(it, ARTIST_TYPE) }
            .map { ArtistRef(id = it.ratingKey, name = it.title) }
            .distinctBy { it.id }
    }

    /**
     * An artist's best-known songs — the same query PlexAmp's Popular Tracks
     * runs, rather than anything this app invents.
     *
     * Popularity is `ratingCount`, the listener count that arrives with Plex's
     * music metadata, so it means "well known" and not "played a lot on this
     * server". Compilations and live records are left out because they'd fill
     * the list with second versions of songs already in it, and grouping by
     * title collapses the rest. A library whose metadata carries no rating
     * counts returns nothing, and the app leaves the section out entirely
     * rather than showing some other list under the same name.
     *
     * The names the app knows come from track tags, so the artist has to be
     * looked up by name first; that index is built once and kept.
     */
    override suspend fun getTopSongs(artistName: String, count: Int): List<Track> {
        val (section, key) = artistLocation(artistName) ?: return emptyList()
        return runCatching {
            fetch(
                "/library/sections/$section/all",
                listOf(
                    "type" to TRACK_TYPE,
                    "artist.id" to key,
                    // `!` is "not one of", `>>` is "greater than".
                    "album.subformat!" to "Compilation,Live",
                    "group" to "title",
                    "ratingCount>>" to "0",
                    "sort" to "ratingCount:desc",
                    "limit" to (if (count > 0) count else TOP_SONGS_LIMIT).toString(),
                ),
            ).container.metadata.map { it.toTrack() }
        }.getOrDefault(emptyList())
    }

    /** Artist name to the section it lives in and its rating key. */
    private val artistKeys = mutableMapOf<String, Pair<String, String>>()
    private val artistKeyLock = Mutex()

    private suspend fun artistLocation(name: String): Pair<String, String>? =
        artistKeyLock.withLock {
            if (artistKeys.isEmpty()) {
                val sections = runCatching { getMusicFolders() }.getOrDefault(emptyList())
                for (section in sections) {
                    runCatching { pagedSection(section.id, ARTIST_TYPE) }
                        .getOrDefault(emptyList())
                        .forEach { artistKeys.putIfAbsent(it.title, section.id to it.ratingKey) }
                }
            }
            artistKeys[name]
        }

    override suspend fun getAlbumTracks(albumId: String): List<Track> =
        fetch("/library/metadata/$albumId/children").container.metadata.map { it.toTrack() }

    // --- Media ---------------------------------------------------------------

    /**
     * Plex's universal transcoder, which takes a metadata key rather than a file
     * — so this stays synchronous, with no lookup of the part id first.
     *
     * `offset` is the same trick the Subsonic path uses for seeking inside a
     * transcode: the stream starts that many seconds in.
     *
     * Original quality is *not* served from here — see the [Track] overload. By
     * id alone there is no way to name the file, and asking the transcoder to
     * direct-play it returns the original bytes behind an `.mp3` URL, which is a
     * lie that anything sniffing the extension rather than the content type
     * (a UPnP renderer, say) will believe.
     *
     * **Everything this endpoint transcodes, it transcodes to MP3.** The name is
     * not decoration: ask for FLAC here and MP3 comes back, with nothing in the
     * response to say so. That is why [MusicSource.streamFormats] offers a Plex
     * source only the original file and MP3 — the formats it cannot serve are
     * absent rather than silently swapped.
     */
    override fun streamUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
    ): String {
        val original = format == StreamFormat.RAW
        val params = buildList {
            add("path" to "/library/metadata/$songId")
            add("mediaIndex" to "0")
            add("partIndex" to "0")
            add("protocol" to "http")
            add("directPlay" to if (original) "1" else "0")
            add("directStream" to "1")
            add("hasMDE" to "1")
            if (timeOffsetSeconds > 0) add("offset" to timeOffsetSeconds.toString())
            format.maxBitRate?.let { add("musicBitrate" to it.toString()) }
            // Deliberately no session identifier. Plex keeps one transcode per
            // identifier and tears down the previous holder of it, so sharing one
            // across the app means a download in flight kills the stream that is
            // playing — the server answers 400 and playback stops dead. Left off,
            // every request gets its own, which is what concurrent streams need.
            add("X-Plex-Token" to token)
            PLEX_IDENTITY.forEach { (k, v) -> add(k to v) }
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + "/music/:/transcode/universal/start.mp3?$query"
    }

    /**
     * Original quality straight from the file, when we know where it is.
     *
     * The path comes back with the track's metadata and is stored on it, so this
     * needs no extra request. Plex serves it with the real container and content
     * type, which is what makes a FLAC arrive as a FLAC — seek table intact —
     * instead of as something claiming to be an MP3.
     */
    override fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
    ): String {
        if (format != StreamFormat.RAW || track.streamPath.isBlank()) {
            return streamUrl(track.id, format, timeOffsetSeconds, estimateContentLength)
        }
        return baseUrl.trimEnd('/') + track.streamPath +
            (if (track.streamPath.contains('?')) "&" else "?") +
            "download=0&X-Plex-Token=" + enc(token)
    }

    /**
     * Words for a song, where the library has them.
     *
     * Plex imports an `.lrc` sitting beside a track the way it imports a
     * subtitle, so lyrics arrive as another stream inside the file rather than
     * as anything lyric-shaped. Finding one costs a metadata request, because
     * the streams aren't in the album listing the library was built from.
     */
    override suspend fun getLyrics(songId: String): Lyrics? {
        val streams = runCatching { fetch("/library/metadata/$songId") }.getOrNull()
            ?.container?.metadata?.firstOrNull()
            ?.media?.flatMap { it.parts }?.flatMap { it.streams }
            .orEmpty()
        val stream = streams.firstOrNull {
            it.streamType == LYRIC_STREAM && !it.key.isNullOrBlank()
        } ?: return null
        val raw = runCatching {
            val response = http.get(baseUrl.trimEnd('/') + stream.key) { plexHeaders() }
            if (response.status.isSuccess()) response.bodyAsText() else null
        }.getOrNull()
        if (raw.isNullOrBlank()) return null
        // Timestamps where the file has them; otherwise it's a plain sheet, and
        // the lines still belong on screen without the karaoke.
        LyricsRepository.parseLrc(raw)?.let { return it }
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.isEmpty()) null else Lyrics(lines.map { LyricLine(null, it) }, synced = false)
    }

    override val streamFormats: List<StreamFormat> get() = STREAM_FORMATS

    /** Plex hands out cover paths, not ids; the token rides along in the URL. */
    override fun coverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        return baseUrl.trimEnd('/') + coverArtId +
            (if (coverArtId.contains('?')) "&" else "?") + "X-Plex-Token=" + enc(token)
    }

    // --- Writing back --------------------------------------------------------

    override suspend fun scrobble(songId: String, atMs: Long?, submission: Boolean) {
        if (!submission) return
        send(
            "GET",
            "/:/scrobble",
            listOf("key" to songId, "identifier" to LIBRARY_IDENTIFIER),
        )
    }

    /** Plex rates 0–10, so a star is worth two. */
    override suspend fun setRating(id: String, stars: Int): Boolean {
        send(
            "PUT",
            "/:/rate",
            listOf(
                "key" to id,
                "identifier" to LIBRARY_IDENTIFIER,
                "rating" to (stars.coerceIn(0, 5) * 2).toString(),
            ),
        )
        return true
    }

    // --- Playlists -----------------------------------------------------------

    override suspend fun getPlaylists(musicFolderId: String?): List<Playlist> =
        fetch("/playlists", listOf("playlistType" to "audio")).container.metadata.map {
            Playlist(
                id = it.ratingKey,
                name = it.title,
                coverArtId = it.composite ?: it.thumb,
                createdAt = (it.addedAt ?: 0L) * 1000L,
                updatedAt = (it.updatedAt ?: it.addedAt ?: 0L) * 1000L,
                trackIds = emptyList(),
            )
        }

    override suspend fun getPlaylist(id: String): Playlist {
        val summary = getPlaylists().firstOrNull { it.id == id }
        val trackIds = getPlaylistTracks(id).map { it.id }
        return summary?.copy(trackIds = trackIds)
            ?: Playlist(id, "", null, 0L, 0L, trackIds)
    }

    override suspend fun getPlaylistTracks(id: String): List<Track> =
        fetch("/playlists/$id/items").container.metadata.map { it.toTrack() }

    /**
     * Plex has no call for an empty playlist: creating one means naming its
     * contents, and a request without a `uri` is refused. The songs therefore
     * go in here rather than in a second pass — see [MusicServer.createPlaylist].
     */
    override suspend fun createPlaylist(name: String, songIds: List<String>): String? {
        if (songIds.isEmpty()) return null
        val uri = itemUri(songIds.joinToString(","))
        val response = runCatching {
            http.post(
                baseUrl.trimEnd('/') +
                    "/playlists?type=audio&smart=0&title=${enc(name)}&uri=${enc(uri)}",
            ) { plexHeaders() }
        }.getOrNull() ?: return null
        val text = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
        if (text.isBlank()) return null
        return runCatching {
            json.decodeFromString<PlexResponse>(text).container.metadata.firstOrNull()?.ratingKey
        }.getOrNull()
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        send("PUT", "/playlists/$id", listOf("title" to name))
    }

    override suspend fun deletePlaylist(id: String) {
        send("DELETE", "/playlists/$id")
    }

    override suspend fun addToPlaylist(id: String, songId: String) {
        send("PUT", "/playlists/$id/items", listOf("uri" to itemUri(songId)))
    }

    /**
     * Plex removes a playlist entry by its own id, not the song's — the same
     * song can appear twice and only one of them is meant. The position given is
     * an index into the playlist as the app last read it, so the entry ids are
     * re-read here rather than remembered.
     */
    override suspend fun removeFromPlaylistAt(id: String, index: Int) {
        val entry = playlistItemIds(id).getOrNull(index) ?: return
        send("DELETE", "/playlists/$id/items/$entry")
    }

    /**
     * Reordering is a sequence of moves: Plex will put one entry after another,
     * and has no call that takes a whole new running order.
     *
     * Walking the wanted order and moving each entry behind the one before it
     * settles the list in a single pass, and skipping the entries already in
     * place keeps a small change to a small number of requests.
     */
    override suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>) {
        val entries = playlistEntries(id)
        // Several entries can share a song id; take each in turn so a playlist
        // holding a song twice still moves the right one.
        val bySong = entries.groupBy { it.first }
            .mapValues { (_, v) -> v.map { it.second }.toMutableList() }
        val wanted = orderedSongIds.mapNotNull { songId ->
            bySong[songId]?.removeFirstOrNull()
        }
        var previous: Long? = null
        val current = entries.map { it.second }.toMutableList()
        for (entry in wanted) {
            val target = previous?.let { current.indexOf(it) + 1 } ?: 0
            val at = current.indexOf(entry)
            if (at != target) {
                send(
                    "PUT",
                    "/playlists/$id/items/$entry/move",
                    if (previous == null) emptyList() else listOf("after" to previous.toString()),
                )
                current.removeAt(at)
                current.add(target, entry)
            }
            previous = entry
        }
    }

    /** Each entry's `(songId, playlistItemID)`, in playlist order. */
    private suspend fun playlistEntries(id: String): List<Pair<String, Long>> =
        fetch("/playlists/$id/items").container.metadata.mapNotNull { item ->
            item.playlistItemID?.let { item.ratingKey to it }
        }

    private suspend fun playlistItemIds(id: String): List<Long> =
        playlistEntries(id).map { it.second }

    /** `server://{machine}/com.plexapp.plugins.library/library/metadata/{id}`. */
    private fun itemUri(songId: String): String =
        "server://$machineIdentifier/$LIBRARY_IDENTIFIER/library/metadata/$songId"

    // --- Mapping -------------------------------------------------------------

    private fun PlexMetadata.toAlbum(): Album = Album(
        id = ratingKey,
        title = title,
        artist = parentTitle ?: "Unknown Artist",
        coverArtId = thumb,
        durationMs = duration ?: 0L,
        songCount = leafCount ?: 0,
        year = year,
        releaseDate = originallyAvailableAt?.replace("-", "")?.toLongOrNull() ?: 0L,
        createdMs = (addedAt ?: 0L) * 1000L,
        playCount = viewCount ?: 0,
        lastPlayedMs = (lastViewedAt ?: 0L) * 1000L,
        rating = starsFrom(userRating),
        genre = genres.firstOrNull()?.tag.orEmpty(),
        // Plex has no compilation flag; what it has is the album artist every
        // library uses to mean one.
        compilation = parentTitle in COMPILATION_ARTISTS,
    )

    private fun PlexMetadata.toTrack(): Track = Track(
        id = ratingKey,
        // The track's own artist first: on a compilation grandparentTitle is
        // the album artist, and every song would read "Various Artists".
        artist = originalTitle ?: grandparentTitle ?: "Unknown Artist",
        title = title,
        album = parentTitle ?: "Unknown Album",
        albumArtist = grandparentTitle ?: "Unknown Artist",
        albumId = parentRatingKey,
        coverArtId = thumb ?: parentThumb,
        durationMs = duration ?: 0L,
        trackNumber = index,
        discNumber = parentIndex,
        year = year,
        playCount = viewCount ?: 0,
        lastPlayedMs = (lastViewedAt ?: 0L) * 1000L,
        rating = starsFrom(userRating),
        genre = genres.firstOrNull()?.tag.orEmpty(),
        streamPath = media.firstOrNull()?.parts?.firstOrNull()?.key.orEmpty(),
    )

    /** Plex's 0–10 back to the app's 0–5. */
    private fun starsFrom(rating: Float?): Int =
        rating?.let { (it / 2f).toInt().coerceIn(0, 5) } ?: 0

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    companion object {
        const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"

        /**
         * The original file, or MP3. Plex's universal music transcoder produces
         * MP3 and nothing else — the endpoint is `start.mp3`, and asking it for
         * FLAC returns MP3 with nothing in the response to admit it. Handing over
         * the untouched file is the better answer for "lossless" anyway.
         */
        val STREAM_FORMATS: List<StreamFormat> = listOf(StreamFormat.RAW, StreamFormat.MP3)

        /** Items per request. Big enough to be one round trip on most libraries. */
        private const val PAGE_SIZE = 500

        private const val ARTIST_TYPE = "8"
        private const val ALBUM_TYPE = "9"
        private const val TRACK_TYPE = "10"

        /** What PlexAmp asks for when it shows Popular Tracks. */
        private const val TOP_SONGS_LIMIT = 100

        /** What a library calls an album that isn't by one artist. */
        private val COMPILATION_ARTISTS = setOf("Various Artists", "Various", "VA")

        /** Plex's stream types: 1 video, 2 audio, 3 subtitle, 4 lyrics. */
        private const val LYRIC_STREAM = 4

        /**
         * Who we say we are. Plex wants these on every request, and uses the
         * client identifier to tie a token to a device — it must be stable, so
         * it is the app's own id rather than something generated per launch.
         */
        val PLEX_IDENTITY: List<Pair<String, String>> = listOf(
            "X-Plex-Client-Identifier" to "com.sublunar.amp",
            "X-Plex-Product" to "Amp",
            "X-Plex-Version" to "1.0.0",
            "X-Plex-Device" to "Light Phone III",
            "X-Plex-Platform" to "Android",
        )
    }
}

class PlexException(message: String) : Exception(message)

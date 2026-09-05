package com.sublunar.amp.data

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    /** Overrides `X-Plex-Product`/`X-Plex-Device`; null keeps the app's defaults. */
    private val product: String? = null,
) : MusicServer {

    private val identityHeaders: List<Pair<String, String>> = plexIdentity(product)

    private val http = NetworkGate.httpClient { expectSuccess = false }

    /** Serialises requests aimed at a player; see [companionXml]. */
    private val playerLock = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override fun close() = http.close()

    // --- Requests ------------------------------------------------------------

    private suspend fun fetch(path: String, params: List<Pair<String, String>> = emptyList()): PlexResponse =
        decode(rawBody(path, params))

    /** The body as text, for the one answer whose shape isn't declared — see [stationKeyFor]. */
    private suspend fun rawBody(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val response = http.get(url) { plexHeaders() }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for $path")
        }
        return response.bodyAsText()
    }

    /** A POST whose answer matters — creating a play queue hands its items back. */
    private suspend fun rawPost(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val response = http.post(url) { plexHeaders() }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for $path")
        }
        return response.bodyAsText()
    }

    private fun decode(text: String): PlexResponse =
        if (text.isBlank()) PlexResponse() else json.decodeFromString(text)

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
        identityHeaders.forEach { (k, v) -> header(k, v) }
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
    private suspend fun pagedSection(section: String, type: String): List<PlexMetadata> =
        paged(
            "/library/sections/$section/all",
            listOf("type" to type, "sort" to "titleSort:asc"),
        )

    /**
     * A container's every item, a page at a time — see [pagedSection] for why
     * Plex is never asked for everything in one request.
     *
     * Playlists too, and for a second reason: their items come with the full
     * media description of every track, and one large playlist answered as a
     * single 27 MB body that the phone could not hold — the fetch failed out
     * of memory, in the background, on every download top-up. No `sort` is
     * added here; a playlist's order is its own, and a section names its own.
     */
    private suspend fun paged(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): List<PlexMetadata> {
        val items = mutableListOf<PlexMetadata>()
        var start = 0
        while (true) {
            val container = fetch(
                path,
                params + listOf(
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
            .map { ArtistRef(id = it.ratingKey, name = it.title, imageId = it.thumb) }
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

    /**
     * Plex has two answers to "songs like this one", and either is taken.
     *
     * The first is the station it builds for the track — `includeStations=1`
     * on the track's metadata names it, and a play queue created from the
     * station's key is how any station hands out its songs (python-plexapi's
     * `Artist.station()` + `PlayQueue.fromStationKey`, with the key's own
     * `?type=10` kept). The second is the tracks it judges nearest, where the
     * server has done its sonic analysis — what Plexamp's Track Radio draws
     * on. Both are Plex's own judgement of the same question, so whichever the
     * server can answer is the radio; what it answered is logged under
     * `AmpRadio`, because neither could be verified here against a live server.
     * Empty when it has neither — the caller says so; nothing plays instead.
     */
    override suspend fun getSimilarSongs(songId: String, count: Int): List<Track> {
        val limit = if (count > 0) count else RADIO_DEFAULT
        // Three askings, each Plex's own, most song-specific first. On the
        // server this was checked against, a track carries no station and
        // `nearest` answers nothing (sonic analysis is a Plex Pass feature),
        // and the artist's station is what Plex actually has — which is also
        // what Navidrome's song radio is underneath: the song's artist and the
        // artists like them.
        val own = runCatching { stationTracks(songId, "track") }
            .onFailure { android.util.Log.w(RADIO_TAG, "plex track station failed: ${it.message}") }
            .getOrDefault(StationAnswer())
        if (own.tracks.isNotEmpty()) return own.tracks.take(limit)
        val nearest = runCatching { nearestTracks(songId, limit) }
            .onFailure { android.util.Log.w(RADIO_TAG, "plex nearest failed: ${it.message}") }
            .getOrDefault(emptyList())
        android.util.Log.i(RADIO_TAG, "plex nearest: ${nearest.size} tracks for $songId")
        if (nearest.isNotEmpty()) return nearest
        val artist = own.grandparentRatingKey ?: return emptyList()
        // A station hands out a few songs at a time — five, on the server this
        // was checked against — and expects the client to report its position
        // and be topped up as it plays. This client reads once, so it asks a
        // few times instead: every ask mints a fresh station (the key's uuid
        // differs each time) with a fresh handful, merged here until there is
        // enough for a radio or an ask brings nothing new.
        val merged = LinkedHashMap<String, Track>()
        for (ask in 1..STATION_ASKS) {
            val answer = runCatching { stationTracks(artist, "artist") }
                .onFailure { android.util.Log.w(RADIO_TAG, "plex artist station failed: ${it.message}") }
                .getOrDefault(StationAnswer())
            val before = merged.size
            answer.tracks.forEach { merged.putIfAbsent(it.id, it) }
            if (merged.size == before || merged.size >= limit) break
        }
        android.util.Log.i(RADIO_TAG, "plex artist radio: ${merged.size} tracks after up to $STATION_ASKS asks")
        return merged.values.take(limit)
    }

    /** What asking an item for its station came to, plus the artist it named. */
    private class StationAnswer(
        val tracks: List<Track> = emptyList(),
        val grandparentRatingKey: String? = null,
    )

    /** An item's station, read once as a play queue; no tracks when it has none. */
    private suspend fun stationTracks(ratingKey: String, what: String): StationAnswer {
        val text = rawBody("/library/metadata/$ratingKey", listOf("includeStations" to "1"))
        val tree = json.parseToJsonElement(text)
        val stationKey = findStationKey(tree)
        val artistKey = findString(tree, "grandparentRatingKey")
        android.util.Log.i(RADIO_TAG, "plex $what $ratingKey: station=${stationKey != null}, artist=$artistKey")
        if (stationKey == null) return StationAnswer(grandparentRatingKey = artistKey)
        val machine = machineIdentifier.ifBlank { identity().orEmpty() }
        if (machine.isBlank()) {
            android.util.Log.w(RADIO_TAG, "plex: no machine identifier, can't build the queue")
            return StationAnswer(grandparentRatingKey = artistKey)
        }
        // Read once rather than followed: `continuous` keeps Plex topping the
        // queue up for clients that report their position back, and this one
        // doesn't.
        val queueText = rawPost(
            "/playQueues",
            listOf(
                "type" to "audio",
                "uri" to "server://$machine/$LIBRARY_IDENTIFIER$stationKey",
                "continuous" to "1",
                "shuffle" to "0",
                "repeat" to "0",
                "includeChapters" to "0",
            ),
        )
        var tracks = decode(queueText).container.metadata.filter { it.type == "track" }.map { it.toTrack() }
        val queueTree = json.parseToJsonElement(queueText)
        val queueId = findString(queueTree, "playQueueID")
        android.util.Log.i(
            RADIO_TAG,
            "plex $what station queue $queueId: ${tracks.size} tracks, " +
                "total=${findString(queueTree, "playQueueTotalCount")}",
        )
        // Checked once, logged: whether re-reading a continuous queue with a
        // wide window is what makes Plex generate more of it.
        if (queueId != null) {
            val wider = runCatching {
                fetch("/playQueues/$queueId", listOf("window" to "100", "includeChapters" to "0"))
                    .container.metadata.filter { it.type == "track" }.map { it.toTrack() }
            }.getOrDefault(emptyList())
            android.util.Log.i(RADIO_TAG, "plex queue $queueId re-read, window=100: ${wider.size} tracks")
            if (wider.size > tracks.size) tracks = wider
        }
        return StationAnswer(tracks, artistKey)
    }

    /** Sonically nearest tracks — python-plexapi's `sonicallySimilar`. */
    private suspend fun nearestTracks(ratingKey: String, limit: Int): List<Track> =
        fetch("/library/metadata/$ratingKey/nearest", listOf("limit" to limit.toString()))
            .container.metadata.filter { it.type == "track" }.map { it.toTrack() }

    /**
     * Walked rather than declared: Plex nests its Stations block differently
     * between XML and JSON, and the one stable fact is a playlist entry whose
     * key says `/station/`. The first such key is the track's own station.
     */
    /** The first string under [name] anywhere in the tree. */
    private fun findString(node: JsonElement, name: String): String? = when (node) {
        is JsonObject -> (node[name] as? JsonPrimitive)?.contentOrNull
            ?: node.values.firstNotNullOfOrNull { findString(it, name) }
        is JsonArray -> node.firstNotNullOfOrNull { findString(it, name) }
        else -> null
    }

    private fun findStationKey(node: JsonElement): String? = when (node) {
        is JsonObject -> {
            val key = (node["key"] as? JsonPrimitive)?.contentOrNull
            if (key != null && "/station/" in key) {
                key
            } else {
                node.values.firstNotNullOfOrNull { findStationKey(it) }
            }
        }
        is JsonArray -> node.firstNotNullOfOrNull { findStationKey(it) }
        else -> null
    }

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
    override suspend fun prepareStream(
        songId: String,
        format: StreamFormat,
        sessionId: String?,
    ): Boolean {
        // Nothing to decide without a session to decide for: the stream URL
        // leaves the identifier off in that case too, and Plex only enforces
        // this against sessions it is tracking.
        if (sessionId.isNullOrBlank()) return true
        return sendChecked("/music/:/transcode/universal/decision", transcodeParams(songId, format, 0, sessionId))
    }

    override fun streamUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
        sessionId: String?,
    ): String {
        val query = transcodeParams(songId, format, timeOffsetSeconds, sessionId)
            .joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + "/music/:/transcode/universal/start.mp3?$query"
    }

    /**
     * What a transcode request is made of — asked for twice, and the two have to
     * match.
     *
     * [prepareStream] settles the decision for a session and the stream then
     * claims it, so anything that differs between the two describes a different
     * playback and leaves the stream without a decision of its own.
     */
    private fun transcodeParams(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        sessionId: String?,
    ): List<Pair<String, String>> {
        val original = format == StreamFormat.RAW
        return buildList {
            add("path" to "/library/metadata/$songId")
            add("mediaIndex" to "0")
            add("partIndex" to "0")
            add("protocol" to "http")
            add("directPlay" to if (original) "1" else "0")
            add("directStream" to "1")
            add("hasMDE" to "1")
            if (timeOffsetSeconds > 0) add("offset" to timeOffsetSeconds.toString())
            format.maxBitRate?.let { add("musicBitrate" to it.toString()) }
            // Not shared across calls: Plex keeps one transcode per identifier
            // and tears down the previous holder of it, so the same id on a
            // download in flight and the stream that is playing would have the
            // second one kill the first — the server answers 400 and playback
            // stops dead. The caller is expected to mint a fresh one per
            // playback (see PlaybackController) and never reuse it for a
            // download, which is why this is left out unless given one.
            if (!sessionId.isNullOrBlank()) add("X-Plex-Session-Identifier" to sessionId)
            add("X-Plex-Token" to token)
            identityHeaders.forEach { (k, v) -> add(k to v) }
        }
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
        sessionId: String?,
    ): String {
        if (format != StreamFormat.RAW || track.streamPath.isBlank()) {
            return streamUrl(track.id, format, timeOffsetSeconds, estimateContentLength, sessionId)
        }
        // The original file is served whole and direct, with no transcode
        // session behind it to identify — nothing to attach sessionId to.
        return baseUrl.trimEnd('/') + track.streamPath +
            (if (track.streamPath.contains('?')) "&" else "?") +
            "download=0&X-Plex-Token=" + enc(token)
    }

    /**
     * A copy to keep, rather than a stream to play.
     *
     * The original needs no transcode and is already served whole, so that path
     * is unchanged. For anything else `download=1` asks Plex's transcoder for a
     * complete file instead of the segmented live encode `start.mp3` produces —
     * the latter has no Xing header, so nothing downstream can know how long it
     * is: ExoPlayer falls back to size ÷ the first frame's bitrate, and on a
     * variable-rate encode that read a 3:07 song as 22:57 and then played
     * silence for the difference.
     */
    override fun downloadUrl(track: Track, format: StreamFormat): String {
        if (format == StreamFormat.RAW && track.streamPath.isNotBlank()) {
            return streamUrl(track, format, estimateContentLength = false)
        }
        val query = (transcodeParams(track.id, format, 0, null) + ("download" to "1"))
            .joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + "/music/:/transcode/universal/start.mp3?$query"
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
            val response = http.get(baseUrl.trimEnd('/') + stream.key) {
                // Not plexHeaders(): that asks for `application/json`, which is
                // right for every endpoint that answers with a MediaContainer
                // and wrong for this one — it serves the lyric file itself.
                // Plex took the mismatch as nothing to serve and answered 404
                // for every track, so a downloaded song stored no words and
                // each download spent a request finding that out.
                header("X-Plex-Token", token)
                header("Accept", "*/*")
                identityHeaders.forEach { (k, v) -> header(k, v) }
            }
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

    /**
     * The cover through Plex's own image resizer, which is what its clients use.
     *
     * The stored art is whatever the scanner found — routinely 2000px and
     * several megabytes — and the path above serves exactly that. This asks the
     * server to do the shrinking, which turns a page of covers from tens of
     * megabytes into a few hundred kilobytes. `upscale=0` so a small original is
     * left alone rather than blown up to the asked-for size and back down again
     * on this end.
     */
    override fun coverArtUrl(coverArtId: String?, maxSizePx: Int): String? {
        if (coverArtId.isNullOrBlank()) return null
        if (maxSizePx <= 0) return coverArtUrl(coverArtId)
        val params = listOf(
            "width" to maxSizePx.toString(),
            "height" to maxSizePx.toString(),
            "minSize" to "1",
            "upscale" to "0",
            "url" to coverArtId,
            "X-Plex-Token" to token,
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + "/photo/:/transcode?" + params
    }

    // --- Writing back --------------------------------------------------------

    override suspend fun scrobble(songId: String, atMs: Long?, submission: Boolean) {
        // Plex has no now-playing call of this shape — that job belongs to
        // [reportTimeline], which it does understand. Marking the track played
        // on a start notice would count everything that was skipped past.
        if (!submission) return
        send(
            "GET",
            "/:/scrobble",
            listOf("key" to songId, "identifier" to LIBRARY_IDENTIFIER),
        )
    }

    /**
     * A heartbeat for whatever is watching `/status/sessions` — Plex's own
     * dashboard, chiefly. Without this, a stream this app started is bytes
     * moving and nothing else: Plex has no session object to hold it in, and
     * so nothing to list as "now playing".
     *
     * `time`/`duration` are milliseconds, which is what `/:/timeline` wants —
     * unlike most of this client's calls, which work in Plex's native seconds.
     */
    override suspend fun reportTimeline(
        sessionId: String,
        songId: String,
        state: TimelineState,
        positionMs: Long,
        durationMs: Long,
    ) {
        val params = buildList {
            add("ratingKey" to songId)
            add("key" to "/library/metadata/$songId")
            add("identifier" to LIBRARY_IDENTIFIER)
            add("state" to state.plexValue)
            add("time" to positionMs.coerceAtLeast(0L).toString())
            if (durationMs > 0) add("duration" to durationMs.toString())
            add("hasMDE" to "1")
            add("X-Plex-Session-Identifier" to sessionId)
        }
        send("GET", "/:/timeline", params)
    }

    private val TimelineState.plexValue: String
        get() = when (this) {
            TimelineState.PLAYING -> "playing"
            TimelineState.PAUSED -> "paused"
            TimelineState.STOPPED -> "stopped"
            TimelineState.BUFFERING -> "buffering"
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
                readOnly = it.isSmart,
            )
        }

    override suspend fun getPlaylist(id: String): Playlist {
        val summary = getPlaylists().firstOrNull { it.id == id }
        val trackIds = getPlaylistTracks(id).map { it.id }
        return summary?.copy(trackIds = trackIds)
            ?: Playlist(id, "", null, 0L, 0L, trackIds)
    }

    override suspend fun getPlaylistTracks(id: String): List<Track> =
        paged("/playlists/$id/items").map { it.toTrack() }

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
        paged("/playlists/$id/items").mapNotNull { item ->
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
        // Plex leaves leafCount off this listing, so this is the only
        // thing that says an album changed. Seconds on the wire.
        updatedMs = (updatedAt ?: 0L) * 1000L,
        playCount = viewCount ?: 0,
        lastPlayedMs = (lastViewedAt ?: 0L) * 1000L,
        rating = starsFrom(userRating),
        genre = genres.firstOrNull()?.tag.orEmpty(),
    )

    // --- Plex Companion — casting to another Plex player ---------------------
    //
    // Companion is Plex's own remote-control protocol: any signed-in player
    // that stays connected to the server (the Apple TV app among them) accepts
    // `/player/...` commands relayed *through the server* — the same proxy
    // route the official controllers use, so this phone never needs to reach
    // the player directly. The player then pulls the audio from the server
    // itself; Amp only steers. Unlike a DLNA renderer, a Plex player holds the
    // whole play queue and advances through it on its own.
    //
    // These endpoints answer XML even where the rest of the server speaks
    // JSON — the proxied replies come from the player — so they go through
    // [companionXml] and the attribute parser at the bottom of this file.

    /**
     * Companion-capable players, from two lists because no one list has
     * everyone: the server's `/clients` only knows players it discovered on
     * its own LAN, and the Apple TV app is famously not among them — it
     * announces itself to plex.tv instead. The account's resources cover
     * those; the token here is the account's, so both are ours to ask. The
     * server relays commands to either kind the same way.
     */
    suspend fun companionPlayers(): List<PlexPlayer> {
        val fromServer = runCatching {
            plexXmlElements(companionXml("/clients"), "Server").mapNotNull { attrs ->
                val id = attrs["machineIdentifier"] ?: return@mapNotNull null
                // Entries that can't take playback commands are controllers or
                // servers — not somewhere sound can go.
                if (!(attrs["protocolCapabilities"] ?: "").contains("playback")) return@mapNotNull null
                // The server's list names the player's own address and
                // Companion port; commands go there, not back through here.
                val host = attrs["address"] ?: attrs["host"] ?: return@mapNotNull null
                val port = attrs["port"]?.toIntOrNull() ?: COMPANION_PORT
                PlexPlayer(
                    id = id,
                    name = attrs["name"] ?: attrs["product"] ?: "Plex player",
                    product = attrs["product"].orEmpty(),
                    directUrl = "http://$host:$port",
                    // The server found it; it is who the server says.
                    identityKnown = true,
                )
            }
        }.getOrDefault(emptyList())
        val fromAccount = runCatching {
            val response = http.get("$PLEX_TV_RESOURCES") { plexHeaders() }
            if (!response.status.isSuccess()) {
                throw PlexException("plex.tv says ${response.status.value} for the device list")
            }
            val body = response.bodyAsText()
            val players = json.decodeFromString<List<PlexResource>>(body)
                .filter { it.provides.contains("player") && it.clientIdentifier.isNotBlank() }
            android.util.Log.i(
                "AmpPlex",
                "plex.tv lists ${players.size} player(s): " + players.joinToString {
                    "${it.name}(present=${it.presence}, connections=${it.connections.size})"
                },
            )
            players
                // Deliberately not filtered on plex.tv's `presence`: it goes
                // stale — an Apple TV that slept and woke reads as absent while
                // sitting there playing. What decides is an address to reach it
                // at; a player that answers nothing simply fails to take the
                // cast, which is visible, unlike a device missing from a list.
                .mapNotNull { resource ->
                    // The local address first: this Wi-Fi, plain http, no
                    // round trip through Plex's relay. A player with no
                    // address is one nothing here can command, so it is not
                    // offered.
                    val direct = resource.connections
                        .filter { it.address.isNotBlank() && it.port > 0 }
                        .sortedByDescending { it.local }
                        .firstOrNull()
                        ?.let { "http://${it.address}:${it.port}" }
                        ?: return@mapNotNull null
                    PlexPlayer(
                        id = resource.clientIdentifier,
                        name = resource.name.ifBlank { resource.product.ifBlank { "Plex player" } },
                        product = resource.product,
                        directUrl = direct,
                    )
                }
        }.getOrElse {
            android.util.Log.i("AmpPlex", "plex.tv device list failed: ${it.message}")
            emptyList()
        }
        val seen = fromServer.map { it.id }.toSet()
        val merged = fromServer + fromAccount.filter { it.id !in seen }
        android.util.Log.i(
            "AmpPlex",
            "players: server=${fromServer.map { it.name }} " +
                "account=${fromAccount.map { "${it.name}@${it.directUrl ?: "no-address"}" }}",
        )
        return merged
    }

    /**
     * Create a server-side play queue over [trackIds], cued on [startId].
     * The queue belongs to the server; whoever plays it walks it themselves.
     */
    suspend fun createPlayQueue(trackIds: List<String>, startId: String, repeatAll: Boolean): PlexQueue? = runCatching {
        val mid = machineIdentifier.ifBlank { identity().orEmpty() }
        if (mid.isBlank()) {
            android.util.Log.i("AmpPlex", "no machine identifier — the server can't be named in a queue uri")
            return@runCatching null
        }
        android.util.Log.i("AmpPlex", "building a queue of ${trackIds.size} on $mid, starting at $startId")
        // A queue is built out of this server's own rating keys. Ids from
        // another source — a queue left over from Navidrome — name nothing
        // here, and the server rejects the lot with no hint which it was.
        val alien = trackIds.filterNot { it.all(Char::isDigit) }
        if (alien.isNotEmpty()) {
            android.util.Log.i(
                "AmpPlex",
                "queue holds ${alien.size} track(s) that aren't Plex ids, e.g. ${alien.first()}",
            )
            return@runCatching null
        }
        val uri = "server://$mid/com.plexapp.plugins.library/library/metadata/" +
            trackIds.joinToString(",")
        val body = companionXml(
            "/playQueues",
            listOf(
                "type" to "audio",
                "uri" to uri,
                "key" to "/library/metadata/$startId",
                "shuffle" to "0",
                "repeat" to if (repeatAll) "1" else "0",
                "own" to "1",
            ),
            post = true,
        )
        queueFrom(body).also {
            if (it == null) android.util.Log.i("AmpPlex", "the server made a queue with no id in it")
        }
    }.getOrElse {
        android.util.Log.i("AmpPlex", "play queue refused: ${it.message}")
        null
    }

    /** The queue id, version and item places, out of any play-queue answer. */
    private fun queueFrom(body: String): PlexQueue? {
        val container = plexXmlElements(body, "MediaContainer").firstOrNull() ?: return null
        val id = container["playQueueID"] ?: return null
        val items = plexXmlElements(body, "Track").mapNotNull { t ->
            val rating = t["ratingKey"] ?: return@mapNotNull null
            val place = t["playQueueItemID"] ?: return@mapNotNull null
            rating to place
        }.toMap()
        return PlexQueue(id, items, container["playQueueVersion"])
    }

    /**
     * Re-read a queue, asking for [window] items around the current one.
     *
     * An edit's own answer carries only as much of the queue as Plex feels
     * like returning, and an item whose place we don't know can't be moved or
     * removed — which is a queue edit silently becoming a re-push. Asking
     * plainly for the window we pushed keeps the places we need.
     */
    suspend fun readPlayQueue(queue: PlexQueue, window: Int): PlexQueue? = runCatching {
        queueFrom(
            companionXml(
                "/playQueues/${queue.id}",
                listOf("window" to window.toString(), "own" to "1"),
            )
        )
    }.getOrElse {
        android.util.Log.i("AmpPlex", "re-reading the queue failed: ${it.message}")
        null
    }

    /**
     * Move a track's place so it follows [afterTrackId] — or to the front when
     * that is null. The server answers with the queue as it now stands.
     */
    suspend fun movePlayQueueItem(queue: PlexQueue, trackId: String, afterTrackId: String?): PlexQueue? = runCatching {
        val place = queue.items[trackId] ?: return@runCatching null
        val after = afterTrackId?.let { queue.items[it] }
        queueFrom(
            companionXml(
                "/playQueues/${queue.id}/items/$place/move",
                if (after != null) listOf("after" to after) else emptyList(),
                put = true,
            )
        )
    }.getOrNull()

    /** Drop a track's place from the queue. */
    suspend fun removePlayQueueItem(queue: PlexQueue, trackId: String): PlexQueue? = runCatching {
        val place = queue.items[trackId] ?: return@runCatching null
        queueFrom(companionXml("/playQueues/${queue.id}/items/$place", delete = true))
    }.getOrNull()

    /** Append [trackIds], or put them next when [next]. */
    suspend fun addToPlayQueue(queue: PlexQueue, trackIds: List<String>, next: Boolean): PlexQueue? = runCatching {
        if (trackIds.isEmpty()) return@runCatching null
        val mid = machineIdentifier.ifBlank { identity() ?: return@runCatching null }
        val uri = "server://$mid/com.plexapp.plugins.library/library/metadata/" + trackIds.joinToString(",")
        queueFrom(
            companionXml(
                "/playQueues/${queue.id}",
                listOf("uri" to uri) + if (next) listOf("next" to "1") else emptyList(),
                put = true,
            )
        )
    }.getOrNull()

    /** Tell [target] its play queue changed underneath it. */
    suspend fun companionRefreshQueue(target: PlexPlayer, queue: PlexQueue, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/playback/refreshPlayQueue",
            listOfNotNull(
                "playQueueID" to queue.id,
                queue.version?.let { "playQueueVersion" to it },
                "type" to "music",
                "commandID" to commandId.toString(),
            ),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "refreshPlayQueue refused: ${it.message}")
        false
    }

    /**
     * Whether the player at this address really is the one we mean.
     *
     * An address is not an identity. A device listed by plex.tv is named by
     * whatever connection the account has on file, and a DHCP lease that moved
     * would have us commanding whatever answers there now. GDM replies carry
     * their own identity, so this is for the ones that don't.
     */
    suspend fun playerIsWhoWeThink(target: PlexPlayer): Boolean = runCatching {
        val reported = plexXmlElements(companionXml("/resources", target = target), "Player")
            .firstNotNullOfOrNull { it["machineIdentifier"] }
        if (reported == null) {
            // Nothing said either way. Not proof of an impostor, and refusing
            // on silence would be worse than the risk.
            android.util.Log.i("AmpPlex", "${target.name} didn't say who it is; going ahead")
            return@runCatching true
        }
        (reported == target.id).also {
            if (!it) android.util.Log.i("AmpPlex", "${target.directUrl} is $reported, not ${target.id}")
        }
    }.getOrElse {
        android.util.Log.i("AmpPlex", "couldn't check who ${target.name} is: ${it.message}")
        false
    }

    /**
     * Ask [target] to push its timeline to us at [port] instead of being asked
     * every second.
     *
     * The protocol's own answer to polling, and the reason a controller has an
     * HTTP server in it — see [PlexCompanionListener]. Polling remains the
     * fallback for a player that won't subscribe.
     */
    suspend fun companionSubscribe(target: PlexPlayer, port: Int, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/timeline/subscribe",
            listOf(
                "port" to port.toString(),
                "protocol" to "http",
                "commandID" to commandId.toString(),
            ),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "subscribe refused: ${it.message}")
        false
    }

    /** Stop the pushes started by [companionSubscribe]. */
    suspend fun companionUnsubscribe(target: PlexPlayer, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/timeline/unsubscribe",
            listOf("commandID" to commandId.toString()),
            target = target,
        )
        true
    }.getOrDefault(false)

    /** Start [queue] on [target] at [startId]/[offsetMs]. True when the player took it. */
    suspend fun companionPlay(
        target: PlexPlayer,
        queue: PlexQueue,
        startId: String,
        offsetMs: Long,
        commandId: Int,
        window: Int = DEFAULT_QUEUE_WINDOW,
    ): Boolean = runCatching {
        val u = URI(baseUrl)
        val mid = machineIdentifier.ifBlank { identity().orEmpty() }
        companionXml(
            "/player/playback/playMedia",
            listOf(
                "key" to "/library/metadata/$startId",
                "offset" to offsetMs.coerceAtLeast(0L).toString(),
                "machineIdentifier" to mid,
                "protocol" to (u.scheme ?: "http"),
                "address" to u.host.orEmpty(),
                "port" to (if (u.port > 0) u.port else if (u.scheme == "https") 443 else 32400).toString(),
                // The window the player is told to fetch is the window it was
                // given; a smaller number here means it re-reads less of the
                // queue than we pushed.
                "containerKey" to "/playQueues/${queue.id}?window=$window&own=1",
                "token" to token,
                "commandID" to commandId.toString(),
            ),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "playMedia refused: ${it.message}")
        false
    }

    /**
     * Jump to a track *within the queue the player already holds*.
     *
     * Not [companionPlay]: that hands the player a queue to go and fetch, and
     * a player pulling a hundred-item container back over the WAN takes long
     * enough that the command times out — which is what tapping a queue row
     * used to do. `skipTo` moves inside what it has.
     */
    suspend fun companionSkipTo(target: PlexPlayer, trackId: String, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/playback/skipTo",
            listOf(
                "key" to "/library/metadata/$trackId",
                "type" to "music",
                "commandID" to commandId.toString(),
            ),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "skipTo refused: ${it.message}")
        false
    }

    /** A plain transport command: `play`, `pause`, `stop`, `skipNext`, `skipPrevious`. */
    suspend fun companionCommand(target: PlexPlayer, command: String, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/playback/$command",
            listOf("type" to "music", "commandID" to commandId.toString()),
            target = target,
        )
        true
    }.getOrElse {
        android.util.Log.i("AmpPlex", "$command refused: ${it.message}")
        false
    }

    suspend fun companionSeek(target: PlexPlayer, ms: Long, commandId: Int): Boolean = runCatching {
        companionXml(
            "/player/playback/seekTo",
            listOf("offset" to ms.coerceAtLeast(0L).toString(), "type" to "music", "commandID" to commandId.toString()),
            target = target,
        )
        true
    }.getOrDefault(false)

    /** `volume` 0–100 and/or `repeat` 0 off / 1 track / 2 queue — the player keeps what it understands. */
    suspend fun companionSetParameters(target: PlexPlayer, params: List<Pair<String, String>>, commandId: Int): Boolean =
        runCatching {
            companionXml(
                "/player/playback/setParameters",
                params + listOf("type" to "music", "commandID" to commandId.toString()),
                target = target,
            )
            true
        }.getOrDefault(false)

    /** The player's music timeline, or null when it can't be asked. */
    suspend fun companionTimeline(target: PlexPlayer, commandId: Int): PlexPlayerTimeline? = runCatching {
        plexTimelineFrom(
            companionXml(
                "/player/timeline/poll",
                listOf("wait" to "0", "commandID" to commandId.toString()),
                target = target,
            )
        )
    }.getOrNull()

    /**
     * A Companion request: to the player itself when one is named, to the
     * server otherwise (the client list and play queues are the server's).
     */
    /**
     * @param inBody send the parameters as a form body rather than a query.
     *   A play queue names every track in one `uri`, and percent-encoded that
     *   is kilobytes for a long queue — enough for a reverse proxy in front of
     *   the server to answer 400 before Plex ever sees it. A body has no such
     *   ceiling, and is what a payload that size should have been all along.
     */
    private suspend fun companionXml(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        target: PlexPlayer? = null,
        post: Boolean = false,
        put: Boolean = false,
        delete: Boolean = false,
        inBody: Boolean = false,
    ): String {
        val host = target?.directUrl ?: baseUrl
        val encoded = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = host.trimEnd('/') + path + if (encoded.isEmpty() || inBody) "" else "?$encoded"
        val form: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            companionHeaders(target)
            if (inBody) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(encoded)
            }
        }
        // A player answers one caller at a time. The poll runs every second, so
        // without this a command lands on top of it, the socket wedges, and
        // enough of those in a row take the player down altogether.
        val response = if (target != null) {
            playerLock.withLock {
                when {
                    post -> http.post(url, form)
                    put -> http.put(url, form)
                    delete -> http.delete(url) { companionHeaders(target) }
                    else -> http.get(url) { companionHeaders(target) }
                }
            }
        } else {
            when {
                post -> http.post(url, form)
                put -> http.put(url, form)
                delete -> http.delete(url) { companionHeaders(target) }
                else -> http.get(url) { companionHeaders(target) }
            }
        }
        if (!response.status.isSuccess()) {
            // The server explains itself in the body, and throwing that away is
            // how a 400 stayed a mystery through three guesses.
            val why = runCatching { response.bodyAsText() }.getOrDefault("").take(300).replace('\n', ' ')
            throw PlexException("Plex says ${response.status.value} for $path at $host — $why")
        }
        return response.bodyAsText()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.companionHeaders(target: PlexPlayer?) {
        header("X-Plex-Token", token)
        // XML on purpose: proxied player replies are XML whatever we ask for,
        // so asking for it everywhere keeps one parser.
        header("Accept", "application/xml")
        identityHeaders.forEach { (k, v) -> header(k, v) }
        if (target != null) {
            header("X-Plex-Target-Client-Identifier", target.id)
            // A player answering for itself checks who is asking: an unknown
            // controller with no provides header is refused before the command
            // is read.
            header("X-Plex-Provides", "controller")
        }
    }

    private fun PlexMetadata.toTrack(): Track = Track(
        id = ratingKey,
        // The track's own artist first: on a compilation grandparentTitle is
        // the album artist, and every song would read "Various Artists".
        artist = originalTitle ?: grandparentTitle ?: "Unknown Artist",
        title = title,
        album = parentTitle ?: "Unknown Album",
        albumArtist = grandparentTitle ?: "Unknown Artist",
        albumId = parentRatingKey,
        // The album's sleeve first: a track's own thumb is usually that very
        // path, and where a file carries its own art it is still the same
        // picture — keyed by the album it is cached once, not once per song.
        coverArtId = parentThumb ?: thumb,
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

        /** Songs per radio when the caller leaves it to the server. */
        private const val RADIO_DEFAULT = 50
        private const val RADIO_TAG = "AmpRadio"

        /** How many fresh stations to deal from before calling the radio full. */
        private const val STATION_ASKS = 5

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

        /** Plex's stream types: 1 video, 2 audio, 3 subtitle, 4 lyrics. */
        private const val LYRIC_STREAM = 4

        /**
         * Who we say we are. Plex wants these on every request, and uses the
         * client identifier to tie a token to a device — it must be stable, so
         * it is the app's own id rather than something generated per launch.
         *
         * [product] overrides `X-Plex-Product`, the player's own name, which is
         * what Plex shows a session as; null keeps the app's. `X-Plex-Device`
         * deliberately stays the hardware: a source name is set per source, and
         * two sources configured here are still the same phone — so naming the
         * device after one of them would make Plex read "Kitchen on Kitchen"
         * and lose which machine it was talking to.
         */
        private const val PLEX_TV_RESOURCES = "https://plex.tv/api/v2/resources?includeHttps=1"

        /** Where a Plex player listens for Companion commands. */
        private const val COMPANION_PORT = 32500

        /** Queue items a player is asked to fetch when nobody says otherwise. */
        const val DEFAULT_QUEUE_WINDOW = 150

        fun plexIdentity(product: String? = null): List<Pair<String, String>> = listOf(
            "X-Plex-Client-Identifier" to "com.sublunar.amp",
            "X-Plex-Product" to (product ?: "Amp"),
            "X-Plex-Version" to "1.0.0",
            "X-Plex-Device" to "Light Phone III",
            // A different field from the one above: this is the *friendly* name,
            // and it is what Plex prints as a session's "Player". Missing, Plex
            // falls back to the product — so a session read "Amp / Amp", the
            // app's name twice with no sign of which phone was playing.
            "X-Plex-Device-Name" to "Light Phone III",
            "X-Plex-Platform" to "Android",
        )
    }
}

class PlexException(message: String) : Exception(message)

/**
 * A Companion-capable player, from the server's list or the account's.
 *
 * Commands go to [directUrl] — the player's own Companion port — and never
 * through the server. Relaying was tried first, because that is what the
 * official controllers appear to do, and the server answers **404** for a
 * player it has not discovered itself: the Apple TV app registers with plex.tv
 * rather than announcing itself on the server's LAN, so the server has no such
 * player to relay to. Rather than keep a fallback that has never worked, a
 * player is listed only when it can be reached directly.
 */
data class PlexPlayer(
    /** The player's own client identifier — the command target. */
    val id: String,
    val name: String,
    val product: String,
    /** `http://host:32500` — the player's own door. */
    val directUrl: String,
    /**
     * Whether the device at [directUrl] said who it was.
     *
     * True for GDM (the reply carries its identity) and for the server's own
     * client list (the server discovered it). False for a device named only by
     * plex.tv, whose address is whatever the account has on file — that one is
     * asked before it is handed any audio.
     */
    val identityKnown: Boolean = false,
)

/**
 * A server-side play queue.
 *
 * [items] maps a track id to its *place* in this queue (`playQueueItemID`),
 * which is what the edit endpoints address — the same track appearing twice
 * has two of them, so the queue is edited by place, not by song.
 */
data class PlexQueue(
    val id: String,
    val items: Map<String, String> = emptyMap(),
    /**
     * The queue's version, which Plex bumps on every edit. Sent with a refresh
     * so a player re-reads the version we mean rather than whichever it
     * happens to fetch — two edits in quick succession are otherwise a race.
     */
    val version: String? = null,
)

/** What a player reports about its music playback. */
data class PlexPlayerTimeline(
    /** `playing`, `paused`, `stopped` or `buffering`. */
    val state: String,
    val timeMs: Long,
    val durationMs: Long,
    /** The playing track, in the server's terms — Amp's Plex track id. */
    val ratingKey: String?,
    /** 0–100 where the player reports one; null where volume isn't its to control. */
    val volume: Int?,
    /** The player's repeat: 0 off, 1 the track, 2 the queue. Null when unsaid. */
    val repeat: Int?,
    /** Whether the player is shuffling. Null when unsaid. */
    val shuffle: Boolean?,
    /**
     * What the player says it accepts — `playPause,stop,volume,seekTo,…` —
     * or null where it doesn't say. The player's own word on its capabilities,
     * which beats guessing from what a number does after we push it.
     */
    val controllable: String?,
)

/**
 * The attributes of every `<tag …>` in [xml], in document order.
 *
 * Companion bodies are flat attribute lists — the shape DlnaCast already
 * parses by hand for UPnP — so a full XML parser buys nothing here.
 */
internal fun plexXmlElements(xml: String, tag: String): List<Map<String, String>> {
    val out = mutableListOf<Map<String, String>>()
    var from = 0
    while (true) {
        val at = xml.indexOf("<$tag", from).takeIf { it >= 0 } ?: break
        // The character after the tag name has to end it, or "Timeline"
        // would also match "TimelineEntry".
        val after = xml.getOrNull(at + tag.length + 1)
        val end = xml.indexOf('>', at).takeIf { it >= 0 } ?: break
        if (after == null || after == ' ' || after == '>' || after == '/' || after == '\n' || after == '\t') {
            val attrs = ATTR.findAll(xml.substring(at, end)).associate {
                it.groupValues[1] to unescapeXml(it.groupValues[2])
            }
            out += attrs
        }
        from = end + 1
    }
    return out
}

private val ATTR = Regex("""([A-Za-z0-9_:-]+)="([^"]*)"""")

private fun unescapeXml(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    .replace("&amp;", "&")

/** The music `<Timeline>` out of a `/player/timeline/poll` body, if any. */
internal fun plexTimelineFrom(body: String): PlexPlayerTimeline? {
    val music = plexXmlElements(body, "Timeline").firstOrNull { it["type"] == "music" } ?: return null
    return PlexPlayerTimeline(
        state = music["state"] ?: "stopped",
        timeMs = music["time"]?.toLongOrNull() ?: 0L,
        durationMs = music["duration"]?.toLongOrNull() ?: 0L,
        ratingKey = music["ratingKey"]?.takeIf { it.isNotBlank() },
        volume = music["volume"]?.toIntOrNull(),
        controllable = music["controllable"]?.takeIf { it.isNotBlank() },
        repeat = music["repeat"]?.toIntOrNull(),
        shuffle = music["shuffle"]?.let { it == "1" || it.equals("true", ignoreCase = true) },
    )
}

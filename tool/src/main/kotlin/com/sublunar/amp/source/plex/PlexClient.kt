package com.sublunar.amp.source.plex

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
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
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.ArtistRef
import com.sublunar.amp.data.KnownStream
import com.sublunar.amp.data.LyricLine
import com.sublunar.amp.data.Lyrics
import com.sublunar.amp.data.LyricsRepository
import com.sublunar.amp.data.MusicFolder
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.NetworkGate
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.RefusedWrite
import com.sublunar.amp.data.ScanRequest
import com.sublunar.amp.data.ServerException
import com.sublunar.amp.data.SongChanges
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.serverClock
import com.sublunar.amp.data.placementMoves

/**
 * A Plex Media Server's music library behind [MusicServer]. Browsing, artwork,
 * streaming, playlists, play counts and star ratings all round-trip to the
 * server, so what is done here shows up in Plexamp and the other way round.
 * What Plex can't do is left out rather than faked: see PlexProvider.capabilities.
 *
 * The Plex Companion calls further down steer another Plex player.
 */
class PlexClient(
    /** A server connection URI, e.g. `http://192.168.1.10:32400`. */
    private val baseUrl: String,
    private val token: String,
    /** Needed to build the URIs that add items to a playlist or a play queue. */
    private val machineIdentifier: String = "",
    /** Overrides `X-Plex-Product`, the name Plex shows a session as; null keeps "Amp". */
    private val product: String? = null,
    /** Who this link says it is: see [plexIdentity] and MusicSource.deviceId. */
    private val clientIdentifier: String,
) : MusicServer {

    private val identityHeaders: List<Pair<String, String>> = plexIdentity(product, clientIdentifier)

    private val http = NetworkGate.httpClient { expectSuccess = false }

    /**
     * A second client for the two calls a person waits on, the reachability
     * ping and the stream decision. A separate client is a separate request
     * queue: on the shared one they waited behind a sync's hundreds of
     * requests, and a ping that waited too long read as "the server is gone".
     */
    private val quickClient = lazy { NetworkGate.httpClient(isolated = true) { expectSuccess = false } }
    private val quick get() = quickClient.value

    /** Serialises requests aimed at a player; see [companionXml]. */
    private val playerLock = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override fun close() {
        http.close()
        if (quickClient.isInitialized()) quick.close()
    }

    // --- Requests ------------------------------------------------------------

    private suspend fun fetch(path: String, params: List<Pair<String, String>> = emptyList()): PlexResponse =
        decode(rawBody(path, params))

    /** A GET's body as text; a refusal throws [PlexException] with its status. */
    private suspend fun rawBody(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        via: HttpClient = http,
    ): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val response = via.get(url) { plexHeaders() }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for $path", response.status.value)
        }
        return response.bodyAsText()
    }

    /** A POST whose answer matters: creating a play queue hands its items back. */
    private suspend fun rawPost(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val response = http.post(url) { plexHeaders() }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for $path", response.status.value)
        }
        return response.bodyAsText()
    }

    /**
     * A change the user is waiting to see land: returns when the server took
     * it and throws when it didn't, so a refusal and a dead link can be told
     * apart (see writeResultOf).
     */
    private suspend fun write(method: String, path: String, params: List<Pair<String, String>> = emptyList()) {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val response = when (method) {
            "GET" -> http.get(url) { plexHeaders() }
            "POST" -> http.post(url) { plexHeaders() }
            "PUT" -> http.put(url) { plexHeaders() }
            "DELETE" -> http.delete(url) { plexHeaders() }
            else -> error("unsupported method $method")
        }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for $path", response.status.value)
        }
    }

    private fun decode(text: String): PlexResponse =
        if (text.isBlank()) PlexResponse() else json.decodeFromString(text)

    /**
     * A request whose answer is only yes or no: says whether the server took
     * it, and logs when it didn't, with the status it answered. [detail] says
     * what the request was about, for a path that is the same for many —
     * never the parameters, which can carry the token.
     */
    private suspend fun sendChecked(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        method: String = "GET",
        via: HttpClient = http,
        detail: String? = null,
    ): Boolean {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val result = runCatching {
            when (method) {
                "GET" -> via.get(url) { plexHeaders() }
                "PUT" -> via.put(url) { plexHeaders() }
                "DELETE" -> via.delete(url) { plexHeaders() }
                else -> error("unsupported method $method")
            }.status
        }
        val status = result.getOrNull()
        val ok = status?.isSuccess() == true
        if (!ok) {
            val why = status?.let { "server answered ${it.value}" } ?: result.exceptionOrNull()?.message ?: "no answer"
            android.util.Log.w(TAG, "$method $path${detail?.let { " ($it)" }.orEmpty()} failed: $why")
        }
        return ok
    }

    private fun io.ktor.client.request.HttpRequestBuilder.plexHeaders() {
        header("X-Plex-Token", token)
        header("Accept", "application/json")
        identityHeaders.forEach { (k, v) -> header(k, v) }
    }

    override suspend fun ping() {
        // Sections is the cheapest call that proves both the address and the
        // token. Only the status is the answer.
        rawBody("/library/sections", via = quick)
    }

    /**
     * The server's own id, which playlist and play-queue URIs are built from.
     * Comes free with a linked account; a server reached by address and token
     * has to be asked.
     */
    private suspend fun identity(): String? =
        runCatching { fetch("/identity").container.machineIdentifier }.getOrNull()

    /**
     * [identity] asked without the token, failing with the reason. Plex answers
     * it to anyone, so a typed-in address is settled before the token is in
     * question: whatever turns this down is something other than Plex.
     */
    suspend fun publicIdentity(): String? {
        val response = http.get(baseUrl.trimEnd('/') + "/identity") {
            header("Accept", "application/json")
            identityHeaders.forEach { (k, v) -> header(k, v) }
        }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for /identity", response.status.value)
        }
        return decode(response.bodyAsText()).container.machineIdentifier
    }

    // --- Browsing ------------------------------------------------------------

    override suspend fun getMusicFolders(): List<MusicFolder> =
        fetch("/library/sections").container.directories
            .filter { it.type == "artist" }
            .map { MusicFolder(id = it.key, name = it.title) }

    /** The one section asked for, or every music section. */
    private suspend fun sectionIds(musicFolderId: String?): List<String> =
        musicFolderId?.let { listOf(it) } ?: getMusicFolders().map { it.id }

    override suspend fun getAllAlbums(musicFolderId: String?): List<Album> =
        sectionIds(musicFolderId)
            .flatMap { section -> pagedSection(section, ALBUM_TYPE).map { it.toAlbum() } }
            .distinctBy { it.id }

    /**
     * Everything of one type in a section, following the pages to the end.
     *
     * Sorted, because pages are offsets into a result set: without an order
     * the server may hand back the rows differently each time, dropping some
     * and repeating others across a page boundary.
     */
    private suspend fun pagedSection(section: String, type: String): List<PlexMetadata> =
        paged(
            "/library/sections/$section/all",
            listOf("type" to type, "sort" to "titleSort:asc"),
        )

    /**
     * A container's every item, a page at a time: Plex caps a large response
     * (saying so through `totalSize`), and a big playlist's items, each with
     * its full media description, are more than the phone can hold in one
     * body. No `sort` is added here: a playlist's order is its own, and a
     * section names its own (see [pagedSection]).
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
     * Plex's "Scan Library Files". Not forced: `force=1` walks every file
     * again, minutes of disk on a large library, where the plain refresh looks
     * only for what changed.
     *
     * Scanning is an owner's privilege, so a library shared with you refuses
     * it, and the refusal is reported: unreported, it would look exactly like
     * a scan that found nothing.
     */
    override suspend fun startServerScan(musicFolderId: String?): ScanRequest {
        val sections = runCatching { sectionIds(musicFolderId) }.getOrDefault(emptyList())
        if (sections.isEmpty()) return ScanRequest.REFUSED
        val started = sections.map { sendChecked("/library/sections/$it/refresh") }.any { it }
        return if (started) ScanRequest.STARTED else ScanRequest.REFUSED
    }

    /**
     * The albums whose songs Plex updated since [mark], from each of
     * [libraries]: a song's `updatedAt` moves when Plex re-reads or edits it.
     *
     * With no mark yet it starts watching from now rather than reading every
     * song in the library, which on a large one the phone can't hold.
     */
    override suspend fun songChangesSince(mark: String?, libraries: List<String?>): SongChanges {
        val now = serverClock(http.get(baseUrl.trimEnd('/') + "/identity") { plexHeaders() }).epochSecond
        val since = mark?.toLongOrNull() ?: return SongChanges.Nothing(now.toString())
        val sections = libraries.filterNotNull().ifEmpty { getMusicFolders().map { it.id } }
        val albumIds = mutableSetOf<String>()
        for (section in sections) {
            // Ids only, a page at a time: a library re-tagged wholesale answers
            // with every song in it, each with its full media description.
            var start = 0
            while (true) {
                val container = fetch(
                    "/library/sections/$section/all",
                    listOf(
                        "type" to TRACK_TYPE,
                        "updatedAt>>" to (since - MARK_OVERLAP_SECONDS).toString(),
                        "sort" to "titleSort:asc",
                        "X-Plex-Container-Start" to start.toString(),
                        "X-Plex-Container-Size" to PAGE_SIZE.toString(),
                    ),
                ).container
                container.metadata.mapNotNullTo(albumIds) { it.parentRatingKey }
                start += container.metadata.size
                if (container.metadata.isEmpty() || start >= (container.totalSize ?: container.size)) break
            }
        }
        return SongChanges.Albums(albumIds, now.toString())
    }

    override suspend fun serverScanning(musicFolderId: String?): Boolean = runCatching {
        fetch("/library/sections").container.directories
            .filter { musicFolderId == null || it.key == musicFolderId }
            .any { it.isRefreshing }
    }.getOrDefault(false)

    override suspend fun getArtistIndex(musicFolderId: String?): List<ArtistRef> =
        sectionIds(musicFolderId)
            .flatMap { pagedSection(it, ARTIST_TYPE) }
            .map { ArtistRef(id = it.ratingKey, name = it.title, imageId = it.thumb) }
            .distinctBy { it.id }

    /**
     * An artist's best-known songs: the query Plexamp's Popular Tracks runs.
     *
     * Popularity is `ratingCount`, the listener count in Plex's music metadata,
     * so it means "well known", not "played a lot on this server".
     * Compilations and live records are left out because they would fill the
     * list with second versions, and grouping by title collapses the rest. A
     * library with no rating counts returns nothing, and the app leaves the
     * section out rather than showing another list under the same name.
     *
     * The app's artist names come from track tags, so the artist is looked up
     * by name first, in an index built once and kept.
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
     * Songs like this one, from the first of Plex's own three answers that has
     * any, most song-specific first: the track's station, the sonically nearest
     * tracks (what Plexamp's Track Radio uses, where the server has done its
     * sonic analysis, a Plex Pass feature), then the artist's station, which is
     * what a server without either has.
     *
     * `includeStations=1` on an item's metadata names its station, and a play
     * queue created from the station's key is how a station hands out its
     * songs (python-plexapi's `Artist.station()` + `PlayQueue.fromStationKey`,
     * with the key's own `?type=10` kept). Empty when there is none: the caller
     * says so, and nothing plays instead.
     */
    override suspend fun getSimilarSongs(songId: String, count: Int): List<Track> {
        val limit = if (count > 0) count else RADIO_DEFAULT
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
        // A station hands out a few songs at a time and expects to be topped up
        // as the client reports its position. This client reads once, so it
        // asks again instead: each ask mints a fresh station with a fresh
        // handful, merged until there is enough or an ask brings nothing new.
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

    /** The first string under [name] anywhere in the tree. */
    private fun findString(node: JsonElement, name: String): String? = when (node) {
        is JsonObject -> (node[name] as? JsonPrimitive)?.contentOrNull
            ?: node.values.firstNotNullOfOrNull { findString(it, name) }
        is JsonArray -> node.firstNotNullOfOrNull { findString(it, name) }
        else -> null
    }

    /**
     * Walked rather than declared: Plex nests its Stations block differently
     * between XML and JSON, and the one stable fact is a playlist entry whose
     * key says `/station/`. The first such key is the item's own station.
     */
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

    /** Settles the transcoder's decision for [sessionId]: see [transcodeParams]. */
    override suspend fun prepareStream(
        songId: String,
        format: StreamFormat,
        sessionId: String?,
    ): Boolean {
        // Nothing to decide without a session to decide for: the stream URL
        // leaves the identifier off in that case too, and Plex only enforces
        // this against sessions it is tracking.
        if (sessionId.isNullOrBlank()) return true
        return sendChecked(
            "/music/:/transcode/universal/decision",
            transcodeParams(songId, format, 0, sessionId),
            via = quick,
            detail = "song $songId",
        )
    }

    /**
     * Only a stream from the transcoder: the file itself has no decision to
     * settle. Every one of them, because Plex keeps the decision per song: a
     * session it is tracking, asked for a song it has no decision for, is
     * refused ("session lacking decision for transcode of key …").
     */
    override fun streamNeedsDecision(track: Track, format: StreamFormat): Boolean =
        fromTranscoder(track, format)

    /** See [MusicServer.oneSessionPerPlayer]: what Plexamp does, and what the dashboard expects. */
    override val oneSessionPerPlayer: Boolean get() = true

    /**
     * The transcoder's output is always a live MP3 encode, never served in
     * ranges, so there is nothing to find out by fetching it. And a fetch would
     * take the transcode of that very song a moment before the speaker asks for
     * it, and the speaker then turns the song away.
     */
    override fun knownStream(track: Track, format: StreamFormat): KnownStream? =
        if (fromTranscoder(track, format)) KnownStream(mime = "audio/mpeg", ranges = false) else null

    /** Whether [streamUrl] sends this track's stream through the transcoder rather than the file. */
    private fun fromTranscoder(track: Track, format: StreamFormat): Boolean =
        format != StreamFormat.RAW || track.streamPath.isBlank()

    /**
     * Plex's universal transcoder, which takes a metadata key rather than a
     * file. `offset` starts the stream that many seconds in. Everything it
     * transcodes comes out as MP3 (see [STREAM_FORMATS]). Asked to direct-play,
     * it returns the original bytes behind an `.mp3` URL, which anything
     * trusting the extension (a UPnP renderer, say) believes, so the original
     * goes through the [Track] overload wherever the file is known. For
     * [sessionId] see [transcodeParams].
     */
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
     * What a transcode request is made of, for [prepareStream] and the stream
     * both. The two have to match: the decision is settled for one playback and
     * the stream claims it, so anything that differs describes another playback
     * and leaves the stream without a decision.
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
            // Never shared with a download: Plex keeps one transcode per
            // identifier and tears down its previous holder, so a download and
            // the playing stream under one id would have one kill the other (a
            // 400, and playback stops dead). The player's streams share the
            // listening session's id one after another, which is safe because
            // it fetches them one at a time (see PlaybackController.sessionIdFor).
            // A download is given none.
            if (!sessionId.isNullOrBlank()) add("X-Plex-Session-Identifier" to sessionId)
            add("X-Plex-Token" to token)
            identityHeaders.forEach { (k, v) -> add(k to v) }
        }
    }

    /**
     * Original quality straight from the file, when the track's metadata gave
     * its path. Plex serves it with the real container and content type, so a
     * FLAC arrives as a FLAC, seek table intact.
     */
    override fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
        sessionId: String?,
    ): String {
        if (fromTranscoder(track, format)) {
            return streamUrl(track.id, format, timeOffsetSeconds, estimateContentLength, sessionId)
        }
        // No transcode session behind the file, so nothing to attach sessionId to.
        return partUrl(track, download = false)
    }

    /**
     * The same file asked for as a download, for the songs whose direct-play
     * address Plex won't serve: a file whose media analysis Plex never
     * finished is answered 500 at `download=0` and served byte for byte at
     * `download=1`. Only the second choice, because Plex holds `download=1`
     * to the account's "Allow downloads", and a shared account kept from
     * downloading would be refused every song instead of just these.
     */
    override fun alternateOriginalUrl(track: Track): String? =
        if (track.streamPath.isBlank()) null else partUrl(track, download = true)

    /**
     * A copy to keep. The original is the file itself asked for as a download,
     * which serves files Plex never finished analysing (see
     * [alternateOriginalUrl]) and lets the server enforce "Allow downloads".
     * For anything else `download=1` asks the transcoder for a complete file:
     * the live encode `start.mp3` produces has no Xing header, so a player
     * guesses a variable-rate copy's length from its size, wrongly.
     */
    override fun downloadUrl(track: Track, format: StreamFormat): String {
        if (format == StreamFormat.RAW && track.streamPath.isNotBlank()) {
            return partUrl(track, download = true)
        }
        val query = (transcodeParams(track.id, format, 0, null) + ("download" to "1"))
            .joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + "/music/:/transcode/universal/start.mp3?$query"
    }

    /** The file at the path the track's metadata gave, to play or to keep. */
    private fun partUrl(track: Track, download: Boolean): String =
        baseUrl.trimEnd('/') + track.streamPath +
            (if (track.streamPath.contains('?')) "&" else "?") +
            "download=${if (download) 1 else 0}&X-Plex-Token=" + enc(token)

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
                // Not plexHeaders(): this serves the lyric file itself, and
                // under `Accept: application/json` Plex answers 404.
                header("X-Plex-Token", token)
                header("Accept", "*/*")
                identityHeaders.forEach { (k, v) -> header(k, v) }
            }
            if (response.status.isSuccess()) response.bodyAsText() else null
        }.getOrNull()
        if (raw.isNullOrBlank()) return null
        // Timestamps where the file has them; otherwise a plain sheet of lines.
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
     * The cover through Plex's own image resizer, which is what its clients
     * use: the stored art is routinely 2000px and several megabytes.
     * `upscale=0` leaves a small original alone rather than blowing it up.
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
        // Now-playing is reportTimeline's job. Marking the track played on a
        // start notice would count everything that was skipped past.
        if (!submission) return
        // Throws when it didn't land, so a play made while the server was away
        // is kept and sent again.
        write(
            "GET",
            "/:/scrobble",
            listOf("key" to songId, "identifier" to LIBRARY_IDENTIFIER),
        )
    }

    /**
     * A heartbeat for whatever is watching `/status/sessions`, Plex's own
     * dashboard chiefly: without it Plex has no session to list as "now
     * playing". `time`/`duration` are milliseconds here, unlike most of Plex's
     * seconds.
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
        // Checked, so a refusal is logged: a refused "stopped" leaves the
        // session on the dashboard, holding a stream, for minutes.
        sendChecked("/:/timeline", params, detail = "${state.plexValue} $songId under $sessionId")
    }

    private val TimelineState.plexValue: String
        get() = when (this) {
            TimelineState.PLAYING -> "playing"
            TimelineState.PAUSED -> "paused"
            TimelineState.STOPPED -> "stopped"
            TimelineState.BUFFERING -> "buffering"
        }

    /** Plex rates 0–10, so a star is worth two. */
    override suspend fun setRating(id: String, stars: Int) {
        write(
            "PUT",
            "/:/rate",
            listOf(
                "key" to id,
                "identifier" to LIBRARY_IDENTIFIER,
                "rating" to (stars.coerceIn(0, 5) * 2).toString(),
            ),
        )
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

    override suspend fun getPlaylistTracks(id: String): List<Track> =
        paged("/playlists/$id/items").map { it.toTrack() }

    /**
     * Plex has no call for an empty playlist: creating one means naming its
     * contents, and a request without a `uri` is refused. So the songs go in
     * here rather than in a second pass (see [MusicServer.createPlaylist]), and
     * an empty one is never offered (see PlexProvider.capabilities).
     */
    override suspend fun createPlaylist(name: String, songIds: List<String>): String? {
        if (songIds.isEmpty()) throw RefusedWrite()
        val uri = itemUri(songIds.joinToString(","))
        val response = http.post(
            baseUrl.trimEnd('/') +
                "/playlists?type=audio&smart=0&title=${enc(name)}&uri=${enc(uri)}",
        ) { plexHeaders() }
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for /playlists", response.status.value)
        }
        // Made, whatever the body says: an answer this can't read is still a playlist on the server.
        return runCatching {
            decode(response.bodyAsText()).container.metadata.firstOrNull()?.ratingKey
        }.getOrNull()
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        write("PUT", "/playlists/$id", listOf("title" to name))
    }

    override suspend fun deletePlaylist(id: String) {
        write("DELETE", "/playlists/$id")
    }

    override suspend fun addToPlaylist(id: String, songId: String) {
        write("PUT", "/playlists/$id/items", listOf("uri" to itemUri(songId)))
    }

    /**
     * Plex removes a playlist entry by its own id, not the song's: the same
     * song can appear twice and only one of them is meant. The positions are
     * indices into the playlist as the app last read it, so the entry ids are
     * re-read here once rather than remembered.
     *
     * One delete per entry, one at a time: Plex has no call that takes several,
     * and deletes sent together didn't all land. A refusal doesn't stop the
     * rest, since each entry is named by its own id, but it makes the answer
     * false, and the caller re-reads the playlist.
     */
    override suspend fun removeFromPlaylistAt(id: String, at: Map<Int, String>): Boolean {
        if (at.isEmpty()) return true
        val entries = playlistEntries(id)
        val wanted = at.map { (index, songId) ->
            // Not the song the caller saw there: the playlist has changed under it.
            entries.getOrNull(index)?.takeIf { it.first == songId }?.second ?: run {
                android.util.Log.w(TAG, "removeFromPlaylistAt($id): position $index isn't the song expected; nothing removed")
                return false
            }
        }
        var all = true
        for (entry in wanted) {
            if (!sendChecked("/playlists/$id/items/$entry", method = "DELETE")) all = false
        }
        return all
    }

    /**
     * Reordering is a sequence of moves: Plex will put one entry after another,
     * and has no call that takes a whole new running order. Entries already in
     * place are skipped, judged by `localOrder`, which follows the list as the
     * moves land.
     *
     * Moves only, never add-then-delete: Plex quietly ignores an add of a track
     * the playlist already holds, so a rebuild that re-adds and then deletes
     * the old copies deletes the only copies. A move can't lose a track. One at
     * a time, awaited, and the first refusal ends the pass, because every move
     * after it would be placed against an order the list isn't in.
     */
    override suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>): Boolean {
        val entries = playlistEntries(id)
        if (entries.size != orderedSongIds.size) {
            android.util.Log.w(
                TAG,
                "reorderPlaylist($id): wanted ${orderedSongIds.size} track(s) but the playlist has " +
                    "${entries.size}; refusing to touch it",
            )
            return false
        }
        // Duplicate song ids: pool entries per song id, hand out in original order.
        val bySong = mutableMapOf<String, MutableList<Long>>()
        for ((songId, entryId) in entries) {
            bySong.getOrPut(songId) { mutableListOf() }.add(entryId)
        }
        val orderedEntryIds = orderedSongIds.map { songId ->
            val queue = bySong[songId]
            if (queue.isNullOrEmpty()) {
                android.util.Log.w(
                    TAG,
                    "reorderPlaylist($id): wanted order references a track not in the playlist; refusing to touch it",
                )
                return false
            }
            queue.removeAt(0)
        }
        if (orderedEntryIds.size < 2) return true
        val localOrder = entries.map { it.second }.toMutableList()
        var moved = false
        suspend fun moveAfter(entryId: Long, afterId: Long): Boolean {
            if (localOrder.getOrNull(localOrder.indexOf(afterId) + 1) == entryId) return true
            val sent = sendChecked(
                "/playlists/$id/items/$entryId/move",
                listOf("after" to afterId.toString()),
                method = "PUT",
            )
            if (!sent) {
                android.util.Log.w(TAG, "reorderPlaylist($id): move failed partway; playlist left partially reordered, nothing lost")
                return false
            }
            localOrder.remove(entryId)
            localOrder.add(localOrder.indexOf(afterId) + 1, entryId)
            moved = true
            return true
        }
        // A move goes after another entry, so the top is reached in two: the new
        // first after the old one, then the old one after it.
        val first = orderedEntryIds.first()
        val front = localOrder.first()
        if (first != front && !(moveAfter(first, front) && moveAfter(front, first))) return false
        for ((entryId, afterId) in placementMoves(localOrder.toList(), orderedEntryIds)) {
            if (!moveAfter(entryId, afterId ?: return false)) return false
        }
        if (!moved) return true
        return runCatching { playlistEntries(id) }.getOrNull()?.map { it.second } == orderedEntryIds
    }

    /** Each entry's `(songId, playlistItemID)`, in playlist order. */
    private suspend fun playlistEntries(id: String): List<Pair<String, Long>> =
        paged("/playlists/$id/items").mapNotNull { item ->
            item.playlistItemID?.let { item.ratingKey to it }
        }

    /** `server://{machine}/com.plexapp.plugins.library/library/metadata/{ids}`, ids comma-separated. */
    private fun itemUri(ids: String, machine: String = machineIdentifier): String =
        "server://$machine/$LIBRARY_IDENTIFIER/library/metadata/$ids"

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

    private fun PlexMetadata.toTrack(): Track = Track(
        id = ratingKey,
        // The track's own artist first: on a compilation grandparentTitle is
        // the album artist, and every song would read "Various Artists".
        artist = originalTitle ?: grandparentTitle ?: "Unknown Artist",
        title = title,
        album = parentTitle ?: "Unknown Album",
        albumArtist = grandparentTitle ?: "Unknown Artist",
        albumId = parentRatingKey,
        // The album's sleeve first: a track's own thumb is usually the same
        // picture, and keyed by the album it is cached once, not once per song.
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

    // --- Plex Companion: steering another Plex player ------------------------
    //
    // Plex's own remote-control protocol. Commands go straight to the player's
    // Companion port (see PlexPlayer); the player pulls the audio from the
    // server itself and, unlike a DLNA renderer, holds the whole play queue and
    // walks it on its own. These endpoints answer XML, so they go through
    // [companionXml] and the attribute parser at the bottom of this file.

    /**
     * Companion-capable players, from two lists because neither has everyone:
     * the server's `/clients` knows only players on its own LAN, and the Apple
     * TV app announces itself to plex.tv instead, which the account's
     * resources cover.
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
            json.decodeFromString<List<PlexResource>>(response.bodyAsText())
                .filter { it.provides.contains("player") && it.clientIdentifier.isNotBlank() }
                // Not filtered on plex.tv's `presence`, which goes stale: an
                // Apple TV that slept and woke reads as absent while playing.
                // A player that answers nothing simply fails to take the cast,
                // which is visible, unlike a device missing from the list.
                .mapNotNull { resource ->
                    // The local address first: plain http, no round trip
                    // through Plex's relay. A player with no address can't be
                    // commanded, so it isn't offered.
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
        // another source name nothing here, and the server rejects the lot
        // with no hint which it was.
        val alien = trackIds.filterNot { it.all(Char::isDigit) }
        if (alien.isNotEmpty()) {
            android.util.Log.i(
                "AmpPlex",
                "queue holds ${alien.size} track(s) that aren't Plex ids, e.g. ${alien.first()}",
            )
            return@runCatching null
        }
        val body = companionXml(
            "/playQueues",
            listOf(
                "type" to "audio",
                "uri" to itemUri(trackIds.joinToString(","), mid),
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
     * Re-read a queue, asking for [window] items around the current one: an
     * edit's own answer carries only as much of the queue as Plex likes, and
     * an item whose place isn't known can't be moved or removed.
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
        queueFrom(
            companionXml(
                "/playQueues/${queue.id}",
                listOf("uri" to itemUri(trackIds.joinToString(","), mid)) +
                    if (next) listOf("next" to "1") else emptyList(),
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
     * Whether the player at this address really is the one we mean. A device
     * listed by plex.tv is named by whatever connection the account has on
     * file, and a DHCP lease that moved would have us commanding whatever
     * answers there now. GDM replies carry their own identity.
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
     * Ask [target] to push its timeline to us at [port] (see
     * PlexCompanionListener) instead of being asked every second. Polling
     * remains the fallback for a player that won't subscribe.
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
        window: Int,
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
                // The window the queue was pushed with: a smaller one here and
                // the player re-reads less of the queue than we pushed.
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
     * Jump to a track *within the queue the player already holds*. Not
     * [companionPlay]: that hands the player a queue to fetch, and pulling a
     * hundred-item container over the WAN takes long enough that the command
     * times out.
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
    private suspend fun companionXml(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        target: PlexPlayer? = null,
        post: Boolean = false,
        put: Boolean = false,
        delete: Boolean = false,
    ): String {
        val host = target?.directUrl ?: baseUrl
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        val url = host.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
        val headers: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { companionHeaders(target) }
        suspend fun send() = when {
            post -> http.post(url, headers)
            put -> http.put(url, headers)
            delete -> http.delete(url, headers)
            else -> http.get(url, headers)
        }
        // A player answers one caller at a time. The poll runs every second, so
        // without this a command lands on top of it, the socket wedges, and
        // enough of those in a row take the player down altogether.
        val response = if (target != null) playerLock.withLock { send() } else send()
        if (!response.status.isSuccess()) {
            // The server says why in the body.
            val why = runCatching { response.bodyAsText() }.getOrDefault("").take(300).replace('\n', ' ')
            throw PlexException("Plex says ${response.status.value} for $path at $host — $why")
        }
        return response.bodyAsText()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.companionHeaders(target: PlexPlayer?) {
        header("X-Plex-Token", token)
        // XML on purpose: a player answers XML whatever is asked for, so asking
        // for it everywhere keeps one parser.
        header("Accept", "application/xml")
        identityHeaders.forEach { (k, v) -> header(k, v) }
        if (target != null) {
            header("X-Plex-Target-Client-Identifier", target.id)
            // A player answering for itself refuses an unknown controller with
            // no provides header before the command is read.
            header("X-Plex-Provides", "controller")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "PlexClient"

        private const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"

        /** Songs per radio when the caller leaves it to the server. */
        private const val RADIO_DEFAULT = 50
        private const val RADIO_TAG = "AmpRadio"

        /** How many fresh stations to deal from before calling the radio full. */
        private const val STATION_ASKS = 5

        /**
         * The original file, or MP3: Plex's universal music transcoder produces
         * MP3 whatever is asked for, with nothing in the response to admit it,
         * so the formats it can't serve are absent rather than silently swapped.
         */
        val STREAM_FORMATS: List<StreamFormat> = listOf(StreamFormat.RAW, StreamFormat.MP3)

        /** Items per request. Big enough to be one round trip on most libraries. */
        private const val PAGE_SIZE = 500

        /** How far before the last mark each ask for changes reaches: a row is stamped before it is written. */
        private const val MARK_OVERLAP_SECONDS = 15 * 60L

        private const val ARTIST_TYPE = "8"
        private const val ALBUM_TYPE = "9"
        private const val TRACK_TYPE = "10"

        /** What Plexamp asks for when it shows Popular Tracks. */
        private const val TOP_SONGS_LIMIT = 100

        /** [PlexStream.streamType] of a lyric stream. */
        private const val LYRIC_STREAM = 4

        private const val PLEX_TV_RESOURCES = "https://plex.tv/api/v2/resources?includeHttps=1"

        /** Where a Plex player listens for Companion commands. */
        private const val COMPANION_PORT = 32500

        /**
         * A player name as a header can carry it: ASCII only. A curly quote,
         * which a phone's keyboard types for an apostrophe, made every request
         * fail before it was sent. Plex reads headers as they are, so curly
         * quotes are folded to plain ones rather than escaped, and anything
         * else is left out.
         */
        internal fun String.headerSafe(): String = buildString {
            for (c in this@headerSafe) when (c) {
                in ' '..'~' -> append(c)
                '\u2018', '\u2019' -> append('\'')
                '\u201C', '\u201D' -> append('"')
            }
        }.trim().ifEmpty { "Amp" }

        /**
         * The client identifier every install of Amp used to send, and which
         * links made before MusicSource.deviceId was kept still send: their
         * tokens were issued to it, so it never changes. Each new link gets its
         * own ([newClientIdentifier]), so each phone is a device of its own to Plex.
         */
        const val LEGACY_CLIENT_ID = "com.sublunar.amp"

        /** An identifier for one link, kept with its source for as long as the token is. */
        fun newClientIdentifier(): String = "amp-" + java.util.UUID.randomUUID().toString()

        /**
         * Who we say we are. Plex wants these on every request, and ties a token
         * to the client identifier, so it must be stable: minted when a server
         * is linked and kept on the source with the token it was issued.
         *
         * [product] overrides `X-Plex-Product`, which is what Plex shows a
         * session as; null keeps the app's. `X-Plex-Device` stays the hardware:
         * two sources here are still the same phone, and naming the device after
         * one of them would make Plex read "Kitchen on Kitchen".
         */
        fun plexIdentity(
            product: String? = null,
            clientIdentifier: String,
        ): List<Pair<String, String>> = listOf(
            "X-Plex-Client-Identifier" to clientIdentifier,
            "X-Plex-Product" to (product?.headerSafe() ?: "Amp"),
            "X-Plex-Version" to "1.0.0",
            "X-Plex-Device" to "Light Phone III",
            // The *friendly* name, which Plex prints as a session's "Player".
            // Missing, Plex falls back to the product and a session reads "Amp / Amp".
            "X-Plex-Device-Name" to "Light Phone III",
            "X-Plex-Platform" to "Android",
        )
    }
}

/** [status] is the HTTP status when the server gave one — see Reachability, which reads a 5xx as "not there". */
class PlexException(message: String, status: Int? = null) : ServerException(message, status)

/**
 * A Companion-capable player, from the server's list or the account's.
 *
 * Commands go to [directUrl], the player's own Companion port, never through
 * the server: the server answers 404 for a player it has not discovered itself,
 * such as the Apple TV app, which registers with plex.tv instead. So a player
 * is listed only when it can be reached directly.
 */
data class PlexPlayer(
    /** The player's own client identifier — the command target. */
    val id: String,
    val name: String,
    val product: String,
    /** `http://host:32500`, the player's own door. */
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

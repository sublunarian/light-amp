package com.sublunar.amp.source.plex

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.RADIO_LENGTH
import com.sublunar.amp.data.RefusedWrite
import com.sublunar.amp.data.ScanRequest
import com.sublunar.amp.data.ServerException
import com.sublunar.amp.data.SongChanges
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.serverClock
import com.sublunar.amp.data.placementMoves
import com.sublunar.amp.source.ServerHttp
import com.sublunar.amp.source.queryOf
import com.sublunar.amp.source.serverJson
import com.sublunar.amp.source.serverUrl

/**
 * A Plex Media Server's music library behind [MusicServer]. Browsing, artwork,
 * streaming, playlists, play counts and star ratings all round-trip to the
 * server, so what is done here shows up in Plexamp and the other way round.
 * What Plex can't do is left out rather than faked: see PlexProvider.capabilities.
 *
 * The Plex Companion calls, which steer another Plex player, are extensions
 * of this client in companion/PlexCompanionCalls.kt.
 */
class PlexClient(
    /** A server connection URI, e.g. `http://192.168.1.10:32400`. */
    internal val baseUrl: String,
    internal val token: String,
    /** Needed to build the URIs that add items to a playlist or a play queue. */
    private val machineIdentifier: String = "",
    /** Overrides `X-Plex-Product`, the name Plex shows a session as; null keeps "Amp". */
    private val product: String? = null,
    /** Who this link says it is: see [plexIdentity] and MusicSource.deviceId. */
    private val clientIdentifier: String,
) : MusicServer {

    private val identityHeaders: List<Pair<String, String>> = plexIdentity(product, clientIdentifier)

    private val http = ServerHttp()

    /** Serialises requests aimed at a player — see the Companion calls. */
    internal val playerLock = Mutex()

    override fun close() = http.close()

    // --- Requests ------------------------------------------------------------

    /** [path] on [host] — the server unless a player is named — with [params] as its query. */
    private fun url(path: String, params: List<Pair<String, String>> = emptyList(), host: String = baseUrl): String =
        serverUrl(host, path, params)

    /**
     * A request with Plex's headers, its answer unread. The identity goes on
     * every request and the token on all but a [withToken] false one; Plex
     * answers XML unless [accept] asks for something else. [more] adds the
     * call's own headers.
     */
    internal suspend fun request(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        method: HttpMethod = HttpMethod.Get,
        host: String = baseUrl,
        accept: String = JSON,
        withToken: Boolean = true,
        via: HttpClient = http.bulk,
        more: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = via.request(url(path, params, host)) {
        this.method = method
        if (withToken) header("X-Plex-Token", token)
        header("Accept", accept)
        identityHeaders.forEach { (k, v) -> header(k, v) }
        more()
    }

    /** An answer as text; a refusal throws [PlexException] with its status. */
    private suspend fun body(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        method: HttpMethod = HttpMethod.Get,
        accept: String = JSON,
        withToken: Boolean = true,
        via: HttpClient = http.bulk,
    ): String {
        val response = request(path, params, method, accept = accept, withToken = withToken, via = via)
        if (!response.status.isSuccess()) {
            throw PlexException("Plex says ${response.status.value} for $path", response.status.value)
        }
        return response.bodyAsText()
    }

    private suspend fun fetch(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        method: HttpMethod = HttpMethod.Get,
    ): PlexResponse = decode(body(path, params, method))

    private fun decode(text: String): PlexResponse =
        if (text.isBlank()) PlexResponse() else serverJson.decodeFromString(text)

    /**
     * A change the user is waiting to see land: returns when the server took
     * it and throws when it didn't, so a refusal and a dead link can be told
     * apart (see writeResultOf).
     */
    private suspend fun write(method: HttpMethod, path: String, params: List<Pair<String, String>> = emptyList()) {
        body(path, params, method)
    }

    /**
     * A request whose answer is only yes or no: says whether the server took
     * it, and logs when it didn't, with the status it answered. [detail] says
     * what the request was about, for a path that is the same for many —
     * never the parameters, which can carry the token.
     */
    private suspend fun send(
        method: HttpMethod,
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        via: HttpClient = http.bulk,
        detail: String? = null,
    ): Boolean = runCatching { body(path, params, method, via = via) }
        .onFailure { error ->
            val why = (error as? PlexException)?.status?.let { "server answered $it" } ?: error.message ?: "no answer"
            Log.w(TAG, "${method.value} $path${detail?.let { " ($it)" }.orEmpty()} failed: $why")
        }
        .isSuccess

    /** [path], which may carry a query of its own, on the server with [params] and the token added. */
    private fun withToken(path: String, params: List<Pair<String, String>> = emptyList()): String =
        baseUrl.trimEnd('/') + path + (if ('?' in path) "&" else "?") + queryOf(params + ("X-Plex-Token" to token))

    override suspend fun ping() {
        // Sections is the cheapest call that proves both the address and the
        // token. Only the status is the answer.
        body("/library/sections", via = http.quick)
    }

    private suspend fun identity(): String? =
        runCatching { fetch("/identity").container.machineIdentifier }.getOrNull()

    @Volatile
    private var knownMachineId = machineIdentifier

    /**
     * The server's own id, which play-queue URIs are built from. Comes free
     * with a linked account; a server reached by address and token is asked,
     * once. Blank when it won't say.
     */
    internal suspend fun machineId(): String =
        knownMachineId.ifBlank { identity().orEmpty().also { knownMachineId = it } }

    /**
     * [identity] asked without the token, failing with the reason. Plex answers
     * it to anyone, so a typed-in address is settled before the token is in
     * question: whatever turns this down is something other than Plex.
     */
    suspend fun publicIdentity(): String? =
        decode(body("/identity", withToken = false)).container.machineIdentifier

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

    /** Everything of one type in a section, following the pages to the end, in [TITLE_SORT] order. */
    private suspend fun pagedSection(section: String, type: String): List<PlexMetadata> =
        paged("/library/sections/$section/all", listOf("type" to type, "sort" to TITLE_SORT))

    /**
     * A container's every item, a page at a time: Plex caps a large response
     * (saying so through `totalSize`), and a big playlist's items, each with
     * its full media description, are more than the phone can hold in one
     * body. No `sort` is added here: a playlist's order is its own, and a
     * section names its own (see [pagedSection]).
     */
    private suspend fun forEachPage(
        path: String,
        params: List<Pair<String, String>>,
        onPage: (PlexContainer) -> Unit,
    ) {
        var start = 0
        while (true) {
            val page = fetch(
                path,
                params + listOf(
                    "X-Plex-Container-Start" to start.toString(),
                    "X-Plex-Container-Size" to PAGE_SIZE.toString(),
                ),
            ).container
            onPage(page)
            start += page.metadata.size
            if (page.metadata.isEmpty() || start >= (page.totalSize ?: page.size)) break
        }
    }

    private suspend fun paged(path: String, params: List<Pair<String, String>> = emptyList()): List<PlexMetadata> {
        val items = mutableListOf<PlexMetadata>()
        forEachPage(path, params) { items += it.metadata }
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
        val started = sections.map { send(HttpMethod.Get, "/library/sections/$it/refresh") }.any { it }
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
        val now = serverClock(request("/identity")).epochSecond
        val since = mark?.toLongOrNull() ?: return SongChanges.Nothing(now.toString())
        val sections = libraries.filterNotNull().ifEmpty { getMusicFolders().map { it.id } }
        val albumIds = mutableSetOf<String>()
        for (section in sections) {
            // Ids only, kept a page at a time: a library re-tagged wholesale
            // answers with every song in it, each with its full media description.
            forEachPage(
                "/library/sections/$section/all",
                listOf(
                    "type" to TRACK_TYPE,
                    "updatedAt>>" to (since - MARK_OVERLAP_SECONDS).toString(),
                    "sort" to TITLE_SORT,
                ),
            ) { page -> page.metadata.mapNotNullTo(albumIds) { it.parentRatingKey } }
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
    override suspend fun getTopSongs(artistName: String): List<Track> {
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
                    "limit" to TOP_SONGS_LIMIT.toString(),
                ),
            ).container.metadata.map { it.toTrack() }
        }.getOrDefault(emptyList())
    }

    /**
     * Artist name to the section it lives in and its rating key. Kept only
     * when every section was read and some artist found: a failed section, or
     * a library still scanning, would leave artists without Popular songs
     * until the client is rebuilt.
     */
    private var artistKeys: Map<String, Pair<String, String>>? = null
    private val artistKeyLock = Mutex()

    private suspend fun artistLocation(name: String): Pair<String, String>? =
        artistKeyLock.withLock {
            artistKeys?.let { return@withLock it[name] }
            val keys = mutableMapOf<String, Pair<String, String>>()
            var whole = true
            val sections = runCatching { getMusicFolders() }.getOrElse {
                whole = false
                emptyList()
            }
            for (section in sections) {
                runCatching { pagedSection(section.id, ARTIST_TYPE) }
                    .onFailure { whole = false }
                    .getOrDefault(emptyList())
                    .forEach { keys.putIfAbsent(it.title, section.id to it.ratingKey) }
            }
            if (whole && keys.isNotEmpty()) artistKeys = keys
            keys[name]
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
    override suspend fun getSimilarSongs(songId: String): List<Track> {
        val own = runCatching { stationTracks(songId) }
            .onFailure { Log.w(RADIO_TAG, "plex track station failed: ${it.message}") }
            .getOrDefault(StationAnswer())
        if (own.tracks.isNotEmpty()) return own.tracks.take(RADIO_LENGTH)
        val nearest = runCatching { nearestTracks(songId, RADIO_LENGTH) }
            .onFailure { Log.w(RADIO_TAG, "plex nearest failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (nearest.isNotEmpty()) return nearest
        val artist = own.grandparentRatingKey ?: return emptyList()
        // A station hands out a few songs at a time and expects to be topped up
        // as the client reports its position. This client reads once, so it
        // asks again instead: each ask mints a fresh station with a fresh
        // handful, merged until there is enough or an ask brings nothing new.
        val merged = LinkedHashMap<String, Track>()
        for (ask in 1..STATION_ASKS) {
            val answer = runCatching { stationTracks(artist) }
                .onFailure { Log.w(RADIO_TAG, "plex artist station failed: ${it.message}") }
                .getOrDefault(StationAnswer())
            val before = merged.size
            answer.tracks.forEach { merged.putIfAbsent(it.id, it) }
            if (merged.size == before || merged.size >= RADIO_LENGTH) break
        }
        return merged.values.take(RADIO_LENGTH)
    }

    /** What asking an item for its station came to, plus the artist it named. */
    private class StationAnswer(
        val tracks: List<Track> = emptyList(),
        val grandparentRatingKey: String? = null,
    )

    /** An item's station, read once as a play queue; no tracks when it has none. */
    private suspend fun stationTracks(ratingKey: String): StationAnswer {
        val tree = serverJson.parseToJsonElement(body("/library/metadata/$ratingKey", listOf("includeStations" to "1")))
        val stationKey = findStationKey(tree)
        val artistKey = findString(tree, "grandparentRatingKey")
        if (stationKey == null) return StationAnswer(grandparentRatingKey = artistKey)
        val machine = machineId()
        if (machine.isBlank()) {
            Log.w(RADIO_TAG, "plex: no machine identifier, can't build the queue")
            return StationAnswer(grandparentRatingKey = artistKey)
        }
        // Read once rather than followed: `continuous` keeps Plex topping the
        // queue up for clients that report their position back, and this one
        // doesn't.
        val queue = fetch(
            "/playQueues",
            listOf(
                "type" to "audio",
                "uri" to "server://$machine/$LIBRARY_IDENTIFIER$stationKey",
                "continuous" to "1",
                "shuffle" to "0",
                "repeat" to "0",
                "includeChapters" to "0",
            ),
            HttpMethod.Post,
        )
        return StationAnswer(queue.container.metadata.filter { it.type == "track" }.map { it.toTrack() }, artistKey)
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
        return send(
            HttpMethod.Get,
            "/music/:/transcode/universal/decision",
            transcodeParams(songId, format, 0, sessionId),
            via = http.quick,
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
     * transcodes comes out as MP3 (see PlexProvider.streamFormats). Asked to
     * direct-play, it returns the original bytes behind an `.mp3` URL, which
     * anything trusting the extension (a UPnP renderer, say) believes, so the
     * original goes through [streamUrl]'s file wherever the file is known. For
     * [sessionId] see [transcodeParams].
     */
    private fun transcodeUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        sessionId: String?,
    ): String = url(TRANSCODE_START, transcodeParams(songId, format, timeOffsetSeconds, sessionId))

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
            return transcodeUrl(track.id, format, timeOffsetSeconds, sessionId)
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
        return url(TRANSCODE_START, transcodeParams(track.id, format, 0, null) + ("download" to "1"))
    }

    /** The file at the path the track's metadata gave, to play or to keep. */
    private fun partUrl(track: Track, download: Boolean): String =
        withToken(track.streamPath, listOf("download" to if (download) "1" else "0"))

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
        val key = streams.firstOrNull { it.streamType == LYRIC_STREAM && !it.key.isNullOrBlank() }?.key
            ?: return null
        // Any type: this serves the lyric file itself, which Plex answers 404
        // under `Accept: application/json`.
        val raw = runCatching { body(key, accept = "*/*") }.getOrNull()
        if (raw.isNullOrBlank()) return null
        // Timestamps where the file has them; otherwise a plain sheet of lines.
        LyricsRepository.parseLrc(raw)?.let { return it }
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return if (lines.isEmpty()) null else Lyrics(lines.map { LyricLine(null, it) }, synced = false)
    }

    /** Plex hands out cover paths, not ids; the token rides along in the URL. */
    override fun coverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        return withToken(coverArtId)
    }

    /**
     * The cover through Plex's own image resizer, which is what its clients
     * use: the stored art is routinely 2000px and several megabytes.
     * `upscale=0` leaves a small original alone rather than blowing it up.
     */
    override fun coverArtUrl(coverArtId: String?, maxSizePx: Int): String? {
        if (coverArtId.isNullOrBlank()) return null
        if (maxSizePx <= 0) return coverArtUrl(coverArtId)
        return url(
            "/photo/:/transcode",
            listOf(
                "width" to maxSizePx.toString(),
                "height" to maxSizePx.toString(),
                "minSize" to "1",
                "upscale" to "0",
                "url" to coverArtId,
                "X-Plex-Token" to token,
            ),
        )
    }

    // --- Writing back --------------------------------------------------------

    override suspend fun scrobble(songId: String, atMs: Long?, submission: Boolean) {
        // Now-playing is reportTimeline's job. Marking the track played on a
        // start notice would count everything that was skipped past.
        if (!submission) return
        // Throws when it didn't land, so a play made while the server was away
        // is kept and sent again.
        write(
            HttpMethod.Get,
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
        send(HttpMethod.Get, "/:/timeline", params, detail = "${state.plexValue} $songId under $sessionId")
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
            HttpMethod.Put,
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
        val created = body(
            "/playlists",
            listOf("type" to "audio", "smart" to "0", "title" to name, "uri" to itemUri(songIds)),
            HttpMethod.Post,
        )
        // Made, whatever the body says: an answer this can't read is still a playlist on the server.
        return runCatching { decode(created).container.metadata.firstOrNull()?.ratingKey }.getOrNull()
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        write(HttpMethod.Put, "/playlists/$id", listOf("title" to name))
    }

    override suspend fun deletePlaylist(id: String) {
        write(HttpMethod.Delete, "/playlists/$id")
    }

    override suspend fun addToPlaylist(id: String, songIds: List<String>) {
        write(HttpMethod.Put, "/playlists/$id/items", listOf("uri" to itemUri(songIds)))
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
                Log.w(TAG, "removeFromPlaylistAt($id): position $index isn't the song expected; nothing removed")
                return false
            }
        }
        var all = true
        for (entry in wanted) {
            if (!send(HttpMethod.Delete, "/playlists/$id/items/$entry")) all = false
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
            val sent = send(HttpMethod.Put, "/playlists/$id/items/$entryId/move", listOf("after" to afterId.toString()))
            if (!sent) {
                Log.w(TAG, "reorderPlaylist($id): move failed partway; playlist left partially reordered, nothing lost")
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
    internal fun itemUri(ids: List<String>, machine: String = machineIdentifier): String =
        "server://$machine/$LIBRARY_IDENTIFIER/library/metadata/${ids.joinToString(",")}"

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

    companion object {
        private const val TAG = "PlexClient"

        private const val JSON = "application/json"

        /**
         * The order a section is paged in. Pages are offsets into a result set:
         * without an order the server may hand back the rows differently each
         * time, dropping some and repeating others across a page boundary.
         */
        private const val TITLE_SORT = "titleSort:asc"

        private const val TRANSCODE_START = "/music/:/transcode/universal/start.mp3"

        private const val LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"

        private const val RADIO_TAG = "AmpRadio"

        /** How many fresh stations to deal from before calling the radio full. */
        private const val STATION_ASKS = 5

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

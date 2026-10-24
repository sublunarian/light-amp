package com.sublunar.amp.source.subsonic

import com.sublunar.amp.data.Album
import com.sublunar.amp.data.ArtistRef
import com.sublunar.amp.data.CANT_DO_THAT
import com.sublunar.amp.data.LikeTarget
import com.sublunar.amp.data.LyricLine
import com.sublunar.amp.data.Lyrics
import com.sublunar.amp.data.MusicFolder
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.RADIO_LENGTH
import com.sublunar.amp.data.SavedQueue
import com.sublunar.amp.data.ScanRequest
import com.sublunar.amp.data.ServerException
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SongChanges
import com.sublunar.amp.data.Starred
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.UNREACHABLE
import com.sublunar.amp.data.WriteResult
import com.sublunar.amp.data.isReorderOf
import com.sublunar.amp.data.md5Hex
import com.sublunar.amp.source.ServerAddress
import com.sublunar.amp.source.ServerHttp
import com.sublunar.amp.source.queryOf
import com.sublunar.amp.source.serverJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.random.Random
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where a Subsonic server is, and the login for it. */
data class SubsonicConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
    /** Sent as `c=`; null keeps the app's default ("amp"). */
    val clientName: String? = null,
) {
    companion object {
        /**
         * Base URLs to try for a raw input, in order — see [ServerAddress] for
         * when http:// follows https://. Drops a trailing slash and a `/rest`
         * suffix.
         */
        fun candidates(raw: String): List<String> =
            ServerAddress.candidates(
                raw.trim().trimEnd('/').replace(Regex("/rest$", RegexOption.IGNORE_CASE), ""),
            )
    }
}

/** An id no server can hold, for a probe that must not touch anything real. */
private const val PROBE_ID = "amp-capability-probe"

/** What a Subsonic error says when the server gave no message of its own. */
internal const val SUBSONIC_REQUEST_FAILED = "Request failed."

/** [status] is the HTTP status when the server gave one — see Reachability, which reads a 5xx as "not there". */
class SubsonicException(
    message: String,
    status: Int? = null,
    /**
     * The server answered in JSON, but not in Subsonic's envelope: what
     * Bandcamp sends for an endpoint it doesn't have (`{"error":true,
     * "error_message":"bad version"}`), where a full server would say
     * `status: failed` inside one.
     */
    val notSubsonic: Boolean = false,
    /**
     * Whatever answered didn't answer in JSON at all: a reverse proxy's login
     * page, a portal, a web server that routes unknown paths to a page. That
     * is something in front of the server talking, so it says nothing about
     * what the server itself implements.
     */
    val notJson: Boolean = false,
    /** Subsonic's own error code, when the server sent one (40 = wrong credentials, 70 = not found…). */
    val code: Int? = null,
    /**
     * The reply was a Subsonic envelope — `status: failed`, or one in a shape
     * this client couldn't read. Either way the endpoint exists.
     */
    val envelope: Boolean = false,
    /** The login was turned away without a Subsonic code to say so — see [bandcampTurnsLoginAway]. */
    val loginRefused: Boolean = false,
    cause: Throwable? = null,
) : ServerException(message, status, cause) {
    /**
     * The server has no such endpoint, as opposed to having one and refusing:
     * JSON that isn't Subsonic, or the status of a route that doesn't exist.
     * Never a page that isn't JSON (a proxy's login page would otherwise read
     * as a server missing everything), nor anything the network or a login throws.
     */
    val endpointMissing: Boolean get() = (notSubsonic && !notJson) || status == 404 || status == 501

    override fun writeResult(): WriteResult? = when {
        // A portal's or a proxy's page: the server itself was never asked.
        notJson -> WriteResult.Unreachable(UNREACHABLE)
        endpointMissing -> WriteResult.Refused(CANT_DO_THAT)
        refusedLogin -> WriteResult.Refused("The server refused the login")
        code == 50 -> WriteResult.Refused("The server doesn't allow that")
        code == 70 -> WriteResult.Refused("The server doesn't have that")
        else -> null
    }

    override val refusalWords: String get() = "The server said no"

    /** 40 is the wrong login, 41 a login that can't use tokens, 44 an API key the server won't take. */
    override val refusedLogin: Boolean get() = loginRefused || code == 40 || code == 41 || code == 44

    /** A page where JSON should be is never Subsonic's: see [notJson]. */
    override val fromServer: Boolean get() = !notJson
}

/**
 * Whether a failed request is Bandcamp turning the login away: a bare 500 with
 * nothing in it. From any other server that is an error of its own, so only
 * Bandcamp's address reads it this way. Its ping accepts any login, so this is
 * where a password changed on Bandcamp's site shows; the sign-in form reads
 * the same answer the same way.
 */
internal fun bandcampTurnsLoginAway(address: String, status: Int, page: Boolean, body: String?): Boolean =
    status == 500 && !page && body != null && body.isBlank() && isBandcampAddress(address)

/**
 * What `reportPlayback` is sent for one report — see SubsonicClient.reportTimeline.
 * Never a play of its own: Amp submits each play itself once enough of it was
 * heard (maybeSubmitPlay), so every report asks the server not to count one,
 * or each song would be counted twice.
 */
internal fun reportPlaybackParams(songId: String, state: TimelineState, positionMs: Long): List<Pair<String, String>> =
    listOf(
        "mediaId" to songId,
        "mediaType" to "song",
        "positionMs" to positionMs.coerceAtLeast(0L).toString(),
        "state" to when (state) {
            TimelineState.PLAYING -> "playing"
            TimelineState.PAUSED -> "paused"
            TimelineState.STOPPED -> "stopped"
            TimelineState.BUFFERING -> "starting"
        },
        "playbackRate" to "1.0",
        "ignoreScrobble" to "true",
    )

/**
 * A Subsonic API client, for every server that speaks it: Navidrome, gonic,
 * Airsonic, Ampache, Bandcamp… Authenticates with the token scheme
 * (md5(password + salt)) and returns the app's own models. Coroutine
 * cancellation propagates to the HTTP call.
 */
class SubsonicClient(
    val config: SubsonicConfig,
    /**
     * Whether this server listed `playbackReport`, and so is told where
     * playback is — see ServerFeatures.REPORT_PLAYBACK. Off for every server
     * that never said so, which then hears nothing new.
     */
    internal val reportsPlayback: Boolean = false,
) : MusicServer {

    private val http = ServerHttp()

    private val authQuery: String = run {
        val salt = randomSalt()
        queryOf(
            listOf(
                "u" to config.username,
                "t" to md5Hex(config.password + salt),
                "s" to salt,
                "v" to API_VERSION,
                "c" to (config.clientName?.ifBlank { null } ?: CLIENT_NAME),
                "f" to "json",
            ),
        )
    }

    override fun close() = http.close()

    // --- URL builders --------------------------------------------------------

    private fun restUrl(endpoint: String, params: List<Pair<String, String>>): String {
        val query = if (params.isEmpty()) authQuery else authQuery + "&" + queryOf(params)
        return "${config.baseUrl}/rest/$endpoint.view?$query"
    }

    /**
     * A copy to keep, rather than something to play.
     *
     * At original quality that is `download`, which hands back the stored file
     * untouched. `stream` would serve the same bytes, but a server may count a
     * request to it as a play, and filling the phone with a library should not
     * rewrite its play counts.
     *
     * At any other quality `stream` is the only way to a smaller file, since
     * `download` never transcodes. Sound here, unlike on Plex: these servers
     * encode mp3 at a constant bitrate, so a player's size ÷ bitrate fallback
     * for the missing duration header is exact. A server set to a variable
     * rate would need fixing at this seam.
     */
    override fun downloadUrl(track: Track, format: StreamFormat): String =
        if (format == StreamFormat.RAW) {
            restUrl("download", listOf("id" to track.id))
        } else {
            streamUrl(track, format, estimateContentLength = false)
        }

    /** Streaming URL for a song in the given format, with optional server-side time seek. */
    override fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        /**
         * Ask the server to declare an estimated Content-Length: on for
         * playback, where it lets the player byte-seek within a transcode
         * instead of reloading from 0:00. Off for downloads: when the real
         * encode comes out shorter than the estimate, the client waits for
         * bytes that never come and fails with "unexpected end of stream".
         */
        estimateContentLength: Boolean,
        /** Subsonic has no session concept; unused here. */
        sessionId: String?,
    ): String {
        val params = mutableListOf("id" to track.id)
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
        via: HttpClient = http.bulk,
    ): SubsonicBody {
        val response = via.get(restUrl(endpoint, params))
        if (!response.status.isSuccess()) {
            // A page behind an error status is something in front of the
            // server (a gateway, an auth portal) — not the server's own 500,
            // which is how Bandcamp says "not this login".
            val page = response.contentType()?.match(ContentType.Text.Html) == true
            val status = response.status.value
            val body = if (status == 500 && !page) response.bodyAsText() else null
            throw SubsonicException(
                "Server returned HTTP $status.",
                status,
                notJson = page,
                loginRefused = bandcampTurnsLoginAway(config.baseUrl, status, page, body),
            )
        }
        val text = response.bodyAsText()
        // Three failures to decode, meaning three things: no JSON at all is
        // something in front of the server (notJson); JSON without an envelope
        // is the server saying it has no such endpoint (notSubsonic); an
        // envelope this client can't read is the server answering in a shape we
        // didn't expect (envelope).
        val parsed = try {
            serverJson.decodeFromString<SubsonicEnvelope>(text)
        } catch (e: SerializationException) {
            // A bare top-level value ("Forbidden", "OK") parses as a JSON
            // literal, but it is plain text from something in front of the
            // server — a page in all but name — not a JSON reply.
            val tree = runCatching { serverJson.parseToJsonElement(text) }.getOrNull()
                ?.takeUnless { it is JsonPrimitive }
            val hasEnvelope = (tree as? JsonObject)?.containsKey("subsonic-response") == true
            throw SubsonicException(
                "Unexpected response from server.",
                notJson = tree == null,
                notSubsonic = tree != null && !hasEnvelope,
                envelope = hasEnvelope,
                cause = e,
            )
        }
        val body = parsed.response
            ?: throw SubsonicException("Unexpected response from server.", notSubsonic = true)
        if (body.status != "ok") {
            throw SubsonicException(
                body.error?.message?.takeIf { it.isNotBlank() } ?: SUBSONIC_REQUEST_FAILED,
                code = body.error?.code ?: 0,
                envelope = true,
            )
        }
        return body
    }

    /** Verify credentials by pinging the server. Throws on failure. */
    override suspend fun ping() {
        request("ping", via = http.quick)
    }

    /**
     * What this server implements, asked of it and kept — see [ServerFeatures].
     *
     * Every probe is a well-formed request that cannot change anything and
     * need not succeed: only the shape of the answer is read. The radio and
     * download probes only read a song the library holds; the ratings probe
     * rates an id no server can have, which a server with the call answers
     * "not found".
     *
     * A server already asked, at the same version with the same set of
     * probes, is asked again only about what it said it lacked: every "no" is
     * re-checked each sync, so one wrong answer (a proxy that misbehaved once,
     * a server mid-upgrade) heals by itself instead of hiding a feature for good.
     *
     * Throws when the server can't be reached: an unreachable server must
     * never read as one missing everything, so the ping goes first and a throw
     * from anywhere here leaves what was known alone.
     */
    override suspend fun probeFeatures(known: ServerFeatures?, sampleTrackId: String?): ServerFeatures? {
        // The guard the whole thing rests on: the address and login work right
        // now, so an endpoint answering "no such thing" below is the server
        // saying so, not a dead link.
        val server = request("ping", via = http.quick)
        val tag = listOfNotNull(server.version, server.type, server.serverVersion)
            .joinToString("/")
            .ifBlank { "unknown" }
        val asked = known != null &&
            known.checkedVersion == tag &&
            known.generation == ServerFeatures.GENERATION
        if (asked && known!!.missing.isEmpty()) return known

        val questions = if (asked) known!!.missing else ServerFeatures.ALL
        val missing = mutableListOf<String>()
        var unanswered = false
        for (endpoint in questions) {
            when (implements(endpoint, sampleTrackId)) {
                false -> missing += endpoint
                // No evidence either way: a 500, a 429, a gateway's page, a
                // reply this client can't read, or a probe that needs a song on
                // a library with none yet. On a re-check the old "no" stands;
                // on a first asking nothing is assumed and the record is left
                // unfinished, so the next sync asks again.
                null -> if (asked) missing += endpoint else unanswered = true
                true -> Unit
            }
        }
        android.util.Log.i(
            "AmpSync",
            "features of $tag (${if (asked) "re-checked" else "asked"}): " +
                "missing ${missing.ifEmpty { listOf("nothing") }}" +
                if (unanswered) ", some still unanswered (no song yet, or no clear reply)" else "",
        )
        return ServerFeatures(
            checkedVersion = tag,
            missing = missing,
            generation = if (unanswered) 0 else ServerFeatures.GENERATION,
        )
    }

    /**
     * Whether the server has [endpoint] at all — true or false only on real
     * evidence, null when the answer was no evidence or the probe needs a
     * song and there is none.
     */
    private suspend fun implements(endpoint: String, sampleTrackId: String?): Boolean? = when (endpoint) {
        ServerFeatures.SCAN_STATUS -> answers(endpoint, emptyList())
        ServerFeatures.SET_RATING -> answers(endpoint, listOf("id" to PROBE_ID, "rating" to "1"))
        ServerFeatures.SIMILAR_SONGS -> sampleTrackId?.let { answers(endpoint, listOf("id" to it, "count" to "1")) }
        ServerFeatures.DOWNLOAD -> sampleTrackId?.let { implementsDownload(it) }
        ServerFeatures.REPORT_PLAYBACK -> listsExtension(ServerFeatures.PLAYBACK_REPORT_EXTENSION)
        else -> true
    }

    /**
     * Whether the server names [extension] in its OpenSubsonic list — the way
     * a server offers a call that isn't Subsonic's own. Yes only when it is
     * listed. No when the list leaves it out, or the server has no list at
     * all: no such call, or a refusal in Subsonic's own words, which is what a
     * server from before OpenSubsonic answers a call it doesn't know. Anything
     * else is no evidence, as in [answers].
     */
    private suspend fun listsExtension(extension: String): Boolean? =
        try {
            extension in request("getOpenSubsonicExtensions", via = http.quick).extensionNames
        } catch (e: SubsonicException) {
            when {
                e.envelope && e.code != null -> false
                e.endpointMissing -> false
                else -> null
            }
        }

    /**
     * What one probe proved. Any Subsonic envelope — success or `status:
     * failed` — proves the endpoint exists; JSON without one, a 404 or a 501
     * proves it doesn't. Everything else (a 5xx, a 401 or 429, a page from
     * something in front of the server) proves nothing, and says so with null.
     */
    private suspend fun answers(endpoint: String, params: List<Pair<String, String>>): Boolean? =
        try {
            request(endpoint, params, via = http.quick)
            true
        } catch (e: SubsonicException) {
            when {
                e.envelope -> true
                e.endpointMissing -> false
                else -> null
            }
        }

    /**
     * `download` without downloading — the same three-way rule as [answers].
     *
     * Asked for the first byte and read from the headers: a server that
     * ignores the range is hung up on, since prepareGet/execute never reads an
     * audio body. A JSON reply is small and decides it: Subsonic's error
     * envelope means the call is there and only this file isn't, while JSON
     * without one is Bandcamp saying it has no such call. A page proves nothing.
     */
    private suspend fun implementsDownload(trackId: String): Boolean? =
        http.quick.prepareGet(restUrl("download", listOf("id" to trackId))) {
            header(HttpHeaders.Range, "bytes=0-0")
        }.execute { response ->
            val type = response.contentType()
            when {
                response.status.value == 404 || response.status.value == 501 -> false
                !response.status.isSuccess() -> null
                type?.match(ContentType.Application.Json) == true -> {
                    val tree = runCatching { serverJson.parseToJsonElement(response.bodyAsText()) }
                        .getOrNull() as? JsonObject
                    val envelope = tree?.get("subsonic-response") as? JsonObject
                    val code = ((envelope?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)
                        ?.content?.toIntOrNull()
                    when {
                        tree == null -> null
                        // Not authorized: the call is there, but this account
                        // can't use it — a Navidrome with downloads switched
                        // off. Original isn't offered, and the "no" is
                        // re-checked every sync.
                        code == 50 -> false
                        envelope != null -> true
                        else -> false
                    }
                }
                type?.match(ContentType.Text.Html) == true -> null
                else -> true
            }
        }

    // --- Browsing ------------------------------------------------------------

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
        // The record's own artist, from the same parent: AlbumID3's artist is
        // the album artist by definition, where a song's is whatever its file
        // carried, and a file without ALBUMARTIST would file every "feat."
        // guest as an artist of their own.
        val albumArtist = body.album?.recordArtist
        // The album's own cover for every track on it — see toTrack.
        return body.album?.song.orEmpty().map { it.toTrack(albumName, albumArtist, body.album?.coverArt) }
    }

    /**
     * What this account has liked, asked for with `getStarred2` — and, only
     * where the server has no such endpoint, the older `getStarred`.
     *
     * Bandcamp has `getStarred`, `star` and `unstar` but not `getStarred2`, and
     * the sync asks for likes first. Only a non-Subsonic answer falls back; a
     * full server's own error inside the envelope is still an error. The two
     * replies differ only in their album entries, and only ids are read here.
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

    /**
     * Whether a scan has run since [mark] — which is all Subsonic can say.
     *
     * Navidrome changes nothing between scans, so the same last scan as the
     * mark means nothing changed. A different one means something may have,
     * with no way to ask which songs: the sync walks the library instead. No
     * answer (a server without getScanStatus, or one mid-scan, whose last scan
     * is still moving) is the same as not knowing.
     */
    override suspend fun songChangesSince(mark: String?, libraries: List<String?>): SongChanges {
        val status = runCatching { request("getScanStatus").scanStatus }.getOrNull()
        val lastScan = status?.lastScan?.takeIf { it.isNotBlank() && !status.scanning }
            ?: return SongChanges.Unknown(mark = null)
        return if (lastScan == mark) SongChanges.Nothing(lastScan) else SongChanges.Unknown(lastScan)
    }

    override suspend fun serverScanning(musicFolderId: String?): Boolean =
        runCatching { request("getScanStatus").scanStatus?.scanning }.getOrNull() ?: false

    /**
     * An artist's most popular songs. Navidrome answers this from its Last.fm
     * agent, matched back onto the local library, so it needs Last.fm configured
     * server-side; it returns an empty list rather than failing when it isn't.
     *
     * No `count`: Subsonic reads one as the number of songs to return, so a 0
     * asks for none, where leaving it out gets the server's own default.
     */
    override suspend fun getTopSongs(artistName: String): List<Track> {
        return try {
            val body = request("getTopSongs", listOf("artist" to artistName))
            body.topSongs?.song.orEmpty().map { it.toTrack() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * `getSimilarSongs` takes the song's own id, and Navidrome resolves it to
     * the artist before asking its Last.fm agent for artists like them. With
     * no agent it still answers from that artist's own catalogue, so the radio
     * narrows rather than vanishes.
     */
    override suspend fun getSimilarSongs(songId: String): List<Track> {
        return try {
            val body = request("getSimilarSongs", listOf("id" to songId, "count" to RADIO_LENGTH.toString()))
            body.similarSongs?.song.orEmpty().map { it.toTrack() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getLyrics(songId: String): Lyrics? {
        return try {
            val body = request("getLyricsBySongId", listOf("id" to songId))
            val structured = body.lyricsList?.structuredLyrics.orEmpty()

            val synced = structured.firstOrNull { entry ->
                entry.synced && entry.line.any { it.start != null }
            }
            synced?.timedLines()?.takeIf { it.isNotEmpty() }?.let { return Lyrics(it, synced = true) }

            structured.firstOrNull { it.line.isNotEmpty() }?.let { plain ->
                unsynced(plain.line.map { it.value })?.let { return it }
            }
            body.lyrics?.value?.let { unsynced(it.split("\n")) }
        } catch (_: Exception) {
            null
        }
    }

    /** Lines without timings, blank ones left out; null when none are left. */
    private fun unsynced(texts: List<String?>): Lyrics? =
        texts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .map { LyricLine(timeMs = null, text = it) }
            .takeIf { it.isNotEmpty() }
            ?.let { Lyrics(it, synced = false) }

    // --- Writing back --------------------------------------------------------

    /** `star` and `unstar` take a song, an album and an artist each under a parameter of its own. */
    override suspend fun setLiked(target: LikeTarget, id: String, liked: Boolean) {
        val param = when (target) {
            LikeTarget.SONG -> "id"
            LikeTarget.ALBUM -> "albumId"
            LikeTarget.ARTIST -> "artistId"
        }
        request(if (liked) "star" else "unstar", listOf(param to id))
    }

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
     * Where playback is, for a server that listed `playbackReport`: its
     * now-playing follows a pause, a seek and a stop as they happen, rather
     * than a song it assumes plays from start to finish. Subsonic has no
     * session, so [sessionId] is unused, and the length is the server's own.
     * Every other server hears nothing.
     */
    override suspend fun reportTimeline(
        sessionId: String,
        songId: String,
        state: TimelineState,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (!reportsPlayback) return
        request("reportPlayback", reportPlaybackParams(songId, state, positionMs))
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
        // rather than an empty one, which just means there is nothing waiting.
        val queue = runCatching { request("getPlayQueue").playQueue }.getOrNull() ?: return null
        val ids = queue.entry.map { it.id }
        if (ids.isEmpty()) return null
        return SavedQueue(ids, queue.currentId, queue.position ?: 0L)
    }

    /**
     * Set a song's or album's star rating on the server, 0–5, where 0 removes
     * it. Throws when it didn't land, as the stars do, so the caller can tell a
     * server that refused from one it couldn't reach.
     */
    override suspend fun setRating(id: String, stars: Int) {
        request("setRating", listOf("id" to id, "rating" to stars.coerceIn(0, 5).toString()))
    }

    // --- Playlists -----------------------------------------------------------

    override suspend fun getPlaylists(musicFolderId: String?): List<Playlist> {
        val body = request("getPlaylists", musicFolderParam(musicFolderId))
        return body.playlists?.playlist.orEmpty().map { it.toPlaylist() }
    }

    /** Songs of a playlist, in order, as full tracks. */
    override suspend fun getPlaylistTracks(id: String): List<Track> {
        val body = request("getPlaylist", listOf("id" to id))
        return body.playlist?.entry.orEmpty().map { it.toTrack() }
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
        val known = playlistSongIds(id)
        if (at.any { (index, songId) -> known.getOrNull(index) != songId }) return false
        if (holdsHiddenSongs(id, known.size)) return false
        val params = mutableListOf("playlistId" to id)
        at.keys.forEach { params.add("songIndexToRemove" to it.toString()) }
        request("updatePlaylist", params)
        return true
    }

    /**
     * Subsonic has no reorder; overwrite the playlist with the full ordered id
     * list. Which is why the songs are checked against the server's own copy
     * first: an overwrite with a partial list doesn't reorder a playlist, it
     * deletes everything the list left out.
     */
    override suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>): Boolean {
        val known = playlistSongIds(id)
        if (!isReorderOf(orderedSongIds, known)) return false
        if (holdsHiddenSongs(id, known.size)) return false
        val params = mutableListOf("playlistId" to id)
        orderedSongIds.forEach { params.add("songId" to it) }
        request("createPlaylist", params)
        return true
    }

    /** The song ids of a playlist as the server holds it, in order, for an edit to check against. */
    private suspend fun playlistSongIds(id: String): List<String> {
        val playlist = request("getPlaylist", listOf("id" to id)).playlist
            ?: throw SubsonicException("Playlist not found.")
        return playlist.entry.map { it.id }
    }

    /**
     * Whether the server keeps songs in this playlist that getPlaylist leaves out: on
     * Navidrome, ones whose file has gone missing. Its own player keeps them through
     * every edit. Over Subsonic they can't be kept, since a remove's index counts them
     * and a reorder rewrites the playlist without them, so such a playlist isn't edited.
     */
    private suspend fun holdsHiddenSongs(id: String, shown: Int): Boolean {
        val stored = request("getPlaylists").playlists?.playlist.orEmpty().firstOrNull { it.id == id }?.songCount
        val hidden = stored != null && stored > shown
        if (hidden) android.util.Log.w("AmpSync", "playlist $id holds ${stored!! - shown} song(s) the server doesn't list; not editing it")
        return hidden
    }

    companion object {
        /**
         * Default `c=` value, when a source has no [SubsonicConfig.clientName]
         * of its own. Navidrome registers a Player under whatever this is.
         */
        private const val CLIENT_NAME = "amp"
        private const val API_VERSION = "1.16.1"
        private const val ALBUM_PAGE_SIZE = 500

        private fun randomSalt(): String {
            val chars = "0123456789abcdef"
            return buildString { repeat(16) { append(chars[Random.nextInt(16)]) } }
        }
    }
}

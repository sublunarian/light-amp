package com.sublunar.amp.source.jellyfin

import com.sublunar.amp.data.Album
import com.sublunar.amp.data.ArtistRef
import com.sublunar.amp.data.LikeTarget
import com.sublunar.amp.data.Lyrics
import com.sublunar.amp.data.MusicFolder
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.RADIO_LENGTH
import com.sublunar.amp.data.ScanRequest
import com.sublunar.amp.data.ServerException
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SongChanges
import com.sublunar.amp.data.Starred
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.isReorderOf
import com.sublunar.amp.data.placementMoves
import com.sublunar.amp.data.serverClock
import com.sublunar.amp.source.ServerHttp
import com.sublunar.amp.source.serverJson
import com.sublunar.amp.source.serverUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Jellyfin server's music library, behind the same [MusicServer] interface
 * Subsonic and Plex use.
 *
 * Closer to Subsonic than Plex is: real favourites, playlists that can be
 * created empty, and a transcoder that produces the format it is asked for.
 * What it lacks is left out rather than faked (see JellyfinProvider.capabilities):
 *
 * - **Star ratings.** Its user data has a favourite flag and a like/dislike,
 *   nothing for 1–5 stars to round-trip to.
 * - **Popular songs.** No per-artist popularity ranking over the API.
 * - **A shared play queue.** Nothing like Subsonic's `savePlayQueue`.
 *
 * Everything else — browsing, artwork, streaming, playlists, favourites, play
 * counts, now-playing and lyrics — round-trips with Jellyfin's own clients.
 */
class JellyfinClient(
    /** The server's address, e.g. `http://192.168.1.10:8096`. */
    private val baseUrl: String,
    /** From `/Users/AuthenticateByName`; every request carries it. */
    private val token: String,
    /**
     * Whose library this is. Jellyfin scopes nearly everything to a user —
     * favourites, plays, which libraries are visible — so it is captured at
     * sign-in and stored with the source.
     */
    private val userId: String,
    /** Overrides the `Client` this reports itself as; null keeps the app's own. */
    private val product: String? = null,
    /** What this source calls the phone, which its token was issued to — see [MusicSource.deviceId]. */
    private val deviceId: String = LEGACY_DEVICE_ID,
) : MusicServer {

    private val http = ServerHttp()

    override fun close() = http.close()

    // --- Requests ------------------------------------------------------------

    private fun url(path: String, params: List<Pair<String, String>> = emptyList()): String =
        serverUrl(baseUrl, path, params)

    private suspend fun get(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        via: HttpClient = http.bulk,
    ): HttpResponse = via.get(url(path, params)) { jellyfinHeaders() }.orThrow(path)

    private suspend inline fun <reified T> fetch(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): T = serverJson.decodeFromString(get(path, params).bodyAsText())

    private fun HttpResponse.orThrow(path: String): HttpResponse {
        if (!status.isSuccess()) throw JellyfinException("Jellyfin says ${status.value} for $path", status.value)
        return this
    }

    /**
     * Everything [path] lists, a page at a time to the end, so each round trip
     * stays small rather than one body holding a whole library. [onPage] sees
     * each page with the response it came in.
     */
    private suspend fun forEachPage(
        path: String,
        params: List<Pair<String, String>>,
        onPage: (HttpResponse, JellyfinItems) -> Unit,
    ) {
        var start = 0
        do {
            val response = get(path, params + listOf("StartIndex" to start.toString(), "Limit" to PAGE_SIZE.toString()))
            val page = serverJson.decodeFromString<JellyfinItems>(response.bodyAsText())
            onPage(response, page)
            start += page.items.size
        } while (page.continuesAfter(start))
    }

    private suspend fun pages(path: String, params: List<Pair<String, String>>): List<JellyfinItem> {
        val items = mutableListOf<JellyfinItem>()
        forEachPage(path, params) { _, page -> items += page.items }
        return items
    }

    /**
     * Whether there is a page after this one. A short page is the end; a full
     * one goes on unless the server's total says otherwise. A total it left
     * out reads as 0, which must not stop the paging at one page.
     */
    private fun JellyfinItems.continuesAfter(start: Int): Boolean =
        items.size >= PAGE_SIZE && (totalRecordCount == 0 || start < totalRecordCount)

    /**
     * A change the user is waiting to see land: returns when the server took
     * it and throws when it didn't, so a refusal and a dead link can be told
     * apart — see [writeResultOf].
     */
    private suspend fun write(
        method: HttpMethod,
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        payload: JsonObject? = null,
    ): HttpResponse = http.bulk.request(url(path, params)) {
        this.method = method
        jellyfinHeaders()
        if (payload != null) {
            contentType(ContentType.Application.Json)
            setBody(serverJson.encodeToString(JsonObject.serializer(), payload))
        }
    }.orThrow(path)

    /** A call whose answer doesn't matter, and whose failure mustn't propagate. */
    private suspend fun send(
        method: HttpMethod,
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        payload: JsonObject? = null,
    ): Boolean = runCatching { write(method, path, params, payload) }.isSuccess

    @Volatile private var version: String? = null

    /** The server's version, read once per client. A read that fails throws, and is tried again next time. */
    private suspend fun serverVersion(): String =
        version ?: fetch<JellyfinPublicInfo>("/System/Info/Public").version.also { version = it }

    /**
     * Whether this server has the per-user routes that take the user as a
     * query parameter (`/UserViews`, `/UserFavoriteItems`, `/UserPlayedItems`)
     * rather than in the path: 10.9 added them and keeps the old ones only for
     * backwards compatibility. Decided by the version, since a 404 can't tell a
     * missing route from a missing song; one that can't be parsed keeps to the
     * old routes, which every server still answers.
     */
    private suspend fun usesUserRoutes(): Boolean = hasUserRoutes(serverVersion()) ?: false

    /**
     * Whether Move puts an entry at the index it is given. 10.10.2 to 10.10.7 put
     * one moved down a place further, so those are only ever moved up. A version
     * that can't be read is treated as one of them.
     */
    private suspend fun movesLandWhereAsked(): Boolean = movesLandWhereAsked(serverVersion()) ?: false

    /** A [write] to one of the per-user routes, by whichever spelling this server has — see [usesUserRoutes]. */
    private suspend fun writeForUser(method: HttpMethod, current: String, legacy: String) {
        if (usesUserRoutes()) write(method, current, listOf("userId" to userId)) else write(method, legacy)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.jellyfinHeaders() {
        header("Authorization", authorization(product, token, deviceId))
        header("Accept", "application/json")
    }

    /**
     * Ends this sign-in on the server, so the phone stops being listed there
     * and the token stops working — what Log Out means.
     *
     * Only for a source with a device of its own: on the [LEGACY_DEVICE_ID]
     * every install once shared, Jellyfin also ends every *session* under that
     * id, whoever's it is — another person's Amp mid-song on the same server.
     */
    suspend fun signOut(): Boolean =
        deviceId != LEGACY_DEVICE_ID && send(HttpMethod.Post, "/Sessions/Logout")

    /**
     * The one thing about a Jellyfin server that depends on who is asking
     * rather than on its version: whether this account may keep copies. Read
     * from the account's own policy after every sync, since its owner can
     * change it at any time; a server that doesn't answer leaves what was known.
     */
    override suspend fun probeFeatures(known: ServerFeatures?, sampleTrackId: String?): ServerFeatures? {
        val me = runCatching { fetch<JellyfinMe>("/Users/Me") }.getOrNull() ?: return known
        return ServerFeatures(
            missing = if (me.policy.enableContentDownloading) emptyList() else listOf(ServerFeatures.KEEP_COPIES),
            generation = ServerFeatures.GENERATION,
        )
    }

    override suspend fun ping() {
        // Proves the address, the token and the user in one call: an expired
        // token answers 401 here rather than an empty library later.
        get("/Users/$userId", via = http.quick)
    }

    // --- Browsing ------------------------------------------------------------

    /**
     * The user's music libraries: the user's views rather than `/Items`, since
     * views are what this user may see, where the item tree holds everything on
     * the server.
     */
    override suspend fun getMusicFolders(): List<MusicFolder> {
        val views = if (usesUserRoutes()) {
            fetch<JellyfinItems>("/UserViews", listOf("userId" to userId))
        } else {
            fetch<JellyfinItems>("/Users/$userId/Views")
        }
        return views.musicLibraries()
    }

    override suspend fun getAllAlbums(musicFolderId: String?): List<Album> =
        paged(musicFolderId, ALBUM_TYPE).map { it.toAlbum() }.distinctBy { it.id }

    /**
     * Everything of one type in a library, or in all of them.
     *
     * The explicit sort is not cosmetic: a page is an offset into a result set,
     * and without an ordering the server may arrange the rows differently for
     * each request, dropping some rows and repeating others across a page
     * boundary. `SortName` is the same key the app's own lists use.
     */
    private suspend fun paged(
        musicFolderId: String?,
        itemType: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): List<JellyfinItem> = pages(
        "/Items",
        buildList {
            add("userId" to userId)
            add("IncludeItemTypes" to itemType)
            add("Recursive" to "true")
            add("SortBy" to "SortName")
            add("SortOrder" to "Ascending")
            add("Fields" to FIELDS)
            addAll(libraryParam(musicFolderId))
            addAll(extra)
        },
    )

    private fun libraryParam(musicFolderId: String?): List<Pair<String, String>> =
        if (musicFolderId.isNullOrBlank()) emptyList() else listOf("ParentId" to musicFolderId)

    /**
     * The albums whose songs Jellyfin saved since [mark], from every library:
     * the sync re-reads only albums it has.
     *
     * Jellyfin stamps DateLastSaved on every save (a probe filling in a
     * file's tags, a re-tag, an edit) and not on a scan that changed nothing.
     * It can be filtered on but not read, so the mark is the server's own
     * clock, and each ask reaches back [MARK_OVERLAP]: a row is stamped a
     * moment before it is written. The loudness scan writes gains without
     * stamping it, so the sync walks the library as well ([SongChanges.Albums.alsoWalk]).
     */
    override suspend fun songChangesSince(mark: String?, libraries: List<String?>): SongChanges {
        val since = mark?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (since == null) {
            val now = serverClock(http.bulk.get(url("/System/Info/Public")) { jellyfinHeaders() })
            return SongChanges.Everything(now.toString())
        }
        val albumIds = mutableSetOf<String>()
        var now: Instant? = null
        forEachPage(
            "/Items",
            listOf(
                "userId" to userId,
                "IncludeItemTypes" to TRACK_TYPE,
                "Recursive" to "true",
                "MinDateLastSaved" to since.minus(MARK_OVERLAP).toString(),
                "SortBy" to "SortName",
                "SortOrder" to "Ascending",
                "EnableImages" to "false",
                "EnableUserData" to "false",
            ),
        ) { response, page ->
            if (now == null) now = serverClock(response)
            page.items.mapNotNullTo(albumIds) { it.albumId }
        }
        return SongChanges.Albums(albumIds, (now ?: Instant.now()).toString(), alsoWalk = true)
    }

    override suspend fun getAlbumTracks(albumId: String): List<Track> =
        fetch<JellyfinItems>(
            "/Items",
            listOf(
                "userId" to userId,
                "ParentId" to albumId,
                "IncludeItemTypes" to TRACK_TYPE,
                "Fields" to FIELDS,
                // Disc first, then track: a two-disc record read in track order
                // alone interleaves the two.
                "SortBy" to "ParentIndexNumber,IndexNumber,SortName",
                "SortOrder" to "Ascending",
            ),
        ).items.map { it.toTrack() }

    /**
     * Jellyfin's instant mix: songs it judges to go with this one, from its own
     * genre and artist data rather than an outside agent. The seed comes back
     * first; the repository puts it first anyway, so it is dropped there rather
     * than counted on here.
     */
    override suspend fun getSimilarSongs(songId: String): List<Track> = runCatching {
        fetch<JellyfinItems>(
            "/Items/$songId/InstantMix",
            listOf("userId" to userId, "Fields" to FIELDS, "Limit" to RADIO_LENGTH.toString()),
        ).items.filter { it.type == TRACK_TYPE }.map { it.toTrack() }
    }.onFailure { android.util.Log.w("AmpRadio", "jellyfin instant mix failed: ${it.message}") }
        .getOrDefault(emptyList())

    /**
     * Favourites, which Jellyfin keeps per user and per item. Artists come back
     * as names: the app's artists are derived from track tags and have no
     * server id to match — the same reason [Starred] carries names everywhere.
     */
    override suspend fun getStarred(musicFolderId: String?): Starred {
        // A failure goes up rather than reading as "nothing is liked": the
        // sync takes this answer as the whole truth and unlikes everything
        // missing from it, so one bad request would wipe every like.
        val favourite = listOf("Filters" to "IsFavorite")
        val songs = paged(musicFolderId, TRACK_TYPE, favourite)
        val albums = paged(musicFolderId, ALBUM_TYPE, favourite)
        val artists = pages("/Artists", listOf("userId" to userId) + favourite + libraryParam(musicFolderId))
        return Starred(
            songIds = songs.map { it.id }.toSet(),
            albumIds = albums.map { it.id }.toSet(),
            artistNames = artists.map { it.name }.toSet(),
        )
    }

    /**
     * Jellyfin's own library scan, which is what makes it notice a record you
     * copied in. Administrator-only: a shared account is refused, and the
     * refusal is reported rather than swallowed, because a silent one looks
     * exactly like a scan that ran and found nothing.
     */
    override suspend fun startServerScan(musicFolderId: String?): ScanRequest =
        if (send(HttpMethod.Post, "/Library/Refresh")) ScanRequest.STARTED else ScanRequest.REFUSED

    override suspend fun serverScanning(musicFolderId: String?): Boolean = runCatching {
        fetch<List<JellyfinTask>>("/ScheduledTasks")
            .any { it.key == SCAN_TASK && it.state.equals("Running", ignoreCase = true) }
    }.getOrDefault(false)

    /**
     * The server's own artist records, which is where their pictures are — the
     * library's artists come from track tags and carry none.
     *
     * Throws rather than answering empty: an artist like looks its name up in
     * here, and an index that couldn't be read is not one without that artist.
     */
    override suspend fun getArtistIndex(musicFolderId: String?): List<ArtistRef> =
        pages(
            "/Artists",
            listOf("userId" to userId, "SortBy" to "SortName", "SortOrder" to "Ascending") + libraryParam(musicFolderId),
        ).map { ArtistRef(id = it.id, name = it.name, imageId = it.imageId()) }
            .distinctBy { it.id }

    // --- Media ---------------------------------------------------------------

    /**
     * A copy to keep — see [SubsonicClient.downloadUrl], which this mirrors.
     *
     * `/Items/{id}/Download` is the endpoint for taking a file away, and unlike
     * `/Audio/{id}/stream` it opens no playback session for a track nobody is
     * listening to. It serves the original only, so a smaller copy still comes
     * from the streaming transcode — sound while Jellyfin encodes mp3 at a
     * constant bitrate, for the reasons set out on the Subsonic one.
     */
    override fun downloadUrl(track: Track, format: StreamFormat): String =
        if (format == StreamFormat.RAW) {
            url("/Items/${track.id}/Download", listOf(TOKEN_PARAM to token))
        } else {
            streamUrl(track, format, estimateContentLength = false)
        }

    /**
     * Jellyfin's universal audio endpoint, which — unlike Plex's — really does
     * produce the container it is asked for. Original quality skips the
     * transcoder through `static=true`, which serves the file whole, with its
     * real container and content type, so a FLAC keeps its seek table.
     * `StartTimeTicks` starts a transcode that far in.
     */
    override fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
        sessionId: String?,
    ): String {
        if (format == StreamFormat.RAW) {
            return url(
                "/Audio/${track.id}/stream",
                listOf(
                    "static" to "true",
                    TOKEN_PARAM to token,
                ),
            )
        }
        val codec = format.id
        // Opus travels in an Ogg container: asking for container=opus gets a
        // file nothing will open.
        val container = if (format == StreamFormat.OPUS) "ogg" else codec
        val params = buildList {
            add("UserId" to userId)
            add("DeviceId" to deviceId)
            add(TOKEN_PARAM to token)
            // Container and codec together, which stops the server remuxing to
            // something else: "ogg" alone lets any Ogg file through untouched,
            // Vorbis at its own bitrate when Opus was asked for.
            add("Container" to "$container|$codec")
            add("AudioCodec" to codec)
            add("TranscodingContainer" to container)
            add("TranscodingProtocol" to "http")
            format.maxBitRate?.let { add("MaxStreamingBitrate" to (it * 1000).toString()) }
            if (timeOffsetSeconds > 0) {
                add("StartTimeTicks" to (timeOffsetSeconds.toLong() * 1000L * TICKS_PER_MS).toString())
            }
            // Ties a run of reports to the stream they are about, so Jellyfin's
            // dashboard shows one session rather than a new one per request.
            if (!sessionId.isNullOrBlank()) add("PlaySessionId" to sessionId)
        }
        return url("/Audio/${track.id}/universal", params)
    }

    /**
     * Jellyfin serves an image for any id it is given, answering 404 only when
     * the fetch happens — so a cover id is minted only for an item that said
     * it had one: see [imageId].
     */
    override fun coverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        return url("/Items/$coverArtId/Images/Primary", listOf(TOKEN_PARAM to token))
    }

    /**
     * The cover through Jellyfin's own resizer: stored art is routinely far
     * larger than a phone needs, and a page of covers drops from megabytes to
     * kilobytes.
     */
    override fun coverArtUrl(coverArtId: String?, maxSizePx: Int): String? {
        if (coverArtId.isNullOrBlank()) return null
        if (maxSizePx <= 0) return coverArtUrl(coverArtId)
        return url(
            "/Items/$coverArtId/Images/Primary",
            listOf(
                "maxWidth" to maxSizePx.toString(),
                "maxHeight" to maxSizePx.toString(),
                "quality" to COVER_QUALITY.toString(),
                TOKEN_PARAM to token,
            ),
        )
    }

    /**
     * Words for a song, from Jellyfin 10.9's lyrics endpoint. Servers before it
     * answer 404, which reads as "no lyrics" rather than as an error.
     */
    override suspend fun getLyrics(songId: String): Lyrics? = runCatching {
        fetch<JellyfinLyrics>("/Audio/$songId/Lyrics").toLyrics()
    }.getOrNull()

    // --- Writing back --------------------------------------------------------

    /**
     * Jellyfin separates the two statements the way this interface does: a
     * start notice opens a session, and a play is recorded against the item.
     * The play throws when it didn't land, so one made while the server was
     * away is kept and sent again; the start notice is only true now, and is
     * let go.
     */
    override suspend fun scrobble(songId: String, atMs: Long?, submission: Boolean) {
        if (!submission) {
            send(
                HttpMethod.Post,
                "/Sessions/Playing",
                payload = buildJsonObject {
                    put("ItemId", songId)
                    put("PositionTicks", 0L)
                },
            )
            return
        }
        writeForUser(HttpMethod.Post, "/UserPlayedItems/$songId", "/Users/$userId/PlayedItems/$songId")
    }

    /**
     * The heartbeat behind Jellyfin's "now playing", and what moves the progress
     * bar on its dashboard. Stopping is an endpoint of its own rather than a
     * state: a session reported paused for ever is still a session, and the
     * server keeps showing it.
     */
    override suspend fun reportTimeline(
        sessionId: String,
        songId: String,
        state: TimelineState,
        positionMs: Long,
        durationMs: Long,
    ) {
        val ticks = positionMs.coerceAtLeast(0L) * TICKS_PER_MS
        val payload = buildJsonObject {
            put("ItemId", songId)
            put("PositionTicks", ticks)
            put("PlaySessionId", sessionId)
            put("IsPaused", state == TimelineState.PAUSED)
            put("CanSeek", true)
        }
        val path = when (state) {
            TimelineState.STOPPED -> "/Sessions/Playing/Stopped"
            else -> "/Sessions/Playing/Progress"
        }
        send(HttpMethod.Post, path, payload = payload)
    }

    /** A favourite is one flag on any item, whatever it is. */
    override suspend fun setLiked(target: LikeTarget, id: String, liked: Boolean) {
        writeForUser(if (liked) HttpMethod.Post else HttpMethod.Delete, "/UserFavoriteItems/$id", "/Users/$userId/FavoriteItems/$id")
    }

    // --- Playlists -----------------------------------------------------------

    override suspend fun getPlaylists(musicFolderId: String?): List<Playlist> =
        paged(null, PLAYLIST_TYPE)
            // A playlist of films or episodes is not one to play here.
            .filter { it.mediaType.isNullOrBlank() || it.mediaType.equals(AUDIO_MEDIA, ignoreCase = true) }
            .distinctBy { it.id }
            .map { it.toPlaylist() }

    override suspend fun getPlaylistTracks(id: String): List<Track> =
        playlistEntries(id).map { it.toTrack() }

    /**
     * The songs in a playlist, which carry the entry ids a removal needs — the
     * same list, in the same order, that [getPlaylistTracks] shows, so a
     * position in one is a position in the other.
     */
    private suspend fun playlistEntries(id: String): List<JellyfinItem> =
        allPlaylistEntries(id).filter { it.type == TRACK_TYPE }

    /** Everything in a playlist, a page at a time: a large one in a single body is more than the phone can hold. */
    private suspend fun allPlaylistEntries(id: String): List<JellyfinItem> =
        pages("/Playlists/$id/Items", listOf("userId" to userId, "Fields" to FIELDS))

    override suspend fun createPlaylist(name: String, songIds: List<String>): String? {
        val payload = buildJsonObject {
            put("Name", name)
            put("UserId", userId)
            put("MediaType", "Audio")
            put(
                "Ids",
                buildJsonArray { songIds.forEach { add(JsonPrimitive(it)) } },
            )
        }
        val response = write(HttpMethod.Post, "/Playlists", payload = payload)
        // Made, whatever the body says: an answer this can't read is still a playlist on the server.
        return runCatching {
            serverJson.decodeFromString<JellyfinNamed>(response.bodyAsText()).id.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Only the name goes: Jellyfin's update leaves alone whatever a request
     * leaves out, but takes any `Ids` it is sent — an empty list included — as the
     * playlist's new contents. Servers before 10.9 have no playlist update at
     * all and refuse, which leaves the name as it was.
     */
    override suspend fun renamePlaylist(id: String, name: String) {
        write(HttpMethod.Post, "/Playlists/$id", payload = renameBody(name))
    }

    override suspend fun deletePlaylist(id: String) {
        write(HttpMethod.Delete, "/Items/$id")
    }

    override suspend fun addToPlaylist(id: String, songIds: List<String>) {
        write(HttpMethod.Post, "/Playlists/$id/Items", listOf("ids" to songIds.joinToString(","), "userId" to userId))
    }

    /**
     * Jellyfin removes a playlist entry by the entry's own id, not the song's —
     * the same song can appear twice and only one of them is meant. The indices
     * given are into the playlist as the app last read it, so the entry ids are
     * re-read here rather than remembered. `entryIds` takes a comma-separated
     * list, so however many go, it's one read and one delete.
     */
    override suspend fun removeFromPlaylistAt(id: String, at: Map<Int, String>): Boolean {
        if (at.isEmpty()) return true
        val entries = runCatching { playlistEntries(id) }.getOrNull() ?: return false
        val entryIds = at.map { (index, songId) ->
            // Not the song the caller saw there: the playlist has changed under it.
            entries.getOrNull(index)?.takeIf { it.id == songId }?.playlistItemId ?: return false
        }
        // Since Jellyfin 12 an entry's id is its song's, so the server removes
        // every copy of a song a playlist holds twice. Unless every copy is
        // what was asked, it is refused rather than taking the others with it.
        if (!takesEveryCopy(entries.map { it.playlistItemId }, entryIds)) return false
        return send(HttpMethod.Delete, "/Playlists/$id/Items", listOf("entryIds" to entryIds.joinToString(",")))
    }

    /**
     * Reordering is one move at a time — Jellyfin has no "here is the new
     * order" call. Each move is applied against the list as it stands, so the
     * entries are re-read after each one rather than computed up front.
     */
    override suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>): Boolean {
        // A wanted order that isn't the whole playlist would move its tracks to
        // the front and leave the rest trailing in an order nobody asked for.
        val all = runCatching { allPlaylistEntries(id) }.getOrNull() ?: return false
        val known = all.filter { it.type == TRACK_TYPE }
        if (!isReorderOf(orderedSongIds, known.map { it.id })) return false
        // Positions on the server count entries this app doesn't show, and a
        // song held twice can't be told apart from its other copy — a move
        // names an entry by its song since Jellyfin 12, and moves the first.
        // Either way the order that landed wouldn't be the one asked for.
        if (all.size != known.size || known.distinctBy { it.playlistItemId }.size != known.size) return false
        // Move names an entry, so a song's copies are handed out in their order.
        val pool = known.groupBy({ it.id }, { it.playlistItemId ?: return false }).mapValues { ArrayDeque(it.value) }
        val wanted = orderedSongIds.map { pool.getValue(it).removeFirst() }
        // Worked out from one read: Move takes the index the entry ends up at.
        val local = known.mapNotNull { it.playlistItemId }.toMutableList()
        var moved = false
        suspend fun move(entryId: String, to: Int): Boolean {
            local.remove(entryId)
            local.add(to, entryId)
            moved = true
            return send(HttpMethod.Post, "/Playlists/$id/Items/$entryId/Move/$to")
        }
        if (movesLandWhereAsked()) {
            for ((entryId, after) in placementMoves(local.toList(), wanted)) {
                val to = if (after == null) 0 else local.filterNot { it == entryId }.indexOf(after) + 1
                if (!move(entryId, to)) return false
            }
        } else {
            // Only ever up, which lands where asked on every version.
            for (target in wanted.indices) {
                if (local[target] != wanted[target] && !move(wanted[target], target)) return false
            }
        }
        if (!moved) return true
        // Read once more: a song this account can't see can put a move a place off.
        val landed = runCatching { playlistEntries(id) }.getOrNull() ?: return false
        return landed.map { it.id } == orderedSongIds
    }

    companion object {
        /**
         * How a URL that can't carry a header — a stream, a download, a cover —
         * carries the token.
         *
         * Not `api_key`: that is "legacy authorization", which Jellyfin 12
         * switches off, and the request then arrives with no token at all. A
         * transcode or a download answers 401, while originals and covers need
         * no login and keep working. Every server since 10.8 reads `ApiKey`.
         */
        const val TOKEN_PARAM = "ApiKey"

        /**
         * Whether removing [asked] by entry id takes exactly what was asked from
         * a playlist whose entries are [held]: on Jellyfin 12 an entry's id is
         * its song's, and the server removes every entry with it.
         */
        internal fun takesEveryCopy(held: List<String?>, asked: List<String>): Boolean {
            val copies = held.groupingBy { it }.eachCount()
            return asked.groupingBy { it }.eachCount().all { (entry, n) -> copies[entry] == n }
        }

        /** Whether a server of [version] moves an entry to the index asked — see [movesLandWhereAsked]. Null when it doesn't read. */
        internal fun movesLandWhereAsked(version: String): Boolean? {
            val parts = versionParts(version)
            val major = parts.getOrNull(0) ?: return null
            val minor = parts.getOrNull(1) ?: return null
            val patch = parts.getOrNull(2) ?: 0
            return !(major == 10 && minor == 10 && patch in 2..7)
        }

        /**
         * Whether a server of [version] has the per-user routes 10.9 added —
         * compared as numbers, since "10.10.0" sorts before "10.9.0" as text,
         * and there is no 11: after 10.11 came 12. Null when [version] doesn't
         * read as one.
         */
        internal fun hasUserRoutes(version: String): Boolean? {
            val parts = versionParts(version)
            val major = parts.getOrNull(0) ?: return null
            val minor = parts.getOrNull(1) ?: return null
            return major > 10 || (major == 10 && minor >= 9)
        }

        /** "10.10.7" as its numbers; a part that doesn't start with a digit is null. */
        private fun versionParts(version: String): List<Int?> =
            version.trim().split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() }

        /** A rename, and nothing that could touch the playlist's contents — see [renamePlaylist]. */
        internal fun renameBody(name: String): JsonObject = buildJsonObject { put("Name", name) }

        /** How far before the last mark each ask for changes reaches — see [songChangesSince]. */
        private val MARK_OVERLAP: Duration = Duration.ofMinutes(15)

        /** Items per request. Big enough to be one round trip on most libraries. */
        private const val PAGE_SIZE = 500

        private const val ALBUM_TYPE = "MusicAlbum"
        private const val TRACK_TYPE = "Audio"
        private const val PLAYLIST_TYPE = "Playlist"
        private const val AUDIO_MEDIA = "Audio"

        /** Jellyfin's key for the library scan, used to tell whether one is running. */
        private const val SCAN_TASK = "RefreshLibrary"

        /**
         * The extras a music library needs, which Jellyfin leaves out unless
         * asked. Without these an album has no year, no genre and no track
         * count, and a compilation's tracks all read as being by its album
         * artist — the fields are cheap, and their absence is silent.
         */
        private const val FIELDS =
            "Genres,DateCreated,PremiereDate,ChildCount,AlbumArtists,ArtistItems,People," +
                "NormalizationGain,DateLastMediaAdded"

        private const val COVER_QUALITY = 90

        /**
         * The device every install of Amp once signed in as, which sources
         * signed in before [MusicSource.deviceId] existed still are: their
         * tokens were issued to it.
         *
         * Jellyfin keeps one sign-in per device and user, so with one id for
         * every phone a second sign-in (the same server added again, the same
         * account on another phone) ended the first. Each sign-in now gets an
         * id of its own: [newDeviceId].
         */
        const val LEGACY_DEVICE_ID = "com.sublunar.amp"

        /** An id for one sign-in, kept with its source for as long as the token is. */
        fun newDeviceId(): String = "amp-" + java.util.UUID.randomUUID().toString()

        /**
         * Jellyfin's own authorization scheme, which every request carries.
         *
         * `Client` is what the server lists the session under, so a source with
         * its own name reports itself by it. `Device` stays the hardware: two
         * sources here are still one phone.
         *
         * Each value is percent-encoded, which the server undoes: a quote or a
         * comma in a player name would end its field early, and a character
         * outside ASCII, such as a curly apostrophe, can't go in a header.
         */
        fun authorization(
            product: String? = null,
            token: String? = null,
            deviceId: String = LEGACY_DEVICE_ID,
        ): String = buildString {
            append("MediaBrowser ")
            append("Client=\"${field(product ?: "Amp")}\", ")
            append("Device=\"${field("Light Phone III")}\", ")
            append("DeviceId=\"${field(deviceId)}\", ")
            append("Version=\"1.0.0\"")
            if (!token.isNullOrBlank()) append(", Token=\"${field(token)}\"")
        }

        /** Percent-encoded as the server's URL-decoding expects — spaces kept, since it reads `+` as one. */
        private fun field(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", " ")
    }
}

/** [status] is the HTTP status when the server gave one — see Reachability, which reads a 5xx as "not there". */
class JellyfinException(message: String, status: Int? = null) : ServerException(message, status)

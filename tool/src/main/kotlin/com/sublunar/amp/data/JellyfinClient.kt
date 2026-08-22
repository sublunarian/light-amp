package com.sublunar.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Jellyfin server's music library, behind the same [MusicServer] interface
 * Navidrome and Plex use.
 *
 * Jellyfin sits closer to Subsonic than Plex does: it has real favourites, real
 * playlists you can create empty, and a transcoder that will produce the format
 * it is asked for. What it does *not* have is left out rather than faked (see
 * the capability flags on [MusicSource], which is what the UI reads):
 *
 * - **Star ratings.** Jellyfin's user data carries a favourite flag and a
 *   like/dislike, not a 1–5 rating. There is nothing for stars to round-trip
 *   to, so they are not offered.
 * - **Popular songs.** No per-artist popularity ranking over the API.
 * - **A shared play queue.** Nothing of the shape Subsonic's `savePlayQueue`
 *   has, so a queue started here doesn't follow you to another client.
 *
 * Everything else — browsing, artwork, streaming, playlists, favourites, play
 * counts, now-playing and lyrics — round-trips, so what happens here shows up
 * in Jellyfin's own clients and the other way about.
 */
class JellyfinClient(
    /** The server's address, e.g. `http://192.168.1.10:8096`. */
    private val baseUrl: String,
    /** From `/Users/AuthenticateByName`; every request carries it. */
    private val token: String,
    /**
     * Whose library this is.
     *
     * Jellyfin scopes nearly everything to a user — what is favourited, what
     * has been played, which libraries are visible — so this is not optional
     * decoration. It is captured at login and stored with the source.
     */
    private val userId: String,
    /** Overrides the `Client` this reports itself as; null keeps the app's own. */
    private val product: String? = null,
) : MusicServer {

    private val http = HttpClient(OkHttp) { expectSuccess = false }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        encodeDefaults = true
    }

    override fun close() = http.close()

    // --- Requests ------------------------------------------------------------

    private fun url(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return baseUrl.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
    }

    private suspend fun body(path: String, params: List<Pair<String, String>> = emptyList()): String {
        val response = http.get(url(path, params)) { jellyfinHeaders() }
        if (!response.status.isSuccess()) {
            throw JellyfinException("Jellyfin says ${response.status.value} for $path")
        }
        return response.bodyAsText()
    }

    private suspend inline fun <reified T> fetch(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): T = json.decodeFromString(body(path, params))

    /** A call whose answer doesn't matter, and whose failure mustn't propagate. */
    private suspend fun send(
        method: String,
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        payload: JsonObject? = null,
    ): Boolean = runCatching {
        val target = url(path, params)
        val response = when (method) {
            "POST" -> http.post(target) {
                jellyfinHeaders()
                if (payload != null) {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(JsonObject.serializer(), payload))
                }
            }
            "DELETE" -> http.delete(target) { jellyfinHeaders() }
            else -> http.get(target) { jellyfinHeaders() }
        }
        response.status.isSuccess()
    }.getOrDefault(false)

    private fun io.ktor.client.request.HttpRequestBuilder.jellyfinHeaders() {
        header("Authorization", authorization(product, token))
        header("Accept", "application/json")
    }

    override suspend fun ping() {
        // Proves the address, the token and the user in one call: an expired
        // token answers 401 here rather than an empty library later.
        body("/Users/$userId")
    }

    // --- Browsing ------------------------------------------------------------

    /**
     * The user's music libraries.
     *
     * `/Views` rather than `/Items`: views are what this user is allowed to see
     * and are already scoped to them, where the item tree includes everything
     * on the server. Anything that isn't a music collection is dropped — a
     * shelf of films is not a shelf you can browse here.
     */
    override suspend fun getMusicFolders(): List<MusicFolder> =
        fetch<JellyfinItems>("/Users/$userId/Views").items
            .filter { it.collectionType.equals("music", ignoreCase = true) }
            .map { MusicFolder(id = it.id, name = it.name) }

    override suspend fun getAllAlbums(musicFolderId: String?): List<Album> =
        paged(musicFolderId, ALBUM_TYPE).map { it.toAlbum() }.distinctBy { it.id }

    /**
     * Everything of one type, following the pages to the end.
     *
     * Jellyfin caps nothing by default but will happily hold a whole library in
     * memory to answer one request, and a phone on a slow link waits for all of
     * it. Paging keeps each round trip small and bounded, and `TotalRecordCount`
     * says when to stop.
     *
     * The explicit sort is not cosmetic: a page is an offset into a result set,
     * and without an ordering the server may arrange the rows differently for
     * each request — which drops some rows and repeats others across a page
     * boundary. `SortName` is the same key the app's own lists use.
     */
    private suspend fun paged(
        musicFolderId: String?,
        itemType: String,
        extra: List<Pair<String, String>> = emptyList(),
    ): List<JellyfinItem> {
        val items = mutableListOf<JellyfinItem>()
        var start = 0
        while (true) {
            val params = buildList {
                add("userId" to userId)
                add("IncludeItemTypes" to itemType)
                add("Recursive" to "true")
                add("SortBy" to "SortName")
                add("SortOrder" to "Ascending")
                add("Fields" to FIELDS)
                add("StartIndex" to start.toString())
                add("Limit" to PAGE_SIZE.toString())
                if (!musicFolderId.isNullOrBlank()) add("ParentId" to musicFolderId)
                addAll(extra)
            }
            val page = fetch<JellyfinItems>("/Items", params)
            items += page.items
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) break
        }
        return items
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
     * genre and artist data rather than an outside agent. Verified against the
     * public demo — the seed comes back first, and the repository puts it
     * first anyway, so it is dropped there rather than counted on here.
     */
    override suspend fun getSimilarSongs(songId: String, count: Int): List<Track> = runCatching {
        fetch<JellyfinItems>(
            "/Items/$songId/InstantMix",
            listOf("userId" to userId, "Fields" to FIELDS) +
                if (count > 0) listOf("Limit" to count.toString()) else emptyList(),
        ).items.filter { it.type == TRACK_TYPE }.map { it.toTrack() }
    }.onFailure { android.util.Log.w("AmpRadio", "jellyfin instant mix failed: ${it.message}") }
        .onSuccess { android.util.Log.i("AmpRadio", "jellyfin instant mix: ${it.size} items for $songId") }
        .getOrDefault(emptyList())

    /**
     * Favourites, which Jellyfin keeps per user and per item.
     *
     * Artists come back as names rather than ids because the app's artist list
     * is derived from track tags and has no server id to match against — the
     * same reason [Starred] carries names for them everywhere else.
     */
    override suspend fun getStarred(musicFolderId: String?): Starred {
        val favourite = listOf("Filters" to "IsFavorite")
        val songs = runCatching { paged(musicFolderId, TRACK_TYPE, favourite) }
            .getOrDefault(emptyList())
        val albums = runCatching { paged(musicFolderId, ALBUM_TYPE, favourite) }
            .getOrDefault(emptyList())
        val artists = runCatching {
            fetch<JellyfinItems>(
                "/Artists",
                buildList {
                    add("userId" to userId)
                    add("Filters" to "IsFavorite")
                    add("Limit" to PAGE_SIZE.toString())
                    if (!musicFolderId.isNullOrBlank()) add("ParentId" to musicFolderId)
                },
            ).items
        }.getOrDefault(emptyList())
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
    override suspend fun startServerScan(musicFolderId: String?): Boolean =
        send("POST", "/Library/Refresh")

    override suspend fun serverScanning(musicFolderId: String?): Boolean = runCatching {
        fetch<List<JellyfinTask>>("/ScheduledTasks")
            .any { it.key == SCAN_TASK && it.state.equals("Running", ignoreCase = true) }
    }.getOrDefault(false)

    /**
     * The server's own artist records, which is where their pictures are — the
     * library's artists come from track tags and carry none.
     */
    override suspend fun getArtistIndex(musicFolderId: String?): List<ArtistRef> = runCatching {
        val items = mutableListOf<JellyfinItem>()
        var start = 0
        while (true) {
            val page = fetch<JellyfinItems>(
                "/Artists",
                buildList {
                    add("userId" to userId)
                    add("SortBy" to "SortName")
                    add("SortOrder" to "Ascending")
                    add("StartIndex" to start.toString())
                    add("Limit" to PAGE_SIZE.toString())
                    if (!musicFolderId.isNullOrBlank()) add("ParentId" to musicFolderId)
                },
            )
            items += page.items
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) break
        }
        items.map { ArtistRef(id = it.id, name = it.name, imageId = it.imageId()) }
            .distinctBy { it.id }
    }.getOrDefault(emptyList())

    // --- Media ---------------------------------------------------------------

    /**
     * Jellyfin's universal audio endpoint, which — unlike Plex's — really does
     * produce the container it is asked for.
     *
     * `container` is a list of what the *client* will accept and `audioCodec`
     * what it should be encoded as; giving both is what stops the server
     * deciding it knows better and remuxing to something else. Original quality
     * skips the transcoder entirely through `static=true`, which serves the file
     * whole, with its real container and content type — so a FLAC arrives as a
     * FLAC with its seek table intact.
     *
     * `startTimeTicks` is the same trick the other backends use for seeking
     * inside a transcode: the stream begins that far in.
     */
    override fun streamUrl(
        songId: String,
        format: StreamFormat,
        timeOffsetSeconds: Int,
        estimateContentLength: Boolean,
        sessionId: String?,
    ): String {
        if (format == StreamFormat.RAW) {
            return url(
                "/Audio/$songId/stream",
                listOf(
                    "static" to "true",
                    "api_key" to token,
                ),
            )
        }
        val container = CONTAINERS[format] ?: MP3_CONTAINER
        val params = buildList {
            add("UserId" to userId)
            add("DeviceId" to DEVICE_ID)
            add("api_key" to token)
            add("Container" to container)
            add("AudioCodec" to (CODECS[format] ?: container))
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
        return url("/Audio/$songId/universal", params)
    }

    override val streamFormats: List<StreamFormat> get() = STREAM_FORMATS

    /**
     * Jellyfin serves an image for any id it is given, answering 404 only when
     * the fetch happens — so a cover id is minted here only for an item that
     * said it had one. [JellyfinItem.imageId] is where that decision is made.
     */
    override fun coverArtUrl(coverArtId: String?): String? {
        if (coverArtId.isNullOrBlank()) return null
        return url("/Items/$coverArtId/Images/Primary", listOf("api_key" to token))
    }

    /**
     * The cover through Jellyfin's own resizer.
     *
     * Stored art is whatever the scanner found, routinely far larger than any
     * phone needs; asking the server to shrink it turns a page of covers from
     * megabytes into kilobytes.
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
                "api_key" to token,
            ),
        )
    }

    /**
     * Words for a song, from Jellyfin 10.9's lyrics endpoint.
     *
     * Timed where the file carried timings and plain where it didn't — an
     * unsynced sheet still belongs on screen, just without the karaoke. Older
     * servers have no such endpoint and answer 404, which reads as "no lyrics"
     * rather than as an error.
     */
    override suspend fun getLyrics(songId: String): Lyrics? = runCatching {
        val parsed = fetch<JellyfinLyrics>("/Audio/$songId/Lyrics")
        val lines = parsed.lyrics
            .filter { it.text.isNotBlank() }
            .map { LyricLine(it.start?.let { ticks -> ticks / TICKS_PER_MS }, it.text.trim()) }
        if (lines.isEmpty()) return@runCatching null
        Lyrics(lines, synced = lines.any { it.timeMs != null })
    }.getOrNull()

    // --- Writing back --------------------------------------------------------

    /**
     * Jellyfin separates the two statements the way this interface does: a start
     * notice opens a session on the server, and a play is recorded against the
     * item. Sending only the second would announce nothing while a track played;
     * sending only the first would count everything skipped past as a play.
     */
    override suspend fun scrobble(songId: String, atMs: Long?, submission: Boolean) {
        if (!submission) {
            send(
                "POST",
                "/Sessions/Playing",
                payload = buildJsonObject {
                    put("ItemId", songId)
                    put("PositionTicks", 0L)
                },
            )
            return
        }
        send("POST", "/Users/$userId/PlayedItems/$songId")
    }

    /**
     * The heartbeat behind Jellyfin's "now playing", and what moves the progress
     * bar on its dashboard.
     *
     * Stopping is a different endpoint rather than a state, which is Jellyfin's
     * shape rather than this app's: a session that is merely reported paused for
     * ever is still a session, and the server keeps showing it.
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
        send("POST", path, payload = payload)
    }

    override suspend fun starSong(songId: String) = favourite(songId, true)
    override suspend fun unstarSong(songId: String) = favourite(songId, false)
    override suspend fun starAlbum(albumId: String) = favourite(albumId, true)
    override suspend fun unstarAlbum(albumId: String) = favourite(albumId, false)
    override suspend fun starArtist(artistId: String) = favourite(artistId, true)
    override suspend fun unstarArtist(artistId: String) = favourite(artistId, false)

    private suspend fun favourite(id: String, on: Boolean) {
        send(if (on) "POST" else "DELETE", "/Users/$userId/FavoriteItems/$id")
    }

    // --- Playlists -----------------------------------------------------------

    override suspend fun getPlaylists(musicFolderId: String?): List<Playlist> =
        paged(null, PLAYLIST_TYPE).map {
            Playlist(
                id = it.id,
                name = it.name,
                coverArtId = it.imageId(),
                createdAt = jellyfinDateMs(it.dateCreated),
                updatedAt = jellyfinDateMs(it.dateCreated),
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
        playlistEntries(id).map { it.toTrack() }

    /** The raw entries, which carry the playlist-entry ids a removal needs. */
    private suspend fun playlistEntries(id: String): List<JellyfinItem> =
        fetch<JellyfinItems>(
            "/Playlists/$id/Items",
            listOf("userId" to userId, "Fields" to FIELDS),
        ).items

    override suspend fun createPlaylist(name: String, songIds: List<String>): String? = runCatching {
        val payload = buildJsonObject {
            put("Name", name)
            put("UserId", userId)
            put("MediaType", "Audio")
            put(
                "Ids",
                buildJsonArray { songIds.forEach { add(JsonPrimitive(it)) } },
            )
        }
        val response = http.post(url("/Playlists")) {
            jellyfinHeaders()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), payload))
        }
        if (!response.status.isSuccess()) return@runCatching null
        json.decodeFromString<JellyfinNamed>(response.bodyAsText()).id.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Renaming needs the whole item back, not just the new name: Jellyfin's
     * update takes a full record and drops anything missing from it. Servers
     * before 10.9 have no playlist update at all and refuse, which leaves the
     * name as it was rather than clearing it.
     */
    override suspend fun renamePlaylist(id: String, name: String) {
        send(
            "POST",
            "/Playlists/$id",
            payload = buildJsonObject {
                put("Name", name)
                put("Ids", buildJsonArray { })
            },
        )
    }

    override suspend fun deletePlaylist(id: String) {
        send("DELETE", "/Items/$id")
    }

    override suspend fun addToPlaylist(id: String, songId: String) {
        send("POST", "/Playlists/$id/Items", listOf("ids" to songId, "userId" to userId))
    }

    /**
     * Jellyfin removes a playlist entry by the entry's own id, not the song's —
     * the same song can appear twice and only one of them is meant. The index
     * given is into the playlist as the app last read it, so the entry ids are
     * re-read here rather than remembered.
     */
    override suspend fun removeFromPlaylistAt(id: String, index: Int) {
        val entries = runCatching { playlistEntries(id) }.getOrDefault(emptyList())
        val entryId = entries.getOrNull(index)?.playlistItemId ?: return
        send("DELETE", "/Playlists/$id/Items", listOf("entryIds" to entryId))
    }

    /**
     * Reordering is one move at a time — Jellyfin has no "here is the new
     * order" call. Each move is applied against the list as it stands, so the
     * entries are re-read after each one rather than computed up front.
     */
    override suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>) {
        orderedSongIds.forEachIndexed { target, songId ->
            val entries = runCatching { playlistEntries(id) }.getOrDefault(emptyList())
            val at = entries.indexOfFirst { it.id == songId }
            if (at < 0 || at == target) return@forEachIndexed
            val entryId = entries[at].playlistItemId ?: return@forEachIndexed
            send("POST", "/Playlists/$id/Items/$entryId/Move/$target")
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    companion object {
        /**
         * The original file, or anything the transcoder will produce.
         *
         * Unlike Plex's, Jellyfin's universal endpoint honours the container and
         * codec it is given, so every one of these really does come back as what
         * was asked for — which is why the whole list is offered rather than the
         * two Plex is limited to.
         */
        val STREAM_FORMATS: List<StreamFormat> = StreamFormat.entries.toList()

        /** Items per request. Big enough to be one round trip on most libraries. */
        private const val PAGE_SIZE = 500

        private const val ALBUM_TYPE = "MusicAlbum"
        private const val TRACK_TYPE = "Audio"
        private const val PLAYLIST_TYPE = "Playlist"

        /** Jellyfin's key for the library scan, used to tell whether one is running. */
        private const val SCAN_TASK = "RefreshLibrary"

        /**
         * The extras a music library needs, which Jellyfin leaves out unless
         * asked. Without these an album has no year, no genre and no track
         * count, and a compilation's tracks all read as being by its album
         * artist — the fields are cheap, and their absence is silent.
         */
        private const val FIELDS =
            "Genres,DateCreated,PremiereDate,ChildCount,AlbumArtists,ArtistItems,People"

        private const val MP3_CONTAINER = "mp3"
        private const val COVER_QUALITY = 90

        /**
         * What to ask the server to send, and what to call it once it does.
         *
         * Opus travels in an Ogg container, so the two names differ — asking for
         * `container=opus` gets a file nothing will open.
         */
        private val CONTAINERS: Map<StreamFormat, String> = mapOf(
            StreamFormat.MP3 to "mp3",
            StreamFormat.OPUS to "ogg",
            StreamFormat.FLAC to "flac",
        )
        private val CODECS: Map<StreamFormat, String> = mapOf(
            StreamFormat.MP3 to "mp3",
            StreamFormat.OPUS to "opus",
            StreamFormat.FLAC to "flac",
        )

        /**
         * Ties this install to its sessions on the server. Stable rather than
         * generated per launch: Jellyfin uses it to recognise the same device
         * coming back, and a fresh one each time litters the dashboard with
         * devices that played one track and vanished.
         */
        const val DEVICE_ID = "com.sublunar.amp"

        /**
         * Jellyfin's own authorization scheme, which every request carries.
         *
         * `Client` is what the server lists the session under, so a source with
         * its own name reports itself by it. `Device` deliberately stays the
         * hardware: two sources configured here are still the same phone, and
         * naming the device after one of them loses which machine was playing.
         */
        fun authorization(product: String? = null, token: String? = null): String = buildString {
            append("MediaBrowser ")
            append("Client=\"${product ?: "Amp"}\", ")
            append("Device=\"Light Phone III\", ")
            append("DeviceId=\"$DEVICE_ID\", ")
            append("Version=\"1.0.0\"")
            if (!token.isNullOrBlank()) append(", Token=\"$token\"")
        }
    }
}

/** What a library calls an album that isn't by one artist. */

/**
 * The cover id for an item, or null when it hasn't got one.
 *
 * Jellyfin will serve `/Items/{anything}/Images/Primary` and only answer 404
 * when the bytes are asked for, so "has a cover" has to be decided from the
 * metadata. A track usually carries no image of its own and inherits the
 * album's, which is why the album is the fallback rather than the track id.
 */
/**
 * The album's picture before the item's own, where the item has an album.
 *
 * A track with art embedded in its file has a Primary image of its own, and
 * Jellyfin says so — but it is the same sleeve its album has, and keyed by the
 * track it was cached once per song: a downloaded library held thousands of
 * copies of a few hundred covers. The album's id keys them all to one file.
 */
fun JellyfinItem.imageId(): String? = when {
    !albumPrimaryImageTag.isNullOrBlank() && !albumId.isNullOrBlank() -> albumId
    imageTags.containsKey("Primary") -> id
    else -> null
}

/**
 * Jellyfin's own record of an album, as one of the app's.
 *
 * Top-level rather than a method on the client because it needs nothing from
 * one — and because the compilation trap below is worth being able to test
 * without a server.
 */

internal fun JellyfinItem.toAlbum(): Album = Album(
    id = id,
    title = name,
    artist = albumArtist ?: albumArtists.firstOrNull()?.name ?: "Unknown Artist",
    coverArtId = imageId(),
    durationMs = (runTimeTicks ?: 0L) / TICKS_PER_MS,
    songCount = childCount ?: 0,
    year = productionYear,
    releaseDate = jellyfinDateNumber(premiereDate),
    createdMs = jellyfinDateMs(dateCreated),
    playCount = userData?.playCount ?: 0,
    lastPlayedMs = jellyfinDateMs(userData?.lastPlayedDate),
    liked = userData?.isFavorite ?: false,
    genre = genres.firstOrNull().orEmpty(),
)

internal fun JellyfinItem.toTrack(): Track = Track(
    id = id,
    title = name,
    // The track's own performers, not the record's: on a compilation the
    // album artist is "Various Artists" for every row, and reading that
    // here would put a shelf of songs by nobody in the songs list.
    artist = artists.firstOrNull()
        ?: artistItems.firstOrNull()?.name
        ?: albumArtist
        ?: "Unknown Artist",
    album = album ?: "Unknown Album",
    albumArtist = albumArtist ?: albumArtists.firstOrNull()?.name ?: "Unknown Artist",
    albumId = albumId,
    coverArtId = imageId(),
    durationMs = (runTimeTicks ?: 0L) / TICKS_PER_MS,
    trackNumber = indexNumber,
    discNumber = parentIndexNumber,
    year = productionYear,
    playCount = userData?.playCount ?: 0,
    lastPlayedMs = jellyfinDateMs(userData?.lastPlayedDate),
    liked = userData?.isFavorite ?: false,
    genre = genres.firstOrNull().orEmpty(),
    // Jellyfin files a composer as a credited person rather than a tag, so the
    // Composers page is built out of People — see JellyfinItem.people. Joined
    // with the separator the tag lists already split on.
    composer = people.filter { it.type.equals("Composer", ignoreCase = true) }
        .joinToString("; ") { it.name }
        .trim(),
)

class JellyfinException(message: String) : Exception(message)

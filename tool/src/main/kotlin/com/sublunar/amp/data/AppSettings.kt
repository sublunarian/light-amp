package com.sublunar.amp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * How much of the player the cover gets.
 *
 * [SMALL] is the default: the cover in its own panel keeps the controls on black
 * where they need no help to be read. A stored value from an older build that
 * named a mode this no longer has falls back to it — see enumFlow.
 */
enum class ArtworkMode {
    /** In the panel between the header and the controls, as a square. */
    SMALL,

    /** None at all — not drawn anywhere in the app, and not fetched either. */
    NONE,
}

/** What gets downloaded for offline playback. */

enum class OfflineMode {
    /** Nothing automatic — the user downloads albums and songs by hand. */
    MANUAL,

    /** Liked albums, liked songs, and everything in the user's playlists. */
    FAVORITES,

    /** Everything, favourites first, as far as the size limit allows. */
    ALL,
}

/**
 * How freely the app may use the network.
 *
 * A tool can't read the actual connection type (ConnectivityManager is blocked by
 * the plugin sandbox), so this is the user telling us, not something detected.
 */
enum class DataMode {
    /**
     * Off Wi-Fi, still stream when the stream would beat the copy on disk.
     */
    MAKE_IT_HURT,

    /**
     * Off Wi-Fi, prefer whatever is downloaded over streaming it again.
     *
     * No longer caps the format: which quality to stream on cellular is stated
     * per source now (see [MusicSource.cellularFormat]), because a mode that
     * overrode the choice was giving people something other than what they
     * asked for without saying so.
     */
    LOW_DATA,

    /** Assume no usable connection: play and show only what's downloaded. */
    WIFI_ONLY,
}

enum class AlbumSort { TITLE, ARTIST, YEAR, DATE_ADDED, RECENTLY_PLAYED, MOST_PLAYED, RATING, RANDOM }
enum class SongSort { TITLE, ARTIST, DATE_ADDED, RECENTLY_PLAYED, MOST_PLAYED, RATING }
enum class ArtistSort { NAME, MOST_PLAYED }
enum class PlaylistSort { RECENTLY_UPDATED, NAME, DATE_CREATED }

enum class LayoutMode {
    /** Standard layout with 5 buttons: Search, Browse with chevron, Now Playing. */
    STANDARD,
    /** Simplified layout with 3 buttons: Search, Browse, Now Playing. */
    SIMPLIFIED,
}

/**
 * Ordering for the genre and composer lists, which are bare tag values.
 *
 * A tag carries nothing to sort on but itself, so the only other question worth
 * asking of it is how much music sits under it — [SONGS] answers that. One key
 * serves both lists: they are the same kind of list, and setting the order of
 * each separately is a distinction no one is asking for.
 */
enum class TagSort { NAME, SONGS }

/**
 * All persisted state: the server credentials plus user preferences, backed by
 * the tool's private Preferences DataStore. Everything is exposed as a Flow so
 * the UI recomposes reactively.
 */
class AppSettings(private val dataStore: DataStore<Preferences>) {

    private val sourcesJson = Json { ignoreUnknownKeys = true }

    // --- Sources -------------------------------------------------------------

    /**
     * Every configured source, in the order they were added.
     *
     * Stored as one JSON blob rather than a key per field: the list is short, it
     * is read and written whole, and DataStore has no list-of-records type.
     *
     * A pre-sources install has its single server under the old flat keys; that
     * is folded in here rather than migrated in place, so downgrading the app
     * still finds its server where it left it.
     */
    val sources: Flow<List<MusicSource>> = dataStore.data.map { p ->
        val stored = p[SOURCES]?.let { text ->
            runCatching { sourcesJson.decodeFromString<List<MusicSource>>(text) }.getOrNull()
        }
        val list = stored ?: legacySource(p)?.let { listOf(it) } ?: emptyList()
        // Downloads used to be configured once for the whole app. A source saved
        // before they moved here has none of its own, and would otherwise read
        // as the defaults — quietly changing what the user had set. The old
        // values stand in until each is chosen for this source, and are filled
        // in on read rather than written back, so nothing is rewritten behind
        // them.
        list.map { source ->
            source.copy(
                downloadFormatId = source.downloadFormatId ?: p[DOWNLOAD_FORMAT],
                offlineModeName = source.offlineModeName ?: legacyOfflineMode(p[OFFLINE_MODE]).name,
                downloadLimitBytes = source.downloadLimitBytes ?: p[DOWNLOAD_LIMIT] ?: DEFAULT_DOWNLOAD_LIMIT,
                downloadLibraryId = source.downloadLibraryId ?: p[DOWNLOAD_LIBRARY_ID],
                downloadLyrics = source.downloadLyrics ?: p[DOWNLOAD_LYRICS] ?: true,
            )
        }
    }.distinctUntilChanged()

    /** The source the library is currently showing; the first one until chosen. */
    val activeSource: Flow<MusicSource?> = combine(
        sources,
        dataStore.data.map { it[ACTIVE_SOURCE] }.distinctUntilChanged(),
    ) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.distinctUntilChanged()

    /**
     * Add a source, or replace the one with the same id.
     *
     * A Plex source is re-matched against `machineIdentifier` first: that is
     * Plex's own stable server id, unlike [MusicSource.id] which is minted fresh
     * every time the link flow runs (account-link, "Enter address and token", a
     * re-link on a different network reaching a different address...). Without
     * this, re-linking the same server just appends another row with the same
     * name and a new id — a duplicate entry in Sources that looks identical to
     * the user but points at a second, empty local database.
     */
    suspend fun saveSource(source: MusicSource): String {
        var effectiveId = source.id
        editSources { list ->
            val existing = list.indexOfFirst { it.id == source.id }.takeIf { it >= 0 }
                ?: source.machineIdentifier.takeIf { it.isNotBlank() }?.let { mid ->
                    list.indexOfFirst { it.kind == source.kind && it.machineIdentifier == mid }
                        .takeIf { it >= 0 }
                }
            if (existing == null) {
                list + source
            } else {
                val old = list[existing]
                // Keep the id (and with it, the downloads database and the
                // user's own preferences for this source) — only what was just
                // discovered about the server itself should move.
                effectiveId = old.id
                val merged = source.copy(
                    id = old.id,
                    downloadFormatId = source.downloadFormatId ?: old.downloadFormatId,
                    offlineModeName = source.offlineModeName ?: old.offlineModeName,
                    downloadLimitBytes = source.downloadLimitBytes ?: old.downloadLimitBytes,
                    downloadLibraryId = source.downloadLibraryId ?: old.downloadLibraryId,
                    downloadLyrics = source.downloadLyrics ?: old.downloadLyrics,
                    libraryId = source.libraryId ?: old.libraryId,
                )
                list.toMutableList().also { it[existing] = merged }
            }
        }
        return effectiveId
    }

    suspend fun removeSource(id: String) {
        editSources { list -> list.filterNot { it.id == id } }
        dataStore.edit { p -> if (p[ACTIVE_SOURCE] == id) p.remove(ACTIVE_SOURCE) }
    }

    suspend fun setActiveSource(id: String) {
        dataStore.edit { it[ACTIVE_SOURCE] = id }
    }

    private suspend fun editSources(block: (List<MusicSource>) -> List<MusicSource>) {
        dataStore.edit { p ->
            val current = p[SOURCES]?.let { text ->
                runCatching { sourcesJson.decodeFromString<List<MusicSource>>(text) }.getOrNull()
            } ?: listOfNotNull(legacySource(p))
            p[SOURCES] = sourcesJson.encodeToString(block(current))
        }
    }

    private fun legacySource(p: Preferences): MusicSource? {
        val base = p[BASE_URL]
        val user = p[USERNAME]
        val pass = p[PASSWORD]
        if (base.isNullOrBlank() || user == null || pass == null) return null
        return MusicSource(
            id = LEGACY_SOURCE_ID,
            kind = SourceKind.SUBSONIC,
            name = MusicSource.nameFor(base),
            baseUrl = base,
            username = user,
            password = pass,
            streamFormatId = StreamFormat.fromId(p[STREAM_FORMAT]).id,
            libraryId = p[LIBRARY_ID],
        )
    }

    /** The active source's connection, or null when it is the phone's own music. */
    val serverConfig: Flow<SubsonicConfig?> =
        activeSource.map { it?.toConfig() }.distinctUntilChanged()

    /** Selected library (music folder) id; null means all libraries. */
    val libraryId: Flow<String?> = activeSource.map { it?.libraryId }.distinctUntilChanged()

    suspend fun setLibraryId(id: String?) {
        val current = activeSource.first() ?: return
        saveSource(current.copy(libraryId = id))
    }

    /** Record the folders a server reported, for the Sources page. */
    suspend fun setSourceLibraries(sourceId: String, libraries: List<SourceLibrary>) {
        val current = sources.first().firstOrNull { it.id == sourceId } ?: return
        if (current.libraries == libraries) return
        saveSource(current.copy(libraries = libraries))
    }

    /**
     * Streaming and download quality, both per source.
     *
     * Two servers can hold the same album in different formats, and only one of
     * them may be able to transcode it — so which quality to ask for is a fact
     * about the server, not about the app. The phone's own music has neither: it
     * is played and kept as whatever is on disk.
     */
    suspend fun setStreamFormat(sourceId: String, format: StreamFormat) {
        editSource(sourceId) { it.copy(streamFormatId = format.id) }
    }

    suspend fun setCellularFormat(sourceId: String, format: StreamFormat) {
        editSource(sourceId) { it.copy(cellularFormatId = format.id) }
    }

    suspend fun setSourceDownloadFormat(sourceId: String, format: StreamFormat) {
        editSource(sourceId) { it.copy(downloadFormatId = format.id) }
    }

    // --- One source's downloads ----------------------------------------------
    //
    // Downloads are per source already — their own folder, their own rows — so
    // what to fetch and how much of the phone to spend on it belongs to the
    // source too. A global size limit was in any case being compared against one
    // source's usage, which is a limit that means something different depending
    // on which source you were looking at.

    /**
     * Show or hide one of a source's libraries on the Sources page.
     *
     * Refuses to hide the last one: the rows under a source are the only way to
     * switch to it, so a source with none would be stranded — configurable but
     * unusable.
     */
    suspend fun setLibraryVisible(sourceId: String, libraryId: String?, visible: Boolean) {
        editSource(sourceId) { source ->
            val hidden = source.hiddenLibraryIds.toMutableList()
            if (visible) {
                hidden.remove(libraryId)
            } else {
                val remaining = source.libraries.count { it.id !in hidden } +
                    (if (source.showsAllLibraries) 1 else 0)
                if (remaining <= 1) return@editSource source
                if (libraryId !in hidden) hidden.add(libraryId)
            }
            source.copy(hiddenLibraryIds = hidden)
        }
    }

    suspend fun setSourceOfflineMode(sourceId: String, mode: OfflineMode) {
        editSource(sourceId) { it.copy(offlineModeName = mode.name) }
    }

    suspend fun setSourceDownloadLimit(sourceId: String, bytes: Long) {
        editSource(sourceId) { it.copy(downloadLimitBytes = bytes) }
    }

    suspend fun setSourceDownloadLibrary(sourceId: String, libraryId: String?) {
        editSource(sourceId) { it.copy(downloadLibraryId = libraryId) }
    }

    suspend fun setSourceDownloadLyrics(sourceId: String, on: Boolean) {
        editSource(sourceId) { it.copy(downloadLyrics = on) }
    }

    private suspend fun editSource(sourceId: String, block: (MusicSource) -> MusicSource) {
        val current = sources.first().firstOrNull { it.id == sourceId } ?: return
        saveSource(block(current))
    }

    // --- Preferences ---------------------------------------------------------

    val albumSort: Flow<AlbumSort> = enumFlow(ALBUM_SORT, AlbumSort.TITLE)
    val songSort: Flow<SongSort> = enumFlow(SONG_SORT, SongSort.TITLE)
    val artistSort: Flow<ArtistSort> = enumFlow(ARTIST_SORT, ArtistSort.NAME)
    val playlistSort: Flow<PlaylistSort> = enumFlow(PLAYLIST_SORT, PlaylistSort.RECENTLY_UPDATED)
    val tagSort: Flow<TagSort> = enumFlow(TAG_SORT, TagSort.NAME)

    /**
     * Whether a tab is showing only what you have liked.
     *
     * One flag per tab rather than one for the library: the tabs are different
     * lists, visited for different reasons, and a narrowing set on one of them
     * is not a statement about the others. Playlists have none — nothing likes a
     * playlist.
     */
    val likedAlbumsOnly: Flow<Boolean> = boolFlow(LIKED_ALBUMS_ONLY, false)
    val likedSongsOnly: Flow<Boolean> = boolFlow(LIKED_SONGS_ONLY, false)
    val likedArtistsOnly: Flow<Boolean> = boolFlow(LIKED_ARTISTS_ONLY, false)

    // Inverts each tab's sort; re-tapping the selected option flips it.
    val albumSortReversed: Flow<Boolean> = boolFlow(ALBUM_SORT_REV, false)
    val songSortReversed: Flow<Boolean> = boolFlow(SONG_SORT_REV, false)
    val artistSortReversed: Flow<Boolean> = boolFlow(ARTIST_SORT_REV, false)
    val playlistSortReversed: Flow<Boolean> = boolFlow(PLAYLIST_SORT_REV, false)
    val tagSortReversed: Flow<Boolean> = boolFlow(TAG_SORT_REV, false)

    // --- Offline / downloads -------------------------------------------------

    /**
     * The old app-wide offline mode, read only to seed a source that has none of
     * its own — see [sources]. "Random" was folded into ALL, which downloads
     * favourites first and then the rest; mapping it rather than letting
     * enumValueOf fail keeps anyone mid-download from dropping back to Manual.
     */
    private fun legacyOfflineMode(stored: String?): OfflineMode = when (stored) {
        null -> OfflineMode.MANUAL
        "RANDOM" -> OfflineMode.ALL
        else -> runCatching { enumValueOf<OfflineMode>(stored) }.getOrNull() ?: OfflineMode.MANUAL
    }

    /**
     * Wi-Fi only until told otherwise.
     *
     * The safe default is the one that cannot spend someone's data plan before
     * they have found the setting. Streaming a FLAC album over cellular is a
     * choice worth making deliberately, not one to discover on a bill.
     */
    val dataMode: Flow<DataMode> = enumFlow(DATA_MODE, DataMode.WIFI_ONLY)

    suspend fun setDataMode(value: DataMode) = putString(DATA_MODE, value.name)


    val invertColors: Flow<Boolean> = boolFlow(INVERT_COLORS, false)
    val karaokeLyrics: Flow<Boolean> = boolFlow(KARAOKE_LYRICS, true)

    /** Whether to use the simplified or standard layout mode. */
    val layoutMode: Flow<LayoutMode> = enumFlow(LAYOUT_MODE, LayoutMode.STANDARD)

    /**
     * Show album lists as a wall of covers instead of a column of titles.
     *
     * Chosen from the header on the album lists themselves rather than here —
     * it is a property of that list, not of the app. Ignored when artwork is
     * off: a grid of empty tiles is worse than the list it replaced.
     */
    val albumGrid: Flow<Boolean> = boolFlow(ALBUM_GRID, false)

    /**
     * The same choice, kept separately for an artist's own page.
     *
     * A discography and the whole library aren't the same kind of list — a
     * dozen covers read well as a grid where eight thousand don't, and the
     * reverse is just as reasonable. One setting for both meant picking a
     * layout for one place and having it applied to the other.
     */
    val artistAlbumGrid: Flow<Boolean> = boolFlow(ARTIST_ALBUM_GRID, false)


    /**
     * Artwork stays grey — the app's own switch over the display workaround.
     *
     * On by default, because turning it off changes the whole device's colour
     * filter for as long as the app is in front, and because without the adb
     * grant behind it there is nothing it can do. See LightDisplayColor.
     */
    val monochromeArtwork: Flow<Boolean> = boolFlow(MONOCHROME_ARTWORK, true)

    /**
     * The queue as it stood when the app last went away: track ids, the index into
     * them, and roughly where the current one had got to.
     *
     * Ids rather than rows — the library cache already holds the tracks, and a
     * long queue of full records has no business in DataStore. Capped at
     * [MAX_SAVED_QUEUE] so "play everything" can't write a megabyte of ids on
     * every track change.
     */
    val savedQueueIds: Flow<List<String>> =
        dataStore.data.map { p ->
            p[SAVED_QUEUE]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        }.distinctUntilChanged()

    val savedQueueIndex: Flow<Int> =
        dataStore.data.map { it[SAVED_INDEX] ?: 0 }.distinctUntilChanged()

    val savedPositionMs: Flow<Long> =
        dataStore.data.map { it[SAVED_POSITION] ?: 0L }.distinctUntilChanged()

    /** The playlist list as JSON, so a cold start offline still lists them. */
    /**
     * The last known playlists, per source.
     *
     * Keyed by source id, because one shared copy is another way for the wrong
     * server's data to appear: switch to a source whose playlists haven't
     * arrived yet and the fallback would hand back the *previous* source's list,
     * which is exactly what clearing them on the switch is meant to prevent.
     */
    suspend fun cachedPlaylists(sourceId: String): String =
        dataStore.data.first()[cachedPlaylistsKey(sourceId)] ?: ""

    suspend fun setCachedPlaylists(sourceId: String, value: String) =
        putString(cachedPlaylistsKey(sourceId), value)

    private fun cachedPlaylistsKey(sourceId: String) =
        stringPreferencesKey("${CACHED_PLAYLISTS.name}.$sourceId")

    /** Read once per operation rather than observed: see [PendingActions]. */
    suspend fun pendingActions(): String = dataStore.data.first()[PENDING_ACTIONS] ?: ""

    suspend fun setPendingActions(value: String) = putString(PENDING_ACTIONS, value)

    suspend fun saveQueue(ids: List<String>, index: Int, positionMs: Long) {
        dataStore.edit { p ->
            p[SAVED_QUEUE] = ids.take(MAX_SAVED_QUEUE).joinToString(",")
            p[SAVED_INDEX] = index
            p[SAVED_POSITION] = positionMs
        }
    }

    /** The user's own hold on downloading, kept across launches. */
    val downloadsPaused: Flow<Boolean> = boolFlow(DOWNLOADS_PAUSED, false)

    /** How much of the player the cover gets, if any. */
    val artwork: Flow<ArtworkMode> = enumFlow(ARTWORK, ArtworkMode.SMALL)

    suspend fun setAlbumSort(value: AlbumSort) = putString(ALBUM_SORT, value.name)
    suspend fun setSongSort(value: SongSort) = putString(SONG_SORT, value.name)
    suspend fun setArtistSort(value: ArtistSort) = putString(ARTIST_SORT, value.name)
    suspend fun setPlaylistSort(value: PlaylistSort) = putString(PLAYLIST_SORT, value.name)
    suspend fun setTagSort(value: TagSort) = putString(TAG_SORT, value.name)
    suspend fun setLikedAlbumsOnly(value: Boolean) = putBool(LIKED_ALBUMS_ONLY, value)
    suspend fun setLikedSongsOnly(value: Boolean) = putBool(LIKED_SONGS_ONLY, value)
    suspend fun setLikedArtistsOnly(value: Boolean) = putBool(LIKED_ARTISTS_ONLY, value)

    suspend fun setAlbumSortReversed(value: Boolean) = putBool(ALBUM_SORT_REV, value)
    suspend fun setSongSortReversed(value: Boolean) = putBool(SONG_SORT_REV, value)
    suspend fun setArtistSortReversed(value: Boolean) = putBool(ARTIST_SORT_REV, value)
    suspend fun setPlaylistSortReversed(value: Boolean) = putBool(PLAYLIST_SORT_REV, value)
    suspend fun setTagSortReversed(value: Boolean) = putBool(TAG_SORT_REV, value)

    suspend fun setInvertColors(value: Boolean) = putBool(INVERT_COLORS, value)
    suspend fun setKaraokeLyrics(value: Boolean) = putBool(KARAOKE_LYRICS, value)
    suspend fun setAlbumGrid(value: Boolean) = putBool(ALBUM_GRID, value)
    suspend fun setArtistAlbumGrid(value: Boolean) = putBool(ARTIST_ALBUM_GRID, value)

    suspend fun setMonochromeArtwork(value: Boolean) = putBool(MONOCHROME_ARTWORK, value)
    suspend fun setArtwork(value: ArtworkMode) = putString(ARTWORK, value.name)
    suspend fun setDownloadsPaused(value: Boolean) = putBool(DOWNLOADS_PAUSED, value)
    suspend fun setLayoutMode(value: LayoutMode) = putString(LAYOUT_MODE, value.name)

    // --- helpers -------------------------------------------------------------

    private fun boolFlow(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        dataStore.data.map { it[key] ?: default }.distinctUntilChanged()

    private inline fun <reified E : Enum<E>> enumFlow(
        key: Preferences.Key<String>,
        default: E,
    ): Flow<E> = dataStore.data.map { prefs ->
        prefs[key]?.let { name -> runCatching { enumValueOf<E>(name) }.getOrNull() } ?: default
    }.distinctUntilChanged()

    private suspend fun putString(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    private suspend fun putBool(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    companion object {
        private val BASE_URL = stringPreferencesKey("server.baseUrl")
        private val USERNAME = stringPreferencesKey("server.username")
        private val PASSWORD = stringPreferencesKey("server.password")
        private val STREAM_FORMAT = stringPreferencesKey("server.streamFormat")
        private val LIBRARY_ID = stringPreferencesKey("server.libraryId")

        private val ALBUM_SORT = stringPreferencesKey("pref.albumSort")
        private val SONG_SORT = stringPreferencesKey("pref.songSort")
        private val ARTIST_SORT = stringPreferencesKey("pref.artistSort")
        private val PLAYLIST_SORT = stringPreferencesKey("pref.playlistSort")
        private val TAG_SORT = stringPreferencesKey("pref.tagSort")
        private val LIKED_ALBUMS_ONLY = booleanPreferencesKey("pref.likedAlbumsOnly")
        private val LIKED_SONGS_ONLY = booleanPreferencesKey("pref.likedSongsOnly")
        private val LIKED_ARTISTS_ONLY = booleanPreferencesKey("pref.likedArtistsOnly")

        private val ALBUM_SORT_REV = booleanPreferencesKey("pref.albumSortReversed")
        private val SONG_SORT_REV = booleanPreferencesKey("pref.songSortReversed")
        private val ARTIST_SORT_REV = booleanPreferencesKey("pref.artistSortReversed")
        private val PLAYLIST_SORT_REV = booleanPreferencesKey("pref.playlistSortReversed")
        private val TAG_SORT_REV = booleanPreferencesKey("pref.tagSortReversed")

        private val INVERT_COLORS = booleanPreferencesKey("pref.invertColors")
        private val KARAOKE_LYRICS = booleanPreferencesKey("pref.karaokeLyrics")
        private val ALBUM_GRID = booleanPreferencesKey("pref.albumGrid")
        private val ARTIST_ALBUM_GRID = booleanPreferencesKey("pref.artistAlbumGrid")
        private val MONOCHROME_ARTWORK = booleanPreferencesKey("pref.monochromeArtwork")
        private val ARTWORK = stringPreferencesKey("pref.artworkMode")
        private val LAYOUT_MODE = stringPreferencesKey("pref.layoutMode")
        private val SOURCES = stringPreferencesKey("pref.sources")
        private val ACTIVE_SOURCE = stringPreferencesKey("pref.activeSource")

        /** The id given to a server configured before sources existed. */
        const val LEGACY_SOURCE_ID = "server"

        private val DOWNLOADS_PAUSED = booleanPreferencesKey("pref.downloadsPaused")
        private val CACHED_PLAYLISTS = stringPreferencesKey("state.playlists")
        private val PENDING_ACTIONS = stringPreferencesKey("state.pendingActions")
        private val SAVED_QUEUE = stringPreferencesKey("state.queueIds")
        private val SAVED_INDEX = intPreferencesKey("state.queueIndex")
        private val SAVED_POSITION = longPreferencesKey("state.positionMs")

        /** Plenty for an album or a long playlist, short of a whole library. */
        const val MAX_SAVED_QUEUE = 1000

        private val OFFLINE_MODE = stringPreferencesKey("pref.offlineMode")
        private val DATA_MODE = stringPreferencesKey("pref.dataMode")
        /**
         * The old app-wide download format. Nothing writes it any more — it is
         * read once per source that has no choice of its own, so an install from
         * before the setting moved keeps the quality it was set to.
         */
        private val DOWNLOAD_FORMAT = stringPreferencesKey("pref.downloadFormat")
        private val DOWNLOAD_LYRICS = booleanPreferencesKey("pref.downloadLyrics")
        private val DOWNLOAD_LIMIT = longPreferencesKey("pref.downloadLimitBytes")
        private val DOWNLOAD_LIBRARY_ID = stringPreferencesKey("pref.downloadLibraryId")

        /** 2 GB — a few hundred albums as MP3, and well inside any device. */
        const val DEFAULT_DOWNLOAD_LIMIT = 2L * 1024 * 1024 * 1024
    }
}

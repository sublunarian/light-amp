package com.sublunar.amp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * How much of the player the cover gets.
 *
 * [SMALL] is the default: the cover in its own panel keeps the controls on black
 * where they need no help to be read. A stored value from an older build that
 * named a mode this no longer has falls back to it — see AppSettings.pref.
 */
enum class ArtworkMode {
    /** In the panel between the header and the controls, as a square. */
    SMALL,

    /** None at all — not drawn anywhere in the app, and not fetched either. */
    NONE,
}

/** What gets downloaded for offline playback. Stored by name. */
enum class OfflineMode {
    /** Nothing automatic — the user downloads albums and songs by hand. */
    MANUAL,

    /** Liked albums, liked songs, and everything in the user's playlists. */
    FAVORITES,

    /** Everything, favourites first, as far as the size limit allows. */
    ALL,
}

/**
 * How freely the app may use the network — the user's choice, read through
 * LinkRules and enforced by NetworkGate.
 */
enum class DataMode {
    /**
     * Nothing waits for Wi-Fi: downloads, covers and streams all run on any
     * connection. The one exception is a track already downloaded, which still
     * plays from its file — a copy on the phone beats paying for the same song.
     */
    MAKE_IT_HURT,

    /**
     * Off Wi-Fi, prefer whatever is downloaded over streaming it again. It
     * doesn't cap the format: cellular quality is chosen per source (see
     * [MusicSource.cellularFormat]), because an override would be a silent
     * substitution.
     */
    LOW_DATA,

    /** Assume no usable connection: play and show only what's downloaded. */
    WIFI_ONLY,
}

enum class AlbumSort { TITLE, ARTIST, YEAR, DATE_ADDED, RECENTLY_PLAYED, MOST_PLAYED, RATING, RANDOM }
enum class SongSort { TITLE, ARTIST, DATE_ADDED, RECENTLY_PLAYED, MOST_PLAYED, RATING }
enum class ArtistSort { NAME, MOST_PLAYED }
enum class PlaylistSort { RECENTLY_UPDATED, NAME, DATE_CREATED }

/**
 * Which of the bar's three destinations was last showing.
 *
 * Only these three, not the page under them: reopening part-way into a record
 * you have since left is worse than reopening at the library itself.
 */
enum class LastSection { LIBRARY, SEARCH, NOW_PLAYING }

/**
 * How the bottom bar is arranged, and what that implies for the pages above it.
 *
 * [STANDARD] is the default, and the one the setting describes the absence of:
 * "Simplified Library View", off. There is no migration to it for older
 * installs: none could tell an old install from a fresh one.
 */
enum class LayoutMode {
    /** Expanded: the four library tabs along the bar, with search as a fifth. */
    STANDARD,

    /** Library, the player and search — the library being a page of its own. */
    SIMPLIFIED,
}

/**
 * One stored value, two ways. As a Flow it is read from the DataStore, so code
 * that has just written sees its write. As [state] it is held for the life of
 * the process, starting from what was stored at launch, for screens: a Flow
 * collected in a composition draws a placeholder for its first frame.
 */
open class Stored<T> internal constructor(flow: Flow<T>, val state: StateFlow<T>) : Flow<T> by flow {
    val value: T get() = state.value
}

/** A preference: a [Stored] value under one key, and the way to change it. */
class Pref<T> internal constructor(
    internal val key: Preferences.Key<*>,
    flow: Flow<T>,
    state: StateFlow<T>,
    private val write: suspend (T) -> Unit,
) : Stored<T>(flow, state) {
    suspend fun set(value: T) = write(value)
}

/**
 * All persisted state — the sources, the preferences and what is restored at
 * launch — in the tool's private Preferences DataStore.
 *
 * Every key string here is stored on phones: never change one. SettingsKeysTest
 * pins them, with the type each is stored as.
 */
class AppSettings(
    private val dataStore: DataStore<Preferences>,
    /** Where each value's [Stored.state] is held: App's, for the life of the process. */
    private val scope: CoroutineScope,
) {

    private val sourcesJson = Json { ignoreUnknownKeys = true }

    /** The store as it was when this was built: where every [Stored.state] starts. */
    private val loaded: Preferences = runBlocking { dataStore.data.first() }

    // --- Sources -------------------------------------------------------------

    /**
     * Every configured source, in the order they were added.
     *
     * Stored as one JSON blob rather than a key per field: the list is short, it
     * is read and written whole, and DataStore has no list-of-records type. Read
     * one record at a time — see [StoredSources]. One this build can't read is
     * left out, and the rest still show.
     */
    val sources: Stored<List<MusicSource>> = held(
        dataStore.data.map { it[SOURCES] }.distinctUntilChanged()
            .map { stored -> readSources(stored).readable }
            .distinctUntilChanged(),
        readSources(loaded[SOURCES]).readable,
    )

    /** The source the library is currently showing; the first one until chosen. */
    val activeSource: Stored<MusicSource?> = held(
        combine(sources, dataStore.data.map { it[ACTIVE_SOURCE] }.distinctUntilChanged(), ::activeOf)
            .distinctUntilChanged(),
        activeOf(sources.value, loaded[ACTIVE_SOURCE]),
    )

    private fun activeOf(list: List<MusicSource>, id: String?): MusicSource? =
        list.firstOrNull { it.id == id } ?: list.firstOrNull()

    /** Selected library (music folder) id; null means all libraries. */
    val libraryId: Stored<String?> =
        held(activeSource.map { it?.libraryId }.distinctUntilChanged(), activeSource.value?.libraryId)

    /** Add a source, or update the one it already is — see [mergeSource]. */
    suspend fun saveSource(source: MusicSource): String {
        var effectiveId = source.id
        editSources { list -> mergeSource(list, source).also { effectiveId = it.second }.first }
        return effectiveId
    }

    suspend fun removeSource(id: String) {
        dataStore.edit { p ->
            p.editSources { list -> list.filterNot { it.id == id } }
            if (p[ACTIVE_SOURCE] == id) p.remove(ACTIVE_SOURCE)
        }
    }

    suspend fun setActiveSource(id: String) {
        dataStore.edit { it[ACTIVE_SOURCE] = id }
    }

    /** The stored list; a value that isn't a JSON array at all reads as none. */
    private fun readSources(stored: String?): StoredSources =
        stored?.let { StoredSources.decode(sourcesJson, it) } ?: StoredSources.NONE

    private suspend fun editSources(block: (List<MusicSource>) -> List<MusicSource>) {
        dataStore.edit { p -> p.editSources(block) }
    }

    /**
     * Rewrite the stored list through [block], which is handed only what this
     * build can read, inside one edit: read and write can't straddle another
     * write. The records it can't read go back where they were.
     */
    private fun MutablePreferences.editSources(block: (List<MusicSource>) -> List<MusicSource>) {
        val stored = readSources(this[SOURCES])
        this[SOURCES] = stored.encode(sourcesJson, block(stored.readable))
    }

    /** Change the one source with [sourceId], if it is still there, in one edit. */
    private suspend fun editSource(sourceId: String, block: (MusicSource) -> MusicSource) {
        editSources { list -> list.map { if (it.id == sourceId) block(it) else it } }
    }

    /**
     * Which of a source's libraries is browsed — null for All Libraries —
     * written exactly as given.
     *
     * Not through [saveSource], which keeps the stored library whenever it is
     * handed none, so that re-linking a server doesn't forget the choice: All
     * Libraries *is* none, and would never be saved.
     */
    suspend fun setSourceLibraryId(sourceId: String, libraryId: String?) {
        editSource(sourceId) { it.copy(libraryId = libraryId) }
    }

    /** Record the folders a server reported, for the Sources page. */
    suspend fun setSourceLibraries(sourceId: String, libraries: List<SourceLibrary>) {
        editSource(sourceId) { it.copy(libraries = libraries) }
    }

    /**
     * Show or hide one of a source's libraries — everywhere, not only on the
     * Sources page: see MusicSource.hiddenLibraryIds and [withLibraryShown].
     * Written exactly, like [setSourceLibraryId], because hiding can move the
     * browsing to All Libraries.
     */
    suspend fun setLibraryVisible(sourceId: String, libraryId: String, visible: Boolean) {
        editSource(sourceId) { it.withLibraryShown(libraryId, visible) }
    }

    /**
     * Streaming and download quality, both per source: two servers can hold the
     * same album in different formats, and only one of them may be able to
     * transcode it. The phone's own music has neither — it is played and kept
     * as whatever is on disk.
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

    suspend fun setSourceOfflineMode(sourceId: String, mode: OfflineMode) {
        editSource(sourceId) { it.copy(offlineModeName = mode.name) }
    }

    /**
     * Record that this source's cache has been refilled by the current parser —
     * see [MusicSource.parserGeneration]. Written only after a sync that
     * finished, so one that was interrupted refetches again next time.
     */
    suspend fun setParserGeneration(sourceId: String, generation: Int) {
        editSource(sourceId) { it.copy(parserGeneration = generation) }
    }

    // --- Library lists -------------------------------------------------------

    val albumSort: Pref<AlbumSort> = enum("pref.albumSort", AlbumSort.TITLE)
    val songSort: Pref<SongSort> = enum("pref.songSort", SongSort.TITLE)
    val artistSort: Pref<ArtistSort> = enum("pref.artistSort", ArtistSort.NAME)
    val playlistSort: Pref<PlaylistSort> = enum("pref.playlistSort", PlaylistSort.RECENTLY_UPDATED)

    // Inverts each tab's sort; re-tapping the selected option flips it.
    val albumSortReversed: Pref<Boolean> = bool("pref.albumSortReversed", false)
    val songSortReversed: Pref<Boolean> = bool("pref.songSortReversed", false)
    val artistSortReversed: Pref<Boolean> = bool("pref.artistSortReversed", false)
    val playlistSortReversed: Pref<Boolean> = bool("pref.playlistSortReversed", false)

    /**
     * Whether a tab is showing only what you have liked. One flag per tab: the
     * tabs are different lists, visited for different reasons. Playlists have
     * none — nothing likes a playlist.
     */
    val likedAlbumsOnly: Pref<Boolean> = bool("pref.likedAlbumsOnly", false)
    val likedSongsOnly: Pref<Boolean> = bool("pref.likedSongsOnly", false)
    val likedArtistsOnly: Pref<Boolean> = bool("pref.likedArtistsOnly", false)

    /**
     * The tag each list is narrowed to, or blank for all of it. Per tab, as the
     * liked switch is: one shared setting would make each page silently change
     * the other.
     */
    val albumsGenre: Pref<String> = string("pref.albumsGenre")
    val albumsComposer: Pref<String> = string("pref.albumsComposer")
    val songsGenre: Pref<String> = string("pref.songsGenre")
    val songsComposer: Pref<String> = string("pref.songsComposer")

    /**
     * Drop every narrowing of the library lists — the liked switches and the
     * genre and composer filters — in one write. See App.swapSource: these
     * are facts about one library, and a genre chosen on one server applied
     * to the next empties its lists with nothing on screen to say why.
     */
    suspend fun clearLibraryFilters() {
        val filters = listOf(
            likedAlbumsOnly, likedSongsOnly, likedArtistsOnly,
            albumsGenre, albumsComposer, songsGenre, songsComposer,
        )
        dataStore.edit { p -> filters.forEach { p.remove(it.key) } }
    }

    /**
     * Bumped each time Random is re-tapped for a new deal — see sortAlbums,
     * whose shuffle is otherwise seeded by the library alone and would hand the
     * same order back forever.
     */
    val shuffleNonce: Stored<Int> = stored { it[SHUFFLE_NONCE] ?: 0 }

    suspend fun bumpShuffleNonce() {
        dataStore.edit { it[SHUFFLE_NONCE] = (it[SHUFFLE_NONCE] ?: 0) + 1 }
    }

    // --- Display and playback ------------------------------------------------

    val invertColors: Pref<Boolean> = bool("pref.invertColors", false)

    val karaokeLyrics: Pref<Boolean> = bool("pref.karaokeLyrics", true)

    /**
     * Even out the volume between tracks, from the loudness the server
     * measured. On by default — with it off, a remaster can be 10 dB louder
     * than the record before it. Attenuate-only, so it never adds clipping;
     * a library with no measurements plays exactly as it does today.
     */
    val replayGain: Pref<Boolean> = bool("pref.replayGain", true)

    /** Whether to use the simplified or standard layout mode. */
    val layoutMode: Pref<LayoutMode> = enum("pref.layoutMode", LayoutMode.STANDARD)

    /** Where the app was when it was last closed — see [LastSection]. */
    val lastSection: Pref<LastSection> = enum("pref.lastSection", LastSection.LIBRARY)

    /** The library tab last chosen, by name, so a cold start opens on it; empty before any. */
    val lastTab: Pref<String> = string("pref.lastTab")

    /**
     * Show album lists as a wall of covers instead of a column of titles.
     *
     * Chosen from the header on the album lists themselves rather than here —
     * it is a property of that list, not of the app. Ignored when artwork is
     * off: a grid of empty tiles is worse than the list it replaced.
     */
    val albumGrid: Pref<Boolean> = bool("pref.albumGrid", false)

    /**
     * The same choice, kept separately for an artist's own page: a dozen covers
     * read well as a grid where eight thousand don't, and the reverse is just as
     * reasonable.
     */
    val artistAlbumGrid: Pref<Boolean> = bool("pref.artistAlbumGrid", false)

    /**
     * Drop the artists' own pictures, keeping every other kind of artwork.
     *
     * Separate from Hide Artwork because they are separate things to dislike: a
     * sleeve is the record's own cover and an artist photo is a publicity shot
     * the server went and found, and a library where only some artists have one
     * reads as broken rather than as sparse. With them off the artist rows fall
     * back to the tighter single-line pitch, exactly as they do with artwork
     * switched off altogether. Hidden by default.
     */
    val hideArtistImages: Pref<Boolean> = bool("pref.hideArtistImages", true)

    /**
     * Stop marking downloaded rows. The leading slot the mark sat in stays,
     * because it is also what keeps every list's titles on one axis.
     */
    val hideDownloadIcons: Pref<Boolean> = bool("pref.hideDownloadIcons", false)

    /** How much of the player the cover gets, if any. */
    val artwork: Pref<ArtworkMode> = enum("pref.artworkMode", ArtworkMode.SMALL)

    // --- Network and downloads -----------------------------------------------

    /**
     * Low Data until told otherwise: safe on a metered plan but *working*. Wi-Fi
     * Only as the default meant a first sign-in over cellular synced nothing and
     * the app looked broken, with the reason buried on the settings page.
     */
    val dataMode: Pref<DataMode> = enum("pref.dataMode", DataMode.LOW_DATA)

    /**
     * How much of the phone the downloads may take, across the whole tool. One
     * budget, not one per source: three sources set to "20GB" would otherwise
     * take sixty.
     */
    val downloadLimit: Pref<Long> = long("pref.downloadLimitBytes", DEFAULT_DOWNLOAD_LIMIT)

    /** The user's own hold on downloading, kept across launches. */
    val downloadsPaused: Pref<Boolean> = bool("pref.downloadsPaused", false)

    // --- Saved state ---------------------------------------------------------

    /**
     * The queue as it stood when the app last went away, as [saveQueue] wrote
     * it, in one read; null when it holds no tracks.
     *
     * Ids rather than rows — the library cache already holds the tracks, and a
     * long queue of full records has no business in DataStore. Capped at
     * [MAX_SAVED_QUEUE] so "play everything" can't write a megabyte of ids on
     * every track change.
     */
    suspend fun savedQueue(): SavedQueue? {
        val p = dataStore.data.first()
        val ids = savedIds(p)
        if (ids.isEmpty()) return null
        return SavedQueue(ids, ids.getOrNull(p[SAVED_INDEX] ?: 0), p[SAVED_POSITION] ?: 0L)
    }

    private fun savedIds(p: Preferences): List<String> =
        p[SAVED_QUEUE]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    /**
     * Save [ids], the playing one at [index] among them, and roughly where it
     * had got to. A longer queue is saved as a slice of at most
     * [MAX_SAVED_QUEUE] ids around the playing one, and [index] points into
     * that slice.
     */
    suspend fun saveQueue(ids: List<String>, index: Int, positionMs: Long) {
        dataStore.edit { p ->
            p[SAVED_QUEUE] = ids.take(MAX_SAVED_QUEUE).joinToString(",")
            p[SAVED_INDEX] = index
            p[SAVED_POSITION] = positionMs
        }
    }

    /**
     * The last known playlists as JSON, per source, so a cold start offline
     * still lists them. Per source, because with one shared copy a source whose
     * playlists haven't arrived yet would be handed the previous source's.
     */
    suspend fun cachedPlaylists(sourceId: String): String =
        dataStore.data.first()[cachedPlaylistsKey(sourceId)] ?: ""

    suspend fun setCachedPlaylists(sourceId: String, value: String) =
        putString(cachedPlaylistsKey(sourceId), value)

    private fun cachedPlaylistsKey(sourceId: String) =
        stringPreferencesKey("${CACHED_PLAYLISTS.name}.$sourceId")

    /**
     * When [sourceId] last finished a library sync, in epoch ms; 0 if never.
     * Kept across launches so a cold start doesn't walk the whole album index
     * again just when someone opens the app to play something.
     */
    suspend fun lastSyncedMs(sourceId: String): Long =
        dataStore.data.first()[lastSyncedKey(sourceId)]?.toLongOrNull() ?: 0L

    suspend fun setLastSyncedMs(sourceId: String, atMs: Long) =
        putString(lastSyncedKey(sourceId), atMs.toString())

    private fun lastSyncedKey(sourceId: String) =
        stringPreferencesKey("state.lastSynced.$sourceId")

    /** How far this source's songs are known to match the server — see [SongCheck]. */
    suspend fun songCheck(sourceId: String): SongCheck =
        dataStore.data.first()[songCheckKey(sourceId)]
            ?.let { runCatching { sourcesJson.decodeFromString<SongCheck>(it) }.getOrNull() }
            ?: SongCheck()

    suspend fun setSongCheck(sourceId: String, check: SongCheck) =
        putString(songCheckKey(sourceId), sourcesJson.encodeToString(check))

    private fun songCheckKey(sourceId: String) =
        stringPreferencesKey("state.songCheck.$sourceId")

    /** Read once per operation rather than observed: see [PendingActions]. */
    suspend fun pendingActions(): String = dataStore.data.first()[PENDING_ACTIONS] ?: ""

    suspend fun setPendingActions(value: String) = putString(PENDING_ACTIONS, value)

    // --- helpers -------------------------------------------------------------

    private fun <T> held(flow: Flow<T>, initial: T): Stored<T> =
        Stored(flow, flow.stateIn(scope, SharingStarted.Eagerly, initial))

    private fun <T> stored(read: (Preferences) -> T): Stored<T> =
        held(dataStore.data.map(read).distinctUntilChanged(), read(loaded))

    /** A preference under [key]: [default] when unset or unreadable, as a stored value from an older build can be. */
    private fun <S, T> pref(key: Preferences.Key<S>, default: T, decode: (S) -> T?, encode: (T) -> S): Pref<T> {
        val read = { p: Preferences -> p[key]?.let(decode) ?: default }
        val flow = dataStore.data.map(read).distinctUntilChanged()
        return Pref(key, flow, flow.stateIn(scope, SharingStarted.Eagerly, read(loaded))) { value ->
            dataStore.edit { it[key] = encode(value) }
        }
    }

    private fun bool(name: String, default: Boolean): Pref<Boolean> =
        pref(booleanPreferencesKey(name), default, { it }, { it })

    private fun long(name: String, default: Long): Pref<Long> =
        pref(longPreferencesKey(name), default, { it }, { it })

    /** Blank when unset. */
    private fun string(name: String): Pref<String> =
        pref(stringPreferencesKey(name), "", { it }, { it })

    /** Stored by name. */
    private inline fun <reified E : Enum<E>> enum(name: String, default: E): Pref<E> =
        pref(stringPreferencesKey(name), default, { runCatching { enumValueOf<E>(it) }.getOrNull() }, { it.name })

    private suspend fun putString(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    companion object {
        private val SOURCES = stringPreferencesKey("pref.sources")
        private val ACTIVE_SOURCE = stringPreferencesKey("pref.activeSource")
        private val SHUFFLE_NONCE = intPreferencesKey("pref.shuffleNonce")
        private val CACHED_PLAYLISTS = stringPreferencesKey("state.playlists")
        private val PENDING_ACTIONS = stringPreferencesKey("state.pendingActions")
        private val SAVED_QUEUE = stringPreferencesKey("state.queueIds")
        private val SAVED_INDEX = intPreferencesKey("state.queueIndex")
        private val SAVED_POSITION = longPreferencesKey("state.positionMs")

        /** How many queue ids are saved for the next launch: plenty for an album or a long playlist. */
        const val MAX_SAVED_QUEUE = 1000

        /** 2 GB — a few hundred albums as MP3, and well inside any device. */
        const val DEFAULT_DOWNLOAD_LIMIT = 2L * 1024 * 1024 * 1024

        /**
         * The id the one server from before sources existed was given. Still
         * in use: it names that source's database, amp-library.db — see
         * libraryDatabaseName.
         */
        const val LEGACY_SOURCE_ID = "server"

        /**
         * What stands in for a source while there is none to read: before
         * boot has read the saved one, and at boot when nothing is saved yet.
         */
        fun placeholderSource(): MusicSource = MusicSource(LEGACY_SOURCE_ID, SourceKind.SUBSONIC, "Server")
    }
}

/**
 * [list] with [source] saved into it, and the id it was saved under.
 *
 * The same id is an edit: the record replaces the old one, keeping only the
 * few choices an edit may leave unset.
 *
 * A different id can still be the same server. Signing in mints a new id every
 * time, so a Plex server re-linked (matched by its `machineIdentifier`) or a
 * Jellyfin account signed into again (matched by its user id — a GUID, so one
 * account on one server) would otherwise become a second row with the same
 * name over an empty library, and on Jellyfin the second sign-in would log the
 * first out. Instead the source it already is takes the new sign-in — token,
 * address, libraries — and keeps its id, so its library and downloads, and
 * every choice the user made for it.
 */
internal fun mergeSource(list: List<MusicSource>, source: MusicSource): Pair<List<MusicSource>, String> {
    val sameId = list.indexOfFirst { it.id == source.id }
    if (sameId >= 0) {
        val old = list[sameId]
        val edited = source.copy(
            downloadFormatId = source.downloadFormatId ?: old.downloadFormatId,
            offlineModeName = source.offlineModeName ?: old.offlineModeName,
            downloadLimitBytes = source.downloadLimitBytes ?: old.downloadLimitBytes,
            libraryId = source.libraryId ?: old.libraryId,
        )
        return list.toMutableList().also { it[sameId] = edited } to old.id
    }
    val sameServer = list.indexOfFirst { it.kind == source.kind && it.provider.isSameServer(it, source) }
    if (sameServer < 0) return list + source to source.id
    val old = list[sameServer]
    val relinked = source.copy(
        id = old.id,
        streamFormatId = old.streamFormatId,
        cellularFormatId = old.cellularFormatId,
        downloadFormatId = old.downloadFormatId,
        offlineModeName = old.offlineModeName,
        downloadLimitBytes = old.downloadLimitBytes,
        playerName = old.playerName,
        parserGeneration = old.parserGeneration,
        libraryId = old.libraryId,
        hiddenLibraryIds = old.hiddenLibraryIds,
        features = old.features,
    )
    return list.toMutableList().also { it[sameServer] = relinked } to old.id
}

/**
 * How far a source's songs are known to match its server, kept between syncs.
 *
 * [mark] is the server's own answer from [MusicServer.songChangesSince] that
 * everything is read up to. Where the server can't say which songs changed,
 * a walk re-reads the library a few albums per sync: [walkCursor] is the last
 * album it reached, [walkMark] the mark it will settle once it gets round, and
 * [walkedMs] when the last one did.
 *
 * A server that names its changes and walks as well (Jellyfin — see
 * [SongChanges.Albums.alsoWalk]) moves [mark] itself on every sync and never
 * sets [walkMark]: its walk catches what the list misses, and has no mark of
 * its own to settle.
 */
@Serializable
data class SongCheck(
    val mark: String? = null,
    val walkMark: String? = null,
    val walkCursor: String? = null,
    val walkedMs: Long = 0L,
)

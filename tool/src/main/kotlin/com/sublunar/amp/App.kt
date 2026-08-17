package com.sublunar.amp

import com.sublunar.amp.art.ArtworkLoader
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.Artist
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.ArtistSort
import com.sublunar.amp.data.PendingActions
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.PlaylistSort
import com.sublunar.amp.data.SongSort
import com.sublunar.amp.data.TagSort
import com.sublunar.amp.data.sortAlbums
import com.sublunar.amp.data.sortArtists
import com.sublunar.amp.data.sortPlaylists
import com.sublunar.amp.data.sortSongs
import com.sublunar.amp.data.sortName
import com.sublunar.amp.data.titleKey
import com.sublunar.amp.ui.components.indexLetterOf
import com.sublunar.amp.ui.screens.LibraryNav
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.Connectivity
import com.sublunar.amp.data.DataMode
import com.sublunar.amp.data.DownloadStore
import com.sublunar.amp.data.Downloader
import com.sublunar.amp.data.LibraryRepository
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.SourceLibrary
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.db.LibraryDatabase
import com.sublunar.amp.playback.PlaybackController
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.display.LightDisplayColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.minutes

/**
 * App-scoped service locator. Initialized once from the boot screen (which owns
 * the activity context) and reused by every screen. The Subsonic client is
 * rebuilt whenever the stored server config changes.
 */
object App {
    lateinit var scope: CoroutineScope
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var library: LibraryRepository
    lateinit var pending: PendingActions
        private set
    lateinit var playback: PlaybackController
        private set
    lateinit var artwork: ArtworkLoader
        private set
    lateinit var downloads: DownloadStore
        private set
    lateinit var downloader: Downloader
        private set

    private val _serverClient = MutableStateFlow<MusicServer?>(null)
    val serverClient: StateFlow<MusicServer?> = _serverClient

    /**
     * The source the library is showing, as state rather than a preferences Flow —
     * the UI reads it on every screen to decide what a source can do.
     */
    private val _source = MutableStateFlow(MusicSource.local())
    val source: StateFlow<MusicSource> = _source

    /**
     * One Room database per source, cached by id.
     *
     * Sources hold different libraries, so they can't share tables — and a shared
     * table would mean wiping and re-syncing on every switch, which is minutes of
     * requests to show a list the app already had. Databases are kept open once
     * built: there are only ever a handful, and switching back should be instant.
     */
    private val databases = mutableMapOf<String, LibraryDatabase>()
    private lateinit var lightContext: SealedLightContext
    private val _dao = MutableStateFlow<LibraryDao?>(null)

    /** The active source's DAO. Never read before [boot] has built the first. */
    private fun dao(): LibraryDao = _dao.value ?: error("Library database not ready")

    private fun databaseFor(source: MusicSource): LibraryDatabase = databases.getOrPut(source.id) {
        // The first server keeps the original file name, so an install from
        // before sources existed finds its cache rather than syncing afresh.
        val name = if (source.id == AppSettings.LEGACY_SOURCE_ID) {
            DB_NAME
        } else {
            "$DB_NAME-${source.id}"
        }
        lightContext.buildDatabase(LibraryDatabase::class.java, name)
    }

    /**
     * Drop a source's cached library and downloaded audio.
     *
     * Called when the user removes it: "remove" has to mean the storage goes too,
     * or a source added and dropped a few times quietly fills the phone.
     */
    suspend fun forgetSource(source: MusicSource) {
        val db = databaseFor(source)
        db.libraryDao().apply {
            clearTracks()
            clearAlbums()
            clearLikedArtists()
            clearDownloads()
        }
        downloads.deleteSource(source.id)
    }

    /**
     * Whether the server looks reachable. Inferred from whether requests succeed —
     * a tool can't query connectivity directly (ConnectivityManager is blocked by
     * the plugin sandbox), so the sync path reports into this rather than us
     * observing the network.
     */
    private val _serverReachable = MutableStateFlow(true)
    val serverReachable: StateFlow<Boolean> = _serverReachable

    fun reportServerReachable(reachable: Boolean) {
        _serverReachable.value = reachable
    }

    /**
     * True when the library should show only downloaded media: the user asked for
     * Wi-Fi only *and* there is no Wi-Fi, or the server isn't answering at all.
     */
    val offlineOnly: Flow<Boolean> by lazy {
        combine(
            settings.dataMode,
            serverReachable,
            Connectivity.wifiConnected,
        ) { mode, reachable, onWifi ->
            // "Wi-Fi only" restricts the library on *cellular*, not everywhere. It
            // used to hide the streamable library unconditionally, because there was
            // no way to see the network — Connectivity works that out from the
            // interface list instead of taking the setting at its word.
            (mode == DataMode.WIFI_ONLY && !onWifi) || !reachable
        }
    }

    private var coreReady = false
    val isReady: Boolean get() = coreReady

    const val SYNC_JOB_KEY = "library-sync"

    fun boot(sealedActivity: SealedLightActivity, context: SealedLightContext) {
        if (!coreReady) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            settings = AppSettings(context.dataStore)
            lightContext = context
            artwork = ArtworkLoader(context.filesDir, serverClient) { _source.value.id }
            // Whatever was last in use, resolved before anything reads the
            // library — the DAO has to exist before the repository is built.
            val first = runBlocking { settings.activeSource.first() }
                ?: MusicSource(AppSettings.LEGACY_SOURCE_ID, SourceKind.SUBSONIC, "Server")
            _source.value = first
            _dao.value = databaseFor(first).libraryDao()
            downloads = DownloadStore(context.filesDir) { source.value.id }
            // One-time: audio downloaded before sources existed sits in the old
            // flat folder, and belongs to whichever source inherited that server.
            if (first.id == AppSettings.LEGACY_SOURCE_ID) downloads.adoptLegacyFiles()
            pending = PendingActions(settings)
            library = LibraryRepository(
                daos = _dao,
                serverClient = serverClient,
                libraryId = settings.libraryId,
                offlineOnly = offlineOnly,
                pending = pending,
                settings = settings,
                scope = scope,
            )
            downloader = Downloader(::dao, downloads, serverClient, settings, scope, context)
            playback = PlaybackController(settings, serverClient, ::dao, downloads, scope)

            // Sync is our only reliable connectivity signal, and the moment fresh
            // library data exists is also the right moment to top up downloads.
            library.onSyncSucceeded = {
                reportServerReachable(true)
                scope.launch {
                    // Fresh library rows are also the first chance to work out
                    // which album a reindexed download belongs to.
                    runCatching { dao().backfillDownloadAlbums() }
                    topUpDownloads()
                }
            }
            // A sync that worked is proof the server is answering, which is the
            // one moment worth retrying everything that happened while it wasn't.
            scope.launch { library.primePlaylists() }
            scope.launch {
                pending.refreshCount()
                serverReachable.collect { reachable ->
                    // Downloads stop dead when the server goes, and pick up where
                    // they were when it comes back.
                    downloader.setOffline(!reachable)
                    if (reachable) library.flushPending()
                }
            }
            scope.launch { downloader.setUserPaused(settings.downloadsPaused.first()) }
            // The colour workaround's switch. See LightDisplayColor: this is an
            // off-SDK spike, and this is what turns it off again.
            //
            // Hidden artwork forces it off regardless: with no covers on screen
            // there is nothing for colour to do, and the control that would turn
            // it back on isn't visible either — leaving the device switched to
            // colour from a setting the user can no longer see would be the
            // worst of both.
            scope.launch {
                combine(settings.monochromeArtwork, settings.artwork) { mono, artwork ->
                    !mono && artwork != ArtworkMode.NONE
                }.collect { LightDisplayColor.enabled = it }
            }
            library.onSyncFailed = { reportServerReachable(false) }

            // One client per source, of whichever kind that source is. Keyed on
            // the whole record so a changed token or address rebuilds it.
            scope.launch {
                settings.activeSource.collect { active ->
                    val previous = _serverClient.value
                    _serverClient.value = active?.toClient()
                    previous?.close()
                }
            }
            // Whatever folders the server reports, remembered on the source so
            // the Sources page can list them instantly and offline.
            scope.launch {
                library.syncState.collect { state ->
                    if (state.syncing || state.lastSyncedMs == 0L) return@collect
                    val current = source.value
                    if (!current.supportsLibraries) return@collect
                    val folders = runCatching { library.musicFolders() }.getOrNull() ?: return@collect
                    // Empty means "couldn't ask", not "there are none".
                    // musicFolders() answers emptyList() for a network failure, a
                    // client that isn't up yet, and a server with no folders
                    // alike — so writing it through wiped libraries that had
                    // already been discovered, collapsing the Sources page back
                    // to a single "All Libraries" row until a later sync
                    // happened to succeed. A server with no libraries renders
                    // identically anyway, so there is nothing to lose by
                    // treating empty as unknown.
                    if (folders.isEmpty()) return@collect
                    settings.setSourceLibraries(
                        current.id,
                        folders.map { SourceLibrary(it.id, it.name) },
                    )
                }
            }
            // Swapping the source swaps the database under everything that reads
            // it; the repository's flows follow _dao, so the whole library
            // changes over without anything else being told.
            scope.launch {
                settings.activeSource.collect { active ->
                    val next = active ?: return@collect
                    if (next.id == _source.value.id) {
                        _source.value = next
                        return@collect
                    }
                    // Everything in flight belongs to the source being left, and
                    // none of it survives the change: a queued track's id only
                    // means something to the server it came from, and the stream
                    // URLs already handed to the player are signed for that
                    // server. Left alone, playback fails on the next track, the
                    // failure trips the offline fallback, and the app spends a
                    // while looking broken before a reachability check lets it
                    // recover.
                    playback.stop()
                    downloader.clearQueue()
                    // Popular songs, the playlists, the search index and the
                    // artist ids are all keyed by name rather than by source, so
                    // they answer for the wrong server until they're dropped.
                    // Cleared *before* the source changes over, so nothing that
                    // recomposes on the new source can catch the old data.
                    library.forgetDerived()
                    // Which tabs were showing their liked list is a fact about
                    _source.value = next
                    _dao.value = databaseFor(next).libraryDao()
                }
            }
            // Re-run when the mode changes so switching to e.g. Favorites starts
            // fetching without waiting for the next sync.
            scope.launch {
                settings.activeSource
                    .map { it?.offlineMode }
                    .distinctUntilChanged()
                    .collect { topUpDownloads() }
            }
            // ...and again once the cached library actually exists. The collector
            // above runs at boot, when Room has usually not finished loading, so it
            // returns early with nothing to queue. Without this the only other
            // trigger is a *successful* sync — so a launch where the sync is a
            // no-op leaves the queue empty and downloads quietly stop.
            // Rebuild the download index from disk before anything reads it: a
            // schema bump wipes the table, and without this the app would re-fetch
            // audio it already has.
            scope.launch { downloader.reindexFromDisk() }
            // Downloads yield to the sync: both hit the same server, and the sync
            // is hundreds of small sequential requests that a saturated
            // transcoder turns into a crawl.
            scope.launch {
                library.syncState.collect { downloader.setSyncing(it.syncing) }
            }
            // Cue up whatever was playing when the app last went away. Waits for
            // the cache because the saved queue is only ids — and for the player,
            // which is attached at the end of this very method while this
            // coroutine is already running on another thread. Restoring without
            // one silently does nothing and is never retried, so the order of
            // those two is not something to leave to chance.
            scope.launch {
                val tracks = library.fullTracks.filter { it.isNotEmpty() }.first()
                playback.bound.first { it }
                // And for the client, when this source has one. Restoring builds
                // the whole queue as audio items up front, and an item's stream
                // URL is fixed at the moment it is built — so with no client
                // yet, every one of them gets an empty URL. What that looks like
                // is the queue and the position restored perfectly and nothing
                // that will play, until something queues music again and the
                // items are rebuilt.
                //
                // Room answers from disk while the client is still being made
                // from a DataStore read on another coroutine, so the cache
                // usually wins that race. A local source never has a client and
                // must not be waited for.
                val active = settings.activeSource.filter { it != null }.first()
                if (active?.kind != SourceKind.LOCAL) {
                    serverClient.filter { it != null }.first()
                }
                playback.restoreState(tracks)
            }
            scope.launch {
                library.fullTracks
                    .filter { it.isNotEmpty() }
                    .distinctUntilChangedBy { it.size }
                    .collect { topUpDownloads() }
            }
            coreReady = true
            // Periodic background library refresh (min 15 min on WorkManager).
            LightWork.enqueuePeriodic(context, SYNC_JOB_KEY, 30.minutes)
        }
        playback.bind(DefaultLightAudio(sealedActivity))
    }

    fun shutdown() {
        playback.release()
    }

    /**
     * A sorted library view plus its A–Z buckets, computed off the main thread.
     *
     * Sorting eight thousand tracks costs 300–650ms on this hardware, and a
     * `remember` in a tab composable only survives recomposition — every visit to
     * the tab paid it again, on the main thread, which is what produced the
     * "Amp isn't responding" dialogs. Deriving it here means the work happens
     * once per change on [scope] (Dispatchers.Default) and the UI just collects.
     */
    data class SortedView<T>(val items: List<T> = emptyList(), val letters: List<Char> = emptyList())

    /**
     * The sort settings, held rather than re-read.
     *
     * A screen that collects `settings.songSort` directly has to name a starting
     * value, and gets that placeholder for its first frame before the stored one
     * arrives — so a list sorted by Recently Added is drawn in title order first
     * and then re-sorts under you. That is the flicker on opening Liked Songs;
     * the tabs never showed it because they read [sortedSongs], which is already
     * settled by the time anything looks at it.
     *
     * Warm from boot, because [sortedSongs] and its neighbours are built on
     * these — so a page opened much later finds the real value waiting.
     */
    val songSort: StateFlow<SongSort> by lazy {
        settings.songSort.stateIn(scope, SharingStarted.Eagerly, SongSort.TITLE)
    }
    val songSortReversed: StateFlow<Boolean> by lazy {
        settings.songSortReversed.stateIn(scope, SharingStarted.Eagerly, false)
    }
    val albumSort: StateFlow<AlbumSort> by lazy {
        settings.albumSort.stateIn(scope, SharingStarted.Eagerly, AlbumSort.TITLE)
    }
    val albumSortReversed: StateFlow<Boolean> by lazy {
        settings.albumSortReversed.stateIn(scope, SharingStarted.Eagerly, false)
    }
    val artistSort: StateFlow<ArtistSort> by lazy {
        settings.artistSort.stateIn(scope, SharingStarted.Eagerly, ArtistSort.NAME)
    }
    val artistSortReversed: StateFlow<Boolean> by lazy {
        settings.artistSortReversed.stateIn(scope, SharingStarted.Eagerly, false)
    }
    /**
     * Whether each tab is narrowed to what you have liked.
     *
     * Warm from boot with the sorts, and for the same reason: they decide what
     * the first frame of a tab contains, and a placeholder would show the whole
     * library for a moment before taking most of it away again. One flag per
     * tab — see AppSettings.
     */
    val likedAlbumsOnly: StateFlow<Boolean> by lazy {
        settings.likedAlbumsOnly.stateIn(scope, SharingStarted.Eagerly, false)
    }
    val likedSongsOnly: StateFlow<Boolean> by lazy {
        settings.likedSongsOnly.stateIn(scope, SharingStarted.Eagerly, false)
    }
    val likedArtistsOnly: StateFlow<Boolean> by lazy {
        settings.likedArtistsOnly.stateIn(scope, SharingStarted.Eagerly, false)
    }

    val tagSort: StateFlow<TagSort> by lazy {
        settings.tagSort.stateIn(scope, SharingStarted.Eagerly, TagSort.NAME)
    }
    val tagSortReversed: StateFlow<Boolean> by lazy {
        settings.tagSortReversed.stateIn(scope, SharingStarted.Eagerly, false)
    }

    val sortedSongs: StateFlow<SortedView<Track>> by lazy {
        combine(library.tracks, songSort, songSortReversed, likedSongsOnly) { list, sort, rev, liked ->
            val sorted = sortSongs(list.filter { !liked || it.liked }, sort, rev)
            SortedView(
                sorted,
                when (sort) {
                    SongSort.TITLE -> sorted.map { indexLetterOf(titleKey(it.title)) }
                    SongSort.ARTIST -> sorted.map { indexLetterOf(sortName(it.artist)) }
                    else -> emptyList()
                },
            )
        }.stateIn(scope, SharingStarted.Eagerly, SortedView())
    }

    /**
     * Whether covers are switched off, as state rather than a preferences Flow.
     *
     * Every row in a long list asks this question, and a DataStore Flow per row
     * would mean a subscription and a disk-backed read each; one StateFlow is
     * read from the snapshot like any other piece of Compose state.
     */
    val hideArtwork: StateFlow<Boolean> by lazy {
        settings.artwork.map { it == ArtworkMode.NONE }
            .stateIn(scope, SharingStarted.Eagerly, false)
    }

    /**
     * Read by both album lists — see [AppSettings.albumGrid]. False whenever
     * artwork is off, since the grid is nothing but artwork.
     */
    val albumGrid: StateFlow<Boolean> by lazy {
        combine(settings.albumGrid, hideArtwork) { grid, hidden -> grid && !hidden }
            .stateIn(scope, SharingStarted.Eagerly, false)
    }

    /** The same, for an artist's own page — see [AppSettings.artistAlbumGrid]. */
    val artistAlbumGrid: StateFlow<Boolean> by lazy {
        combine(settings.artistAlbumGrid, hideArtwork) { grid, hidden -> grid && !hidden }
            .stateIn(scope, SharingStarted.Eagerly, false)
    }

    /**
     * Search at the top of each list rather than in the header — see
     * [AppSettings.inlineSearch].
     *
     * Hot and eager rather than collected with an initial value: it decides how
     * many rows sit above the content, and a list that composed on one answer
     * and settled on the other would open sitting on the field it is meant to
     * have scrolled past.
     */
    val inlineSearch: StateFlow<Boolean> by lazy {
        settings.inlineSearch.stateIn(scope, SharingStarted.Eagerly, false)
    }

    /**
     * Artists' own pictures off, sleeves untouched — see
     * [AppSettings.hideArtistImages]. Also true whenever artwork is off
     * wholesale, so the one place that reads it needs only the one answer.
     */
    val hideArtistImages: StateFlow<Boolean> by lazy {
        combine(settings.hideArtistImages, hideArtwork) { off, all -> off || all }
            .stateIn(scope, SharingStarted.Eagerly, false)
    }

    /**
     * Rows against the screen's edge, download marks gone — see
     * [AppSettings.hideDownloadIcons]. Hot and eager for the same reason as
     * [inlineSearch]: it decides a measurement, not a decoration.
     */
    val hideDownloadIcons: StateFlow<Boolean> by lazy {
        settings.hideDownloadIcons.stateIn(scope, SharingStarted.Eagerly, false)
    }

    val sortedAlbums: StateFlow<SortedView<Album>> by lazy {
        combine(library.albums, albumSort, albumSortReversed, likedAlbumsOnly) { list, sort, rev, liked ->
            val sorted = sortAlbums(list.filter { !liked || it.liked }, sort, rev)
            // The bucket follows whatever the list is ordered by, so sorting by
            // artist gives an index over artist names rather than no index at all.
            // Both use the same key the sort itself used, or the letters would
            // disagree with the order.
            SortedView(
                sorted,
                when (sort) {
                    AlbumSort.TITLE -> sorted.map { indexLetterOf(titleKey(it.title)) }
                    AlbumSort.ARTIST -> sorted.map { indexLetterOf(sortName(it.artist)) }
                    else -> emptyList()
                },
            )
        }.stateIn(scope, SharingStarted.Eagerly, SortedView())
    }

    val sortedArtists: StateFlow<SortedView<Artist>> by lazy {
        combine(library.artists, artistSort, artistSortReversed, likedArtistsOnly) { list, sort, rev, liked ->
            val sorted = sortArtists(list.filter { !liked || it.liked }, sort, rev)
            SortedView(
                sorted,
                if (sort == ArtistSort.NAME) sorted.map { indexLetterOf(sortName(it.name)) } else emptyList(),
            )
        }.stateIn(scope, SharingStarted.Eagerly, SortedView())
    }

    val sortedPlaylists: StateFlow<SortedView<Playlist>> by lazy {
        combine(library.playlists, settings.playlistSort, settings.playlistSortReversed) { list, sort, rev ->
            val sorted = sortPlaylists(list, sort, rev)
            SortedView(
                sorted,
                if (sort == PlaylistSort.NAME) sorted.map { indexLetterOf(titleKey(it.name)) } else emptyList(),
            )
        }.stateIn(scope, SharingStarted.Eagerly, SortedView())
    }

    /** Queue whatever the current offline mode wants that isn't downloaded yet. */
    private suspend fun topUpDownloads() {
        if (!coreReady) return

        // Nothing to download on the phone's own music: the files are already
        // here, put there by Light's own transfer, and this app has no business
        // fetching anything on their behalf or writing into that folder.
        if (!source.value.supportsDownloads) return

        // Only ever download from the library chosen in download settings.
        //
        // The Room cache holds just the library being browsed — switching library
        // clears and re-syncs it — so when the download library is a *different*
        // one there is nothing legitimate here to enumerate. Downloading whatever
        // happens to be in view instead is how a "Music Library" setting quietly
        // filled up with tracks from another library. Better to fetch nothing than
        // the wrong thing. (null means "follow whatever the app is browsing", which
        // is what the picker offers, so that case always proceeds.)
        val chosen = settings.activeSource.first()?.downloadLibraryId
        if (chosen != null && chosen != settings.libraryId.first()) return

        // The unfiltered library: `library.tracks` hides everything not yet
        // downloaded whenever the offline view is on, which would reduce this to
        // "download what is already downloaded".
        val tracks = library.fullTracks.value
        if (tracks.isEmpty()) return
        val playlistTrackIds = library.playlists.value.flatMap { it.trackIds }.toSet()
        downloader.applyAutoMode(
            allTracks = tracks,
            likedTracks = tracks.filter { it.liked },
            likedAlbumIds = library.fullAlbums.value.filter { it.liked }.map { it.id }.toSet(),
            playlistTracks = tracks.filter { it.id in playlistTrackIds },
            likedArtistNames = library.artists.value.filter { it.liked }.map { it.name }.toSet(),
        )
    }

    private const val DB_NAME = "amp-library.db"
}

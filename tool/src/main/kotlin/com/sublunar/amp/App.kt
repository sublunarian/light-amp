package com.sublunar.amp

import android.util.Log
import com.sublunar.amp.art.ArtworkLoader
import com.sublunar.amp.art.ArtworkNeed
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.Artist
import com.sublunar.amp.data.ArtistSort
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.Connectivity
import com.sublunar.amp.data.DataMode
import com.sublunar.amp.data.DownloadStore
import com.sublunar.amp.data.Downloader
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.LibraryRepository
import com.sublunar.amp.data.LinkRules
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.NetworkGate
import com.sublunar.amp.data.OfflineMode
import com.sublunar.amp.data.PendingActions
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.PlaylistSort
import com.sublunar.amp.data.Reachability
import com.sublunar.amp.data.SongSort
import com.sublunar.amp.data.SourceLibrary
import com.sublunar.amp.data.StreamProxy
import com.sublunar.amp.data.TagFilter
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.creditNames
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.LibraryDatabase
import com.sublunar.amp.data.db.MAX_BOUND_IDS
import com.sublunar.amp.data.db.libraryDatabaseName
import com.sublunar.amp.data.hasComposer
import com.sublunar.amp.data.hasGenre
import com.sublunar.amp.data.noteOom
import com.sublunar.amp.data.sortAlbums
import com.sublunar.amp.data.sortArtists
import com.sublunar.amp.data.sortName
import com.sublunar.amp.data.sortPlaylists
import com.sublunar.amp.data.sortSongs
import com.sublunar.amp.data.titleKey
import com.sublunar.amp.playback.LateUnbindGuard
import com.sublunar.amp.playback.PlaybackController
import com.sublunar.amp.playback.output.Outputs
import com.sublunar.amp.ui.components.NOTICE_MS
import com.sublunar.amp.ui.components.indexLetterOf
import com.thelightphone.sdk.LightWork
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.audio.DefaultLightAudio
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.display.LightDisplayColor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's wiring, built once by [boot] from the boot screen and read by every
 * screen: one database and one client per source, with the active source's
 * held as state.
 */
object App {
    lateinit var scope: CoroutineScope
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var library: LibraryRepository
        private set
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

    /** The source the library is showing, as state: every screen reads what it can do. */
    private val _source = MutableStateFlow(AppSettings.placeholderSource())
    val source: StateFlow<MusicSource> = _source

    /**
     * A line on top of whatever screen is showing, for a few seconds: a write
     * that didn't land, said after the sheet that made it has closed. Drawn
     * once, by PlayerTheme, which every screen passes through.
     */
    private val _writeNotice = MutableStateFlow<String?>(null)
    val writeNotice: StateFlow<String?> = _writeNotice
    private var noticeJob: Job? = null

    /** Show [text] in place of whatever notice is up, and take it down after [NOTICE_MS]. */
    @Synchronized
    fun tell(text: String) {
        noticeJob?.cancel()
        _writeNotice.value = text
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            _writeNotice.value = null
        }
    }

    /**
     * One Room database per source, by id, kept open once built: there are only
     * a handful, and switching back to a source shows its library at once.
     */
    private val databases = mutableMapOf<String, LibraryDatabase>()
    private lateinit var lightContext: SealedLightContext
    private val _dao = MutableStateFlow<LibraryDao?>(null)

    /** The active source's DAO. Never read before [boot] has built the first. */
    private fun dao(): LibraryDao = _dao.value ?: error("Library database not ready")

    /**
     * Locked while looking up and building: callers on several threads missing
     * together would each build a Room instance on the same file, and Room
     * tells only the instance that wrote, so lists on the other would never
     * move. Cheap to hold: building opens nothing until the first query.
     */
    private fun databaseFor(source: MusicSource): LibraryDatabase = synchronized(databases) {
        databases.getOrPut(source.id) {
            lightContext.buildDatabase(LibraryDatabase::class.java, libraryDatabaseName(source.id))
        }
    }

    /**
     * One client per source, kept for as long as the source is: downloads run
     * for every source at once, and browsing a source must use the client its
     * downloads use, not a second connection pool to the same host. Keyed on
     * the whole record, so a renewed token or a changed address rebuilds it.
     */
    private val clients = mutableMapOf<String, Pair<MusicSource, MusicServer>>()

    /** The client that talks to [source], or null for the phone's own music. */
    private fun clientFor(source: MusicSource): MusicServer? {
        synchronized(clients) {
            val held = clients[source.id]
            if (held != null && held.first == source) return held.second
            held?.second?.close()
            val built = source.toClient()
            if (built == null) clients.remove(source.id) else clients[source.id] = source to built
            return built
        }
    }

    private fun forgetClient(sourceId: String) {
        synchronized(clients) { clients.remove(sourceId)?.second?.close() }
    }

    /**
     * Every downloaded track on the phone, source by source, read from each
     * source's own database: LibraryRepository knows only the active one. For
     * the downloads inventory, which plays nothing. A download whose track row
     * is missing (the cache cleared, no sync since) is left out; its bytes
     * still count in the storage figure, which is read off the disk.
     */
    suspend fun downloadsBySource(): List<Pair<String, List<Track>>> =
        settings.sources.first().mapNotNull { source ->
            val dao = databaseFor(source).libraryDao()
            val ids = runCatching { dao.downloadedIds() }
                .noteOom("downloads of ${source.name}").getOrDefault(emptyList())
            if (ids.isEmpty()) return@mapNotNull null
            val tracks = runCatching { downloadedTracks(dao, ids) }
                .noteOom("downloaded tracks of ${source.name}")
                .getOrDefault(emptyList())
            if (tracks.isEmpty()) null else source.name to tracks
        }

    /**
     * The songs of [ids] that [dao] holds, in id order, which the page's sort
     * keeps among equals. The browsed source's come from the library already in
     * memory rather than as a second copy: only what it hasn't read is asked for.
     */
    private suspend fun downloadedTracks(dao: LibraryDao, ids: List<String>): List<Track> {
        val unread = ids.toHashSet()
        val loaded = library.loadedTracks(dao).filter { unread.remove(it.id) }
        val read = unread.toList().chunked(MAX_BOUND_IDS).flatMap { dao.tracksByIds(it) }
        return (loaded + read).sortedBy { it.id }
    }

    /** Drop a removed source's cached library and downloaded audio: its storage goes with it. */
    suspend fun forgetSource(source: MusicSource) {
        // Whatever it still had queued goes first, so nothing below is undone
        // by a transfer landing a moment later.
        downloader.cancelSource(source.id)
        val db = databaseFor(source)
        // Named before the tables go: the covers on disk can only be found
        // through the ids this database holds — see ArtworkLoader.forget.
        val covers = runCatching { db.libraryDao().allCoverArtIds() }
            .noteOom("covers of ${source.name}").getOrDefault(emptyList())
        db.libraryDao().apply {
            clearTracks()
            clearAlbums()
            clearLikedArtists()
            clearDownloads()
            clearAllTopSongs()
            clearAllPlaylistTracks()
        }
        downloads.deleteSource(source.id)
        artwork.forget(source.id, covers)
        backgroundPlaylistTracks.remove(source.id)
        backgroundPlaylistFailedAt.keys.removeAll { it.startsWith("${source.id}/") }
        forgetClient(source.id)
    }

    /**
     * The cover files the artwork budget must never evict: one per downloaded
     * song's album, across every source. Removing the downloads frees them.
     */
    private suspend fun protectedCoverFiles(): Set<String> =
        settings.sources.first().flatMap { source ->
            runCatching { databaseFor(source).libraryDao().downloadedCoverArtIds() }
                .noteOom("protected covers of ${source.name}")
                .getOrDefault(emptyList())
                .map { artwork.fileNameFor(source.id, it) }
        }.toSet()

    /**
     * Every cover file some source's library points at, which the monthly
     * expiry leaves alone — see ArtworkLoader.expireUnclaimed. Null if any
     * database couldn't be read: unreadable looks like empty from here, and
     * nothing is expired on a launch that can't tell.
     */
    private suspend fun claimedCoverFiles(): Set<String>? {
        val claimed = HashSet<String>()
        for (source in settings.sources.first()) {
            val ids = runCatching { databaseFor(source).libraryDao().allCoverArtIds() }
                .noteOom("claimed covers of ${source.name}").getOrNull() ?: return null
            ids.mapTo(claimed) { artwork.fileNameFor(source.id, it) }
        }
        return claimed
    }

    /** The cover sync in flight, if one is — see [syncCovers]. */
    private var coverSync: Job? = null

    /**
     * Fetch the covers the library on screen has and the phone doesn't, on an
     * unmetered link only, in every data mode: nobody asked for these bytes,
     * and covers still load as they are looked at. Lists on a metered link draw
     * only covers already here, so this decides how a library looks on cellular.
     * Run after a sync, when the link becomes free, and at launch.
     *
     * One run at a time, replaced rather than queued: a second trigger is the
     * same library, whose fetched covers are on disk, or a different one.
     */
    private fun syncCovers() {
        synchronized(this) {
            coverSync?.cancel()
            coverSync = scope.launch {
                // The stored setting, not [hideArtwork]: at launch that flow
                // still holds its placeholder, and "off" has to mean not one.
                if (settings.artwork.first() == ArtworkMode.NONE) return@launch
                val source = settings.activeSource.first() ?: return@launch
                val client = clientFor(source) ?: return@launch
                // The chosen library, as the lists filter it — see
                // LibraryRepository.allAlbums — newest first: those are the
                // covers a run cut short is most likely to be asked for.
                val wanted = runCatching { databaseFor(source).libraryDao().allAlbumsSnapshot() }
                    .noteOom("cover sync of ${source.name}")
                    .getOrDefault(emptyList())
                    .filter { source.libraryId == null || it.libraryId == null || it.libraryId == source.libraryId }
                    .sortedByDescending { it.createdMs }
                    .mapNotNull { it.coverArtId }
                if (wanted.isEmpty()) return@launch
                val startedMs = System.currentTimeMillis()
                val result = artwork.syncCovers(
                    sourceId = source.id,
                    client = client,
                    coverArtIds = wanted,
                    protectedFiles = protectedCoverFiles(),
                    mayContinue = {
                        yieldToPlayback()
                        // Asked of the system, not of the flow: this is the
                        // last look before bytes that must not cost anything.
                        val now = rulesNow()
                        now.unmetered && now.mayStream && _source.value.id == source.id
                    },
                )
                if (result.missing > 0) {
                    Log.i(
                        "AmpArt",
                        "cover sync: ${source.name}: ${result.fetched} of ${result.missing} missing fetched " +
                            "(${result.wanted} in the library), ${result.fetchedBytes shr 10} KB in " +
                            "${(System.currentTimeMillis() - startedMs) / 1000} s" +
                            (result.stopped?.let { " — stopped: $it" } ?: ""),
                    )
                }
            }
        }
    }

    /**
     * Delete every source's downloaded audio (the Offline page's Delete All):
     * the queue is dropped whole, then every source's index and folder goes,
     * since the downloader knows the queue, not what is on the disk.
     */
    suspend fun deleteAllDownloads() {
        downloader.cancelAll()
        settings.sources.first().forEach { wipeDownloads(it) }
    }

    /**
     * Delete one source's downloaded audio (the Offline page's per-server
     * Delete). Only its queue goes: the other sources' downloads carry on.
     */
    suspend fun deleteDownloadsFor(source: MusicSource) {
        downloader.cancelSource(source.id)
        wipeDownloads(source)
    }

    private suspend fun wipeDownloads(source: MusicSource) {
        // A database that won't open shouldn't save its files: the bytes are
        // what fills the phone, and the index is rebuilt from them.
        runCatching { databaseFor(source).libraryDao().clearDownloads() }
        downloads.deleteSource(source.id)
    }

    /** Whether the browsed server is answering — see [Reachability]. */
    val reachability: Reachability by lazy {
        Reachability(scope, client = { serverClient.value }, mayAsk = { rules.value.mayTalkToServer })
    }
    val serverReachable: StateFlow<Boolean> get() = reachability.reachable

    /**
     * True is proof, and is taken as such. False is a *suspicion* (one stream
     * that died, one sync that failed), settled by asking the server at once.
     */
    private fun reportServerReachable(reachable: Boolean) {
        if (reachable) reachability.report(true) else reachability.check(delayMs = 0L)
    }

    /**
     * What the connection and the mode allow right now — see [LinkRules]. The
     * one state everything reads, so a change reaches all of it at once.
     */
    val rules: StateFlow<LinkRules> by lazy {
        combine(Connectivity.network, dataMode, reachability.reachable) { net, mode, reachable ->
            LinkRules(
                connected = net.isConnected,
                unmetered = net.isConnected && !net.isMetered,
                mode = mode,
                serverReachable = reachable,
                wifi = net.isConnected && net.isWifi,
            )
        }.stateIn(scope, SharingStarted.Eagerly, LinkRules.UNKNOWN)
    }

    /**
     * True when the library should show only what is on the phone: nothing
     * else can be played right now (the mode's rule, no link, or a server not
     * answering). Never for the phone's own music, which is all here and none
     * of it a download: narrowed to downloads, it would go blank offline.
     */
    @OptIn(FlowPreview::class)
    val offlineOnly: Flow<Boolean> by lazy {
        // Two speeds. The link or the mode forbidding streams is a fact, and
        // the lists follow it at once. The server not answering is a suspicion
        // until it lasts: on poor reception a ping is often missed, and the
        // lists would flicker to downloads and back. Playback still reacts to
        // the first miss; only the lists wait.
        combine(
            source.map { !it.hasServer }.distinctUntilChanged(),
            rules.map { !it.mayTalkToServer }.distinctUntilChanged(),
            rules.map { !it.serverReachable }.distinctUntilChanged()
                .debounce { gone -> if (gone) SERVER_GONE_SETTLE_MS else 0L },
        ) { local, link, server -> !local && (link || server) }.distinctUntilChanged()
    }

    /** How long the server must stay silent before the lists narrow to downloads. */
    private const val SERVER_GONE_SETTLE_MS = 20_000L

    /** How long a new link settles before parked download servers are asked about it. */
    private const val PARKED_PROBE_SETTLE_MS = 2_000L

    /** The mode as it stands, for gates that must answer synchronously. */
    private val dataMode: StateFlow<DataMode> by lazy {
        settings.dataMode.stateIn(scope, SharingStarted.Eagerly, DataMode.WIFI_ONLY)
    }

    /**
     * Whether the server may be spoken to at all (sync, playlists, lyrics).
     * Wi-Fi Only means none off an unmetered link; the other modes allow
     * metadata anywhere and restrict only real bytes.
     */
    fun metadataAllowed(): Boolean = rules.value.mayTalkToServer

    /**
     * Step aside while the player is trying to start a stream: on a slow link a
     * sync six requests wide leaves the player a sixth of very little, and the
     * song takes a minute to start. Background loops call this between
     * requests. Bounded, so a stream that never starts can't hold them for
     * ever, and free when nothing is starting.
     */
    suspend fun yieldToPlayback() {
        if (!coreReady || !playback.buffering.value) return
        withTimeoutOrNull(YIELD_TO_PLAYBACK_MS) { playback.buffering.first { !it } }
    }

    private const val YIELD_TO_PLAYBACK_MS = 20_000L

    /** The loopback door between the platform player and the music server — see [StreamProxy]. */
    val streamProxy: StreamProxy by lazy {
        StreamProxy(
            gateOpen = { NetworkGate.isOpen() },
            upstream = { proxyUpstream },
            log = { Log.i("AmpProxy", it) },
            // Less slack on a link that costs money: read-ahead spent on a
            // track that is then skipped is data spent on nothing.
            readAheadBytes = {
                if (Connectivity.isUnmetered()) StreamProxy.READ_AHEAD_BYTES else StreamProxy.READ_AHEAD_METERED_BYTES
            },
        )
    }
    /**
     * No more patient than the player, which gives a connection eight seconds
     * and then retries through the door: a longer upstream wait would stack
     * another stream, another transcode, on a slow link with each retry.
     */
    private val proxyUpstream by lazy { NetworkGate.transferClient(connectTimeoutMs = 7_000L, readTimeoutMs = 8_000L) }

    /**
     * [rules], computed on the spot from a fresh look at the link. The flow is
     * a few dispatch hops behind its inputs, and a decision made at the event
     * that changed them (a stream just refused, a ping just failed) would read
     * the old answer. Two binder reads.
     */
    fun rulesNow(): LinkRules {
        Connectivity.refresh()
        val net = Connectivity.network.value
        return LinkRules(
            connected = net.isConnected,
            unmetered = net.isConnected && !net.isMetered,
            mode = dataMode.value,
            serverReachable = reachability.reachable.value,
            wifi = net.isConnected && net.isWifi,
        )
    }

    /**
     * Something happened that the connectivity callback may have missed — the
     * app came back to the front. Re-read the link, and if the server was last
     * seen silent, ask it again. Cheap enough to call on every return.
     */
    fun linkMayHaveChanged() {
        if (!coreReady) return
        Connectivity.refresh()
        if (!serverReachable.value) reachability.check(delayMs = 0L)
        // Nothing, unless a download server is parked — see Downloader.wakeParked.
        scope.launch { downloader.wakeParked() }
    }

    /**
     * The one rule every byte answers to: may the network be used right now?
     * False only in Wi-Fi Only on a metered link, or with no link at all.
     *
     * The same answer as [metadataAllowed], enforced rather than consulted:
     * that one is the courtesy that saves work, this the wall behind it. Every
     * HTTP client is built through NetworkGate, the player streams only
     * through StreamProxy, and the raw download and discovery paths ask
     * directly, so a path that never asked is refused all the same.
     *
     * Asked of the system each time, not of the last callback: at the edge of
     * Wi-Fi the default network moves to cellular without either going away,
     * and the report can arrive after the socket would.
     */
    private fun networkAllowed(): Boolean =
        dataMode.value != DataMode.WIFI_ONLY || Connectivity.isUnmeteredNow()

    /**
     * Whether real bytes may move (downloads, cover art): on an unmetered link,
     * or on a metered one in Make it Hurt, whose name is the consent.
     */
    private fun heavyDataAllowed(): Boolean = rules.value.mayMoveHeavyBytes

    // Volatile: set at the end of boot on the main thread, and read by the
    // collectors boot starts, which run on others — see topUpDownloads.
    @Volatile
    private var coreReady = false
    val isReady: Boolean get() = coreReady

    /** The key BackgroundSync's job is registered under. */
    private const val SYNC_JOB_KEY = "library-sync"

    fun boot(sealedActivity: SealedLightActivity, context: SealedLightContext) {
        // First, before anything below can make or release a player handle,
        // and outside the once-only block because it keeps its own flag — see
        // LateUnbindGuard for why it has to be a raw post from right here.
        LateUnbindGuard.install()
        if (!coreReady) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            settings = AppSettings(context.dataStore)
            lightContext = context
            Connectivity.bind(context, scope)
            // The wall goes up before anything exists to be built behind it:
            // every client below is made through NetworkGate, and the player
            // — attached or the detached service, same process — streams only
            // through StreamProxy, whose upstream is one of those clients.
            NetworkGate.policy = ::networkAllowed
            // The door the player streams through — see StreamProxy. Started
            // here, before anything builds a queue, and proven before it is
            // used: until its self-test passes, nothing streams.
            streamProxy.start(scope)
            NetworkGate.isMetered = { Connectivity.network.value.let { !it.isConnected || it.isMetered } }
            // Speakers are reached on a stack of their own, outside the wall
            // above, so each output family is held to the link's rules here.
            Outputs.install(::rulesNow)
            artwork = ArtworkLoader(
                context.filesDir,
                serverClient,
                fetchAllowed = { need ->
                    when (need) {
                        ArtworkNeed.FOCUSED -> rules.value.mayFetchFocusedArt
                        ArtworkNeed.BROWSING -> rules.value.mayMoveHeavyBytes
                    }
                },
                artworkOff = { hideArtwork.value },
            ) { _source.value.id }
            // The source last in use, resolved first: the DAO has to exist before the repository.
            val first = runBlocking { settings.activeSource.first() }
                ?: AppSettings.placeholderSource()
            _source.value = first
            _dao.value = databaseFor(first).libraryDao()
            downloads = DownloadStore(context.filesDir)
            // One-time: audio downloaded before sources existed sits in the old
            // flat folder, and belongs to whichever source inherited that server.
            if (first.id == AppSettings.LEGACY_SOURCE_ID) downloads.adoptLegacyFiles(first.id)
            pending = PendingActions(settings)
            library = LibraryRepository(
                daos = _dao,
                serverClient = serverClient,
                libraryId = settings.libraryId,
                offlineOnly = offlineOnly,
                metadataAllowed = ::metadataAllowed,
                bulkAllowed = ::heavyDataAllowed,
                yieldToPlayback = ::yieldToPlayback,
                pending = pending,
                tell = ::tell,
                settings = settings,
                scope = scope,
            )
            downloader = Downloader(
                activeSource = { _source.value },
                daoFor = { databaseFor(it).libraryDao() },
                clientFor = ::clientFor,
                store = downloads,
                settings = settings,
                scope = scope,
                lightContext = context,
                heavyDataAllowed = ::heavyDataAllowed,
                waitingLabel = { rules.value.heavyWaitingFor ?: NetworkGate.WAITING_FOR_WIFI },
                yieldToPlayback = ::yieldToPlayback,
            )
            playback = PlaybackController(
                settings, serverClient, ::dao, downloads, scope,
                metadataAllowed = ::metadataAllowed,
                standInDir = java.io.File(context.filesDir, "stand-in"),
            )

            // A finished sync is proof the server is there. (Its end also tops up
            // the downloads, through the size collector below.)
            library.onSyncSucceeded = {
                reportServerReachable(true)
                // Its downloads too, if they were parked — the reachability
                // flow only speaks on a change, and a download failing never
                // tells it anything. See Downloader.parkings.
                downloader.tryNow(source.value.id)
                scope.launch {
                    // Fresh library rows are also the first chance to work out
                    // which album a reindexed download belongs to.
                    runCatching { dao().backfillDownloadAlbums() }.noteOom("download album backfill")
                    // ...and the first chance to put back the words a rebuilt
                    // download row lost, which the files themselves don't carry.
                    runCatching { downloader.refillMissingLyrics() }
                }
                // And the covers of whatever the sync just brought in.
                syncCovers()
            }
            library.onCoversDropped = { sourceId, ids -> artwork.drop(sourceId, ids) }
            // The playlists cached last time, shown before anything is fetched.
            scope.launch { library.primePlaylists() }
            scope.launch {
                serverReachable.collect { reachable ->
                    // The browsed server's downloads stop when it goes and pick
                    // up when it comes back: reachability says nothing of the
                    // other sources, which are judged by their own transfers.
                    downloader.setOffline(source.value.id, !reachable)
                    // Back: what was held while it was gone is sent.
                    if (reachable) library.flushPending()
                }
            }
            // One line per change of the rules: which inputs moved and what they
            // now allow. The first thing to read when something did the unexpected.
            scope.launch {
                rules.collect { r ->
                    Log.i(
                        "AmpNet",
                        "rules: connected=${r.connected} unmetered=${r.unmetered} wifi=${r.wifi} " +
                            "mode=${r.mode} server=${r.serverReachable} → talk=${r.mayTalkToServer} " +
                            "stream=${r.mayStream} heavy=${r.mayMoveHeavyBytes} lan=${r.mayUseLan}",
                    )
                }
            }
            // A socket opened over cellular stays usable for minutes, and the
            // rule answers for the default network, not a socket's. So the rule
            // closing shuts every connection, and *entering* Wi-Fi Only shuts
            // just those opened on a metered link: on Wi-Fi the rest are free,
            // and shutting them would fail the transfers in flight. The rule
            // opening shuts nothing.
            scope.launch {
                var wasWifiOnly = false
                var wasClosed = false
                rules.map { (it.mode == DataMode.WIFI_ONLY) to (it.mode == DataMode.WIFI_ONLY && !it.unmetered) }
                    .distinctUntilChanged()
                    .collect { (wifiOnly, closed) ->
                        when {
                            closed && !wasClosed -> NetworkGate.slam()
                            wifiOnly && !wasWifiOnly -> NetworkGate.slamMetered()
                        }
                        wasWifiOnly = wifiOnly
                        wasClosed = closed
                    }
            }
            // Downloads held for a quality their server couldn't meet go the
            // moment that changes (another quality picked, or a sync found the
            // call after all). The worker had ended — see Downloader.drain —
            // and nothing else would start it for a source on Manual.
            scope.launch {
                settings.sources
                    .map { list -> list.filterNot { it.downloadFormatAvailable }.map { it.id }.toSet() }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { downloader.retry() }
            }
            // And those a server refused, once their record changes — signed
            // in again, say. See Downloader.refusals. Or parked, when an address
            // changed is the reason it stopped answering: Downloader.parkings.
            scope.launch {
                settings.sources
                    .drop(1)
                    .collect {
                        if (downloader.refused.value.isNotEmpty() || downloader.parked.value.isNotEmpty()) downloader.retry()
                    }
            }
            // Bulk work put off on a metered link is picked up the moment bytes
            // may move, and a parked server is asked again, a mode change with
            // no change of link included. After the same settle as a change of
            // link (below), so a ping doesn't go out in a new link's first
            // seconds. See Downloader.wakeParked.
            scope.launch {
                rules.map { it.mayMoveHeavyBytes }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { may ->
                        if (may) {
                            scope.launch {
                                delay(PARKED_PROBE_SETTLE_MS)
                                downloader.wakeParked()
                            }
                            topUpDownloads()
                        }
                    }
            }
            // A queue held by the rule has no worker waiting on it — see
            // Downloader.drain — so the rule moving is what starts one.
            scope.launch {
                rules.map { it.heavyWaitingFor }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { downloader.holdChanged() }
            }
            // The covers a sync on cellular couldn't fetch, or a run cut short.
            // Its own edge: in Make it Hurt the one above never moves, and the
            // cover sync is for free bytes whatever the mode.
            scope.launch {
                rules.map { it.unmetered && it.mayStream }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { free -> if (free) syncCovers() }
            }
            // Being allowed to ask the server is a reason to ask: a *mode*
            // change isn't a network change, and a server last seen silent on
            // a dying Wi-Fi would stay "gone" on a cellular link that reaches it.
            scope.launch {
                rules.map { it.mayTalkToServer }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { may -> if (may) reachability.check(delayMs = 0L) }
            }
            // A change of link ends every connection: they belong to the network
            // just left. Dropped before the reachability check's wait, so its
            // ping goes out on a fresh one.
            scope.launch {
                Connectivity.network
                    .map { Triple(it.isConnected, it.isMetered, it.isWifi) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        Log.i("AmpNet", "link changed → dropping every connection")
                        NetworkGate.linkChanged()
                    }
            }
            // Every change of connection is a reason to ask the server again: a
            // LAN address stops answering on cellular, and starts again at home.
            scope.launch {
                Connectivity.network
                    .map { Triple(it.isConnected, it.isMetered, it.isWifi) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { reachability.check() }
            }
            // ...and every parked download server, which nothing else asks.
            // After a settle: a new link's first seconds are the worst moment
            // to ask (see NetworkGate.linkChanged).
            scope.launch {
                Connectivity.network
                    .map { Triple(it.isConnected, it.isMetered, it.isWifi) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collectLatest {
                        delay(PARKED_PROBE_SETTLE_MS)
                        downloader.wakeParked()
                    }
            }
            // Held writes also go whenever there is both a client and a
            // connection: reachability starts out true and may never change, so
            // the serverReachable collector can miss Wi-Fi coming back. Watching
            // either one alone misses a case. Cheap otherwise: flushPending asks first.
            scope.launch {
                combine(serverClient, Connectivity.changed) { client, (connected, _) ->
                    client != null && connected
                }
                    .distinctUntilChanged()
                    .collect { ready -> if (ready) runCatching { library.flushPending() } }
            }
            scope.launch { downloader.setUserPaused(settings.downloadsPaused.first()) }
            // The colour workaround (an off-SDK spike, see LightDisplayColor)
            // follows the artwork switch: with no covers there is nothing for it to do.
            scope.launch {
                settings.artwork.collect { LightDisplayColor.enabled = it != ArtworkMode.NONE }
            }
            // A refused login is not the server being gone: marked unreachable,
            // the library would narrow to downloads and hide a good cache.
            library.onSyncFailed = { authFailure -> if (!authFailure) reportServerReachable(false) }

            // The client, the database and the source change over together, in
            // order — see [swapSource].
            scope.launch {
                settings.activeSource.collect { active -> swapSource(active) }
            }
            // Whatever folders the server reports, remembered on the source so
            // the Sources page can list them instantly and offline.
            scope.launch {
                library.syncState.collect { state ->
                    if (state.syncing || state.lastSyncedMs == 0L) return@collect
                    val current = source.value
                    if (!current.supportsLibraries) return@collect
                    val folders = runCatching { library.musicFolders() }.getOrNull() ?: return@collect
                    // Empty means "couldn't ask" (a network failure, a client not
                    // up yet) as often as "none", and written through it would
                    // wipe libraries already found. None looks the same anyway.
                    if (folders.isEmpty()) return@collect
                    settings.setSourceLibraries(
                        current.id,
                        folders.map { SourceLibrary(it.id, it.name) },
                    )
                }
            }
            // Any source's mode changing (the Offline page sets it per server)
            // starts fetching without waiting for the next sync.
            scope.launch {
                settings.sources
                    .map { list -> list.map { it.id to it.offlineMode } }
                    .distinctUntilChanged()
                    .collect { topUpDownloads() }
            }
            // Rebuild the download index from disk before anything reads it: a
            // schema change with no migration path drops the table, and the
            // audio would be fetched again.
            scope.launch { downloader.reindexFromDisk() }
            // The artwork cache's launch upkeep. Tracks point at their album's
            // cover first, so the protected set is one file per downloaded album
            // and per-song copies fall to the budget. Then the expiry (what no
            // library names and nobody looked at in a month), the budget — see
            // ArtworkLoader.trimToBudget — and a cover sync, since a launch
            // inside the sync floor has no sync to start one.
            scope.launch {
                settings.sources.first().forEach { source ->
                    runCatching { databaseFor(source).libraryDao().collapseTrackCovers() }
                        .noteOom("cover collapse of ${source.name}")
                        .onSuccess { if (it > 0) Log.i("AmpArt", "${source.name}: $it tracks now share their album's cover") }
                }
                claimedCoverFiles()?.let { artwork.expireUnclaimed(it) }
                artwork.trimToBudget(protectedCoverFiles())
                syncCovers()
            }
            // TEMPORARY, REMOVE BEFORE COMMUNITY REVIEW — the one-time repair
            // of mp3s downloaded before Amp wrote their index. Runs once per
            // install and never again; everything downloaded since is indexed
            // as it lands, in Downloader. Delete this block together with
            // DownloadStore.indexMp3s and AppSettings.mp3IndexRepairNeeded /
            // markMp3IndexRepaired — see the removal list on indexMp3s.
            scope.launch(Dispatchers.IO) {
                if (!settings.mp3IndexRepairNeeded()) return@launch
                val n = downloads.indexMp3s()
                // Only now: see markMp3IndexRepaired for why not before.
                settings.markMp3IndexRepaired()
                Log.i("AmpMp3", "one-time repair: indexed $n downloaded mp3(s) that had none")
            }
            // A source's downloads yield to its sync, hundreds of small requests
            // that a saturated transcoder turns into a crawl. Only the browsed
            // source syncs, so only its downloads wait.
            scope.launch {
                library.syncState.collect {
                    downloader.setSyncing(if (it.syncing) source.value.id else null)
                }
            }
            // Cue up whatever was playing when the app last went away. Waits for
            // the player, attached at the end of this very method while this
            // runs on another thread: restoring without one does nothing, and
            // is never retried.
            scope.launch {
                playback.bound.first { it }
                // And for the client, when this source can have one: an item's
                // stream URL is fixed when it is built, so with no client the
                // queue would restore and nothing in it would play. Not for a
                // source that never will (the phone's own music, a server
                // missing its address or token). Asked of the pool, so it is
                // the client the source switch uses.
                val active = settings.activeSource.filter { it != null }.first()
                if (active != null && clientFor(active) != null) {
                    serverClient.filter { it != null }.first()
                }
                // The cached library, last and not kept: a list held across the
                // restore's waits keeps a whole library alive once a write
                // replaces it. Read again when the ids are matched.
                library.fullTracks.first { it.isNotEmpty() }
                playback.restoreState { library.fullTracks.value }
            }
            // The download top-up again when the first cached library lands
            // (sync or not: the mode collector above ran before there was
            // anything to queue), at the end of every sync, failed ones
            // included, and whenever the library changes size outside one.
            // A sync's batches wait for its end. Sizes only, and nothing
            // awaited here: a collector suspended on a list holds it.
            scope.launch {
                var requestedAt = 0
                var wasSyncing = false
                combine(library.fullTracks.map { it.size }, library.syncState.map { it.syncing }, ::Pair)
                    .distinctUntilChanged()
                    .collect { (size, syncing) ->
                        val syncEnded = wasSyncing && !syncing
                        wasSyncing = syncing
                        val firstLibrary = requestedAt == 0 && size > 0
                        val moved = !syncing && size > 0 && size != requestedAt
                        if (!syncEnded && !firstLibrary && !moved) return@collect
                        if (size > 0) requestedAt = size
                        scope.launch { topUpDownloads() }
                    }
            }
            // Once per process, like the rest: per boot, each activity would add another.
            scope.launch { settings.replayGain.collect { playback.replayGain.value = it } }
            coreReady = true
            // Periodic background library refresh (min 15 min on WorkManager).
            LightWork.enqueuePeriodic(context, SYNC_JOB_KEY, 30.minutes)
        }
        playback.attach(DefaultLightAudio(sealedActivity))
    }

    /**
     * The screen that booted [sealedActivity] is backing out. Named, so that an
     * old activity's back-out can't release the handle a newer one has taken
     * over — see PlaybackController.attach.
     */
    fun shutdown(sealedActivity: SealedLightActivity) {
        playback.release(owner = DefaultLightAudio(sealedActivity))
    }

    /**
     * A sorted library view plus its A–Z buckets, computed once per change on
     * [scope], off the main thread. Sorting a large library takes long enough
     * that doing it in a tab's composition, on every visit, froze the app.
     */
    data class SortedView<T>(val items: List<T> = emptyList(), val letters: List<Char> = emptyList())

    /**
     * The sort settings, held as state rather than collected per screen: a
     * collected setting draws its placeholder for a frame first, and the list
     * re-sorts under you. The sorted views are built on these.
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
     * Whether each tab is narrowed to what you have liked, one flag per tab.
     * Held for the same reason as the sorts.
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

    /**
     * Which tag each list is narrowed to, as one value so the views below stay
     * inside `combine`'s five-flow form. Blank means no filter.
     */
    val songsTagFilter: StateFlow<TagFilter> by lazy {
        combine(settings.songsGenre, settings.songsComposer) { g, c -> TagFilter(g, c) }
            .stateIn(scope, SharingStarted.Eagerly, TagFilter())
    }
    val albumsTagFilter: StateFlow<TagFilter> by lazy {
        combine(settings.albumsGenre, settings.albumsComposer) { g, c -> TagFilter(g, c) }
            .stateIn(scope, SharingStarted.Eagerly, TagFilter())
    }

    /**
     * The albums a tag filter leaves standing, or null when nothing is filtered.
     * Derived from the songs: an album carries one genre of its own and no
     * composer, and is a Bach album because Bach wrote what is on it.
     */
    private val albumsMatchingTags: StateFlow<Set<String>?> by lazy {
        combine(library.tracks, albumsTagFilter) { tracks, filter ->
            if (filter.isEmpty) return@combine null
            tracks.asSequence()
                .filter { filter.admits(it) }
                .mapNotNull { it.albumId }
                .toSet()
        }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    val sortedSongs: StateFlow<SortedView<Track>> by lazy {
        combine(library.tracks, songSort, songSortReversed, likedSongsOnly, songsTagFilter) {
                list, sort, rev, liked, tags ->
            val narrowed = list.asSequence()
                .filter { !liked || it.liked }
                .filter { tags.admits(it) }
                .toList()
            val sorted = sortSongs(narrowed, sort, rev)
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

    /** Whether [track] has the filter's genre and composer, a blank one matching all. */
    private fun TagFilter.admits(track: Track): Boolean =
        (genre.isEmpty() || track.hasGenre(genre)) && (composer.isEmpty() || track.hasComposer(composer))

    /**
     * Whether covers are switched off, as state: every row in a long list asks,
     * and a DataStore Flow per row would be a subscription and a read each.
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
     * Which shape the bottom bar takes — see [AppSettings.layoutMode]. Held,
     * since the bar is on every screen and a placeholder frame would show.
     */
    val layoutMode: StateFlow<LayoutMode> by lazy {
        settings.layoutMode.stateIn(scope, SharingStarted.Eagerly, LayoutMode.STANDARD)
    }

    /**
     * Artists' own pictures off, sleeves untouched — see
     * [AppSettings.hideArtistImages]. Also true whenever artwork is off
     * wholesale, so the one place that reads it needs only the one answer.
     */
    val hideArtistImages: StateFlow<Boolean> by lazy {
        // Initial matches the stored default, so the first frame doesn't flash
        // a row of photos that are about to be hidden.
        combine(settings.hideArtistImages, hideArtwork) { off, all -> off || all }
            .stateIn(scope, SharingStarted.Eagerly, true)
    }

    /**
     * Rows against the screen's edge, download marks gone — see
     * [AppSettings.hideDownloadIcons]. Held: it decides a measurement.
     */
    val hideDownloadIcons: StateFlow<Boolean> by lazy {
        settings.hideDownloadIcons.stateIn(scope, SharingStarted.Eagerly, false)
    }

    val sortedAlbums: StateFlow<SortedView<Album>> by lazy {
        // The three order settings fold into one flow first: combine takes five
        // flows at most, and the reshuffle nonce made six.
        val order = combine(albumSort, albumSortReversed, settings.shuffleNonce, ::Triple)
        combine(library.albums, order, likedAlbumsOnly, albumsMatchingTags) {
                list, (sort, rev, nonce), liked, tagged ->
            val narrowed = list.filter { (!liked || it.liked) && (tagged == null || it.id in tagged) }
            val sorted = sortAlbums(narrowed, sort, rev, nonce)
            // The letters follow the sort's own key, or they would disagree
            // with the order.
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

    /**
     * Change over to [active]: the client, the database, then the source, in
     * that order and in one place. The source is what everything else keys on
     * (the download top-up, the screens), so it changes *last*: work in a gap
     * would hold one server's identity and another's music, and nothing
     * downstream can defend itself against that.
     */
    private suspend fun swapSource(active: MusicSource?) {
        val next = active ?: return
        if (next.id == _source.value.id) {
            // The same source, unchanged or changed in place (a renewed token, a
            // new address): the client is keyed on the whole record, so a change
            // rebuilds it, and the library doesn't move. `== null` matters: boot
            // sets _source before this collector starts, so its first emission
            // lands here with nothing changed, and must still build the client.
            if (next != _source.value || _serverClient.value == null) {
                _serverClient.value = clientFor(next)
            }
            _source.value = next
            return
        }
        // The playing queue goes: its stream URLs are signed for the server
        // being left. The download queue stays, since each entry names its own
        // source: choosing a source is a browsing choice, not a download scope.
        // A speaker stays chosen for the new queue when the new source has a
        // server to give it an address; the phone's own files have none.
        playback.stop(keepOutput = next.hasServer)
        // A sync for the source being left has nothing to tell us, and its
        // failure would be reported against the new one. Its hold on that
        // source's downloads goes with it.
        library.cancelSync()
        downloader.setSyncing(null)
        // And whatever it said about syncing — see LibraryRepository.adoptSource.
        library.adoptSource(runCatching { settings.lastSyncedMs(next.id) }.getOrDefault(0L))
        // Popular songs, the playlists, the search index and the artist ids are
        // all keyed by name rather than by source, so they answer for the wrong
        // server until they're dropped. Cleared *before* the source changes
        // over, so nothing that recomposes on the new source can catch the old
        // data.
        library.forgetDerived()
        // So are the liked switches and the tag filters: a genre chosen on one
        // server can empty every list on the next, with no row left to clear it.
        settings.clearLibraryFilters()
        // The pool keeps the previous client open: its source may well have
        // downloads still to fetch.
        _serverClient.value = clientFor(next)
        _dao.value = databaseFor(next).libraryDao()
        // Last, so that anything it wakes finds the rest already in place.
        _source.value = next
        // What was known about the server being left says nothing about this
        // one: assume it is there, and ask.
        reachability.reset()
        // Its own sync, now that everything it pins is in place, rather than
        // when the root screen next asks, which races this switch. The floor
        // still applies. On Main, as every other caller is: the check-then-claim
        // inside isn't atomic.
        withContext(Dispatchers.Main) { library.syncInBackground() }
    }

    /**
     * One top-up pass at a time, and a burst of requests is one more pass, not
     * one each: each pass reads every source's tables and asks background
     * servers for their playlists. A request mid-pass marks the pass to run
     * again, and whatever it would have found, the rerun finds.
     */
    private val topUpRunning = Mutex()
    private val topUpRequested = AtomicBoolean(false)

    /**
     * How long a pass waits after being asked for: the triggers fire when the
     * screen is busiest (a source switch refills every list from the
     * database), and the wait folds a burst of triggers into one pass.
     */
    private const val TOP_UP_SETTLE_MS = 3_000L

    /**
     * How long a playlist that would not answer is left alone before being
     * asked again: one that failed for want of memory would fill the heap at
     * every pass.
     */
    private const val PLAYLIST_RETRY_MS = 30 * 60 * 1000L

    /** When a background source's playlist last failed to answer, by "source/playlist". */
    private val backgroundPlaylistFailedAt = ConcurrentHashMap<String, Long>()

    /**
     * Playlist membership for the sources not being browsed, by source id, for
     * the life of the process: the repository knows only the browsed source's.
     * The list once per session, and each playlist's songs once they answer,
     * null until then, so a failing one is asked again alone rather than
     * taking the others with it. Dropped with the source — see [forgetSource].
     */
    private val backgroundPlaylistTracks = ConcurrentHashMap<String, MutableMap<String, List<String>?>>()

    /**
     * Queue whatever each source's offline mode wants that isn't downloaded
     * yet, for every source with a mode set, whichever is being browsed.
     */
    private suspend fun topUpDownloads() {
        if (!coreReady) return
        topUpRequested.set(true)
        while (true) {
            // Someone else is running the pass; the flag tells them to go round
            // once more when they finish.
            if (!topUpRunning.tryLock()) return
            try {
                while (topUpRequested.get()) {
                    // After the screen, and once for a burst — see TOP_UP_SETTLE_MS.
                    delay(TOP_UP_SETTLE_MS)
                    topUpRequested.set(false)
                    topUpPass()
                }
            } finally {
                topUpRunning.unlock()
            }
            // A request that landed between the last check and the unlock has
            // nobody running for it — unless this loop picks it up.
            if (!topUpRequested.get()) return
        }
    }

    private suspend fun topUpPass() {
        for (source in settings.sources.first()) {
            // Nothing to download for the phone's own music: the files are here.
            if (!source.mayDownload || source.offlineMode == OfflineMode.MANUAL) continue
            runCatching { topUpDownloads(source) }
                .noteOom("top-up ${source.name}")
                .onFailure { Log.w("AmpDl", "top-up failed for ${source.name}", it) }
        }
    }

    private suspend fun topUpDownloads(source: MusicSource) {
        val startedMs = System.currentTimeMillis()
        // This source's own tables, not the repository's lists, which follow
        // the browsed source. Asked for ids, not read whole: tracks would be a
        // second copy of the library, held through the playlist fetch below.
        val dao = databaseFor(source).libraryDao()
        // Only the libraries kept on the Sources page ("Shown on"): hiding one
        // says you aren't interested in it. Not the browsed library: what to
        // keep is wider than what to look at.
        val hidden = source.hiddenLibraryIds.filterNotNull().distinct()
        val noneHidden = hidden.isEmpty()
        if (!dao.hasKeptTracks(hidden, noneHidden)) return
        // Playlists count in both modes (Favorites promises them, Everything
        // fetches them first), and no server sends membership with the list.
        val playlistTrackIds = playlistTrackIdsFor(source)
        // Starred on the server, whatever is on screen — not derived from
        // the artists list, which narrows with the offline view.
        val likedArtists = dao.likedArtistNames().toSet()
        downloader.applyAutoMode(
            source = source,
            likedTrackIds = dao.likedKeptTrackIds(hidden, noneHidden),
            playlistTrackIds = playlistTrackIds.toList().chunked(MAX_BOUND_IDS)
                .flatMap { dao.keptTrackIdsAmong(it, hidden, noneHidden) },
            // Scoped as the rest are: a liked record in a hidden library is
            // not fetched, and one in a library not being browsed is.
            likedAlbumTrackIds = dao.likedAlbumTrackIds(hidden),
            likedArtistTrackIds = trackIdsCreditedTo(dao, likedArtists, hidden, noneHidden),
            everyTrackId = { dao.keptTrackIdsNotDownloaded(hidden, noneHidden) },
        )
        // One line per source per pass: its cost, which goes on the playlists.
        Log.i(
            "AmpDl",
            "top-up ${source.name} (${source.offlineMode}): " +
                "${playlistTrackIds.size} in playlists, ${System.currentTimeMillis() - startedMs} ms",
        )
    }

    /**
     * The tracks of [dao]'s library credited to any of [artists], as
     * Track.albumArtistNames reads a credit — worked out over the distinct
     * credits rather than over every row, then asked for by the credits that
     * matched. A blank credit stands for the track artist, as it does there.
     */
    private suspend fun trackIdsCreditedTo(
        dao: LibraryDao,
        artists: Set<String>,
        hidden: List<String>,
        noneHidden: Boolean,
    ): List<String> {
        if (artists.isEmpty()) return emptyList()
        val (blank, credits) = dao.albumArtistCredits().partition { it.isBlank() }
        val matched = credits.filter { credit -> creditNames(credit).any { it in artists } }
        val byCredit = matched.chunked(MAX_BOUND_IDS).flatMap { dao.keptTrackIdsCredited(it, hidden, noneHidden) }
        if (blank.isEmpty()) return byCredit
        val byArtist = dao.artistsCreditedAs(blank)
            .filter { artist -> creditNames(artist).any { it in artists } }
            .chunked(MAX_BOUND_IDS)
            .flatMap { dao.keptTrackIdsByArtist(blank, it, hidden, noneHidden) }
        return byCredit + byArtist
    }

    /** Every track id in any of [source]'s playlists, as far as they can be known. */
    private suspend fun playlistTrackIdsFor(source: MusicSource): Set<String> {
        if (source.id == _source.value.id) {
            // The browsed source: the repository's cache, shared with the
            // Playlists tab. Not Playlist.trackIds, always empty for a server
            // playlist.
            if (library.allPlaylists.value.isEmpty()) runCatching { library.refreshPlaylists() }
            runCatching { library.primePlaylistTrackIds(library.allPlaylists.value.map { it.id }) }
                .noteOom("playlists of ${source.name}")
            return library.freshPlaylistTrackIds().values.flatten().toHashSet()
        }
        val client = clientFor(source) ?: return emptySet()
        // The list, once, behind the metadata gate a sync waits on.
        val known = backgroundPlaylistTracks[source.id] ?: run {
            if (!metadataAllowed()) return emptySet()
            val playlists = runCatching { client.getPlaylists(source.libraryId) }.getOrNull()
                ?: return emptySet()
            HashMap<String, List<String>?>()
                .also { map -> playlists.forEach { map[it.id] = null } }
                .also { backgroundPlaylistTracks[source.id] = it }
        }
        // Then the playlists that haven't answered, bar those that failed
        // lately, as wide in parallel as the repository runs the sync.
        val now = System.currentTimeMillis()
        val missing = known.filterValues { it == null }.keys.filter { id ->
            now - (backgroundPlaylistFailedAt["${source.id}/$id"] ?: 0L) > PLAYLIST_RETRY_MS
        }
        if (missing.isNotEmpty() && metadataAllowed()) {
            val gate = Semaphore(PLAYLIST_PRIME_CONCURRENCY)
            val fetched = coroutineScope {
                missing.map { id ->
                    async {
                        gate.withPermit {
                            id to runCatching { client.getPlaylistTracks(id) }
                                .noteOom("playlist $id of ${source.name}").getOrNull()?.map { it.id }
                        }
                    }
                }.awaitAll()
            }
            // A failed fetch stays null, never cached as empty, and dated so the
            // next passes skip it for a while.
            fetched.forEach { (id, ids) ->
                if (ids != null) known[id] = ids else backgroundPlaylistFailedAt["${source.id}/$id"] = now
            }
        }
        return known.values.flatMapTo(HashSet()) { it.orEmpty() }
    }

    private const val PLAYLIST_PRIME_CONCURRENCY = 6
}

package com.sublunar.amp

import com.sublunar.amp.art.ArtworkLoader
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.Artist
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.ArtistSort
import com.sublunar.amp.data.PendingActions
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.PlaylistSort
import com.sublunar.amp.data.SongSort
import com.sublunar.amp.data.sortAlbums
import com.sublunar.amp.data.sortArtists
import com.sublunar.amp.data.sortPlaylists
import com.sublunar.amp.data.sortSongs
import com.sublunar.amp.data.sortName
import com.sublunar.amp.data.titleKey
import com.sublunar.amp.ui.components.indexLetterOf
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
import com.sublunar.amp.data.OfflineMode
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.TagFilter
import com.sublunar.amp.data.hasComposer
import com.sublunar.amp.data.hasGenre
import com.sublunar.amp.data.SourceLibrary
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.toTrack
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import com.sublunar.amp.data.NetworkGate
import com.thelightphone.sdk.audio.LightAudioNetworkPolicy
import com.sublunar.amp.data.LinkRules
import com.sublunar.amp.data.Reachability
import com.sublunar.amp.data.StreamProxy
import kotlinx.coroutines.withTimeoutOrNull
import com.thelightphone.sdk.cast.DlnaCast
import com.sublunar.amp.art.ArtworkNeed
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview

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
     * One client per source, kept for as long as the source is.
     *
     * The browsed source's client used to be the only one, built on a switch
     * and closed on the next. Downloads run for every source at once now, so
     * a track from a server you are not looking at needs that server's client
     * to fetch it with — and it needs the *same* one the library uses when
     * you switch there, not a second connection pool to the same host. Keyed
     * on the whole record, as the switch always was, so a renewed token or a
     * changed address rebuilds it; closed only when the source is removed.
     */
    private val clients = mutableMapOf<String, Pair<MusicSource, MusicServer>>()

    /** The client that talks to [source], or null for the phone's own music. */
    fun clientFor(source: MusicSource): MusicServer? {
        if (source.kind == SourceKind.LOCAL) return null
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
     * Every downloaded track on the phone, source by source.
     *
     * Each source keeps its own database, and the app only ever has one of them
     * open as *the* library — so nothing else can answer this. Read directly
     * here rather than through LibraryRepository, which is built around whichever
     * source is active and rightly so: everywhere else in the app, a track you
     * can see is a track you can play.
     *
     * Which is exactly what these are not. The page that shows this is an
     * inventory of what the phone is holding; playing them would mean resolving
     * files and streams through a source that isn't the current one, and the
     * page names no source at all until you have more than one.
     *
     * A download whose track row is missing — the library cache cleared, no sync
     * since — is left out. Its bytes are still counted in the storage figure,
     * which is read off the disk.
     */
    suspend fun downloadsBySource(): List<Pair<String, List<Track>>> =
        settings.sources.first().mapNotNull { source ->
            val dao = databaseFor(source).libraryDao()
            val ids = runCatching { dao.downloadedIds() }.getOrDefault(emptyList())
            if (ids.isEmpty()) return@mapNotNull null
            val tracks = runCatching { dao.tracksByIds(ids).map { it.toTrack() } }
                .getOrDefault(emptyList())
            if (tracks.isEmpty()) null else source.name to tracks
        }

    /**
     * Drop a source's cached library and downloaded audio.
     *
     * Called when the user removes it: "remove" has to mean the storage goes too,
     * or a source added and dropped a few times quietly fills the phone.
     */
    suspend fun forgetSource(source: MusicSource) {
        // Whatever it still had queued goes first, so nothing below is undone
        // by a transfer landing a moment later.
        downloader.cancelSource(source.id)
        val db = databaseFor(source)
        // Named before the tables go: the covers on disk can only be found
        // through the ids this database holds — see ArtworkLoader.forget.
        val covers = runCatching { db.libraryDao().allCoverArtIds() }.getOrDefault(emptyList())
        db.libraryDao().apply {
            clearTracks()
            clearAlbums()
            clearLikedArtists()
            clearDownloads()
            clearAllTopSongs()
        }
        downloads.deleteSource(source.id)
        artwork.forget(source.id, covers)
        backgroundPlaylistTracks.remove(source.id)
        backgroundPlaylistFailedAt.keys.removeAll { it.startsWith("${source.id}/") }
        forgetClient(source.id)
    }

    /**
     * The cover files the artwork budget must never evict: one per downloaded
     * song's album, across every source. These are the offline sleeves, and
     * they are tied to their songs — removing the downloads is what frees them,
     * not the budget.
     */
    private suspend fun protectedCoverFiles(): Set<String> =
        settings.sources.first().flatMap { source ->
            runCatching { databaseFor(source).libraryDao().downloadedCoverArtIds() }
                .getOrDefault(emptyList())
                .map { artwork.fileNameFor(source.id, it) }
        }.toSet()

    /**
     * Every cover file some source's library points at — what the cache's
     * monthly expiry must leave alone. See ArtworkLoader.expireUnclaimed.
     *
     * Null if any source's database couldn't be read: an unreadable library
     * looks exactly like an empty one from here, and the difference is every
     * cover it owns. Nothing is expired on a launch that can't tell.
     */
    private suspend fun claimedCoverFiles(): Set<String>? {
        val claimed = HashSet<String>()
        for (source in settings.sources.first()) {
            val ids = runCatching { databaseFor(source).libraryDao().allCoverArtIds() }.getOrNull() ?: return null
            ids.mapTo(claimed) { artwork.fileNameFor(source.id, it) }
        }
        return claimed
    }

    /** The cover sync in flight, if one is — see [syncCovers]. */
    private var coverSync: Job? = null

    /**
     * Fetch the covers the library on screen has and the phone doesn't, while
     * bytes are free.
     *
     * Lists draw only the covers already on the phone once the link costs
     * money, so this is what decides what a library looks like on cellular.
     * Run whenever there might be something to fetch and a free link to fetch
     * it on: after a sync, when the link becomes free, at launch. Finding
     * nothing missing costs a look at the disk and no request.
     *
     * Unmetered only, in every data mode. Make it Hurt spends cellular data
     * on what is asked for; this is asked for by nobody, and the covers it
     * would fetch still load as they are looked at.
     *
     * One at a time and replaced, not queued: a second trigger is either the
     * same library — and the disk already holds what the first run fetched —
     * or a different one, which the first run has just stopped being for.
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
                    android.util.Log.i(
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
     * Delete every source's downloaded audio, whichever source is active.
     *
     * The Offline page's Delete All row. The queue is dropped whole, and then
     * every source's index and folder is walked the way [forgetSource] does —
     * the downloader knows the queue, not what is already on the disk.
     */
    suspend fun deleteAllDownloads() {
        downloader.cancelAll()
        settings.sources.first().forEach { wipeDownloads(it) }
    }

    /**
     * Delete one source's downloaded audio — the per-server Delete on the
     * Offline page. Only that source's queue goes with it: every source's
     * downloads share one worker, and the others carry on.
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

    /**
     * Whether the browsed server is answering — see [Reachability], which finds
     * out by asking rather than by waiting for a sync to say so.
     */
    val reachability: Reachability by lazy {
        Reachability(scope, client = { serverClient.value }, mayAsk = { rules.value.mayTalkToServer })
    }
    val serverReachable: StateFlow<Boolean> get() = reachability.reachable

    /**
     * True is proof, and is taken as such. False is a *suspicion* — one stream
     * that died, one sync that failed — and is settled by asking the server,
     * at once, instead of being believed until the next sync.
     */
    fun reportServerReachable(reachable: Boolean) {
        if (reachable) reachability.report(true) else reachability.check(delayMs = 0L)
    }

    /**
     * What the connection and the mode allow right now — see [LinkRules]. The
     * one state the library, the queue, playback, downloads and artwork read,
     * so that a network or mode change reaches all of them in the same moment.
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
     * else can be played right now, whatever the reason — the mode's rule, no
     * link, or a server that isn't answering.
     */
    @OptIn(FlowPreview::class)
    val offlineOnly: Flow<Boolean> by lazy {
        // Two reasons, two speeds. The link or the mode forbidding streams is
        // a fact, and the lists follow it at once. The server not answering
        // is a *suspicion* until it has lasted: on poor reception one ping in
        // six seconds is missed all the time, and the library used to shrink
        // to downloads on that one miss and grow back on the next ping — a
        // flicker that reads as the app not knowing what it has. Playback
        // still reacts to the first miss (a track that can't be had parks or
        // is skipped); only the lists wait to see whether the silence holds.
        combine(
            rules.map { !it.mayTalkToServer }.distinctUntilChanged(),
            rules.map { !it.serverReachable }.distinctUntilChanged()
                .debounce { gone -> if (gone) SERVER_GONE_SETTLE_MS else 0L },
        ) { link, server -> link || server }.distinctUntilChanged()
    }

    /** How long the server must stay silent before the lists narrow to downloads. */
    private const val SERVER_GONE_SETTLE_MS = 20_000L

    /** The mode as it stands, for gates that must answer synchronously. */
    val dataMode: StateFlow<DataMode> by lazy {
        settings.dataMode.stateIn(scope, SharingStarted.Eagerly, DataMode.WIFI_ONLY)
    }

    /**
     * Whether the server may be spoken to at all — sync, playlists, lyrics.
     *
     * Wi-Fi Only's own words are "assume no usable connection", and a sync
     * alone is hundreds of requests: off an unmetered link it means none. The
     * other modes allow metadata anywhere; what they restrict is real bytes.
     */
    fun metadataAllowed(): Boolean = rules.value.mayTalkToServer

    /**
     * Step aside while the player is trying to start a stream.
     *
     * The player fetches on the same link as everything else, and on a slow
     * one a launch sync six requests wide leaves it a sixth of very little:
     * the song takes a minute to start, looks like nothing happened, gets
     * tapped again, and starts over. Background loops call this between
     * requests. Bounded, so a stream that never starts can't hold them for
     * ever — and free when nothing is starting, which is nearly always.
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
            log = { android.util.Log.i("AmpProxy", it) },
            // Less slack on a link that costs money: read-ahead spent on a
            // track that is then skipped is data spent on nothing.
            readAheadBytes = {
                if (Connectivity.isUnmetered()) StreamProxy.READ_AHEAD_BYTES else StreamProxy.READ_AHEAD_METERED_BYTES
            },
        )
    }
    /**
     * No more patient than the player is. It gives a connection eight seconds
     * and then tries again through the door; an upstream request still waiting
     * after that is one the player has already abandoned, and on a slow link
     * each retry would otherwise stack another stream — another transcode —
     * on top of the ones still trickling in.
     */
    private val proxyUpstream by lazy { NetworkGate.transferClient(connectTimeoutMs = 7_000L, readTimeoutMs = 8_000L) }

    /**
     * [rules], computed on the spot from a fresh look at the link.
     *
     * The flow is a few dispatch hops behind its inputs — it is combined on a
     * background thread — and a decision made in the same breath as the event
     * that changed them reads the old answer. Playback decides exactly then:
     * a stream has just been refused, or a ping has just failed, and the next
     * track to try is chosen on the spot. Two binder reads.
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
    }

    /**
     * The one rule every byte answers to: may the network be used right now?
     * False only in Wi-Fi Only on a metered link — or with no link at all.
     *
     * The same answer as [metadataAllowed], enforced rather than consulted.
     * That one is the courtesy: the early return that saves building work
     * which would only be refused. This one is the wall behind it — every
     * HTTP client is built through NetworkGate, the platform player asks
     * through the SDK's LightAudioNetworkPolicy, and the raw download and
     * discovery paths ask directly — so a path that never asked is refused
     * all the same. Before this, Wi-Fi Only was a set of courtesy checks,
     * and a stream already in the queue played over cellular.
     *
     * Asked of the system each time, not of the last callback: a phone at
     * the edge of Wi-Fi moves its default to cellular without either network
     * going away, and a report of that can arrive after the socket would.
     */
    fun networkAllowed(): Boolean =
        dataMode.value != DataMode.WIFI_ONLY || Connectivity.isUnmeteredNow()

    /**
     * Whether real bytes may move — downloads, cover art.
     *
     * Free on an unmetered link. On a metered one, only Make it Hurt, whose
     * name is the consent: everything passes through there, by design.
     */
    fun heavyDataAllowed(): Boolean = rules.value.mayMoveHeavyBytes

    private var coreReady = false
    val isReady: Boolean get() = coreReady

    const val SYNC_JOB_KEY = "library-sync"

    fun boot(sealedActivity: SealedLightActivity, context: SealedLightContext) {
        if (!coreReady) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            settings = AppSettings(context.dataStore)
            lightContext = context
            Connectivity.bind(context, scope)
            // The wall goes up before anything exists to be built behind it:
            // every client below is made through NetworkGate, and the player
            // — attached or the detached service, same process — asks the
            // same rule at its own connections.
            NetworkGate.policy = ::networkAllowed
            // The door the player streams through — see StreamProxy. Started
            // here, before anything builds a queue, and proven before it is
            // used: until its self-test passes, the player gets the server's
            // own URLs, which the SDK patch below still stands in front of.
            streamProxy.start(scope)
            NetworkGate.isMetered = { Connectivity.network.value.let { !it.isConnected || it.isMetered } }
            // DLNA is a Wi-Fi feature, full stop: no search, no cast, no probe
            // and no control call anywhere else. Asked of the system each time
            // — a cast is a handful of calls a second, not a stream of bytes.
            DlnaCast.allowsNetwork = { rulesNow().mayUseLan }
            LightAudioNetworkPolicy.allowsNetwork = ::networkAllowed
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
            // Whatever was last in use, resolved before anything reads the
            // library — the DAO has to exist before the repository is built.
            // The download budget became one for the whole tool; carry the
            // largest of the old per-source ones up to it.
            runBlocking { settings.migrateDownloadLimit() }
            val first = runBlocking { settings.activeSource.first() }
                ?: MusicSource(AppSettings.LEGACY_SOURCE_ID, SourceKind.SUBSONIC, "Server")
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
            )

            // A finished sync is proof the server is there, and the moment fresh
            // library data exists is also the right moment to top up downloads.
            library.onSyncSucceeded = {
                reportServerReachable(true)
                scope.launch {
                    // Fresh library rows are also the first chance to work out
                    // which album a reindexed download belongs to.
                    runCatching { dao().backfillDownloadAlbums() }
                    // ...and the first chance to put back the words a rebuilt
                    // download row lost, which the files themselves don't carry.
                    runCatching { downloader.refillMissingLyrics() }
                    topUpDownloads()
                }
                // And the covers of whatever the sync just brought in.
                syncCovers()
            }
            library.onCoversDropped = { sourceId, ids -> artwork.drop(sourceId, ids) }
            // A sync that worked is proof the server is answering, which is the
            // one moment worth retrying everything that happened while it wasn't.
            scope.launch { library.primePlaylists() }
            scope.launch {
                pending.refreshCount()
                serverReachable.collect { reachable ->
                    // That server's downloads stop dead when it goes, and pick
                    // up where they were when it comes back. Reachability is
                    // inferred from the browsed source's sync, so it is that
                    // source this says anything about — the others are judged
                    // by their own transfers.
                    downloader.setOffline(source.value.id, !reachable)
                    if (reachable) library.flushPending()
                }
            }
            // ...and again whenever there is both a server to tell and a
            // connection able to carry it.
            //
            // The line above cannot see this: reachability is inferred from a
            // sync succeeding, and it starts out true, so it never transitions
            // and never fires after boot. A like or a rating held back in
            // Wi-Fi Only therefore sat in the queue until something else
            // happened to talk to the server — up to half an hour after Wi-Fi
            // came back, with nothing on screen to say so.
            //
            // Both halves are needed. Watching the connection alone fires at
            // launch, before the client is built, and then never again because
            // nothing about the connection has changed; watching the client
            // alone misses Wi-Fi arriving later. Cheap when it is not the
            // moment — flushPending asks whether it may send before it sends.
            // One line per change of the rules, for the log: which of the
            // inputs moved and what it now allows. This is the first thing to
            // read when the library or the player did something unexpected.
            scope.launch {
                rules.collect { r ->
                    android.util.Log.i(
                        "AmpNet",
                        "rules: connected=${r.connected} unmetered=${r.unmetered} wifi=${r.wifi} " +
                            "mode=${r.mode} server=${r.serverReachable} → talk=${r.mayTalkToServer} " +
                            "stream=${r.mayStream} heavy=${r.mayMoveHeavyBytes} lan=${r.mayUseLan}",
                    )
                }
            }
            // Closing the wall is not only refusing new connections. A socket
            // opened over cellular in another mode stays usable for minutes,
            // and the rule answers for the phone's default network, not for
            // the network a socket is on — so entering Wi-Fi Only shuts every
            // connection there is, as does the rule reading closed.
            //
            // Only on those two edges, and only as much as each needs. Closing
            // shuts everything. *Entering* the mode shuts just what was opened
            // on a metered link: on Wi-Fi the rest is free, and shutting it
            // would fail the downloads and the sync in flight and tell the
            // reachability ping that a server which is plainly there has gone.
            // The rule *opening* shuts nothing — that is the moment everything
            // held back starts again.
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
            // Bulk work put off on a metered link — every playlist's contents,
            // and with them the playlist downloads and badges — is picked up
            // the moment the link stops costing money, not at the next launch.
            scope.launch {
                rules.map { it.mayMoveHeavyBytes }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { may -> if (may) topUpDownloads() }
            }
            // The covers a sync on cellular couldn't fetch, or a run that Wi-Fi
            // dropping cut short. Its own edge rather than the one above:
            // in Make it Hurt heavy bytes are always allowed, so that one never
            // moves, and the cover sync is for free bytes whatever the mode.
            scope.launch {
                rules.map { it.unmetered && it.mayStream }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { free -> if (free) syncCovers() }
            }
            // The permission to ask opening is a reason to ask, whatever opened
            // it. A network change is covered below; a *mode* change is not a
            // network change, and without this a server last seen silent on a
            // dying Wi-Fi stayed "gone" after switching to a mode that may use
            // the cellular link it is perfectly reachable on.
            scope.launch {
                rules.map { it.mayTalkToServer }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { may -> if (may) reachability.check(delayMs = 0L) }
            }
            // A change of link is the end of every connection: they all belong
            // to the network the phone just left. Dropped here, on the same
            // emission that tells the rest of the app — before the 400 ms the
            // reachability check waits, so its ping goes out on a fresh one.
            scope.launch {
                Connectivity.network
                    .map { Triple(it.isConnected, it.isMetered, it.isWifi) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        android.util.Log.i("AmpNet", "link changed → dropping every connection")
                        NetworkGate.linkChanged()
                    }
            }
            // Every change of connection is a reason to ask the server again:
            // a LAN address stops answering on cellular, and starts again at
            // home, and neither is something a sync should have to discover.
            scope.launch {
                Connectivity.network
                    .map { Triple(it.isConnected, it.isMetered, it.isWifi) }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { reachability.check() }
            }
            scope.launch {
                combine(serverClient, Connectivity.changed) { client, (connected, _) ->
                    client != null && connected
                }
                    .distinctUntilChanged()
                    .collect { ready -> if (ready) runCatching { library.flushPending() } }
            }
            scope.launch { downloader.setUserPaused(settings.downloadsPaused.first()) }
            // The colour workaround follows the artwork switch. See LightDisplayColor:
            // this is an off-SDK spike, and hiding artwork is what turns it off —
            // with no covers on screen there is nothing for colour to do. It used
            // to have a switch of its own ("Monochrome Artwork"); a sleeve you
            // chose to see is a sleeve you want in colour, so the switch went.
            scope.launch {
                settings.artwork.collect { LightDisplayColor.enabled = it != ArtworkMode.NONE }
            }
            // A refused login is not the server being gone. Marking it
            // unreachable narrows the library to downloads, which on a source
            // with none blanks a perfectly good cached library and explains
            // nothing — the credentials are stale, and the rows are still there
            // to browse until they are renewed.
            library.onSyncFailed = { authFailure -> if (!authFailure) reportServerReachable(false) }

            // The client is built by the source-switch collector below rather
            // than by a collector of its own. Three of them watched
            // activeSource — one for the client, one for the source and its
            // database, one to top up downloads — and nothing ordered them, so
            // the source could flip while the client and the tables still
            // belonged to the server being left. What ran in that gap held one
            // server's identity and another's music: see [swapSource].
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
            // Re-run when any source's mode changes, so switching one to e.g.
            // Favorites starts fetching without waiting for the next sync. Any
            // source, not the browsed one: the Offline page sets this per
            // server, and downloads run for all of them.
            scope.launch {
                settings.sources
                    .map { list -> list.map { it.id to it.offlineMode } }
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
            // The artwork budget, applied at launch — which is also what trims an
            // install from before there was one. Tracks are pointed at their
            // album's cover first, so the protected set is one file per
            // downloaded album rather than one per downloaded song, and the
            // per-song copies fall to the budget. See ArtworkLoader.trimToBudget.
            //
            // Before the budget, the expiry: what no library names and nobody
            // has looked at in a month goes whether or not there is room for
            // it, so the cache is a copy of the libraries and not a history of
            // them. After both, whatever the library has that the phone
            // doesn't is fetched, if the link is free — a launch inside the
            // sync floor has no sync to do that for it.
            scope.launch {
                settings.sources.first().forEach { source ->
                    runCatching { databaseFor(source).libraryDao().collapseTrackCovers() }
                        .onSuccess { if (it > 0) android.util.Log.i("AmpArt", "${source.name}: $it tracks now share their album's cover") }
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
                android.util.Log.i("AmpMp3", "one-time repair: indexed $n downloaded mp3(s) that had none")
            }
            // A source's downloads yield to its sync: both hit the same server,
            // and the sync is hundreds of small sequential requests that a
            // saturated transcoder turns into a crawl. Only the browsed source
            // syncs, so it is that one that waits; the others carry on.
            scope.launch {
                library.syncState.collect {
                    downloader.setSyncing(if (it.syncing) source.value.id else null)
                }
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
        scope.launch { settings.replayGain.collect { playback.replayGain.value = it } }
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

    /**
     * Which tag each list is narrowed to, as one value so the views below stay
     * inside `combine`'s five-flow form.
     *
     * Blank means "all of it", which is the absence of a filter rather than a
     * tag that happens to be named nothing.
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
     *
     * An album carries one genre of its own and no composer at all, so the
     * question is really about its songs: an album is a Bach album because Bach
     * wrote what is on it. Derived from the tracks for that reason, which also
     * guarantees every value the picker offers actually leads somewhere.
     */
    val albumsMatchingTags: StateFlow<Set<String>?> by lazy {
        combine(library.tracks, albumsTagFilter) { tracks, filter ->
            if (filter.isEmpty) return@combine null
            tracks.asSequence()
                .filter { filter.genre.isEmpty() || it.hasGenre(filter.genre) }
                .filter { filter.composer.isEmpty() || it.hasComposer(filter.composer) }
                .mapNotNull { it.albumId }
                .toSet()
        }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    val sortedSongs: StateFlow<SortedView<Track>> by lazy {
        combine(library.tracks, songSort, songSortReversed, likedSongsOnly, songsTagFilter) {
                list, sort, rev, liked, tags ->
            val narrowed = list.asSequence()
                .filter { !liked || it.liked }
                .filter { tags.genre.isEmpty() || it.hasGenre(tags.genre) }
                .filter { tags.composer.isEmpty() || it.hasComposer(tags.composer) }
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
     * Which shape the bottom bar takes — see [AppSettings.layoutMode].
     *
     * Eager, like its neighbours, because the bar is drawn on every screen: read
     * as a plain preference Flow it would show the standard bar for a frame on
     * the way to every page, on a phone set to the simplified one.
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
     * [AppSettings.hideDownloadIcons]. Hot and eager for the same reason as
     * it decides a measurement, not a decoration.
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

    /**
     * Change over to [active]: the client, the database, then the source.
     *
     * In that order, and in one place, because the order is the whole point.
     * The source is what everything else keys on — the download top-up, the
     * screens, anything asking "which server is this" — so it changes *last*,
     * once the client and the tables it implies are already the new one's.
     *
     * Split across three collectors of the same flow, as this was, nothing
     * ordered them: the source flipped first and the rest caught up over the
     * next few hundred milliseconds. Work that ran in the gap held one
     * server's identity and another's music, and asked the new server for the
     * old one's ids — a download top-up queued sixteen thousand Navidrome
     * tracks against Plex, and cover art went to the wrong server entirely.
     * Nothing downstream can defend itself against that; it has to not happen.
     */
    private suspend fun swapSource(active: MusicSource?) {
        val next = active ?: return
        if (next.id == _source.value.id) {
            // The same source — either unchanged, or changed in place by a
            // renewed token or a new address. The client is keyed on the whole
            // record, so a change rebuilds it; the database is the same one and
            // the library does not move.
            //
            // `== null` is not belt and braces: at boot, [boot] sets _source to
            // the stored source *before* this collector starts, so its first
            // emission is this branch with nothing changed. Without this the
            // client was never built at all until the user happened to switch
            // sources — which left playback failing with "Source error",
            // downloads fetching "via null", and everything the mode gates
            // looking innocent because there was nothing to send with.
            if (next != _source.value || _serverClient.value == null) {
                _serverClient.value = clientFor(next)
            }
            _source.value = next
            return
        }
        // The playing queue belongs to the source being left and doesn't
        // survive the change: the stream URLs already handed to the player are
        // signed for that server. Left alone, playback fails on the next
        // track, the failure trips the offline fallback, and the app spends a
        // while looking broken before a reachability check lets it recover.
        //
        // The download queue, deliberately, does survive. Every entry in it
        // names its own source and is fetched from that source whichever one
        // is on screen — selecting a source is a browsing choice, not a
        // download scope. It used to be cleared here, which threw away an
        // hour's worth of queue on every switch, and it was cleared because a
        // queued track *didn't* know its source and the worker assumed the
        // browsed one; see Downloader.
        playback.stop()
        // A sync for the source being left has nothing to tell us, and its
        // failure would be reported against the new one. Its hold on that
        // source's downloads goes with it.
        library.cancelSync()
        downloader.setSyncing(null)
        // Popular songs, the playlists, the search index and the artist ids are
        // all keyed by name rather than by source, so they answer for the wrong
        // server until they're dropped. Cleared *before* the source changes
        // over, so nothing that recomposes on the new source can catch the old
        // data.
        library.forgetDerived()
        // So are the liked switches and the genre and composer filters: they
        // narrow *this* library's lists, and a genre chosen on one server means
        // nothing on the next. Left in place, "Ambient" from Plex applied to a
        // Navidrome that spells its genres differently emptied every list, the
        // page said nothing about being narrowed, and the row to clear it hid
        // itself because the new library had no such tag to offer.
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
    }

    /**
     * One top-up pass at a time, and a burst of requests is one more pass, not
     * one each.
     *
     * Three triggers ask for a top-up — a sync finishing, a source's mode
     * changing, the browsed library's size changing — and a source switch fires
     * two of them within a second. Each pass reads every source's tables and
     * asks a background server for its playlists, so with the requests simply
     * queued behind a lock, a minute of switching between servers on the
     * device became three minutes of back-to-back passes doing the same work.
     * A request that arrives mid-pass now just marks the pass as needing to run
     * again; whatever it would have found, the rerun finds.
     */
    private val topUpRunning = Mutex()
    private val topUpRequested = AtomicBoolean(false)

    /**
     * How long a pass waits after being asked for, before it starts.
     *
     * The triggers fire at the moments the screen is busiest — a source switch
     * blanks every list and refills it from the database, and a pass that
     * started in the same instant read every source's whole tables on the same
     * database thread the refill was queued behind. Background work goes after
     * the screen. The wait also folds a burst of triggers into one pass.
     */
    private const val TOP_UP_SETTLE_MS = 3_000L

    /**
     * How long a playlist that would not answer is left alone before being
     * asked again — a fetch that just failed is not improved by repeating it on
     * the next pass, and one that failed for want of memory took the heap to
     * its ceiling each time it was tried.
     */
    private const val PLAYLIST_RETRY_MS = 30 * 60 * 1000L

    /** When a background source's playlist last failed to answer, by "source/playlist". */
    private val backgroundPlaylistFailedAt = ConcurrentHashMap<String, Long>()

    /**
     * Playlist membership for the sources not being browsed, by source id, for
     * the life of the process.
     *
     * The repository knows the browsed source's playlists and shares them with
     * the Playlists tab; a source in the background has no repository, so its
     * membership is asked of its own server here and remembered. The list of
     * playlists once per session, like the repository's own cache, and each
     * playlist's songs once they have answered — null until then, so one that
     * fails is asked again alone on the next pass rather than taking every
     * other playlist's fetch with it. That was the nine seconds every pass
     * spent on Plex: one playlist not answering meant none were remembered. A
     * playlist changed on a server you are not looking at is picked up at the
     * next launch, or the next time you look. Dropped with the source — see
     * [forgetSource].
     */
    private val backgroundPlaylistTracks = ConcurrentHashMap<String, MutableMap<String, List<String>?>>()

    /**
     * Queue whatever each source's offline mode wants that isn't downloaded yet.
     *
     * Every source with a mode set, not the one being browsed. Downloads run
     * for all of them at once, and the Offline page sets the mode per server —
     * so "Everything" on Plex means Plex's library arrives while you listen to
     * Navidrome or to the phone's own files, and "Manual" on Navidrome means
     * Navidrome fetches only what you asked for by hand, whichever is on screen.
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
            // Nothing to download on the phone's own music: the files are
            // already here, put there by Light's own transfer, and this app
            // has no business fetching anything on their behalf.
            if (!source.supportsDownloads || source.offlineMode == OfflineMode.MANUAL) continue
            runCatching { topUpDownloads(source) }
                .onFailure { android.util.Log.w("AmpDl", "top-up failed for ${source.name}", it) }
        }
    }

    private suspend fun topUpDownloads(source: MusicSource) {
        val startedMs = System.currentTimeMillis()
        // This source's own tables, read once. Not the repository's lists —
        // those follow the browsed source, and a top-up that read them for
        // another source would hold one server's identity and another's music.
        val dao = databaseFor(source).libraryDao()
        // Only ever download from the libraries kept on the Sources page.
        //
        // Which libraries this covers is that page's "Shown on" setting, not
        // one of its own: downloads used to have a library setting that
        // restated a choice already made a level up and could disagree with
        // it. Hiding a library there says you are not interested in it;
        // fetching it anyway, in the background, onto a phone with a size
        // limit, is not a reading of that. Not the browsed library either —
        // what to *keep* is a wider question than what to look at right now.
        val hidden = source.hiddenLibraryIds.filterNotNull().toSet()
        val albums = dao.allAlbumsSnapshot()
            .filter { hidden.isEmpty() || it.libraryId == null || it.libraryId !in hidden }
        val albumIds = albums.mapTo(HashSet()) { it.id }
        val tracks = dao.allTracksSnapshot()
            .asSequence()
            .filter { hidden.isEmpty() || it.albumId == null || it.albumId in albumIds }
            .map { it.toTrack() }
            .toList()
        if (tracks.isEmpty()) return
        // Playlists count in both modes — Favorites promises them outright, and
        // Everything fetches them first — so what they hold has to be known
        // here, not just on the Playlists tab. No server sends membership with
        // the list, so each playlist's songs are asked for, once.
        val playlistTrackIds = playlistTrackIdsFor(source)
        downloader.applyAutoMode(
            source = source,
            allTracks = tracks,
            likedTracks = tracks.filter { it.liked },
            // Scoped as `tracks` above is: a liked record in a hidden library
            // is not fetched, and one in a library not being browsed is.
            likedAlbumIds = albums.filter { it.liked }.mapTo(HashSet()) { it.id },
            playlistTracks = tracks.filter { it.id in playlistTrackIds },
            // Starred on the server, whatever is on screen — not derived from
            // the artists list, which narrows with the offline view.
            likedArtistNames = dao.likedArtistNames().toSet(),
        )
        // One line per source per pass — the cost of a pass is the thing worth
        // knowing about this, and the playlists are where it goes.
        android.util.Log.i(
            "AmpDl",
            "top-up ${source.name} (${source.offlineMode}): ${tracks.size} rows, " +
                "${playlistTrackIds.size} in playlists, ${System.currentTimeMillis() - startedMs} ms",
        )
    }

    /** Every track id in any of [source]'s playlists, as far as they can be known. */
    private suspend fun playlistTrackIdsFor(source: MusicSource): Set<String> {
        if (source.id == _source.value.id) {
            // The browsed source: the repository's cache, shared with the
            // Playlists tab. The list is fetched if it hasn't been yet, then
            // each playlist's songs, once — reading Playlist.trackIds, always
            // empty for a server playlist, had both modes quietly downloading
            // no playlist at all.
            if (library.playlists.value.isEmpty()) runCatching { library.refreshPlaylists() }
            runCatching { library.primePlaylistTrackIds(library.playlists.value.map { it.id }) }
            return library.playlistTrackIds.value.values.flatten().toHashSet()
        }
        val client = clientFor(source) ?: return emptySet()
        // The list, once. Metadata, so the metadata gate — the same one a
        // sync waits on.
        val known = backgroundPlaylistTracks[source.id] ?: run {
            if (!metadataAllowed()) return emptySet()
            val playlists = runCatching { client.getPlaylists(source.libraryId) }.getOrNull()
                ?: return emptySet()
            HashMap<String, List<String>?>()
                .also { map -> playlists.forEach { map[it.id] = null } }
                .also { backgroundPlaylistTracks[source.id] = it }
        }
        // Then whichever playlists haven't answered yet — leaving alone, for a
        // while, the ones that were asked and failed. One request per playlist
        // is unavoidable; running them one after another is not. Same width as
        // the repository gives the sync.
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
                            id to runCatching { client.getPlaylistTracks(id) }.getOrNull()?.map { it.id }
                        }
                    }
                }.awaitAll()
            }
            // A failed fetch stays null: cached as "empty" it would stand for
            // the rest of the session. It is dated, so the next pass skips it.
            fetched.forEach { (id, ids) ->
                if (ids != null) known[id] = ids else backgroundPlaylistFailedAt["${source.id}/$id"] = now
            }
        }
        return known.values.flatMapTo(HashSet()) { it.orEmpty() }
    }

    private const val PLAYLIST_PRIME_CONCURRENCY = 6

    private const val DB_NAME = "amp-library.db"
}

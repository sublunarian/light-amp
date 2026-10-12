package com.sublunar.amp.data

import com.sublunar.amp.App
import com.sublunar.amp.data.db.DownloadEntity
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.toTrack
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.transfer.LightTransferService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

data class DownloadProgress(
    val pending: Int = 0,
    val completed: Int = 0,
    val currentTitle: String? = null,
    /**
     * Which server the current track is coming from, by name — for the
     * Downloads page, where with several servers queued the title alone
     * doesn't say whose music is moving.
     */
    val currentSource: String? = null,
    /** Set when the size limit stopped the queue rather than it finishing. */
    val limitReached: Boolean = false,
    val error: String? = null,
) {
    val active: Boolean get() = pending > 0
}

/**
 * Downloads tracks for offline playback and keeps the download index in step with
 * the files on disk.
 *
 * **Every source at once.** The queue holds tracks from every configured server,
 * each tagged with the source it came from, and the worker resolves the client,
 * the tables, the folder and the format *per track* from that tag. Which source
 * is being browsed is a browsing choice and has no bearing here: a Plex library
 * set to download everything keeps arriving while you listen to Navidrome or to
 * the phone's own files. What holds downloads back is the data mode, the user's
 * own pause, and — per source, never for everyone — a server that has stopped
 * answering or a library mid-sync. See [DownloadQueue] for the order.
 *
 * One worker at a time, deliberately — and measured, not assumed. Navidrome
 * transcodes on demand, and that encode is the ceiling: against the real library a
 * single stream and three concurrent streams both delivered **0.5 MB/s in total**,
 * except that with three nothing finished inside 92s while one worker completed
 * four tracks. Concurrency buys no bandwidth here and costs completion latency, so
 * don't reach for it again without re-measuring the aggregate. (Bursts of ~4 MB/s
 * do happen — those are tracks already sitting in the server's transcode cache.)
 * That measurement was one server; two servers would be two transcoders, but
 * also one phone's downlink, so the same rule stands until someone measures it.
 */
class Downloader(
    /**
     * The source being browsed — for the calls the library screens make about
     * the tracks on them: [enqueue] without a source, [remove], [cachedLyrics].
     * The worker never reads it. Every queued track names its own source, and
     * resolving anything about a transfer through "the active source" is
     * exactly the mistake that sent Navidrome ids to Plex.
     */
    private val activeSource: () -> MusicSource,
    /** A source's tables — App.databaseFor. */
    private val daoFor: (MusicSource) -> LibraryDao,
    /** A source's client — App.clientFor. Null for the phone's own music. */
    private val clientFor: (MusicSource) -> MusicServer?,
    private val store: DownloadStore,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    /** Used only to hold the process at foreground priority while draining. */
    private val lightContext: SealedLightContext,
    /**
     * Whether real bytes may move right now — see App.heavyDataAllowed.
     *
     * Checked between tracks, like every other pause: the queue keeps its
     * order and simply waits. This is the gate that was missing when a queue
     * built at home drained over cellular the moment the server still
     * answered — reachability was the only thing ever consulted.
     */
    private val heavyDataAllowed: () -> Boolean,
    /**
     * What a held queue is waiting for, in the screens' words — Wi-Fi in the
     * modes that keep heavy bytes off cellular, a connection in the one that
     * doesn't. See LinkRules.heavyWaitingFor.
     */
    private val waitingLabel: () -> String = { NetworkGate.WAITING_FOR_WIFI },
    /** Suspends while the player is starting a stream — see App.yieldToPlayback. */
    private val yieldToPlayback: suspend () -> Unit = {},
) {
    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    /** Everything waiting, every source's — guarded by [lock]. */
    private val queue = DownloadQueue()
    private val lock = Mutex()
    private var worker: Job? = null

    /**
     * The entry being transferred right now, and one that a [cancelSource]
     * has disowned mid-transfer. The transfer itself is blocking I/O and isn't
     * interrupted; instead, whatever it produces is thrown away when it ends,
     * and it is neither recorded nor requeued. Cheaper than cancelling the
     * worker, which would also drop the foreground service out from under the
     * other sources' downloads.
     */
    @Volatile
    private var inFlight: DownloadQueue.Key? = null

    @Volatile
    private var abandoned: DownloadQueue.Key? = null

    /**
     * The source whose library is syncing, if one is.
     *
     * A sync is hundreds of sequential `getAlbum` calls, and the same server is
     * transcoding every download on demand — so letting both run means the sync
     * queues behind ffmpeg and crawls. Downloads are the interruptible half of
     * that pair, so *that server's* yield. The others have nothing to do with it
     * and carry on. Only the browsed source ever syncs, so one id is enough.
     */
    @Volatile
    private var syncingSourceId: String? = null

    /** Set from the Downloads page and remembered across launches. */
    @Volatile
    private var userPaused = false

    /** When to try each source again after its server stopped answering — see [SourceBackOff]. */
    private val backOffs = SourceBackOff()

    /**
     * Sources whose server refused a download outright — 401, the login; 403,
     * not for this account — with the record they were refused under.
     *
     * Not an outage, so not backed off: retrying a minute later only asks the
     * same question, and a queue did exactly that for ever, flashing a song's
     * title every few seconds under "Waiting for the server". The source's
     * downloads wait instead, the Downloads page says why, and the refusal
     * lapses when the record changes — signed in again, or a sync learning the
     * account may download after all — or when a download is asked for by hand.
     */
    private val refusals = ConcurrentHashMap<String, Pair<Int, MusicSource>>()
    private val _refused = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Which sources' downloads wait on a refusal, by status — for the Downloads page. */
    val refused: StateFlow<Map<String, Int>> = _refused

    private fun refuse(source: MusicSource, status: Int) {
        refusals[source.id] = status to source
        _refused.value = refusals.mapValues { it.value.first }
    }

    private fun forgive(sourceId: String) {
        if (refusals.remove(sourceId) != null) _refused.value = refusals.mapValues { it.value.first }
    }

    /** With the queue gone there is nothing left to refuse, and a row saying so would be about nothing. */
    private fun forgiveAll() = refusals.keys.toList().forEach { forgive(it) }

    /** Whether [source]'s downloads still wait on a refusal made under the record it has now. */
    private fun stillRefused(source: MusicSource): Boolean {
        val (_, refusedAs) = refusals[source.id] ?: return false
        if (refusedAs == source) return true
        forgive(source.id)
        return false
    }

    /**
     * Sources whose server has stopped answering for long enough that the
     * worker stopped asking — see [SourceBackOff.PARK_AFTER_FAILURES] — with
     * the last error and the record they were parked under.
     *
     * The back-off alone retried for ever: a server down all afternoon kept
     * the worker polling once a second and the transfer service up the
     * whole time, which spends the six hours a day Android allows a
     * `dataSync` service, so the downloads after the outage ran throttled.
     * Parked, the source's entries stay queued, the worker ends like it does
     * for a refusal, and the Downloads page says why.
     *
     * Never on one failure, and never for good: a download asked for by
     * hand, a tap on that row, the browsed server answering or syncing, the
     * record changing, or a ping answered on a change of link, a mode
     * opening, the app coming back to the front or the half-hourly job —
     * see [wakeParked] — each puts it back in line.
     */
    private val parkings = ConcurrentHashMap<String, Pair<String?, MusicSource>>()
    private val _parked = MutableStateFlow<Map<String, String?>>(emptyMap())

    /** Sources whose downloads wait because their server stopped answering, with the last error — for the Downloads page. */
    val parked: StateFlow<Map<String, String?>> = _parked

    private fun park(source: MusicSource, error: String?) {
        parkings[source.id] = error to source
        _parked.value = parkings.mapValues { it.value.first }
        android.util.Log.i("AmpDl", "parked ${source.name} after ${SourceBackOff.PARK_AFTER_FAILURES} failures (${error ?: "no reason"})")
    }

    /** Clears the back-off either way; true when the source was parked. */
    private fun unpark(sourceId: String): Boolean {
        clearBackOff(sourceId)
        if (parkings.remove(sourceId) == null) return false
        _parked.value = parkings.mapValues { it.value.first }
        return true
    }

    private fun unparkAll() = parkings.keys.toList().forEach { unpark(it) }

    /** Whether [source]'s downloads are still parked under the record it has now. */
    private fun stillParked(source: MusicSource): Boolean {
        val (_, parkedAs) = parkings[source.id] ?: return false
        if (parkedAs == source) return true
        unpark(source.id)
        return false
    }

    /** Sources nothing in the queue can be fetched from until something about them changes. */
    private fun heldUntilChanged(sources: List<MusicSource>): Set<String> =
        sources.filter { !it.downloadFormatAvailable || stillRefused(it) || stillParked(it) }.map { it.id }.toSet()

    private fun waiting(sourceId: String): Boolean = backOffs.waiting(sourceId, System.currentTimeMillis())

    /** Whether a source's tracks may be picked right now. */
    private fun eligible(sourceId: String): Boolean =
        sourceId != syncingSourceId && !waiting(sourceId)

    /** A failed try; true once the source has failed long enough to park. */
    private fun backOff(sourceId: String): Boolean = backOffs.fail(sourceId, System.currentTimeMillis())

    private fun clearBackOff(sourceId: String) = backOffs.clear(sourceId)

    /**
     * The pauses that hold everyone: the user's, and the data mode's. See
     * [drain] for why the worker ends on them rather than waiting them out.
     */
    private fun heldForEveryone(): Boolean = userPaused || !heavyDataAllowed()

    /**
     * What the Downloads page shows for a queue held for everyone, with no
     * worker left to say it: still queued, and what it waits for.
     */
    private fun showHeld(pending: Int) {
        _progress.value = _progress.value.copy(
            pending = pending,
            currentTitle = if (userPaused) "Paused" else waitingLabel(),
            currentSource = null,
        )
    }

    // --- Public API ----------------------------------------------------------

    /**
     * Queue [tracks] from the source being browsed — what the library screens
     * call, about the rows on them.
     */
    fun enqueue(tracks: List<Track>, manual: Boolean = true) =
        enqueue(activeSource(), tracks, manual)

    /**
     * Queue [tracks] of [source] for download.
     *
     * [manual] is what the user asked for by name — an album, a playlist, a
     * selection — and jumps ahead of anything an offline mode queued. Only
     * [applyAutoMode] passes false.
     */
    fun enqueue(source: MusicSource, tracks: List<Track>, manual: Boolean = true) {
        if (tracks.isEmpty()) return
        // Nothing to fetch for the phone's own music: the files are already
        // here, and this app has no business asking anyone for them.
        if (!source.mayDownload) return
        // Asking for something by hand is also asking to try now: whatever the
        // last failure decided about waiting, the person tapping Download has
        // better information about whether the server is up than we do.
        if (manual) {
            unpark(source.id)
            forgive(source.id)
        }
        scope.launch {
            // One query for the whole set. Asking per track meant an automatic mode
            // over a ten-thousand-track library fired ten thousand point selects,
            // and it did so while holding the lock.
            val already = daoFor(source).downloadedIds().toHashSet()
            lock.withLock {
                tracks.forEach { track ->
                    if (track.id in already) return@forEach
                    // A file on this phone is not something to fetch a copy of.
                    // Belt as well as braces — the UI hides the action, and
                    // App.topUpDownloads never offers these — because a stray
                    // enqueue here would ask the server for an id it has never
                    // heard of, once per track.
                    if (LocalLibrary.isLocal(track.id)) return@forEach
                    queue.add(QueuedDownload(source.id, track), manual)
                }
                _progress.value = _progress.value.copy(
                    pending = queue.size + (if (inFlight != null) 1 else 0),
                    limitReached = false,
                    error = null,
                )
                // Started while holding the lock: checking liveness from two
                // enqueue coroutines otherwise lets both top the pool up and the
                // same track gets fetched twice.
                startWorkerLocked()
            }
        }
    }

    /**
     * Hold [sourceId]'s downloads while its library syncs; null when no sync
     * is running. See [syncingSourceId].
     */
    fun setSyncing(sourceId: String?) {
        syncingSourceId = sourceId
    }

    /**
     * Hold one source's downloads while its server is unreachable.
     *
     * Without this the worker walks the whole queue at full speed, failing every
     * track in turn — which empties a ten-thousand-track queue into the error
     * counter in a few seconds and leaves nothing to resume.
     */
    fun setOffline(sourceId: String, value: Boolean) {
        // A reachability signal is a hint, not a verdict: it schedules the next
        // attempt, and a success clears it. See SourceBackOff. Only a real
        // transfer failing parks a source, so the count it returns is not
        // this signal's business.
        if (value) backOff(sourceId) else tryNow(sourceId)
    }

    /** Something says [sourceId]'s server is there — it answered, or someone asked by hand: try it now. */
    fun tryNow(sourceId: String) {
        if (unpark(sourceId)) retry()
    }

    /**
     * The user's own pause, from the Downloads page. Survives a restart.
     *
     * A worker either way: resuming starts the queue again, and pausing one
     * that no worker is holding — see [drain] — still has to change what
     * the Downloads page says about it.
     */
    fun setUserPaused(value: Boolean) {
        userPaused = value
        scope.launch { settings.setDownloadsPaused(value) }
        scope.launch { lock.withLock { startWorkerLocked() } }
    }

    /**
     * The rule that holds everyone's downloads moved — heavy data allowed
     * again, or waiting for something else. A queue held by it has no worker
     * (see [drain]), so this is what starts one: to move the queue, or to
     * find it still held and leave the Downloads page saying what for. Any
     * entry will do, held by its source or not, because a worker that finds
     * nothing it may fetch ends before it starts the transfer service.
     */
    fun holdChanged() {
        scope.launch { lock.withLock { if (!queue.isEmpty()) startWorkerLocked() } }
    }

    fun cancelAll() {
        scope.launch {
            lock.withLock {
                queue.clear()
                abandoned = null
            }
            unparkAll()
            forgiveAll()
            worker?.cancel()
            worker = null
            _progress.value = DownloadProgress()
        }
    }

    /**
     * Drop everything waiting for one source — its Delete Downloads, or the
     * source itself going. The other sources' downloads are not touched, and
     * a transfer of this source's already under way is disowned rather than
     * interrupted: see [abandoned].
     */
    suspend fun cancelSource(sourceId: String) {
        lock.withLock {
            queue.removeSource(sourceId)
            // Nothing of its source's is left to refuse or to wait for.
            unpark(sourceId)
            forgive(sourceId)
            val current = inFlight
            if (current?.sourceId == sourceId) abandoned = current
            _progress.value = if (queue.isEmpty() && current == null) {
                DownloadProgress(completed = _progress.value.completed)
            } else {
                _progress.value.copy(pending = queue.size + (if (current != null) 1 else 0))
            }
            // With no worker running, whatever is left is held: for everyone,
            // which the page goes on saying, or until something about its
            // source changes — a worker ends on exactly that — so nothing is
            // moving and the count above would show a "Downloading" row for
            // it. Idle is the truth.
            if (worker?.isActive != true) {
                if (!queue.isEmpty() && heldForEveryone()) {
                    showHeld(queue.size)
                } else {
                    _progress.value = DownloadProgress(completed = _progress.value.completed)
                }
            }
        }
    }

    suspend fun remove(trackId: String) {
        val source = activeSource()
        val dao = daoFor(source)
        dao.download(trackId)?.let { store.delete(source.id, it.fileName) }
        dao.deleteDownload(trackId)
    }

    suspend fun removeAll(trackIds: List<String>) = trackIds.forEach { remove(it) }

    /** Lyrics captured with a download, for offline display. */
    suspend fun cachedLyrics(trackId: String): String? =
        daoFor(activeSource()).download(trackId)?.lyrics

    /**
     * Re-index audio that is on disk but missing from the database, for every
     * source.
     *
     * Room drops all tables on a schema bump (the SDK can't register migrations),
     * which would otherwise make the app re-download everything it already has.
     * Files are the durable artefact; the index is derived from them.
     */
    suspend fun reindexFromDisk() {
        settings.sources.first()
            .filter { it.supportsDownloads }
            .forEach { source -> runCatching { reindexFromDisk(source) } }
    }

    private suspend fun reindexFromDisk(source: MusicSource) {
        val dao = daoFor(source)
        val known = dao.downloadedIds().toHashSet()
        val missing = store.onDisk(source.id).filter { (id, _) -> id !in known }
        if (missing.isEmpty()) return
        // The file name carries only the track id, so the album comes from the
        // library — which on a rebuilt cache is usually empty at this point.
        // [LibraryDao.backfillDownloadAlbums] finishes the job after the sync.
        val albums = dao.tracksByIds(missing.map { it.first })
            .associate { it.id to it.albumId }
        for ((id, format) in missing) {
            val file = store.fileFor(source.id, id, format)
            if (!file.isFile) continue
            dao.upsertDownload(
                DownloadEntity(
                    trackId = id,
                    albumId = albums[id],
                    fileName = file.name,
                    format = format.id,
                    bytes = file.length(),
                    lyrics = null,
                    downloadedAtMs = file.lastModified(),
                ),
            )
        }
    }

    /**
     * Fetch the words for the browsed source's downloads that have none.
     *
     * [reindexFromDisk] can rebuild a download row from the file on disk, but the
     * lyrics were only ever in the database — so a schema bump leaves an offline
     * track with its audio and no words, and nothing else would ever fetch them:
     * the track counts as downloaded, so it is never queued again. This is the
     * one part of a wiped table the disk cannot answer for.
     *
     * A track whose words are nowhere stores a blank rather than staying null, so
     * it is asked about once and not on every launch afterwards. Blank parses to
     * nothing downstream, which is what an absent lyric already did — see
     * LyricsRepository.resolve.
     *
     * Capped per run: a large offline library would otherwise be thousands of
     * requests in one go, and there is no hurry — what is left comes back on the
     * next sync. Runs after a sync, and only the browsed source syncs, so it is
     * that source's downloads it repairs.
     */
    suspend fun refillMissingLyrics() {
        val source = activeSource()
        val client = clientFor(source) ?: return
        val dao = daoFor(source)
        val ids = dao.downloadsMissingLyrics(LYRICS_REFILL_PER_RUN)
        if (ids.isEmpty()) return
        val wantKaraoke = settings.karaokeLyrics.first()
        val tracks = dao.tracksByIds(ids).associateBy { it.id }
        for (id in ids) {
            val track = tracks[id]?.toTrack() ?: continue
            val row = dao.download(id) ?: continue
            val words = runCatching { fetchLyrics(client, track, wantKaraoke) }.getOrNull()
            dao.upsertDownload(row.copy(lyrics = words.orEmpty()))
        }
    }

    /**
     * Queue whatever [source]'s [OfflineMode] implies. Safe to call repeatedly —
     * already-downloaded tracks are skipped, so this just tops up.
     */
    suspend fun applyAutoMode(
        source: MusicSource,
        allTracks: List<Track>,
        likedTracks: List<Track>,
        likedAlbumIds: Set<String>,
        playlistTracks: List<Track>,
        likedArtistNames: Set<String>,
    ) {
        // Order is priority: the queue drains front to back and the size limit cuts
        // it off wherever it runs out, so whatever matters most has to come first.
        val favourites = likedTracks +
            playlistTracks +
            allTracks.filter { it.albumId in likedAlbumIds } +
            allTracks.filter {
                it.albumArtistNames().any { name -> name in likedArtistNames }
            }
        val wanted = when (source.offlineMode) {
            OfflineMode.MANUAL -> return
            OfflineMode.FAVORITES -> favourites
            // Everything, but favourites first so they are never the tracks that
            // lose out when the budget runs dry.
            OfflineMode.ALL -> favourites + allTracks
        }
        enqueue(source, wanted.distinctBy { it.id }, manual = false)
    }

    private companion object {
        const val PAUSE_POLL_MS = 1_000L

        /** How many lyric refills one pass will attempt — see refillMissingLyrics. */
        private const val LYRICS_REFILL_PER_RUN = 200

        /**
         * How long the transfer service is kept after it was started, at the
         * least: the five seconds Android gives a started service to reach the
         * foreground — see LightTransferService and [drain].
         */
        private const val SERVICE_SETTLE_MS = 5_000L
    }

    // --- Worker --------------------------------------------------------------

    /**
     * Picks the queue up again after something that held it changed — a
     * download quality the server couldn't meet, chosen again, or a parked
     * server answering. A queue with nothing it can move ends the worker
     * straight away, without ever starting the foreground service.
     */
    fun retry() {
        scope.launch {
            // Only when something queued isn't held by its own source: a
            // worker for a queue it could never touch would start only to find
            // that out and end.
            val blocked = heldUntilChanged(settings.sources.first())
            lock.withLock {
                if (queue.sourceIds().any { it !in blocked }) startWorkerLocked()
            }
        }
    }

    private val probing = Mutex()

    /**
     * Ask each parked server once whether it is back, and put the ones that
     * answer back in line — see [parkings]. No worker and no transfer
     * service unless one does.
     *
     * Asked the way the browsed server is asked, by [Reachability]: a 5xx is
     * a proxy saying the thing behind it is down, so no; a refused login is
     * the server answering, so yes, and the transfer that follows meets the
     * refusal and says so.
     */
    suspend fun wakeParked() {
        if (parkings.isEmpty() || !heavyDataAllowed()) return
        if (!probing.tryLock()) return
        try {
            val sources = settings.sources.first()
            var woke = false
            for (id in parkings.keys.toList()) {
                val source = sources.firstOrNull { it.id == id }
                if (source == null) {
                    // Removed since it was parked; nothing is left to wake.
                    unpark(id)
                    continue
                }
                val client = clientFor(source) ?: continue
                val answered = Reachability(scope, client = { client }, mayAsk = heavyDataAllowed).verify()
                android.util.Log.i("AmpDl", "parked ${source.name}: ping → ${answered ?: "no verdict"}")
                if (answered == true && unpark(id)) woke = true
            }
            if (woke) retry()
        } finally {
            probing.unlock()
        }
    }

    /**
     * Asked for a worker while one was still running. Mid-queue that changes
     * nothing — its next pass sees whatever the asking was about — but one
     * that has already decided to end does not look again, and a resume or
     * an answer arriving in that moment was lost: the queue sat until
     * something else came along. So a worker that was asked for looks once
     * more after it has gone, with its service already stopped.
     */
    @Volatile
    private var askedWhileRunning = false

    /** Must be called with [lock] held. */
    private fun startWorkerLocked() {
        if (worker?.isActive == true) {
            askedWhileRunning = true
            return
        }
        askedWhileRunning = false
        val job = scope.launch {
            try {
                drain()
            } catch (_: CancellationException) {
                // Cancelled by cancelAll(); progress was already reset there.
            } finally {
                inFlight = null
                // Runs on cancellation too, so the phone never keeps a foreground
                // service alive for a queue that has stopped.
                LightTransferService.stop(lightContext)
            }
        }
        // Not after a cancel: cancelAll has just emptied the queue.
        job.invokeOnCompletion { cause ->
            if (cause == null && askedWhileRunning) scope.launch { lock.withLock { startWorkerLocked() } }
        }
        worker = job
    }

    /**
     * Lyrics to store beside a downloaded track.
     *
     * With karaoke on, timed lyrics are the point — so when the server only has
     * plain text, lrclib is asked as well, exactly as the player does when
     * online. Without it, whatever the server has is enough and the extra
     * request isn't worth making on every track in the library.
     */
    private suspend fun fetchLyrics(
        client: MusicServer,
        track: Track,
        wantKaraoke: Boolean,
    ): String? {
        val fromServer = runCatching { client.getLyrics(track.id) }.getOrNull()
        val best = if (wantKaraoke && fromServer?.synced != true) {
            runCatching { LyricsRepository.timedFromLrclib(track) }.getOrNull() ?: fromServer
        } else {
            fromServer
        }
        return best?.lines?.joinToString("\n") { line ->
            if (line.timeMs != null) "[${line.timeMs}]${line.text}" else line.text
        }
    }

    /** Why nothing is moving although something is queued — see [eligible]. */
    private fun heldReason(sourceIds: Set<String>): String =
        if (sourceIds.isNotEmpty() && sourceIds.all { waiting(it) }) {
            "Waiting for the server"
        } else {
            "Paused while syncing"
        }

    private suspend fun drain() {
        // Held around actual transfers rather than around the worker: starting the
        // service for a queue that turns out to be empty means starting and
        // stopping it within milliseconds, and Android kills the process when a
        // foreground service is torn down before it ever reached the foreground.
        var serviceSinceMs: Long? = null
        // For the same reason, a worker that ends a moment after its first
        // transfer began — refused at once, or cut off by the rule it is about
        // to end on — keeps the service until it has had those seconds.
        suspend fun letServiceSettle() {
            serviceSinceMs?.let { delay(SERVICE_SETTLE_MS - (System.currentTimeMillis() - it)) }
        }
        val wantKaraoke = settings.karaokeLyrics.first()
        // One budget for the tool, not one per source — see AppSettings.downloadLimit.
        val limit = settings.downloadLimit.first()
        var completed = 0

        while (true) {
            // The pauses that hold everyone: the user's, and the data mode's.
            // Checked between tracks, so a sync starting mid-file lets that file
            // finish rather than abandoning the bytes already fetched.
            //
            // The worker ends on them and the entries stay queued. It used to
            // wait them out, polling, and once a transfer had run the service
            // stayed up for as long as the wait lasted — a Wi-Fi rule can hold
            // a queue all day, and that spends the hours a day Android allows
            // the service. Resuming starts it again (setUserPaused), and so
            // does the rule moving (holdChanged), which App hears from the
            // link's callback, on the way back to the front, and from the
            // half-hourly job: the moments this loop used to re-read the link
            // for itself in case the callback missed one.
            if (heldForEveryone()) {
                val waiting = lock.withLock { queue.size }
                if (waiting == 0) break
                // Time spent not trying is not time spent failing: a run of
                // failures from before the wait must not park a server on the
                // first one after it.
                backOffs.clearAll()
                showHeld(waiting)
                letServiceSettle()
                return
            }
            // A transfer is the greediest thing on the link; a stream being
            // started waits for nobody else — see App.yieldToPlayback.
            yieldToPlayback()

            // Manual lane first, always, of whichever source can be asked right
            // now — see [DownloadQueue.next]. Which lane it came from travels
            // with it, so a re-queue goes back where it belongs.
            // A source whose download quality its server can't meet — an
            // Original where it has no `download` call — is never picked: its
            // entries wait in the queue rather than being fetched as something
            // else. Read every pass, so changing the quality frees them.
            // And one whose server refused the last download: see [refusals].
            // And one parked after its server stopped answering: see [parkings].
            val blocked = heldUntilChanged(settings.sources.first())
            val (picked, held) = lock.withLock {
                val next = queue.next { eligible(it) && it !in blocked }
                next to (if (next == null) queue.sourceIds() else emptySet())
            }
            if (picked == null) {
                if (held.isEmpty()) break
                // Only sources waiting on a quality change, a refusal, or a
                // server parked after ten minutes of failures are left.
                // Nothing here can end that wait, so the worker ends instead —
                // which is what lets the foreground service go — and the
                // entries stay queued. Whatever ends the wait starts it again;
                // see [retry]. The Downloads page says why.
                if (held.all { it in blocked }) break
                // Something is queued, and every source it belongs to is held:
                // on its server to answer again, or for its sync to finish.
                _progress.value = _progress.value.copy(
                    currentTitle = heldReason(held - blocked),
                    currentSource = null,
                )
                delay(PAUSE_POLL_MS)
                continue
            }
            val entry = picked.entry
            val next = entry.track

            // The record as it stands now, not as it was when queued: a token
            // renewed or an address changed since then is the one to use.
            val source = settings.sources.first().firstOrNull { it.id == entry.sourceId }
            if (source == null) {
                // Removed since it was queued; the entry goes with it.
                continue
            }

            // Re-checked every track: the budget moves as files land, and the
            // user can lower the limit while a queue is running.
            //
            // Measured across every source, because the budget covers all of
            // them. Against this source's table alone, two sources could each
            // fill to the limit and take twice it between them — which is the
            // arithmetic that made the limit worth moving out of the sources in
            // the first place. Read off the disk for the same reason: a table
            // can only ever answer for its own source.
            if (store.usedBytesEverywhere() >= limit) {
                lock.withLock { queue.clear() }
                // A row saying a server isn't answering, or refused, would be
                // about entries that are no longer there.
                unparkAll()
                forgiveAll()
                _progress.value = _progress.value.copy(
                    pending = 0,
                    currentTitle = null,
                    currentSource = null,
                    limitReached = true,
                )
                letServiceSettle()
                return
            }

            _progress.value = _progress.value.copy(
                pending = lock.withLock { queue.size } + 1,
                completed = completed,
                currentTitle = next.title,
                currentSource = source.name,
            )

            val client = clientFor(source)
            if (client == null) {
                // Nothing to ask — a record with no server behind it. Back to
                // the front of its own lane, and its source waits its turn like
                // one that isn't answering, so the others aren't held up by it.
                _progress.value = _progress.value.copy(error = "Not connected")
                lock.withLock { queue.requeueFront(picked) }
                if (backOff(source.id)) park(source, "Not connected")
                continue
            }

            // This source's own choice, not the browsed one's: the downloads
            // belong to that library, and a server that can only serve one
            // format shouldn't dictate what the others are fetched as.
            val format = source.downloadFormat
            // Changed to a quality the server can't meet since this pass began:
            // back in the queue untouched, and the next pass leaves it there.
            if (!source.downloadFormatAvailable) {
                lock.withLock { queue.requeueFront(picked) }
                continue
            }
            val dao = daoFor(source)

            if (serviceSinceMs == null) {
                // Without this the process drops to the cached bucket the moment the
                // user leaves the app and transfers are throttled about ninefold —
                // see LightTransferService for the measurements.
                LightTransferService.start(lightContext, "Syncing your library for offline")
                serviceSinceMs = System.currentTimeMillis()
            }
            inFlight = entry.key
            val file = try {
                store.download(source.id, client.downloadUrl(next, format), next.id, format)
            } finally {
                inFlight = null
            }

            // Disowned while it was transferring — its source's downloads were
            // deleted, or the source itself was. Whatever landed goes; nothing
            // is recorded, nothing requeued, no strike, no back-off.
            val disowned = lock.withLock {
                (abandoned == entry.key).also { if (it) abandoned = null }
            }
            if (disowned) {
                file?.delete()
                continue
            }

            if (file != null) {
                // An mp3 that came down as a stream has no index, and every
                // player that opens it has to guess its length — see
                // Mp3VbrIndex. Written now, before anything records the size.
                if (format == StreamFormat.MP3) {
                    runCatching { Mp3VbrIndex.index(file) }
                        .onSuccess { if (it is Mp3VbrIndex.Outcome.Written) android.util.Log.i("AmpMp3", "indexed ${file.name}: ${it.frames} frames") }
                        .onFailure { android.util.Log.w("AmpMp3", "couldn't index ${file.name}", it) }
                }
                // The sleeve is part of having the record offline — this
                // source's sleeve, from this source's server.
                runCatching { App.artwork.prefetch(source.id, client, next.coverArtId) }
                // Always: the words are a few kilobytes beside a song, and an
                // offline track without them is the one place they cannot be
                // fetched on demand.
                val lyrics = fetchLyrics(client, next, wantKaraoke)
                dao.upsertDownload(
                    DownloadEntity(
                        trackId = next.id,
                        albumId = next.albumId,
                        fileName = file.name,
                        format = format.id,
                        bytes = file.length(),
                        lyrics = lyrics,
                        downloadedAtMs = System.currentTimeMillis(),
                    ),
                )
                completed++
                clearBackOff(source.id)
                if (_progress.value.error != null) _progress.value = _progress.value.copy(error = null)
                lock.withLock { queue.complete(entry) }
            } else {
                // A cancelled worker must not put the track back: cancelAll has
                // just emptied the queue it would go into.
                coroutineContext.ensureActive()
                val status = store.lastStatus
                if (status == 401 || status == 403) {
                    lock.withLock { queue.requeueFront(picked) }
                    refuse(source, status)
                    continue
                }
                // A failed transfer is the server's problem more often than the
                // track's: hold *this source's* queue and let the next
                // reachability check start it again, rather than failing all ten
                // thousand in a row. Where the track goes back to — front, or
                // back after enough strikes — is the queue's rule; see
                // [DownloadQueue.fail]. Failing for long enough parks the
                // source instead: see [parkings].
                lock.withLock { queue.fail(picked) }
                val error = store.lastError
                _progress.value = _progress.value.copy(error = error)
                if (backOff(source.id)) park(source, error)
                continue
            }
        }

        _progress.value = DownloadProgress(completed = completed)
        letServiceSettle()
    }
}

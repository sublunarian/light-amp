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

    /**
     * When to try each source again after its server stopped answering — not
     * *whether* to.
     *
     * This used to be a boolean latched on the first failed transfer and
     * cleared only by a successful library sync. A single blip therefore parked
     * downloads indefinitely: the queue sat on "Waiting for the server" on
     * perfectly good wifi, and nothing about downloading could get it going
     * again, because only syncing could clear the flag. A deadline retries by
     * itself and needs nobody's permission.
     *
     * Per source, because a server that is down is one server: its tracks wait,
     * everyone else's are picked instead — see [eligible].
     */
    private val retryAtBySource = ConcurrentHashMap<String, Long>()

    /** Consecutive failed transfers per source, for how long to wait before the next try. */
    private val failuresBySource = ConcurrentHashMap<String, Int>()

    private fun waiting(sourceId: String): Boolean =
        System.currentTimeMillis() < (retryAtBySource[sourceId] ?: 0L)

    /** Whether a source's tracks may be picked right now. */
    private fun eligible(sourceId: String): Boolean =
        sourceId != syncingSourceId && !waiting(sourceId)

    /** Backs off to a minute, so a server that is really down isn't hammered. */
    private fun backOff(sourceId: String) {
        val failures = (failuresBySource[sourceId] ?: 0) + 1
        failuresBySource[sourceId] = failures
        val wait = (RETRY_BASE_MS * (1L shl (failures - 1).coerceAtMost(6)))
            .coerceAtMost(RETRY_MAX_MS)
        retryAtBySource[sourceId] = System.currentTimeMillis() + wait
    }

    /** A transfer that worked proves the server is there, whatever else said. */
    private fun clearBackOff(sourceId: String) {
        failuresBySource.remove(sourceId)
        retryAtBySource.remove(sourceId)
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
        if (!source.supportsDownloads) return
        // Asking for something by hand is also asking to try now: whatever the
        // last failure decided about waiting, the person tapping Download has
        // better information about whether the server is up than we do.
        if (manual) clearBackOff(source.id)
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
        // attempt, and a success clears it. See retryAtBySource.
        if (value) backOff(sourceId) else clearBackOff(sourceId)
    }

    /** The user's own pause, from the Downloads page. Survives a restart. */
    fun setUserPaused(value: Boolean) {
        userPaused = value
        scope.launch { settings.setDownloadsPaused(value) }
        if (!value) scope.launch { lock.withLock { startWorkerLocked() } }
    }

    fun cancelAll() {
        scope.launch {
            lock.withLock {
                queue.clear()
                abandoned = null
            }
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
            val current = inFlight
            if (current?.sourceId == sourceId) abandoned = current
            _progress.value = if (queue.isEmpty() && current == null) {
                DownloadProgress(completed = _progress.value.completed)
            } else {
                _progress.value.copy(pending = queue.size + (if (current != null) 1 else 0))
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

        /** First retry after a failed transfer; doubles up to [RETRY_MAX_MS]. */
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
    }

    // --- Worker --------------------------------------------------------------

    /** Must be called with [lock] held. */
    private fun startWorkerLocked() {
        if (worker?.isActive == true) return
        worker = scope.launch {
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
        var holdingService = false
        val wantKaraoke = settings.karaokeLyrics.first()
        // One budget for the tool, not one per source — see AppSettings.downloadLimit.
        val limit = settings.downloadLimit.first()
        var completed = 0

        while (true) {
            // The pauses that hold everyone: the user's, and the data mode's.
            // Checked between tracks, so a sync starting mid-file lets that file
            // finish rather than abandoning the bytes already fetched.
            while (userPaused || !heavyDataAllowed()) {
                _progress.value = _progress.value.copy(
                    currentTitle = if (userPaused) "Paused" else NetworkGate.WAITING_FOR_WIFI,
                    currentSource = null,
                )
                delay(PAUSE_POLL_MS)
            }

            // Manual lane first, always, of whichever source can be asked right
            // now — see [DownloadQueue.next]. Which lane it came from travels
            // with it, so a re-queue goes back where it belongs.
            val (picked, held) = lock.withLock {
                val next = queue.next(::eligible)
                next to (if (next == null) queue.sourceIds() else emptySet())
            }
            if (picked == null) {
                if (held.isEmpty()) break
                // Something is queued, and every source it belongs to is held:
                // on its server to answer again, or for its sync to finish.
                _progress.value = _progress.value.copy(
                    currentTitle = heldReason(held),
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
                _progress.value = _progress.value.copy(
                    pending = 0,
                    currentTitle = null,
                    currentSource = null,
                    limitReached = true,
                )
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
                backOff(source.id)
                continue
            }

            // This source's own choice, not the browsed one's: the downloads
            // belong to that library, and a server that can only serve one
            // format shouldn't dictate what the others are fetched as.
            val format = source.downloadFormat
            val dao = daoFor(source)

            if (!holdingService) {
                // Without this the process drops to the cached bucket the moment the
                // user leaves the app and transfers are throttled about ninefold —
                // see LightTransferService for the measurements.
                LightTransferService.start(lightContext, "Syncing your library for offline")
                holdingService = true
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
                lock.withLock { queue.complete(entry) }
            } else {
                // A cancelled worker must not put the track back: cancelAll has
                // just emptied the queue it would go into.
                coroutineContext.ensureActive()
                // A failed transfer is the server's problem more often than the
                // track's: hold *this source's* queue and let the next
                // reachability check start it again, rather than failing all ten
                // thousand in a row. Where the track goes back to — front, or
                // back after enough strikes — is the queue's rule; see
                // [DownloadQueue.fail].
                lock.withLock { queue.fail(picked) }
                _progress.value = _progress.value.copy(error = store.lastError)
                backOff(source.id)
                continue
            }
        }

        _progress.value = DownloadProgress(completed = completed)
    }
}

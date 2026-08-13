package com.sublunar.amp.data

import com.sublunar.amp.App
import com.sublunar.amp.data.db.DownloadEntity
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.transfer.LightTransferService
import com.sublunar.amp.data.db.LibraryDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DownloadProgress(
    val pending: Int = 0,
    val completed: Int = 0,
    val currentTitle: String? = null,
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
 * One worker at a time, deliberately — and measured, not assumed. Navidrome
 * transcodes on demand, and that encode is the ceiling: against the real library a
 * single stream and three concurrent streams both delivered **0.5 MB/s in total**,
 * except that with three nothing finished inside 92s while one worker completed
 * four tracks. Concurrency buys no bandwidth here and costs completion latency, so
 * don't reach for it again without re-measuring the aggregate. (Bursts of ~4 MB/s
 * do happen — those are tracks already sitting in the server's transcode cache.) The queue is a [LinkedHashMap] so re-queuing a track already waiting
 * doesn't duplicate it, and the user's manual picks stay in the order they asked.
 */
class Downloader(
    /** Resolved per call: the active source's database, see App.dao. */
    private val daoProvider: () -> LibraryDao,
    private val store: DownloadStore,
    private val serverClient: StateFlow<MusicServer?>,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    /** Used only to hold the process at foreground priority while draining. */
    private val lightContext: SealedLightContext,
) {
    private val dao: LibraryDao get() = daoProvider()

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    /**
     * Two lanes, drained in order: what the user asked for, then what a mode
     * decided to fetch on their behalf.
     *
     * Without the split, tapping Download on an album while "Download everything"
     * was working through a ten-thousand-track library put that album ten
     * thousand tracks back — the one download the user was actually waiting for
     * was the last one they'd get. A manual pick also *promotes* a track already
     * waiting in the automatic lane rather than queueing it twice.
     */
    private val manualQueue = LinkedHashMap<String, Track>()
    private val autoQueue = LinkedHashMap<String, Track>()

    private val queuedCount: Int get() = manualQueue.size + autoQueue.size
    private val lock = Mutex()
    private var worker: Job? = null

    /**
     * Throw away everything waiting, because it belongs to another server.
     *
     * The queues hold tracks, and a track's id only means something to the source
     * it came from. Left in place across a source switch, the worker carries on
     * asking the *new* server for the old one's ids — which fails once per track,
     * and on Plex fails against a library that has never heard of them.
     */
    suspend fun clearQueue() {
        lock.withLock {
            manualQueue.clear()
            autoQueue.clear()
            _progress.value = _progress.value.copy(pending = 0, currentTitle = null)
        }
    }

    /**
     * Set while the library is syncing.
     *
     * A sync is hundreds of sequential `getAlbum` calls, and the same server is
     * transcoding every download on demand — so letting both run means the sync
     * queues behind ffmpeg and crawls. Downloads are the interruptible half of
     * that pair, so they yield.
     */
    @Volatile
    private var syncing = false

    /** Set from the Downloads page and remembered across launches. */
    @Volatile
    private var userPaused = false

    /**
     * When to try again after the server stopped answering — not *whether* to.
     *
     * This used to be a boolean latched on the first failed transfer and
     * cleared only by a successful library sync. A single blip therefore parked
     * downloads indefinitely: the queue sat on "Waiting for the server" on
     * perfectly good wifi, and nothing about downloading could get it going
     * again, because only syncing could clear the flag. A deadline retries by
     * itself and needs nobody's permission.
     */
    @Volatile
    private var retryAtMs = 0L

    /** Consecutive failed transfers, for how long to wait before the next try. */
    @Volatile
    private var failures = 0

    private val waiting: Boolean get() = System.currentTimeMillis() < retryAtMs

    private val paused: Boolean get() = syncing || userPaused || waiting

    private fun pauseReason(): String = when {
        userPaused -> "Paused"
        waiting -> "Waiting for the server"
        else -> "Paused while syncing"
    }

    /** Backs off to a minute, so a server that is really down isn't hammered. */
    private fun backOff() {
        failures++
        val wait = (RETRY_BASE_MS * (1L shl (failures - 1).coerceAtMost(6)))
            .coerceAtMost(RETRY_MAX_MS)
        retryAtMs = System.currentTimeMillis() + wait
    }

    /** A transfer that worked proves the server is there, whatever else said. */
    private fun clearBackOff() {
        failures = 0
        retryAtMs = 0L
    }

    // --- Public API ----------------------------------------------------------

    /**
     * Queue [tracks] for download.
     *
     * [manual] is what the user asked for by name — an album, a playlist, a
     * selection — and jumps ahead of anything an offline mode queued. Only
     * [applyAutoMode] passes false.
     */
    fun enqueue(tracks: List<Track>, manual: Boolean = true) {
        if (tracks.isEmpty()) return
        // Asking for something by hand is also asking to try now: whatever the
        // last failure decided about waiting, the person tapping Download has
        // better information about whether the server is up than we do.
        if (manual) clearBackOff()
        scope.launch {
            // One query for the whole set. Asking per track meant an automatic mode
            // over a ten-thousand-track library fired ten thousand point selects,
            // and it did so while holding the lock.
            val already = dao.downloadedIds().toHashSet()
            lock.withLock {
                tracks.forEach { track ->
                    if (track.id in already) return@forEach
                    // A file on this phone is not something to fetch a copy of.
                    // Belt as well as braces — the UI hides the action, and
                    // App.topUpDownloads never offers these — because a stray
                    // enqueue here would ask the server for an id it has never
                    // heard of, once per track.
                    if (LocalLibrary.isLocal(track.id)) return@forEach
                    if (manual) {
                        autoQueue.remove(track.id)
                        manualQueue[track.id] = track
                    } else if (track.id !in manualQueue) {
                        autoQueue[track.id] = track
                    }
                }
                _progress.value = _progress.value.copy(
                    pending = queuedCount,
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

    /** Hold downloads while something more time-critical uses the server. */
    fun setSyncing(value: Boolean) {
        syncing = value
    }

    /**
     * Hold downloads while the server is unreachable.
     *
     * Without this the worker walks the whole queue at full speed, failing every
     * track in turn — which empties a ten-thousand-track queue into the error
     * counter in a few seconds and leaves nothing to resume.
     */
    fun setOffline(value: Boolean) {
        // A reachability signal is a hint, not a verdict: it schedules the next
        // attempt, and a success clears it. See retryAtMs.
        if (value) backOff() else clearBackOff()
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
                manualQueue.clear()
                autoQueue.clear()
            }
            worker?.cancel()
            worker = null
            _progress.value = DownloadProgress()
        }
    }

    suspend fun remove(trackId: String) {
        dao.download(trackId)?.let { store.delete(it.fileName) }
        dao.deleteDownload(trackId)
    }

    suspend fun removeAll(trackIds: List<String>) = trackIds.forEach { remove(it) }

    suspend fun deleteEverything() {
        cancelAll()
        dao.clearDownloads()
        store.deleteAll()
    }

    /** Lyrics captured with a download, for offline display. */
    suspend fun cachedLyrics(trackId: String): String? = dao.download(trackId)?.lyrics

    /**
     * Re-index audio that is on disk but missing from the database.
     *
     * Room drops all tables on a schema bump (the SDK can't register migrations),
     * which would otherwise make the app re-download everything it already has.
     * Files are the durable artefact; the index is derived from them.
     */
    suspend fun reindexFromDisk() {
        val known = dao.downloadedIds().toHashSet()
        val missing = store.onDisk().filter { (id, _) -> id !in known }
        if (missing.isEmpty()) return
        // The file name carries only the track id, so the album comes from the
        // library — which on a rebuilt cache is usually empty at this point.
        // [LibraryDao.backfillDownloadAlbums] finishes the job after the sync.
        val albums = dao.tracksByIds(missing.map { it.first })
            .associate { it.id to it.albumId }
        for ((id, format) in missing) {
            val file = store.fileFor(id, format)
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
     * Queue whatever the current [OfflineMode] implies. Safe to call repeatedly —
     * already-downloaded tracks are skipped, so this just tops up.
     */
    suspend fun applyAutoMode(
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
                it.albumArtist.ifBlank { it.artist } in likedArtistNames
            }
        val wanted = when (settings.activeSource.first()?.offlineMode ?: OfflineMode.MANUAL) {
            OfflineMode.MANUAL -> return
            OfflineMode.FAVORITES -> favourites
            // Everything, but favourites first so they are never the tracks that
            // lose out when the budget runs dry.
            OfflineMode.ALL -> favourites + allTracks
        }
        enqueue(wanted.distinctBy { it.id }, manual = false)
    }

    private companion object {
        const val PAUSE_POLL_MS = 1_000L

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

    private suspend fun drain() {
        // Held around actual transfers rather than around the worker: starting the
        // service for a queue that turns out to be empty means starting and
        // stopping it within milliseconds, and Android kills the process when a
        // foreground service is torn down before it ever reached the foreground.
        var holdingService = false
        // The active source's own choices, not app-wide ones: the downloads
        // belong to that library, a server that can only serve one format
        // shouldn't dictate what the others are fetched as, and a size budget
        // was in any case only ever being weighed against one source's usage.
        val source = settings.activeSource.first()
        val format = source?.downloadFormat ?: StreamFormat.DEFAULT
        val wantLyrics = source?.wantsLyrics ?: true
        val wantKaraoke = settings.karaokeLyrics.first()
        val limit = source?.downloadLimit ?: AppSettings.DEFAULT_DOWNLOAD_LIMIT
        var completed = 0

        while (true) {
            // Checked between tracks, so a sync starting mid-file lets that file
            // finish rather than abandoning the bytes already fetched.
            while (paused) {
                _progress.value = _progress.value.copy(currentTitle = pauseReason())
                delay(PAUSE_POLL_MS)
            }

            // Manual lane first, always: see [manualQueue]. Which lane it came
            // from travels with it, so a re-queue goes back where it belongs.
            val (next, wasManual) = lock.withLock {
                val lane = if (manualQueue.isNotEmpty()) manualQueue else autoQueue
                lane.entries.firstOrNull()
                    ?.also { lane.remove(it.key) }
                    ?.value
                    ?.to(lane === manualQueue)
            } ?: break

            // Re-checked every track: the budget moves as files land, and the
            // user can lower the limit while a queue is running.
            if (dao.downloadedBytes() >= limit) {
                lock.withLock {
                    manualQueue.clear()
                    autoQueue.clear()
                }
                _progress.value = _progress.value.copy(
                    pending = 0,
                    currentTitle = null,
                    limitReached = true,
                )
                return
            }

            _progress.value = _progress.value.copy(
                pending = lock.withLock { queuedCount } + 1,
                completed = completed,
                currentTitle = next.title,
            )

            val client = serverClient.value
            if (client == null) {
                _progress.value = _progress.value.copy(error = "Not connected")
                // Put it back: the queue is the record of what still has to be
                // fetched, and dropping it because the Wi-Fi blinked would mean
                // rebuilding it from scratch.
                // Back to the front of its own lane, not the back: an outage
                // shouldn't cost a track the place it had earned.
                lock.withLock {
                    val lane = if (wasManual) manualQueue else autoQueue
                    val rest = LinkedHashMap(lane)
                    lane.clear()
                    lane[next.id] = next
                    lane.putAll(rest)
                }
                return
            }

            if (!holdingService) {
                // Without this the process drops to the cached bucket the moment the
                // user leaves the app and transfers are throttled about ninefold —
                // see LightTransferService for the measurements.
                LightTransferService.start(lightContext, "Syncing your library for offline")
                holdingService = true
            }
            val file = store.download(
                client.streamUrl(next, format, estimateContentLength = false),
                next.id,
                format,
            )
            if (file != null) {
                // The sleeve is part of having the record offline.
                runCatching { App.artwork.prefetch(next.coverArtId) }
                val lyrics = if (wantLyrics) fetchLyrics(client, next, wantKaraoke) else null
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
                clearBackOff()
            } else {
                _progress.value = _progress.value.copy(error = store.lastError)
                // A failed transfer is the server's problem more often than the
                // track's: hold the queue and let the next reachability check
                // start it again, rather than failing all ten thousand in a row.
                // Back to the front of its own lane, not the back: an outage
                // shouldn't cost a track the place it had earned.
                lock.withLock {
                    val lane = if (wasManual) manualQueue else autoQueue
                    val rest = LinkedHashMap(lane)
                    lane.clear()
                    lane[next.id] = next
                    lane.putAll(rest)
                }
                backOff()
                continue
            }
        }

        _progress.value = DownloadProgress(completed = completed)
    }
}

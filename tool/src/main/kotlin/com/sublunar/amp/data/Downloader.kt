package com.sublunar.amp.data

import android.util.Log
import com.sublunar.amp.App
import com.sublunar.amp.data.db.DownloadEntity
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.MAX_BOUND_IDS
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.transfer.LightTransferService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
 * One worker at a time, deliberately, and measured: a server transcoding on
 * demand is the ceiling, and three concurrent streams delivered the same total
 * as one while finishing nothing sooner. Don't reach for concurrency again
 * without re-measuring the aggregate.
 */
class Downloader(
    /**
     * The source being browsed — for the calls the library screens make about
     * the tracks on them: [enqueue] without a source, [remove], [cachedLyrics].
     * The worker never reads it: every queued track names its own source, and
     * resolving a transfer through "the active source" sends one server's ids
     * to another.
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
     * Checked between tracks, like every other pause: the queue keeps its
     * order and simply waits.
     */
    private val heavyDataAllowed: () -> Boolean,
    /**
     * What a held queue is waiting for, in the screens' words — Wi-Fi in the
     * modes that keep heavy bytes off cellular, a connection in the one that
     * doesn't. See LinkRules.heavyWaitingFor.
     */
    private val waitingLabel: () -> String,
    /** Suspends while the player is starting a stream — see App.yieldToPlayback. */
    private val yieldToPlayback: suspend () -> Unit,
    /** Whether the server may be spoken to at all — see App.metadataAllowed. */
    private val metadataAllowed: () -> Boolean,
    /**
     * Every track id in the browsed source's playlists, from the repository's
     * cache, which the Playlists tab shares — see [playlistTrackIdsFor].
     */
    private val browsedPlaylistTrackIds: suspend () -> Set<String>,
) {
    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress

    /** Everything waiting, every source's — guarded by [lock]. */
    private val queue = DownloadQueue()
    private val lock = Mutex()
    private var worker: Job? = null

    /**
     * The entry being worked on, from the moment it is picked, and one that a
     * [cancelSource] has disowned meanwhile. A transfer is blocking I/O and
     * isn't interrupted; instead, whatever it produces is thrown away when it
     * ends, and it is neither recorded nor requeued — see [settle]. Cheaper
     * than cancelling the worker, which would also drop the foreground service
     * out from under the other sources' downloads.
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
     * Why a source's downloads wait until something about it changes, and the
     * record they were held under: a different record lifts the hold — see
     * [stillHeld].
     */
    sealed interface Hold {
        val heldAs: MusicSource

        /**
         * The server refused a download outright — 401, the login; 403, not
         * for this account. Not an outage, so not backed off: retrying only
         * asks the same question again, for ever. It lapses when the record
         * changes — signed in again, or a sync learning the account may
         * download after all — or when a download is asked for by hand.
         */
        data class Refused(val status: Int, override val heldAs: MusicSource) : Hold

        /**
         * The server stopped answering for long enough that the worker stopped
         * asking — see [SourceBackOff.PARK_AFTER_FAILURES] — with the last error.
         *
         * The back-off alone retries for ever, keeping the transfer service up
         * through a long outage and spending the six hours a day Android allows
         * a `dataSync` service. Parked, the source's entries stay queued and the
         * worker ends, as it does for a refusal.
         *
         * Never on one failure, and never for good: a download asked for by
         * hand, a tap on that row, the browsed server answering or syncing, the
         * record changing, or a ping answered on a change of link, a mode
         * opening, the app coming back to the front or the half-hourly job —
         * see [wakeParked] — each puts it back in line.
         */
        data class Parked(val error: String?, override val heldAs: MusicSource) : Hold
    }

    private val _holds = MutableStateFlow<Map<String, Hold>>(emptyMap())

    /** Which sources' downloads wait on a [Hold], and why — for the Downloads page. */
    val holds: StateFlow<Map<String, Hold>> = _holds

    private fun refuse(source: MusicSource, status: Int) {
        _holds.update { it + (source.id to Hold.Refused(status, source)) }
    }

    private fun park(source: MusicSource, error: String?) {
        _holds.update { it + (source.id to Hold.Parked(error, source)) }
        Log.i("AmpDl", "parked ${source.name} after ${SourceBackOff.PARK_AFTER_FAILURES} failures (${error ?: "no reason"})")
    }

    /** Clears the back-off either way; true when the source was parked. */
    private fun unpark(sourceId: String): Boolean {
        clearBackOff(sourceId)
        var wasParked = false
        _holds.update { holds ->
            wasParked = holds[sourceId] is Hold.Parked
            if (wasParked) holds - sourceId else holds
        }
        return wasParked
    }

    /** Whatever holds [sourceId]'s downloads goes, and its back-off with it. */
    private fun lift(sourceId: String) {
        clearBackOff(sourceId)
        _holds.update { it - sourceId }
    }

    /** With the queue gone there is nothing left to hold, and a row saying so would be about nothing. */
    private fun liftAll() {
        _holds.getAndUpdate { emptyMap() }.forEach { (id, hold) -> if (hold is Hold.Parked) clearBackOff(id) }
    }

    private fun parkedIds(): List<String> = _holds.value.filterValues { it is Hold.Parked }.keys.toList()

    /** Whether [source]'s downloads are still held under the record it has now. */
    private fun stillHeld(source: MusicSource): Boolean {
        val hold = _holds.value[source.id] ?: return false
        if (hold.heldAs == source) return true
        if (hold is Hold.Parked) unpark(source.id) else _holds.update { it - source.id }
        return false
    }

    /** Sources nothing in the queue can be fetched from until something about them changes. */
    private fun heldUntilChanged(sources: List<MusicSource>): Set<String> =
        sources.filter { !it.downloadFormatAvailable || stillHeld(it) }.map { it.id }.toSet()

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
    fun enqueue(tracks: List<Track>) = enqueue(activeSource(), tracks)

    /**
     * Queue [tracks] of [source] for download, as asked for by name — an album,
     * a playlist, a selection — which jumps ahead of anything an offline mode
     * queued. Only [applyAutoMode] queues anything else, and it queues ids.
     */
    fun enqueue(source: MusicSource, tracks: List<Track>) =
        queueUp(source, tracks.map { QueuedDownload(source.id, it) }, manual = true)

    private fun queueUp(source: MusicSource, entries: List<QueuedDownload>, manual: Boolean) {
        if (entries.isEmpty()) return
        // Nothing to fetch for the phone's own music: the files are already
        // here, and this app has no business asking anyone for them.
        if (!source.mayDownload) return
        // Asking for something by hand is also asking to try now: whatever the
        // last failure decided about waiting, the person tapping Download has
        // better information about whether the server is up than we do.
        if (manual) lift(source.id)
        scope.launch {
            // One query for the whole set, outside the lock: an automatic mode
            // over a large library would otherwise be a point select per track.
            val already = daoFor(source).downloadedIds().toHashSet()
            lock.withLock {
                entries.forEach { entry ->
                    if (entry.trackId in already) return@forEach
                    // A file on this phone is not something to fetch a copy of.
                    // Belt as well as braces — the UI hides the action, and
                    // the top-up never offers these — because a stray
                    // enqueue here would ask the server for an id it has never
                    // heard of, once per track.
                    if (PhoneFiles.isPhoneFile(entry.trackId)) return@forEach
                    queue.add(entry, manual)
                }
                _progress.value = _progress.value.copy(
                    pending = queue.size + (if (inFlight != null) 1 else 0),
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
     * Hold one source's downloads while its server is unreachable, rather than
     * let the worker fail every track of the queue in turn at full speed.
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
        scope.launch { settings.downloadsPaused.set(value) }
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

    /**
     * Drop the whole queue and stop the worker, in one hold of [lock]: an
     * enqueue landing in between would find the worker about to be cancelled,
     * ask it to look again, and be left with nobody to fetch it.
     */
    fun cancelAll() {
        scope.launch {
            lock.withLock {
                queue.clear()
                abandoned = null
                liftAll()
                worker?.cancel()
                worker = null
                _progress.value = DownloadProgress()
            }
        }
    }

    /** A source removed: its queue goes, and what was learnt of its playlists. */
    suspend fun forget(sourceId: String) {
        cancelSource(sourceId)
        backgroundPlaylistTracks.remove(sourceId)
        backgroundPlaylistFailedAt.keys.removeAll { it.startsWith("$sourceId/") }
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
            lift(sourceId)
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
     * A schema change with no migration path (a downgrade, say) drops every
     * table — see light-db-fallback.patch — and without this the app would
     * re-download everything it already has. Files are the durable artefact;
     * the index is derived from them.
     */
    suspend fun reindexFromDisk() {
        settings.sources.first()
            .filter { it.supportsDownloads }
            .forEach { source -> runCatching { reindexFromDisk(source) }.noteOom("reindex ${source.name}") }
    }

    private suspend fun reindexFromDisk(source: MusicSource) {
        val dao = daoFor(source)
        val known = dao.downloadedIds().toHashSet()
        val missing = store.onDisk(source.id).filter { (id, _) -> id !in known }
        if (missing.isEmpty()) return
        for ((id, format) in missing) {
            val file = store.fileFor(source.id, id, format)
            if (!file.isFile) continue
            dao.upsertDownload(
                DownloadEntity(
                    trackId = id,
                    albumId = null,
                    fileName = file.name,
                    format = format.id,
                    bytes = file.length(),
                    lyrics = null,
                    downloadedAtMs = file.lastModified(),
                ),
            )
        }
        // The file name carries only the track id, so the album comes from the
        // library — which on a rebuilt cache is usually empty at this point, so
        // this runs again after the sync. In SQL, because reading the songs for
        // one column fails once more are missing than one statement binds.
        dao.backfillDownloadAlbums()
    }

    /**
     * Fetch the words for the browsed source's downloads that have none.
     *
     * [reindexFromDisk] can rebuild a download row from the file on disk, but the
     * lyrics were only ever in the database, and a track that counts as
     * downloaded is never queued again — this is the one part of a wiped table
     * the disk cannot answer for.
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
            val track = tracks[id] ?: continue
            val row = dao.download(id) ?: continue
            val words = runCatching { fetchLyrics(client, track, wantKaraoke) }.getOrNull()
            dao.upsertDownload(row.copy(lyrics = words.orEmpty()))
        }
    }

    // --- Top-up --------------------------------------------------------------

    /**
     * One top-up pass at a time, and a burst of requests is one more pass, not
     * one each: each pass reads every source's tables and asks background
     * servers for their playlists. A request mid-pass marks the pass to run
     * again, and whatever it would have found, the rerun finds.
     */
    private val topUpRunning = Mutex()
    private val topUpRequested = AtomicBoolean(false)

    /** When a background source's playlist last failed to answer, by "source/playlist". */
    private val backgroundPlaylistFailedAt = ConcurrentHashMap<String, Long>()

    /**
     * Playlist membership for the sources not being browsed, by source id, for
     * the life of the process: the repository knows only the browsed source's.
     * The list once per session, and each playlist's songs once they answer,
     * null until then, so a failing one is asked again alone rather than
     * taking the others with it. Dropped with the source — see [forget].
     */
    private val backgroundPlaylistTracks = ConcurrentHashMap<String, MutableMap<String, List<String>?>>()

    /**
     * Queue whatever each source's offline mode wants that isn't downloaded
     * yet, for every source with a mode set, whichever is being browsed.
     */
    suspend fun topUp() {
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
            runCatching { topUp(source) }
                .noteOom("top-up ${source.name}")
                .onFailure { Log.w("AmpDl", "top-up failed for ${source.name}", it) }
        }
    }

    private suspend fun topUp(source: MusicSource) {
        val startedMs = System.currentTimeMillis()
        // This source's own tables, not the repository's lists, which follow
        // the browsed source. Asked for ids, not read whole: tracks would be a
        // second copy of the library, held through the playlist fetch below.
        val dao = daoFor(source)
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
        applyAutoMode(
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
        if (source.id == activeSource().id) return browsedPlaylistTrackIds()
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

    /**
     * Queue whatever [source]'s [OfflineMode] implies, by id — see
     * [QueuedDownload]. Safe to call repeatedly — already-downloaded tracks
     * are skipped, so this just tops up. [everyTrackId] is only asked for in
     * Everything mode.
     */
    private suspend fun applyAutoMode(
        source: MusicSource,
        likedTrackIds: List<String>,
        playlistTrackIds: List<String>,
        likedAlbumTrackIds: List<String>,
        likedArtistTrackIds: List<String>,
        everyTrackId: suspend () -> List<String>,
    ) {
        // Order is priority: the queue drains front to back and the size limit cuts
        // it off wherever it runs out, so whatever matters most has to come first.
        val wanted = LinkedHashSet<String>().apply {
            addAll(likedTrackIds)
            addAll(playlistTrackIds)
            addAll(likedAlbumTrackIds)
            addAll(likedArtistTrackIds)
        }
        when (source.offlineMode) {
            OfflineMode.MANUAL -> return
            OfflineMode.FAVORITES -> Unit
            // Everything, but favourites first so they are never the tracks that
            // lose out when the budget runs dry. And the rest only while there is
            // room for any of it: with the budget spent, the worker would empty
            // the queue at its first pick.
            OfflineMode.ALL ->
                if (store.usedBytesEverywhere() < settings.downloadLimit.first()) wanted.addAll(everyTrackId())
        }
        queueUp(source, wanted.map { QueuedDownload(source.id, it) }, manual = false)
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
     * answer back in line — see [Hold.Parked]. No worker and no transfer
     * service unless one does.
     *
     * Asked the way the browsed server is asked — see [askServer]: a 5xx is
     * a proxy saying the thing behind it is down, so no; a refused login is
     * the server answering, so yes, and the transfer that follows meets the
     * refusal and says so.
     */
    suspend fun wakeParked() {
        if (parkedIds().isEmpty() || !heavyDataAllowed()) return
        if (!probing.tryLock()) return
        try {
            val sources = settings.sources.first()
            var woke = false
            for (id in parkedIds()) {
                val source = sources.firstOrNull { it.id == id }
                if (source == null) {
                    // Removed since it was parked; nothing is left to wake.
                    unpark(id)
                    continue
                }
                val client = clientFor(source) ?: continue
                // Asked again per server: the rule can close part-way.
                if (!heavyDataAllowed()) continue
                val ping = askServer(client)
                Log.i("AmpDl", "parked ${source.name}: ping → ${ping.answered ?: "no verdict"} in ${ping.tookMs}ms (${ping.why})")
                if (ping.answered == true && unpark(id)) woke = true
            }
            if (woke) retry()
        } finally {
            probing.unlock()
        }
    }

    /**
     * Asked for a worker while one was still running. Mid-queue that changes
     * nothing — its next pass sees whatever the asking was about — but one
     * that has already decided to end does not look again, so a resume or an
     * answer arriving in that moment would be lost. A worker that was asked
     * for looks once more after it has gone, with its service already stopped.
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
        return best?.let(LyricsRepository::encodeStored)
    }

    /** Why nothing is moving although something is queued — see [eligible]. */
    private fun heldReason(sourceIds: Set<String>): String =
        if (sourceIds.isNotEmpty() && sourceIds.all { waiting(it) }) {
            "Waiting for the server"
        } else {
            "Paused while syncing"
        }

    private suspend fun drain() {
        val service = ServiceHold()
        val wantKaraoke = settings.karaokeLyrics.first()
        val used = UsedBytes()
        var completed = 0

        while (true) {
            // The pauses that hold everyone: the user's, and the data mode's.
            // Checked between tracks, so a sync starting mid-file lets that file
            // finish rather than abandoning the bytes already fetched.
            //
            // The worker ends on them and the entries stay queued, rather than
            // waiting with the service up: a Wi-Fi rule can hold a queue all
            // day, which spends the hours a day Android allows the service.
            // Resuming starts it again (setUserPaused), and so does the rule
            // moving (holdChanged), which App hears from the link's callback, on
            // the way back to the front, and from the half-hourly job.
            if (heldForEveryone()) {
                val waiting = lock.withLock { queue.size }
                if (waiting == 0) break
                // Time spent not trying is not time spent failing: a run of
                // failures from before the wait must not park a server on the
                // first one after it.
                backOffs.clearAll()
                showHeld(waiting)
                service.settle()
                return
            }
            // A transfer is the greediest thing on the link; a stream being
            // started waits for nobody else — see App.yieldToPlayback.
            yieldToPlayback()

            // Read once a pass: the records as they stand now, not as they were
            // when queued, so a token renewed or an address changed since is the
            // one used, and a change frees what [heldUntilChanged] held.
            val sources = settings.sources.first()
            val blocked = heldUntilChanged(sources)
            val (picked, held) = pick(blocked)
            if (picked == null) {
                // Only sources waiting on a quality change, a refusal, or a
                // parked server are left. Nothing here can end that wait, so the
                // worker ends instead — which lets the foreground service go —
                // and the entries stay queued. Whatever ends the wait starts it
                // again; see [retry]. The Downloads page says why.
                if (held.isEmpty() || held.all { it in blocked }) break
                // Something is queued, and every source it belongs to is held:
                // on its server to answer again, or for its sync to finish.
                _progress.value = _progress.value.copy(
                    currentTitle = heldReason(held - blocked),
                    currentSource = null,
                )
                delay(PAUSE_POLL_MS)
                continue
            }

            when (fetch(picked, sources, used, completed, service, wantKaraoke)) {
                Fetch.SAVED -> completed++
                Fetch.NEXT -> Unit
                Fetch.OVER_BUDGET -> {
                    lock.withLock { queue.clear() }
                    // A row saying a server isn't answering, or refused, would be
                    // about entries that are no longer there.
                    liftAll()
                    _progress.value = _progress.value.copy(
                        pending = 0,
                        currentTitle = null,
                        currentSource = null,
                    )
                    service.settle()
                    return
                }
            }
        }

        _progress.value = DownloadProgress(completed = completed)
        service.settle()
    }

    /**
     * Take the next entry, manual lane first, of whichever source can be asked
     * right now — see [DownloadQueue.next] — and mark it in flight in the same
     * hold of [lock], so a [cancelSource] from here on disowns it. Which lane it
     * came from travels with it, so a re-queue goes back where it belongs.
     *
     * Never picked: a source in [blocked] — see [heldUntilChanged]. With
     * nothing picked, the sources that still have entries waiting.
     */
    private suspend fun pick(blocked: Set<String>): Pair<DownloadQueue.Picked?, Set<String>> = lock.withLock {
        val next = queue.next { eligible(it) && it !in blocked }
        if (next == null) return@withLock null to queue.sourceIds()
        inFlight = next.entry.key
        // Only one entry is ever in flight: anything disowned before is over.
        abandoned = null
        next to emptySet()
    }

    /**
     * End [picked]'s time in flight and hand it back to the queue through
     * [then] — unless a [cancelSource] disowned it meanwhile: then it simply
     * goes, and the answer is false. Nothing of a disowned entry is recorded or
     * requeued, and it earns its source no strike and no back-off.
     */
    private suspend fun settle(
        picked: DownloadQueue.Picked,
        then: DownloadQueue.(DownloadQueue.Picked) -> Unit = {},
    ): Boolean = lock.withLock {
        inFlight = null
        if (abandoned == picked.entry.key) {
            abandoned = null
            false
        } else {
            queue.then(picked)
            true
        }
    }

    private enum class Fetch { SAVED, NEXT, OVER_BUDGET }

    /** Work [picked] off: fetch it, or say why not and put it where it belongs. */
    private suspend fun fetch(
        picked: DownloadQueue.Picked,
        sources: List<MusicSource>,
        used: UsedBytes,
        completed: Int,
        service: ServiceHold,
        wantKaraoke: Boolean,
    ): Fetch {
        val entry = picked.entry
        val source = sources.firstOrNull { it.id == entry.sourceId }
        if (source == null) {
            // Removed since it was queued; the entry goes with it.
            settle(picked)
            return Fetch.NEXT
        }
        val dao = daoFor(source)
        val track = trackToFetch(entry, dao)
        if (track == null) {
            settle(picked) { complete(it.entry) }
            return Fetch.NEXT
        }

        // Re-checked every track, because the budget moves as files land, and
        // read again, so a limit lowered mid-queue holds from the next track.
        if (used.now() >= settings.downloadLimit.first()) {
            settle(picked)
            return Fetch.OVER_BUDGET
        }

        _progress.value = _progress.value.copy(
            pending = lock.withLock { queue.size } + 1,
            completed = completed,
            currentTitle = track.title,
            currentSource = source.name,
        )

        val client = clientFor(source)
        if (client == null) {
            // Nothing to ask — a record with no server behind it. Back to
            // the front of its own lane, and its source waits its turn like
            // one that isn't answering, so the others aren't held up by it.
            _progress.value = _progress.value.copy(error = "Not connected")
            if (settle(picked) { requeueFront(it) } && backOff(source.id)) park(source, "Not connected")
            return Fetch.NEXT
        }

        // This source's own choice, not the browsed one's: the downloads
        // belong to that library, and a server that can only serve one
        // format shouldn't dictate what the others are fetched as.
        val format = source.downloadFormat
        // Changed to a quality the server can't meet since this pass began:
        // back in the queue untouched, and the next pass leaves it there.
        if (!source.downloadFormatAvailable) {
            settle(picked) { requeueFront(it) }
            return Fetch.NEXT
        }

        service.start()
        return when (val fetched = store.download(source.id, client.downloadUrl(track, format), track.id, format)) {
            is DownloadStore.Fetched.Saved -> {
                // Disowned while it was transferring — its source's downloads
                // were deleted, or the source itself was. Whatever landed goes.
                if (!settle(picked) { complete(it.entry) }) {
                    fetched.file.delete()
                    return Fetch.NEXT
                }
                keep(fetched.file, track, format, source, client, dao, wantKaraoke)
                used.add(fetched.file.length())
                clearBackOff(source.id)
                if (_progress.value.error != null) _progress.value = _progress.value.copy(error = null)
                Fetch.SAVED
            }
            is DownloadStore.Fetched.Refused -> {
                failed(picked, source, fetched.status, fetched.why)
                Fetch.NEXT
            }
            is DownloadStore.Fetched.Failed -> {
                failed(picked, source, status = null, fetched.why)
                Fetch.NEXT
            }
        }
    }

    /**
     * The row to fetch for [entry], or null when there is nothing to: fetched
     * while it waited (a top-up that ran while this same song was transferring
     * found it in no lane and no table, and queued it again), or an offline
     * mode's id — see QueuedDownload — that has left the library since. Read
     * from its source's tables now, so it is the row as it stands now.
     */
    private suspend fun trackToFetch(entry: QueuedDownload, dao: LibraryDao): Track? {
        if (dao.download(entry.trackId) != null) return null
        return entry.track ?: dao.tracksByIds(listOf(entry.trackId)).firstOrNull()
    }

    /** Make [file] the download of [track]: its index, its sleeve, its words, and its row. */
    private suspend fun keep(
        file: File,
        track: Track,
        format: StreamFormat,
        source: MusicSource,
        client: MusicServer,
        dao: LibraryDao,
        wantKaraoke: Boolean,
    ) {
        // An mp3 that came down as a stream has no index, and every
        // player that opens it has to guess its length — see
        // Mp3VbrIndex. Written now, before anything records the size.
        if (format == StreamFormat.MP3) {
            runCatching { withContext(Dispatchers.IO) { Mp3VbrIndex.index(file) } }
                .onSuccess { if (it is Mp3VbrIndex.Outcome.Written) Log.i("AmpMp3", "indexed ${file.name}: ${it.frames} frames") }
                .onFailure { Log.w("AmpMp3", "couldn't index ${file.name}", it) }
        }
        // The sleeve is part of having the record offline — this
        // source's sleeve, from this source's server.
        runCatching { App.artwork.prefetch(source.id, client, track.coverArtId) }
        // Always: the words are a few kilobytes beside a song, and an
        // offline track without them is the one place they cannot be
        // fetched on demand.
        val lyrics = fetchLyrics(client, track, wantKaraoke)
        dao.upsertDownload(
            DownloadEntity(
                trackId = track.id,
                albumId = track.albumId,
                fileName = file.name,
                format = format.id,
                bytes = file.length(),
                lyrics = lyrics,
                downloadedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** A transfer that brought nothing back: the server refused it, or [why]. */
    private suspend fun failed(picked: DownloadQueue.Picked, source: MusicSource, status: Int?, why: String) {
        // A cancelled worker must not put the track back: cancelAll has
        // just emptied the queue it would go into.
        coroutineContext.ensureActive()
        if (status == 401 || status == 403) {
            if (settle(picked) { requeueFront(it) }) refuse(source, status)
            return
        }
        // A failed transfer is the server's problem more often than the
        // track's: back off *this source's* queue rather than failing
        // every entry in a row. Where the track goes back to — front, or
        // back after enough strikes — is the queue's rule; see
        // [DownloadQueue.fail]. Failing for long enough parks the
        // source instead: see [Hold.Parked].
        if (!settle(picked) { fail(it) }) return
        _progress.value = _progress.value.copy(error = why)
        if (backOff(source.id)) park(source, why)
    }

    /**
     * The transfer service, held around actual transfers rather than around
     * the worker: starting it for a queue that turns out to be empty means
     * starting and stopping it within milliseconds, and Android kills the
     * process when a foreground service is torn down before it ever reached
     * the foreground.
     */
    private inner class ServiceHold {
        private var sinceMs: Long? = null

        fun start() {
            if (sinceMs != null) return
            // Without this the process drops to the cached bucket the moment the
            // user leaves the app and transfers are throttled — see
            // LightTransferService.
            LightTransferService.start(lightContext, "Syncing your library for offline")
            sinceMs = System.currentTimeMillis()
        }

        /**
         * For the same reason, a worker that ends a moment after its first
         * transfer began — refused at once, or cut off by the rule it is about
         * to end on — keeps the service until it has had those seconds.
         */
        suspend fun settle() {
            sinceMs?.let { delay(SERVICE_SETTLE_MS - (System.currentTimeMillis() - it)) }
        }
    }

    /**
     * What every source's downloads take on disk, for the budget: one budget
     * for the tool, not one per source — see AppSettings.downloadLimit — so it
     * is measured off the disk, where a table only answers for its own source.
     * Measured once a worker and then added to as files land, rather than a
     * walk of every folder per track; measured again after any deletion.
     */
    private inner class UsedBytes {
        private var deletionsSeen = -1
        private var bytes = 0L

        fun now(): Long {
            val deletions = store.deletions
            if (deletions != deletionsSeen) {
                deletionsSeen = deletions
                bytes = store.usedBytesEverywhere()
            }
            return bytes
        }

        fun add(landed: Long) {
            bytes += landed
        }
    }

    private companion object {
        const val PAUSE_POLL_MS = 1_000L

        /** How many lyric refills one pass will attempt — see refillMissingLyrics. */
        const val LYRICS_REFILL_PER_RUN = 200

        /**
         * How long the transfer service is kept after it was started, at the
         * least: the five seconds Android gives a started service to reach the
         * foreground — see LightTransferService and [drain].
         */
        const val SERVICE_SETTLE_MS = 5_000L

        /**
         * How long a top-up pass waits after being asked for: the triggers fire
         * when the screen is busiest (a source switch refills every list from
         * the database), and the wait folds a burst of triggers into one pass.
         */
        const val TOP_UP_SETTLE_MS = 3_000L

        /**
         * How long a playlist that would not answer is left alone before being
         * asked again: one that failed for want of memory would fill the heap
         * at every pass.
         */
        const val PLAYLIST_RETRY_MS = 30 * 60 * 1000L

        const val PLAYLIST_PRIME_CONCURRENCY = 6
    }
}

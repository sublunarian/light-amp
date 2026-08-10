package com.sublunar.amp.playback

import androidx.media3.common.Player
import com.thelightphone.sdk.cast.DlnaCast
import com.thelightphone.sdk.cast.DlnaRenderer
import com.thelightphone.sdk.cast.DlnaState
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.Connectivity
import com.sublunar.amp.data.DataMode
import com.sublunar.amp.data.DownloadStore
import com.sublunar.amp.data.LocalLibrary
import com.sublunar.amp.data.RepeatMode
import android.view.KeyEvent
import androidx.media3.common.PlaybackException
import com.sublunar.amp.App
import com.sublunar.amp.data.PendingAction
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.qualityRank
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The single, app-scoped playback engine. Wraps one [LightAudioPlayer] (created
 * from the activity, reused across every screen). All queue mutations route
 * through here; edits use the player's incremental ops so adding or reordering
 * upcoming tracks never interrupts the current one.
 *
 * Playback is foreground-only until LightOS ships background audio; this class
 * needs no change when it does.
 */
class PlaybackController(
    private val settings: AppSettings,
    private val serverClient: StateFlow<MusicServer?>,
    /** Resolved per call: the active source's database, see App.dao. */
    private val daoProvider: () -> LibraryDao,
    private val downloads: DownloadStore,
    private val scope: CoroutineScope,
) {
    private var player: LightAudioPlayer? = null

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume

    val currentTrack: StateFlow<Track?> =
        combine(_queue, _index) { q, i -> q.getOrNull(i) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    // Matches the stored default, so the window before the setting arrives
    // errs towards not spending data rather than towards spending it.
    private var dataMode = DataMode.WIFI_ONLY

    /**
     * Mirrored rather than queried per track: [Connectivity.isOnWifi] enumerates
     * network interfaces, and source selection happens on the main thread.
     */
    @Volatile
    private var onWifi = false
    private var lastScrobbledId: String? = null

    /**
     * Identifies *this* playback to the server, distinct from any other
     * stream (or download) in flight — see the comment on
     * [MusicServer.streamUrl]. Minted fresh whenever the track actually
     * playing changes, and carried on both the stream URL and the
     * [reportTimeline] calls that go with it, so Plex's dashboard has one
     * session to show rather than none.
     */
    private var sessionId: String = UUID.randomUUID().toString()

    /** The track [sessionId] currently belongs to, so it can be closed out by id
     * when replaced — see [mintSessionId]. Null when nothing's reported yet. */
    private var sessionTrackId: String? = null

    // Local files for downloaded tracks, refreshed as downloads land so queue
    // items can be built synchronously.
    private var localFiles: Map<String, Pair<java.io.File, StreamFormat>> = emptyMap()

    private val dao: LibraryDao get() = daoProvider()

    /**
     * How far into the track the current *stream* starts, when the server was
     * asked to seek for us. Zero for files and for a stream played from the top.
     */
    @Volatile
    private var streamOffsetMs = 0L

    /**
     * The same trick for the renderer: how far into the track its stream starts.
     *
     * A UPnP Seek is a byte-range request, and a Navidrome transcode is a live
     * ffmpeg pipe with no seek table — so handing the Denon a seek right after
     * SetAVTransportURI got the request refused and the track played from the
     * top, which is why casting mid-song sometimes "restarted" it. The offset
     * goes into the URL instead, and the readouts are shifted back.
     */
    private var castOffsetMs = 0L

    /** Latched by [fallBackOffline]; cleared when the server answers again. */
    @Volatile
    private var forceOffline = false
    private var retriedTrackId: String? = null

    /**
     * Tracks whose stream failed, which play from disk instead.
     *
     * Narrower than [forceOffline], and the right size for a server that is
     * plainly there but wouldn't serve *this* song: one track goes to its
     * downloaded copy and everything else still streams.
     */
    private val streamFailed = mutableSetOf<String>()

    // Queue order captured when shuffle was switched on, so switching it back off
    // can restore it.
    private var preShuffleOrder: List<Track>? = null

    // --- DLNA casting (TEMPORARY — removed before submitting to Light) --------

    private val _castRenderer = MutableStateFlow<DlnaRenderer?>(null)
    val castRenderer: StateFlow<DlnaRenderer?> = _castRenderer

    /** True while audio is going to a renderer instead of this device. */
    val isCasting: Boolean get() = _castRenderer.value != null

    private var castJob: Job? = null

    // "rendererId|format" -> stream format actually sent + its MIME type.
    private val castFormats = mutableMapOf<String, Pair<StreamFormat, String>>()

    /** What the renderer is playing, and what it has been given to play next. */
    private var castCurrentUrl: String? = null
    private var castNextUrl: String? = null

    /** Attach the audio stack from the current activity. Idempotent per process. */
    fun bind(audio: LightAudio) {
        if (player != null) return
        val p = audio.newPlayer(LightAudioUsage.Music)
        player = p
        p.onPlaybackError = { error ->
            // A bad HTTP status is proof the server is *there*: it answered, it
            // just didn't like the request. Treating that as "unreachable" takes
            // the whole library down to downloads-only over one bad URL, and it
            // stays that way until something else proves otherwise.
            fallBackOffline(
                serverAnswered = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )
        }
        // While casting, the renderer is the source of truth for these — the local
        // player sits paused and would otherwise report position 0 / not-playing
        // straight over the cast state.
        scope.launch { p.isPlaying.collect { if (!isCasting) _isPlaying.value = it } }
        // A server-seeked stream starts at 0:00 of a shorter file, so both
        // readouts are shifted back into the track's own timeline.
        scope.launch { p.positionMs.collect { if (!isCasting) _positionMs.value = it + streamOffsetMs } }
        scope.launch {
            p.durationMs.collect { reported ->
                if (isCasting) return@collect
                // What the library says the track runs to, which is a real number
                // even before a single byte has arrived.
                val known = _queue.value.getOrNull(_index.value)?.durationMs ?: 0L
                _durationMs.value = when {
                    // A transcode arrives chunked, with no length to derive a
                    // duration from — Plex streams every format but the original
                    // that way. The player reports nothing, and without this the
                    // bar has a position and no end to measure it against.
                    reported <= 0L -> known
                    // A server-seeked stream is a shorter file starting at 0:00,
                    // so its own duration is the remainder, not the track's.
                    streamOffsetMs > 0 -> known.takeIf { it > 0L } ?: (reported + streamOffsetMs)
                    else -> reported
                }
            }
        }
        scope.launch {
            p.currentMediaItemIndex.collect { idx ->
                if (isCasting) return@collect
                if (idx >= 0 && idx != _index.value) {
                    _index.value = idx
                    // A new track is a fresh stream from its own beginning.
                    streamOffsetMs = 0
                    // A new track is a new session too, or Plex sees the same
                    // one just keep changing what it's playing rather than one
                    // track ending and the next beginning. The one it replaces
                    // is told it's stopped first, so it doesn't just sit in
                    // `/status/sessions` until its lease times out.
                    _queue.value.getOrNull(idx)?.let { mintSessionId(it.id) }
                    // Seeded from the library rather than waited for: a chunked
                    // stream may never report a duration at all, and the player
                    // won't emit again to give the collector above a second go.
                    _queue.value.getOrNull(idx)?.durationMs
                        ?.takeIf { it > 0L }
                        ?.let { _durationMs.value = it }
                    reportTimeline(TimelineState.PLAYING)
                }
                maybeScrobble()
            }
        }
        // Anything that changes what plays next — an edit, a jump, a repeat mode —
        // has to be pushed to the renderer, which is holding a URI we chose
        // earlier. Debounced because a single edit moves several of these at once.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch {
            combine(_queue, _index, _repeatMode) { _, _, _ -> Unit }
                .debounce(CAST_REQUEUE_DEBOUNCE_MS)
                .collect { _castRenderer.value?.let { queueNextOnRenderer(it) } }
        }
        scope.launch { settings.dataMode.collect { dataMode = it } }
        // The server answering again is the signal to stop pinning playback to
        // local files; the next track resolves normally.
        scope.launch {
            App.serverReachable.collect { reachable ->
                if (reachable) {
                    forceOffline = false
                    retriedTrackId = null
                    streamFailed.clear()
                }
            }
        }
        scope.launch { Connectivity.wifiConnected.collect { onWifi = it } }
        // Shared with the library rather than queried again here — one read of
        // the downloads table feeds both. It also fixes a subtler thing: this
        // used to resolve `dao` once, at bind, and so stayed subscribed to
        // whichever database was current at boot. After a source switch the map
        // still described the *previous* source's downloads, whose track ids
        // match nothing in the new one, so downloaded audio quietly stopped
        // being used at all.
        scope.launch {
            App.library.downloadFiles.collect { rows ->
                localFiles = rows.mapNotNull { row ->
                    downloads.existing(row.fileName)?.let { file ->
                        row.trackId to (file to StreamFormat.fromId(row.format))
                    }
                }.toMap()
            }
        }
        // Mirror the system media volume so the fader reflects it (and the
        // hardware volume keys, which drive the same stream).
        // The hardware rocker moves Android's own media volume, which while
        // casting controls a player that isn't making the sound. Forwarding it to
        // the renderer makes the buttons act on whatever is actually playing —
        // without intercepting the keys, which the tool sandbox can't do.
        scope.launch {
            p.deviceVolume.collect { level ->
                _volume.value = level
                val renderer = _castRenderer.value ?: return@collect
                DlnaCast.setVolume(renderer, level)
            }
        }

        // Snapshot the queue periodically rather than on every position tick: the
        // point is to survive a kill, and a few seconds of lost progress is a fair
        // trade against writing to DataStore once a second forever.
        scope.launch {
            while (true) {
                delay(SAVE_STATE_INTERVAL_MS)
                persistState()
            }
        }

        // Plex tears a session out of `/status/sessions` if nothing pings it
        // for a while — the same idea as a UPnP lease. Only while something is
        // actually playing: a paused session was already told once and doesn't
        // need repeating, and an idle queue has no session at all.
        scope.launch {
            while (true) {
                delay(TIMELINE_INTERVAL_MS)
                if (_isPlaying.value) reportTimeline(TimelineState.PLAYING)
            }
        }
    }

    /** Write the queue, index and position so the next launch can pick them up. */
    private suspend fun persistState() {
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        settings.saveQueue(tracks.map { it.id }, _index.value, _positionMs.value)
    }

    /**
     * Reload the queue saved by a previous run, ready but **paused**.
     *
     * Resolving ids against the cached library means a track deleted server-side
     * simply drops out. The index is remapped onto whatever survived so the right
     * song is still cued rather than an arbitrary neighbour.
     */
    suspend fun restoreState(library: List<Track>) {
        if (player == null || _queue.value.isNotEmpty()) return
        val ids = settings.savedQueueIds.first()
        if (ids.isEmpty()) return
        val byId = library.associateBy { it.id }
        val savedIndex = settings.savedQueueIndex.first()
        val wantedId = ids.getOrNull(savedIndex)
        val tracks = ids.mapNotNull { byId[it] }
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == wantedId }.takeIf { it >= 0 } ?: 0

        val p = player ?: return
        val position = settings.savedPositionMs.first()
        // ExoPlayer's application looper is Main and it enforces that: touching the
        // player from this coroutine's Default dispatcher throws "Player is accessed
        // on the wrong thread" and takes the app down on launch. Every other caller
        // reaches setQueue from a composable, i.e. already on Main.
        withContext(Dispatchers.Main.immediate) {
            _queue.value = tracks
            lastScrobbledId = null
            // Start position goes in at prepare time: seekTo() clamps to the
            // duration, which is still unknown this early, so it would land on 0.
            p.setMediaQueueAt(tracks.map { it.toAudioItem() }, index, position)
            _index.value = index
            // Deliberately not p.play(): restoring should cue the track up, not
            // start making noise on its own the moment the app is opened.
            p.pause()
        }
    }

    fun release() {
        // A renderer keeps playing whatever URI it was handed, so leaving without
        // telling it to stop strands audio on the speaker while the app shows
        // "This device". Bounded so teardown can't hang on an unreachable device.
        _castRenderer.value?.let { renderer ->
            castJob?.cancel()
            castJob = null
            _castRenderer.value = null
            runBlocking { withTimeoutOrNull(CAST_STOP_TIMEOUT_MS) { DlnaCast.stop(renderer) } }
        }
        // One last snapshot before the player goes: an orderly exit should not
        // lose the few seconds since the last tick.
        runBlocking { withTimeoutOrNull(SAVE_STATE_TIMEOUT_MS) { persistState() } }
        player?.release()
        player = null
    }

    // --- Playback controls ---------------------------------------------------

    /** Replace the queue with [tracks] and start at [startIndex]. */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        if (_shuffle.value) {
            val first = tracks[start]
            val rest = tracks.toMutableList().apply { removeAt(start) }.shuffled()
            setQueue(capped(listOf(first) + rest, 0).first, 0)
        } else {
            val (window, index) = capped(tracks, start)
            setQueue(window, index)
        }
    }

    /**
     * Trim a queue to something the player can actually hold.
     *
     * Media3 mirrors the whole timeline into a legacy MediaSession queue for
     * Bluetooth clients — and loads artwork for every entry while doing it. Handing
     * it eight thousand tracks ran the process out of memory in
     * `MediaSessionLegacyStub.updateQueue`, and building that many stream URLs
     * blocked the tap that started it for seconds.
     *
     * The window is centred on the track being played, keeping a little history
     * behind it, so "play the 3,000th song" still works — it just doesn't carry
     * the other seven thousand along.
     */
    private fun capped(tracks: List<Track>, startIndex: Int): Pair<List<Track>, Int> {
        if (tracks.size <= MAX_QUEUE) return tracks to startIndex
        val first = (startIndex - QUEUE_LOOKBACK).coerceIn(0, tracks.size - MAX_QUEUE)
        return tracks.subList(first, first + MAX_QUEUE) to (startIndex - first)
    }

    fun togglePlayPause() {
        val renderer = _castRenderer.value
        if (renderer != null) {
            val wasPlaying = _isPlaying.value
            _isPlaying.value = !wasPlaying
            scope.launch {
                if (wasPlaying) DlnaCast.pause(renderer) else DlnaCast.resume(renderer)
            }
            reportTimeline(if (wasPlaying) TimelineState.PAUSED else TimelineState.PLAYING)
            return
        }
        val p = player ?: return
        val wasPlaying = _isPlaying.value
        if (wasPlaying) p.pause() else p.play()
        reportTimeline(if (wasPlaying) TimelineState.PAUSED else TimelineState.PLAYING)
    }

    fun play() {
        val renderer = _castRenderer.value
        if (renderer != null) {
            _isPlaying.value = true
            scope.launch { DlnaCast.resume(renderer) }
            reportTimeline(TimelineState.PLAYING)
            return
        }
        player?.play()
        reportTimeline(TimelineState.PLAYING)
    }

    fun pause() {
        val renderer = _castRenderer.value
        if (renderer != null) {
            _isPlaying.value = false
            scope.launch { DlnaCast.pause(renderer) }
            reportTimeline(TimelineState.PAUSED)
            return
        }
        player?.pause()
        reportTimeline(TimelineState.PAUSED)
    }

    /**
     * Seek, by whichever route the source allows.
     *
     * A downloaded file seeks natively and exactly. A *transcoded* stream can't:
     * the server is piping a live ffmpeg encode with no seek table, so the
     * player can only guess a byte offset from an estimated length and then read
     * forward to reach it — which at 320 kbps means pulling megabytes before a
     * sound comes out, and landing somewhere approximate when it does.
     *
     * So the seek is handed to the server instead: the same track requested with
     * `timeOffset`, which starts the encode at that second. The stream then runs
     * from 0:00, and [streamOffsetMs] shifts the readouts back into the track's
     * own timeline.
     */
    fun seekTo(ms: Long) {
        val renderer = _castRenderer.value
        if (renderer != null) {
            _positionMs.value = ms
            // Re-push the track from the new offset rather than asking the
            // renderer to seek — see [castOffsetMs].
            castJob?.cancel()
            castJob = scope.launch {
                if (startCastTrack(renderer, ms)) pollRenderer(renderer)
            }
            return
        }
        val p = player ?: return
        val track = _queue.value.getOrNull(_index.value)
        if (track == null || !needsServerSeek(track)) {
            p.seekTo(ms)
            return
        }
        serverSeek(p, track, ms)
    }

    /** True when the current source is a transcode the player can't seek within. */
    private fun needsServerSeek(track: Track): Boolean {
        if (track.source() is LightAudioSource.FileSource) return false
        // The original file is served as-is, with its own headers and seek table.
        return effectiveFormat() != StreamFormat.RAW
    }

    private fun serverSeek(p: LightAudioPlayer, track: Track, ms: Long) {
        val client = serverClient.value ?: return
        val target = ms.coerceAtLeast(0L)
        val url = client.streamUrl(
            track,
            effectiveFormat(),
            timeOffsetSeconds = (target / 1000).toInt(),
            sessionId = sessionId,
        )
        streamOffsetMs = target
        // Only the playing item is replaced; the queue either side of it is
        // untouched, so a seek doesn't disturb what plays next.
        _positionMs.value = target
        val item = LightAudioItem(
            source = LightAudioSource.UrlSource(url),
            metadata = LightMediaMetadata(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
            ),
        )
        scope.launch(Dispatchers.Main.immediate) {
            val wasPlaying = p.isPlaying.value
            val at = _index.value
            // Replaced in place rather than removed and re-added. Taking the
            // playing item out moves the player's idea of the current index —
            // twice, once each way — and the collector above reads that as a new
            // track and clears streamOffsetMs. The clock then counts up from zero
            // while the audio plays from where it was asked to.
            p.replaceRange(at, at + 1, listOf(item))
            // The replacement is a stream that already starts at the target, so
            // it has to play from its own beginning: Media3 keeps the old
            // position when the item it replaces looks like the same one, which
            // would land us at the offset twice over. This sets the index the
            // player is already on, so nothing reads it as a track change.
            p.seekToIndex(at)
            if (wasPlaying) p.play()
        }
    }

    /** Stop playback and empty the queue. */
    fun stop() {
        reportTimeline(TimelineState.STOPPED)
        sessionTrackId = null
        _castRenderer.value?.let { renderer ->
            castJob?.cancel()
            castJob = null
            _castRenderer.value = null
            scope.launch { DlnaCast.stop(renderer) }
        }
        val p = player ?: return
        // Same rule as restoreState: ExoPlayer's looper is Main and it throws
        // rather than tolerating anything else. Unlike the rest of this class,
        // stop() is not only reached from a composable — a source switch calls it
        // from a collector on Default — so it has to place itself. `immediate`
        // means a caller already on Main is unaffected and still synchronous.
        scope.launch(Dispatchers.Main.immediate) {
            p.stop()
            p.setMediaQueue(emptyList())
            _queue.value = emptyList()
            _index.value = -1
            streamOffsetMs = 0
            _positionMs.value = 0
            _durationMs.value = 0
        }
    }
    fun skipForward() = player?.skipForward()
    fun skipBack() = player?.skipBack()

    fun next() {
        if (isCasting) {
            jumpTo(_index.value + 1)
            return
        }
        player?.skipToNext()
    }

    /** Restart the current track if we're past the intro, otherwise go to the previous one. */
    fun previous() {
        if (isCasting) {
            if (_positionMs.value > PREVIOUS_RESTART_MS) seekTo(0) else jumpTo(_index.value - 1)
            return
        }
        val p = player ?: return
        if (_positionMs.value > PREVIOUS_RESTART_MS) p.seekTo(0) else p.skipToPrevious()
    }

    /** Jump to an arbitrary queue position (tapping a queue row). */
    fun jumpTo(index: Int) {
        if (index !in _queue.value.indices) return
        val renderer = _castRenderer.value
        if (renderer != null) {
            _index.value = index
            _positionMs.value = 0
            castJob?.cancel()
            castJob = scope.launch {
                if (startCastTrack(renderer)) pollRenderer(renderer)
            }
            return
        }
        val p = player ?: return
        p.seekToIndex(index)
        _index.value = index
        p.play()
    }

    // --- Queue editing (seamless) --------------------------------------------

    fun addToQueue(tracks: List<Track>) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        _queue.value = _queue.value + tracks
        p.addItems(tracks.map { it.toAudioItem() })
    }

    /**
     * Put these tracks immediately after the one playing.
     *
     * A track already in the queue is *moved* rather than copied: "play next" on
     * something queued for later means bring it forward, and inserting a second
     * copy would leave it to play twice.
     */
    fun playNext(tracks: List<Track>) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        for (track in tracks) {
            val from = _queue.value.indexOfFirst { it.id == track.id }
            val cur = _index.value
            if (from < 0) {
                val at = (cur + 1).coerceIn(0, _queue.value.size)
                _queue.value = _queue.value.toMutableList().apply { add(at, track) }
                p.addItemAt(at, track.toAudioItem())
                continue
            }
            if (from == cur) continue
            // Destinations are read after the removal, so a track pulled from
            // behind the cursor lands one place earlier than one pushed forward.
            moveInQueue(from, if (from < cur) cur else cur + 1)
        }
    }

    fun removeFromQueue(index: Int) {
        val p = player ?: return
        val q = _queue.value
        if (index !in q.indices) return
        p.removeItem(index)
        val newQueue = q.toMutableList().apply { removeAt(index) }
        _queue.value = newQueue
        _index.value = if (newQueue.isEmpty()) -1
        else p.currentMediaItemIndex.value.coerceIn(0, newQueue.lastIndex)
    }

    /**
     * Drop several tracks from the queue at once.
     *
     * Removed back to front so each removal can't shift the indices of the ones
     * still to go, and the surviving list is computed here rather than read back
     * from the player — a snapshot taken mid-edit is how rows end up playing the
     * wrong track.
     */
    fun removeFromQueue(ids: Set<String>) {
        val p = player ?: return
        if (ids.isEmpty()) return
        val q = _queue.value
        val targets = q.indices.filter { q[it].id in ids }
        if (targets.isEmpty()) return
        targets.sortedDescending().forEach { p.removeItem(it) }
        val gone = targets.toSet()
        val newQueue = q.filterIndexed { i, _ -> i !in gone }
        _queue.value = newQueue
        _index.value = if (newQueue.isEmpty()) {
            -1
        } else {
            p.currentMediaItemIndex.value.coerceIn(0, newQueue.lastIndex)
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        val p = player ?: return
        val q = _queue.value
        if (from !in q.indices || to !in q.indices || from == to) return
        p.moveItem(from, to)
        _queue.value = q.toMutableList().apply { add(to, removeAt(from)) }
        _index.value = p.currentMediaItemIndex.value.coerceIn(0, _queue.value.lastIndex)
    }

    // --- Modes ---------------------------------------------------------------

    fun cycleRepeat() {
        setRepeat(
            when (_repeatMode.value) {
                RepeatMode.OFF -> RepeatMode.QUEUE
                RepeatMode.QUEUE -> RepeatMode.TRACK
                RepeatMode.TRACK -> RepeatMode.OFF
            }
        )
    }

    fun setRepeat(mode: RepeatMode) {
        _repeatMode.value = mode
        player?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
            RepeatMode.QUEUE -> Player.REPEAT_MODE_ALL
        }
    }

    /**
     * Move the fader. While casting this drives the *renderer's* volume over UPnP
     * RenderingControl; otherwise it's the phone's media stream — the same one the
     * hardware keys use.
     */
    /**
     * Handle a hardware volume key, returning true when it was ours to handle.
     *
     * The keys are deliberately allowed to fall through to the system so the
     * active MediaSession moves playback volume — but while casting there is no
     * local playback, so Android moves the *ring* stream and the speaker never
     * hears about it. Catching them here is the only way the rocker can reach a
     * renderer; everything else is left to the system exactly as before.
     */
    fun handleVolumeKey(keyCode: Int): Boolean {
        if (!isCasting) return false
        val step = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_KEY_STEP
            KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_KEY_STEP
            else -> return false
        }
        setVolume(_volume.value + step)
        return true
    }

    /**
     * Set the output's volume, whichever output that is.
     *
     * Casting also nudges the device's own level to match, so the rocker starts
     * from where the renderer actually is rather than jumping on the first press.
     */
    fun setVolume(fraction: Float) {
        val v = fraction.coerceIn(0f, 1f)
        _volume.value = v
        val renderer = _castRenderer.value
        if (renderer != null) {
            scope.launch { DlnaCast.setVolume(renderer, v) }
        }
        player?.setSystemVolume(v)
    }

    /**
     * Toggles shuffle by reordering only the *upcoming* part of the live queue.
     * History and the current track stay put and the reorder is applied with
     * incremental move ops, so the playing track is never re-prepared (rebuilding
     * the queue with setMediaQueue would restart it). Turning shuffle off puts the
     * upcoming tracks back into their pre-shuffle order.
     */
    fun toggleShuffle() {
        val on = !_shuffle.value
        _shuffle.value = on
        val q = _queue.value
        val current = _index.value
        if (q.isEmpty() || current < 0) {
            preShuffleOrder = if (on) q.takeIf { it.isNotEmpty() } else null
            return
        }
        val head = q.take(current + 1)
        val tail = q.drop(current + 1)
        val newTail = if (on) {
            preShuffleOrder = q
            tail.shuffled()
        } else {
            val original = preShuffleOrder
            preShuffleOrder = null
            if (original == null) tail else restoreOrder(tail, original)
        }
        if (tail.isEmpty()) return
        _queue.value = head + newTail
        // One replace rather than a move per track: settling a few hundred
        // positions one at a time is a timeline update each, which locks the main
        // thread and (with artwork) exhausts the heap.
        player?.replaceRange(head.size, q.size, newTail.map { it.toAudioItem() })
    }

    // --- DLNA casting --------------------------------------------------------

    /** Find renderers on the network. Blocking on the network for a few seconds. */
    suspend fun findCastDevices(): List<DlnaRenderer> = DlnaCast.discover()

    /**
     * Move playback to [renderer]: stop this device, hand the current track's
     * stream URL to the renderer at the position we'd reached, and start polling
     * it for progress.
     */
    fun castTo(renderer: DlnaRenderer) {
        player?.pause()
        _castRenderer.value = renderer
        val from = _positionMs.value
        castJob?.cancel()
        castJob = scope.launch {
            // Adopt the renderer's current level so the fader isn't lying — and
            // move the device's own to match, so the first press of the rocker
            // nudges from where the speaker is rather than jumping.
            DlnaCast.volume(renderer)?.let {
                _volume.value = it
                withContext(Dispatchers.Main.immediate) { player?.setSystemVolume(it) }
            }
            if (startCastTrack(renderer, from)) pollRenderer(renderer)
        }
    }

    /**
     * Stop casting and (by default) pick playback back up on this device from
     * wherever the renderer had reached.
     */
    fun stopCasting(resumeLocally: Boolean = true) {
        val renderer = _castRenderer.value ?: return
        val at = _positionMs.value
        castJob?.cancel()
        castJob = null
        _castRenderer.value = null
        castOffsetMs = 0L
        scope.launch { DlnaCast.stop(renderer) }
        val p = player ?: return
        _volume.value = p.deviceVolume.value
        if (resumeLocally) {
            p.seekTo(at)
            p.play()
        }
    }

    /** Push the queue's current track to the renderer, optionally seeking in. */
    private suspend fun startCastTrack(renderer: DlnaRenderer, seekToMs: Long = 0L): Boolean {
        val track = _queue.value.getOrNull(_index.value) ?: return false
        // A new track on the renderer is a new session, same as the local
        // player's — see the index collector in bind(). Also covers a seek,
        // which restarts the renderer's stream from scratch the same way a
        // track change does, so the session it's replacing needs closing too.
        mintSessionId(track.id)
        val (format, mime) = castFormatFor(renderer, track)
        // Anything under a second is where the track starts anyway, and a
        // timeOffset of 0 is a re-encode for nothing.
        //
        // Only for a transcode: the original file is served whole, with its own
        // seek table and byte ranges the renderer can use — and Navidrome
        // ignores timeOffset for it, so asking would land back at 0:00.
        val wanted = if (seekToMs > 1_000L) seekToMs else 0L
        val offset = if (format == StreamFormat.RAW) 0L else wanted
        // No estimated length for a renderer. Navidrome derives the estimate from
        // duration x the *cap* bitrate, but its ffmpeg output is ABR and comes in
        // well under: a 100s track declared 3,091,660 bytes and delivered
        // 2,817,871. A UPnP renderer takes that declaration for the extent of the
        // stream and stops at the length it implies — about 23 seconds early on
        // that track. Chunked is honest: it plays to the end of the audio, and the
        // duration it displays comes from the DIDL metadata either way.
        val url = serverClient.value?.streamUrl(
            track,
            format,
            estimateContentLength = false,
            timeOffsetSeconds = (offset / 1000).toInt(),
            sessionId = sessionId,
        ) ?: return false
        val started = DlnaCast.play(
            renderer = renderer,
            url = url,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            mimeType = mime,
        )
        if (!started) return false
        _isPlaying.value = true
        _durationMs.value = track.durationMs
        castOffsetMs = offset
        _positionMs.value = wanted
        if (offset == 0L && wanted > 0L) DlnaCast.seek(renderer, wanted)
        maybeScrobble()
        castCurrentUrl = url
        queueNextOnRenderer(renderer)
        return true
    }

    /**
     * Hand the renderer the following track so it can cross into it seamlessly.
     *
     * Records the URL under [castNextUrl] so the poller can recognise the
     * hand-off when it happens: the renderer switches on its own, and the only
     * evidence is that GetPositionInfo starts reporting the other URI.
     */
    private suspend fun queueNextOnRenderer(renderer: DlnaRenderer) {
        castNextUrl = null
        val after = nextCastIndex() ?: return
        val track = _queue.value.getOrNull(after) ?: return
        val (format, mime) = castFormatFor(renderer, track)
        // Its own id, not the current track's: both transcodes are in flight
        // at once during the hand-off, and Plex tears down whichever one
        // shares an identifier with the other.
        val url = serverClient.value
            ?.streamUrl(track, format, estimateContentLength = false, sessionId = UUID.randomUUID().toString())
            ?: return
        val queued = DlnaCast.setNext(
            renderer = renderer,
            url = url,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            mimeType = mime,
        )
        // Renderers that don't implement it fault; the stop-and-restart path then
        // carries the queue as it did before.
        if (queued) castNextUrl = url
    }

    /** The index the queue would move to next, or null at the end of it. */
    private fun nextCastIndex(): Int? {
        val size = _queue.value.size
        if (size == 0) return null
        return when (_repeatMode.value) {
            RepeatMode.TRACK -> _index.value
            RepeatMode.QUEUE -> (_index.value + 1) % size
            RepeatMode.OFF -> (_index.value + 1).takeIf { it < size }
        }
    }

    /**
     * Format to cast in: the user's chosen streaming quality, honoured as long as
     * the renderer can actually decode what the *server* will send.
     *
     * Deliberately not an upgrade — choosing MP3 in settings means MP3 goes to the
     * speaker too. Two things make this trickier than it looks, and both are
     * load-bearing:
     *  - the format name in the URL doesn't fix the container (a "flac" transcode
     *    can arrive as Ogg-FLAC), so the MIME type is probed from the server;
     *  - an unsupported format doesn't error, the renderer just stalls, so we fall
     *    back to MP3 whenever the device's sink list doesn't cover that MIME.
     * If the device won't list its formats, the user's choice wins.
     */
    private suspend fun castFormatFor(
        renderer: DlnaRenderer,
        track: Track,
    ): Pair<StreamFormat, String> {
        val desired = effectiveFormat()
        val key = "${renderer.id}|${desired.id}"
        castFormats[key]?.let { return it }

        val client = serverClient.value
        val sink = runCatching { DlnaCast.sinkFormats(renderer) }.getOrDefault(emptySet())
        val mime = client?.let { DlnaCast.probeMime(it.streamUrl(track, desired)) }
            ?: assumedMime(desired)
        val choice = if (sinkAccepts(sink, mime)) {
            desired to mime
        } else {
            StreamFormat.MP3 to "audio/mpeg"
        }
        castFormats[key] = choice
        return choice
    }

    private fun assumedMime(format: StreamFormat): String = when (format) {
        StreamFormat.MP3 -> "audio/mpeg"
        StreamFormat.OPUS -> "audio/ogg"
        StreamFormat.FLAC -> "audio/flac"
        // Unknown container up front; the DLNA wildcard lets the device sniff.
        StreamFormat.RAW -> "*"
    }

    /**
     * Whether the renderer's advertised sink covers [mime]. Compared through
     * alias groups because devices and servers spell the same container
     * differently (`audio/mp4` vs `audio/m4a`, `audio/mpeg` vs `audio/mp3`).
     */
    private fun sinkAccepts(sink: Set<String>, mime: String): Boolean {
        if (sink.isEmpty() || mime == "*") return true
        val aliases = when (mime) {
            "audio/mpeg", "audio/mp3" -> setOf("audio/mpeg", "audio/mp3")
            "audio/flac", "audio/x-flac" -> setOf("audio/flac", "audio/x-flac")
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" ->
                setOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac")
            "audio/ogg", "audio/opus", "application/ogg" ->
                setOf("audio/ogg", "audio/opus", "application/ogg")
            "audio/wav", "audio/x-wav" -> setOf("audio/wav", "audio/x-wav")
            else -> setOf(mime)
        }
        return sink.any { it.substringBefore(';').trim() in aliases }
    }

    /**
     * Mirror the renderer's clock into the UI and advance the queue when a track
     * finishes — a renderer only ever holds one URI, so gapless hand-off has to
     * be driven from here.
     */
    private suspend fun pollRenderer(renderer: DlnaRenderer) {
        var settled = false
        // A renderer reports STOPPED for a moment while it re-buffers as well as
        // at the end of a track, and the two are indistinguishable from one poll.
        // Two in a row is: the stream really has ended.
        var stoppedPolls = 0
        while (coroutineContext.isActive && _castRenderer.value?.id == renderer.id) {
            delay(CAST_POLL_MS)
            val position = DlnaCast.position(renderer)
            if (position != null) {
                // The renderer crossed into the track we queued: follow it here
                // rather than driving it, so nothing interrupts the audio.
                // Repeat-one queues the track that's already playing, so a URI
                // match on its own proves nothing — it has to be a *different*
                // URI from the one we started.
                val moved = castNextUrl != null &&
                    castNextUrl != castCurrentUrl &&
                    position.trackUri == castNextUrl
                if (moved) {
                    nextCastIndex()?.let { _index.value = it }
                    castCurrentUrl = castNextUrl
                    // The queued track was handed over whole, from its start.
                    castOffsetMs = 0L
                    _durationMs.value = _queue.value.getOrNull(_index.value)?.durationMs
                        ?: _durationMs.value
                    maybeScrobble()
                    queueNextOnRenderer(renderer)
                    stoppedPolls = 0
                }
                _positionMs.value = position.positionMs + castOffsetMs
                // The renderer only knows about the piece of the track it was
                // given, so a stream that started part-way in reports a short
                // duration. The track's own is the honest number.
                if (castOffsetMs == 0L && position.durationMs > 0) {
                    _durationMs.value = position.durationMs
                }
                if (position.positionMs > 0) settled = true
            }
            when (DlnaCast.state(renderer)) {
                DlnaState.PLAYING -> {
                    settled = true
                    stoppedPolls = 0
                    _isPlaying.value = true
                }
                DlnaState.PAUSED -> {
                    stoppedPolls = 0
                    _isPlaying.value = false
                }
                // Only treat STOPPED as end-of-track once the renderer has
                // actually started; it also reports STOPPED while loading.
                DlnaState.STOPPED -> if (settled && ++stoppedPolls >= STOPPED_POLLS_TO_ADVANCE) {
                    if (!advanceCast(renderer)) return
                    settled = false
                    stoppedPolls = 0
                }
                else -> stoppedPolls = 0
            }
        }
    }

    /** Move to the next queue entry on the renderer; false when the queue ends. */
    private suspend fun advanceCast(renderer: DlnaRenderer): Boolean {
        val next = when (_repeatMode.value) {
            RepeatMode.TRACK -> _index.value
            RepeatMode.QUEUE -> (_index.value + 1) % _queue.value.size.coerceAtLeast(1)
            RepeatMode.OFF -> _index.value + 1
        }
        if (next !in _queue.value.indices) {
            _isPlaying.value = false
            return false
        }
        _index.value = next
        return startCastTrack(renderer)
    }

    // --- internals -----------------------------------------------------------

    private fun setQueue(tracks: List<Track>, startIndex: Int) {
        val p = player ?: return
        _queue.value = tracks
        lastScrobbledId = null
        // Keep the local player loaded even while casting, so switching back to
        // this device resumes instantly instead of re-preparing the queue.
        p.setMediaQueue(tracks.map { it.toAudioItem() }, startIndex)
        _index.value = startIndex
        // A new queue plays from the start of whatever it opens on. Any offset
        // still set belongs to a stream that was seeked before this one, and
        // would be added to the clock of a track starting at 0:00 — which is how
        // a song opens at 0:49 with the audio at the very beginning. The index
        // collector can't be relied on to clear it: it only fires when the index
        // *changes*, and a new queue often starts on the same one.
        streamOffsetMs = 0
        // Picking something new to play is reason enough to try the server again.
        streamFailed.clear()
        // Right from the first frame, rather than whenever the stream gets round
        // to declaring a length — see the durationMs collector in ensurePlayer.
        tracks.getOrNull(startIndex)?.durationMs
            ?.takeIf { it > 0L }
            ?.let { _durationMs.value = it }
        val renderer = _castRenderer.value
        if (renderer != null) {
            p.pause()
            castJob?.cancel()
            castJob = scope.launch {
                if (startCastTrack(renderer)) pollRenderer(renderer)
            }
            return
        }
        p.play()
    }

    /** Sort [tracks] back into the relative order they had in [original]. */
    private fun restoreOrder(tracks: List<Track>, original: List<Track>): List<Track> {
        val rank = original.withIndex().associate { (i, t) -> t.id to i }
        // Tracks queued after shuffling have no pre-shuffle rank; keep them last.
        return tracks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    /**
     * A stream died — carry on from the downloaded copy, at the same second.
     *
     * Losing the network mid-track otherwise just stops the music: the player
     * reports an error, playback ends, and the user is left tapping play on a URL
     * that will keep failing. Where the track is downloaded, this rebuilds the
     * queue against local files and resumes at the position that was reached, so
     * walking out of Wi-Fi sounds like nothing happened.
     *
     * [forceOffline] latches until the server answers again, because the very
     * next track would otherwise be resolved to a URL and fail in the same way.
     */
    private fun fallBackOffline(serverAnswered: Boolean) {
        val p = player ?: return
        val track = _queue.value.getOrNull(_index.value) ?: return
        if (!serverAnswered) App.reportServerReachable(false)
        // Nothing local to fall back to: let the error stand rather than
        // restarting a stream that just failed.
        if (!localFiles.containsKey(track.id)) return
        // One attempt per track, so a file that is itself unplayable (a codec the
        // device can't decode) can't spin here.
        if (retriedTrackId == track.id) return
        retriedTrackId = track.id
        streamFailed += track.id
        // Only a server that never answered is grounds for pinning *everything*
        // to local files. That latch is released when the server answers again —
        // which never happens if we never called it unreachable.
        if (!serverAnswered) forceOffline = true
        val resumeAt = _positionMs.value
        scope.launch(Dispatchers.Main.immediate) {
            p.setMediaQueueAt(_queue.value.map { it.toAudioItem() }, _index.value, resumeAt)
            p.play()
        }
    }

    /**
     * Tell the server this session is playing, paused or stopped — see
     * [MusicServer.reportTimeline]. Fire-and-forget: a dropped heartbeat just
     * means the dashboard is stale until the next one, not a broken stream.
     */
    private fun reportTimeline(state: TimelineState) {
        val track = _queue.value.getOrNull(_index.value) ?: return
        // Nothing playing on the server's behalf to report on.
        if (LocalLibrary.isLocal(track.id)) return
        val client = serverClient.value ?: return
        val sid = sessionId
        val position = _positionMs.value
        val duration = _durationMs.value
        scope.launch { runCatching { client.reportTimeline(sid, track.id, state, position, duration) } }
    }

    /**
     * Close out a session that's about to be replaced by a fresh one — a new
     * [sessionId] with nothing telling Plex the old one is done leaves it
     * sitting in `/status/sessions` until its lease times out on its own, and
     * a track change every few minutes means those pile up faster than they
     * expire. Takes the outgoing id and track explicitly, since by the time
     * this runs [sessionId] has usually already moved on to the next one.
     */
    private fun reportTimelineStopped(oldSessionId: String, oldTrackId: String) {
        if (LocalLibrary.isLocal(oldTrackId)) return
        val client = serverClient.value ?: return
        scope.launch {
            runCatching {
                client.reportTimeline(oldSessionId, oldTrackId, TimelineState.STOPPED, 0L, 0L)
            }
        }
    }

    /**
     * Retire [sessionId] on whatever track it belonged to (if any) and mint a
     * fresh one for [newTrackId]. Every spot that used to just reassign
     * `sessionId = UUID.randomUUID().toString()` goes through this instead, so
     * the outgoing session is never simply dropped on the floor.
     */
    private fun mintSessionId(newTrackId: String) {
        sessionTrackId?.let { oldTrackId -> reportTimelineStopped(sessionId, oldTrackId) }
        sessionId = UUID.randomUUID().toString()
        sessionTrackId = newTrackId
    }

    private fun maybeScrobble() {
        val track = _queue.value.getOrNull(_index.value) ?: return
        // Nothing to tell, and nowhere to keep a play count: a local file's
        // history would be invented by this app and restorable by nothing.
        if (LocalLibrary.isLocal(track.id)) return
        if (track.id == lastScrobbledId) return
        lastScrobbledId = track.id
        // Stamped now, sent whenever: a play that happened offline still belongs
        // at the time it happened once the server hears about it.
        val at = System.currentTimeMillis()
        val client = serverClient.value
        scope.launch {
            // Local first, so the library's ordering moves with the listening
            // whether or not the server ever hears about this play.
            runCatching { App.library.markPlayed(track.id) }
            val sent = client != null && runCatching { client.scrobble(track.id, at) }.isSuccess
            // Guarded: a play can land before App has finished wiring itself up,
            // and a missed scrobble is not worth a crash.
            if (!sent) {
                runCatching {
                    App.pending.add(PendingAction(PendingAction.Kind.SCROBBLE, track.id, atMs = at))
                }
            }
        }
    }

    /**
     * The format to actually request.
     *
     * Low Data is about *cellular* data, not quality in general — on Wi-Fi there's
     * nothing to save, so the user's chosen format applies as normal.
     *
     * Off Wi-Fi it clamps to Opus rather than MP3: at 192 kbps Opus is 24 KB/s
     * against MP3 320's 40 KB/s, so it saves about 40% of the bytes *and* sounds
     * better. MP3 was only ever the compatibility-safe choice, and this device
     * decodes Opus fine.
     */
    private fun effectiveFormat(): StreamFormat {
        // From the source rather than the client: the format is a property of
        // which server you are on, and not every backend carries a config object.
        //
        // The connection picks between two stated choices rather than a data
        // mode quietly capping one. Low Data used to drop anything heavier than
        // Opus off Wi-Fi, which meant asking for lossless on cellular and being
        // given something else without being told — the substitution this app
        // has been getting rid of everywhere else. Whoever wants Opus on
        // cellular can now say so, and be believed.
        val source = App.source.value
        return if (onWifi) source.wifiFormat else source.cellularFormat
    }

    /**
     * Where to play a track from.
     *
     * Downloads win by default — that's the point of having them. A download is
     * abandoned only when the user's chosen streaming format is genuinely better
     * than the copy on disk, *and* the bytes are cheap: either Wi-Fi (whatever the
     * data mode, including Wi-Fi Only) or Make it Hurt, which buys quality on any
     * connection.
     *
     * Low Data needs no special case either: off Wi-Fi it pins [effectiveFormat]
     * to MP3, the lowest rank, so it can never outrank what was downloaded — and
     * on Wi-Fi it behaves like any other mode.
     */
    private fun Track.source(): LightAudioSource {
        // A track from the phone's own library is the file, always — there is no
        // stream to prefer and no server to ask.
        LocalLibrary.fileOf(id)?.let { return LightAudioSource.FileSource(it) }
        val local = localFiles[id]
        if (local != null) {
            val (file, downloadedFormat) = local
            val streamed = effectiveFormat()
            val worthTheBytes = onWifi || dataMode == DataMode.MAKE_IT_HURT
            val preferStream = !forceOffline && id !in streamFailed && worthTheBytes &&
                streamed.qualityRank > downloadedFormat.qualityRank
            if (!preferStream) return LightAudioSource.FileSource(file)
        }
        return LightAudioSource.UrlSource(
            serverClient.value?.streamUrl(this, effectiveFormat(), sessionId = sessionId).orEmpty(),
        )
    }

    private fun Track.toAudioItem(): LightAudioItem = LightAudioItem(
        source = source(),
        metadata = LightMediaMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
        ),
    )

    companion object {
        private const val PREVIOUS_RESTART_MS = 3_000L
        private const val CAST_POLL_MS = 1_000L

        /**
         * The most tracks the player is ever given at once, and how many already
         * played are kept behind the starting point.
         */
        private const val MAX_QUEUE = 500
        private const val QUEUE_LOOKBACK = 50

        /** One press of the rocker, as a fraction of full scale. */
        private const val VOLUME_KEY_STEP = 0.05f

        /** Consecutive STOPPED readings that mean the track really ended. */
        private const val STOPPED_POLLS_TO_ADVANCE = 2

        /** Settling time before re-arming the renderer's next track after an edit. */
        private const val CAST_REQUEUE_DEBOUNCE_MS = 400L
        private const val CAST_STOP_TIMEOUT_MS = 1_500L

        /** How often the queue snapshot is written while playing. */
        private const val SAVE_STATE_INTERVAL_MS = 5_000L
        private const val SAVE_STATE_TIMEOUT_MS = 1_000L

        /** How often a live session is re-announced; PlexAmp uses roughly this. */
        private const val TIMELINE_INTERVAL_MS = 10_000L
    }
}

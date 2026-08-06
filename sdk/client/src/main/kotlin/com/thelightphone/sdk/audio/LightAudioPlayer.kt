package com.thelightphone.sdk.audio

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays a queue of local, bundled, or remote audio with observable playback
 * state.
 *
 * Transient focus loss pauses and later resumes playback; duckable loss lowers
 * volume. Call [release] when the owning screen is destroyed.
 */
class LightAudioPlayer internal constructor(
    context: Context,
    usage: LightAudioUsage = LightAudioUsage.Music
) {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
    // SPIKE (background audio): retained app context to start the keep-alive service.
    private val appContext = context.applicationContext
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _isPlaying = MutableStateFlow(false)
    private val _currentMediaItemIndex = MutableStateFlow(NO_MEDIA_ITEM)
    private var positionJob: Job? = null
    private var pausedForTransientLoss = false
    private var released = false

    /** Current position in milliseconds, updated while playing. */
    val positionMs: StateFlow<Long> = _positionMs
    /** Resolved duration in milliseconds, or `0` while unknown/unavailable. */
    val durationMs: StateFlow<Long> = _durationMs
    /** Whether the platform is actively advancing playback. */
    val isPlaying: StateFlow<Boolean> = _isPlaying
    /** Current queue index, or `-1` when the queue is empty. */
    val currentMediaItemIndex: StateFlow<Int> = _currentMediaItemIndex

    // System media (STREAM_MUSIC) volume as a 0..1 fraction. Also reflects the
    // hardware volume keys, so a UI fader stays in sync with button presses.
    private val _deviceVolume = MutableStateFlow(1f)
    val deviceVolume: StateFlow<Float> = _deviceVolume

    private val player = ExoPlayer.Builder(context)
        .setDeviceVolumeControlEnabled(true)
        // SDK PATCH (additive, upstreamable): pause when the audio route goes
        // away — Bluetooth disconnecting, headphones unplugged. Media3 handles
        // ACTION_AUDIO_BECOMING_NOISY itself when this is set, and the default
        // is false: without it, pulling your headphones out or walking away
        // from a speaker starts playing out loud on the phone. A tool cannot
        // do this for itself — it needs a BroadcastReceiver, and the sandbox
        // blocks `android.content`.
        .setHandleAudioBecomingNoisy(true)
        .build().apply player@{
        setAudioAttributes(usage.toMedia3AudioAttributes(), false)
        addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // `this@player` is the ExoPlayer (Int index), not the wrapper's StateFlow.
                _currentMediaItemIndex.value = if (mediaItem == null) {
                    NO_MEDIA_ITEM
                } else {
                    this@player.currentMediaItemIndex
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startPositionUpdates()
                } else {
                    stopPositionUpdates()
                    updatePosition()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateDuration()
                updatePosition()
                if (playbackState == Player.STATE_ENDED) {
                    stopPositionUpdates()
                    abandonFocus()
                }
            }

            /**
             * SDK PATCH (additive, upstreamable): surface playback failures.
             *
             * Without this a tool has no way to know a stream died — the player
             * just stops, `isPlaying` goes false, and it is indistinguishable
             * from a pause. A player that can't report an error can't have a
             * fallback, which is what an offline-capable music tool needs when
             * the network drops mid-track.
             */
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError?.invoke(error)
            }

            override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
                updateDeviceVolume()
            }

            // Fires once the volume range is actually known — the first moment the
            // fader can show a truthful level.
            override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
                updateDeviceVolume()
            }
        })
    }

    /**
     * Called when playback fails. Set by the tool; see [Player.Listener.onPlayerError].
     */
    var onPlaybackError: ((PlaybackException) -> Unit)? = null

    private val focus = AudioFocusHelper(
        context = context,
        usage = usage,
        gainType = AudioManager.AUDIOFOCUS_GAIN,
        onFocusChange = ::onAudioFocusChange
    )

    // SPIKE (background audio + hardware volume keys): a MediaSession wrapping the
    // player gives the OS a session to route hardware volume keys to. Revert when
    // LightOS ships background audio + device-key trust for side-loaded tools.
    private val mediaSession = MediaSession.Builder(appContext, player)
        .apply {
            appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.let { launch ->
                setSessionActivity(
                    PendingIntent.getActivity(appContext, 0, launch, PendingIntent.FLAG_IMMUTABLE)
                )
            }
        }
        .build()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    init {
        // Publish the session, then connect a controller to LightMediaService so
        // it registers the session and manages the media notification + foreground
        // lifecycle (background audio + lock-screen controls). A MediaSessionService
        // only foregrounds once a controller connects — starting it directly would
        // time out. Seed the fader from the current system volume too.
        LightMediaService.activeSession = mediaSession
        val token = SessionToken(appContext, ComponentName(appContext, LightMediaService::class.java))
        controllerFuture = MediaController.Builder(appContext, token).buildAsync().also { future ->
            future.addListener(
                { mediaController = runCatching { future.get() }.getOrNull() },
                ContextCompat.getMainExecutor(appContext),
            )
        }
        updateDeviceVolume()
    }

    /** Set the system media volume from a 0..1 fraction. */
    fun setSystemVolume(fraction: Float) {
        val info = player.deviceInfo
        val range = info.maxVolume - info.minVolume
        if (range <= 0) return
        val target = info.minVolume + (fraction.coerceIn(0f, 1f) * range).roundToInt()
        player.setDeviceVolume(target.coerceIn(info.minVolume, info.maxVolume), 0)
    }

    private fun updateDeviceVolume() {
        val info = player.deviceInfo
        val range = info.maxVolume - info.minVolume
        // A zero range means the player hasn't resolved its device info yet (it
        // starts out as DeviceInfo.UNKNOWN). Publishing 0f there would claim the
        // phone is silent — and it would stick, because the only other trigger is
        // a volume *change*. Leave the value be and wait for onDeviceInfoChanged.
        if (range <= 0) return
        _deviceVolume.value =
            ((player.deviceVolume - info.minVolume).toFloat() / range).coerceIn(0f, 1f)
    }

    /** Playback rate, clamped to a minimum positive rate. */
    var speed: Float = 1.0f
        set(value) {
            field = value.coerceAtLeast(MIN_SPEED)
            player.playbackParameters = PlaybackParameters(field)
        }

    /** Enables the platform player's silence-skipping behavior. */
    var skipSilence: Boolean = false
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        set(value) {
            field = value
            player.skipSilenceEnabled = value
        }

    /** When `true`, playback pauses at the end of each queue item instead of advancing. */
    var pauseAtEndOfMediaItems: Boolean = false
        @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
        set(value) {
            field = value
            player.pauseAtEndOfMediaItems = value
        }

    /** Replaces the queue with [file] and prepares it for playback. */
    fun setSource(file: File) {
        setQueue(listOf(file), metadata = null)
    }

    internal fun setQueue(files: List<File>, metadata: LightMediaMetadata?) {
        setMediaQueue(files.map { file ->
            LightAudioItem(
                source = LightAudioSource.FileSource(file),
                metadata = metadata ?: LightMediaMetadata(file.nameWithoutExtension),
            )
        })
    }

    /**
     * Replaces and prepares the queue, selecting [startIndex]. An empty list
     * clears playback and ignores [startIndex].
     *
     * @throws IllegalArgumentException when a non-empty queue has an invalid
     *   [startIndex]
     */
    fun setMediaQueue(items: List<LightAudioItem>, startIndex: Int = 0) {
        if (items.isEmpty()) {
            player.clearMediaItems()
            _currentMediaItemIndex.value = NO_MEDIA_ITEM
            updateDuration()
            updatePosition()
            return
        }
        require(startIndex in items.indices) { "Start index must reference a queue item" }
        val mediaItems = items.mapIndexed { index, item -> item.toMediaItem(index) }
        player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        _currentMediaItemIndex.value = startIndex
        player.prepare()
        updateDuration()
        updatePosition()
    }

    /**
     * ADDITIVE PATCH — like [setMediaQueue], but starts [startIndex] at
     * [startPositionMs] instead of at its beginning.
     *
     * Needed because [seekTo] clamps to `player.duration`, which is
     * `C.TIME_UNSET` until the item has been buffered — so seeking straight after
     * preparing a queue silently lands on 0. ExoPlayer takes a start position at
     * prepare time precisely for this case (resuming where the user left off), and
     * without it that is not expressible through the SDK.
     */
    fun setMediaQueueAt(items: List<LightAudioItem>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) {
            setMediaQueue(items, 0)
            return
        }
        require(startIndex in items.indices) { "Start index must reference a queue item" }
        val mediaItems = items.mapIndexed { index, item -> item.toMediaItem(index) }
        player.setMediaItems(mediaItems, startIndex, startPositionMs.coerceAtLeast(0L))
        _currentMediaItemIndex.value = startIndex
        player.prepare()
        updateDuration()
        updatePosition()
    }

    /**
     * Start or resume playback if audio focus is available.
     *
     * Observe [isPlaying] for the actual playback state.
     */
    fun play() {
        if (released || !focus.request()) {
            return
        }
        player.play()
    }

    /** Pauses playback and abandons audio focus. */
    fun pause() {
        pausedForTransientLoss = false
        player.pause()
        abandonFocus()
    }

    /** Stops playback, returns to position zero, and abandons audio focus. */
    fun stop() {
        pausedForTransientLoss = false
        player.stop()
        player.seekTo(0L)
        updatePosition()
        abandonFocus()
    }

    /** Seeks to [ms], clamped to the resolved duration. Unknown duration clamps to zero. */
    fun seekTo(ms: Long) {
        player.seekTo(ms.coerceIn(0L, player.duration.validDuration()))
        updatePosition()
    }

    /** Seeks backward 15 seconds, clamped to the item bounds. */
    fun skipBack() {
        seekTo(skipPosition(positionMs.value, durationMs.value, -SKIP_INTERVAL_MS))
    }

    /** Seeks forward 15 seconds, clamped to the item bounds. */
    fun skipForward() {
        seekTo(skipPosition(positionMs.value, durationMs.value, SKIP_INTERVAL_MS))
    }

    /** Selects the next queue item when one exists. */
    fun skipToNext() {
        player.seekToNextMediaItem()
    }

    /** Selects the previous queue item when one exists. */
    fun skipToPrevious() {
        player.seekToPreviousMediaItem()
    }

    // --- Incremental queue editing (sublunar additions) ----------------------
    // These delegate to the platform player so the queue can be edited without
    // rebuilding it (which would re-buffer and interrupt the current item).
    // Purely additive; upstreamable to the SDK.

    /** Appends items to the end of the queue without interrupting playback. */
    fun addItems(items: List<LightAudioItem>) {
        if (items.isEmpty()) return
        val base = player.mediaItemCount
        player.addMediaItems(items.mapIndexed { offset, item -> item.toMediaItem(base + offset) })
        _currentMediaItemIndex.value = player.currentMediaItemIndex
    }

    /** Inserts [item] at [index] (clamped) without interrupting playback. */
    fun addItemAt(index: Int, item: LightAudioItem) {
        val at = index.coerceIn(0, player.mediaItemCount)
        player.addMediaItem(at, item.toMediaItem(at))
        _currentMediaItemIndex.value = player.currentMediaItemIndex
    }

    /** Removes the queue item at [index] without interrupting the current item. */
    /**
     * SDK PATCH (additive, upstreamable): swap out a range of the queue.
     *
     * Reordering by repeated [moveItem] is a timeline update per move, and a few
     * hundred of those lock the main thread — with a media session attached, the
     * legacy queue is rebuilt (artwork and all) each time and the process runs out
     * of memory. `replaceMediaItems` does it once, and leaves an item outside the
     * range playing untouched.
     */
    fun replaceRange(fromIndex: Int, toIndex: Int, items: List<LightAudioItem>) {
        scope.launch {
            val mediaItems = items.mapIndexed { index, item -> item.toMediaItem(fromIndex + index) }
            player.replaceMediaItems(fromIndex, toIndex, mediaItems)
        }
    }

    fun removeItem(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        player.removeMediaItem(index)
        _currentMediaItemIndex.value =
            if (player.mediaItemCount == 0) NO_MEDIA_ITEM else player.currentMediaItemIndex
    }

    /** Moves a queue item, keeping the current item playing. */
    fun moveItem(from: Int, to: Int) {
        val count = player.mediaItemCount
        if (from !in 0 until count || to !in 0 until count || from == to) return
        player.moveMediaItem(from, to)
        _currentMediaItemIndex.value = player.currentMediaItemIndex
    }

    /** Jumps playback to the queue item at [index], starting from its beginning. */
    fun seekToIndex(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        player.seekToDefaultPosition(index)
        _currentMediaItemIndex.value = player.currentMediaItemIndex
    }

    /**
     * Loop mode using Media3 constants: [Player.REPEAT_MODE_OFF],
     * [Player.REPEAT_MODE_ONE], or [Player.REPEAT_MODE_ALL].
     */
    var repeatMode: Int
        get() = player.repeatMode
        set(value) { player.repeatMode = value }

    /** Permanently releases playback, focus, and state-update resources. Idempotent. */
    fun release() {
        if (released) return
        released = true
        stopPositionUpdates()
        abandonFocus()
        mediaController?.release()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        LightMediaService.activeSession = null
        mediaSession.release()
        player.release()
        scope.cancel()
    }


    private fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForTransientLoss = false
                scope.launch { player.pause() }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedForTransientLoss = player.isPlaying
                scope.launch { player.pause() }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player.volume = DUCKED_VOLUME
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                player.volume = FULL_VOLUME
                if (pausedForTransientLoss) {
                    pausedForTransientLoss = false
                    scope.launch { play() }
                }
            }
        }
    }

    private fun abandonFocus() {
        focus.abandon()
    }

    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                updatePosition()
                updateDuration()
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun updatePosition() {
        _positionMs.value = player.currentPosition.coerceAtLeast(0L)
    }

    private fun updateDuration() {
        _durationMs.value = player.duration.validDuration()
    }
}

internal fun LightAudioItem.toMediaItem(queueIndex: Int): MediaItem {
    val uri = Uri.parse(source.uriString())
    return MediaItem.Builder()
        .setUri(uri)
        .setMediaId(uri.toString())
        .setMediaMetadata(metadata.toMedia3Metadata(queueIndex))
        .build()
}

internal fun LightAudioSource.uriString(): String = when (this) {
    is LightAudioSource.FileSource -> Uri.fromFile(file).toString()
    is LightAudioSource.AssetSource -> "asset:///${assetPath.trimStart('/')}"
    is LightAudioSource.UrlSource -> url
}

private fun LightMediaMetadata.toMedia3Metadata(queueIndex: Int): MediaMetadata {
    return MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setDurationMs(durationMs)
        .setTrackNumber(queueIndex + 1)
        .build()
}

internal fun skipPosition(positionMs: Long, durationMs: Long, deltaMs: Long): Long {
    return (positionMs + deltaMs).coerceIn(0L, durationMs.validDuration())
}

private fun Long.validDuration(): Long = takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L

private const val SKIP_INTERVAL_MS = 15_000L
private const val POSITION_POLL_MS = 250L
private const val MIN_SPEED = 0.1f
private const val DUCKED_VOLUME = 0.2f
private const val FULL_VOLUME = 1.0f
private const val NO_MEDIA_ITEM = -1

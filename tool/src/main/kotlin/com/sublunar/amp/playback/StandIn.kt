package com.sublunar.amp.playback

import android.os.SystemClock
import androidx.media3.common.Player
import com.sublunar.amp.data.Track
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's own player while something else has the sound.
 *
 * The media session belongs to the phone's player, and the session is what a
 * launcher's playback controls, Bluetooth buttons and LightOS read. Left
 * paused while casting, it told all of them the phone was stopped at 0:00 on
 * whatever song casting began with, and their buttons went to a player that
 * makes no sound. So while an output plays, the player plays silence instead:
 * one item, named and timed like the output's song, kept at the output's
 * position and play state. Whatever reaches it that Amp didn't do itself is a
 * press, and is handed back through [press] to go where the sound is.
 *
 * Playing, it also keeps media3's service in the foreground. Without that,
 * Android froze Amp ten seconds after it left the screen, and the speaker
 * played out its song and stopped with nobody to give it the next one.
 *
 * Main thread only, like the player.
 */
internal class StandIn(
    private val scope: CoroutineScope,
    private val dir: File,
    private val queue: StateFlow<List<Track>>,
    private val index: StateFlow<Int>,
    private val isPlaying: StateFlow<Boolean>,
    private val positionMs: StateFlow<Long>,
    private val item: (Track, LightAudioSource) -> LightAudioItem,
    private val press: (StandInPress) -> Unit,
) {
    /**
     * The output has the sound, so the stand-in belongs in the player,
     * whichever handle that is. The queue is the controller's alone until
     * [stop] puts it back.
     */
    @Volatile
    var active = false
        private set

    /** The handle it plays on, when there is one. */
    private var held: LightAudioPlayer? = null

    private val watch = StandInWatch()
    private var following: Job? = null
    private var watching: Job? = null

    /** The song the player has silence for, once it has it. */
    private var trackId: String? = null
    private var lengthMs = 0L

    /** The song silence is being made for. */
    private var wantedId: String? = null

    /** Bumped per song and per lost handle, so a file made too late isn't put in. */
    private var generation = 0

    // What the output last reported, and when, carried forward to where it
    // will have got to by the time a new song's silence is ready.
    private var outputPos = 0L
    private var outputAt = 0L
    private var outputPlaying = false

    /** A press was passed on: the output's reports lag it, and mustn't drag the stand-in back. */
    private var graceUntil = Long.MIN_VALUE

    /** The output has the sound from here on: stand in on [p], and on every handle after it. */
    fun start(p: LightAudioPlayer?) {
        // A switch from one output to another: already standing in.
        if (active) return
        active = true
        // Read now, so the first song's silence goes in where the output is.
        outputPos = positionMs.value
        outputAt = SystemClock.elapsedRealtime()
        outputPlaying = isPlaying.value
        // Posted rather than immediate: a new queue and its index are set one
        // after the other, and an immediate collector would stand in, for a
        // moment, for the old index in the new queue.
        following = scope.launch(Dispatchers.Main) {
            launch { combine(queue, index) { q, i -> q.getOrNull(i) }.collect(::onTrack) }
            launch { isPlaying.collect(::onPlaying) }
            launch { positionMs.collect(::onPosition) }
        }
        p?.let(::bind)
    }

    /**
     * Stand in on [p]: the first handle, or a new activity's. Put in afresh
     * either way, because the service may have stopped and come back empty in
     * between.
     */
    fun bind(p: LightAudioPlayer) {
        if (!active) return
        held = p
        wantedId = null
        current()?.let { put(p, it) }
        watch(p)
    }

    /** The handle is gone. The next one gets [bind]. */
    fun unbind() {
        held = null
        generation++
        watching?.cancel()
        watching = null
        trackId = null
        wantedId = null
    }

    /**
     * The sound is back on the phone, or stopped: leave the player, paused,
     * for the queue to be put back. Paused even when nothing replaces it, as
     * silence left playing would hold media3's service in the foreground for
     * as long as the process lives.
     */
    fun stop() {
        if (!active) return
        active = false
        following?.cancel()
        following = null
        val p = held
        unbind()
        p?.pause()
        android.util.Log.i(TAG, "stood down")
    }

    private fun current(): Track? = queue.value.getOrNull(index.value)

    private fun onTrack(track: Track?) {
        val p = held ?: return
        if (track == null) {
            clear(p)
            return
        }
        if (track.id == wantedId) return
        // Another song, which starts at its beginning. Its silence put in at
        // the old one's place stood there until the output's next report.
        if (wantedId != null) {
            outputPos = 0L
            outputAt = SystemClock.elapsedRealtime()
        }
        put(p, track)
    }

    /**
     * Nothing to stand in for — the queue went, a source switch keeping the
     * speaker for the next one: the player is emptied, as a stopped phone's is.
     */
    private fun clear(p: LightAudioPlayer) {
        if (wantedId == null && trackId == null) return
        generation++
        trackId = null
        wantedId = null
        p.setMediaQueue(emptyList())
        android.util.Log.i(TAG, "nothing to stand in for")
    }

    private fun onPlaying(playing: Boolean) {
        outputPlaying = playing
        val p = held ?: return
        if (trackId == null || playing == watch.wanted) return
        watch.expect(p.positionMs.value, playing, SystemClock.elapsedRealtime())
        if (playing) p.play() else p.pause()
    }

    private fun onPosition(positionMs: Long) {
        val now = SystemClock.elapsedRealtime()
        outputPos = positionMs
        outputAt = now
        val p = held ?: return
        // An unprepared item clamps a seek to 0, which the next reading would
        // take for a press back to the top.
        if (trackId == null || now < graceUntil || p.durationMs.value <= 0L) return
        if (abs(p.positionMs.value - positionMs) <= RESYNC_MS) return
        watch.expect(positionMs, watch.wanted, now)
        p.seekTo(positionMs)
    }

    private fun put(p: LightAudioPlayer, track: Track) {
        val gen = ++generation
        // The old song's silence is no longer the output's: nothing moves it,
        // and nothing it does is a press, until the new one is in.
        trackId = null
        wantedId = track.id
        val length = track.durationMs.takeIf { it > 0L } ?: UNKNOWN_LENGTH_MS
        scope.launch(Dispatchers.IO) {
            val file = try {
                Silence.fileFor(dir, length)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "no silence for ${track.id}: $e")
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                if (gen != generation || held !== p) return@withContext
                val now = SystemClock.elapsedRealtime()
                val at = (outputPos + if (outputPlaying) now - outputAt else 0L).coerceAtLeast(0L)
                trackId = track.id
                lengthMs = Silence.lengthOf(length)
                watch.expect(at, outputPlaying, now)
                // The output repeats, not this. A wrap here would read as a
                // press back to the top.
                p.repeatMode = Player.REPEAT_MODE_OFF
                p.setMediaQueueAt(listOf(item(track, LightAudioSource.FileSource(file))), 0, at)
                if (outputPlaying) p.play() else p.pause()
                android.util.Log.i(TAG, "standing in for ${track.id} at=$at playing=$outputPlaying")
                // Only now: until the new item was in, the player could still
                // reopen the old file.
                scope.launch(Dispatchers.IO) { if (gen == generation) Silence.prune(dir, keep = file) }
            }
        }
    }

    private fun watch(p: LightAudioPlayer) {
        watching?.cancel()
        watching = scope.launch(Dispatchers.Main) {
            combine(p.isPlaying, p.positionMs) { playing, pos -> playing to pos }.collect { (playing, pos) ->
                if (held !== p || trackId == null) return@collect
                val now = SystemClock.elapsedRealtime()
                val presses = watch.read(pos, playing, now, lengthMs)
                if (presses.isEmpty()) return@collect
                graceUntil = now + PRESS_GRACE_MS
                for (it in presses) {
                    android.util.Log.i(TAG, "press $it")
                    press(it)
                }
            }
        }
    }

    private companion object {
        const val TAG = "AmpStandIn"

        /** Further apart than the output's once-a-second reports ever drift. */
        const val RESYNC_MS = 3_000L

        /** Long enough for a speaker to answer a press and report it. */
        const val PRESS_GRACE_MS = 4_000L

        /** For a song the library has no length for. */
        const val UNKNOWN_LENGTH_MS = 10 * 60_000L
    }
}

/** What a press on the stand-in asks the output for. */
internal sealed interface StandInPress {
    data object Play : StandInPress

    data object Pause : StandInPress

    data class SeekTo(val positionMs: Long) : StandInPress
}

/**
 * Tells Amp's own moves of the stand-in from anybody else's.
 *
 * Everything Amp does to the stand-in comes back as readings too: a play it
 * asked for, a seek that keeps it level with the speaker, a new song. Amp
 * marks each with [expect], and readings within [QUIET_MS] of it are its echo:
 * they only move the baseline. After that, a press is a *change* from the
 * last reading: playing to paused, paused to playing, or a jump in position.
 * A mismatch alone is never one, so a stand-in that failed to start, never
 * seen playing, can't pause the speaker.
 */
internal class StandInWatch {
    /** Whether the stand-in should be playing, by Amp's say or a press's. */
    var wanted = false
        private set

    private var quietUntil = Long.MIN_VALUE

    private var lastPos = 0L
    private var lastAt = 0L
    private var lastPlaying = false

    /** Amp is moving the stand-in to [positionMs], [playing] or not. */
    fun expect(positionMs: Long, playing: Boolean, nowMs: Long) {
        wanted = playing
        quietUntil = nowMs + QUIET_MS
        lastPos = positionMs
        lastAt = nowMs
    }

    /** One reading of the stand-in, [lengthMs] long; any presses it shows, play state first. */
    fun read(positionMs: Long, playing: Boolean, nowMs: Long, lengthMs: Long): List<StandInPress> {
        if (nowMs < quietUntil) {
            remember(positionMs, playing, nowMs)
            return emptyList()
        }
        val presses = mutableListOf<StandInPress>()
        val ending = lengthMs > 0L && lastPos >= lengthMs - END_SLACK_MS
        val atEnd = lengthMs > 0L && positionMs >= lengthMs - END_SLACK_MS
        if (playing && !lastPlaying && !wanted) {
            wanted = true
            presses += StandInPress.Play
        } else if (!playing && lastPlaying && wanted && !atEnd) {
            wanted = false
            presses += StandInPress.Pause
        }
        // Ahead of where it could have played to, or behind where it was. A
        // stall falls between the two and is not a press. Nor is coming back
        // round from the end.
        val reachable = lastPos + if (lastPlaying) nowMs - lastAt else 0L
        val wrapped = ending && positionMs < END_SLACK_MS
        if (positionMs > reachable + JUMP_MS || (positionMs < lastPos - JUMP_MS && !wrapped)) {
            presses += StandInPress.SeekTo(positionMs)
        }
        remember(positionMs, playing, nowMs)
        return presses
    }

    private fun remember(positionMs: Long, playing: Boolean, nowMs: Long) {
        lastPos = positionMs
        lastAt = nowMs
        lastPlaying = playing
    }

    internal companion object {
        /** A silent file plays within a few hundred milliseconds of being put in. */
        const val QUIET_MS = 1_500L

        /** Well past the jitter of the player's quarter-second readings. */
        const val JUMP_MS = 2_000L

        /** Close enough to the end that stopping there is the end, not a pause. */
        const val END_SLACK_MS = 1_500L
    }
}

/**
 * Silent MP3s for [StandIn], one per length.
 *
 * Every frame is the same 96 bytes: MPEG-1 Layer III, 48 kHz, mono, 32 kbps,
 * with no side info and no audio data, which any MP3 decoder plays as digital
 * silence. Constant bitrate and no header, so the player works the length out
 * from the size, and seeks by arithmetic.
 */
internal object Silence {
    /** 1152 samples at 48 kHz. */
    const val FRAME_MS = 24L

    private val frame = ByteArray(96).apply {
        this[0] = 0xFF.toByte()
        this[1] = 0xFB.toByte()
        this[2] = 0x14
        this[3] = 0xC0.toByte()
    }

    /** How long a file made for [lengthMs] plays: whole frames, never shorter. */
    fun lengthOf(lengthMs: Long): Long = framesFor(lengthMs) * FRAME_MS

    /** A file in [dir] that plays silence for [lengthMs], made the first time it's asked for. */
    @Synchronized
    fun fileFor(dir: File, lengthMs: Long): File {
        val frames = framesFor(lengthMs)
        val file = File(dir, "silence-$frames.mp3")
        if (file.length() == frames * frame.size) return file
        dir.mkdirs()
        val part = File(dir, "${file.name}.part")
        part.outputStream().buffered(1 shl 16).use { out -> repeat(frames.toInt()) { out.write(frame) } }
        if (!part.renameTo(file)) error("couldn't keep ${file.name}")
        return file
    }

    /** Delete the finished files in [dir], all but [keep]. */
    @Synchronized
    fun prune(dir: File, keep: File) {
        dir.listFiles()?.forEach { if (it != keep && it.name.endsWith(".mp3")) it.delete() }
    }

    private fun framesFor(lengthMs: Long): Long = (lengthMs.coerceAtLeast(1L) + FRAME_MS - 1) / FRAME_MS
}

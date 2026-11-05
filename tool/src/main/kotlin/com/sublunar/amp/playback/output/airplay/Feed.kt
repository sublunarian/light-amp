package com.sublunar.amp.playback.output.airplay

import com.sublunar.amp.data.Track
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** One track decoded to PCM, a block at a time. */
internal interface PcmSource : Closeable {
    val sampleRate: Int
    val channels: Int
    val order: ChannelOrder

    /** Where in the track the first block starts, in ms: a stream the server sought starts late. */
    val startMs: Long

    /**
     * The next block into [block]; false at the end of the track. Blocks
     * while its bytes are on their way, and throws when they won't come.
     */
    fun next(block: PcmBlock): Boolean
}

/** Interleaved PCM: 16-bit samples when [isShort], floats in ±1 otherwise. */
internal class PcmBlock {
    var frames = 0
    var isShort = true
    var shorts = ShortArray(0)
    var floats = FloatArray(0)

    fun shorts(samples: Int): ShortArray {
        if (shorts.size < samples) shorts = ShortArray(samples)
        isShort = true
        return shorts
    }

    fun floats(samples: Int): FloatArray {
        if (floats.size < samples) floats = FloatArray(samples)
        isShort = false
        return floats
    }
}

/** A track as it was laid into the stream: its first frame, counted from the start of the run. */
internal data class Segment(val firstFrame: Long, val row: Int, val track: Track, val startMs: Long)

/**
 * The queue, decoded and laid end to end as AirPlay's packets: CD audio,
 * 352 frames each, as ALAC. One feed per run of playing — a start, a seek, a
 * resume — from [first] at [fromMs], on to whatever follows each track,
 * with no gap between them: the next track's first samples fill the packet
 * the last one ended in.
 *
 * Runs on its own thread a few seconds ahead of the speaker, and waits when
 * that far ahead. 44.1 kHz 16-bit stereo goes into the packets as it came
 * out of the file; anything else goes through [Resampler] and [Dither].
 */
internal class Feed(
    private val open: (Track, Long) -> PcmSource,
    /** The row and track that follow the one at [Segment.row] — repeat and the end of the queue in mind — or null. */
    private val following: (Segment) -> Pair<Int, Track>?,
    private val gainFor: (Track) -> Float,
    private val log: (String) -> Unit,
    private val first: Pair<Int, Track>,
    private val fromMs: Long,
    /** Told of the track that follows as each one starts, so its bytes can be on their way before they're needed. */
    private val prefetch: (Track) -> Unit = {},
) {
    val packets = ArrayBlockingQueue<ByteArray>(CAPACITY_PACKETS)
    val segments = CopyOnWriteArrayList<Segment>()

    /** No more packets will come, and why: null while they still may. */
    @Volatile
    var ended: End? = null
        private set

    enum class End {
        /** The queue ran out: what [following] said. */
        QUEUE,

        /** The first track couldn't be read at all: nothing was laid. */
        UNREADABLE,

        /** A track stopped coming partway, or a following one couldn't be read. */
        BROKE,

        /** [stop] was called. */
        STOPPED,
    }

    /** The row and track that broke, where one did: a following track that wouldn't open, or one that gave nothing. */
    @Volatile
    var brokenAt: Pair<Int, Track>? = null
        private set

    @Volatile
    private var stopped = false

    private val thread = Thread({ run() }, "AmpAirPlay-decode").apply { isDaemon = true }

    // The packet being filled, in interleaved stereo shorts.
    private val pending = ShortArray(AlacFrames.FRAMES_PER_PACKET * 2)
    private var pendingFrames = 0

    /** Frames laid into packets so far, counted from the run's start. */
    private var framesOut = 0L

    fun start() = thread.start()

    /**
     * Stop, from any thread. The decode thread closes its own decoder on its
     * way out: closing it from here could free the codec's buffers under a
     * copy in progress.
     */
    fun stop() {
        stopped = true
        thread.interrupt()
    }

    /** Frames decoded and waiting to be sent, roughly: what the sender has in hand. */
    val bufferedFrames: Int get() = packets.size * AlacFrames.FRAMES_PER_PACKET

    private fun run() {
        var next: Pair<Int, Track>? = first
        var startMs = fromMs
        var stage: RateStage? = null
        val block = PcmBlock()
        val stereo = FloatArray(BLOCK_FRAMES_MAX * 2)
        var end = End.QUEUE
        try {
            while (next != null && !stopped) {
                val (row, track) = next
                val source = try {
                    open(track, startMs)
                } catch (e: Exception) {
                    if (stopped) break
                    log("couldn't read row $row (${e::class.simpleName})")
                    end = if (segments.isEmpty()) End.UNREADABLE else End.BROKE
                    brokenAt = row to track
                    break
                }
                var framesIn = 0L
                try {
                    val gain = gainFor(track)
                    val exact = source.sampleRate == AIRPLAY_RATE && source.channels == 2 && gain >= 1f
                    // A run of tracks at one rate goes through one resampler,
                    // so nothing at their joins is cut short.
                    if (stage != null && (exact || stage.inRate != source.sampleRate)) {
                        drain(stage)
                        stage = null
                    }
                    if (!exact && stage == null && source.sampleRate != AIRPLAY_RATE) stage = RateStage(Resampler(source.sampleRate), framesOut)
                    val firstFrame = stage?.frameAtInput() ?: framesOut
                    val segment = Segment(firstFrame, row, track, source.startMs)
                    segments += segment
                    // Not the same track again (repeat-one): the door ends one
                    // fetch of a track when a second starts, and this is that one.
                    following(segment)?.let { (_, after) -> if (after.id != track.id) runCatching { prefetch(after) } }
                    log(
                        "row $row: ${source.sampleRate} Hz, ${source.channels} ch, from ${source.startMs} ms" +
                            if (exact) ", sent as it is" else if (stage != null) ", resampled" else ", dithered",
                    )
                    val dither = Dither()
                    while (!stopped) {
                        if (!source.next(block)) break
                        if (block.frames == 0) continue
                        framesIn += block.frames
                        if (exact && block.isShort) {
                            lay(block.shorts, block.frames)
                            continue
                        }
                        // Everything else as stereo floats, gain applied.
                        val floats = if (block.isShort) {
                            val f = block.floats(block.frames * source.channels)
                            for (i in 0 until block.frames * source.channels) f[i] = block.shorts[i] / 32768f
                            f
                        } else {
                            block.floats
                        }
                        var at = 0
                        while (at < block.frames) {
                            val n = minOf(BLOCK_FRAMES_MAX, block.frames - at)
                            toStereo(floats.sliceFrom(at * source.channels, n * source.channels), n, source.channels, source.order, stereo)
                            if (gain < 1f) for (i in 0 until n * 2) stereo[i] *= gain
                            if (stage != null) {
                                stage.push(stereo, n)
                                pullInto(stage, dither)
                            } else {
                                layFloats(stereo, n, dither)
                            }
                            at += n
                        }
                    }
                } finally {
                    runCatching { source.close() }
                }
                if (stopped) break
                // A track that gave nothing from its start is broken, not
                // over: under repeat-one it would be opened again for ever.
                if (framesIn == 0L && startMs == 0L) {
                    log("row $row gave no sound")
                    segments.removeAt(segments.size - 1)
                    end = if (framesOut == 0L) End.UNREADABLE else End.BROKE
                    brokenAt = row to track
                    break
                }
                startMs = 0
                next = following(segments.last())
            }
            if (!stopped) {
                stage?.let { drain(it) }
                // The last packet goes out whole, its end silent.
                if (pendingFrames > 0) {
                    pending.fill(0, pendingFrames * 2)
                    pendingFrames = AlacFrames.FRAMES_PER_PACKET
                    emit()
                }
            }
        } catch (_: InterruptedException) {
            end = End.STOPPED
        } catch (e: Exception) {
            if (!stopped) {
                log("decoding stopped (${e::class.simpleName})")
                end = if (segments.isEmpty() || framesOut == 0L) End.UNREADABLE else End.BROKE
            }
        } finally {
            ended = if (stopped) End.STOPPED else end
        }
    }

    private val scratch = ShortArray(BLOCK_FRAMES_MAX * 2)
    private val resampled = FloatArray(BLOCK_FRAMES_MAX * 2)

    private fun pullInto(stage: RateStage, dither: Dither) {
        while (true) {
            val n = stage.pull(resampled, BLOCK_FRAMES_MAX)
            if (n == 0) return
            softClip(resampled, n * 2)
            layFloats(resampled, n, dither)
        }
    }

    private fun drain(stage: RateStage) {
        stage.drain()
        val dither = Dither()
        while (true) {
            val n = stage.pullToEnd(resampled, BLOCK_FRAMES_MAX)
            if (n == 0) return
            softClip(resampled, n * 2)
            layFloats(resampled, n, dither)
        }
    }

    private fun layFloats(stereo: FloatArray, frames: Int, dither: Dither) {
        dither.toShorts(stereo, frames, scratch)
        lay(scratch, frames)
    }

    /** [frames] stereo frames of [pcm] into packets. */
    private fun lay(pcm: ShortArray, frames: Int) {
        var at = 0
        while (at < frames) {
            val n = minOf(frames - at, AlacFrames.FRAMES_PER_PACKET - pendingFrames)
            System.arraycopy(pcm, at * 2, pending, pendingFrames * 2, n * 2)
            pendingFrames += n
            at += n
            framesOut += n
            if (pendingFrames == AlacFrames.FRAMES_PER_PACKET) emit()
        }
    }

    private fun emit() {
        val frame = ByteArray(AlacFrames.PACKET_BYTES)
        AlacFrames.escapeFrame(pending, 0, frame)
        pendingFrames = 0
        while (!stopped) {
            if (packets.offer(frame, 200, TimeUnit.MILLISECONDS)) return
        }
        throw InterruptedException()
    }

    /** A resampler and where its output began in the run, so a track's first frame can be placed exactly. */
    private class RateStage(val resampler: Resampler, private val outBase: Long) {
        val inRate: Int get() = resampler.inRate
        private var inputFrames = 0L
        private var pulled = 0L

        /** The run frame the next input frame will land at. */
        fun frameAtInput(): Long = outBase + ceilDiv(inputFrames * AIRPLAY_RATE, inRate.toLong())

        fun push(stereo: FloatArray, frames: Int) {
            resampler.push(stereo, frames)
            inputFrames += frames
        }

        fun pull(out: FloatArray, capacity: Int): Int = resampler.pull(out, capacity).also { pulled += it }

        fun drain() = resampler.drain()

        /** After [drain]: what is left of the input's span, and not a frame past it. */
        fun pullToEnd(out: FloatArray, capacity: Int): Int {
            val owed = ceilDiv(inputFrames * AIRPLAY_RATE, inRate.toLong()) - pulled
            if (owed <= 0) return 0
            return pull(out, minOf(capacity.toLong(), owed).toInt())
        }

        private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b
    }

    private companion object {
        /**
         * About six seconds of packets: what covers the next track's opening
         * (a transcode starting, an MP4 whose index is at its end) and the
         * network's stumbles.
         */
        const val CAPACITY_PACKETS = 750
        const val BLOCK_FRAMES_MAX = 4096
    }
}

/** [length] samples of this array from [from], copied only when they don't start at 0. */
private fun FloatArray.sliceFrom(from: Int, length: Int): FloatArray =
    if (from == 0) this else copyOfRange(from, from + length)

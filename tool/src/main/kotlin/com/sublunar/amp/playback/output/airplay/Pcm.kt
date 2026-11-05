package com.sublunar.amp.playback.output.airplay

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sound AirPlay 1 carries: CD audio, 44.1 kHz, 16-bit, stereo. Anything
 * else is brought to it here — resampled ([Resampler]), dithered down to
 * 16 bits ([Dither]) and folded to two channels ([toStereo]). Audio already
 * at 44.1 kHz and 16 bits never comes through here at all: it goes out as
 * the file holds it.
 */
const val AIRPLAY_RATE = 44_100

/**
 * Sample-rate conversion by a windowed sinc, one interpolation filter per
 * phase of the ratio between the rates (147 of them from 48 kHz).
 *
 * Flat to 20 kHz at 44.1 kHz out, at least 90 dB down from 22.05 kHz, where
 * anything left would fold back into hearing. Converting up, the band ends
 * just short of the input's own Nyquist instead.
 *
 * Streaming: [push] what arrives, [pull] what can be made. The filter looks
 * half its length ahead, so the last few milliseconds come out only at
 * [drain]. Interleaved stereo floats in and out.
 */
class Resampler(val inRate: Int, val outRate: Int = AIRPLAY_RATE) {
    private val up: Int
    private val down: Int
    private val half: Int
    private val table: FloatArray

    init {
        require(inRate > 0 && outRate > 0)
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g

        val band = min(inRate, outRate) / 2.0
        val transition = band * TRANSITION_SHARE
        val cutoff = (band - transition / 2) / inRate
        // Kaiser's estimate of the length for this stopband and transition.
        val length = (STOPBAND_DB - 8) / (2.285 * 2 * PI * transition / inRate)
        half = max(4, ceil(length / 2).toInt())
        val taps = 2 * half
        val beta = 0.1102 * (STOPBAND_DB - 8.7)
        val i0Beta = besselI0(beta)

        table = FloatArray(up * taps)
        for (phase in 0 until up) {
            val frac = phase.toDouble() / up
            var sum = 0.0
            val row = DoubleArray(taps)
            for (t in 0 until taps) {
                // Tap t sits at input sample (t - half + 1) from the floor of the output's position.
                val x = (t - half + 1) - frac
                val r = x / half
                val window = if (r <= -1 || r >= 1) 0.0 else besselI0(beta * sqrt(1 - r * r)) / i0Beta
                row[t] = 2 * cutoff * sinc(2 * cutoff * x) * window
                sum += row[t]
            }
            // Each phase passes DC exactly, so a level doesn't wobble from sample to sample.
            for (t in 0 until taps) table[phase * taps + t] = (row[t] / sum).toFloat()
        }
    }

    /** Input still to be used: interleaved stereo, [held] frames from the start. */
    private var input = FloatArray(4096)
    private var held = 0

    /** Global index of input[0], counting from the first frame pushed. */
    private var inputStart = -(half - 1).toLong()

    /** The next output frame's position, in output frames since the start. */
    private var produced = 0L

    init {
        // Silence before the first sample, so the filter has a past to look back on.
        held = half - 1
    }

    fun push(samples: FloatArray, frames: Int) {
        ensure(held + frames)
        System.arraycopy(samples, 0, input, held * 2, frames * 2)
        held += frames
    }

    /**
     * Writes what the input pushed so far allows into [out] (interleaved
     * stereo), at most [capacity] frames, and returns how many.
     */
    fun pull(out: FloatArray, capacity: Int): Int {
        val taps = 2 * half
        var n = 0
        while (n < capacity) {
            val pos = produced * down
            val base = pos / up
            val phase = (pos % up).toInt()
            // The filter's last tap reads input frame base + half.
            val first = base - half + 1 - inputStart
            if (first + taps > held) break
            var l = 0f
            var r = 0f
            var at = (first * 2).toInt()
            val row = phase * taps
            for (t in 0 until taps) {
                val c = table[row + t]
                l += c * input[at]
                r += c * input[at + 1]
                at += 2
            }
            out[n * 2] = l
            out[n * 2 + 1] = r
            n++
            produced++
        }
        compact()
        return n
    }

    /** The end of the stream: silence after the last sample, so its last moments come out too. */
    fun drain() {
        val pad = FloatArray(half * 2 * 2)
        push(pad, half * 2)
    }

    /** Output frames the input pushed so far stands for, drained or not. */
    fun framesFor(inputFrames: Long): Long = inputFrames * up / down

    private fun compact() {
        val nextBase = (produced * down) / up
        val keepFrom = (nextBase - half + 1 - inputStart).toInt()
        if (keepFrom <= 0) return
        val drop = min(keepFrom, held)
        System.arraycopy(input, drop * 2, input, 0, (held - drop) * 2)
        held -= drop
        inputStart += drop
    }

    private fun ensure(frames: Int) {
        if (frames * 2 <= input.size) return
        input = input.copyOf(max(frames * 2, input.size * 2))
    }

    private companion object {
        const val STOPBAND_DB = 90.0

        /** The share of the band given to the slope from pass to stop: 20 to 22.05 kHz at 44.1 out. */
        const val TRANSITION_SHARE = 0.09

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4
            var k = 1
            while (term > sum * 1e-12) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}

/**
 * Floats in ±1 to 16-bit samples. A sample that already is a 16-bit value —
 * a CD-quality file the decoder chose to hand over as floats — goes over as
 * exactly that value; anything finer gets triangular dither of one step,
 * which turns the rounding into a steady hiss far below hearing instead of
 * distortion that follows the music.
 */
class Dither {
    private var state = 0x2545F491L

    /** [frames] interleaved stereo frames of [input] into [out]. */
    fun toShorts(input: FloatArray, frames: Int, out: ShortArray, outOffset: Int = 0) {
        val samples = frames * 2
        var exact = true
        for (i in 0 until samples) {
            val scaled = input[i] * 32768f
            if (scaled != floor(scaled) || scaled < -32768f || scaled > 32767f) {
                exact = false
                break
            }
        }
        if (exact) {
            for (i in 0 until samples) out[outOffset + i] = (input[i] * 32768f).toInt().toShort()
            return
        }
        for (i in 0 until samples) {
            val noise = uniform() - uniform()
            val v = floor(input[i] * 32768f + noise + 0.5f).toInt()
            out[outOffset + i] = v.coerceIn(-32768, 32767).toShort()
        }
    }

    /** Uniform in [0, 1), from a xorshift generator: fast, and nothing here needs better. */
    private fun uniform(): Float {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        state = x
        return ((x ushr 40) and 0xFFFFFF).toFloat() / 16_777_216f
    }
}

/**
 * Where each channel of a decoder's output sits, for folding it to stereo.
 * Android's decoders and ALAC order a surround file's channels differently.
 */
enum class ChannelOrder { ANDROID, ALAC }

/**
 * [frames] frames of [channels]-channel float audio in [input] as stereo in
 * [out]: mono to both sides, stereo as it is, surround folded down the usual
 * way — fronts on their side, centre and surrounds at −3 dB, a centre
 * surround at −6 dB to both, the LFE left out — scaled so it can't clip.
 */
fun toStereo(input: FloatArray, frames: Int, channels: Int, order: ChannelOrder, out: FloatArray) {
    when (channels) {
        1 -> for (i in 0 until frames) {
            out[i * 2] = input[i]
            out[i * 2 + 1] = input[i]
        }
        2 -> System.arraycopy(input, 0, out, 0, frames * 2)
        else -> {
            val (left, right) = foldDown(channels, order)
            for (i in 0 until frames) {
                val f = i * channels
                var l = 0f
                var r = 0f
                for (c in 0 until channels) {
                    val x = input[f + c]
                    l += left[c] * x
                    r += right[c] * x
                }
                out[i * 2] = l
                out[i * 2 + 1] = r
            }
        }
    }
}

private enum class Speaker { L, R, C, LFE, SL, SR, CS }

/** Each channel's speaker, in the order a decoder of [order] hands them over. */
private fun speakers(channels: Int, order: ChannelOrder): List<Speaker> = when (order) {
    // Android's order follows WAVE's: FL FR FC LFE BL BR (FLC FRC) BC SL SR.
    ChannelOrder.ANDROID -> when (channels) {
        3 -> listOf(Speaker.L, Speaker.R, Speaker.C)
        4 -> listOf(Speaker.L, Speaker.R, Speaker.SL, Speaker.SR)
        5 -> listOf(Speaker.L, Speaker.R, Speaker.C, Speaker.SL, Speaker.SR)
        6 -> listOf(Speaker.L, Speaker.R, Speaker.C, Speaker.LFE, Speaker.SL, Speaker.SR)
        7 -> listOf(Speaker.L, Speaker.R, Speaker.C, Speaker.LFE, Speaker.CS, Speaker.SL, Speaker.SR)
        else -> listOf(Speaker.L, Speaker.R, Speaker.C, Speaker.LFE, Speaker.SL, Speaker.SR, Speaker.SL, Speaker.SR)
    }
    // ALAC's own layouts: C L R, then Ls Rs, with Cs and LFE where present,
    // and 7.1's extra front pair (Lc Rc) next to the centre.
    ChannelOrder.ALAC -> when (channels) {
        3 -> listOf(Speaker.C, Speaker.L, Speaker.R)
        4 -> listOf(Speaker.C, Speaker.L, Speaker.R, Speaker.CS)
        5 -> listOf(Speaker.C, Speaker.L, Speaker.R, Speaker.SL, Speaker.SR)
        6 -> listOf(Speaker.C, Speaker.L, Speaker.R, Speaker.SL, Speaker.SR, Speaker.LFE)
        7 -> listOf(Speaker.C, Speaker.L, Speaker.R, Speaker.SL, Speaker.SR, Speaker.CS, Speaker.LFE)
        else -> listOf(Speaker.C, Speaker.L, Speaker.R, Speaker.L, Speaker.R, Speaker.SL, Speaker.SR, Speaker.LFE)
    }
}.let { known -> if (known.size >= channels) known.take(channels) else known + List(channels - known.size) { Speaker.LFE } }

/** What each channel adds to the left and the right, normalised so the louder side sums to 1. */
private fun foldDown(channels: Int, order: ChannelOrder): Pair<FloatArray, FloatArray> {
    val left = FloatArray(channels)
    val right = FloatArray(channels)
    speakers(channels, order).forEachIndexed { c, speaker ->
        when (speaker) {
            Speaker.L -> left[c] = 1f
            Speaker.R -> right[c] = 1f
            Speaker.C -> {
                left[c] = MINUS_3_DB
                right[c] = MINUS_3_DB
            }
            Speaker.SL -> left[c] = MINUS_3_DB
            Speaker.SR -> right[c] = MINUS_3_DB
            Speaker.CS -> {
                left[c] = MINUS_6_DB
                right[c] = MINUS_6_DB
            }
            Speaker.LFE -> {}
        }
    }
    val gain = 1f / maxOf(left.sum(), right.sum(), 1f)
    for (c in 0 until channels) {
        left[c] *= gain
        right[c] *= gain
    }
    return left to right
}

private const val MINUS_3_DB = 0.70710677f
private const val MINUS_6_DB = 0.5f

/**
 * Rounds off the peaks resampling can raise past full scale (a loud master's
 * samples sit at the limit, the wave between them goes over): above
 * [SOFT_KNEE] a sample bends toward ±1 instead of being cut off there.
 * Nothing below the knee changes.
 */
fun softClip(samples: FloatArray, count: Int) {
    for (i in 0 until count) {
        val x = samples[i]
        val a = kotlin.math.abs(x)
        if (a <= SOFT_KNEE) continue
        val over = (a - SOFT_KNEE) / (1f - SOFT_KNEE)
        val bent = SOFT_KNEE + (1f - SOFT_KNEE) * kotlin.math.tanh(over)
        samples[i] = if (x < 0) -bent else bent
    }
}

private const val SOFT_KNEE = 0.97f

package com.sublunar.amp.playback.output.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class PcmTest {

    @Test
    fun `a verbatim frame decodes back to exactly its samples`() {
        val random = Random(11)
        val pcm = ShortArray(AlacFrames.FRAMES_PER_PACKET * 2) { random.nextInt(-32768, 32768).toShort() }
        pcm[0] = Short.MIN_VALUE
        pcm[1] = Short.MAX_VALUE
        val packet = ByteArray(AlacFrames.PACKET_BYTES + 8)
        val length = AlacFrames.escapeFrame(pcm, 0, packet)
        assertEquals(1412, length)

        val decoder = AlacDecoder(AIRPLAY_STREAM)
        val out = IntArray(AlacFrames.FRAMES_PER_PACKET * 2)
        assertEquals(AlacFrames.FRAMES_PER_PACKET, decoder.decode(packet, 0, length, out))
        assertArrayEquals(pcm.map { it.toInt() }.toIntArray(), out)
    }

    @Test
    fun `a frame ends on the END tag, so a decoder that reads on finds nothing after it`() {
        val packet = ByteArray(AlacFrames.PACKET_BYTES)
        AlacFrames.escapeFrame(ShortArray(AlacFrames.FRAMES_PER_PACKET * 2), 0, packet)
        // 23 + 11264 sample bits leave 3 bits of the last byte but one; the END tag fills them.
        val tail = ((packet[1410].toInt() and 0xff) shl 8) or (packet[1411].toInt() and 0xff)
        assertEquals(0b111, (tail ushr 6) and 0b111)
        assertEquals(0, tail and 0x3f)
    }

    @Test
    fun `48 kHz to 44_1 keeps a 1 kHz tone's level and pitch`() {
        val out = resample(48_000, toneAt(1_000.0, 48_000, 48_000))
        val skip = 2_000
        val body = out.copyOfRange(skip * 2, out.size - skip * 2)
        val level = rms(body)
        assertEquals(0.5 / sqrt(2.0), level, 0.001)
        assertEquals(1_000.0, frequencyOf(body, AIRPLAY_RATE), 2.0)
    }

    @Test
    fun `48 kHz to 44_1 removes what would fold back`() {
        // 23 kHz is above 44.1's Nyquist: left in, it would land at 21.1 kHz.
        val out = resample(48_000, toneAt(23_000.0, 48_000, 48_000))
        val body = out.copyOfRange(4_000, out.size - 4_000)
        val db = 20 * log10(rms(body) / (0.5 / sqrt(2.0)))
        assertTrue("left $db dB", db < -85)
    }

    @Test
    fun `96 kHz keeps the top of the band`() {
        val out = resample(96_000, toneAt(19_000.0, 96_000, 96_000))
        val body = out.copyOfRange(4_000, out.size - 4_000)
        assertEquals(0.5 / sqrt(2.0), rms(body), 0.005)
    }

    @Test
    fun `resampling neither gains nor loses time`() {
        for (rate in listOf(48_000, 96_000, 88_200, 32_000, 22_050, 192_000)) {
            val out = resample(rate, toneAt(440.0, rate, rate / 2))
            // Half a second in is half a second out, to the frame.
            assertEquals("from $rate", AIRPLAY_RATE / 2, out.size / 2)
        }
    }

    @Test
    fun `a peak at the start isn't shifted in time`() {
        val input = FloatArray(48_000 * 2)
        input[24_000 * 2] = 1f
        input[24_000 * 2 + 1] = 1f
        val out = resample(48_000, input)
        var peakAt = 0
        for (i in 0 until out.size / 2) if (abs(out[i * 2]) > abs(out[peakAt * 2])) peakAt = i
        assertEquals(22_050, peakAt)
    }

    @Test
    fun `16-bit values in floats come out exactly`() {
        val values = shortArrayOf(-32768, -1, 0, 1, 12345, 32767, 7, -7)
        val floats = FloatArray(values.size) { values[it] / 32768f }
        val out = ShortArray(values.size)
        Dither().toShorts(floats, values.size / 2, out)
        assertArrayEquals(values, out)
    }

    @Test
    fun `finer values are dithered within one step and average out right`() {
        val target = 1000.25f
        val floats = FloatArray(20_000) { target / 32768f }
        val out = ShortArray(floats.size)
        Dither().toShorts(floats, floats.size / 2, out)
        assertTrue(out.all { it in 999..1002 })
        assertEquals(target.toDouble(), out.average(), 0.05)
    }

    @Test
    fun `full scale clips rather than wraps`() {
        val floats = floatArrayOf(1.5f, -1.5f, 0.99999f, -1f)
        val out = ShortArray(4)
        Dither().toShorts(floats, 2, out)
        assertEquals(32767.toShort(), out[0])
        assertEquals((-32768).toShort(), out[1])
    }

    @Test
    fun `mono goes to both sides and 5_1 folds down without clipping`() {
        val stereo = FloatArray(4)
        toStereo(floatArrayOf(0.25f, -0.5f), 2, 1, ChannelOrder.ANDROID, stereo)
        assertArrayEquals(floatArrayOf(0.25f, 0.25f, -0.5f, -0.5f), stereo, 0f)

        // FL FR FC LFE BL BR, everything at full scale.
        val surround = FloatArray(6) { 1f }
        val out = FloatArray(2)
        toStereo(surround, 1, 6, ChannelOrder.ANDROID, out)
        assertTrue(out.all { it <= 1f + 1e-6f })
        assertEquals(1f, out[0], 1e-5f)
    }

    @Test
    fun `7_1 keeps its side channels, and ALAC 7_1 its extra fronts`() {
        // FL FR FC LFE BL BR SL SR: only the left side channel sounds.
        val sides = FloatArray(8).also { it[6] = 1f }
        val out = FloatArray(2)
        toStereo(sides, 1, 8, ChannelOrder.ANDROID, out)
        assertTrue(out[0] > 0.2f && out[1] == 0f)
        // C Lc Rc L R Ls Rs LFE: only Lc sounds.
        val lc = FloatArray(8).also { it[1] = 1f }
        toStereo(lc, 1, 8, ChannelOrder.ALAC, out)
        assertTrue(out[0] > 0.2f && out[1] == 0f)
        // Everything at full scale can't clip either side.
        toStereo(FloatArray(8) { 1f }, 1, 8, ChannelOrder.ANDROID, out)
        assertTrue(out.all { it <= 1f + 1e-6f })
    }

    @Test
    fun `the soft knee leaves everything below it alone and keeps overs under full scale`() {
        val samples = floatArrayOf(0.5f, -0.96f, 0.97f, 1.2f, -1.5f, 3f)
        softClip(samples, samples.size)
        assertArrayEquals(floatArrayOf(0.5f, -0.96f, 0.97f), samples.copyOfRange(0, 3), 0f)
        assertTrue(samples[3] in 0.97f..1f && samples[4] in -1f..-0.97f && samples[5] <= 1f)
        // Louder in stays at least as loud out: the knee bends, it never folds back.
        assertTrue(samples[5] >= samples[3])
        val justOver = floatArrayOf(0.975f, 0.99f)
        softClip(justOver, 2)
        assertTrue(justOver[0] in 0.97f..0.975f && justOver[1] > justOver[0] && justOver[1] < 0.99f)
    }

    private fun resample(rate: Int, input: FloatArray): FloatArray {
        val resampler = Resampler(rate)
        val chunk = FloatArray(1_000 * 2)
        val out = ArrayList<Float>()
        val buffer = FloatArray(8_192 * 2)
        var at = 0
        while (at < input.size / 2) {
            val frames = minOf(1_000, input.size / 2 - at)
            System.arraycopy(input, at * 2, chunk, 0, frames * 2)
            resampler.push(chunk, frames)
            at += frames
            while (true) {
                val n = resampler.pull(buffer, 8_192)
                if (n == 0) break
                for (i in 0 until n * 2) out += buffer[i]
            }
        }
        resampler.drain()
        val wanted = (input.size / 2).toLong() * AIRPLAY_RATE / rate
        while (out.size / 2 < wanted) {
            val n = resampler.pull(buffer, minOf(8_192, (wanted - out.size / 2).toInt()))
            if (n == 0) break
            for (i in 0 until n * 2) out += buffer[i]
        }
        return out.toFloatArray()
    }

    private fun toneAt(hz: Double, rate: Int, frames: Int): FloatArray =
        FloatArray(frames * 2) { (0.5 * sin(2 * PI * hz * (it / 2) / rate)).toFloat() }

    private fun rms(samples: FloatArray): Double {
        var sum = 0.0
        for (i in samples.indices step 2) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (samples.size / 2))
    }

    private fun frequencyOf(samples: FloatArray, rate: Int): Double {
        var crossings = 0
        for (i in 2 until samples.size step 2) if (samples[i - 2] < 0 && samples[i] >= 0) crossings++
        return crossings.toDouble() * rate / (samples.size / 2)
    }

    private companion object {
        val AIRPLAY_STREAM = AlacConfig(
            frameLength = AlacFrames.FRAMES_PER_PACKET,
            bitDepth = 16,
            pb = 40,
            mb = 10,
            kb = 14,
            channels = 2,
            maxRun = 255,
            sampleRate = AIRPLAY_RATE,
        )
    }
}

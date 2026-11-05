package com.sublunar.amp.playback.output.airplay

/**
 * Apple Lossless, decoded in Kotlin.
 *
 * The phone's own ALAC decoders (Qualcomm's, the only ones LightOS has)
 * refuse every file — the hardware one misreads the stream's setup and the
 * software one stops without a word — so a speaker fed from this phone would
 * get nothing from an ALAC library. The format is open (Apple published it in
 * 2011), small, and integer-only, so it is decoded here: frames from the
 * file's demuxer go in, PCM at the file's own bit depth comes out, bit for bit
 * what the encoder was given. The tests hold it to ffmpeg's decode of files
 * from both ffmpeg's encoder and Apple's.
 *
 * One frame is a run of elements — a mono channel, a channel pair, or filler
 * — ended by an END tag. Each channel is either stored verbatim (an "escape"
 * frame) or as the residue of an adaptive linear predictor, Rice-coded with a
 * running mean. A pair may be matrixed to mid/side, and 24- and 32-bit audio
 * keeps its lowest bytes apart, uncompressed.
 */
class AlacDecoder(val config: AlacConfig) {

    private val frameLength = config.frameLength
    private val predictor = IntArray(frameLength)
    private val mixU = IntArray(frameLength)
    private val mixV = IntArray(frameLength)
    private val shiftBuffer = IntArray(frameLength * 2)
    private val coefsU = IntArray(32)
    private val coefsV = IntArray(32)

    /**
     * Decode one frame into [out], interleaved, each sample a signed integer
     * at [AlacConfig.bitDepth] (a 24-bit sample lies in ±2^23). Returns how
     * many frames — one sample per channel — it held: the file's frame length,
     * or fewer for the last.
     *
     * Throws [AlacException] for a frame that can't be what an encoder wrote.
     */
    fun decode(frame: ByteArray, offset: Int = 0, length: Int = frame.size, out: IntArray): Int {
        val bits = BitReader(frame, offset, offset + length)
        val channels = config.channels
        var channel = 0
        var samples = 0
        while (channel < channels) {
            when (val tag = bits.read(3)) {
                ID_SCE, ID_LFE -> {
                    samples = decodeMono(bits, out, channel)
                    channel += 1
                }
                ID_CPE -> {
                    if (channel + 2 > channels) throw AlacException("a channel pair past the last channel")
                    samples = decodePair(bits, out, channel)
                    channel += 2
                }
                ID_DSE -> skipDataStream(bits)
                ID_FIL -> skipFill(bits)
                ID_END -> break
                else -> throw AlacException("element $tag isn't used in ALAC")
            }
        }
        return samples
    }

    private fun decodeMono(bits: BitReader, out: IntArray, channel: Int): Int {
        bits.read(4) // element instance tag
        if (bits.read(12) != 0) throw AlacException("unused header bits set")
        val header = bits.read(4)
        val partial = header ushr 3
        val bytesShifted = (header ushr 1) and 3
        if (bytesShifted == 3) throw AlacException("three bytes shifted")
        val shift = bytesShifted * 8
        val escape = header and 1
        val samples = if (partial != 0) bits.read(32) else frameLength
        if (samples !in 0..frameLength) throw AlacException("$samples samples in a frame of $frameLength")

        var shiftedBy = bytesShifted
        if (escape == 0) {
            val chanBits = config.bitDepth - shift
            // A mono frame carries the pair's mixing header too, always zero.
            bits.read(16)
            val mode = bits.read(4)
            val denShift = bits.read(4)
            val pbFactor = bits.read(3)
            val count = bits.read(5)
            for (i in 0 until count) coefsU[i] = bits.read(16).toShort().toInt()
            val shiftStart = bits.position
            if (bytesShifted != 0) bits.skip(shift.toLong() * samples)

            riceDecode(bits, samples, chanBits, (config.pb * pbFactor) / 4)
            predict(mode, samples, chanBits, coefsU, count, denShift, mixU)

            if (bytesShifted != 0) {
                val low = BitReader(bits, shiftStart)
                for (i in 0 until samples) shiftBuffer[i] = low.read(shift)
            }
        } else {
            readVerbatim(bits, samples, mixU, null)
            shiftedBy = 0
        }

        val stride = config.channels
        val lowShift = shiftedBy * 8
        var o = channel
        for (i in 0 until samples) {
            out[o] = if (lowShift == 0) mixU[i] else (mixU[i] shl lowShift) or shiftBuffer[i]
            o += stride
        }
        return samples
    }

    private fun decodePair(bits: BitReader, out: IntArray, channel: Int): Int {
        bits.read(4) // element instance tag
        if (bits.read(12) != 0) throw AlacException("unused header bits set")
        val header = bits.read(4)
        val partial = header ushr 3
        val bytesShifted = (header ushr 1) and 3
        if (bytesShifted == 3) throw AlacException("three bytes shifted")
        val shift = bytesShifted * 8
        val escape = header and 1
        val samples = if (partial != 0) bits.read(32) else frameLength
        if (samples !in 0..frameLength) throw AlacException("$samples samples in a frame of $frameLength")

        var mixBits = 0
        var mixRes = 0
        var shiftedBy = bytesShifted
        if (escape == 0) {
            // The side channel carries one bit more than the samples.
            val chanBits = config.bitDepth - shift + 1
            mixBits = bits.read(8)
            mixRes = bits.read(8).toByte().toInt()

            var byte = bits.read(8)
            val modeU = byte ushr 4
            val denShiftU = byte and 0xf
            byte = bits.read(8)
            val pbFactorU = byte ushr 5
            val countU = byte and 0x1f
            for (i in 0 until countU) coefsU[i] = bits.read(16).toShort().toInt()

            byte = bits.read(8)
            val modeV = byte ushr 4
            val denShiftV = byte and 0xf
            byte = bits.read(8)
            val pbFactorV = byte ushr 5
            val countV = byte and 0x1f
            for (i in 0 until countV) coefsV[i] = bits.read(16).toShort().toInt()

            val shiftStart = bits.position
            if (bytesShifted != 0) bits.skip(shift.toLong() * 2 * samples)

            riceDecode(bits, samples, chanBits, (config.pb * pbFactorU) / 4)
            predict(modeU, samples, chanBits, coefsU, countU, denShiftU, mixU)
            riceDecode(bits, samples, chanBits, (config.pb * pbFactorV) / 4)
            predict(modeV, samples, chanBits, coefsV, countV, denShiftV, mixV)

            if (bytesShifted != 0) {
                val low = BitReader(bits, shiftStart)
                for (i in 0 until samples * 2) shiftBuffer[i] = low.read(shift)
            }
        } else {
            readVerbatim(bits, samples, mixU, mixV)
            shiftedBy = 0
        }

        val stride = config.channels
        val lowShift = shiftedBy * 8
        var o = channel
        for (i in 0 until samples) {
            val u = mixU[i]
            val v = mixV[i]
            var left: Int
            var right: Int
            if (mixRes != 0) {
                left = u + v - ((mixRes * v) shr mixBits)
                right = left - v
            } else {
                left = u
                right = v
            }
            if (lowShift != 0) {
                left = (left shl lowShift) or shiftBuffer[2 * i]
                right = (right shl lowShift) or shiftBuffer[2 * i + 1]
            }
            out[o] = left
            out[o + 1] = right
            o += stride
        }
        return samples
    }

    /** Uncompressed samples, at the file's full bit depth, interleaved when [second] is given. */
    private fun readVerbatim(bits: BitReader, samples: Int, first: IntArray, second: IntArray?) {
        val chanBits = config.bitDepth
        val signShift = 32 - chanBits
        for (i in 0 until samples) {
            first[i] = readSigned(bits, chanBits, signShift)
            if (second != null) second[i] = readSigned(bits, chanBits, signShift)
        }
    }

    private fun readSigned(bits: BitReader, chanBits: Int, signShift: Int): Int =
        if (chanBits <= 16) {
            (bits.read(chanBits) shl signShift) shr signShift
        } else {
            val high = (bits.read(16) shl 16) shr signShift
            high + bits.read(chanBits - 16)
        }

    /**
     * The predictor's residue, Rice-coded against a running mean, into
     * [predictor]. Long runs of zeros are coded as a count.
     */
    private fun riceDecode(bits: BitReader, samples: Int, chanBits: Int, pb: Int) {
        var mb = config.mb.toLong()
        val kb = config.kb
        val wb = (1 shl kb) - 1
        var zmode = 0L
        var c = 0
        while (c < samples) {
            var k = log2Plus3((mb ushr QB_SHIFT).toInt())
            if (k > kb) k = kb
            val m = (1 shl k) - 1
            val n = readResidue(bits, m, k, chanBits)

            val coded = (n + zmode) and MASK32
            val magnitude = (coded + 1) ushr 1
            predictor[c] = (if (coded and 1L != 0L) -magnitude else magnitude).toInt()
            c++

            mb = (pb * (n + zmode) + mb - ((pb * mb) ushr QB_SHIFT)) and MASK32
            if (n > MEAN_CLAMP) mb = MEAN_CLAMP
            zmode = 0

            if (((mb shl M_MUL_SHIFT) and MASK32) < QB && c < samples) {
                zmode = 1
                val runK = leadingZeros32(mb) - BIT_OFF + ((mb + M_OFF) ushr M_DEN_SHIFT).toInt()
                val runM = ((1 shl runK) - 1) and wb
                val run = readRunLength(bits, runM, runK)
                if (c + run > samples) throw AlacException("a run of zeros past the frame's end")
                for (j in 0 until run) predictor[c++] = 0
                if (run >= 65535) zmode = 0
                mb = 0
            }
        }
    }

    /** One Rice-coded residue: a unary prefix and k bits, or past nine ones the value itself in [maxBits]. */
    private fun readResidue(bits: BitReader, m: Int, k: Int, maxBits: Int): Long {
        val prefix = Integer.numberOfLeadingZeros(bits.peek32().inv())
        if (prefix >= MAX_PREFIX) {
            bits.skip(MAX_PREFIX.toLong())
            return bits.read(maxBits).toLong() and MASK32
        }
        bits.skip(prefix + 1L)
        if (k == 1) return prefix.toLong()
        val v = bits.peek(k)
        var result = prefix.toLong() * m
        if (v >= 2) {
            result += v - 1
            bits.skip(k.toLong())
        } else {
            bits.skip(k - 1L)
        }
        return result
    }

    /** The length of a run of zeros: the same code, with a 16-bit escape. */
    private fun readRunLength(bits: BitReader, m: Int, k: Int): Int {
        val prefix = Integer.numberOfLeadingZeros(bits.peek32().inv())
        if (prefix >= MAX_PREFIX) {
            bits.skip(MAX_PREFIX.toLong())
            return bits.read(16)
        }
        bits.skip(prefix + 1L)
        val v = bits.peek(k)
        return if (v < 2) {
            bits.skip(k - 1L)
            prefix * m
        } else {
            bits.skip(k.toLong())
            prefix * m + v - 1
        }
    }

    /**
     * Undo the encoder's linear prediction: [predictor] holds the residue,
     * [out] gets the samples. Mode 0 is one pass; any other mode first undoes
     * a fixed first-order pass, then the adaptive one.
     */
    private fun predict(mode: Int, samples: Int, chanBits: Int, coefs: IntArray, count: Int, denShift: Int, out: IntArray) {
        if (mode != 0) unpredict(predictor, predictor, samples, coefs, FIRST_ORDER, chanBits, 0)
        unpredict(predictor, out, samples, coefs, count, chanBits, denShift)
    }

    private fun unpredict(
        residue: IntArray,
        out: IntArray,
        samples: Int,
        coefs: IntArray,
        count: Int,
        chanBits: Int,
        denShift: Int,
    ) {
        if (samples == 0) return
        val chanShift = 32 - chanBits
        out[0] = residue[0]
        if (count == 0) {
            if (residue !== out) System.arraycopy(residue, 1, out, 1, samples - 1)
            return
        }
        if (count == FIRST_ORDER) {
            var previous = out[0]
            for (j in 1 until samples) {
                val sum = residue[j] + previous
                previous = (sum shl chanShift) shr chanShift
                out[j] = previous
            }
            return
        }

        // Warm-up: the first samples only accumulate.
        val warm = minOf(count, samples - 1)
        for (j in 1..warm) {
            val sum = residue[j] + out[j - 1]
            out[j] = (sum shl chanShift) shr chanShift
        }

        val denHalf = if (denShift == 0) 0 else 1 shl (denShift - 1)
        val lim = count + 1
        for (j in lim until samples) {
            val top = out[j - lim]
            var sum = 0
            for (k in 0 until count) sum += coefs[k] * (out[j - 1 - k] - top)

            var delta = residue[j]
            var delta0 = delta
            val sign = signOf(delta)
            delta += top + ((sum + denHalf) shr denShift)
            out[j] = (delta shl chanShift) shr chanShift

            // Adapt the coefficients toward the residue's sign, nearest taps first.
            if (sign > 0) {
                for (k in count - 1 downTo 0) {
                    val d = top - out[j - 1 - k]
                    val s = signOf(d)
                    coefs[k] = (coefs[k] - s).toShort().toInt()
                    delta0 -= (count - k) * ((s * d) shr denShift)
                    if (delta0 <= 0) break
                }
            } else if (sign < 0) {
                for (k in count - 1 downTo 0) {
                    val d = top - out[j - 1 - k]
                    val s = signOf(d)
                    coefs[k] = (coefs[k] + s).toShort().toInt()
                    delta0 -= (count - k) * ((-s * d) shr denShift)
                    if (delta0 >= 0) break
                }
            }
        }
    }

    private fun skipDataStream(bits: BitReader) {
        bits.read(4) // element instance tag
        val aligned = bits.read(1)
        var count = bits.read(8)
        if (count == 255) count += bits.read(8)
        if (aligned != 0) bits.alignToByte()
        bits.skip(count * 8L)
    }

    private fun skipFill(bits: BitReader) {
        var count = bits.read(4)
        if (count == 15) count += bits.read(8) - 1
        bits.skip(count * 8L)
    }

    private companion object {
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_DSE = 4
        const val ID_LFE = 3
        const val ID_FIL = 6
        const val ID_END = 7

        const val QB_SHIFT = 9
        const val QB = 1L shl QB_SHIFT
        const val M_MUL_SHIFT = 2
        const val M_DEN_SHIFT = QB_SHIFT - M_MUL_SHIFT - 1
        const val M_OFF = 1L shl (M_DEN_SHIFT - 2)
        const val BIT_OFF = 24
        const val MEAN_CLAMP = 0xffffL
        const val MAX_PREFIX = 9
        const val FIRST_ORDER = 31
        const val MASK32 = 0xffffffffL

        fun signOf(i: Int): Int = (i shr 31) or (-i ushr 31)

        fun leadingZeros32(v: Long): Int = Integer.numberOfLeadingZeros(v.toInt())

        /** floor(log2(x + 3)), the Rice parameter for a mean of [x]. */
        fun log2Plus3(x: Int): Int = 31 - Integer.numberOfLeadingZeros(x + 3)
    }
}

/**
 * What an ALAC stream says of itself: the 24-byte "magic cookie" a file
 * carries beside its frames (MediaExtractor hands it over as csd-0).
 */
data class AlacConfig(
    val frameLength: Int,
    val bitDepth: Int,
    val pb: Int,
    val mb: Int,
    val kb: Int,
    val channels: Int,
    val maxRun: Int,
    val sampleRate: Int,
) {
    companion object {
        /**
         * Read from [bytes]: the bare 24 bytes, or wrapped in the `alac` box
         * an MP4 keeps them in (with or without QuickTime's `frma` before it).
         */
        fun parse(bytes: ByteArray): AlacConfig {
            var at = 0
            val tag = indexOfAlacTag(bytes)
            // Inside a box: the tag, then four bytes of version and flags.
            if (tag >= 0 && bytes.size - (tag + 8) >= CONFIG_SIZE) at = tag + 8
            if (bytes.size - at < CONFIG_SIZE) throw AlacException("an ALAC cookie of ${bytes.size} bytes")
            fun u8(i: Int) = bytes[at + i].toInt() and 0xff
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
            val config = AlacConfig(
                frameLength = u32(0),
                bitDepth = u8(5),
                pb = u8(6),
                mb = u8(7),
                kb = u8(8),
                channels = u8(9),
                maxRun = u16(10),
                sampleRate = u32(20),
            )
            if (config.frameLength !in 1..MAX_FRAME_LENGTH || config.bitDepth !in setOf(16, 20, 24, 32) ||
                config.channels !in 1..8 || config.kb !in 1..31
            ) {
                throw AlacException("an ALAC cookie that can't be right: $config")
            }
            return config
        }

        private const val CONFIG_SIZE = 24
        private const val MAX_FRAME_LENGTH = 1 shl 16

        private fun indexOfAlacTag(bytes: ByteArray): Int {
            for (i in 0..bytes.size - 4) {
                if (bytes[i] == 'a'.code.toByte() && bytes[i + 1] == 'l'.code.toByte() &&
                    bytes[i + 2] == 'a'.code.toByte() && bytes[i + 3] == 'c'.code.toByte()
                ) {
                    // `frma` names the format before the `alac` box itself; the
                    // box is the last tag, the one the cookie follows.
                    val later = indexOfAlacTagFrom(bytes, i + 4)
                    return if (later >= 0) later else i
                }
            }
            return -1
        }

        private fun indexOfAlacTagFrom(bytes: ByteArray, from: Int): Int {
            for (i in from..bytes.size - 4) {
                if (bytes[i] == 'a'.code.toByte() && bytes[i + 1] == 'l'.code.toByte() &&
                    bytes[i + 2] == 'a'.code.toByte() && bytes[i + 3] == 'c'.code.toByte()
                ) return i
            }
            return -1
        }
    }
}

class AlacException(message: String) : Exception(message)

/**
 * Bits read most significant first from [data] between [start] and [end].
 * Reading past the end throws, but a look ahead ([peek32], [peek]) sees
 * zeros there, as a decoder looking at its last few bits must.
 */
internal class BitReader(private val data: ByteArray, start: Int, private val end: Int) {
    var position: Long = start.toLong() * 8
        private set

    /** A second reader over the same bytes, from [at]. */
    constructor(other: BitReader, at: Long) : this(other.data, 0, other.end) {
        position = at
    }

    /** The next [n] bits, 0 to 32, as an unsigned number (32 bits may come back negative). */
    fun read(n: Int): Int {
        if (n == 0) return 0
        if (position + n > end.toLong() * 8) throw AlacException("read past the frame's end")
        val value = peekAt(position, n)
        position += n
        return value
    }

    fun peek32(): Int = peekAt(position, 32)

    fun peek(n: Int): Int = if (n == 0) 0 else peekAt(position, n)

    fun skip(n: Long) {
        position += n
        if (position > end.toLong() * 8) throw AlacException("skipped past the frame's end")
    }

    fun alignToByte() {
        position = (position + 7) and 7L.inv()
    }

    private fun peekAt(at: Long, n: Int): Int {
        var byteIndex = (at ushr 3).toInt()
        val bitOffset = (at and 7).toInt()
        // Up to 40 bits cover any 32 starting mid-byte.
        var window = 0L
        for (i in 0 until 5) {
            window = (window shl 8) or (if (byteIndex < end) (data[byteIndex].toLong() and 0xff) else 0L)
            byteIndex++
        }
        return ((window ushr (40 - bitOffset - n)) and ((1L shl n) - 1)).toInt()
    }
}

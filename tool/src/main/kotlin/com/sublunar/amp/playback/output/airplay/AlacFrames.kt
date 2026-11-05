package com.sublunar.amp.playback.output.airplay

/**
 * CD audio as the ALAC frames AirPlay 1 carries: 352 stereo samples a
 * packet, stored verbatim (ALAC's "escape" frame) rather than compressed. A
 * local network has room for the 1.4 Mbit/s that costs, it needs no
 * encoder, and what the speaker decodes is the PCM bit for bit.
 *
 * The frame is what Apple's own encoder writes for audio it doesn't
 * compress: a channel-pair element (tag 1) whose header says "escape", the
 * samples left then right, sixteen bits each, most significant bit first,
 * then the END tag and padding to the byte.
 */
object AlacFrames {
    const val FRAMES_PER_PACKET = 352

    /** 23 header bits, 352 × 32 sample bits, 3 for END: 11 290 bits, padded to 1412 bytes. */
    const val PACKET_BYTES = (23 + FRAMES_PER_PACKET * 32 + 3 + 7) / 8

    /**
     * One frame of [FRAMES_PER_PACKET] interleaved stereo samples from
     * [pcm] at [offset], written into [out] at [outOffset]. Returns its length.
     */
    fun escapeFrame(pcm: ShortArray, offset: Int, out: ByteArray, outOffset: Int = 0): Int {
        val bits = BitWriter(out, outOffset)
        bits.write(1, 3) // ID_CPE: a channel pair
        bits.write(0, 4) // element instance tag
        bits.write(0, 12) // unused
        // Not a partial frame (the packet holds the full 352 the stream
        // announced), no low bytes shifted off, escape: verbatim samples.
        bits.write(0b0001, 4)
        for (i in 0 until FRAMES_PER_PACKET * 2) bits.write(pcm[offset + i].toInt() and 0xffff, 16)
        bits.write(7, 3) // ID_END
        return bits.finish() - outOffset
    }
}

/** Bits written most significant first into [out] from [start]. */
internal class BitWriter(private val out: ByteArray, start: Int) {
    private var byte = start
    private var accumulator = 0L
    private var count = 0

    fun write(value: Int, bits: Int) {
        accumulator = (accumulator shl bits) or (value.toLong() and ((1L shl bits) - 1))
        count += bits
        while (count >= 8) {
            count -= 8
            out[byte++] = (accumulator ushr count).toByte()
        }
        accumulator = accumulator and ((1L shl count) - 1)
    }

    /** Pads the last byte with zeros and returns the index just past it. */
    fun finish(): Int {
        if (count > 0) {
            out[byte++] = (accumulator shl (8 - count)).toByte()
            count = 0
            accumulator = 0
        }
        return byte
    }
}

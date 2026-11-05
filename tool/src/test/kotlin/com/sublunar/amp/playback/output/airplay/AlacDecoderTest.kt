package com.sublunar.amp.playback.output.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The decoder against files from two encoders: ffmpeg's and Apple's own
 * (macOS afconvert, which also keeps 24-bit audio's low byte apart). Each
 * fixture was made from a WAV and decodes, in ffmpeg, to exactly that WAV's
 * samples — the SHA-1s below — so matching them is matching the source.
 *
 * The clips are a quarter second of a tone with pink or brown noise, then
 * digital silence (the run-of-zeros path), and end on a partial frame.
 */
class AlacDecoderTest {

    @Test
    fun `ffmpeg 16-bit stereo decodes to its source`() =
        assertDecodes("ffmpeg-16.m4a", bitDepth = 16, channels = 2, bytes = 54684, sha1 = SOURCE_16)

    @Test
    fun `Apple 16-bit stereo decodes to its source`() =
        assertDecodes("apple-16.m4a", bitDepth = 16, channels = 2, bytes = 54684, sha1 = SOURCE_16)

    @Test
    fun `ffmpeg 16-bit mono decodes to its source`() =
        assertDecodes("ffmpeg-16-mono.m4a", bitDepth = 16, channels = 1, bytes = 27342, sha1 = SOURCE_16_MONO)

    @Test
    fun `ffmpeg 24-bit stereo decodes to its source`() =
        assertDecodes("ffmpeg-24.m4a", bitDepth = 24, channels = 2, bytes = 88320, sha1 = SOURCE_24)

    @Test
    fun `Apple 24-bit stereo, low byte kept apart, decodes to its source`() =
        assertDecodes("apple-24.m4a", bitDepth = 24, channels = 2, bytes = 88320, sha1 = SOURCE_24)

    @Test
    fun `the cookie reads bare or inside its box`() {
        val bare = Mp4Alac.read(fixture("ffmpeg-16.m4a")).cookie
        val boxed = byteArrayOf(0, 0, 0, 36, 'a'.b, 'l'.b, 'a'.b, 'c'.b, 0, 0, 0, 0) + bare
        val quickTime = byteArrayOf(0, 0, 0, 12, 'f'.b, 'r'.b, 'm'.b, 'a'.b, 'a'.b, 'l'.b, 'a'.b, 'c'.b) + boxed
        val expected = AlacConfig.parse(bare)
        assertEquals(expected, AlacConfig.parse(boxed))
        assertEquals(expected, AlacConfig.parse(quickTime))
        assertEquals(4096, expected.frameLength)
        assertEquals(44100, expected.sampleRate)
    }

    @Test
    fun `a cut-off frame is refused, not read past`() {
        val file = Mp4Alac.read(fixture("ffmpeg-16.m4a"))
        val decoder = AlacDecoder(AlacConfig.parse(file.cookie))
        val frame = file.frames.first()
        val out = IntArray(decoder.config.frameLength * decoder.config.channels)
        assertThrows(AlacException::class.java) { decoder.decode(frame, 0, frame.size / 3, out) }
    }

    private fun assertDecodes(name: String, bitDepth: Int, channels: Int, bytes: Int, sha1: String) {
        val file = Mp4Alac.read(fixture(name))
        val config = AlacConfig.parse(file.cookie)
        assertEquals(bitDepth, config.bitDepth)
        assertEquals(channels, config.channels)

        val decoder = AlacDecoder(config)
        val out = IntArray(config.frameLength * config.channels)
        val digest = MessageDigest.getInstance("SHA-1")
        var total = 0
        for (frame in file.frames) {
            val frames = decoder.decode(frame, 0, frame.size, out)
            val samples = frames * config.channels
            // As ffmpeg writes them: 16-bit little-endian, or 24-bit raised to 32.
            val buffer = ByteBuffer.allocate(samples * if (bitDepth == 16) 2 else 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until samples) {
                if (bitDepth == 16) buffer.putShort(out[i].toShort()) else buffer.putInt(out[i] shl (32 - bitDepth))
            }
            digest.update(buffer.array())
            total += buffer.capacity()
        }
        assertEquals(bytes, total)
        assertEquals(sha1, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private val Char.b: Byte get() = code.toByte()

    private companion object {
        const val SOURCE_16 = "746470f1004d382d79f94a72305024e3197763a5"
        const val SOURCE_16_MONO = "85393e19a78ad9dddbee581d1e9ca8accdab8f4d"
        const val SOURCE_24 = "091aa307144217ddf959e25dc8f31612a3ee144f"

        fun fixture(name: String): ByteArray = File("src/test/resources/airplay/$name").readBytes()
    }
}

/**
 * Just enough MP4 to pull an ALAC track's cookie and frames out of a test
 * fixture — the app itself leaves demuxing to MediaExtractor.
 */
internal class Mp4Alac(val cookie: ByteArray, val frames: List<ByteArray>) {
    companion object {
        fun read(file: ByteArray): Mp4Alac {
            val buffer = ByteBuffer.wrap(file)
            val stbl = find(buffer, 0, file.size, listOf("moov", "trak", "mdia", "minf", "stbl"))
                ?: error("no sample table")
            val stsd = child(buffer, stbl.first, stbl.second, "stsd") ?: error("no stsd")
            // stsd: version/flags, entry count, then the `alac` sample entry
            // (8-byte header, 28 bytes of audio fields) holding the `alac` box.
            val entry = stsd.first + 8
            val inner = entry + 8 + 28
            val innerSize = buffer.getInt(inner)
            val cookie = file.copyOfRange(inner + 12, inner + innerSize)

            val stsz = child(buffer, stbl.first, stbl.second, "stsz") ?: error("no stsz")
            val count = buffer.getInt(stsz.first + 8)
            val sizes = IntArray(count) { buffer.getInt(stsz.first + 12 + it * 4) }

            val stco = child(buffer, stbl.first, stbl.second, "stco") ?: error("no stco")
            val chunks = IntArray(buffer.getInt(stco.first + 4)) { buffer.getInt(stco.first + 8 + it * 4) }

            val stsc = child(buffer, stbl.first, stbl.second, "stsc") ?: error("no stsc")
            val runs = buffer.getInt(stsc.first + 4)
            val firstChunk = IntArray(runs) { buffer.getInt(stsc.first + 8 + it * 12) }
            val perChunk = IntArray(runs) { buffer.getInt(stsc.first + 12 + it * 12) }

            val frames = ArrayList<ByteArray>(count)
            var sample = 0
            for (chunk in chunks.indices) {
                val run = firstChunk.indexOfLast { it <= chunk + 1 }
                var at = chunks[chunk]
                repeat(perChunk[run]) {
                    if (sample < count) {
                        frames += file.copyOfRange(at, at + sizes[sample])
                        at += sizes[sample]
                        sample++
                    }
                }
            }
            return Mp4Alac(cookie, frames)
        }

        /** The payload range of the box at [path] below [start, end). */
        private fun find(buffer: ByteBuffer, start: Int, end: Int, path: List<String>): Pair<Int, Int>? {
            var range = start to end
            for (name in path) range = child(buffer, range.first, range.second, name) ?: return null
            return range
        }

        private fun child(buffer: ByteBuffer, start: Int, end: Int, name: String): Pair<Int, Int>? {
            var at = start
            while (at + 8 <= end) {
                val size = buffer.getInt(at)
                val type = String(ByteArray(4) { buffer.get(at + 4 + it) }, Charsets.US_ASCII)
                if (size < 8) return null
                if (type == name) return (at + 8) to (at + size)
                at += size
            }
            return null
        }
    }
}

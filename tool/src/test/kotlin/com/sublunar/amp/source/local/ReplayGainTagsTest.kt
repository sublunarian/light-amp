package com.sublunar.amp.source.local

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser is fed real container bytes rather than a mock: every case here
 * is a file laid out the way the format says, so a change that breaks a real
 * library breaks a test first.
 */
class ReplayGainTagsTest {

    // --- builders -------------------------------------------------------------

    private fun le32(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun be32(value: Int) = byteArrayOf(
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun syncSafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    /** vendor string, count, then each `KEY=VALUE`, all little-endian. */
    private fun vorbisComments(vararg entries: String): ByteArray {
        val out = ByteArrayOutputStream()
        val vendor = "test".toByteArray()
        out.write(le32(vendor.size)); out.write(vendor)
        out.write(le32(entries.size))
        entries.forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            out.write(le32(bytes.size)); out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun flac(vararg entries: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        // A STREAMINFO block first, as every real FLAC has, so the walk has to
        // skip something before reaching the comments.
        val streamInfo = ByteArray(34)
        out.write(0x00); out.write(be32(streamInfo.size).copyOfRange(1, 4)); out.write(streamInfo)
        val comments = vorbisComments(*entries)
        // Last-block flag set, type 4.
        out.write(0x84); out.write(be32(comments.size).copyOfRange(1, 4)); out.write(comments)
        return out.toByteArray()
    }

    private fun oggPage(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray())
        out.write(ByteArray(22)) // version, type, granule, serial, seq, crc
        val segments = mutableListOf<Int>()
        var left = payload.size
        while (left >= 255) { segments.add(255); left -= 255 }
        segments.add(left)
        out.write(segments.size)
        segments.forEach { out.write(it) }
        out.write(payload)
        return out.toByteArray()
    }

    private fun opus(vararg entries: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(oggPage("OpusHead".toByteArray() + ByteArray(11)))
        out.write(oggPage("OpusTags".toByteArray() + vorbisComments(*entries)))
        return out.toByteArray()
    }

    private fun oggVorbis(vararg entries: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(oggPage(byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23)))
        out.write(oggPage(byteArrayOf(3) + "vorbis".toByteArray() + vorbisComments(*entries)))
        return out.toByteArray()
    }

    /** An ID3v2.4 tag holding TXXX frames, then a byte of audio. */
    private fun mp3(vararg entries: Pair<String, String>, major: Int = 4): ByteArray {
        val frames = ByteArrayOutputStream()
        entries.forEach { (key, value) ->
            val body = byteArrayOf(3) + // UTF-8
                key.toByteArray(Charsets.UTF_8) + byteArrayOf(0) +
                value.toByteArray(Charsets.UTF_8)
            frames.write("TXXX".toByteArray())
            frames.write(if (major >= 4) syncSafe(body.size) else be32(body.size))
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        val tag = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray())
        out.write(byteArrayOf(major.toByte(), 0, 0))
        out.write(syncSafe(tag.size))
        out.write(tag)
        out.write(0xFF) // where the audio would start
        return out.toByteArray()
    }

    private fun atom(type: String, body: ByteArray): ByteArray =
        be32(body.size + 8) + type.toByteArray(Charsets.US_ASCII) + body

    private fun mp4(name: String, value: String): ByteArray {
        val mean = atom("mean", byteArrayOf(0, 0, 0, 0) + "com.apple.iTunes".toByteArray())
        val nameAtom = atom("name", byteArrayOf(0, 0, 0, 0) + name.toByteArray())
        val data = atom("data", byteArrayOf(0, 0, 0, 1) + byteArrayOf(0, 0, 0, 0) + value.toByteArray())
        val freeform = atom("----", mean + nameAtom + data)
        val ilst = atom("ilst", freeform)
        // `meta` is a full atom: version and flags precede its children.
        val meta = atom("meta", byteArrayOf(0, 0, 0, 0) + ilst)
        val udta = atom("udta", meta)
        // An `ftyp` first, and `moov` after the media data, as a real file has.
        return atom("ftyp", "M4A ".toByteArray()) + atom("mdat", ByteArray(64)) + atom("moov", udta)
    }

    private fun write(name: String, bytes: ByteArray): File {
        val file = File.createTempFile("rgtest", "-$name")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    // --- per container --------------------------------------------------------

    @Test
    fun flacTrackGain() {
        val file = write("a.flac", flac("REPLAYGAIN_TRACK_GAIN=-7.15 dB", "TITLE=Something"))
        assertEquals(-7.15f, ReplayGainTags.gainDb(file)!!, 0.001f)
    }

    @Test
    fun opusR128ConvertsToReplayGainReference() {
        // -1536 in Q7.8 is -6 dB against -23 LUFS, so -1 dB against -18.
        val file = write("a.opus", opus("R128_TRACK_GAIN=-1536"))
        assertEquals(-1f, ReplayGainTags.gainDb(file)!!, 0.001f)
    }

    @Test
    fun opusPrefersReplayGainOverR128WhenBothPresent() {
        val file = write("a.opus", opus("R128_TRACK_GAIN=-1536", "REPLAYGAIN_TRACK_GAIN=-4.2 dB"))
        assertEquals(-4.2f, ReplayGainTags.gainDb(file)!!, 0.001f)
    }

    @Test
    fun oggVorbisTrackGain() {
        val file = write("a.ogg", oggVorbis("REPLAYGAIN_TRACK_GAIN=-9.00 dB"))
        assertEquals(-9f, ReplayGainTags.gainDb(file)!!, 0.001f)
    }

    @Test
    fun mp3Id3v24TrackGain() {
        val file = write("a.mp3", mp3("REPLAYGAIN_TRACK_GAIN" to "-5.42 dB"))
        assertEquals(-5.42f, ReplayGainTags.gainDb(file)!!, 0.001f)
    }

    @Test
    fun mp3Id3v23UsesPlainFrameSizes() {
        val file = write("a.mp3", mp3("REPLAYGAIN_TRACK_GAIN" to "-3.00 dB", major = 3))
        assertEquals(-3f, ReplayGainTags.gainDb(file)!!, 0.001f)
    }

    @Test
    fun mp4FreeformTrackGain() {
        val file = write("a.m4a", mp4("replaygain_track_gain", "-8.30 dB"))
        assertEquals(-8.3f, ReplayGainTags.gainDb(file)!!, 0.001f)
    }

    // --- selection and refusal ------------------------------------------------

    @Test
    fun albumGainIsTheFallbackAndTrackGainWins() {
        val albumOnly = write("b.flac", flac("REPLAYGAIN_ALBUM_GAIN=-6.00 dB"))
        assertEquals(-6f, ReplayGainTags.gainDb(albumOnly)!!, 0.001f)

        val both = write("c.flac", flac("REPLAYGAIN_ALBUM_GAIN=-6.00 dB", "REPLAYGAIN_TRACK_GAIN=-2.50 dB"))
        assertEquals(-2.5f, ReplayGainTags.gainDb(both)!!, 0.001f)
    }

    @Test
    fun valueFormsAndCaseAreAccepted() {
        assertEquals(-7f, ReplayGainTags.gainDb(write("d.flac", flac("REPLAYGAIN_TRACK_GAIN=-7dB")))!!, 0.001f)
        assertEquals(-7f, ReplayGainTags.gainDb(write("e.flac", flac("REPLAYGAIN_TRACK_GAIN=-7")))!!, 0.001f)
        assertEquals(3.5f, ReplayGainTags.gainDb(write("f.flac", flac("REPLAYGAIN_TRACK_GAIN=+3.50 dB")))!!, 0.001f)
        // Keys are matched case-insensitively; some taggers write them lower.
        assertEquals(-1f, ReplayGainTags.gainDb(write("g.flac", flac("replaygain_track_gain=-1.0 dB")))!!, 0.001f)
    }

    @Test
    fun untaggedAndUnreadableFilesYieldNothing() {
        assertNull(ReplayGainTags.gainDb(write("h.flac", flac("TITLE=No gain here"))))
        assertNull(ReplayGainTags.gainDb(write("i.mp3", mp3("MUSICBRAINZ_TRACKID" to "abc"))))
        assertNull(ReplayGainTags.gainDb(write("j.flac", byteArrayOf(1, 2, 3))))
        assertNull(ReplayGainTags.gainDb(write("k.flac", ByteArray(0))))
        assertNull(ReplayGainTags.gainDb(File("/nonexistent/nothing.flac")))
    }

    @Test
    fun nonsenseValuesAreRefusedRatherThanApplied() {
        assertNull(ReplayGainTags.gainDb(write("l.flac", flac("REPLAYGAIN_TRACK_GAIN=not a number"))))
        // Outside any real measurement: evidence of a misparse, not a volume.
        assertNull(ReplayGainTags.gainDb(write("m.flac", flac("REPLAYGAIN_TRACK_GAIN=-400 dB"))))
        assertNull(ReplayGainTags.gainDb(write("n.flac", flac("REPLAYGAIN_TRACK_GAIN=999 dB"))))
    }

    @Test
    fun theContentDecidesWhenTheExtensionIsWrongOrMissing() {
        // A FLAC named .bin still reads, because the magic bytes are checked.
        val file = write("o.bin", flac("REPLAYGAIN_TRACK_GAIN=-2.00 dB"))
        assertEquals(-2f, ReplayGainTags.gainDb(file)!!, 0.001f)
    }

    @Test
    fun aTruncatedFileStopsInsteadOfThrowing() {
        val whole = flac("REPLAYGAIN_TRACK_GAIN=-7.15 dB")
        for (cut in listOf(4, 8, 20, whole.size / 2, whole.size - 3)) {
            val file = write("p$cut.flac", whole.copyOfRange(0, cut))
            // Any answer is acceptable; throwing or hanging is not.
            assertTrue(ReplayGainTags.gainDb(file) == null || ReplayGainTags.gainDb(file)!!.isFinite())
        }
    }
}

package com.sublunar.amp.data

import java.io.File

/**
 * Writes the index a streaming MP3 encoder cannot.
 *
 * A variable-rate MP3 says nothing about its own length unless something
 * writes it down: a "Xing" frame at the front, holding the number of frames
 * and, at each percent of the way through, how far into the file that is.
 * LAME writes it *last*, seeking back to the start once it knows the total.
 * An encoder writing to a stream has nowhere to seek back to, and Plex's
 * transcoder produces every mp3 as a live segmented encode — so the frames
 * arrive, and nothing else.
 *
 * Without that frame ExoPlayer divides the file size by the *first* frame's
 * bitrate, which on a variable-rate encode can be wrong several times over: a
 * wrong duration, silence played past the real end, and every seek landing
 * somewhere other than where the bar said.
 *
 * So this does the encoder's last step: walk the frames, count them, note
 * where each percent begins, and put the frame LAME would have written in
 * front. Not one audio byte moves. A file that already carries an index is
 * recognised from its first frame and left alone.
 *
 * Layer III only; the tables are the MPEG audio frame header as ExoPlayer's
 * MpegAudioUtil reads it and LAME's VbrTag.c writes it.
 */
object Mp3VbrIndex {

    sealed interface Outcome {
        /** An index was written: [frames] audio frames over [bytes] of stream. */
        data class Written(val frames: Int, val bytes: Int) : Outcome

        /** Already carries a Xing, Info or VBRI frame; left exactly as it was. */
        data object AlreadyIndexed : Outcome

        /** No Layer III frame at the start — not something to touch. */
        data object NotMpegAudio : Outcome

        /** The frames stop short of the end, so any count would be a lie. */
        data object Unparseable : Outcome
    }

    /**
     * Index [file] in place.
     *
     * An indexed file is recognised from its opening frame alone, so checking
     * one reads a few kilobytes; only a file that needs the work is read whole.
     * Written through a temporary file and renamed, so an interruption leaves
     * the original as it was rather than half-rewritten.
     */
    fun index(file: File): Outcome {
        val head = readHead(file, PROBE_BYTES)
        val tagEnd = id3v2End(head)
        if (tagEnd + 4 <= head.size) {
            val first = firstFrame(head, tagEnd) ?: return Outcome.NotMpegAudio
            if (hasIndex(head, first)) return Outcome.AlreadyIndexed
        }
        val data = file.readBytes()
        val fullTagEnd = id3v2End(data)
        val first = firstFrame(data, fullTagEnd) ?: return Outcome.NotMpegAudio
        if (hasIndex(data, first)) return Outcome.AlreadyIndexed
        val walk = walk(data, first) ?: return Outcome.Unparseable
        val index = buildIndexFrame(first, walk)
        val out = File(file.parentFile, file.name + INDEXING_SUFFIX)
        out.outputStream().buffered().use { o ->
            // The ID3v2 tag, if there is one, verbatim; then the index; then
            // every frame and whatever followed them, verbatim.
            o.write(data, 0, fullTagEnd)
            o.write(index)
            o.write(data, first, data.size - first)
        }
        if (!out.renameTo(file)) {
            out.delete()
            error("couldn't replace ${file.name}")
        }
        return Outcome.Written(walk.offsets.size, index.size + (walk.end - first))
    }

    // --- Frames --------------------------------------------------------------

    /** One MPEG audio frame header, as the four bytes at the start of a frame. */
    private class Header(val raw: Int) {
        /** 1, 2 or 25 for MPEG 2.5; 0 is the reserved value. */
        val version = when ((raw ushr 19) and 3) { 3 -> 1; 2 -> 2; 0 -> 25; else -> 0 }
        private val layerIII = ((raw ushr 17) and 3) == 1
        val bitrateIndex = (raw ushr 12) and 0xF
        val sampleRateIndex = (raw ushr 10) and 3
        val padding = (raw ushr 9) and 1
        val mono = ((raw ushr 6) and 3) == 3

        val valid: Boolean
            get() = (raw ushr 21) and 0x7FF == 0x7FF &&
                version != 0 && layerIII && bitrateIndex in 1..14 && sampleRateIndex != 3

        val sampleRate: Int get() = SAMPLE_RATES[when (version) { 1 -> 0; 2 -> 1; else -> 2 }][sampleRateIndex]
        val bitrateKbps: Int get() = (if (version == 1) BITRATES_V1 else BITRATES_V2)[bitrateIndex]

        /** Bytes of side information between the header and the audio data. */
        val sideInfo: Int
            get() = if (version == 1) (if (mono) 17 else 32) else (if (mono) 9 else 17)

        val length: Int get() = frameLength(version, bitrateKbps, sampleRate, padding)

        /** Frames of one stream agree on version and sample rate; bitrate and padding are free to vary. */
        fun sameStreamAs(o: Header) = version == o.version && sampleRateIndex == o.sampleRateIndex
    }

    private fun frameLength(version: Int, bitrateKbps: Int, sampleRate: Int, padding: Int): Int =
        (if (version == 1) 144_000 else 72_000) * bitrateKbps / sampleRate + padding

    private fun headerAt(data: ByteArray, at: Int): Header? {
        if (at < 0 || at + 4 > data.size) return null
        val raw = ((data[at].toInt() and 0xFF) shl 24) or
            ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or
            (data[at + 3].toInt() and 0xFF)
        return Header(raw).takeIf { it.valid }
    }

    /**
     * Where the audio begins. A lone sync word turns up inside audio data
     * often enough; a second frame starting exactly where the first says it
     * ends does not, and that is what counts as finding the stream.
     */
    private fun firstFrame(data: ByteArray, from: Int): Int? {
        val limit = minOf(data.size - 4, from + SYNC_SCAN_BYTES)
        var at = from
        while (at <= limit) {
            val h = headerAt(data, at)
            if (h != null) {
                val next = at + h.length
                if (next == data.size || headerAt(data, next)?.sameStreamAs(h) == true) return at
            }
            at++
        }
        return null
    }

    private fun hasIndex(data: ByteArray, first: Int): Boolean {
        val h = headerAt(data, first) ?: return false
        val at = first + 4 + h.sideInfo
        return tagAt(data, at, "Xing") || tagAt(data, at, "Info") || tagAt(data, first + 36, "VBRI")
    }

    private class Walk(val offsets: IntArray, val end: Int, val head: Header)

    private fun walk(data: ByteArray, start: Int): Walk? {
        val head = headerAt(data, start) ?: return null
        val offsets = ArrayList<Int>()
        var at = start
        while (at + 4 <= data.size) {
            val h = headerAt(data, at)
            if (h == null || !h.sameStreamAs(head)) break
            val next = at + h.length
            if (next > data.size) break // a final frame the stream ended inside
            offsets.add(at)
            at = next
        }
        if (offsets.isEmpty()) return null
        // What follows the last frame has to be nothing, an ID3v1 tag, or the
        // stub of a frame cut off by the end of the file. Anything else means
        // the walk lost the stream part-way, and an index built on part of a
        // file would recreate the very bug this exists to fix, with a shorter
        // phantom. Better to leave it as it came.
        val rest = data.size - at
        val clean = rest < 4 ||
            (rest == ID3V1_BYTES && tagAt(data, at, "TAG")) ||
            headerAt(data, at)?.let { it.sameStreamAs(head) && at + it.length > data.size } == true
        return if (clean) Walk(offsets.toIntArray(), at, head) else null
    }

    // --- The index frame -----------------------------------------------------

    /**
     * The frame LAME would have written: the first frame's own header, at the
     * smallest standard bitrate whose frame can hold the index, padding off and
     * no CRC; then side information left zero; then the tag, the flags, the
     * frame count, the byte count, and a hundred positions. Offsets are from
     * the start of this frame, as a fraction of [Outcome.Written.bytes], which
     * is how every reader of a Xing frame interprets them.
     */
    private fun buildIndexFrame(first: Int, walk: Walk): ByteArray {
        val h = walk.head
        val needed = 4 + h.sideInfo + 4 + 4 + 4 + 4 + TOC_ENTRIES
        val rates = if (h.version == 1) BITRATES_V1 else BITRATES_V2
        val bitrateIndex = (1..14).first { frameLength(h.version, rates[it], h.sampleRate, 0) >= needed }
        val raw = (h.raw and (0xF shl 12).inv() and (1 shl 9).inv()) or
            (bitrateIndex shl 12) or
            (1 shl 16) // protection bit set: no CRC
        val frame = ByteArray(Header(raw).length)
        frame.putInt(0, raw)
        var p = 4 + h.sideInfo
        p = frame.putTag(p, "Xing")
        p = frame.putInt(p, FLAG_FRAMES or FLAG_BYTES or FLAG_TOC)
        p = frame.putInt(p, walk.offsets.size)
        val bytes = frame.size + (walk.end - first)
        p = frame.putInt(p, bytes)
        for (i in 0 until TOC_ENTRIES) {
            val frameIndex = (i.toLong() * walk.offsets.size / TOC_ENTRIES).toInt()
            val offset = frame.size + (walk.offsets[frameIndex] - first)
            frame[p++] = (offset.toLong() * 256 / bytes).coerceIn(0, 255).toByte()
        }
        return frame
    }

    // --- Bytes ---------------------------------------------------------------

    private fun readHead(file: File, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        file.inputStream().use { s ->
            while (got < n) {
                val r = s.read(buf, got, n - got)
                if (r < 0) break
                got += r
            }
        }
        return if (got == n) buf else buf.copyOf(got)
    }

    /** Bytes taken by an ID3v2 tag at the front, or 0. Sizes are 7-bit "syncsafe". */
    private fun id3v2End(data: ByteArray): Int {
        if (!tagAt(data, 0, "ID3") || data.size < 10) return 0
        val size = (0 until 4).fold(0) { acc, i -> (acc shl 7) or (data[6 + i].toInt() and 0x7F) }
        val footer = if ((data[5].toInt() and 0x10) != 0) 10 else 0
        return minOf(data.size, 10 + size + footer)
    }

    private fun tagAt(data: ByteArray, at: Int, tag: String): Boolean =
        at >= 0 && at + tag.length <= data.size && tag.indices.all { data[at + it] == tag[it].code.toByte() }

    private fun ByteArray.putTag(at: Int, tag: String): Int {
        tag.forEachIndexed { i, c -> this[at + i] = c.code.toByte() }
        return at + tag.length
    }

    private fun ByteArray.putInt(at: Int, v: Int): Int {
        this[at] = (v ushr 24).toByte()
        this[at + 1] = (v ushr 16).toByte()
        this[at + 2] = (v ushr 8).toByte()
        this[at + 3] = v.toByte()
        return at + 4
    }

    private val SAMPLE_RATES = arrayOf(
        intArrayOf(44_100, 48_000, 32_000), // MPEG 1
        intArrayOf(22_050, 24_000, 16_000), // MPEG 2
        intArrayOf(11_025, 12_000, 8_000), // MPEG 2.5
    )
    private val BITRATES_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

    private const val TOC_ENTRIES = 100
    private const val FLAG_FRAMES = 1
    private const val FLAG_BYTES = 2
    private const val FLAG_TOC = 4
    private const val ID3V1_BYTES = 128
    private const val PROBE_BYTES = 16 * 1024
    private const val SYNC_SCAN_BYTES = 64 * 1024
    private const val INDEXING_SUFFIX = ".indexing"
}

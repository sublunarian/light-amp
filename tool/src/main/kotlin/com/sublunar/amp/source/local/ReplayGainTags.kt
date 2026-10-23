package com.sublunar.amp.source.local

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * ReplayGain read straight out of a file's own tags.
 *
 * [MediaMetadataRetriever][android.media.MediaMetadataRetriever] — what
 * [LocalLibrary] reads everything else with — only answers a fixed list of
 * keys, and ReplayGain is not one of them. It is always a *custom* tag: a
 * `REPLAYGAIN_TRACK_GAIN` Vorbis comment in FLAC/Ogg/Opus, a `TXXX` frame in
 * MP3, an iTunes `----` atom in MP4. So the tag has to be read here, which is
 * cheap enough to do in the same pass that reads everything else: each parser
 * walks only the metadata at the front of the file (MP4 excepted, where `moov`
 * may sit at the end and is seeked to properly).
 *
 * The gain lands on [Track.gainDb] at scan time exactly as a server's does, so
 * playback treats a local file no differently from a Navidrome track.
 */
object ReplayGainTags {

    /**
     * Track gain in dB, or null when the file carries none.
     *
     * Album gain is the fallback, matching what the Subsonic client does with
     * the two fields a server sends: a record tagged only album-wide still
     * normalises, just against the album's reference instead of the track's.
     */
    fun gainDb(file: File): Float? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            when (file.extension.lowercase()) {
                "flac" -> pick(readFlac(raf))
                "ogg", "oga", "opus" -> pick(readOgg(raf))
                "mp3" -> pick(readId3(raf))
                "m4a", "m4b", "mp4", "aac" -> pick(readMp4(raf))
                // A container this doesn't know may still be one of the above
                // wearing the wrong name; the magic bytes decide.
                else -> pick(bySignature(raf))
            }
        }
    }.getOrNull()

    // --- picking a value out of the collected tags ---------------------------

    private fun pick(tags: Map<String, String>): Float? {
        if (tags.isEmpty()) return null
        tags[TRACK_GAIN]?.let { parseDb(it) }?.let { return it }
        tags[ALBUM_GAIN]?.let { parseDb(it) }?.let { return it }
        // Opus states gain as R128: a Q7.8 fixed-point dB relative to -23 LUFS,
        // where ReplayGain's reference is -18 — hence the 5 dB shift, without
        // which every Opus file would play 5 dB quieter than the same music in
        // any other container.
        tags[R128_TRACK_GAIN]?.trim()?.toIntOrNull()?.let { return sane(it / 256f + R128_TO_RG_DB) }
        tags[R128_ALBUM_GAIN]?.trim()?.toIntOrNull()?.let { return sane(it / 256f + R128_TO_RG_DB) }
        return null
    }

    /** Values are written "-7.15 dB", "-7.15dB" or bare; take the number. */
    private fun parseDb(raw: String): Float? {
        val text = raw.trim().dropLastWhile { it == 'b' || it == 'B' || it == 'd' || it == 'D' }.trim()
        return sane(text.toFloatOrNull() ?: return null)
    }

    /**
     * A plausible gain, or nothing.
     *
     * A misparse is not a small error here — it is a volume the user did not
     * ask for — so a value outside what any real measurement produces is
     * treated as evidence the parse went wrong rather than applied.
     */
    private fun sane(db: Float): Float? =
        db.takeIf { it.isFinite() && it >= MIN_DB && it <= MAX_DB }

    // --- Vorbis comments (FLAC, Ogg, Opus) -----------------------------------

    /**
     * A comment list: a vendor string, a count, then `KEY=VALUE` entries, all
     * with little-endian lengths.
     */
    private fun vorbisComments(data: ByteArray, start: Int): Map<String, String> {
        val out = HashMap<String, String>()
        var p = start
        val vendorLen = le32(data, p) ?: return out
        if (vendorLen < 0) return out
        p += 4 + vendorLen
        val count = le32(data, p) ?: return out
        p += 4
        repeat(count.coerceIn(0, MAX_COMMENTS)) {
            val len = le32(data, p) ?: return out
            p += 4
            if (len < 0 || p + len > data.size) return out
            val entry = String(data, p, len, Charsets.UTF_8)
            p += len
            val eq = entry.indexOf('=')
            if (eq > 0) out[entry.substring(0, eq).uppercase()] = entry.substring(eq + 1)
        }
        return out
    }

    /** Walk FLAC's metadata blocks to the Vorbis comment one (type 4). */
    private fun readFlac(raf: RandomAccessFile): Map<String, String> {
        raf.seek(0)
        val magic = ByteArray(4)
        if (raf.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "fLaC") return emptyMap()
        repeat(MAX_BLOCKS) {
            val header = ByteArray(4)
            if (raf.read(header) != 4) return emptyMap()
            val last = (header[0].toInt() and 0x80) != 0
            val type = header[0].toInt() and 0x7F
            val length = be24(header, 1)
            if (length < 0 || length > MAX_BLOCK_BYTES) return emptyMap()
            if (type == FLAC_VORBIS_COMMENT) {
                val block = ByteArray(length)
                if (raf.read(block) != length) return emptyMap()
                return vorbisComments(block, 0)
            }
            raf.seek(raf.filePointer + length)
            if (last) return emptyMap()
        }
        return emptyMap()
    }

    /**
     * Ogg: reassemble the front pages, then read whichever comment header the
     * stream carries.
     *
     * Only the first pages are needed — the comment header is required to come
     * second, right after the identification header — and reassembling them
     * sidesteps the packet that spans a page boundary, which is otherwise the
     * one case a front-of-file scan gets wrong.
     */
    private fun readOgg(raf: RandomAccessFile): Map<String, String> {
        val payload = oggPayload(raf) ?: return emptyMap()
        indexOf(payload, "OpusTags")?.let { return vorbisComments(payload, it + 8) }
        // Vorbis' comment header packet, found by its leading type byte of 3
        // *and* the codec name: "vorbis" alone also matches the identification
        // header on the page before it, which would parse as nonsense.
        indexOf(payload, "\u0003vorbis")?.let { return vorbisComments(payload, it + 7) }
        // FLAC inside Ogg carries FLAC's own metadata blocks.
        indexOf(payload, "fLaC")?.let { at ->
            return flacBlocks(payload.copyOfRange(at, payload.size))
        }
        return emptyMap()
    }

    private fun oggPayload(raf: RandomAccessFile): ByteArray? {
        raf.seek(0)
        val head = ByteArray(4)
        if (raf.read(head) != 4 || String(head, Charsets.US_ASCII) != "OggS") return null
        raf.seek(0)
        val out = ByteArrayOutputStream()
        repeat(MAX_OGG_PAGES) {
            val header = ByteArray(OGG_HEADER_BYTES)
            if (raf.read(header) != OGG_HEADER_BYTES) return out.toByteArray()
            if (String(header, 0, 4, Charsets.US_ASCII) != "OggS") return out.toByteArray()
            val segments = header[26].toInt() and 0xFF
            val table = ByteArray(segments)
            if (raf.read(table) != segments) return out.toByteArray()
            val length = table.sumOf { it.toInt() and 0xFF }
            val body = ByteArray(length)
            if (raf.read(body) != length) return out.toByteArray()
            out.write(body)
            if (out.size() > MAX_OGG_BYTES) return out.toByteArray()
        }
        return out.toByteArray()
    }

    /** FLAC metadata blocks already in memory (the Ogg-embedded case). */
    private fun flacBlocks(data: ByteArray): Map<String, String> {
        var p = 4 // past "fLaC"
        repeat(MAX_BLOCKS) {
            if (p + 4 > data.size) return emptyMap()
            val last = (data[p].toInt() and 0x80) != 0
            val type = data[p].toInt() and 0x7F
            val length = be24(data, p + 1)
            p += 4
            if (length < 0 || p + length > data.size) return emptyMap()
            if (type == FLAC_VORBIS_COMMENT) return vorbisComments(data, p)
            p += length
            if (last) return emptyMap()
        }
        return emptyMap()
    }

    // --- ID3v2 (MP3) ----------------------------------------------------------

    private fun readId3(raf: RandomAccessFile): Map<String, String> {
        raf.seek(0)
        val header = ByteArray(10)
        if (raf.read(header) != 10) return emptyMap()
        if (String(header, 0, 3, Charsets.US_ASCII) != "ID3") return emptyMap()
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val size = syncSafe(header, 6)
        if (size <= 0 || size > MAX_ID3_BYTES) return emptyMap()
        val body = ByteArray(size)
        if (raf.read(body) != size) return emptyMap()

        var p = 0
        // An extended header sits before the frames and states its own length.
        if (flags and 0x40 != 0) {
            val extended = if (major >= 4) syncSafe(body, 0) else be32(body, 0) + 4
            if (extended <= 0 || extended >= size) return emptyMap()
            p += extended
        }

        val out = HashMap<String, String>()
        while (p + ID3_FRAME_HEADER <= size) {
            val id = String(body, p, 4, Charsets.US_ASCII)
            // Padding: the frames are over and the rest of the tag is zeroes.
            if (id[0] == '\u0000') break
            // v2.4 made frame sizes syncsafe; v2.3 left them plain.
            val frameSize = if (major >= 4) syncSafe(body, p + 4) else be32(body, p + 4)
            p += ID3_FRAME_HEADER
            if (frameSize <= 0 || p + frameSize > size) break
            if (id == "TXXX") {
                txxx(body, p, frameSize)?.let { (key, value) -> out[key.uppercase()] = value }
            }
            p += frameSize
        }
        return out
    }

    /** A TXXX frame: an encoding byte, then a description and a value. */
    private fun txxx(body: ByteArray, at: Int, size: Int): Pair<String, String>? {
        val encoding = body[at].toInt() and 0xFF
        val text = at + 1
        val end = at + size
        return when (encoding) {
            ID3_UTF16_BOM, ID3_UTF16_BE -> {
                val charset = if (encoding == ID3_UTF16_BE) Charsets.UTF_16BE else Charsets.UTF_16
                val split = indexOfDoubleNull(body, text, end) ?: return null
                val key = String(body, text, split - text, charset).trim(BOM)
                val value = String(body, split + 2, end - split - 2, charset).trim(BOM)
                key to value
            }
            else -> {
                val charset = if (encoding == ID3_UTF8) Charsets.UTF_8 else Charsets.ISO_8859_1
                val split = indexOfNull(body, text, end) ?: return null
                val key = String(body, text, split - text, charset)
                val value = String(body, split + 1, end - split - 1, charset).trimEnd(' ', '\u0000')
                key to value
            }
        }
    }

    // --- MP4 / M4A ------------------------------------------------------------

    /**
     * Walk down to `moov/udta/meta/ilst` and read the `----` freeform atoms,
     * which is where iTunes-style tags — ReplayGain among them — are kept.
     */
    private fun readMp4(raf: RandomAccessFile): Map<String, String> {
        val ilst = descend(raf, 0, raf.length(), listOf("moov", "udta", "meta", "ilst"))
            ?: return emptyMap()
        val out = HashMap<String, String>()
        var pos = ilst.first
        val end = ilst.second
        while (pos + ATOM_HEADER <= end) {
            val (size, type, body) = atom(raf, pos) ?: break
            if (type == "----") {
                freeform(raf, body, pos + size)?.let { (k, v) -> out[k.uppercase()] = v }
            }
            if (size <= 0) break
            pos += size
        }
        return out
    }

    /** The `mean`/`name`/`data` triplet inside one freeform atom. */
    private fun freeform(raf: RandomAccessFile, from: Long, end: Long): Pair<String, String>? {
        var pos = from
        var name: String? = null
        var value: String? = null
        while (pos + ATOM_HEADER <= end) {
            val (size, type, body) = atom(raf, pos) ?: return null
            val length = (pos + size - body).toInt()
            if (length < 0 || length > MAX_ATOM_BYTES) return null
            when (type) {
                // Carries 4 bytes of version and flags before its text.
                "name" -> name = readText(raf, body + 4, length - 4)
                // `data` adds a 4-byte locale after those.
                "data" -> value = readText(raf, body + 8, length - 8)
            }
            if (size <= 0) return null
            pos += size
        }
        return (name ?: return null) to (value ?: return null)
    }

    private fun descend(
        raf: RandomAccessFile,
        from: Long,
        to: Long,
        path: List<String>,
    ): Pair<Long, Long>? {
        var pos = from
        while (pos + ATOM_HEADER <= to) {
            val (size, type, body) = atom(raf, pos) ?: return null
            if (size <= 0) return null
            if (type == path.first()) {
                // `meta` is a full atom: version and flags come before its children.
                val start = if (type == "meta") body + 4 else body
                val end = pos + size
                return if (path.size == 1) start to end else descend(raf, start, end, path.drop(1))
            }
            pos += size
        }
        return null
    }

    /** One atom header: its total size, its type, and where its body starts. */
    private fun atom(raf: RandomAccessFile, at: Long): Triple<Long, String, Long>? {
        raf.seek(at)
        val header = ByteArray(ATOM_HEADER.toInt())
        if (raf.read(header) != ATOM_HEADER.toInt()) return null
        val type = String(header, 4, 4, Charsets.US_ASCII)
        var size = be32(header, 0).toLong() and 0xFFFFFFFFL
        var body = at + ATOM_HEADER
        // A size of 1 means the real, 64-bit size follows the type.
        if (size == 1L) {
            val extended = ByteArray(8)
            if (raf.read(extended) != 8) return null
            size = be64(extended, 0)
            body += 8
        }
        if (size < ATOM_HEADER) return null
        return Triple(size, type, body)
    }

    private fun readText(raf: RandomAccessFile, at: Long, length: Int): String? {
        if (length <= 0 || length > MAX_ATOM_BYTES) return null
        raf.seek(at)
        val buffer = ByteArray(length)
        if (raf.read(buffer) != length) return null
        return String(buffer, Charsets.UTF_8)
    }

    // --- fallback -------------------------------------------------------------

    /** An unfamiliar extension: let the first bytes say what the file is. */
    private fun bySignature(raf: RandomAccessFile): Map<String, String> {
        raf.seek(0)
        val magic = ByteArray(4)
        if (raf.read(magic) != 4) return emptyMap()
        val four = String(magic, Charsets.US_ASCII)
        val three = String(magic, 0, 3, Charsets.US_ASCII)
        return when {
            four == "fLaC" -> readFlac(raf)
            four == "OggS" -> readOgg(raf)
            three == "ID3" -> readId3(raf)
            else -> readMp4(raf)
        }
    }

    // --- byte helpers ---------------------------------------------------------

    private fun le32(data: ByteArray, at: Int): Int? {
        if (at < 0 || at + 4 > data.size) return null
        return (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)
    }

    private fun be24(data: ByteArray, at: Int): Int {
        if (at + 3 > data.size) return -1
        return ((data[at].toInt() and 0xFF) shl 16) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            (data[at + 2].toInt() and 0xFF)
    }

    private fun be32(data: ByteArray, at: Int): Int {
        if (at + 4 > data.size) return -1
        return ((data[at].toInt() and 0xFF) shl 24) or
            ((data[at + 1].toInt() and 0xFF) shl 16) or
            ((data[at + 2].toInt() and 0xFF) shl 8) or
            (data[at + 3].toInt() and 0xFF)
    }

    private fun be64(data: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
        return value
    }

    /** ID3's sizes drop the high bit of every byte so they can't fake a sync. */
    private fun syncSafe(data: ByteArray, at: Int): Int {
        if (at + 4 > data.size) return -1
        return ((data[at].toInt() and 0x7F) shl 21) or
            ((data[at + 1].toInt() and 0x7F) shl 14) or
            ((data[at + 2].toInt() and 0x7F) shl 7) or
            (data[at + 3].toInt() and 0x7F)
    }

    private fun indexOf(data: ByteArray, needle: String): Int? {
        val bytes = needle.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..data.size - bytes.size) {
            for (j in bytes.indices) if (data[i + j] != bytes[j]) continue@outer
            return i
        }
        return null
    }

    private fun indexOfNull(data: ByteArray, from: Int, to: Int): Int? {
        for (i in from until to) if (data[i].toInt() == 0) return i
        return null
    }

    private fun indexOfDoubleNull(data: ByteArray, from: Int, to: Int): Int? {
        var i = from
        while (i + 1 < to) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
            i += 2
        }
        return null
    }

    private const val TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
    private const val ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
    private const val R128_TRACK_GAIN = "R128_TRACK_GAIN"
    private const val R128_ALBUM_GAIN = "R128_ALBUM_GAIN"

    /** ReplayGain references -18 LUFS, R128 -23; the difference is the shift. */
    private const val R128_TO_RG_DB = 5f

    /** No real measurement lands outside this, so anything that does is a bug. */
    private const val MIN_DB = -60f
    private const val MAX_DB = 30f

    private const val BOM = '\uFEFF'
    private const val FLAC_VORBIS_COMMENT = 4
    private const val MAX_BLOCKS = 32
    private const val MAX_BLOCK_BYTES = 8 * 1024 * 1024
    private const val MAX_COMMENTS = 512
    private const val MAX_OGG_PAGES = 8
    private const val MAX_OGG_BYTES = 256 * 1024
    private const val OGG_HEADER_BYTES = 27
    private const val MAX_ID3_BYTES = 8 * 1024 * 1024
    private const val ID3_FRAME_HEADER = 10
    private const val ID3_UTF16_BOM = 1
    private const val ID3_UTF16_BE = 2
    private const val ID3_UTF8 = 3
    private const val ATOM_HEADER = 8L
    private const val MAX_ATOM_BYTES = 64 * 1024
}

package com.sublunar.amp.data

import android.os.StatFs
import com.sublunar.amp.log.Log
import com.sublunar.amp.log.logName
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * The on-disk half of offline playback: downloaded audio under the tool's private
 * files directory, plus the storage arithmetic the settings UI needs.
 *
 * A folder per source, because two servers can hand out the same track id for
 * different music — and because removing a source has to be able to take its
 * audio with it without touching anyone else's. Every call names the source it
 * means: the downloader fetches for every source at once, whichever is being
 * browsed, so there is no "current" folder for it to default to.
 *
 * Plain [File] work — the plugin sandbox allows `java.io` and `android.os.StatFs`,
 * so none of this needs SDK support.
 */
class DownloadStore(private val filesDir: File) {

    /** Behind the wall, built on first use — see NetworkGate.transferClient. */
    private val http by lazy {
        NetworkGate.transferClient(CONNECT_TIMEOUT_MS.toLong(), READ_TIMEOUT_MS.toLong())
    }

    private val downloadsDir: File get() = File(filesDir, "downloads")

    private fun rootFor(sourceId: String): File = File(downloadsDir, sourceId)

    /** Every source's folder — used by the sweep and by "delete everything". */
    private fun allRoots(): List<File> =
        downloadsDir.listFiles().orEmpty().filter { it.isDirectory }

    init {
        // Sweep partials left by a download or an mp3 index the process didn't
        // live to finish: they're never resumable, and only take space. Filtered
        // in Kotlin rather than via a File(name)Filter lambda, whose SAM
        // overload resolution is easy to get silently wrong.
        downloadsDir.mkdirs()
        allRoots().flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && (it.name.endsWith(PART_SUFFIX) || it.name.endsWith(Mp3VbrIndex.INDEXING_SUFFIX)) }
            .forEach { it.delete() }
    }

    /**
     * Everything [sourceId] has on disk, as (trackId, format) pairs, to rebuild
     * the download index from — see Downloader.reindexFromDisk. The files are
     * named `<trackId>.<suffix>`, which is enough.
     */
    fun onDisk(sourceId: String): List<Pair<String, StreamFormat>> =
        rootFor(sourceId).listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile || file.name.endsWith(PART_SUFFIX)) return@mapNotNull null
            val dot = file.name.lastIndexOf('.')
            if (dot <= 0) return@mapNotNull null
            val id = file.name.substring(0, dot)
            val suffix = file.name.substring(dot + 1)
            val format = StreamFormat.entries.firstOrNull { it.suffix == suffix }
                ?: return@mapNotNull null
            id to format
        }

    fun fileFor(sourceId: String, trackId: String, format: StreamFormat): File =
        File(rootFor(sourceId), "$trackId.${format.suffix}")

    fun existing(sourceId: String, fileName: String): File? =
        File(rootFor(sourceId), fileName).takeIf { it.isFile }

    /** What one [download] came to. */
    sealed interface Fetched {
        data class Saved(val file: File) : Fetched

        /** The server answered [status] instead of the song. */
        data class Refused(val status: Int) : Fetched {
            val why: String get() = "Server said $status"
        }

        /** Nothing usable came back, for the reason given, in the words the Downloads page shows. */
        data class Failed(val why: String) : Fetched
    }

    /**
     * Download [url] to the file for this track of [sourceId]. Writes to a
     * temporary file first so an interrupted download can never be mistaken for
     * a complete one.
     */
    suspend fun download(
        sourceId: String,
        url: String,
        trackId: String,
        format: StreamFormat,
        /** The song's length, which sets how small a real copy of it can be — see [MIN_BYTES_PER_SECOND]. */
        durationMs: Long = 0,
    ): Fetched =
        withContext(Dispatchers.IO) {
            // The id is the file's name, and one that is a path would put the
            // server's bytes outside this source's folder.
            if ('/' in trackId) return@withContext Fetched.Failed("Server sent a track id that isn't a file name")
            val target = fileFor(sourceId, trackId, format)
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "${target.name}$PART_SUFFIX")
            val kind = endpointKind(url.toHttpUrlOrNull()?.encodedPath)
            val startedMs = System.currentTimeMillis()
            try {
                // Through the wall, like every other byte — see NetworkGate: it
                // asks at the connect, the handshake, each redirect and retry,
                // cuts the body when the rule closes, and can be slammed.
                val call = http.newCall(Request.Builder().url(url).build())
                // A cancelled download lets go of the socket instead of reading
                // on to the end of a file nobody wants any more. A completion
                // handler can't do this — the job can't complete while its body
                // is blocked in the read — so a sibling waits for the
                // cancellation itself and closes the call from outside.
                val onCancel = launch { try { awaitCancellation() } finally { call.cancel() } }
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "download: HTTP ${response.code} for $kind")
                            return@withContext Fetched.Refused(response.code)
                        }
                        // A server can answer 200 with an error *document* — Subsonic
                        // does — and saved under the track's name that would be "the
                        // song" from then on: a file that never plays and is never
                        // fetched again.
                        val type = response.body.contentType()
                        if (type != null && (type.type == "text" || isDocumentSubtype(type.subtype))) {
                            Log.w(TAG, "download: ${type.type}/${type.subtype}, not audio, from $kind")
                            return@withContext Fetched.Failed("Server sent $type instead of audio")
                        }
                        // The body ends when the server says, so the phone's free
                        // space mustn't be what ends it.
                        val room = freeBytes().takeIf { it > 0 }?.minus(KEEP_FREE_BYTES) ?: Long.MAX_VALUE
                        response.body.byteStream().use { input ->
                            // 8 KiB (copyTo's default) into an unbuffered FileOutputStream
                            // is a write syscall every 8 KiB; on this hardware that costs
                            // more than the transfer does.
                            BufferedOutputStream(partial.outputStream(), BUFFER_BYTES).use { output ->
                                copyAtMost(input, output, room, BUFFER_BYTES)
                            }
                        }
                    }
                } finally {
                    onCancel.cancel()
                }
                // Cancelled while the last bytes arrived: not a download.
                ensureActive()
                // A success is counted in the worker's line at its end — see Downloader.drain.
                val bytes = partial.length()
                // A conversion that failed on the server can still answer 200
                // with a few bytes, and kept, that is the song from then on: a
                // file that never plays and is never fetched again. (From
                // Garado's fork of Amp, scaled here by the song's length so a
                // short voice memo isn't mistaken for one.)
                val plausible = maxOf(MIN_BYTES, durationMs / 1000 * MIN_BYTES_PER_SECOND)
                if (bytes < plausible) {
                    Log.w(TAG, "download: $bytes bytes for ${durationMs / 1000} s from $kind, discarded")
                    partial.delete()
                    return@withContext Fetched.Failed(if (bytes == 0L) "Server sent an empty file" else "Server sent a broken file")
                }
                if (!partial.renameTo(target)) {
                    partial.delete()
                    return@withContext Fetched.Failed("Couldn't save ${target.name}")
                }
                Fetched.Saved(target)
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "download from $kind failed after ${partial.length()} bytes, " +
                        "${System.currentTimeMillis() - startedMs} ms: ${e.logName()}",
                )
                partial.delete()
                // No `javaClass` here — the plugin sandbox forbids reflection.
                Fetched.Failed(e.message ?: "Download failed")
            }
        }

    suspend fun delete(sourceId: String, fileName: String) = withContext(Dispatchers.IO) {
        File(rootFor(sourceId), fileName).delete().also { deletionCount.incrementAndGet() }
    }

    /** Throw away one source's audio entirely — see App.forgetSource. */
    fun deleteSource(id: String) {
        File(downloadsDir, id).deleteRecursively()
        deletionCount.incrementAndGet()
    }

    /**
     * Bumped once each deletion has finished, so a running total of
     * [usedBytesEverywhere] measured while one was under way is measured again.
     */
    val deletions: Int get() = deletionCount.get()
    private val deletionCount = AtomicInteger()

    /** Free space on the volume holding the downloads. */
    fun freeBytes(): Long =
        runCatching { StatFs(downloadsDir.apply { mkdirs() }.absolutePath).availableBytes }.getOrDefault(0L)

    /**
     * Everything the tool has downloaded, across every source, read off the disk.
     *
     * The disk rather than the downloads table: the table lives in the active
     * source's database and can only ever answer for that one, where the budget
     * this is weighed against covers the whole phone. Files are the truth here
     * anyway — a row without its file is not using any storage.
     */
    fun usedBytesEverywhere(): Long = allRoots().sumOf { usedBytesIn(it) }

    /** One source's share of [usedBytesEverywhere], off the disk the same way. */
    fun usedBytes(sourceId: String): Long = usedBytesIn(rootFor(sourceId))

    private fun usedBytesIn(root: File): Long =
        root.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
            .sumOf { it.length() }

    /**
     * The largest limit the user may choose.
     *
     * The SDK imposes no quota of its own, so the real constraint is leaving the
     * phone usable: a share of what the tool could occupy, with an absolute
     * ceiling on top. What it *could* occupy is the free space **plus what it is
     * already holding** — against free space alone, a phone with 56 GB
     * downloaded and 10 GB spare offered at most 7 GB.
     *
     * Never below what is already downloaded, either: choosing a cap under the
     * current usage would silently mean "throw away the difference".
     */
    fun maxSelectableBytes(): Long {
        val used = usedBytesEverywhere()
        val share = ((freeBytes() + used) * FREE_SPACE_SHARE).toLong()
        return share.coerceAtMost(ABSOLUTE_CEILING)
            .coerceAtLeast(used)
            .coerceAtLeast(AppSettings.DEFAULT_DOWNLOAD_LIMIT)
    }

    companion object {
        /** Below this a download is no song at all, however short. */
        private const val MIN_BYTES = 2048L

        /**
         * 8 kbps, a third of the lowest bitrate anything is encoded at (a 24 kbps
         * voice memo): a real copy is always bigger than its length at this.
         */
        private const val MIN_BYTES_PER_SECOND = 1024L

        private const val TAG = "AmpDl"
        private const val BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PART_SUFFIX = ".part"

        /** Android's own low-storage line: StorageManager warns below the smaller of 5% and this. */
        private const val KEEP_FREE_BYTES = 500L * 1024 * 1024

        // 64 GB is reachable on the LP3's 128 GB only by claiming more than half
        // of what's free; a quarter still stays behind for the rest of the phone.
        private const val FREE_SPACE_SHARE = 0.75
        private const val ABSOLUTE_CEILING = 64L * 1024 * 1024 * 1024
    }
}

/**
 * [input] into [output] to its end, throwing once more than [ceiling] bytes
 * have come, before any of the excess is written.
 */
internal fun copyAtMost(input: InputStream, output: OutputStream, ceiling: Long, bufferBytes: Int) {
    val buffer = ByteArray(bufferBytes)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return
        copied += read
        if (copied > ceiling) throw IOException("Not enough space on the phone")
        output.write(buffer, 0, read)
    }
}

/** File extension to store a downloaded stream under. */
val StreamFormat.suffix: String
    get() = when (this) {
        StreamFormat.MP3 -> "mp3"
        StreamFormat.OPUS -> "opus"
        StreamFormat.FLAC -> "flac"
        // "raw" is whatever the server holds; the container is unknown up front,
        // and ExoPlayer sniffs content rather than trusting the extension.
        StreamFormat.RAW -> "audio"
    }

/**
 * Always in GB, for a used/allowed pair — "740 MB" against "50.3 GB" is hard to
 * compare at a glance.
 */
fun formatGb(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 100) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
}

/** "1.4 GB", "820 MB" — sizes as the settings screens show them. */
fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    if (gb >= 1.0) {
        return if (gb >= 10) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
    }
    val mb = bytes / 1024.0 / 1024.0
    return "${mb.toInt()} MB"
}

/**
 * Whether an answer of this `application/` subtype is a document rather than a
 * song — and a playlist of segments is one too: an HLS (`vnd.apple.mpegurl`) or
 * DASH (`dash+xml`) answer saved under the track's name is a few lines of text
 * that will never play.
 */
internal fun isDocumentSubtype(subtype: String): Boolean =
    subtype in setOf("json", "xml", "html", "javascript") ||
        subtype.endsWith("+xml") || subtype.endsWith("+json") || "mpegurl" in subtype

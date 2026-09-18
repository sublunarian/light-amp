package com.sublunar.amp.data

import android.os.StatFs
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private fun rootFor(sourceId: String): File =
        File(downloadsDir, sourceId).apply { mkdirs() }

    /** Every source's folder — used by the sweep and by "delete everything". */
    private fun allRoots(): List<File> =
        downloadsDir.listFiles().orEmpty().filter { it.isDirectory }

    init {
        // Sweep partials left by a download the process didn't live to finish;
        // they're never resumable and would otherwise count against the budget.
        // Filtered in Kotlin rather than via a File(name)Filter lambda, whose SAM
        // overload resolution is easy to get silently wrong.
        //
        // Legacy layout: downloads used to sit directly in the folder that now
        // holds one directory per source. Anything left there is moved into the
        // first source's folder by [adoptLegacyFiles].
        val downloads = downloadsDir.apply { mkdirs() }
        (downloads.listFiles().orEmpty().toList() + allRoots().flatMap { it.listFiles().orEmpty().toList() })
            .filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
            .forEach { it.delete() }
    }

    /**
     * Move pre-sources downloads into [sourceId]'s folder.
     *
     * Called once for the source that inherits the old single-server setup; the
     * files are the durable artefact, and re-fetching gigabytes because the
     * layout changed underneath them is not an acceptable upgrade.
     */
    fun adoptLegacyFiles(sourceId: String) {
        val target = rootFor(sourceId)
        downloadsDir.listFiles().orEmpty()
            .filter { it.isFile }
            .forEach { it.renameTo(File(target, it.name)) }
    }

    /**
     * Everything [sourceId] has on disk, as (trackId, format) pairs.
     *
     * The download index lives in Room, and the SDK drops every table whenever the
     * schema version moves — so a bump would otherwise strand gigabytes of audio
     * as unreferenced files and re-fetch the lot. The files are named
     * `<trackId>.<suffix>`, which is enough to rebuild the index from.
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

    /**
     * Download [url] to the file for this track of [sourceId]. Writes to a
     * temporary file first so an interrupted download can never be mistaken for
     * a complete one.
     */
    suspend fun download(sourceId: String, url: String, trackId: String, format: StreamFormat): File? =
        withContext(Dispatchers.IO) {
            val target = fileFor(sourceId, trackId, format)
            val partial = File(target.parentFile, "${target.name}$PART_SUFFIX")
            try {
                val startedMs = System.currentTimeMillis()
                // Through the wall, like every other byte — see NetworkGate. This
                // used to be a bare URLConnection with a check in front of it and
                // one between reads, which left the connect, the TLS handshake, any
                // redirect and any silent retry ungated, on a stack whose socket
                // nothing here could close. The gated client asks at each of
                // those, cuts the body when the rule closes, and can be slammed.
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
                            lastError = "Server said ${response.code}"
                            return@withContext null
                        }
                        // A server can answer 200 with an error *document* — Subsonic
                        // does — and saved under the track's name that would be "the
                        // song" from then on: a file that never plays and is never
                        // fetched again.
                        val type = response.body.contentType()
                        if (type != null && (type.type == "text" || type.subtype in NOT_AUDIO_SUBTYPES)) {
                            lastError = "Server sent $type instead of audio"
                            return@withContext null
                        }
                        response.body.byteStream().use { input ->
                            // 8 KiB (copyTo's default) into an unbuffered FileOutputStream
                            // is a write syscall every 8 KiB; on this hardware that costs
                            // more than the transfer does.
                            BufferedOutputStream(partial.outputStream(), BUFFER_BYTES).use { output ->
                                input.copyTo(output, BUFFER_BYTES)
                            }
                        }
                    }
                } finally {
                    onCancel.cancel()
                }
                // Cancelled while the last bytes arrived: not a download.
                ensureActive()
                val elapsedMs = System.currentTimeMillis() - startedMs
                val bytes = partial.length()
                if (elapsedMs > 0) {
                    val kbPerSec = bytes * 1000 / elapsedMs / 1024
                    Log.i(TAG, "downloaded $bytes bytes in ${elapsedMs}ms (${kbPerSec} KB/s) $trackId")
                }
                if (partial.length() == 0L) {
                    partial.delete()
                    return@withContext null
                }
                if (!partial.renameTo(target)) {
                    partial.delete()
                    lastError = "Couldn't save ${target.name}"
                    return@withContext null
                }
                target
            } catch (e: Exception) {
                Log.w(TAG, "download failed after ${partial.length()} bytes for $trackId", e)
                partial.delete()
                // No `javaClass` here — the plugin sandbox forbids reflection.
                lastError = e.message ?: "Download failed"
                null
            }
        }

    /** Reason the most recent download failed, for surfacing in the UI. */
    @Volatile
    var lastError: String? = null
        private set

    suspend fun delete(sourceId: String, fileName: String) = withContext(Dispatchers.IO) {
        File(rootFor(sourceId), fileName).delete()
    }

    /** Throw away one source's audio entirely — see App.forgetSource. */
    fun deleteSource(id: String) {
        File(downloadsDir, id).deleteRecursively()
    }

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
    fun usedBytesEverywhere(): Long =
        allRoots().sumOf { root ->
            root.listFiles().orEmpty()
                .filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
                .sumOf { it.length() }
        }

    /**
     * TEMPORARY MIGRATION — DELETE BEFORE SUBMITTING THE TOOL FOR COMMUNITY
     * REVIEW.
     *
     * ## What this is
     *
     * Index every mp3 on disk that lacks one — see [Mp3VbrIndex] for what an
     * index is and why a streamed mp3 arrives without one. Amp downloaded mp3s
     * without writing that index until 2026-09-03. This repairs the files
     * already on people's phones, once, so that a fix shipped in an update does
     * not require re-fetching a library to take effect.
     *
     * ## Why it is temporary
     *
     * Everything downloaded since that date is indexed as it lands, by the call
     * in [Downloader]'s download loop. That is the real mechanism and it is
     * permanent. This walk exists only for files that predate it, so it is
     * dead weight the moment those files are gone — and it is not free: it
     * reads the first frame of every downloaded mp3 in every source's folder,
     * which for a large offline library is tens of megabytes of disk at
     * startup to discover there is nothing to do. It is gated to run once per
     * install ([AppSettings.mp3IndexRepairNeeded]) so that cost is paid once,
     * but the code should not outlive the installs that need it.
     *
     * ## How to remove it (all of it)
     *
     * 1. This function.
     * 2. Its caller in `App.boot` — the `scope.launch(Dispatchers.IO)` block
     *    marked TEMPORARY, which claims the flag and calls this.
     * 3. [AppSettings.mp3IndexRepairNeeded], [AppSettings.markMp3IndexRepaired]
     *    and their `MP3_INDEX_REPAIRED` key.
     *
     * Keep [Mp3VbrIndex] itself and the call in [Downloader]. Those are not
     * part of this migration — without them, every mp3 downloaded from a
     * server that transcodes as a stream goes back to reporting a length that
     * can be wrong by a factor of seven, playing silence past its end, and
     * seeking to the wrong place.
     *
     * ## When it is safe to remove
     *
     * When it is reasonable to expect that anyone still using downloads made
     * before 2026-09-03 has launched a version carrying this at least once.
     * Nothing breaks for a straggler who has not: their old files keep the
     * wrong duration until re-downloaded, which is the state they were already
     * in. No data is lost either way.
     *
     * ## One detail, if you are reading this to change it
     *
     * The downloads table's `bytes` for a repaired file is left as it was, a
     * few hundred bytes short of the truth. That is deliberate: the storage
     * figure the user sees is read off the disk rather than the table, and the
     * table lives in one source's database while this covers every source.
     */
    fun indexMp3s(): Int {
        var written = 0
        allRoots().forEach { root ->
            root.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".${StreamFormat.MP3.suffix}") }
                .forEach { file ->
                    runCatching { Mp3VbrIndex.index(file) }
                        .onSuccess {
                            if (it is Mp3VbrIndex.Outcome.Written) {
                                written++
                                Log.i("AmpMp3", "indexed ${file.name}: ${it.frames} frames")
                            }
                        }
                        .onFailure { Log.w("AmpMp3", "couldn't index ${file.name}", it) }
                }
        }
        return written
    }

    /**
     * The largest limit the user may choose.
     *
     * The SDK imposes no quota of its own — downloads go to ordinary app-private
     * storage — so the real constraint is leaving the phone usable: a fraction
     * of what the tool could occupy, with an absolute ceiling on top.
     *
     * What it *could* occupy is the free space **plus what it is already
     * holding**, because that space is its own — filling it is what put it
     * there. Measured against free space alone, a phone with 56GB downloaded
     * and 10GB spare offered a maximum of 7GB: an invitation to delete almost
     * everything, on a screen that never mentions deleting anything.
     *
     * Never below what is already downloaded, either. A cap under the current
     * usage cannot be shown as the state the user is in, and choosing it would
     * silently mean "throw away the difference".
     */
    fun maxSelectableBytes(): Long {
        val used = usedBytesEverywhere()
        val share = ((freeBytes() + used) * FREE_SPACE_SHARE).toLong()
        return share.coerceAtMost(ABSOLUTE_CEILING)
            .coerceAtLeast(used)
            .coerceAtLeast(AppSettings.DEFAULT_DOWNLOAD_LIMIT)
    }

    companion object {
        private const val TAG = "Downloads"
        private const val BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PART_SUFFIX = ".part"
        /** Content types that are an answer *about* a track, never the track. */
        private val NOT_AUDIO_SUBTYPES = setOf("json", "xml", "html")
        // 64 GB is reachable on the LP3's 128 GB of storage only if we're willing
        // to claim more than half of what's free, so the share goes up with the
        // ceiling; a quarter of free space still stays behind for the OS and the
        // rest of the phone.
        private const val FREE_SPACE_SHARE = 0.75
        private const val ABSOLUTE_CEILING = 64L * 1024 * 1024 * 1024
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

/** Rough audio quality order, used to decide whether streaming beats a download. */
val StreamFormat.qualityRank: Int
    get() = when (this) {
        StreamFormat.MP3 -> 1
        StreamFormat.OPUS -> 2
        StreamFormat.FLAC -> 3
        StreamFormat.RAW -> 4
    }

/** "1.4 GB", "820 MB" — sizes as the settings screens show them. */
/**
 * Always in GB, for the used/allowed pair on the Downloads page — mixing "740 MB"
 * against "50.3 GB" makes the two halves hard to compare at a glance.
 */
fun formatGb(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 100) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    if (gb >= 1.0) {
        return if (gb >= 10) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
    }
    val mb = bytes / 1024.0 / 1024.0
    return "${mb.toInt()} MB"
}

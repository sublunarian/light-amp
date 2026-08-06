package com.sublunar.amp.data

import android.os.StatFs
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The on-disk half of offline playback: downloaded audio under the tool's private
 * files directory, plus the storage arithmetic the settings UI needs.
 *
 * Plain [File] work — the plugin sandbox allows `java.io` and `android.os.StatFs`,
 * so none of this needs SDK support.
 */
class DownloadStore(
    private val filesDir: File,
    /**
     * Which source's downloads to work in.
     *
     * A folder per source, because two servers can hand out the same track id for
     * different music — and because removing a source has to be able to take its
     * audio with it without touching anyone else's.
     */
    private val sourceId: () -> String,
) {

    private val root: File
        get() = File(File(filesDir, "downloads"), sourceId()).apply { mkdirs() }

    /** Every source's folder — used by the sweep and by "delete everything". */
    private fun allRoots(): List<File> =
        File(filesDir, "downloads").listFiles().orEmpty().filter { it.isDirectory }

    init {
        // Sweep partials left by a download the process didn't live to finish;
        // they're never resumable and would otherwise count against the budget.
        // Filtered in Kotlin rather than via a File(name)Filter lambda, whose SAM
        // overload resolution is easy to get silently wrong.
        //
        // Legacy layout: downloads used to sit directly in the folder that now
        // holds one directory per source. Anything left there is moved into the
        // first source's folder by [adoptLegacyFiles].
        val downloads = File(filesDir, "downloads").apply { mkdirs() }
        (downloads.listFiles().orEmpty().toList() + allRoots().flatMap { it.listFiles().orEmpty().toList() })
            .filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
            .forEach { it.delete() }
    }

    /**
     * Move pre-sources downloads into this source's folder.
     *
     * Called once for the source that inherits the old single-server setup; the
     * files are the durable artefact, and re-fetching gigabytes because the
     * layout changed underneath them is not an acceptable upgrade.
     */
    fun adoptLegacyFiles() {
        val downloads = File(filesDir, "downloads")
        val target = root
        downloads.listFiles().orEmpty()
            .filter { it.isFile }
            .forEach { it.renameTo(File(target, it.name)) }
    }

    /**
     * Everything already on disk, as (trackId, format) pairs.
     *
     * The download index lives in Room, and the SDK drops every table whenever the
     * schema version moves — so a bump would otherwise strand gigabytes of audio
     * as unreferenced files and re-fetch the lot. The files are named
     * `<trackId>.<suffix>`, which is enough to rebuild the index from.
     */
    fun onDisk(): List<Pair<String, StreamFormat>> =
        root.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile || file.name.endsWith(PART_SUFFIX)) return@mapNotNull null
            val dot = file.name.lastIndexOf('.')
            if (dot <= 0) return@mapNotNull null
            val id = file.name.substring(0, dot)
            val suffix = file.name.substring(dot + 1)
            val format = StreamFormat.entries.firstOrNull { it.suffix == suffix }
                ?: return@mapNotNull null
            id to format
        }

    fun fileFor(trackId: String, format: StreamFormat): File =
        File(root, "$trackId.${format.suffix}")

    fun existing(fileName: String): File? = File(root, fileName).takeIf { it.isFile }

    /**
     * Download [url] to the file for this track. Writes to a temporary file first
     * so an interrupted download can never be mistaken for a complete one.
     */
    suspend fun download(url: String, trackId: String, format: StreamFormat): File? =
        withContext(Dispatchers.IO) {
            val target = fileFor(trackId, format)
            val partial = File(root, "${target.name}$PART_SUFFIX")
            try {
                val startedMs = System.currentTimeMillis()
                val connection = URL(url).openConnection()
                // Without these a half-open connection parks the worker forever,
                // which reads as "downloads have stopped" rather than as an error.
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.getInputStream().use { input ->
                    // 8 KiB (copyTo's default) into an unbuffered FileOutputStream
                    // is a write syscall every 8 KiB; on this hardware that costs
                    // more than the transfer does.
                    BufferedOutputStream(partial.outputStream(), BUFFER_BYTES).use { output ->
                        input.copyTo(output, BUFFER_BYTES)
                    }
                }
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

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(root, fileName).delete()
    }

    /** Throw away one source's audio entirely — see App.forgetSource. */
    fun deleteSource(id: String) {
        File(File(filesDir, "downloads"), id).deleteRecursively()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        root.listFiles()?.forEach { it.delete() }
        Unit
    }

    /** Free space on the volume holding the downloads. */
    fun freeBytes(): Long = runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(0L)

    /**
     * The largest limit the user may choose.
     *
     * The SDK imposes no quota of its own — downloads go to ordinary app-private
     * storage — so the real constraint is leaving the phone usable. That means a
     * fraction of what's *currently* free (so the cap falls as the device fills)
     * with an absolute ceiling on top, and never below the default so the setting
     * can't collapse to nothing on a full device.
     */
    fun maxSelectableBytes(): Long {
        val share = (freeBytes() * FREE_SPACE_SHARE).toLong()
        return share.coerceAtMost(ABSOLUTE_CEILING).coerceAtLeast(AppSettings.DEFAULT_DOWNLOAD_LIMIT)
    }

    companion object {
        private const val TAG = "Downloads"
        private const val BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val PART_SUFFIX = ".part"
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

package com.sublunar.amp.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.sublunar.amp.data.LocalLibrary
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.md5Hex
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Loads album art. Bytes are fetched once and cached on disk; decoded bitmaps
 * are downsampled to the requested display size and kept in a small memory LRU.
 * RGB_565 halves bitmap memory, which suits both the display and the battery.
 */
class ArtworkLoader(
    filesDir: File,
    private val serverClient: StateFlow<MusicServer?>,
) {
    private val http = HttpClient(OkHttp) { expectSuccess = false }
    private val diskDir = File(filesDir, "artwork").apply { mkdirs() }
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_ENTRIES) {}

    /**
     * A cover already decoded at this size, without suspending.
     *
     * Lets a list draw its covers on the *first* frame instead of a frame later:
     * without it, every return to a list showed a page of empty placeholders that
     * filled in a moment afterwards, even though the bitmaps were in memory the
     * whole time.
     */
    fun peek(coverArtId: String?, targetSizePx: Int): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        return memory.get("$coverArtId@${sizeBucket(targetSizePx)}")
    }

    suspend fun load(coverArtId: String?, targetSizePx: Int): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        val bucket = sizeBucket(targetSizePx)
        val memKey = "$coverArtId@$bucket"
        memory.get(memKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bytes = readDisk(coverArtId) ?: fetch(coverArtId)?.also { writeDisk(coverArtId, it) }
            ?: return@withContext null
            val bitmap = decodeDownsampled(bytes, bucket) ?: return@withContext null
            val image = bitmap.asImageBitmap()
            memory.put(memKey, image)
            image
        }
    }

    /**
     * Put a cover on disk without decoding it.
     *
     * Called as tracks are downloaded, so an offline library has its sleeves —
     * [load] finds them in the same place it would have written them itself.
     */
    suspend fun prefetch(coverArtId: String?) {
        if (coverArtId.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            if (diskFile(coverArtId).let { it.exists() && it.length() > 0 }) return@withContext
            fetch(coverArtId)?.let { writeDisk(coverArtId, it) }
        }
    }

    private suspend fun fetch(coverArtId: String): ByteArray? {
        // A local track's cover id is its own path: the sleeve is inside the
        // file, and there is no server to ask for it.
        LocalLibrary.fileOf(coverArtId)?.let { return embedded(it) }
        val url = serverClient.value?.coverArtUrl(coverArtId) ?: return null
        return try {
            val response = http.get(url)
            if (!response.status.isSuccess()) return null
            response.body<ByteArray>().takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The picture tagged into an audio file.
     *
     * [MediaMetadataRetriever] takes a path and needs no context, which is what
     * makes it usable from a tool at all — see LocalLibrary.
     */
    private fun embedded(file: File): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun diskFile(coverArtId: String) = File(diskDir, md5Hex(coverArtId))

    private fun readDisk(coverArtId: String): ByteArray? =
        diskFile(coverArtId).takeIf { it.exists() && it.length() > 0 }?.readBytes()

    private fun writeDisk(coverArtId: String, bytes: ByteArray) {
        try {
            diskFile(coverArtId).writeBytes(bytes)
        } catch (_: Exception) {
            // A failed cache write is non-fatal; the image still displays this time.
        }
    }

    private fun decodeDownsampled(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, target)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, target: Int): Int {
        if (target <= 0 || width <= 0 || height <= 0) return 1
        var sample = 1
        val smallest = minOf(width, height)
        while (smallest / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }

    /** Snap to a few size buckets so different callers reuse the same decode. */
    private fun sizeBucket(px: Int): Int = when {
        px <= 0 -> 128
        px <= 160 -> 128
        px <= 360 -> 320
        px <= 720 -> 640
        else -> 1024
    }

    companion object {
        private const val MEMORY_ENTRIES = 150
    }
}

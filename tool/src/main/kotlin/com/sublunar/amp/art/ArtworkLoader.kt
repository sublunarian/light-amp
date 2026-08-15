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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Loads album art. Bytes are fetched once and cached on disk; decoded bitmaps
 * are downsampled to the requested display size and kept in a small memory LRU.
 * RGB_565 halves bitmap memory, which suits both the display and the battery.
 */
class ArtworkLoader(
    filesDir: File,
    private val serverClient: StateFlow<MusicServer?>,
    /**
     * Which source a cover belongs to, read at the moment it is asked for.
     *
     * Cover ids are only unique *within* a server, and [serverClient] is
     * whichever one is active now — so without this a Plex id could be fetched
     * from Navidrome, and the answer filed under that id for good. Which is
     * exactly what happened: Navidrome replied "not found" as JSON, 185 bytes of
     * it went into the cache as though it were a sleeve, and every later request
     * for that cover read it back and failed to decode. No amount of fixing the
     * URL could help, because nothing was ever fetched again.
     */
    private val sourceId: () -> String,
) {
    private val http = HttpClient(OkHttp) { expectSuccess = false }
    private val diskDir = File(filesDir, "artwork").apply { mkdirs() }
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_ENTRIES) {}
    private val gate = Semaphore(FETCH_CONCURRENCY)

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
        return memory.get(memoryKey(coverArtId, sizeBucket(targetSizePx)))
    }

    private fun memoryKey(coverArtId: String, bucket: Int) =
        "${sourceId()}|$coverArtId@$bucket"

    suspend fun load(coverArtId: String?, targetSizePx: Int): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        val bucket = sizeBucket(targetSizePx)
        val memKey = memoryKey(coverArtId, bucket)
        memory.get(memKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            // Cached bytes are trusted only as far as they decode. Anything
            // already on disk that turns out not to be a picture is thrown away
            // and asked for again — otherwise one bad answer, cached once, is a
            // cover that stays broken for the life of the install.
            val cached = readDisk(coverArtId)?.takeIf { looksLikeImage(it) }
            if (cached == null) diskFile(coverArtId).delete()
            val bytes = cached ?: fetch(coverArtId)?.also { writeDisk(coverArtId, it) }
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
        val client = serverClient.value ?: return null
        val sized = client.coverArtUrl(coverArtId, FETCH_PX)
        val original = client.coverArtUrl(coverArtId)
        // Not all at once. A grid asks for a screenful of covers the moment it
        // appears, and thirty of those in flight over a connection that leaves
        // the house is how they all become slow and some of them time out.
        return gate.withPermit {
            // The full-size URL is kept as a fallback: a server that can't
            // resize, or a resizer that isn't answering, should cost a slower
            // cover rather than a missing one.
            download(sized) ?: download(original.takeIf { it != sized })
        }
    }

    private suspend fun download(url: String?): ByteArray? {
        if (url == null) return null
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

    private fun diskFile(coverArtId: String) = File(diskDir, md5Hex("${sourceId()}|$coverArtId"))

    private fun readDisk(coverArtId: String): ByteArray? =
        diskFile(coverArtId).takeIf { it.exists() && it.length() > 0 }?.readBytes()

    /**
     * Whether these bytes are a picture at all.
     *
     * A server can answer a cover request with a 200 and something else
     * entirely — an error document, a login page — and the only thing that
     * makes that obvious is the first few bytes. Kept without this check, one
     * such answer becomes a cover that can never load again.
     */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun at(i: Int) = bytes[i].toInt() and 0xFF
        val jpeg = at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF
        val png = at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47
        val gif = at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46
        val webp = at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50
        val bmp = at(0) == 0x42 && at(1) == 0x4D
        return jpeg || png || gif || webp || bmp
    }

    private fun writeDisk(coverArtId: String, bytes: ByteArray) {
        if (!looksLikeImage(bytes)) return
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

        /**
         * The size covers are fetched at, whatever they are drawn at.
         *
         * One file per cover, sized for the largest place it is ever shown —
         * the player's full-width square, which is the screen's own width. Rows
         * downsample from the same bytes, so a page of thumbnails costs one
         * fetch each rather than one per size, and an album opened after its row
         * was drawn needs no second trip.
         */
        private const val FETCH_PX = 1024

        /** How many covers are fetched at once; the rest wait their turn. */
        private const val FETCH_CONCURRENCY = 4
    }
}

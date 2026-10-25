package com.sublunar.amp.art

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.NetworkGate
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.md5Hex
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * What a cover is wanted for, which decides whether it may cost cellular data —
 * the data mode's call, not the loader's: see LinkRules.mayFetchFocusedArt and
 * mayMoveHeavyBytes.
 */
enum class ArtworkNeed {
    /** The cover in front of the user: the playing track, the open album. */
    FOCUSED,
    /** A row or a tile in a list the user is scrolling past. */
    BROWSING,
}

/**
 * Loads album art. Bytes are fetched once and cached on disk, one file per cover;
 * decoded bitmaps are downsampled to the requested size and kept in a small
 * memory LRU. RGB_565 halves bitmap memory.
 */
class ArtworkLoader(
    filesDir: File,
    private val serverClient: StateFlow<MusicServer?>,
    /** Whether a cover may be fetched over the network now, for that need — see LinkRules. */
    private val fetchAllowed: (ArtworkNeed) -> Boolean,
    /**
     * Whether covers are switched off entirely — see App.hideArtwork. The drawing
     * path stops a level up, in rememberArtwork; this gates the other ways in,
     * [prefetch] and [syncCovers].
     */
    private val artworkOff: () -> Boolean,
    /**
     * Which source a cover belongs to, read at the moment it is asked for. Cover
     * ids are unique only *within* a server, so disk and memory are keyed by
     * source too: otherwise one server's "not found" body can be filed as
     * another's cover, and never fetched again.
     */
    private val sourceId: () -> String,
) {
    private val http = NetworkGate.httpClient { expectSuccess = false }
    private val diskDir = File(filesDir, "artwork").apply { mkdirs() }
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_ENTRIES) {}
    private val gate = Semaphore(FETCH_CONCURRENCY)

    /**
     * A cover already decoded at this size, without suspending, so a list draws
     * its covers on the *first* frame rather than a frame of placeholders later.
     */
    fun peek(coverArtId: String?, targetSizePx: Int): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        return memory.get(memoryKey(coverArtId, sizeBucket(targetSizePx)))
    }

    private fun memoryKey(coverArtId: String, bucket: Int) =
        "${sourceId()}|$coverArtId@$bucket"

    suspend fun load(coverArtId: String?, targetSizePx: Int, need: ArtworkNeed = ArtworkNeed.BROWSING): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        val bucket = sizeBucket(targetSizePx)
        val memKey = memoryKey(coverArtId, bucket)
        memory.get(memKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            // Bytes on disk that aren't a picture are thrown away and asked for
            // again; kept, one bad answer is a cover broken for good. Only
            // those: with no file read, a delete could take one a writer has
            // just put there.
            val source = sourceId()
            val onDisk = readDisk(source, coverArtId)
            val cached = onDisk?.takeIf { looksLikeImage(it) }
            if (onDisk != null && cached == null) diskFile(source, coverArtId).delete()
            val bytes = cached ?: fetch(serverClient.value, coverArtId, need)?.also { writeDisk(source, coverArtId, it) }
                ?: return@withContext null
            val bitmap = decodeDownsampled(bytes, bucket) ?: return@withContext null
            val image = bitmap.asImageBitmap()
            memory.put(memKey, image)
            image
        }
    }

    /**
     * Put a cover on disk without decoding it, as tracks are downloaded, so an
     * offline library has its sleeves where [load] looks for them.
     *
     * Takes its source and client rather than the active ones: downloads run for
     * every source whichever is browsed, and a sleeve must be asked of, and filed
     * under, its own server (see [sourceId]).
     */
    suspend fun prefetch(sourceId: String, client: MusicServer, coverArtId: String?) {
        if (coverArtId.isNullOrBlank()) return
        if (artworkOff()) return
        withContext(Dispatchers.IO) {
            if (isOnDisk(sourceId, coverArtId)) return@withContext
            fetch(client, coverArtId, ArtworkNeed.BROWSING)?.let { writeDisk(sourceId, coverArtId, it) }
        }
    }

    private suspend fun fetch(client: MusicServer?, coverArtId: String, need: ArtworkNeed): ByteArray? {
        // A local track's cover id is its own path: the sleeve is inside the
        // file, and there is no server to ask for it.
        PhoneFiles.fileOf(coverArtId)?.let { return embedded(it) }
        // A cover is a quarter-megabyte at panel size: nothing next to the song
        // playing, but a listful is not, so those wait for cheap bytes and show
        // the placeholder meanwhile.
        if (!fetchAllowed(need)) {
            // Only the focused cover is news; a list refused on cellular is the design.
            if (need == ArtworkNeed.FOCUSED) android.util.Log.i("AmpArt", "focused cover not fetched: rules forbid it")
            return null
        }
        if (client == null) return null
        val sized = client.coverArtUrl(coverArtId, panelWidthPx)
        val original = client.coverArtUrl(coverArtId)
        val startedMs = System.currentTimeMillis()
        // A grid asks for a screenful at once; all in flight together, they all
        // slow down and some time out.
        val answer = gate.withPermit {
            // The full-size URL is the fallback: a resizer that isn't answering
            // should cost a slower cover, not a missing one.
            val first = download(sized)
            if (first is CoverFetch.Got || original == sized) first else download(original)
        }
        val bytes = (answer as? CoverFetch.Got)?.bytes
        if (need == ArtworkNeed.FOCUSED || bytes == null) {
            val outcome = when (answer) {
                is CoverFetch.Got -> "${answer.bytes.size / 1024} KB"
                is CoverFetch.None -> "FAILED (${answer.why})"
                is CoverFetch.Failed -> "FAILED (${answer.why})"
            }
            android.util.Log.i(
                "AmpArt",
                "${need.name.lowercase()} cover $outcome in ${System.currentTimeMillis() - startedMs} ms",
            )
        }
        return bytes
    }

    /**
     * One request for one picture. The drawing path only wants bytes; the cover
     * sync must tell "no such picture" (never ask again) from "no answer" (ask
     * again) — see [CoverFetch].
     */
    private suspend fun download(url: String?): CoverFetch {
        if (url == null) return CoverFetch.None("no url")
        return try {
            val response = http.get(url)
            val status = response.status.value
            when {
                response.status.isSuccess() -> {
                    val bytes = response.body<ByteArray>()
                    // Whether they are a picture is settled in [writeDisk]: Subsonic
                    // says "not found" with a 200.
                    if (bytes.isNotEmpty()) CoverFetch.Got(bytes) else CoverFetch.None("empty body")
                }
                // Refusals about *us* or about the moment, not about the cover.
                status == 401 || status == 403 || status == 408 || status == 429 || status >= 500 ->
                    CoverFetch.Failed("HTTP $status")
                else -> CoverFetch.None("HTTP $status")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CoverFetch.Failed("${e::class.simpleName}: ${e.message?.take(80)}")
        }
    }

    /** The picture tagged into an audio file; [MediaMetadataRetriever] takes a path, so a tool can use it. */
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

    private fun diskFile(sourceId: String, coverArtId: String) =
        File(diskDir, fileNameFor(sourceId, coverArtId))

    private fun isOnDisk(sourceId: String, coverArtId: String): Boolean =
        diskFile(sourceId, coverArtId).let { it.exists() && it.length() > 0 }

    private fun readDisk(sourceId: String, coverArtId: String): ByteArray? {
        val file = diskFile(sourceId, coverArtId).takeIf { it.exists() && it.length() > 0 } ?: return null
        // A read leaves no mark of its own, and the budget evicts least
        // recently used — so touch it, and the covers looked at stay.
        file.setLastModified(System.currentTimeMillis())
        return file.readBytes()
    }

    /**
     * Drop covers the library has just stopped pointing at — the other half of
     * [syncCovers]. A sync that removes an album, or sees its art change (a *new*
     * cover id on Navidrome and Plex), names the ids it no longer holds; see
     * LibrarySync.reportDroppedCovers. A source removed names every id its
     * database held, read before the tables are cleared: files are named by a
     * hash, so only the ids can find them.
     *
     * Only those covers leave memory, not the source's whole set: this runs
     * after every sync that changed anything, under a list that is on screen.
     */
    suspend fun drop(sourceId: String, coverArtIds: Collection<String>) {
        if (coverArtIds.isEmpty()) return
        var removed = 0
        var freed = 0L
        withContext(Dispatchers.IO) {
            coverArtIds.forEach { id ->
                val file = diskFile(sourceId, id)
                val size = file.length()
                if (file.delete()) {
                    removed++
                    freed += size
                }
            }
        }
        val prefixes = coverArtIds.mapTo(HashSet()) { "$sourceId|$it@" }
        memory.snapshot().keys
            .filter { key -> key.substringBeforeLast('@') + "@" in prefixes }
            .forEach { memory.remove(it) }
        knownMissing[sourceId]?.removeAll(coverArtIds.toSet())
        if (removed > 0) {
            android.util.Log.i("AmpArt", "dropped $removed cover(s) the library no longer has, ${freed shr 10} KB")
        }
    }

    /**
     * Fetch the covers [coverArtIds] names and the disk doesn't hold, after a
     * library sync while the link is free. On a metered link lists draw only
     * what is already here (see [ArtworkNeed]), so this is what a library looks
     * like on cellular.
     *
     * Panel-sized pictures only, with no fallback to the original: right for the
     * one cover someone is looking at, wrong for a whole library (an original can
     * be twenty megabytes).
     *
     * [protectedFiles] are the covers the budget may never take — see
     * [trimToBudget] — read only if the budget is reached. The library being
     * synced is added to them while this runs, so room for its covers is never
     * made at its own expense.
     */
    suspend fun syncCovers(
        sourceId: String,
        client: MusicServer,
        coverArtIds: List<String>,
        protectedFiles: suspend () -> Set<String>,
        mayContinue: suspend () -> Boolean,
    ): CoverSyncResult = withContext(Dispatchers.IO) {
        var kept: Set<String>? = null
        suspend fun keep(): Set<String> =
            kept ?: (protectedFiles() + coverArtIds.map { fileNameFor(sourceId, it) }).also { kept = it }
        CoverSync(
            isOnDisk = { id -> isOnDisk(sourceId, id) },
            // One fetch slot, so a list scrolled meanwhile still has the rest.
            fetch = { id -> gate.withPermit { download(client.coverArtUrl(id, panelWidthPx)) } },
            store = { id, bytes -> writeDisk(sourceId, id, bytes) },
            cacheBytes = { coverFiles().sumOf { it.length() } },
            // Below the line, not to it, or a cache at its ceiling walks the
            // directory again for every cover that follows.
            makeRoom = { trimToBudget(keep(), DISK_BUDGET_BYTES - DISK_BUDGET_BYTES / 10) },
            budgetBytes = DISK_BUDGET_BYTES,
            // Switched off part-way is switched off: see [artworkOff].
            mayContinue = { !artworkOff() && mayContinue() },
            knownMissing = knownMissing.getOrPut(sourceId) { ConcurrentHashMap.newKeySet() },
        ).run(coverArtIds)
    }

    /** What each server has said it has no picture for — see [CoverSync]. Not kept across launches. */
    private val knownMissing = ConcurrentHashMap<String, MutableSet<String>>()

    /** Numbers the half-written files, so two writes never share one — see [writeDisk]. */
    private val writes = AtomicLong()

    /**
     * Delete what no library names and nobody has looked at in a month.
     *
     * For everything [drop] can't see: the covers a failed sync pruned but never
     * reported, a source whose database was cleared. [claimed] is every file any
     * source's library points at; only the budget applies to those.
     *
     * The month is for covers that are legitimately nobody's (a playlist song
     * from a library never synced, a radio result): a read is a use (see
     * [readDisk]), so those still looked at stay rather than being deleted at
     * every launch and fetched again.
     */
    suspend fun expireUnclaimed(claimed: Set<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var removed = 0
        var freed = 0L
        // A write that a killed process never finished; see [writeDisk].
        diskDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(PARTIAL) && now - it.lastModified() > STALE_PARTIAL_MS }
            .forEach { it.delete() }
        for (file in coverFiles()) {
            if (file.name in claimed || now - file.lastModified() < UNCLAIMED_KEPT_MS) continue
            val size = file.length()
            if (file.delete()) {
                removed++
                freed += size
            }
        }
        if (removed > 0) {
            android.util.Log.i("AmpArt", "expired $removed cover(s) no library has, unused for a month: ${freed shr 20} MB")
        }
    }

    /**
     * Hold the cache to [DISK_BUDGET_BYTES], least recently used first; over the
     * budget, evict down to [target]. Run at launch, off the main thread.
     *
     * [keep] names the files that survive whatever the budget says: the covers of
     * downloaded albums, the offline sleeves, which go when their songs do — see
     * CoverUpkeep.protectedFiles. Answers what the cache comes to afterwards, more
     * than the budget when what must be kept is.
     */
    suspend fun trimToBudget(keep: Set<String>, target: Long = DISK_BUDGET_BYTES): Long = withContext(Dispatchers.IO) {
        val files = coverFiles()
        val before = files.sumOf { it.length() }
        var total = before
        if (total <= DISK_BUDGET_BYTES) return@withContext total
        var removed = 0
        for (file in files.filter { it.name !in keep }.sortedBy { it.lastModified() }) {
            if (total <= target) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                removed++
            }
        }
        android.util.Log.i(
            "AmpArt",
            "artwork cache trimmed: $removed files, ${before shr 20} MB -> ${total shr 20} MB",
        )
        total
    }

    /** The covers on disk — not the half-written ones; see [writeDisk]. */
    private fun coverFiles(): List<File> =
        diskDir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(PARTIAL) }

    /** The file a cover is kept in, for anything naming files to keep or drop. */
    fun fileNameFor(sourceId: String, coverArtId: String): String = md5Hex("$sourceId|$coverArtId")

    /**
     * Whether these bytes are a picture at all, by their magic number. A server
     * can answer a cover request with a 200 and an error document or a login
     * page; kept, that is a cover that never loads again.
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

    /**
     * Keep these bytes as that cover, and say whether they were kept.
     *
     * Written beside the real name and renamed onto it, so the file is absent or
     * whole: a half-written JPEG still decodes, to a sleeve grey from the middle
     * down, and a list reads the directory while the cover sync writes it. Each
     * write has a name of its own, since both can be fetching the same cover.
     */
    private fun writeDisk(sourceId: String, coverArtId: String, bytes: ByteArray): Boolean {
        if (!looksLikeImage(bytes)) return false
        val file = diskFile(sourceId, coverArtId)
        val partial = File(diskDir, "${file.name}.${writes.incrementAndGet()}$PARTIAL")
        return try {
            partial.writeBytes(bytes)
            partial.renameTo(file).also { moved -> if (!moved) partial.delete() }
        } catch (_: Exception) {
            // A failed cache write is non-fatal; the image still displays this time.
            partial.delete()
            false
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

    /**
     * The panel's own width, set by the first themed frame: the fetch size on any
     * panel, as [FETCH_PX] is on the LP3, which stands in until then.
     */
    @Volatile
    var panelWidthPx: Int = FETCH_PX

    /** Snap to a few size buckets so different callers reuse the same decode. */
    private fun sizeBucket(px: Int): Int = when {
        px <= 0 -> 128
        px <= 160 -> 128
        px <= 360 -> 320
        px <= 720 -> 640
        else -> panelWidthPx
    }

    companion object {
        private const val MEMORY_ENTRIES = 150

        /**
         * The size covers are fetched at, whatever they are drawn at: the largest
         * place one is shown, the player's full-width square on the 1080px LP3
         * panel. Rows downsample from the same bytes, so each cover is one fetch.
         */
        private const val FETCH_PX = 1080

        /** How many covers are fetched at once; the rest wait their turn. */
        private const val FETCH_CONCURRENCY = 4

        /**
         * What the covers on disk may add up to, beyond the protected ones. At
         * [FETCH_PX] a cover runs 70–280 KB, so some three thousand: a sync fills
         * the cache with whole libraries (see [syncCovers]), and one smaller than
         * the libraries it serves re-fetches one each time another is opened.
         */
        private const val DISK_BUDGET_BYTES = 400L * 1024 * 1024

        /** How long a cover no library names is kept after it was last looked at. */
        private const val UNCLAIMED_KEPT_MS = 30L * 24 * 60 * 60 * 1000

        /** The suffix of a cover still being written — see [writeDisk]. */
        private const val PARTIAL = ".part"

        /** Older than this, a half-written cover belongs to a process that is gone. */
        private const val STALE_PARTIAL_MS = 60L * 60 * 1000
    }
}

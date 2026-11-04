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
 * Loads album art. Bytes are fetched once and cached on disk; decoded bitmaps are
 * downsampled to the requested size and kept in a small memory LRU. RGB_565
 * halves bitmap memory.
 *
 * Each cover comes in two sizes, a file each: a small one ([SMALL_PX]) for the
 * rows, grid tiles and album card, a tenth of the bytes, and the panel-wide one
 * for the player and the full-screen sleeve. A small cover is drawn from the
 * full one when only that is here, and the full one stands in softly from the
 * small one when it can't be fetched.
 */
class ArtworkLoader(
    filesDir: File,
    private val serverClient: StateFlow<MusicServer?>,
    /**
     * Whether a cover may be fetched over the network now, for that need, and
     * whether it's the small one (a few dozen KB) or the full one — see LinkRules.
     */
    private val fetchAllowed: (need: ArtworkNeed, small: Boolean) -> Boolean,
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

    /** A file per source whose time is its pictures' last refresh — see [refreshDue]. */
    private val refreshDir = File(filesDir, "artwork-refreshed").apply { mkdirs() }
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_ENTRIES) {}
    private val gate = Semaphore(FETCH_CONCURRENCY)

    /**
     * A cover already decoded at this size, without suspending, so a list draws
     * its covers on the *first* frame rather than a frame of placeholders later.
     */
    fun peek(coverArtId: String?, targetSizePx: Int, fromSource: String? = null): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        return memory.get(memoryKey(coverArtId, sizeBucket(targetSizePx), fromSource ?: sourceId()))
    }

    private fun memoryKey(coverArtId: String, bucket: Int, source: String) = "$source|$coverArtId@$bucket"

    /**
     * [coverArtId]'s cover at about [targetSizePx]. [fromSource] is the source it belongs to where
     * that isn't the one browsed, as on the Downloads page: its id means nothing to the browsed
     * server, so only the copy filed with its downloads is drawn.
     */
    suspend fun load(
        coverArtId: String?,
        targetSizePx: Int,
        need: ArtworkNeed = ArtworkNeed.BROWSING,
        fromSource: String? = null,
    ): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        val bucket = sizeBucket(targetSizePx)
        val browsed = sourceId()
        val source = fromSource ?: browsed
        val memKey = memoryKey(coverArtId, bucket, source)
        memory.get(memKey)?.let { return it }

        val small = bucket <= SMALL_PX
        return withContext(Dispatchers.IO) {
            val client = serverClient.value.takeIf { source == browsed }
            val bytes = readUsable(source, coverArtId, small)
                // A full copy downsamples to any small size.
                ?: (if (small) readUsable(source, coverArtId, small = false) else null)
                ?: fetch(client, coverArtId, need, small)?.also { writeDisk(source, coverArtId, small, it) }
            // Soft beats none: the small copy, when the full one can't be had.
            // Not kept in memory, so the full one is drawn once it can be fetched.
            val standIn = if (bytes == null && !small) readUsable(source, coverArtId, small = true) else null
            val picture = bytes ?: standIn ?: return@withContext null
            val bitmap = decodeDownsampled(picture, bucket) ?: return@withContext null
            val image = bitmap.asImageBitmap()
            if (standIn == null) memory.put(memKey, image)
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
            // The full one: offline, the player shows it full width.
            if (isOnDisk(sourceId, coverArtId, small = false)) return@withContext
            fetch(client, coverArtId, ArtworkNeed.BROWSING, small = false)
                ?.let { writeDisk(sourceId, coverArtId, small = false, it) }
        }
    }

    /**
     * The disk's copy of a cover at that size, if it is a picture. Bytes that
     * aren't are thrown away and asked for again; kept, one bad answer is a
     * cover broken for good. Only those: with no file read, a delete could take
     * one a writer has just put there.
     */
    private fun readUsable(sourceId: String, coverArtId: String, small: Boolean): ByteArray? {
        val onDisk = readDisk(sourceId, coverArtId, small) ?: return null
        if (looksLikeImage(onDisk)) return onDisk
        diskFile(sourceId, coverArtId, small).delete()
        return null
    }

    private suspend fun fetch(client: MusicServer?, coverArtId: String, need: ArtworkNeed, small: Boolean): ByteArray? {
        // A local track's cover id is its own path: the sleeve is inside the
        // file, and there is no server to ask for it.
        PhoneFiles.fileOf(coverArtId)?.let { return embedded(it) }
        // A cover is a quarter-megabyte at panel size: nothing next to the song
        // playing, but a listful is not. The rules say which may cost cellular.
        if (!fetchAllowed(need, small)) {
            // Only the focused cover is news; a list refused on cellular is the design.
            if (need == ArtworkNeed.FOCUSED) android.util.Log.i("AmpArt", "focused cover not fetched: rules forbid it")
            return null
        }
        if (client == null) return null
        val sized = client.coverArtUrl(coverArtId, if (small) SMALL_PX else panelWidthPx)
        // The full-size URL is the fallback for the full cover only: an
        // original can be twenty megabytes, never worth it for a small one.
        val original = if (small) sized else client.coverArtUrl(coverArtId)
        val startedMs = System.currentTimeMillis()
        // A grid asks for a screenful at once; all in flight together, they all
        // slow down and some time out.
        val answer = gate.withPermit {
            // A resizer that isn't answering should cost a slower cover, not a missing one.
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
            CoverFetch.Failed("${e::class.simpleName}")
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

    private fun diskFile(sourceId: String, coverArtId: String, small: Boolean) =
        File(diskDir, fileNameFor(sourceId, coverArtId) + if (small) SMALL_SUFFIX else "")

    private fun isOnDisk(sourceId: String, coverArtId: String, small: Boolean): Boolean =
        diskFile(sourceId, coverArtId, small).let { it.exists() && it.length() > 0 }

    private fun readDisk(sourceId: String, coverArtId: String, small: Boolean): ByteArray? {
        val file = diskFile(sourceId, coverArtId, small).takeIf { it.exists() && it.length() > 0 } ?: return null
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
                for (small in listOf(false, true)) {
                    val file = diskFile(sourceId, id, small)
                    val size = file.length()
                    if (file.delete()) {
                        removed++
                        freed += size
                    }
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
     * Fetch what a library's covers and artist pictures lack on disk, after a
     * library sync while the link is free. Small copies of everything first —
     * what lists draw, a tenth of the bytes, so a library looks whole on any
     * link soonest — then the full covers, for as long as the cache has room.
     *
     * Sized pictures only, with no fallback to the original: right for the one
     * cover someone is looking at, wrong for a whole library (an original can
     * be twenty megabytes).
     *
     * [protectedFiles] are the covers the budget may never take — see
     * [trimToBudget] — read only if the budget is reached. The library being
     * synced is added to them while this runs, so room for its covers is never
     * made at its own expense. Only small copies make room; full ones stop
     * where the room runs out, rather than evicting another library's.
     */
    suspend fun syncCovers(
        sourceId: String,
        client: MusicServer,
        albumCoverIds: List<String>,
        artistImageIds: List<String>,
        budgetBytes: Long,
        protectedFiles: suspend () -> Set<String>,
        mayContinue: suspend () -> Boolean,
        /** Each pass's progress — see [CoverSync]'s; "small" or "full" first. */
        onProgress: (size: String, fetched: Int, missing: Int) -> Unit = { _, _, _ -> },
    ): Pair<CoverSyncResult, CoverSyncResult> = withContext(Dispatchers.IO) {
        val everything = albumCoverIds + artistImageIds
        var kept: Set<String>? = null
        suspend fun keep(): Set<String> =
            kept ?: (protectedFiles() + everything.flatMap { fileNamesFor(sourceId, it) }).also { kept = it }
        fun pass(small: Boolean) = CoverSync(
            // Either copy will do for a small one: a full copy downsamples.
            isOnDisk = { id -> isOnDisk(sourceId, id, small) || (small && isOnDisk(sourceId, id, small = false)) },
            // One fetch slot, so a list scrolled meanwhile still has the rest.
            fetch = { id ->
                gate.withPermit { download(client.coverArtUrl(id, if (small) SMALL_PX else panelWidthPx)) }
            },
            store = { id, bytes -> writeDisk(sourceId, id, small, bytes) },
            cacheBytes = { coverFiles().sumOf { it.length() } },
            // Small copies make room (below the line, not to it, or a cache at
            // its ceiling walks the directory again for every cover that
            // follows). Full ones never do: what they would evict is another
            // library's full covers, which that library's sync would fetch
            // again, each sync taking the room back in turn. A full pass
            // stops where the room runs out, and the rest load when opened.
            makeRoom = if (small) {
                { trimToBudget(keep(), budgetBytes, budgetBytes - budgetBytes / 10) }
            } else {
                { coverFiles().sumOf { it.length() } }
            },
            budgetBytes = budgetBytes,
            // Switched off part-way is switched off: see [artworkOff].
            mayContinue = { !artworkOff() && mayContinue() },
            knownMissing = knownMissing.getOrPut(sourceId) { ConcurrentHashMap.newKeySet() },
            onProgress = { fetched, missing -> onProgress(if (small) "small" else "full", fetched, missing) },
        )
        val smallResult = pass(small = true).run(everything)
        // Artists are only ever drawn small.
        val fullResult = if (smallResult.stopped == null) {
            pass(small = false).run(albumCoverIds)
        } else {
            CoverSyncResult(albumCoverIds.size, 0, 0, 0L, "small covers unfinished")
        }
        smallResult to fullResult
    }

    /**
     * Whether [sourceId]'s pictures whose ids never change are due to be fetched
     * afresh: a month since the last time, or never yet. Kept beside the cache,
     * not in it, so reading a cover (which touches its file) can't move it.
     */
    fun refreshDue(sourceId: String): Boolean =
        lastRefreshedMs(sourceId)?.let { System.currentTimeMillis() - it >= REFRESH_EVERY_MS } ?: true

    fun markRefreshed(sourceId: String) {
        runCatching { refreshFile(sourceId).apply { writeText("") }.setLastModified(System.currentTimeMillis()) }
    }

    /** When [sourceId]'s pictures were last refreshed, or null if never. */
    fun lastRefreshedMs(sourceId: String): Long? = refreshFile(sourceId).takeIf { it.exists() }?.lastModified()

    private fun refreshFile(sourceId: String) = File(refreshDir, md5Hex(sourceId))

    /** What the covers on disk come to, for the Storage page. */
    suspend fun cacheBytes(): Long = withContext(Dispatchers.IO) { coverFiles().sumOf { it.length() } }

    /** The cache in one line, for the launch log: its size, and how many of each copy. */
    suspend fun describe(): String = withContext(Dispatchers.IO) {
        val (small, full) = coverFiles().partition { it.name.endsWith(SMALL_SUFFIX) }
        "${(small.sumOf { it.length() } + full.sumOf { it.length() }) shr 20} MB: " +
            "${small.size} small (${small.sumOf { it.length() } shr 20} MB), " +
            "${full.size} full (${full.sumOf { it.length() } shr 20} MB)"
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
     * Hold the cache to [budget], full covers before small ones and least
     * recently used first within each: a small copy is what keeps a list whole.
     * Over the budget, evict down to [target]. Run at launch, off the main thread.
     *
     * [keep] names the files that survive whatever the budget says: the covers of
     * downloaded albums, the offline sleeves, which go when their songs do — see
     * CoverUpkeep.protectedFiles. Answers what the cache comes to afterwards, more
     * than the budget when what must be kept is.
     */
    suspend fun trimToBudget(keep: Set<String>, budget: Long, target: Long = budget): Long = withContext(Dispatchers.IO) {
        val files = coverFiles()
        val before = files.sumOf { it.length() }
        var total = before
        if (total <= budget) return@withContext total
        var removed = 0
        val evictable = files.filter { it.name !in keep }
            .sortedWith(compareBy({ it.name.endsWith(SMALL_SUFFIX) }, { it.lastModified() }))
        for (file in evictable) {
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

    /** The file a cover's full copy is kept in; its small copy adds [SMALL_SUFFIX]. */
    private fun fileNameFor(sourceId: String, coverArtId: String): String = md5Hex("$sourceId|$coverArtId")

    /** Both files a cover may be kept in, for anything naming files to keep or drop. */
    fun fileNamesFor(sourceId: String, coverArtId: String): List<String> =
        fileNameFor(sourceId, coverArtId).let { listOf(it, it + SMALL_SUFFIX) }

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
    private fun writeDisk(sourceId: String, coverArtId: String, small: Boolean, bytes: ByteArray): Boolean {
        if (!looksLikeImage(bytes)) return false
        val file = diskFile(sourceId, coverArtId, small)
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
         * The small copy's size: the largest place one is drawn (a 300px grid
         * tile or album card) on the 1080px LP3 panel. Some 15–30 KB a cover
         * against 70–280 KB at [FETCH_PX].
         */
        const val SMALL_PX = 320

        /** Added to a cover's file name for its small copy. */
        private const val SMALL_SUFFIX = "-s"

        /** How often a picture whose id never changes is fetched afresh, in case it has. */
        private const val REFRESH_EVERY_MS = 30L * 24 * 60 * 60 * 1000

        /** How long a cover no library names is kept after it was last looked at. */
        private const val UNCLAIMED_KEPT_MS = 30L * 24 * 60 * 60 * 1000

        /** The suffix of a cover still being written — see [writeDisk]. */
        private const val PARTIAL = ".part"

        /** Older than this, a half-written cover belongs to a process that is gone. */
        private const val STALE_PARTIAL_MS = 60L * 60 * 1000
    }
}

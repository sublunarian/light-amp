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
import com.sublunar.amp.data.NetworkGate

/**
 * Loads album art. Bytes are fetched once and cached on disk; decoded bitmaps
 * are downsampled to the requested display size and kept in a small memory LRU.
 * RGB_565 halves bitmap memory, which suits both the display and the battery.
 */
/**
 * What a cover is wanted for, which decides whether it may cost cellular data.
 * The distinction is the data mode's, not the loader's — see
 * LinkRules.mayFetchFocusedArt and mayMoveHeavyBytes.
 */
enum class ArtworkNeed {
    /** The cover in front of the user: the playing track, the open album. */
    FOCUSED,
    /** A row or a tile in a list the user is scrolling past. */
    BROWSING,
}

class ArtworkLoader(
    filesDir: File,
    private val serverClient: StateFlow<MusicServer?>,
    /** Whether a cover may be fetched over the network now, for that need — see LinkRules. */
    private val fetchAllowed: (ArtworkNeed) -> Boolean = { true },
    /**
     * Whether covers are switched off entirely — see App.hideArtwork.
     *
     * Only [prefetch] asks. The drawing path is already stopped a level up, in
     * rememberArtwork, which is where switching covers off stops them being
     * fetched and decoded; this is the other way in, and it had no such gate.
     */
    private val artworkOff: () -> Boolean = { false },
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
    private val http = NetworkGate.httpClient { expectSuccess = false }
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

    suspend fun load(coverArtId: String?, targetSizePx: Int, need: ArtworkNeed = ArtworkNeed.BROWSING): ImageBitmap? {
        if (coverArtId.isNullOrBlank()) return null
        val bucket = sizeBucket(targetSizePx)
        val memKey = memoryKey(coverArtId, bucket)
        memory.get(memKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            // Cached bytes are trusted only as far as they decode. Anything
            // already on disk that turns out not to be a picture is thrown away
            // and asked for again — otherwise one bad answer, cached once, is a
            // cover that stays broken for the life of the install.
            val source = sourceId()
            val cached = readDisk(source, coverArtId)?.takeIf { looksLikeImage(it) }
            if (cached == null) diskFile(source, coverArtId).delete()
            val bytes = cached ?: fetch(serverClient.value, coverArtId, need)?.also { writeDisk(source, coverArtId, it) }
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
     *
     * Names its source and its client rather than reading the active ones:
     * downloads run for every source whichever is being browsed, so the sleeve
     * of a Plex track landing while Navidrome is on screen has to be asked of
     * Plex and filed under Plex — the other way round is how a "not found"
     * body once became a cover that could never load again (see [sourceId]).
     */
    suspend fun prefetch(sourceId: String, client: MusicServer, coverArtId: String?) {
        if (coverArtId.isNullOrBlank()) return
        // Downloading an album pulled its sleeve down even with artwork turned
        // off — a quarter-megabyte per record, for a picture the app had been
        // told never to draw.
        if (artworkOff()) return
        withContext(Dispatchers.IO) {
            if (diskFile(sourceId, coverArtId).let { it.exists() && it.length() > 0 }) return@withContext
            fetch(client, coverArtId, ArtworkNeed.BROWSING)?.let { writeDisk(sourceId, coverArtId, it) }
        }
    }

    private suspend fun fetch(client: MusicServer?, coverArtId: String, need: ArtworkNeed): ByteArray? {
        // A local track's cover id is its own path: the sleeve is inside the
        // file, and there is no server to ask for it.
        LocalLibrary.fileOf(coverArtId)?.let { return embedded(it) }
        // A cover is a quarter-megabyte at panel size. One for what is playing
        // is nothing next to the song; a listful is not, so those wait for
        // cheap bytes: the placeholder shows, nothing is cached, and the next
        // look on Wi-Fi fetches as though this never happened.
        if (!fetchAllowed(need)) {
            // Logged only for the cover in front of the user: a list refusing
            // its hundred rows on cellular is the design, not news.
            if (need == ArtworkNeed.FOCUSED) android.util.Log.i("AmpArt", "focused cover not fetched: rules forbid it")
            return null
        }
        if (client == null) return null
        val sized = client.coverArtUrl(coverArtId, panelWidthPx)
        val original = client.coverArtUrl(coverArtId)
        val startedMs = System.currentTimeMillis()
        // Not all at once. A grid asks for a screenful of covers the moment it
        // appears, and thirty of those in flight over a connection that leaves
        // the house is how they all become slow and some of them time out.
        return gate.withPermit {
            // The full-size URL is kept as a fallback: a server that can't
            // resize, or a resizer that isn't answering, should cost a slower
            // cover rather than a missing one.
            download(sized).bytesOrNull() ?: download(original.takeIf { it != sized }).bytesOrNull()
        }.also { bytes ->
            if (need == ArtworkNeed.FOCUSED || bytes == null) {
                android.util.Log.i(
                    "AmpArt",
                    "${need.name.lowercase()} cover ${if (bytes != null) "${bytes.size / 1024} KB" else "FAILED ($lastFailure)"} " +
                        "in ${System.currentTimeMillis() - startedMs} ms",
                )
            }
        }
    }

    /** Why the most recent download came back empty — for the log line above. */
    @Volatile
    private var lastFailure: String = "no answer"

    private fun CoverFetch.bytesOrNull(): ByteArray? = (this as? CoverFetch.Got)?.bytes

    /**
     * One request for one picture, and which of three things came of it.
     *
     * The drawing path only wants to know whether there are bytes. The cover
     * sync has to tell "the server has no such picture" from "the server
     * didn't answer", because the first is never worth asking again and the
     * second always is — see [CoverFetch].
     */
    private suspend fun download(url: String?): CoverFetch {
        if (url == null) return CoverFetch.None
        return try {
            val response = http.get(url)
            val status = response.status.value
            when {
                response.status.isSuccess() -> {
                    val bytes = response.body<ByteArray>()
                    // Whether they are a picture is settled where they are
                    // kept — see [writeDisk], and CoverSync for what a 200
                    // that isn't one means: Subsonic says "not found" so.
                    if (bytes.isNotEmpty()) CoverFetch.Got(bytes) else none("empty body")
                }
                // Refusals about *us* or about the moment, not about the cover.
                status == 401 || status == 403 || status == 408 || status == 429 || status >= 500 ->
                    CoverFetch.Failed("HTTP $status").also { lastFailure = it.why }
                else -> none("HTTP $status")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CoverFetch.Failed("${e::class.simpleName}: ${e.message?.take(80)}").also { lastFailure = it.why }
        }
    }

    private fun none(why: String): CoverFetch {
        lastFailure = why
        return CoverFetch.None
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

    private fun diskFile(sourceId: String, coverArtId: String) =
        File(diskDir, fileNameFor(sourceId, coverArtId))

    private fun readDisk(sourceId: String, coverArtId: String): ByteArray? {
        val file = diskFile(sourceId, coverArtId).takeIf { it.exists() && it.length() > 0 } ?: return null
        // Reading is use. The budget evicts by last use, and a read leaves no
        // mark of its own — so the covers you look at are the ones that stay.
        file.setLastModified(System.currentTimeMillis())
        return file.readBytes()
    }

    /**
     * Drop one source's covers — on Log Out, with its library.
     *
     * Files are named by a hash of source and cover id, so a source's files
     * can't be told apart on disk; the caller reads the ids out of the
     * source's own database before that database is cleared, and names them.
     */
    suspend fun forget(sourceId: String, coverArtIds: Collection<String>) {
        withContext(Dispatchers.IO) {
            coverArtIds.forEach { File(diskDir, fileNameFor(sourceId, it)).delete() }
        }
        memory.snapshot().keys.filter { it.startsWith("$sourceId|") }.forEach { memory.remove(it) }
    }

    /**
     * Drop covers the library has just stopped pointing at.
     *
     * The other half of [syncCovers], and the reason the cache can hold a
     * whole library without becoming a record of every library there has
     * ever been: a sync that removes an album — or sees its art change, which
     * on Navidrome and Plex is a *new* cover id — names the ids its database
     * held before and doesn't now, and their files go with them. The same
     * reasoning as [forget] for why they have to be named.
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
     * Fetch the covers [coverArtIds] names and the disk doesn't hold.
     *
     * Lists only draw what is already here once the link costs money (see
     * [ArtworkNeed]), so what is already here decides what a library looks
     * like on cellular — and until this existed that was whatever had been
     * scrolled past on Wi-Fi. Run after a library sync, while the link is
     * free, it makes the answer "all of it".
     *
     * Asks for the panel-sized picture only. The drawing path falls back to
     * the original when a resizer won't answer, which is right for the one
     * cover someone is looking at and wrong seven hundred times over: an
     * original can be twenty megabytes.
     *
     * [protectedFiles] are the covers the budget may never take — see
     * [trimToBudget]. While this runs the library being synced is added to
     * them, so making room for its covers is never done at its own expense.
     */
    suspend fun syncCovers(
        sourceId: String,
        client: MusicServer,
        coverArtIds: List<String>,
        protectedFiles: Set<String>,
        mayContinue: suspend () -> Boolean,
    ): CoverSyncResult = withContext(Dispatchers.IO) {
        val keep by lazy { protectedFiles + coverArtIds.map { fileNameFor(sourceId, it) } }
        CoverSync(
            isOnDisk = { id -> diskFile(sourceId, id).let { it.exists() && it.length() > 0 } },
            // One of the four fetch slots, so a list being scrolled while
            // this runs still has three.
            fetch = { id -> gate.withPermit { download(client.coverArtUrl(id, panelWidthPx)) } },
            store = { id, bytes -> writeDisk(sourceId, id, bytes) },
            cacheBytes = { coverFiles().sumOf { it.length() } },
            // Below the line rather than to it, or a cache at its ceiling
            // walks the whole directory again for every cover that follows.
            makeRoom = { trimToBudget(keep, DISK_BUDGET_BYTES - DISK_BUDGET_BYTES / 10) },
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
     * [drop] removes a cover the moment a sync sees its album go. This is for
     * everything that path can't see: the covers orphaned before it existed,
     * the ones a failed sync pruned but never reported, a source whose
     * database was cleared. [claimed] is every file any source's library
     * points at; those are never touched here — only the budget applies to
     * them.
     *
     * The month is for the covers that are legitimately nobody's: a track in
     * a playlist whose album is in a library never synced, a radio result.
     * Those are fetched when looked at, and a read is a use (see [readDisk]),
     * so the ones still being looked at stay and the rest age out. Without
     * it they would be deleted at every launch and fetched again after.
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
     * Hold the cache to [DISK_BUDGET_BYTES], least recently used first.
     *
     * Run once at launch, off the main thread, which is also what brings an
     * install from before there was a budget down to it: the cache used to
     * grow for ever, a cover for every album ever scrolled past, and nothing
     * cleared it. [keep] names the files that must survive whatever the
     * budget says — the covers of downloaded albums, which are the offline
     * sleeves and belong to their songs rather than to this cache; they go
     * when the songs do. See App.protectedCoverFiles.
     *
     * Over the budget, it evicts down to [target]. Answers what the cache
     * comes to afterwards, which is more than the budget when what must be
     * kept is.
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

    /**
     * Keep these bytes as that cover, and say whether they were kept.
     *
     * Written beside the real name and moved onto it, so the file is either
     * absent or whole. Covers used to be written one at a time by whoever
     * was about to draw them; the cover sync writes hundreds under a list
     * that is reading the same directory, and a half-written JPEG decodes —
     * to a sleeve that is grey from the middle down, cached in memory as
     * though that were the picture. Each write has a name of its own on the
     * way: the list and the sync can both be fetching the same cover.
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
     * The panel's own width, told to the loader by the first themed frame — the
     * fetch size on any panel, as [FETCH_PX] is on the LP3. A plain field is
     * enough for a hint read at fetch time, and until that frame the LP3's
     * width stands in.
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
         * The size covers are fetched at, whatever they are drawn at.
         *
         * One file per cover, sized for the largest place it is ever shown —
         * the player's full-width square, which is the screen's own width: the
         * LP3 panel is 1080px across, so 1080, not a power of two. Rows
         * downsample from the same bytes, so a page of thumbnails costs one
         * fetch each rather than one per size, and an album opened after its row
         * was drawn needs no second trip.
         */
        private const val FETCH_PX = 1080

        /** How many covers are fetched at once; the rest wait their turn. */
        private const val FETCH_CONCURRENCY = 4

        /**
         * What the covers on disk may add up to, beyond the protected ones.
         *
         * At [FETCH_PX] a cover runs 70–280 KB, so this holds some three
         * thousand. It was half that while the cache held only what had
         * been scrolled past; now that a sync fills it with the library
         * (see [syncCovers]) it has to hold one — two servers of seven
         * hundred albums each came to more than the old figure, and a cache
         * smaller than the libraries it serves re-fetches one of them every
         * time the other is opened. A collection of ten thousand albums
         * still stops here rather than at a gigabyte and a half.
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

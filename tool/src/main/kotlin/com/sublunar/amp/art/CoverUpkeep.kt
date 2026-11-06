package com.sublunar.amp.art

import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.LibraryView
import com.sublunar.amp.data.LinkRules
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.noteOom
import com.sublunar.amp.log.Log
import com.sublunar.amp.log.logRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The artwork cache's upkeep across every source: the cover sync, the launch
 * sweep, and which covers the budget and the expiry must leave alone. The
 * loader keeps the files; this works out which, from each source's database.
 */
class CoverUpkeep(
    private val artwork: ArtworkLoader,
    private val settings: AppSettings,
    /** A source's tables — App.databaseFor. */
    private val daoFor: (MusicSource) -> LibraryDao,
    /** A source's client — App.clientFor. Null for the phone's own music. */
    private val clientFor: (MusicSource) -> MusicServer?,
    /** The source being browsed right now. */
    private val browsedSourceId: () -> String,
    /** The link's rules asked of the system — App.rulesNow. */
    private val rulesNow: () -> LinkRules,
    /** Suspends while the player starts a stream — App.yieldToPlayback. */
    private val yieldToPlayback: suspend () -> Unit,
    /** The cache's share of the Storage Limit — the budget everything here keeps to. */
    private val reserve: CacheReserve,
    private val scope: CoroutineScope,
) {
    /** The cover sync in flight, if one is — see [sync]. */
    private var running: Job? = null

    /**
     * Fetch the covers the library on screen has and the phone doesn't, on an
     * unmetered link only, in every data mode: nobody asked for these bytes,
     * and covers still load as they are looked at. Lists on a metered link draw
     * only covers already here, so this decides how a library looks on cellular.
     * Run after a sync, when the link becomes free, and at launch.
     *
     * One run at a time, replaced rather than queued: a second trigger is the
     * same library, whose fetched covers are on disk, or a different one.
     * [trigger] says what set it off, for the log.
     */
    fun sync(trigger: String? = null) {
        val why = trigger?.let { " ($it)" }.orEmpty()
        synchronized(this) {
            if (running?.isActive == true) Log.i("AmpArt", "cover sync: the run in flight is replaced by a new one$why")
            running?.cancel()
            running = scope.launch {
                // Read from the store, not the held state: "off" has to mean
                // not one, even just after it was switched.
                if (settings.artwork.first() == ArtworkMode.NONE) return@launch skipped("artwork off$why")
                val source = settings.activeSource.first() ?: return@launch skipped("no source$why")
                val client = clientFor(source) ?: return@launch skipped("${source.logRef} has no client$why")
                // The chosen library, as the lists show it — see LibraryView —
                // newest first: those are the covers a run cut short is most
                // likely to be asked for.
                val view = LibraryView(source.selectedLibraries, source.hiddenLibraries)
                val albums = runCatching { daoFor(source).allAlbumsSnapshot() }
                    .noteOom("cover sync of ${source.logRef}")
                    .getOrDefault(emptyList())
                    .filter { view.showsAlbum(it.libraryId) }
                    .sortedByDescending { it.createdMs }
                    .mapNotNull { it.coverArtId }
                // The artists' pictures too, unless they're switched off.
                val artists = if (settings.hideArtistImages.first()) {
                    emptyList()
                } else {
                    runCatching { daoFor(source).artistImages() }
                        .noteOom("artist pictures of ${source.logRef}")
                        .getOrDefault(emptyList())
                        .map { it.imageId }
                }
                if (albums.isEmpty() && artists.isEmpty()) return@launch skipped("${source.logRef} has nothing to show$why")
                // A picture whose id never changes when the picture does (an
                // artist's, any cover on most Subsonic servers) can't be told
                // stale from here, so once a month on a free link those are
                // dropped and fetched again below as missing. Only on a free
                // link: dropped where they can't come back, lists go blank.
                if (artwork.refreshDue(source.id) && rulesNow().maySyncCovers) {
                    val unversioned = (albums + artists).filterNot(client::coverIdVersioned)
                    artwork.drop(source.id, unversioned)
                    artwork.markRefreshed(source.id)
                    Log.i("AmpArt", "cover refresh: ${source.logRef}: ${unversioned.size} pictures whose ids never change, fetched afresh")
                }
                // A sync may have changed how many albums there are.
                reserve.recount()
                Log.i("AmpArt", "cover sync: ${source.logRef} started$why: ${albums.size} covers, ${artists.size} artist pictures")
                val startedMs = System.currentTimeMillis()
                val (small, full) = artwork.syncCovers(
                    sourceId = source.id,
                    client = client,
                    albumCoverIds = albums,
                    artistImageIds = artists,
                    budgetBytes = reserve.now(),
                    protectedFiles = ::protectedFiles,
                    onProgress = { size, fetched, missing ->
                        if (missing > 0) {
                            Log.i(
                                "AmpArt",
                                if (fetched == 0) "cover sync: ${source.logRef}: $size: $missing missing, fetching"
                                else "cover sync: ${source.logRef}: $size: $fetched of $missing so far",
                            )
                        }
                    },
                    mayContinue = {
                        yieldToPlayback()
                        // Asked of the system, not of the flow: this is the
                        // last look before bytes that must not cost anything.
                        rulesNow().maySyncCovers && browsedSourceId() == source.id
                    },
                )
                Log.i(
                    "AmpArt",
                    "cover sync: ${source.logRef} done in ${(System.currentTimeMillis() - startedMs) / 1000} s: " +
                        "${small.summary("small")}; ${full.summary("full")}",
                )
            }
        }
    }

    private fun skipped(why: String) = Log.i("AmpArt", "cover sync skipped: $why")

    /** One pass of a run, for its end line: what was fetched, why the rest wasn't, and why it stopped. */
    private fun CoverSyncResult.summary(size: String): String {
        val unfetched = listOf(none to "none on the server", notPictures to "not kept (not a picture, or not written)", failed to "failed")
            .filter { (count, _) -> count > 0 }
            .joinToString(", ") { (count, what) -> "$count $what" }
        return "$size $fetched of $missing missing fetched ($wanted wanted)" +
            (if (unfetched.isEmpty()) "" else ", $unfetched") +
            ", ${fetchedBytes shr 10} KB" +
            (stopped?.let { ", stopped: $it" } ?: "")
    }

    /**
     * The cache's launch upkeep. Tracks point at their album's cover first, so
     * the protected set is one file per downloaded album and per-song copies
     * fall to the budget. Then the expiry (what no library names and nobody
     * looked at in a month), the budget — see ArtworkLoader.trimToBudget — and
     * a cover sync, since a launch inside the sync floor has no sync to start one.
     */
    suspend fun atLaunch() {
        settings.sources.first().forEach { source ->
            runCatching { daoFor(source).collapseTrackCovers() }
                .noteOom("cover collapse of ${source.logRef}")
                .onSuccess { if (it > 0) Log.i("AmpArt", "${source.logRef}: $it tracks now share their album's cover") }
            // Lyrics of songs the library no longer has, like their covers.
            runCatching { daoFor(source).pruneLyrics() }.noteOom("lyrics of ${source.logRef}")
        }
        claimedFiles()?.let { artwork.expireUnclaimed(it) }
        reserve.recount()
        artwork.trimToBudget(protectedFiles(), reserve.now())
        logCache()
        sync("launch")
    }

    /**
     * What the cache holds, once a launch: the covers against the reserve, then
     * each source's artist pictures and kept lyrics. The only place the cache's
     * contents show, since a cover or lyric that loads fine logs nothing.
     */
    private suspend fun logCache() {
        Log.i("AmpArt", "cache: ${artwork.describe()} of a ${reserve.now() shr 20} MB reserve")
        settings.sources.first().forEach { source ->
            runCatching {
                val dao = daoFor(source)
                val refreshed = artwork.lastRefreshedMs(source.id)
                    ?.let { "${(System.currentTimeMillis() - it) / DAY_MS} days ago" } ?: "never"
                "${source.logRef}: ${dao.albumCount()} albums, ${dao.artistImageCount()} artist pictures, " +
                    "lyrics kept for ${dao.lyricsCount()} songs (${dao.lyricsFoundCount()} with words), " +
                    "pictures refreshed $refreshed"
            }.noteOom("cache summary of ${source.logRef}").onSuccess { Log.i("AmpArt", "cache: $it") }
        }
    }

    /**
     * The cover files the artwork budget must never evict: one per downloaded
     * song's album, across every source. Removing the downloads frees them.
     */
    private suspend fun protectedFiles(): Set<String> =
        settings.sources.first().flatMap { source ->
            runCatching { daoFor(source).downloadedCoverArtIds() }
                .noteOom("protected covers of ${source.logRef}")
                .getOrDefault(emptyList())
                .flatMap { artwork.fileNamesFor(source.id, it) }
        }.toSet()

    /**
     * Every cover file some source's library points at, which the monthly
     * expiry leaves alone — see ArtworkLoader.expireUnclaimed. Null if any
     * database couldn't be read: unreadable looks like empty from here, and
     * nothing is expired on a launch that can't tell.
     */
    private suspend fun claimedFiles(): Set<String>? {
        val claimed = HashSet<String>()
        for (source in settings.sources.first()) {
            val ids = runCatching { daoFor(source).allCoverArtIds() }
                .noteOom("claimed covers of ${source.logRef}").getOrNull() ?: return null
            ids.flatMapTo(claimed) { artwork.fileNamesFor(source.id, it) }
        }
        return claimed
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

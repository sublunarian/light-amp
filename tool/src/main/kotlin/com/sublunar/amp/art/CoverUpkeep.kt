package com.sublunar.amp.art

import android.util.Log
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.LibraryView
import com.sublunar.amp.data.LinkRules
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.noteOom
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
     */
    fun sync() {
        synchronized(this) {
            running?.cancel()
            running = scope.launch {
                // Read from the store, not the held state: "off" has to mean
                // not one, even just after it was switched.
                if (settings.artwork.first() == ArtworkMode.NONE) return@launch
                val source = settings.activeSource.first() ?: return@launch
                val client = clientFor(source) ?: return@launch
                // The chosen library, as the lists show it — see LibraryView —
                // newest first: those are the covers a run cut short is most
                // likely to be asked for.
                val view = LibraryView(source.libraryId, source.hiddenLibraries)
                val albums = runCatching { daoFor(source).allAlbumsSnapshot() }
                    .noteOom("cover sync of ${source.name}")
                    .getOrDefault(emptyList())
                    .filter { view.showsAlbum(it.libraryId) }
                    .sortedByDescending { it.createdMs }
                    .mapNotNull { it.coverArtId }
                // The artists' pictures too, unless they're switched off.
                val artists = if (settings.hideArtistImages.first()) {
                    emptyList()
                } else {
                    runCatching { daoFor(source).artistImages() }
                        .noteOom("artist pictures of ${source.name}")
                        .getOrDefault(emptyList())
                        .map { it.imageId }
                }
                if (albums.isEmpty() && artists.isEmpty()) return@launch
                // A picture whose id never changes when the picture does (an
                // artist's, any cover on most Subsonic servers) can't be told
                // stale from here, so once a month on a free link those are
                // dropped and fetched again below as missing. Only on a free
                // link: dropped where they can't come back, lists go blank.
                if (artwork.refreshDue(source.id) && rulesNow().maySyncCovers) {
                    val unversioned = (albums + artists).filterNot(client::coverIdVersioned)
                    artwork.drop(source.id, unversioned)
                    artwork.markRefreshed(source.id)
                    Log.i("AmpArt", "cover refresh: ${source.name}: ${unversioned.size} pictures whose ids never change, fetched afresh")
                }
                // A sync may have changed how many albums there are.
                reserve.recount()
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
                                if (fetched == 0) "cover sync: ${source.name}: $size: $missing missing, fetching"
                                else "cover sync: ${source.name}: $size: $fetched of $missing so far",
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
                for ((size, result) in listOf("small" to small, "full" to full)) {
                    if (result.missing == 0) continue
                    Log.i(
                        "AmpArt",
                        "cover sync: ${source.name}: $size: ${result.fetched} of ${result.missing} missing fetched " +
                            "(${result.wanted} in the library), ${result.fetchedBytes shr 10} KB in " +
                            "${(System.currentTimeMillis() - startedMs) / 1000} s" +
                            (result.stopped?.let { " — stopped: $it" } ?: ""),
                    )
                }
            }
        }
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
                .noteOom("cover collapse of ${source.name}")
                .onSuccess { if (it > 0) Log.i("AmpArt", "${source.name}: $it tracks now share their album's cover") }
            // Lyrics of songs the library no longer has, like their covers.
            runCatching { daoFor(source).pruneLyrics() }.noteOom("lyrics of ${source.name}")
        }
        claimedFiles()?.let { artwork.expireUnclaimed(it) }
        reserve.recount()
        artwork.trimToBudget(protectedFiles(), reserve.now())
        logCache()
        sync()
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
                "${source.name}: ${dao.albumCount()} albums, ${dao.artistImageCount()} artist pictures, " +
                    "lyrics kept for ${dao.lyricsCount()} songs (${dao.lyricsFoundCount()} with words), " +
                    "pictures refreshed $refreshed"
            }.noteOom("cache summary of ${source.name}").onSuccess { Log.i("AmpArt", "cache: $it") }
        }
    }

    /**
     * The cover files the artwork budget must never evict: one per downloaded
     * song's album, across every source. Removing the downloads frees them.
     */
    private suspend fun protectedFiles(): Set<String> =
        settings.sources.first().flatMap { source ->
            runCatching { daoFor(source).downloadedCoverArtIds() }
                .noteOom("protected covers of ${source.name}")
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
                .noteOom("claimed covers of ${source.name}").getOrNull() ?: return null
            ids.flatMapTo(claimed) { artwork.fileNamesFor(source.id, it) }
        }
        return claimed
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

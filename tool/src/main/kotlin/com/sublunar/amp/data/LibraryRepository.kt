package com.sublunar.amp.data

import com.sublunar.amp.data.db.AlbumEntity
import com.sublunar.amp.data.db.DownloadFile
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.LikedArtistEntity
import com.sublunar.amp.data.db.MAX_BOUND_IDS
import com.sublunar.amp.data.db.TopSongEntity
import com.sublunar.amp.data.db.TrackEntity
import com.sublunar.amp.data.db.toAlbum
import com.sublunar.amp.data.db.toEntity
import com.sublunar.amp.data.db.toTrack
import com.sublunar.amp.source.PhoneLibrary
import com.sublunar.amp.source.PhonePlaylists
import com.sublunar.amp.source.PhoneAccess
import com.sublunar.amp.source.plex.plexClientIdentifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class SyncState(
    val syncing: Boolean = false,
    val phase: String = "",
    val error: String? = null,
    val lastSyncedMs: Long = 0L,
)

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/**
 * A playlist's songs, and whether they are all of them.
 *
 * [LibraryRepository.playlistTracks] can hand back less than the playlist holds —
 * filtered to what's playable offline, or rebuilt from a cached membership that
 * the local library no longer has every track for. That's right for showing, and
 * wrong for editing: [LibraryRepository.removeFromPlaylistAt] takes an index into
 * the *server's* list, and [LibraryRepository.reorderPlaylist] takes an order that
 * has to name every track or the ones left out are the ones lost. A caller that
 * edits must check [complete] first.
 */
data class PlaylistView(val tracks: List<Track>, val complete: Boolean)

/**
 * The library: reads come from the Room cache (instant on launch) and are kept
 * fresh by [sync], which pulls the server catalogue incrementally — only albums
 * that are new or whose song count changed have their tracks re-fetched.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRepository(
    /**
     * The active source's DAO.
     *
     * A flow rather than an instance because switching sources switches the whole
     * database underneath: the observed lists re-subscribe to the new one, and
     * everything derived from them changes over on its own.
     */
    private val daos: StateFlow<LibraryDao?>,
    private val serverClient: StateFlow<MusicServer?>,
    // The persisted selection as a raw flow (not a cached StateFlow) so [sync]
    // reads the actual stored value — a stateIn's initial `null` would race the
    // DataStore load on boot and sync the wrong (unscoped) library.
    private val libraryId: Flow<String?>,
    /** True when only downloaded media should be visible (offline / Wi-Fi only). */
    offlineOnly: Flow<Boolean>,
    /** Whether the server may be spoken to at all — see App.metadataAllowed. */
    private val metadataAllowed: () -> Boolean = { true },
    /**
     * Whether bulk background fetching is worth the link right now — false on
     * a metered connection outside Make it Hurt. See [primePlaylistTrackIds].
     */
    private val bulkAllowed: () -> Boolean = { true },
    /**
     * Suspends while the player is trying to start a stream, so background
     * work steps aside for the one thing the user is waiting on. Bounded by
     * the caller — see App.yieldToPlayback.
     */
    private val yieldToPlayback: suspend () -> Unit = {},
    /** Where likes and ratings go when the server can't be told about them. */
    private val pending: PendingActions,
    /**
     * Puts a line in front of the user for a moment — a write the server
     * refused, after the screen that made it has already gone. See App.tell.
     */
    private val tell: (String) -> Unit = {},
    /** For the handful of things cached in preferences rather than in a table. */
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The database in use right now, for the imperative half of this class. */
    private val dao: LibraryDao get() = daos.value ?: error("Library database not ready")

    /**
     * [daos] as a flow of tables, for the observed half.
     *
     * Every list clears the moment the database underneath changes, before the
     * new one has answered. Without that, `flatMapLatest` switches the source
     * of truth while everything downstream goes on serving the *previous*
     * source's rows until Room finishes the first query — about three seconds,
     * during which the app holds one source's identity and another's contents.
     *
     * That window was not theoretical. A download top-up ran inside it holding
     * 16,222 Navidrome rows while the active source was already Plex, which is
     * how a Navidrome id reached Plex's transcoder and 400'd there once a
     * minute forever; the same window sent a Navidrome cover id to Plex's photo
     * endpoint, which tried to resolve it as a hostname. Both are the same
     * mistake — answering for a source we have left — and both are closed by
     * refusing to answer at all until the new source can.
     *
     * The cost is a blink of empty lists on a source switch, which is the
     * truth: the old source's music is not this source's music, and everything
     * else about the switch already says so.
     *
     * Each list is also read afresh whenever [rereads] moves — see [reread].
     * That re-read is not blanked first: it is the same source, and an empty
     * list in between would be a flicker, not the truth.
     *
     * A [paced] list is read at most once every [SYNC_REREAD_MS] while its own
     * source syncs — see [pacedWhileSyncing].
     */
    private fun <T> observing(
        empty: T,
        rereads: StateFlow<Int> = reread,
        paced: Boolean = false,
        select: (LibraryDao) -> Flow<T>,
    ): Flow<T> =
        daos.flatMapLatest { current ->
            current?.let { dao ->
                rereads.flatMapLatest { n ->
                    if (paced) select(dao).pacedWhileSyncing(dao) { rereads.value != n } else select(dao)
                }.onStart { emit(empty) }
            } ?: emptyFlow()
        }

    /**
     * Moved once a sync has finished writing, so every list reads its table
     * again rather than trusting Room to have told it about the writes.
     *
     * Room 2.7.0 — the version Light's store builder pins — can leave an
     * observed query showing what it read before a write landed; 2.8.0 and
     * 2.8.1 each fixed such a race. A new source showed it: the first sync
     * stored its albums and the Albums page stayed empty until something
     * else wrote to the same table.
     *
     * The tracks have [rereadTracks] of their own, moved only by a sync that
     * wrote to them: every sync rewrites the album rows, and reading the whole
     * library again after one that changed no song was a second copy of it
     * built for nothing.
     */
    private val reread = MutableStateFlow(0)
    private val rereadTracks = MutableStateFlow(0)

    /** The database a sync is writing to right now, if one is — see [pacedWhileSyncing]. */
    private val syncingDao = MutableStateFlow<LibraryDao?>(null)

    /** Moved by every like and rating made here — see [pacedWhileSyncing]. */
    private val localWrites = MutableStateFlow(0)

    private fun wroteHere() = localWrites.update { it + 1 }

    /**
     * Each read handed on at once; the next one, while [dao]'s sync runs, not
     * before [SYNC_REREAD_MS], the sync's end, or a like or rating made here.
     *
     * Room reads the whole table again after every write to it, and a first
     * sync writes forty albums at a time: on a large library, a whole read
     * about every half second, each with the artists, the tag lists and the
     * song sort worked out again behind it, ran the heap out. Room's flow
     * keeps only the latest change while its reader is busy, so holding the
     * reader here after it hands a list on is what bounds the reads — and the
     * waiting ends the moment the sync does, as does the re-read it asks for.
     *
     * Your own edits end the wait too. Held back, a like still read "Like"
     * when the sheet was opened again, and the second tap liked the song
     * twice rather than taking it back.
     *
     * The sync's end both ends the wait and asks for its re-read, in no set
     * order. A wait ended first would go on to read the change it was holding
     * back, which the re-read reads anyway — a second whole library at the end
     * of every sync. So once [superseded], it waits to be cancelled instead.
     */
    private fun <T> Flow<T>.pacedWhileSyncing(dao: LibraryDao, superseded: () -> Boolean): Flow<T> = flow {
        // Taken before each read, so an edit landing while one runs still ends the wait after it.
        var writes = localWrites.value
        collect { rows ->
            emit(rows)
            if (syncingDao.value === dao) {
                withTimeoutOrNull(SYNC_REREAD_MS) {
                    combine(syncingDao, localWrites) { syncing, n -> syncing !== dao || n != writes }.first { it }
                }
                if (superseded()) awaitCancellation()
            }
            writes = localWrites.value
        }
    }

    /**
     * The downloads table, read once for everything that needs it.
     *
     * There were five observers of this table — ids, total bytes, per-album
     * placements, newest-first order, and the player's map of files on disk —
     * each its own query. Room re-runs *every* observer of a table after every
     * write to it, so a completed download cost five scans, and switching source
     * cost five more. The rows are small and the derivations are cheap; the
     * queries were the expensive part. One read, four derivations.
     *
     * It is deliberately the widest projection of the five rather than the
     * narrowest: fileName and format are only wanted by the player, but carrying
     * them costs a few bytes a row against a whole extra scan of the table.
     * Lyrics stay out — see [LibraryDao.observeDownloads].
     */
    val downloadFiles: StateFlow<List<DownloadFile>> =
        observing(emptyList()) { it.observeDownloads() }
            .onEach { downloadsLoaded.value = true }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether [downloadFiles] has heard from the database yet.
     *
     * It seeds as an empty list, and an empty list is also a perfectly ordinary
     * answer — so "no downloads" and "not asked yet" are the same value and
     * cannot be told apart by reading it. Anything that decides between a local
     * file and a stream has to know which it is looking at: deciding while this
     * is false streams tracks that are sitting on the disk, and the choice is
     * baked into the queue and never revisited. See PlaybackController.
     */
    val downloadsLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Track ids with a completed download. */
    val downloadedTrackIds: StateFlow<Set<String>> =
        downloadFiles.map { rows -> rows.mapTo(HashSet()) { it.trackId } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val offline: StateFlow<Boolean> =
        offlineOnly.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Sweep every library as it is opened, not only when a sync succeeds.
        //
        // A track whose album is not in the same database is never something
        // this app wrote on purpose: the local scan replaces both tables
        // together, a sync writes an album before its tracks, and
        // getAlbumTracks only fetches for an album already present. So an orphan
        // is always debris — in practice another source's songs, left by a sync
        // that was still finishing when the source changed under it.
        //
        // Doing it here rather than inside the sync is what makes it reachable:
        // the sync only prunes when the server answers, so a library behind an
        // expired login kept showing another server's music with no way to clear
        // it. Opening it is enough now.
        scope.launch {
            daos.filterNotNull().collect { d ->
                val orphans = runCatching { d.orphanedTrackCount() }.noteOom("orphan count").getOrDefault(0)
                if (orphans > 0) {
                    android.util.Log.w("AmpSync", "dropping $orphans orphaned track(s) on open")
                    runCatching { d.deleteOrphanedTracks() }.noteOom("orphan sweep")
                }
            }
        }
    }

    /**
     * The last whole read of the tracks table, and the database it came from:
     * what the next read of the same database reuses unchanged songs from.
     *
     * Only ever the browsed database's. A source switch drops it in
     * [trackRows] when the lists change over, so a read never compares one
     * source's rows against another's, and the source left behind isn't kept
     * alive for its objects.
     */
    @Volatile private var lastTracksRead: Pair<LibraryDao, TrackGeneration>? = null

    /**
     * The songs the lists hold right now, if they were read from [dao]; none
     * when they are another database's, or nothing has been read yet.
     *
     * For a page that reads songs out of more than one source's database —
     * see App.downloadsBySource — and wants the browsed one's as the objects
     * already in memory rather than a second copy of them.
     */
    fun loadedTracks(dao: LibraryDao): List<Track> =
        lastTracksRead?.takeIf { it.first === dao }?.second?.tracks.orEmpty()

    /**
     * Every cached track, whichever library it belongs to.
     *
     * The one mapping of the table; [allTracks] narrows this rather than
     * observing again, so choosing a library costs a filter and not a second
     * scan of ten thousand rows. Downloads read from here — what is on the disk
     * is on the disk regardless of which library is being browsed.
     *
     * Room still says when to read: it counts the table again after every
     * write to it and hands each count on, the same number or not, so a count
     * arriving is the signal and its value only sizes the read. The read
     * itself is [readTracks], which keeps the songs that didn't change.
     */
    private val trackRows: StateFlow<List<Track>> =
        observing(emptyList(), rereadTracks, paced = true) { dao ->
            if (lastTracksRead?.first !== dao) lastTracksRead = null
            dao.observeTrackCount().map { count -> readTracks(dao, count) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The tracks table as [dao] holds it now, in storage order.
     *
     * A row at the same rowid with the same content as last time is handed
     * back as the object it was then — see [TrackRead]. A write that changes
     * one song therefore costs one song and a new list, where it used to cost
     * a whole library of new objects, alive next to the old one until every
     * holder of the old list let go.
     */
    private suspend fun readTracks(dao: LibraryDao, count: Int): List<Track> {
        val previous = lastTracksRead?.takeIf { it.first === dao }?.second ?: TrackGeneration.EMPTY
        val read = TrackRead(previous, count)
        dao.readTracks(TRACK_PAGE) { page -> page.forEach { read.add(it.rowId, it.track) } }
        val next = read.finish()
        // Kept only while it is still the browsed database's: a read finishing
        // just after a source switch would otherwise hold the source left
        // behind, every song of it, until the next read.
        if (daos.value === dao) lastTracksRead = dao to next
        android.util.Log.i("AmpLists", "tracks table → ${next.size} rows, ${read.reused} kept as they were")
        return next.tracks
    }

    /** Which of the server's libraries is chosen, or null for all of them. */
    private val selectedLibrary: StateFlow<String?> =
        libraryId.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The libraries the user hid, which are hidden everywhere: not on the
     * Sources page, not under All Libraries, not in search, the tabs or a
     * playlist, and not fetched by a sync. Their rows stay cached, so showing
     * one again is instant — see MusicSource.hiddenLibraryIds.
     */
    private val hiddenLibraries: StateFlow<Set<String>> =
        settings.activeSource
            .map { it?.hiddenLibraryIds?.filterNotNull()?.toSet().orEmpty() }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * The chosen library's albums, filtered out of the cache rather than fetched.
     *
     * Every library the source has stays cached at once, and choosing one is a
     * filter over what is already here — so switching works with no server at
     * all, which is the whole point: the alternative was to empty the cache and
     * re-sync, and offline that emptied it and stopped.
     *
     * A row with no library recorded shows under every one. It predates the
     * column and there is nothing to file it under; hiding it would lose a
     * library that is sitting right there until the next sync says otherwise.
     */
    /** The album table as stored, library tags and all. */
    private val albumRows: StateFlow<List<AlbumEntity>> =
        observing(emptyList(), paced = true) { it.observeAlbums() }
            .onEach { android.util.Log.i("AmpLists", "albums table → ${it.size} rows (re-read ${reread.value})") }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val allAlbums: StateFlow<List<Album>> =
        combine(albumRows, selectedLibrary, hiddenLibraries) { rows, library, hidden ->
            rows.asSequence()
                .filter { library == null || it.libraryId == null || it.libraryId == library }
                // Tracks, artists, search and the liked filters all narrow from
                // here, so this one line hides a library everywhere they look.
                .filter { it.libraryId == null || it.libraryId !in hidden }
                .map { it.toAlbum() }
                .toList()
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * And its songs, which are the ones on those albums.
     *
     * A track carries no library of its own — it belongs to whichever library
     * its record does, and one column is one thing to keep in step instead of
     * two. A track with no album at all is kept: the sync prunes those (see
     * runSync), so one still here is a row nothing has spoken for yet.
     */
    private val allTracks: StateFlow<List<Track>> =
        combine(trackRows, allAlbums) { rows, albums ->
            val visible = albums.mapTo(HashSet()) { it.id }
            rows.filter { it.albumId == null || it.albumId in visible }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The whole cached library, ignoring the offline view filter.
     *
     * The download top-up is triggered off this, never off [tracks]: in Wi-Fi
     * Only mode (or whenever the server is unreachable) [tracks] narrows to what
     * is already downloaded, so a trigger keyed on it would fire on the wrong
     * changes. What the top-up actually *fetches* it reads from each source's
     * own tables — see App.topUpDownloads — since it runs for every source, not
     * only the one these lists follow.
     */
    val fullTracks: StateFlow<List<Track>> get() = allTracks
    val fullAlbums: StateFlow<List<Album>> get() = allAlbums

    /**
     * The library as the UI should see it. When offline, this narrows to what's
     * actually playable — so browsing, search, and queue-building all follow
     * automatically instead of each screen having to check.
     */
    val tracks: StateFlow<List<Track>> =
        combine(allTracks, downloadedTrackIds, offline) { list, downloaded, offlineOnlyNow ->
            if (offlineOnlyNow) list.filter { it.id in downloaded } else list
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> =
        combine(allAlbums, tracks, offline) { list, visible, offlineOnlyNow ->
            if (!offlineOnlyNow) {
                list
            } else {
                val ids = visible.mapNotNull { it.albumId }.toSet()
                list.filter { it.id in ids }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // How many tracks each album has, rebuilt only when the library itself changes.
    // Without this the album-badge flow re-grouped all ten thousand tracks after
    // every single completed download.
    //
    // Each memo holds the list it was worked out from, and with it every song
    // in it — so it is dropped wherever that list stops being refreshed: on a
    // source switch (see forgetDerived), and once there are no downloads to
    // badge. Kept, it held a library long gone.
    @Volatile private var albumSizes: Pair<List<Track>, Map<String, Int>>? = null

    private fun albumSizes(list: List<Track>): Map<String, Int> =
        albumSizes?.takeIf { it.first === list }?.second
            ?: list.mapNotNull { it.albumId }.groupingBy { it }.eachCount().also { albumSizes = list to it }

    /**
     * Albums whose every cached track is downloaded — drives the download badge.
     *
     * Counts downloaded rows per album and compares against the album's size, so
     * the per-download cost is the number of downloads rather than the size of the
     * library, and no track lists are allocated.
     */
    val downloadedAlbumIds: StateFlow<Set<String>> =
        combine(allTracks, downloadFiles) { list, rows ->
            if (rows.isEmpty()) {
                albumSizes = null
                return@combine emptySet()
            }
            val sizes = albumSizes(list)
            val have = HashMap<String, Int>()
            for (row in rows) {
                val album = row.albumId ?: continue
                have[album] = (have[album] ?: 0) + 1
            }
            have.entries.mapNotNullTo(mutableSetOf()) { (album, n) ->
                album.takeIf { sizes[album] == n }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    // How many tracks each artist has, rebuilt only when the library itself
    // changes — the same memoisation as albumSizes, for the same reason.
    @Volatile private var artistSizes: Pair<List<Track>, Map<String, Int>>? = null

    private fun artistSizes(list: List<Track>): Map<String, Int> =
        artistSizes?.takeIf { it.first === list }?.second
            ?: list.flatMap { it.albumArtistNames() }.groupingBy { it }.eachCount().also { artistSizes = list to it }

    /**
     * Artists whose every cached track is downloaded — drives the artist list's
     * download badge, the same way [downloadedAlbumIds] drives the album one.
     */
    val downloadedArtistNames: StateFlow<Set<String>> =
        combine(allTracks, downloadFiles) { list, rows ->
            if (rows.isEmpty()) {
                artistSizes = null
                trackIndex = null
                return@combine emptySet()
            }
            val sizes = artistSizes(list)
            // The memoised index: this recomputes every time a download lands,
            // and building a ten-thousand-entry map per finished track is
            // exactly what that memo exists to avoid.
            val byId = trackIndex(list)
            val have = HashMap<String, Int>()
            for (row in rows) {
                val credits = byId[row.trackId]?.albumArtistNames() ?: continue
                for (artist in credits) {
                    have[artist] = (have[artist] ?: 0) + 1
                }
            }
            have.entries.mapNotNullTo(mutableSetOf()) { (artist, n) ->
                artist.takeIf { sizes[artist] == n }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Track lookup for downloadedArtistNames, memoised on the library list
    // instance so a completed download doesn't rebuild a ten-thousand-entry map.
    @Volatile private var trackIndex: Pair<List<Track>, Map<String, Track>>? = null

    private fun trackIndex(list: List<Track>): Map<String, Track> =
        trackIndex?.takeIf { it.first === list }?.second
            ?: list.associateBy { it.id }.also { trackIndex = list to it }

    /**
     * The tag values this library actually uses.
     *
     * Derived from the cache rather than fetched: `getGenres` exists, but there
     * is no equivalent for composers, and a list built from the tracks on hand is
     * the same answer for all three. Empty means the server doesn't fill that
     * field — which is how the More page decides whether to offer it at all.
     *
     * Worked out only while a page shows them. Once started they used to run
     * for the rest of the process, two passes over the whole library on every
     * read of it, a sync's included. Stopped, they keep their last answer, so
     * a page opening shows that while the new one is worked out rather than
     * an empty list — unless the source has changed since, when that answer
     * is another server's, and could set a filter this library can't match.
     */
    private val tagValues = mutableListOf<MutableStateFlow<List<String>>>()

    val genres: StateFlow<List<String>> = tagValuesOf { it.genre }

    val composers: StateFlow<List<String>> = tagValuesOf { it.composer }

    /**
     * What stateIn with WhileSubscribed does, by hand: a stateIn's value can't
     * be emptied from outside, and a source switch has to — see forgetDerived.
     */
    private fun tagValuesOf(select: (Track) -> String): StateFlow<List<String>> {
        val values = MutableStateFlow(emptyList<String>())
        tagValues += values
        scope.launch {
            SharingStarted.WhileSubscribed(TAG_VALUES_KEEP_MS).command(values.subscriptionCount)
                .collectLatest { command ->
                    if (command == SharingCommand.START) tracksFlowOf(select).collect { values.value = it }
                }
        }
        return values.asStateFlow()
    }

    private fun tracksFlowOf(select: (Track) -> String): Flow<List<String>> =
        tracks.map { list ->
            list.asSequence()
                .flatMap { splitTagValues(select(it)) }
                .distinct()
                .sortedBy { nameKey(it) }
                .toList()
        }


    /** Everything tagged with [genre], as tracks. */
    fun tracksWithGenre(genre: String): List<Track> =
        tracks.value.filter { track -> splitTagValues(track.genre).any { it.equals(genre, true) } }

    fun tracksWithComposer(composer: String): List<Track> =
        tracks.value.filter { track -> splitTagValues(track.composer).any { it.equals(composer, true) } }

    /**
     * How many songs carry each value of one tag — what the tag lists sort by
     * when they aren't in name order.
     *
     * Keyed exactly as [genres] and [composers] list them, so every value has a
     * count and none is split across two spellings of the same word.
     */
    fun tagCounts(byComposer: Boolean): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        tracks.value.forEach { track ->
            splitTagValues(if (byComposer) track.composer else track.genre).forEach { value ->
                counts[value] = (counts[value] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * The artists starred on the server.
     *
     * Deliberately not derived from [artists], which narrows with the offline
     * view and the chosen library: whether you have starred someone is not a
     * fact about what is on screen. The download top-up reads the same table
     * directly, for the same reason — see App.topUpDownloads.
     */
    val likedArtistNames: StateFlow<Set<String>> =
        observing(emptyList()) { it.observeLikedArtists() }.map { names -> names.toSet() }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Each artist's picture, keyed by the name the library knows them under.
     *
     * The app's artists come from track tags and so have nothing but a name; the
     * server keeps its own record of the same artist, with a picture on it. This
     * is the join between the two, filled by [primeArtistImages].
     */
    private val _artistImages = MutableStateFlow<Map<String, String>>(emptyMap())

    val artists: StateFlow<List<Artist>> =
        combine(tracks, likedArtistNames, _artistImages) { list, liked, images ->
            deriveArtists(list, liked, images)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Fetch the server's artist index for its pictures, once per source.
     *
     * One request for the whole list, asked for by the pages that show artists
     * rather than on every sync: a library with no artist art on the server
     * would otherwise pay for it on every launch. Failure leaves the map as it
     * was, so the rows simply show their placeholder.
     */
    suspend fun primeArtistImages() {
        if (_artistImages.value.isNotEmpty()) return
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        val refs = runCatching { client.getArtistIndex(libraryId.first()) }.noteOom("artist index").getOrNull() ?: return
        // Doubles as the id index that starring needs, which is the same call.
        if (artistIds == null) artistIds = refs.associate { it.name to it.id }
        _artistImages.value = refs
            .mapNotNull { ref -> ref.imageId?.takeIf { it.isNotBlank() }?.let { ref.name to it } }
            .toMap()
    }

    // State, not cold flows: a screen collecting one of these with an empty
    // initial value showed "No liked albums yet" for as long as the push took,
    // which read as the app flashing through a different page on the way.
    val likedArtists: StateFlow<List<Artist>> =
        artists.map { list -> list.filter { it.liked } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val likedTracks: StateFlow<List<Track>> =
        tracks.map { list -> list.filter { it.liked } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val likedAlbums: StateFlow<List<Album>> =
        albums.map { list -> list.filter { it.liked } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState

    // Serializes sync + switch so they never write the cache concurrently; the
    // job handle lets a library switch cancel an in-flight sync.
    private val syncMutex = Mutex()
    private var syncJob: Job? = null

    // Serializes playlist mutations: servers read-then-write full playlist state
    // with no compare-and-swap, so concurrent edits can clobber each other.
    private val playlistMutex = Mutex()

    /** Runs [block] with playlist writes serialized — see [playlistMutex]. First come, first written. */
    private suspend fun <T> playlistWrite(block: suspend () -> T): T = playlistMutex.withLock { block() }

    /**
     * Wait out the playlist writes already queued, then return — called before a
     * playlist is read from the server. Edits show on screen before they are
     * written, and a Plex reorder is seconds of single moves, so a read that
     * doesn't wait can come back with the order from before the edit, or one
     * half-moved: Play from the playlist's menu would then queue something other
     * than the list the user just arranged. The lock is fair, so taking it once
     * is waiting for everyone ahead; it isn't held for the read itself, which
     * can be slow and has no reason to keep edits waiting.
     */
    private suspend fun awaitPlaylistWrites() = playlistMutex.withLock { }

    // name -> server artist id, resolved once and reused for starring.
    private var artistIds: Map<String, String>? = null

    // artist name -> top songs, so revisiting an artist page doesn't re-hit the
    // server (and the Last.fm-backed ordering stays stable within a session).
    private val topSongs = ConcurrentHashMap<String, List<Track>>()


    // Playlists are fetched live from the server (not cached in Room).
    /**
     * Membership cache backing [downloadedPlaylistIds].
     *
     * `getPlaylists` — Subsonic's and Plex's alike — never returns a playlist's
     * songs, only its metadata, so the download badge in the playlist list has
     * nothing to compare against until something calls [playlistTracks] once per
     * playlist. [primePlaylistTrackIds] does that filling for the list itself.
     */
    private val _playlistTrackIds = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /**
     * Which songs each playlist holds, for the playlists primed so far — see
     * [primePlaylistTrackIds]. The list itself never carries this: no server
     * sends membership with its playlist list, so [Playlist.trackIds] is empty
     * for every server playlist and anything reading it there reads nothing.
     */
    val playlistTrackIds: StateFlow<Map<String, List<String>>> = _playlistTrackIds

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    /**
     * The playlists as the UI should see them — narrowed offline, as the other
     * three lists are.
     *
     * A playlist whose membership isn't known stays visible: no server returns
     * songs with the list (see [_playlistTrackIds]), so an empty [Playlist.trackIds]
     * means "not asked yet" far more often than it means "empty". Hiding those
     * would take away playlists that are downloaded and playable, which is worse
     * than leaving one showing that turns out to have nothing in it.
     */
    val playlists: StateFlow<List<Playlist>> =
        combine(_playlists, _playlistTrackIds, downloadedTrackIds, offline) {
                list, membership, downloaded, offlineOnlyNow ->
            if (!offlineOnlyNow) {
                list
            } else {
                list.filter { playlist ->
                    val ids = membership[playlist.id] ?: playlist.trackIds
                    ids.isEmpty() || ids.any { it in downloaded }
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

     /**
     * Re-read the playlists, keeping the last known set when the server can't be
     * reached.
     *
     * Playlists have no table of their own, so an unhandled failure here used to
     * be the crash you got by opening the tab on a plane. The cached copy lives
     * in preferences — enough to list them, and enough (via [playlistTracks]) to
     * play the parts of them that are downloaded.
     */
    /**
     * The playlists, when they live on this phone rather than on a server.
     *
     * Music on the phone has no server to ask, exactly as with the library
     * scan — see [runLocalScan]. Its playlists are files in the music folder
     * instead; see [PhonePlaylists].
     */
    private suspend fun phonePlaylists(): PhonePlaylists? =
        settings.activeSource.first()?.phoneLibrary?.playlists

    suspend fun refreshPlaylists() {
        val sourceId = settings.activeSource.first()?.id ?: return
        val phone = phonePlaylists()
        if (phone != null) {
            // Read straight off the disk every time: the files are the truth,
            // and another app (or an adb push) may have changed them.
            _playlists.value = phone.list()
            return
        }
        if (!metadataAllowed()) return
        val fetched = runCatching { serverClient.value?.getPlaylists(libraryId.first()) }.getOrNull()
        if (fetched != null) {
            _playlists.value = fetched
            settings.setCachedPlaylists(sourceId, json.encodeToString(fetched))
            return
        }
        if (_playlists.value.isEmpty()) _playlists.value = loadCachedPlaylists()
    }

    /** Restores the last known playlists so a cold start offline isn't blank. */
    suspend fun primePlaylists() {
        if (_playlists.value.isEmpty()) _playlists.value = loadCachedPlaylists()
    }

    private suspend fun loadCachedPlaylists(): List<Playlist> {
        val sourceId = settings.activeSource.first()?.id ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<Playlist>>(settings.cachedPlaylists(sourceId))
        }.getOrDefault(emptyList())
    }

    /**
     * A playlist's songs — from the server, or rebuilt from the cached membership
     * against the local library when it can't be asked.
     *
     * The offline rebuild keeps the playlist's order and silently drops tracks
     * the library no longer holds, which is the same thing the server would do.
     */
    suspend fun playlistTracks(id: String): List<Track> = playlistView(id).tracks

    /** [playlistTracks] plus whether it's the whole playlist — see [PlaylistView]. */
    suspend fun playlistView(id: String): PlaylistView {
        val (tracks, whole) = fetchPlaylistTracks(id)
        // Cached below at full membership, then narrowed on the way out: the
        // badge needs to know what the playlist *is*, the page needs to show
        // what it can actually play.
        // An empty answer is not cached. It means either a genuinely empty
        // playlist or a fetch that failed — and this path can't tell them apart,
        // where the one below can. Kept, it would satisfy primePlaylistTrackIds'
        // "already known" guard for the rest of the session, so one bad moment
        // would leave that playlist's badge off until the app restarted.
        if (tracks.isNotEmpty()) {
            _playlistTrackIds.update { it + (id to tracks.map { track -> track.id }) }
        }
        // A hidden library's songs leave the page too. Marked incomplete when
        // any did, which keeps the playlist from being edited here: an edit
        // writes back the list as shown, and would delete them from the server.
        val hiddenAlbums = hiddenLibraries.value.takeIf { it.isNotEmpty() }?.let { hidden ->
            albumRows.value.asSequence().filter { it.libraryId in hidden }.map { it.id }.toSet()
        }.orEmpty()
        val shown = if (hiddenAlbums.isEmpty()) tracks else tracks.filterNot { it.albumId in hiddenAlbums }
        val wholeShown = whole && shown.size == tracks.size
        if (!offline.value) return PlaylistView(shown, wholeShown)
        // A song from the phone's own folder needs no server, so it stays whatever
        // the connection is.
        val downloaded = downloadedTrackIds.value
        val playable = shown.filter { it.id in downloaded || LocalLibrary.isLocal(it.id) }
        return PlaylistView(playable, wholeShown && playable.size == shown.size)
    }

    /** The songs, and whether they're the playlist entire rather than what could be recovered. */
    private suspend fun fetchPlaylistTracks(id: String): PlaylistView {
        awaitPlaylistWrites()
        val phone = phonePlaylists()
        if (phone != null) {
            val ids = phone.trackIds(id)
            val tracks = getTracksByIds(ids)
            return PlaylistView(tracks, tracks.size == ids.size)
        }
        // The list of playlists and their membership both wait for a connection
        // the mode allows; opening one asked the server regardless, which made
        // Wi-Fi Only mean something different depending on which way in you
        // took. The fallback below needs no network and was already written —
        // it was simply never reached until the request had failed.
        if (metadataAllowed()) {
            serverClient.value?.let { client ->
                runCatching { client.getPlaylistTracks(id) }.getOrNull()
                    ?.let { return PlaylistView(it, complete = true) }
            }
        }
        // The rebuild is a guess at what the server holds, not a reading of it:
        // the cached membership can be stale and the library can be missing rows
        // for ids it names, so nothing built here is safe to edit against.
        // Membership already fetched this session first: no server returns
        // songs with the list of playlists, so [Playlist.trackIds] is usually
        // empty — and an empty rebuild is a playlist that opens with nothing
        // in it, on exactly the slow link that made the request fail.
        val ids = _playlistTrackIds.value[id]
            ?: (_playlists.value.firstOrNull { it.id == id } ?: return PlaylistView(emptyList(), false)).trackIds
        return PlaylistView(getTracksByIds(ids), complete = false)
    }

    /**
     * Primes currently running, by playlist id.
     *
     * The "already known" check below can only see work that has *finished*,
     * and on launch two callers arrive about three seconds apart — App.boot and
     * the library shell composing. Both looked, both found nothing, and both
     * fetched: for an 8,293-track playlist that was the same 1.3 MB answer
     * twice, one copy of it abandoned half-transferred when its screen went
     * away. Knowing what is in flight is what the guard was missing.
     */
    private val playlistPrimes = mutableMapOf<String, Deferred<Unit>>()
    private val playlistPrimeLock = Mutex()

    /**
     * Fills the membership cache for [id] if it isn't already known or on its
     * way, and otherwise waits for the fetch already running.
     *
     * Talks to the server directly rather than through [playlistTracks], and
     * skips the cache write on failure — [playlistTracks]'s offline rebuild is
     * the right fallback for a screen that must show *something*, but caching
     * that guess here would freeze a transient failure as "confirmed empty"
     * for the rest of the session.
     */
    suspend fun primePlaylistTrackIds(id: String) {
        if (id in _playlistTrackIds.value) return
        val prime = playlistPrimeLock.withLock {
            // Re-read inside the lock: the fetch this caller would have started
            // may have finished while it waited for the lock.
            if (id in _playlistTrackIds.value) return
            val running = playlistPrimes[id]
            // A finished prime is not a running one. It may have failed, and
            // the next caller deserves a fresh attempt rather than a share of
            // that empty answer — which is also why nothing has to be swept up
            // afterwards: a stale entry is simply never reused.
            if (running != null && running.isActive) {
                running
            } else {
                // In the repository's scope, not the caller's, so a screen
                // going away mid-fetch no longer throws the download out with
                // it. The work is shared; whoever is still waiting gets it.
                scope.async { primePlaylistOnce(id) }.also { playlistPrimes[id] = it }
            }
        }
        // Deliberately unguarded: this caller being cancelled should cancel
        // this caller, and leave the shared fetch running for the others.
        prime.await()
    }

    private suspend fun primePlaylistOnce(id: String) {
        if (phonePlaylists() != null || serverClient.value == null) {
            playlistTracks(id)
            return
        }
        if (!metadataAllowed()) return
        awaitPlaylistWrites()
        val tracks = runCatching { serverClient.value?.getPlaylistTracks(id) }.noteOom("playlist prime $id").getOrNull() ?: return
        _playlistTrackIds.update { it + (id to tracks.map { track -> track.id }) }
    }

    /**
     * The same, for a whole list — what the playlists tab actually needs.
     *
     * One request per playlist is unavoidable, since no server returns
     * membership with the list. Running them one after another is not: forty
     * playlists meant forty serial round trips before the last badge could
     * appear. Same gate as the library sync, so a tab opening can't take the
     * server apart either.
     */
    suspend fun primePlaylistTrackIds(ids: List<String>) = coroutineScope {
        // Every playlist's full contents, six at a time, is the heaviest thing
        // launch does after the sync — one playlist was measured at 1.3 MB —
        // and all it buys is download badges and the download top-up. On a
        // metered link outside Make it Hurt nothing can be downloaded anyway,
        // so it waits for Wi-Fi. Opening a playlist still fetches that one.
        if (!bulkAllowed() && phonePlaylists() == null) return@coroutineScope
        val gate = Semaphore(SYNC_CONCURRENCY)
        ids.filterNot { it in _playlistTrackIds.value }
            .map { id ->
                launch {
                    gate.withPermit {
                        yieldToPlayback()
                        primePlaylistTrackIds(id)
                    }
                }
            }
            .joinAll()
    }

    /** Playlists whose every known track is downloaded — drives the download badge. */
    val downloadedPlaylistIds: StateFlow<Set<String>> =
        combine(_playlistTrackIds, downloadedTrackIds) { membership, downloaded ->
            membership.entries.mapNotNullTo(mutableSetOf()) { (playlistId, ids) ->
                playlistId.takeIf { ids.isNotEmpty() && ids.all { trackId -> trackId in downloaded } }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun createPlaylist(name: String): WriteResult = createPlaylist(name, emptyList())

    /**
     * [trackIds] are the playlist's opening contents, not a hint — see
     * [MusicServer.createPlaylist]. Callers must not add them again.
     *
     * This and the edits after it say when they didn't land — see [said] —
     * and answer how it went, for a caller with more to do after.
     */
    suspend fun createPlaylist(name: String, trackIds: List<String>): WriteResult = playlistMutex.withLock {
        val phone = phonePlaylists()
        val result = if (phone != null) {
            fileWrite(phone.create(name.trim(), trackIds) != null)
        } else {
            serverWrite { it.createPlaylist(name.trim(), trackIds) }
        }
        refreshPlaylists()
        said("createPlaylist", result)
    }

    suspend fun renamePlaylist(id: String, name: String): WriteResult = playlistWrite {
        val phone = phonePlaylists()
        val result = if (phone != null) {
            fileWrite(phone.rename(id, name.trim()))
        } else {
            serverWrite { it.renamePlaylist(id, name.trim()) }
        }
        refreshPlaylists()
        said("renamePlaylist($id)", result)
    }

    suspend fun deletePlaylist(id: String): WriteResult = playlistWrite {
        val phone = phonePlaylists()
        val result = if (phone != null) {
            fileWrite(phone.delete(id))
        } else {
            serverWrite { it.deletePlaylist(id) }
        }
        refreshPlaylists()
        said("deletePlaylist($id)", result)
    }

    /**
     * Append one track. Sequential by contract: the server's update appends, so
     * concurrent calls would race the order. [playlistMutex] is what makes that
     * contract hold across calls, not just within a single caller's loop.
     */
    suspend fun addToPlaylist(id: String, trackId: String): WriteResult = playlistWrite {
        val phone = phonePlaylists()
        val result = if (phone != null) {
            fileWrite(phone.add(id, trackId))
        } else {
            serverWrite { it.addToPlaylist(id, trackId) }
        }
        said("addToPlaylist($id)", result)
    }

    /** No client at all is a server that can't be reached, not one that said no. */
    private suspend fun serverWrite(block: suspend (MusicServer) -> Unit): WriteResult =
        serverClient.value?.let { client -> attemptWrite { block(client) } } ?: WriteResult.Unreachable(UNREACHABLE)

    /** The phone's own playlists are files, and a file that wouldn't change is the only way one fails. */
    private fun fileWrite(done: Boolean): WriteResult =
        if (done) WriteResult.Saved else WriteResult.Refused("Couldn't change the playlist file")

    /**
     * A playlist write that didn't land is said, and that is all: none of
     * these show their change before the server has it, so there is nothing on
     * screen to take back.
     */
    private fun said(what: String, result: WriteResult): WriteResult {
        val reason = when (result) {
            WriteResult.Saved -> return result
            is WriteResult.Unreachable -> result.reason
            is WriteResult.Refused -> result.reason
        }
        android.util.Log.w("AmpSync", "$what → $result")
        tell("Couldn't save\n$reason")
        return result
    }

    /**
     * Remove the entries [at] these positions in the playlist, each given with the
     * song expected there — see [MusicServer.removeFromPlaylistAt].
     * Whether they all went: callers show the edit at once and take it back on
     * a false.
     */
    suspend fun removeFromPlaylistAt(id: String, at: Map<Int, String>): Boolean = playlistWrite {
        val phone = phonePlaylists()
        if (phone != null) {
            phone.removeAt(id, at)
        } else {
            // The client's own answer, not merely "it didn't throw": some of them
            // catch their transport errors internally, so isSuccess here would be
            // true for a write the server refused — and for no client at all.
            runCatching { serverClient.value?.removeFromPlaylistAt(id, at) ?: false }
                .onFailure { android.util.Log.w("AmpSync", "removeFromPlaylistAt($id) failed: ${it.message}", it) }
                .getOrDefault(false)
        }
    }

    /** Whether the new order landed — see [removeFromPlaylistAt]. */
    suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>): Boolean = playlistWrite {
        val phone = phonePlaylists()
        if (phone != null) {
            phone.reorder(id, orderedSongIds)
        } else {
            // See removeFromPlaylistAt: the client's answer, not the absence of a throw.
            runCatching { serverClient.value?.reorderPlaylist(id, orderedSongIds) ?: false }
                .onFailure { android.util.Log.w("AmpSync", "reorderPlaylist($id) failed: ${it.message}", it) }
                .getOrDefault(false)
        }
    }

    // --- Sync ----------------------------------------------------------------

    /** The server's libraries (music folders); empty if unsupported or on error. */
    suspend fun musicFolders(): List<MusicFolder> =
        runCatching { serverClient.value?.getMusicFolders().orEmpty() }.getOrDefault(emptyList())

    /** Full incremental sync of the currently selected library. Safe to repeat. */
    suspend fun sync() = syncScoped(libraryId.first())

    /**
     * Switch to [musicFolderId]: cancel any in-flight sync, clear the cache, and
     * re-sync just that library. Serialized through [syncMutex] so a background
     * sync can't interleave and re-leak the previous library's rows.
     *
     * **Nothing is thrown away.** Every library the source has stays cached
     * side by side, tagged with where it came from, and choosing one filters the
     * cache — see [allAlbums]. This used to clear and re-fetch, which needs a
     * server: offline it emptied the library and left it empty, with the
     * downloaded files still on the disk and nothing pointing at them.
     *
     * The sync that follows is a refresh, not the thing that makes the switch
     * work. It failing — or never running, because there is no signal — leaves
     * the chosen library showing whatever was last cached for it.
     */
    suspend fun switchLibrary(musicFolderId: String?) {
        syncJob?.cancelAndJoin()
        val job = scope.launch {
            syncMutex.withLock {
                runSync(musicFolderId)
            }
        }
        syncJob = job
        job.join()
    }

    private suspend fun syncScoped(musicFolderId: String?) {
        syncMutex.withLock { runSync(musicFolderId) }
    }

    /**
     * Drop whatever is in flight, because it is for a source we have left.
     *
     * The writes are already pinned to the database they started with, so this
     * is not what keeps them apart — it stops the app spending a fetch, and a
     * failure, on a server the user is no longer looking at. A sync that fails
     * after the switch would otherwise report *this* source as unreachable.
     */
    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * Start the sync state over for the source just switched to, from the time
     * it last finished a sync.
     *
     * There is one state for the whole app, and it used to carry straight
     * across a switch: the source just added read "last synced 2 minutes ago"
     * — the previous one's time — and, worse, its first sync was turned away by
     * the floor in [syncInBackground], which asked that same clock, so a new
     * server sat empty until minutes later something asked again. The last
     * one's error and its refusal to scan were inherited the same way.
     */
    fun adoptSource(lastSyncedMs: Long) {
        scanRefused = false
        _syncState.value = SyncState(lastSyncedMs = lastSyncedMs)
    }

    /**
     * Put back a `syncing` claim that [runSync] turned out to have no work for.
     *
     * Only clears the flag: an error already set here by an earlier run is
     * left to stand, since nothing about "there was nothing to sync" makes it
     * untrue.
     */
    private fun releaseSyncClaim() {
        _syncState.value = _syncState.value.copy(syncing = false, phase = "")
    }

    /** The actual scoped sync (null = all libraries); callers serialize via [syncMutex]. */
    /**
     * Try the server's other advertised addresses, and keep one that answers.
     *
     * Plex hands out a LAN address and one or more remote ones. Whichever
     * answered at link time is almost always the LAN one — and it stops working
     * the moment the phone leaves that network, with nothing to re-resolve it.
     * The stored source then points at 192.168.x.x for ever and Plex appears
     * broken on cellular no matter what else is changed.
     *
     * Returns true when the address changed; saving the source rebuilds the
     * client, so playback and downloads follow it without being told.
     */
    private suspend fun repointPlex(): Boolean {
        val source = settings.activeSource.first() ?: return false
        if (source.kind != SourceKind.PLEX || source.connections.isEmpty()) return false
        val ordered = (source.connections - source.baseUrl) + source.baseUrl
        val found = PlexAccount.firstReachable(ordered, source.token, source.plexClientIdentifier) ?: return false
        if (found == source.baseUrl) return false
        android.util.Log.i("AmpSync", "plex moved: ${source.baseUrl} -> $found")
        settings.saveSource(source.copy(baseUrl = found))
        return true
    }

    private suspend fun runSync(musicFolderId: String?, allowRepoint: Boolean = true) {
        val source = settings.activeSource.first()
        // Pinned before anything is written — see the note below; the local
        // scan needs the same guarantee, a switch away mid-scan would
        // otherwise write this phone's files into a server's library.
        // Each early return below clears the claim its caller staked, because
        // the callers now set `syncing` before launching so a duplicate can be
        // turned away while this one is still awaiting its first suspend. A
        // return that left the flag standing would turn away every later sync
        // too, not just the duplicate — the library would go quiet for the
        // life of the process. runLocalScan is exempt: it owns the flag across
        // its whole run.
        val dao = daos.value ?: return releaseSyncClaim()
        // The phone's own music has no server to ask; it is read off the disk.
        val phone = source?.phoneLibrary
        if (source != null && phone != null) {
            runLocalScan(dao, source.id, phone)
            return
        }
        // Wi-Fi Only off Wi-Fi means no network — and a sync is hundreds of
        // requests, plus the download top-up it triggers on success.
        if (!metadataAllowed()) return releaseSyncClaim()
        // A build that changed how a track is read has to refill the cache once,
        // because the incremental filter below can only see what the *server*
        // changed — see MusicSource.parserGeneration.
        val refillForParser =
            source != null && source.parserGeneration != TRACK_PARSER_GENERATION
        val client = serverClient.value ?: return releaseSyncClaim()
        // `dao` above is pinned for the run, exactly as the client is. The
        // property of that name re-reads whichever database is current, so a
        // source switched while this was in flight moved the writes onto the
        // new source's database while the fetches carried on against the old
        // server — one server's albums and tracks landing in another's
        // library. Both ends of a sync belong to the source it started for,
        // and every helper this calls takes the pinned one rather than the
        // property: the song-fetch phase didn't, and thirty of Plex's albums
        // turned up in a Navidrome library, untagged, with ids Navidrome had
        // never issued.
        _syncState.value = _syncState.value.copy(syncing = true, error = null, phase = "Connecting")
        syncingDao.value = dao
        // What the run wrote, so the re-read at its end covers that and no more.
        val wrote = SyncWrites()
        try {
            // Read before the first write — see reportDroppedCovers.
            val coversBefore = coverIdsOrNull(dao)
            // Rows with no library recorded show in *every* library (see
            // allAlbums), so an untagged album leaks into libraries it was never
            // in. A scoped sync can only tag the folder it fetched; this asks
            // the server for each folder's album list — cheap, no songs — and
            // stamps every row it names. Runs only while untagged rows exist,
            // which after the first pass is never.
            val untagged = dao.untaggedAlbumCount()
            if (untagged > 0) {
                val folders = client.getMusicFolders()
                if (folders.size >= 2) {
                    _syncState.value = _syncState.value.copy(phase = "Sorting libraries")
                    var stamped = 0
                    var named = 0
                    for (folder in folders) {
                        val ids = client.getAllAlbums(folder.id).map { it.id }
                        named += ids.size
                        ids.chunked(500).forEach { chunk -> stamped += dao.tagAlbums(folder.id, chunk) }
                    }
                    if (stamped > 0) wrote.albums = true
                    // Whatever is still untagged after every folder has spoken is
                    // an album the server has nowhere — its id gone or changed,
                    // as a re-import does — and, being untagged, it was exempt
                    // from every scoped prune and showed in every library. Now
                    // a fetch *can* speak for it: the lists just read are the
                    // whole server. Same empty-answer rule as the main prune.
                    val dead = if (named > 0) dao.untaggedAlbumIds() else emptyList()
                    if (dead.isNotEmpty()) {
                        val deadSet = dead.toSet()
                        val sample = dao.allAlbumsSnapshot()
                            .filter { it.id in deadSet }
                            .take(5)
                            .joinToString { "${it.artist} – ${it.title} [${it.id}]" }
                        android.util.Log.w("AmpSync", "dropping albums the server has nowhere, e.g. $sample")
                        dead.forEach { dao.deleteTracksForAlbum(it) }
                        dao.deleteAlbums(dead)
                        wrote.tracks = true
                        wrote.albums = true
                    }
                    android.util.Log.i(
                        "AmpSync",
                        "retagged $stamped album rows across ${folders.size} libraries; " +
                            "$untagged had no library, ${dead.size} of them exist nowhere and were dropped",
                    )
                }
            }
            // "All libraries" has to be fanned out per folder and merged: an
            // unscoped getAlbumList2 returns only the server's default library,
            // so asking once would silently hide every other library.
            //
            // A hidden library is left out: hidden means not wanted on this
            // phone, which includes the minutes and the data a big one costs.
            val hidden = source?.hiddenLibraryIds?.filterNotNull()?.toSet().orEmpty()
            val folderIds: List<String?> = if (musicFolderId != null) {
                listOf(musicFolderId)
            } else {
                client.getMusicFolders().map { it.id }.filterNot { it in hidden }.ifEmpty { listOf(null) }
            }

            // Null when the server didn't answer: likes then stay as they are,
            // rather than being reconciled against an empty answer — which
            // unliked everything — or failing the whole sync over them.
            val starred = runCatching { folderIds.map { client.getStarred(it) }.mergeStarred() }
                .noteOom("sync: favourites")
                .onFailure { android.util.Log.w("AmpSync", "favourites unread, likes kept: $it") }
                .getOrNull()
            val cached = dao.allAlbumsSnapshot().associateBy { it.id }

            _syncState.value = _syncState.value.copy(phase = "Fetching albums")
            // Which folder each album came from is kept, not merged away: it is
            // written onto the row so that switching library later is a filter
            // over the cache rather than a fetch — see AlbumEntity.libraryId.
            // An album in two folders keeps the first, as distinctBy always did.
            val fetched = folderIds
                .flatMap { folder ->
                    val list = client.getAllAlbums(folder)
                    android.util.Log.i(
                        "AmpSync",
                        "folder=$folder returned ${list.size} albums" +
                            list.take(3).joinToString(prefix = " e.g. ") { it.title },
                    )
                    list.map { it to folder }
                }
                .distinctBy { (album, _) -> album.id }
            val serverAlbums = fetched.map { (album, _) ->
                album.copy(liked = starred?.let { album.id in it.albumIds } ?: cached[album.id]?.liked ?: album.liked)
            }
            val libraryOf = fetched.associate { (album, folder) -> album.id to folder }
            val serverAlbumById = serverAlbums.associateBy { it.id }

            android.util.Log.i(
                "AmpSync",
                "scope=$musicFolderId cached rows by library tag: " +
                    cached.values.groupBy { it.libraryId }.mapValues { it.value.size },
            )

            dao.upsertAlbums(serverAlbums.map { it.toEntity(libraryOf[it.id]) })
            if (serverAlbums.isNotEmpty()) wrote.albums = true

            // Prune albums (and their tracks) that no longer exist on the server —
            // but never on an empty result.
            //
            // A fetch that succeeds and returns nothing is indistinguishable from
            // a server whose library has genuinely been emptied: a stale library
            // scope, a 200 with an empty body, a section id that no longer
            // resolves. Pruning on that deletes the whole cached library, and
            // because the next sync asks the same question the same way, syncing
            // again doesn't bring it back. A server that really has no albums
            // shows an empty library either way, so there is nothing to lose by
            // refusing to act on it.
            if (serverAlbums.isEmpty()) {
                android.util.Log.w("AmpSync", "returned no albums; keeping ${cached.size} cached")
            } else {
                // Only within the library that was just fetched. Syncing one
                // folder says nothing about what is in the others, and pruning
                // against its answer would delete every album belonging to the
                // rest — which is what made switching library a re-download.
                // Rows with no library recorded are left alone: they predate
                // this and no fetch can speak for them.
                //
                // Nor a hidden library's rows, which were never asked about:
                // they are kept, so showing it again needs no fetch to appear.
                val inScope = if (musicFolderId == null) {
                    cached.filterValues { it.libraryId == null || it.libraryId !in hidden }
                } else {
                    cached.filterValues { it.libraryId == musicFolderId }
                }
                val staleAlbumIds = inScope.keys - serverAlbumById.keys
                if (staleAlbumIds.isNotEmpty()) {
                    staleAlbumIds.forEach { dao.deleteTracksForAlbum(it) }
                    dao.deleteAlbums(staleAlbumIds.toList())
                    wrote.tracks = true
                }
                // And the tracks that belong to no album here at all. The prune
                // above only reaches albums this library knows, so a song
                // written in by something else — a sync for another source that
                // was still finishing when the source changed under it — is
                // never considered, and never leaves. It shows up in Artists and
                // Songs, mixed in with this library's own, while Albums looks
                // perfectly clean because its album row was never written.
                //
                // Guarded by the same empty-result rule as the album prune: this
                // only runs where the server did answer with albums.
                val orphans = dao.orphanedTrackCount()
                if (orphans > 0) {
                    android.util.Log.w("AmpSync", "dropping $orphans track(s) belonging to no album here")
                    dao.deleteOrphanedTracks()
                    wrote.tracks = true
                }
            }

            // Only new or changed albums need their songs re-fetched — but "changed"
            // has to include "its songs aren't actually here". Albums are written
            // before their songs, so a sync interrupted in between leaves the album
            // rows looking complete while the tracks are missing; comparing only
            // against the album cache then skips them forever and the library never
            // recovers. Checking the real per-album track count makes it self-heal.
            val cachedTrackCounts = dao.trackCountsByAlbum().associate { it.albumId to it.tracks }
            // Why each album was chosen, counted: a sync that walks the whole
            // library is indistinguishable in the server's log from one that
            // legitimately has everything to fetch, and telling those apart is
            // what found the bug below.
            val reasons = mutableMapOf<String, Int>()
            var unchanged = 0
            val toFetch = if (refillForParser) serverAlbums else serverAlbums.filter { album ->
                val prev = cached[album.id]
                val cachedCount = cachedTrackCounts[album.id] ?: 0
                val why = when {
                    prev == null -> "new album"
                    cachedCount == 0 -> "no songs cached"
                    // A count the server didn't send is not a count of zero, and
                    // comparing against it as though it were is a test nothing
                    // can pass. Plex leaves leafCount off the album listing, so
                    // every one of its albums reported 0 against a cache holding
                    // the real number, every album looked changed, and the whole
                    // library — 708 of them — was re-fetched on every sync, every
                    // half hour, for ever.
                    //
                    // Where there is no count, ask the other question the server
                    // does answer: when did it last touch this album. Plex moves
                    // updatedAt whenever the album's contents change, so a track
                    // added to a record already in the library is seen, without
                    // asking about the 707 that didn't move. A server that sends
                    // neither leaves both sides at 0, which compares equal and
                    // keeps the cache — the same and only safe answer.
                    //
                    // Rows written before this column existed hold 0, so the
                    // first sync after the upgrade disagrees with every album
                    // and walks the library once. That is the intended cost: it
                    // is also the catch-up for anything missed while the count
                    // was the only test.
                    album.songCount == 0 ->
                        if (album.updatedMs != prev.updatedMs) {
                            "album changed on the server"
                        } else {
                            unchanged++
                            null
                        }
                    prev.songCount != album.songCount -> "server's count changed"
                    cachedCount != album.songCount -> "cache short of the count"
                    else -> null
                }
                if (why != null) reasons[why] = (reasons[why] ?: 0) + 1
                why != null
            }
            // And the albums whose songs changed without their count moving —
            // a re-tag, or tags a server finished reading after this album was
            // first synced — as far as the server can say. See songChangesSince.
            val songCheck = source?.let { settings.songCheck(it.id) }
            // Asked on a refill too, which reads every album anyway: it is
            // what hands over the first mark, so the next sync needn't.
            val changes = if (songCheck == null) {
                null
            } else {
                runCatching { client.songChangesSince(songCheck.mark, folderIds) }
                    .noteOom("sync: song changes")
                    .onFailure { android.util.Log.w("AmpSync", "song changes: couldn't ask: $it") }
                    .getOrNull()
            }
            val reread = songCheck?.let {
                rereadFor(
                    changes, it, serverAlbums,
                    whole = musicFolderId == null, bulk = bulkAllowed(), nowMs = System.currentTimeMillis(),
                )
            }
            val already = toFetch.mapTo(HashSet()) { it.id }
            val more = reread?.albums.orEmpty().filter { already.add(it.id) }
            if (more.isNotEmpty()) reasons[reread!!.why] = more.size
            val albumsToFetch = toFetch + more

            android.util.Log.i(
                "AmpSync",
                if (refillForParser) {
                    "parser refill: fetching songs for all ${serverAlbums.size} albums"
                } else {
                    "fetching songs for ${albumsToFetch.size} of ${serverAlbums.size} albums $reasons" +
                        (if (unchanged > 0) "; $unchanged uncounted and untouched, cache kept" else "") +
                        "; song changes: ${changes?.let { it::class.simpleName } ?: "not asked"}"
                },
            )

            // Counted in albums, because that is what it is walking — one
            // request per album for its songs. Labelled "Songs" it read as a
            // song count, and a library with more songs than albums then looks
            // like it is being cut short.
            _syncState.value = _syncState.value.copy(phase = "Albums 0/${albumsToFetch.size}")
            val likedSongs = starred?.songIds ?: dao.likedTrackIds().toHashSet()
            val unread = fetchAndStoreSongs(dao, client, albumsToFetch, likedSongs, libraryOf, wrote)
            // Settled only once every album it covers is read: one that failed
            // keeps the old mark, so it is asked about again next time.
            if (source != null && reread != null && unread == 0 && reread.next != songCheck) {
                settings.setSongCheck(source.id, reread.next)
            }

            // Reconcile track likes across the whole library — writing only
            // the ones that moved, see LibraryDao.replaceTrackLikes.
            if (starred != null) {
                val moved = dao.replaceTrackLikes(starred.songIds.toList())
                if (moved > 0) wrote.tracks = true
                if (dao.replaceLikedArtists(starred.artistNames) > 0) wrote.likedArtists = true
            }

            // Only now: a run that threw part-way leaves the old number in place
            // so the refill is attempted again rather than being marked done.
            if (refillForParser && source != null) {
                settings.setParserGeneration(source.id, TRACK_PARSER_GENERATION)
            }

            // An album whose art changed has a new cover id, and its songs —
            // not re-fetched for that — still carry the old one. Pointed at
            // the album's here, as every launch already does, so a record
            // has one sleeve rather than one per era, and so the old one is
            // seen to have been let go of just below.
            runCatching { dao.collapseTrackCovers() }.noteOom("sync: cover collapse")
                .onSuccess { if (it > 0) wrote.tracks = true }
            reportDroppedCovers(dao, source?.id, coversBefore)
            if (wrote.tracks || wrote.albums || wrote.likedArtists) rereadLists(tracks = wrote.tracks)
            // The writing is over, so the lists read freely again — after the
            // re-read above is asked for, so a list this wakes already sees it
            // is superseded and leaves the reading to it: see pacedWhileSyncing.
            syncingDao.compareAndSet(dao, null)

            val finishedAt = System.currentTimeMillis()
            _syncState.value = SyncState(
                syncing = false,
                lastSyncedMs = finishedAt,
            )
            // The source this ran for, not whichever is active by now: a switch
            // made while it ran would otherwise be told it had just synced.
            runCatching { source?.id?.let { settings.setLastSyncedMs(it, finishedAt) } }
            learnServerFeatures(dao, client, source)
            onSyncSucceeded?.invoke()
        } catch (e: CancellationException) {
            // A library switch cancelled this sync; don't record it as an error.
            _syncState.value = _syncState.value.copy(syncing = false)
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AmpSync", "sync failed: ${e::class.simpleName}: ${e.message}", e)
            // The address may simply have moved — see repointPlex. Only once,
            // so a genuinely dead server can't loop.
            if (allowRepoint && repointPlex()) {
                runSync(musicFolderId, allowRepoint = false)
                return
            }
            _syncState.value = _syncState.value.copy(
                syncing = false,
                error = syncErrorMessage(e),
            )
            onSyncFailed?.invoke(isAuthFailure(e))
        } finally {
            syncingDao.compareAndSet(dao, null)
        }
    }

    /**
     * Rebuild the library from the files on this phone.
     *
     * Wholesale rather than incremental: the filesystem *is* the index, a scan of
     * a few thousand files takes seconds, and anything clever would only be a
     * cache of something already local. Likes, ratings and play counts have
     * nowhere to live on a local source, so there is nothing to reconcile.
     */
    /**
     * Ask the server what it implements, once a sync has just proved it answers.
     *
     * Here rather than at Save for two reasons: the probes that need a real song
     * need a library first, and a server that has just served a whole sync is
     * known to be reachable and logged in — so an endpoint it calls missing is
     * missing, not a proxy or a dead link talking. Anything thrown leaves what
     * was known alone. See [ServerFeatures].
     */
    private suspend fun learnServerFeatures(
        dao: LibraryDao,
        client: MusicServer?,
        source: MusicSource?,
    ) {
        if (client == null || source == null) return
        runCatching {
            val learned = client.probeFeatures(source.features, dao.anyTrackId())
            if (learned == null || learned == source.features) return@runCatching
            // Re-read the source: a sync is long, and what it was started with
            // may have been edited since — only the features are ours to write.
            val current = settings.sources.first().firstOrNull { it.id == source.id }
                ?: return@runCatching
            settings.saveSource(current.copy(features = learned))
        }.noteOom("sync: server features")
    }

    private suspend fun runLocalScan(dao: LibraryDao, sourceId: String, phone: PhoneLibrary) {
        _syncState.value = _syncState.value.copy(
            syncing = true,
            error = null,
            phase = "Reading this phone",
        )
        try {
            val access = phone.access()
            if (access != PhoneAccess.GRANTED) {
                _syncState.value = SyncState(
                    syncing = false,
                    // Blocked is not "not yet allowed": the prompt the other
                    // message leads to can't change a LightOS policy, and a
                    // row that sends you round that loop is worse than one
                    // that says where the wall is.
                    error = when (access) {
                        PhoneAccess.BLOCKED_BY_LIGHTOS -> "LightOS is blocking this tool's music access"
                        else -> "Allow music access to read this phone"
                    },
                )
                return
            }
            val scan = phone.scan { count ->
                _syncState.value = _syncState.value.copy(phase = "$count tracks")
            }
            val coversBefore = coverIdsOrNull(dao)
            dao.clearTracks()
            dao.clearAlbums()
            dao.upsertAlbums(scan.albums.map { it.toEntity() })
            dao.upsertTracks(scan.tracks.map { it.toEntity() })
            reportDroppedCovers(dao, sourceId, coversBefore)
            rereadLists(tracks = true)
            val finishedAt = System.currentTimeMillis()
            _syncState.value = SyncState(
                syncing = false,
                lastSyncedMs = finishedAt,
            )
            runCatching { settings.setLastSyncedMs(sourceId, finishedAt) }
            onSyncSucceeded?.invoke()
        } catch (e: CancellationException) {
            _syncState.value = _syncState.value.copy(syncing = false)
            throw e
        } catch (e: Exception) {
            _syncState.value = _syncState.value.copy(
                syncing = false,
                error = e.message ?: "Couldn't read the music folder",
            )
        }
    }

    /**
     * Set by [com.sublunar.amp.App] to learn whether the server is reachable and
     * to top up automatic downloads once fresh library data has landed. Callbacks
     * rather than a dependency so the repository doesn't have to know about either.
     */
    var onSyncSucceeded: (() -> Unit)? = null

    /**
     * Told which covers a finished sync left nothing pointing at, so their
     * files can go — see ArtworkLoader.drop. The source is named because the
     * run was pinned to it, and it may no longer be the one on screen.
     */
    var onCoversDropped: (suspend (sourceId: String, coverArtIds: Set<String>) -> Unit)? = null

    private suspend fun coverIdsOrNull(dao: LibraryDao): Set<String>? =
        runCatching { dao.allCoverArtIds().toSet() }.noteOom("sync: cover ids").getOrNull()

    /** See [reread]; the tracks too when [tracks], the run having written to them. */
    private fun rereadLists(tracks: Boolean) {
        android.util.Log.i("AmpLists", "sync done; reading the lists again" + if (tracks) "" else ", songs unchanged")
        reread.update { it + 1 }
        if (tracks) rereadTracks.update { it + 1 }
    }

    /** Which tables a sync wrote to — see [rereadLists]. */
    private class SyncWrites {
        var tracks = false
        var albums = false
        var likedArtists = false
    }

    /**
     * Name the covers that were in this database before the run and aren't now.
     *
     * An album removed from the server takes its cover id with it; an album
     * whose art changed gets a new one, on Navidrome and Plex both, since
     * theirs carry the date the art was last touched. Either way the old
     * file is a picture of nothing, and the artwork cache can't find it on
     * its own — its files are named by a hash, so only the id names one.
     *
     * Asked of the database rather than worked out from the prune, so it
     * covers every way a run changes what is pointed at — the album prune,
     * the orphaned tracks, an upsert that changed an id — without having to
     * be told about each. Only on success: a run that threw may have pruned
     * and not yet written, and the covers of a half-finished sync are not
     * orphans. What that misses, the cache ages out on its own (see
     * ArtworkLoader.expireUnclaimed).
     */
    private suspend fun reportDroppedCovers(dao: LibraryDao, sourceId: String?, before: Set<String>?) {
        if (sourceId == null || before.isNullOrEmpty()) return
        val after = coverIdsOrNull(dao) ?: return
        val dropped = before - after
        if (dropped.isEmpty()) return
        runCatching { onCoversDropped?.invoke(sourceId, dropped) }
            .noteOom("sync: dropped covers")
            .onFailure { if (it is CancellationException) throw it }
    }
    /**
     * Called when a sync gives up. The flag is true when the server answered and
     * refused us — see [isAuthFailure]; the caller must not read that as the
     * server being unreachable.
     */
    var onSyncFailed: ((authFailure: Boolean) -> Unit)? = null

    private suspend fun fetchAndStoreSongs(
        /** The sync's own database — never the property, which follows the active source. */
        dao: LibraryDao,
        client: MusicServer,
        albums: List<Album>,
        starredSongIds: Set<String>,
        /** Which library each album came from — see the count write-back below. */
        libraryOf: Map<String, String?>,
        wrote: SyncWrites,
    ): Int = coroutineScope {
        val gate = Semaphore(SYNC_CONCURRENCY)
        val done = AtomicInteger(0)
        var unread = 0
        // Fetch concurrently, but write in batches: one transaction per album meant
        // ~1300 invalidations of the tracks table across a full sync, each one
        // re-running every observer's whole-table query while rows moved underneath
        // it. Network stays parallel; the database sees a handful of swaps.
        albums.chunked(SYNC_WRITE_BATCH).forEach { batch ->
            val fetched = batch.map { album ->
                async {
                    gate.withPermit {
                        yieldToPlayback()
                        // Null when the server didn't answer — which is not an
                        // album with no songs. Read as one, a passing failure
                        // deleted the album's songs until a later sync noticed.
                        val songs = try {
                            client.getAlbumTracks(album.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }?.map { it.copy(liked = it.id in starredSongIds) }
                        val n = done.incrementAndGet()
                        if (n % 10 == 0 || n == albums.size) {
                            _syncState.value =
                                _syncState.value.copy(phase = "Albums $n/${albums.size}")
                        }
                        songs
                    }
                }
            }.awaitAll()
            // A switch away cancels this sync; a batch already fetched must
            // not be written on the way out.
            ensureActive()
            val read = batch.indices.filter { fetched[it] != null }
            unread += batch.size - read.size
            // Only the albums whose songs aren't what is cached already — see
            // songsChanged. One rewritten unchanged is deleted and inserted
            // again, which moves its rows in the table and so changes the
            // library list as surely as a real edit: a whole re-read of it, and
            // everything worked out from it, for nothing.
            val cachedRows = dao.tracksForAlbums(read.map { batch[it].id }).groupBy { it.albumId }
            val changed = read.mapNotNull { i ->
                val rows = fetched[i].orEmpty().map { it.toEntity() }
                (batch[i].id to rows).takeIf { songsChanged(cachedRows[batch[i].id].orEmpty(), rows) }
            }
            if (changed.isNotEmpty()) {
                dao.replaceAlbumTracks(changed.map { it.first }, changed.flatMap { it.second })
                wrote.tracks = true
            }
            // Write back the count we actually got for any album whose server
            // didn't provide one, so the next sync has something to compare
            // against and doesn't re-fetch the whole library every time.
            val counted = batch.mapIndexedNotNull { index, album ->
                val songs = fetched.getOrNull(index)?.size ?: 0
                album.copy(songCount = songs).takeIf { album.songCount == 0 && songs > 0 }
            }
            // With its library tag: an upsert replaces the whole row, and a
            // row written back without its tag is an album that then shows
            // in every library — see allAlbums.
            if (counted.isNotEmpty()) {
                dao.upsertAlbums(counted.map { it.toEntity(libraryOf[it.id]) })
                wrote.albums = true
            }
        }
        unread
    }

    fun syncInBackground(force: Boolean = false) {
        // Claimed before the launch, for the reason spelled out in
        // scanAndSyncInBackground: runSync only says "syncing" once it holds
        // the mutex and has awaited the active source, so a second call
        // arriving before that isn't turned away — it queues a whole second
        // sync behind the first on the mutex.
        //
        // Which matters because the caller is a LaunchedEffect on the boot
        // screen, and every re-entry into that composition asks again. Six
        // minutes of ordinary navigating fetched the album list seven times
        // over a metered link, measured 2026-09-03: ~400 KB a page, for one
        // library that had not changed between the first fetch and the last.
        if (_syncState.value.syncing) return
        // And a floor between runs. The claim above stops them overlapping; it
        // does nothing about them repeating, and they did: four full syncs in
        // seventy seconds, measured 2026-09-03, each fetching the same 709
        // albums and then reporting "0 of 709 albums; cache kept". A sync that
        // finds nothing is also what re-runs the download top-up, so the
        // repetition was not free even once the bytes were back on Wi-Fi.
        //
        // Sync Now is deliberately exempt — see scanAndSyncInBackground. Asking
        // by hand is asking to go now, whatever this would have said.
        // "in 0 until", not "<": a time in the future — a sync recorded while the
        // clock was wrong — is not a recent sync, and would otherwise switch
        // automatic syncing off until the clock caught up.
        //
        // [force] skips the floors, for a library just shown again: until it
        // is fetched it may have nothing to show at all.
        if (!force && System.currentTimeMillis() - _syncState.value.lastSyncedMs in 0 until SYNC_MIN_INTERVAL_MS) return
        _syncState.value = _syncState.value.copy(syncing = true, error = null, phase = "Connecting")
        syncJob = scope.launch {
          try {
            // The floor above only knows this process. A cold start has no
            // memory of the sync that finished a minute before it, so the
            // stored time is asked too — and on a metered link the floor is
            // hours, not minutes: an automatic sync is hundreds of requests
            // spent on a library that rarely changed, in the minutes someone
            // opened the app to play something. Sync Now ignores all of this.
            val last = runCatching {
                settings.activeSource.first()?.id?.let { settings.lastSyncedMs(it) }
            }.getOrNull() ?: 0L
            val floor = if (bulkAllowed()) SYNC_MIN_INTERVAL_MS else SYNC_MIN_INTERVAL_METERED_MS
            if (!force && System.currentTimeMillis() - last in 0 until floor) {
                android.util.Log.i("AmpSync", "launch sync skipped: last one ${(System.currentTimeMillis() - last) / 60_000} min ago")
                _syncState.value = _syncState.value.copy(syncing = false, phase = "", lastSyncedMs = last)
                return@launch
            }
            sync()
          } catch (e: Throwable) {
            // The claim above was made before this coroutine ran. runSync
            // releases it on its own paths — but a cancellation (a source
            // switch) or a failed settings read can end this before runSync
            // ever starts, and a claim left standing turns every later sync
            // away, Sync Now included, until the app is restarted.
            _syncState.value = _syncState.value.copy(syncing = false)
            throw e
          }
        }
    }

    /**
     * Ask the server to look for new files, then sync what it finds.
     *
     * A sync pulls what the server already knows, which is why a record dropped
     * into the music folder stays invisible however many times you tap it — the
     * server hasn't looked. This asks it to, waits a little while for the answer,
     * and then syncs.
     *
     * The wait is bounded because a scan is the server's business and can take
     * as long as it likes on a large library. Giving up and syncing anyway is the
     * right end to it: whatever the scan has found by then arrives now, and the
     * rest at the next sync. Only the explicit Sync Now goes through here —
     * background syncs have no business making a server walk its disks.
     */
    /**
     * Set when the server would not be told to scan — see
     * [scanAndSyncInBackground]. Read by the Sync Now row so a refusal is
     * visible rather than being mistaken for a library with nothing new in it.
     */
    @Volatile
    var scanRefused: Boolean = false
        private set

    fun scanAndSyncInBackground() {
        // Claimed before anything is awaited. Asking the server to scan is a
        // network call, and until it came back the state still read "not
        // syncing" — so the row stayed live and an second tap started a whole
        // second scan-and-sync behind the first. They queue on the sync mutex
        // and run one after another, which is what "fetching albums several
        // times over" is.
        if (_syncState.value.syncing) return
        _syncState.value = _syncState.value.copy(syncing = true, error = null, phase = "Scanning server")
        val previous = syncJob
        syncJob = scope.launch {
            previous?.cancelAndJoin()
            val client = serverClient.value
            val folder = libraryId.first()
            // A server already known to have no scan call isn't asked at all —
            // see MusicSource.supportsServerScan. Refreshing still fetches the
            // library, which for such a server is the whole of what a refresh
            // ever was.
            val canScan = settings.activeSource.first()?.supportsServerScan ?: true
            val outcome = if (client != null && canScan) {
                runCatching { client.startServerScan(folder) }
                    .getOrDefault(ScanRequest.REFUSED)
            } else {
                ScanRequest.ABSENT
            }
            val asked = outcome == ScanRequest.STARTED
            // Said plainly rather than left to look like an empty scan. On Plex
            // this is what a library someone shared with you does — scanning
            // belongs to whoever owns the server. A server with no such call
            // has refused nothing, so it says nothing.
            scanRefused = outcome == ScanRequest.REFUSED
            if (asked) {
                var waited = 0L
                while (waited < SCAN_WAIT_MS) {
                    delay(SCAN_POLL_MS)
                    waited += SCAN_POLL_MS
                    val busy = runCatching { client?.serverScanning(folder) }.getOrDefault(false) == true
                    if (!busy) break
                }
            }
            sync()
        }
    }

    // --- Reads ---------------------------------------------------------------

    /** An album's tracks, from cache when present, otherwise fetched and cached. */
    suspend fun getAlbumTracks(albumId: String): List<Track> {
        val cached = dao.tracksForAlbum(albumId).map { it.toTrack() }
        if (cached.isNotEmpty()) return narrowedToDownloads(cached).sortedWith(TRACK_ORDER)
        val client = serverClient.value ?: return emptyList()
        val fresh = client.getAlbumTracks(albumId)
        dao.upsertTracks(fresh.map { it.toEntity() })
        return narrowedToDownloads(fresh).sortedWith(TRACK_ORDER)
    }

    /**
     * An album's songs read again from its server, and stored — what opening
     * its page does, so a record someone is looking at is never older than the
     * server, whatever a sync has or hasn't noticed. Null where the server
     * can't be asked or didn't answer, and the page keeps what it had.
     *
     * Likes stay as this phone has them: one made offline may not have reached
     * the server yet, and a sync reconciles the rest.
     */
    suspend fun refreshAlbumTracks(albumId: String): List<Track>? {
        if (!metadataAllowed() || offline.value) return null
        // Both ends pinned, and the write dropped if the source changed while
        // the server was answering — see runSync.
        val dao = daos.value ?: return null
        val client = serverClient.value ?: return null
        val fresh = runCatching { client.getAlbumTracks(albumId) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val cached = dao.tracksForAlbum(albumId)
        val liked = cached.associate { it.id to it.liked }
        val merged = fresh.map { it.copy(liked = liked[it.id] ?: it.liked) }
        if (daos.value !== dao) return null
        // Written only if something moved — see fetchAndStoreSongs: rewritten
        // as it was, the album still changed the library list, and opening any
        // record online cost a whole re-read of the library.
        val rows = merged.map { it.toEntity() }
        if (songsChanged(cached, rows)) {
            dao.replaceAlbumTracks(listOf(albumId), rows)
        }
        return narrowedToDownloads(merged).sortedWith(TRACK_ORDER)
    }

    /**
     * What of these will actually play, offline; all of them otherwise.
     *
     * The lists narrow to the downloads when there is no server — see [tracks] —
     * and a record's own page has to say the same thing. Showing the whole track
     * listing there offered eleven songs and played four, and which four was
     * only discoverable by tapping them.
     */
    private fun narrowedToDownloads(tracks: List<Track>): List<Track> {
        if (!offline.value) return tracks
        val downloaded = downloadedTrackIds.value
        return tracks.filter { it.id in downloaded }
    }

    /**
     * A queue of whole albums in random order — each album's tracks in their own
     * order, one album after another.
     *
     * Shuffling *albums* rather than tracks is a different thing from Shuffle on
     * a song list: a record is meant to be heard in sequence, and this keeps that
     * while making which record you get a surprise. Capped at [limit] albums
     * because a full library would be tens of thousands of items in the player's
     * queue for no benefit — that's days of listening either way.
     */
    suspend fun shuffledAlbumQueue(limit: Int = SHUFFLE_ALBUM_LIMIT): List<Track> =
        albumQueue(albums.value.shuffled().map { it.id }, limit)

    /**
     * The same, in the order given — what Play on the albums list means: the
     * records as they are on screen, each one in its own running order.
     */
    suspend fun albumQueue(albumIds: List<String>, limit: Int = SHUFFLE_ALBUM_LIMIT): List<Track> =
        albumIds.take(limit).flatMap { id ->
            dao.tracksForAlbum(id).map { it.toTrack() }.sortedWith(TRACK_ORDER)
        }

    /** Tracks for the given ids, preserving id order (used by playlists/queue restore). */
    suspend fun getTracksByIds(ids: List<String>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(MAX_BOUND_IDS).flatMap { dao.tracksByIds(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** All tracks for an artist, from cache, ordered by album then track. */
    fun tracksForArtist(name: String): List<Track> =
        tracks.value.filter { name in it.albumArtistNames() }
            .sortedWith(compareBy({ it.album.lowercase() }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))

    /**
     * An artist's most popular songs, in popularity order.
     *
     * Navidrome answers `getTopSongs` from its Last.fm agent and matches the
     * results back onto the library, so this is a short top-tracks list rather
     * than the full discography — shuffling it stays within those songs.
     *
     * Kept in the source's database once fetched, so an artist's page opens
     * with the row already there — and still has it offline, narrowed to what
     * is downloaded like every other list. Popularity drifts over weeks, not
     * minutes, so a stored list is served as it stands and refreshed in the
     * background once it is older than [TOP_SONGS_TTL_MS]. Only ids are
     * stored: they are matched back onto the library's own rows, so likes and
     * play counts agree with the rest of the UI, and a song the library
     * doesn't hold is left out rather than written in.
     *
     * An empty answer is never stored. It means either "this server ranks
     * nothing" or "the server couldn't be reached just then", and the two are
     * indistinguishable here — keeping it would turn a moment's failure into a
     * row that stays missing.
     */
    suspend fun topSongsForArtist(name: String): List<Track> {
        topSongs[name]?.let { return it }
        val stored = runCatching { dao.topSongs(name) }.getOrDefault(emptyList())
        if (stored.isNotEmpty()) {
            val byId = tracks.value.associateBy { it.id }
            val held = stored.mapNotNull { byId[it.trackId] }
            val stale = System.currentTimeMillis() - stored.first().fetchedAtMs > TOP_SONGS_TTL_MS
            if (stale && !offline.value && serverClient.value != null) {
                scope.launch { refreshTopSongs(name) }
            }
            if (held.isNotEmpty()) {
                topSongs[name] = held
                return held
            }
            // Stored, but none of it is here to play — offline with nothing of
            // theirs downloaded. Online, ask again rather than show nothing.
            if (offline.value) return emptyList()
        }
        return refreshTopSongs(name)
    }

    /** Ask the server, store what it says, answer with the library's own rows. */
    private suspend fun refreshTopSongs(name: String): List<Track> {
        val client = serverClient.value ?: return emptyList()
        val answered = runCatching { client.getTopSongs(name) }.getOrDefault(emptyList())
        if (answered.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        runCatching {
            dao.replaceTopSongs(name, answered.mapIndexed { i, t -> TopSongEntity(name, i, t.id, now) })
        }
        val byId = tracks.value.associateBy { it.id }
        // Fresh from the server, a song the library doesn't hold is still shown
        // this once — it plays through the server like any other.
        val result = answered.map { byId[it.id] ?: it }
        topSongs[name] = result
        return result
    }

    /**
     * A radio seeded by [seed]: the song itself, then what the server thinks
     * follows from it.
     *
     * The seed leads because it is the song that was chosen — a radio that opens
     * on something else has already wandered off. Server rows are swapped for
     * cached ones so likes, play counts and downloads agree with the rest of the
     * UI, and the seed is dropped from the server's answer so it can't play
     * twice. Empty when the server has nothing: the caller says so, rather than
     * shuffling the library and calling it radio.
     */
    suspend fun radioFrom(seed: Track): List<Track> {
        val client = serverClient.value ?: return emptyList()
        val answered = client.getSimilarSongs(seed.id, RADIO_LENGTH)
        // Matched once the server has answered, and only the songs it named:
        // a map of the whole library made before the call held that library
        // for as long as the server took, past any read of it in between.
        val named = answered.mapTo(HashSet()) { it.id }
        val byId = tracks.value.filter { it.id in named }.associateBy { it.id }
        val similar = answered
            .filter { it.id != seed.id }
            .distinctBy { it.id }
            .map { byId[it.id] ?: it }
        android.util.Log.i("AmpRadio", "radio from ${seed.id}: server ${answered.size}, usable ${similar.size}")
        return if (similar.isEmpty()) emptyList() else listOf(seed) + similar
    }

    /** An artist's albums as a discography: oldest release first, undated last. */
    fun albumsForArtist(name: String): List<Album> {
        val albumIds = tracks.value
            .filter { name in it.albumArtistNames() }
            .mapNotNull { it.albumId }
            .toSet()
        return albums.value.filter { it.id in albumIds }.sortedWith(RELEASE_ORDER)
    }

    // --- Likes ---------------------------------------------------------------

    /**
     * The server, when it may be spoken to *and* the mode allows the bytes.
     *
     * Null in both cases, so that every caller which already queues its action
     * when there is no server queues it when there is no allowance either. A
     * like or a rating is the user's, durable, and worth keeping — the same
     * reasoning as a play, which PlaybackController queues for the same reason.
     * What must not happen is sending it now: Wi-Fi Only meant Wi-Fi only, and
     * these were the last things still going out over cellular regardless.
     */
    private fun reachableClient(): MusicServer? =
        if (metadataAllowed()) serverClient.value else null

    // One like or rating at a time, in the order they were tapped: launched
    // side by side, two taps on the same heart could reach the server the
    // wrong way round and leave it holding the first.
    private val likeWrites = Mutex()

    /** How many times each thing has been liked or rated — see [mirror]. */
    private val taps = ConcurrentHashMap<String, Int>()

    /**
     * The server's half of a like or a rating the phone already shows.
     *
     * Saved: anything older still queued for the same thing is dropped, since
     * sent later it would undo this. Unreachable: queued, to be sent when the
     * server is back. Refused: the server will never hold it, so the phone's
     * half is taken back with [undo] — the next sync would do the same, minutes
     * later and without a word — and said, when [say]. Only while no later tap
     * on the same thing has replaced this one: taking back a refusal must not
     * take back the tap after it.
     *
     * [client] is the one that was there at the tap, like the dao [undo] writes
     * to: a source switched away from meanwhile still gets its own answer.
     */
    private suspend fun mirror(
        action: PendingAction,
        client: MusicServer?,
        say: Boolean,
        send: suspend (MusicServer) -> Unit,
        undo: suspend () -> Unit,
    ): WriteResult {
        val key = "${action.sourceId}:${action.kind.group()}:${action.id}"
        val tap = taps.merge(key, 1, Int::plus)!!
        return likeWrites.withLock {
            if (client == null) return@withLock WriteResult.Unreachable(UNREACHABLE).also { pending.add(action) }
            val result = attemptWrite { send(client) }
            if (result != WriteResult.Saved) android.util.Log.w("AmpSync", "${action.kind} ${action.id} → $result")
            when (result) {
                WriteResult.Saved -> pending.forget(action)
                is WriteResult.Unreachable -> pending.add(action)
                is WriteResult.Refused -> if (taps[key] == tap) {
                    undo()
                    if (say) tell("Couldn't save\n${result.reason}")
                }
            }
            result
        }
    }

    /**
     * Like or unlike a song.
     *
     * The local row is the truth the moment it's tapped, and stays that way even
     * if the server can't be told: undoing the user's action because the train
     * went into a tunnel is worse than telling Navidrome about it later. A
     * server that answers no is a different thing from one that can't be
     * reached, and that like is taken back and said — see [mirror].
     */
    suspend fun setTrackLiked(track: Track, liked: Boolean) {
        val pinned = dao
        val client = reachableClient()
        pinned.setTrackLiked(track.id, liked)
        wroteHere()
        val source = settings.activeSource.first()?.id
        val kind = if (liked) PendingAction.Kind.STAR_SONG else PendingAction.Kind.UNSTAR_SONG
        mirror(
            PendingAction(kind, track.id, sourceId = source), client, say = true,
            send = { if (liked) it.starSong(track.id) else it.unstarSong(track.id) },
            undo = {
                pinned.setTrackLiked(track.id, !liked)
                wroteHere()
            },
        )
    }

    /**
     * Like an artist. Stored locally by name (our Artist rows are derived from
     * track tags), and mirrored to the server when that name resolves to a real
     * server-side artist so other clients see the same favourites. A name the
     * server has no artist for — a featured guest, a tag-only spelling — is
     * refused: it could never be told, and the next sync would drop it unseen.
     */
    suspend fun setArtistLiked(name: String, liked: Boolean) {
        val pinned = dao
        val client = reachableClient()
        if (liked) pinned.likeArtists(listOf(LikedArtistEntity(name))) else pinned.unlikeArtist(name)
        val source = settings.activeSource.first()?.id
        val kind = if (liked) PendingAction.Kind.STAR_ARTIST else PendingAction.Kind.UNSTAR_ARTIST
        mirror(
            PendingAction(kind, name, sourceId = source), client, say = true,
            send = {
                val id = artistIdFor(name, it) ?: throw RefusedWrite(NO_SUCH_ARTIST)
                if (liked) it.starArtist(id) else it.unstarArtist(id)
            },
            undo = { if (liked) pinned.unlikeArtist(name) else pinned.likeArtists(listOf(LikedArtistEntity(name))) },
        )
    }

    /**
     * Server-side id for a display name, from a lazily built index.
     *
     * A name the stored index lacks has it read again, once: the artist may be
     * newer than the index. A failed read throws, so it counts as a server that
     * couldn't be reached rather than as one without the artist. The index is
     * the active source's — a write still finishing for a source switched away
     * from asks its own server and leaves it alone.
     */
    private suspend fun artistIdFor(name: String, client: MusicServer): String? {
        val current = client === serverClient.value
        if (current) artistIds?.get(name)?.let { return it }
        val fresh = client.getArtistIndex().associate { it.name to it.id }
        if (current && fresh.isNotEmpty()) artistIds = fresh
        return fresh[name]
    }

    suspend fun setAlbumLiked(album: Album, liked: Boolean) {
        val pinned = dao
        val client = reachableClient()
        pinned.setAlbumLiked(album.id, liked)
        wroteHere()
        val source = settings.activeSource.first()?.id
        val kind = if (liked) PendingAction.Kind.STAR_ALBUM else PendingAction.Kind.UNSTAR_ALBUM
        mirror(
            PendingAction(kind, album.id, sourceId = source), client, say = true,
            send = { if (liked) it.starAlbum(album.id) else it.unstarAlbum(album.id) },
            undo = {
                pinned.setAlbumLiked(album.id, !liked)
                wroteHere()
            },
        )
    }

    // --- Search --------------------------------------------------------------

    /**
     * Folded search text for every track, rebuilt only when the library changes.
     *
     * Matching has to compare folded-to-folded, or typing "olafur" can never reach
     * "Ólafur Arnalds". Folding the query is free; folding the haystack is not, so
     * doing it per keystroke across ten thousand tracks would be visibly slow —
     * hence the cache, keyed on the list instance the flow last emitted.
     *
     * Kept only while that list is the library. It holds the list, and with it
     * every song in it, so held past the next read it was a whole second
     * library in memory until the next search came along.
     */
    @Volatile private var haystack: Pair<List<Track>, List<String>>? = null

    init {
        scope.launch { tracks.collect { list -> if (haystack?.first !== list) haystack = null } }
    }

    private fun haystack(list: List<Track>): List<String> {
        haystack?.let { (forList, hay) -> if (forList === list) return hay }
        val hay = list.map { titleKey(it.title + "\n" + it.artist + "\n" + it.album) }
        haystack = list to hay
        // A newer list may have arrived while this one was folded, and been
        // told there was nothing to drop: then this is what drops it.
        if (tracks.value !== list) haystack = null
        return hay
    }

    fun search(query: String): SearchResults {
        if (query.isBlank()) return SearchResults()
        val q = titleKey(query)
        if (q.isBlank()) return SearchResults()
        val trackList = tracks.value
        val hay = haystack(trackList)
        val matchingTracks = trackList
            .filterIndexed { i, _ -> hay[i].contains(q) }
            .take(SEARCH_LIMIT)
        // Albums and artists are two orders of magnitude smaller than tracks, so
        // they fold in place rather than carrying a cache of their own.
        val matchingAlbums = albums.value.filter {
            titleKey(it.title).contains(q) || titleKey(it.artist).contains(q)
        }.take(SEARCH_LIMIT)
        val matchingArtists = artists.value.filter {
            titleKey(it.name).contains(q)
        }.take(SEARCH_LIMIT)
        return SearchResults(matchingArtists, matchingAlbums, matchingTracks)
    }

    /**
     * Rate a song or album, 0 to clear.
     *
     * Applied locally at once and queued when the server can't be reached, so
     * rating a record on a plane behaves like liking one does. One the server
     * refuses goes back to [previous], and the answer is the caller's to say —
     * see [mirror].
     */
    suspend fun setRating(id: String, stars: Int, isAlbum: Boolean, previous: Int): WriteResult {
        val pinned = dao
        val client = reachableClient()
        val store: suspend (Int) -> Unit = {
            if (isAlbum) pinned.setAlbumRating(id, it) else pinned.setTrackRating(id, it)
            wroteHere()
        }
        store(stars)
        val source = settings.activeSource.first()?.id
        val kind = if (isAlbum) PendingAction.Kind.RATE_ALBUM else PendingAction.Kind.RATE_SONG
        return mirror(
            PendingAction(kind, id, value = stars, sourceId = source), client, say = false,
            send = { it.setRating(id, stars) },
            undo = { store(previous) },
        )
    }

    /**
     * Note a play against the cached row.
     *
     * The server counts plays too, but only tells us at the next sync — and the
     * sort the user just chose is "Recently Played". Counting locally as well
     * means the list is right straight away; the sync overwrites it with the
     * server's own figures, which include plays from other clients.
     */
    suspend fun markPlayed(trackId: String) {
        dao.markPlayed(trackId, System.currentTimeMillis())
    }

    /**
     * Send everything that happened while the server couldn't be told.
     *
     * Through [reachableClient], so a queue built up in Wi-Fi Only is not
     * emptied over cellular the moment something calls this — which would send
     * exactly the requests the queue exists to have held back.
     *
     * Only this source's: the queue holds every source's, and a like sent to a
     * server that never had the song would be refused and lost. What the server
     * refuses is said once, however many it was — one line is enough to know
     * something didn't take, and the log has the rest.
     */
    suspend fun flushPending() {
        val client = reachableClient() ?: return
        val source = settings.activeSource.first()?.id ?: return
        // Behind any like still on its way, so neither overtakes the other.
        val refused = likeWrites.withLock {
            pending.flush(client, source) { name -> artistIdFor(name, client) }
        }
        refused.forEach { android.util.Log.w("AmpSync", "offline write refused: $it") }
        refused.firstOrNull()?.let { tell("Couldn't save something done offline\n$it") }
    }

    suspend fun clearCache() {
        dao.clearTracks()
        dao.clearAlbums()
        dao.clearAllTopSongs()
        forgetDerived()
    }

    /**
     * Throw away everything held in memory *about* the library, leaving the rows
     * alone.
     *
     * Switching source swaps the database wholesale, so none of this still
     * applies — and none of it is keyed by source, which is what makes it
     * dangerous rather than merely stale. [topSongs] is keyed by artist name, so
     * an artist looked up on one server answers for the same name on the other:
     * visit an artist on a server with no popular tracks and the empty answer
     * follows you to the server that has them.
     *
     * Separate from [clearCache] because that also deletes rows, and a source
     * switch must not delete the rows of the source being switched *to*.
     */
    fun forgetDerived() {
        // Playlists are the one list held in memory rather than in the source's
        // own database, so nothing swaps them out underneath the UI — without
        // this, the tab shows the previous server's playlists until the new
        // ones have been fetched over the network, which is long enough to read.
        _playlists.value = emptyList()
        _playlistTrackIds.value = emptyMap()
        topSongs.clear()
        artistIds = null
        _artistImages.value = emptyMap()
        haystack = null
        // Not lastTracksRead: the database hasn't changed over yet, and a write
        // the cancelled sync lands before it does is read against it. Dropped
        // here, that read was a whole library of new objects; trackRows drops
        // it the moment the lists change over.
        // Each holds a list of the source being left — see albumSizes.
        albumSizes = null
        artistSizes = null
        trackIndex = null
        // Stopped, these would still offer the last source's — see genres.
        tagValues.forEach { it.value = emptyList() }
    }

    private fun deriveArtists(
        list: List<Track>,
        liked: Set<String>,
        images: Map<String, String>,
    ): List<Artist> =
        list.flatMap { track -> track.albumArtistNames().map { it to track } }
            .groupBy({ it.first }, { it.second })
            .map { (name, ts) ->
                Artist(
                    name = name,
                    imageId = images[name],
                    albumCount = ts.mapNotNull { it.albumId }.distinct().size,
                    trackCount = ts.size,
                    playCount = ts.sumOf { it.playCount },
                    lastPlayedMs = ts.maxOfOrNull { it.lastPlayedMs } ?: 0L,
                    liked = name in liked,
                )
            }
            // Key once per artist, not once per comparison — see LibrarySort.
            .map { sortName(it.name) to it }
            .sortedBy { it.first }
            .map { it.second }

    /** Union of the per-folder starred results (one call per library when merging). */
    private fun List<Starred>.mergeStarred(): Starred =
        if (size == 1) first() else Starred(
            songIds = flatMapTo(mutableSetOf()) { it.songIds },
            albumIds = flatMapTo(mutableSetOf()) { it.albumIds },
            artistNames = flatMapTo(mutableSetOf()) { it.artistNames },
        )

    companion object {
        /** Albums whose tracks are written per transaction. */
        /** How long Sync Now will wait on a server scan before syncing anyway. */
        private const val SCAN_WAIT_MS = 30_000L
        private const val SCAN_POLL_MS = 1_500L

        /**
         * Bump when a change to how a track is *parsed* means the cached rows
         * are wrong — not when the server's own data changes, which the
         * ordinary incremental sync already catches.
         *
         * 1: album artist and composer were read from Subsonic field names that
         * do not exist. The base Child object has no `albumArtist` and no
         * `composer`; those are OpenSubsonic's `displayAlbumArtist` and
         * `displayComposer`. So every cached track had the track artist as its
         * album artist — which put every "feat." guest in the Artists list —
         * and no composer at all, which kept the Composers page hidden.
         *
         * 2: an album credited to several artists was stored as the one joined
         * string the server sent ("Johann Sebastian Bach; Glenn Gould"), which
         * made a compound artist that owned a single record. The credit now
         * comes from OpenSubsonic's structured `artists`, joined with the
         * separator Track.albumArtistNames splits on.
         */
        const val TRACK_PARSER_GENERATION = 2

        private const val SYNC_WRITE_BATCH = 40
        private const val SYNC_CONCURRENCY = 6
        private const val SEARCH_LIMIT = 100

        /** Rows per page of a library read: a page is one CursorWindow fill, and the new objects held at once. See readTracks. */
        private const val TRACK_PAGE = 1_000

        /** At most one read of the library this often while its own sync writes: one per forty albums ran the heap out. See pacedWhileSyncing. */
        private const val SYNC_REREAD_MS = 10_000L

        /** How long the tag lists go on after the last page showing them, so one page leading to the next doesn't start them over. */
        private const val TAG_VALUES_KEEP_MS = 5_000L


        /** Chronological by release date; albums with no date sort to the end. */
        val RELEASE_ORDER: Comparator<Album> = compareBy(
            { if (it.releaseDate == 0L) Long.MAX_VALUE else it.releaseDate },
            { it.title.lowercase() },
        )
        /** Albums per shuffle — a few hundred tracks, not the whole library. */
        private const val SHUFFLE_ALBUM_LIMIT = 25

        val TRACK_ORDER: Comparator<Track> =
            compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title.lowercase() })
    }
}

/** How many songs a radio asks the server for — Subsonic's own default. */
/**
 * How long an automatic sync waits after the last one finished.
 *
 * Only [LibraryRepository.syncInBackground] consults it — the periodic job and
 * Sync Now both mean "now".
 */
private const val SYNC_MIN_INTERVAL_MS = 5L * 60 * 1000

/** The same floor on a metered link, where an automatic sync costs the user data and their first minutes. */
private const val SYNC_MIN_INTERVAL_METERED_MS = 6L * 60 * 60 * 1000

private const val RADIO_LENGTH = 50

/** How long a stored popular-songs list is served before being refreshed. */
private const val TOP_SONGS_TTL_MS = 7L * 24 * 60 * 60 * 1000

/**
 * The next [size] of [ordered] after [after], and where the walk got to — null
 * once it has reached the end, so the next one starts from the beginning.
 */
internal fun nextWalk(ordered: List<String>, after: String?, size: Int): Pair<List<String>, String?> {
    val rest = if (after == null) ordered else ordered.filter { it > after }
    val batch = rest.take(size)
    return batch to if (rest.size > size) batch.last() else null
}

/** What a sync re-reads beyond new and re-counted albums, why, and the [SongCheck] to keep after. */
internal class Reread(val albums: List<Album>, val why: String, val next: SongCheck)

/**
 * Turns a server's [SongChanges] into albums to re-read.
 *
 * Only a sync of every library ([whole]) may settle a mark: one scoped to a
 * single library would mark as read the changes in the others. Re-reading
 * every album, or walking, is bulk — left for a link that allows it ([bulk]),
 * with the check kept where it was so a later sync does it instead.
 *
 * A walk that has gone round rests for [WALK_REST_MS] before the next begins.
 * A server that can't say anything at all — or one that scans on a schedule,
 * which reads as "something may have changed" every time — would otherwise
 * keep one going for ever.
 *
 * A server that names what changed but misses some of it asks for a walk as
 * well ([SongChanges.Albums.alsoWalk]). Its named albums are read on every
 * sync, as anyone's are, and its mark moves with them; the walk goes on beside
 * that, on its own cursor and rest, and settles no mark of its own — the two
 * share one [SongCheck] without either undoing the other.
 */
internal fun rereadFor(
    changes: SongChanges?,
    check: SongCheck,
    albums: List<Album>,
    whole: Boolean,
    bulk: Boolean,
    nowMs: Long,
): Reread? {
    val settled = { mark: String -> if (whole) SongCheck(mark = mark) else check }
    return when (changes) {
        null -> null
        is SongChanges.Nothing -> Reread(emptyList(), "", settled(changes.mark))
        is SongChanges.Albums -> {
            val named = albums.filter { it.id in changes.ids }
            if (!changes.alsoWalk) return Reread(named, "server says changed", settled(changes.mark))
            val marked = if (whole) check.copy(mark = changes.mark) else check
            if (!whole || !bulk) return Reread(named, "server says changed", marked)
            val (picked, cursor) = walkOn(check, albums, nowMs)
                ?: return Reread(named, "server says changed", marked)
            val next = if (cursor == null) {
                marked.copy(walkCursor = null, walkedMs = nowMs)
            } else {
                marked.copy(walkCursor = cursor)
            }
            Reread(
                albums.filter { it.id in changes.ids || it.id in picked },
                if (named.isEmpty()) "walk" else "server says changed, and walk",
                next,
            )
        }
        is SongChanges.Everything -> when {
            !bulk -> null
            // Every album read is a walk gone round, so one that follows rests from here.
            whole -> Reread(albums, "read once, to compare from", SongCheck(mark = changes.mark, walkedMs = nowMs))
            else -> Reread(albums, "read once, to compare from", check)
        }
        is SongChanges.Unknown -> {
            if (!whole || !bulk) return null
            val (picked, cursor) = walkOn(check, albums, nowMs) ?: return Reread(emptyList(), "", check)
            // A walk covers changes up to the mark it began under; a scan
            // during the walk is caught by the next one.
            val walkMark = check.walkMark ?: changes.mark
            val next = if (cursor == null) {
                SongCheck(mark = walkMark, walkedMs = nowMs)
            } else {
                check.copy(walkMark = walkMark, walkCursor = cursor)
            }
            Reread(albums.filter { it.id in picked }, "walk", next)
        }
    }
}

/**
 * The ids of the walk's next batch from where [check] left it, and the cursor
 * to keep after — or null while a walk that has gone round rests.
 */
private fun walkOn(check: SongCheck, albums: List<Album>, nowMs: Long): Pair<Set<String>, String?>? {
    if (check.walkCursor == null && nowMs - check.walkedMs in 0 until WALK_REST_MS) return null
    val (ids, cursor) = nextWalk(albums.map { it.id }.sorted(), check.walkCursor, WALK_BATCH)
    return ids.toHashSet() to cursor
}

/**
 * Albums a walk re-reads per sync, where the server can't say which changed,
 * or can't say all of it — see [rereadFor]. A 1,400-album library goes round
 * in about fifty syncs on Wi-Fi: a day or so, for thirty small requests each.
 */
internal const val WALK_BATCH = 30

/** How long a finished walk rests before another may start — see [rereadFor]. */
internal const val WALK_REST_MS = 12 * 60 * 60 * 1000L

/**
 * Whether [fresh], an album's songs as the server has them, says anything its
 * [cached] rows don't. Compared as sets: the server's order is not the table's.
 *
 * Plays this phone has counted already are not news. A song played here is
 * stamped here at once (markPlayed), and the server's figures for it never
 * match the stamp — its own clock, Plex to the second, a scrobble still on its
 * way — so opening the album of the song just heard rewrote it, and read the
 * whole library again, every time. A count higher than the cached one is a
 * play somewhere else, and is.
 */
internal fun songsChanged(cached: List<TrackEntity>, fresh: List<TrackEntity>): Boolean {
    val before = cached.associateBy { it.id }
    val heard = fresh.mapTo(HashSet()) { row ->
        val old = before[row.id]
        if (old == null || row.playCount > old.playCount) row
        else row.copy(playCount = old.playCount, lastPlayedMs = old.lastPlayedMs)
    }
    return heard != cached.toHashSet()
}

/**
 * Whether a failure was the server turning us away rather than not being
 * there at all.
 *
 * The difference matters: an unreachable server means the library can only
 * show what is downloaded, while a refused login means the library is fine
 * and the credentials are stale. Treating the second as the first hides a
 * perfectly good cached library and says nothing about why.
 */
internal fun isAuthFailure(e: Exception): Boolean {
    val code = Regex("""\b(\d{3})\b""").find(e.message.orEmpty())?.value?.toIntOrNull()
    return code == 401 || code == 403
}

/**
 * A sync failure in words the person holding the phone can act on.
 *
 * The raw messages are the server's, not theirs: "Plex says 521 for
 * /library/sections/3/all" is accurate, unreadable, and long enough to be
 * truncated to nonsense in the one row that shows it. 5xx in particular
 * matters — it means the server is down rather than anything being wrong
 * with the app or the login, and without saying so the only visible symptom
 * is a Sync button that flashes and does nothing.
 */
internal fun syncErrorMessage(e: Exception): String {
    // The status the server actually sent, from the exception that carries
    // it. This used to scrape any three-digit number out of the message —
    // and a timeout's message names the URL it was fetching, whose
    // `Container-Size=500` read as "the server had an error (500)".
    val code = (e as? ServerException)?.status
    if (code == null && e is java.io.IOException) return "Couldn't reach the server"
    return when {
        code == null -> e.message?.takeIf { it.length < 60 } ?: "Couldn't reach the server"
        // 52x are Cloudflare's: it answered, the server behind it didn't.
        code in 520..529 -> "Your server isn't responding ($code)"
        code in 500..599 -> "The server had an error ($code)"
        code == 401 || code == 403 -> "The server refused the login. Sign in again in Sources."
        code == 404 -> "That library isn't on the server any more"
        else -> "The server said $code"
    }
}

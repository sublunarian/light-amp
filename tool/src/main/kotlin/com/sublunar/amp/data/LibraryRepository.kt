package com.sublunar.amp.data

import com.sublunar.amp.data.db.AlbumEntity
import com.sublunar.amp.data.db.DownloadFile
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.LikedArtistEntity
import com.sublunar.amp.data.db.MAX_BOUND_IDS
import com.sublunar.amp.data.db.PlaylistTrackEntity
import com.sublunar.amp.data.db.TopSongEntity
import com.sublunar.amp.data.db.TrackEntity
import com.sublunar.amp.data.db.toAlbum
import com.sublunar.amp.data.db.toEntity
import com.sublunar.amp.data.db.toTrack
import com.sublunar.amp.source.PhoneLibrary
import com.sublunar.amp.source.PhonePlaylists
import com.sublunar.amp.source.PhoneAccess
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
 * A playlist's songs: [all] of them in order, and which are [shown].
 *
 * A song is shown when the chosen library holds it and, offline, when it can
 * play. [tracks] is what a page lists and plays. Edits address [all]:
 * [LibraryRepository.removeFromPlaylistAt] takes an index into the *server's*
 * list, and [LibraryRepository.reorderPlaylist] takes an order that has to name
 * every song or the ones left out are the ones lost.
 *
 * [complete] is whether [all] is the server's own list rather than a rebuild
 * from cached membership the library no longer has every song for. A caller
 * that edits must check it first.
 */
data class PlaylistView(val all: List<Track>, val complete: Boolean, val shown: List<Boolean> = all.map { true }) {
    val tracks: List<Track> = all.filterIndexed { i, _ -> shown[i] }
}

/**
 * The library: reads come from the active source's Room cache (instant on
 * launch) and are kept fresh by [sync], which pulls the server catalogue
 * incrementally — only albums that are new or changed have their songs re-fetched.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRepository(
    /**
     * The active source's DAO. A flow because switching sources switches the
     * whole database: the observed lists re-subscribe, and everything derived
     * from them follows.
     */
    private val daos: StateFlow<LibraryDao?>,
    private val serverClient: StateFlow<MusicServer?>,
    // The persisted selection as a raw flow, not a StateFlow: [sync] must read
    // the stored value, and a stateIn's initial null would race the DataStore
    // load at boot and sync the wrong (unscoped) library.
    private val libraryId: Flow<String?>,
    /** True when only downloaded media should be visible (offline / Wi-Fi only). */
    offlineOnly: Flow<Boolean>,
    /** Whether the server may be spoken to at all — see App.metadataAllowed. */
    private val metadataAllowed: () -> Boolean,
    /** Whether bulk background fetching is worth the link now: false on a metered link outside Make it Hurt. */
    private val bulkAllowed: () -> Boolean,
    /** Suspends while the player starts a stream, so background work steps aside — see App.yieldToPlayback. */
    private val yieldToPlayback: suspend () -> Unit,
    /** Where likes and ratings go when the server can't be told about them. */
    private val pending: PendingActions,
    /** Puts a line in front of the user — a write the server refused, after its screen has gone. See App.tell. */
    private val tell: (String) -> Unit,
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
     * Every list empties the moment the database changes, before the new one has
     * answered. Otherwise everything downstream serves the *previous* source's
     * rows for the seconds Room takes, and whatever acts on them meanwhile (a
     * download top-up, a cover fetch) sends one server's ids to another. The cost
     * is a blink of empty lists on a source switch, which is the truth.
     *
     * Each list is also read afresh whenever [rereads] moves — see [reread] —
     * without blanking: same source, so an empty list would only flicker.
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
     * Moved once a sync has finished writing, so every list reads its table again
     * rather than trusting Room to have noticed the writes: Room 2.7.0 (the
     * version Light's store builder pins) can leave an observed query showing what
     * it read before a write landed — a first sync's albums stayed invisible.
     *
     * The tracks have [rereadTracks], moved only by a sync that wrote songs: every
     * sync rewrites album rows, and re-reading the whole library after one that
     * changed no song is a second copy of it for nothing.
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
     * Room re-reads the whole table after every write, and a first sync writes
     * forty albums at a time: on a large library, a whole read (with artists, tag
     * lists and sorts behind it) every half second runs the heap out. Room's flow
     * keeps only the latest change while its reader is busy, so holding the
     * reader here after each list is what bounds the reads.
     *
     * Edits made here end the wait too: held back, a like reopened still read
     * "Like", and a second tap liked it twice rather than taking it back.
     *
     * The sync's end both ends the wait and asks for its re-read, in no set
     * order; a wait ended first would read what the re-read reads anyway. So once
     * [superseded], it waits to be cancelled instead.
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
     * The downloads table, read once for everything derived from it. Room re-runs
     * *every* observer of a table after every write to it, so one observer here
     * beats one query per derivation; it carries fileName and format for the
     * player, a few bytes a row against a whole extra scan. Lyrics stay out —
     * see [LibraryDao.observeDownloads].
     */
    val downloadFiles: StateFlow<List<DownloadFile>> =
        observing(emptyList()) { it.observeDownloads() }
            .onEach { downloadsLoaded.value = true }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether [downloadFiles] has heard from the database yet: its empty seed and
     * "no downloads" are the same value. Playback must wait for this before it
     * chooses between a file and a stream, or it streams tracks that are on the
     * disk — see PlaybackController.
     */
    val downloadsLoaded: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Track ids with a completed download. */
    val downloadedTrackIds: StateFlow<Set<String>> =
        downloadFiles.map { rows -> rows.mapTo(HashSet()) { it.trackId } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val offline: StateFlow<Boolean> =
        offlineOnly.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Sweep every library as it is opened, not only when a sync succeeds: a
        // sync prunes only when the server answers, so a library behind an
        // expired login would keep another server's songs for good. A track whose
        // album isn't in the same database is always debris — nothing writes one
        // on purpose (a sync writes an album before its tracks).
        scope.launch {
            daos.filterNotNull().collect { d ->
                val orphans = runCatching { d.orphanedTrackCount() }.noteOom("orphan count").getOrDefault(0)
                if (orphans > 0) {
                    android.util.Log.w("AmpSync", "dropping $orphans orphaned track(s) on open")
                    runCatching { d.deleteOrphanedTracks() }.noteOom("orphan sweep")
                }
                // Playlist membership comes back with its database; forgetDerived
                // has emptied the map by now on a switch.
                seedPlaylistTrackIds(d)
            }
        }
    }

    /**
     * The last whole read of the tracks table, and the database it came from:
     * what the next read of the same database reuses unchanged songs from. Only
     * ever the browsed database's — [trackRows] drops it when the lists change
     * over, so the source left behind isn't kept alive for its objects.
     */
    @Volatile private var lastTracksRead: Pair<LibraryDao, TrackGeneration>? = null

    /**
     * The songs the lists hold right now, if they were read from [dao]; none
     * otherwise. For a page reading several sources' databases (App.downloadsBySource)
     * that wants the browsed one's as the objects already in memory.
     */
    fun loadedTracks(dao: LibraryDao): List<Track> =
        lastTracksRead?.takeIf { it.first === dao }?.second?.tracks.orEmpty()

    /**
     * Every cached track, whichever library it belongs to: the one read of the
     * table, which [allTracks] narrows by a filter rather than a second scan.
     *
     * Room says when to read: it re-counts the table after every write and hands
     * each count on, changed or not, so a count arriving is the signal and its
     * value only sizes the read. [readTracks] keeps the songs that didn't change.
     */
    private val trackRows: StateFlow<List<Track>> =
        observing(emptyList(), rereadTracks, paced = true) { dao ->
            if (lastTracksRead?.first !== dao) lastTracksRead = null
            dao.observeTrackCount().map { count -> readTracks(dao, count) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The tracks table as [dao] holds it now, in storage order, reusing unchanged songs — see [TrackRead]. */
    private suspend fun readTracks(dao: LibraryDao, count: Int): List<Track> {
        val previous = lastTracksRead?.takeIf { it.first === dao }?.second ?: TrackGeneration.EMPTY
        val read = TrackRead(previous, count)
        dao.readTracks(TRACK_PAGE) { page -> page.forEach { read.add(it.rowId, it.track) } }
        val next = read.finish()
        // Only while still the browsed database's: a read finishing just after a
        // switch would otherwise hold the source left behind until the next read.
        if (daos.value === dao) lastTracksRead = dao to next
        return next.tracks
    }

    /** Which of the server's libraries is chosen, or null for all of them. */
    private val selectedLibrary: StateFlow<String?> =
        libraryId.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The libraries the user hid, which are hidden everywhere — All Libraries,
     * search, the tabs, playlists — and not fetched by a sync. Their rows stay
     * cached, so showing one again is instant. See MusicSource.hiddenLibraryIds.
     */
    private val hiddenLibraries: StateFlow<Set<String>> =
        settings.activeSource
            .map { it?.hiddenLibraryIds?.filterNotNull()?.toSet().orEmpty() }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** The album table as stored, library tags and all. */
    private val albumRows: StateFlow<List<AlbumEntity>> =
        observing(emptyList(), paced = true) { it.observeAlbums() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The chosen library's albums, filtered out of the cache rather than fetched:
     * every library the source has stays cached, so switching works with no
     * server at all.
     *
     * A row with no library recorded shows under every one: there is nothing to
     * file it under, and hiding it would lose albums that are right there.
     */
    private val allAlbums: StateFlow<List<Album>> =
        combine(albumRows, selectedLibrary, hiddenLibraries) { rows, library, hidden ->
            val view = LibraryView(library, hidden)
            rows.asSequence()
                // Tracks, artists, search and the liked filters all narrow from
                // here, so this one line hides a library everywhere they look.
                .filter { view.showsAlbum(it.libraryId) }
                .map { it.toAlbum() }
                .toList()
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * And its songs: a track belongs to whichever library its album does, so one
     * column is kept in step, not two. A track with no album at all is kept.
     */
    private val allTracks: StateFlow<List<Track>> =
        combine(trackRows, allAlbums) { rows, albums ->
            val visible = albums.mapTo(HashSet()) { it.id }
            rows.filter { it.albumId == null || it.albumId in visible }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The chosen library's songs, ignoring the offline narrowing. The download
     * top-up's trigger keys on this, never on [tracks], which offline narrows to
     * what is downloaded and so moves on the wrong changes — see App.topUpDownloads.
     */
    val fullTracks: StateFlow<List<Track>> get() = allTracks

    /**
     * The library as the UI should see it: offline, narrowed to what's playable,
     * so browsing, search and queue-building all follow without each screen checking.
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

    // How many tracks each album has, rebuilt only when the library changes, not
    // on every completed download.
    //
    // Each memo holds the list it was worked out from, and with it every song —
    // so it is dropped wherever that list stops being refreshed: on a source
    // switch (see forgetDerived), and once there are no downloads to badge.
    @Volatile private var albumSizes: Pair<List<Track>, Map<String, Int>>? = null

    private fun albumSizes(list: List<Track>): Map<String, Int> =
        albumSizes?.takeIf { it.first === list }?.second
            ?: list.mapNotNull { it.albumId }.groupingBy { it }.eachCount().also { albumSizes = list to it }

    /**
     * Albums whose every cached track is downloaded — drives the download badge.
     * Counts downloaded rows per album against the album's size, so a download
     * costs the number of downloads, not the size of the library.
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
     * The tag values this library actually uses, derived from the cache (no server
     * lists composers). Empty means the server doesn't fill that field, which is
     * how the More page decides whether to offer it.
     *
     * Worked out only while a page shows them, not on every read of the library.
     * Stopped, they keep their last answer for the next page to show meanwhile —
     * unless the source has changed, which empties them (another server's values
     * could set a filter this library can't match).
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

    /**
     * The artists liked on the server. Not derived from [artists], which narrows
     * with the offline view and the chosen library: a like is not a fact about
     * what is on screen.
     */
    val likedArtistNames: StateFlow<Set<String>> =
        observing(emptyList()) { it.observeLikedArtists() }.map { names -> names.toSet() }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Each artist's picture from the server's own record, keyed by name — filled by [primeArtistImages]. */
    private val _artistImages = MutableStateFlow<Map<String, String>>(emptyMap())

    val artists: StateFlow<List<Artist>> =
        combine(tracks, likedArtistNames, _artistImages) { list, liked, images ->
            deriveArtists(list, liked, images)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

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
                    liked = name in liked,
                )
            }
            // Key once per artist, not once per comparison — see LibrarySort.
            .map { sortName(it.name) to it }
            .sortedBy { it.first }
            .map { it.second }

    /**
     * Fetch the server's artist index for its pictures, once per source: one
     * request, asked by the pages that show artists rather than by every sync.
     * Failure leaves the map as it was, and rows show their placeholder.
     */
    suspend fun primeArtistImages() {
        if (_artistImages.value.isNotEmpty()) return
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        val refs = runCatching { client.getArtistIndex(libraryId.first()) }.noteOom("artist index").getOrNull() ?: return
        // The same call is the id index that liking needs.
        if (artistIds == null) artistIds = refs.associate { it.name to it.id }
        _artistImages.value = refs
            .mapNotNull { ref -> ref.imageId?.takeIf { it.isNotBlank() }?.let { ref.name to it } }
            .toMap()
    }

    // Eager state, so a page's first frame already knows whether there are any.
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

    // Serializes syncs and library switches so they never write the cache
    // concurrently; the job lets a switch cancel the sync in flight.
    private val syncMutex = Mutex()
    private var syncJob: Job? = null

    // Serializes playlist writes, first come first written: servers
    // read-then-write whole playlists with no compare-and-swap.
    private val playlistMutex = Mutex()

    /**
     * Wait out the playlist writes already queued — before a playlist is read
     * from the server. Edits show before they are written, and a Plex reorder is
     * seconds of single moves, so a read that doesn't wait can return the order
     * from before the edit, or half-moved. The lock is fair, so taking it once
     * waits for everyone ahead; it isn't held for the (slow) read itself.
     */
    private suspend fun awaitPlaylistWrites() = playlistMutex.withLock { }

    // Artist name -> server artist id, resolved once and reused for liking.
    private var artistIds: Map<String, String>? = null

    // Artist name -> popular songs, so revisiting an artist page doesn't ask the
    // server again, and the order stays stable within a session.
    private val topSongs = ConcurrentHashMap<String, List<Track>>()

    /**
     * Which songs each playlist holds, for the playlists primed so far — see
     * [primePlaylistTrackIds]. No server sends membership with its playlist list,
     * so [Playlist.trackIds] is empty for every server playlist.
     */
    private val membership = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /**
     * Write membership to [membership] and through to the source's database.
     *
     * The map is the live answer, read by every guard the instant it is set; the
     * table is the copy that outlives the process, read back only by
     * [seedPlaylistTrackIds] when a database opens.
     *
     * [d] is the database the caller started with: after a source switch the
     * answer belongs to neither the new map nor the new database. [fresh] is
     * false for a local edit, which changes the copy without re-reading it.
     */
    private suspend fun storeMembership(d: LibraryDao?, id: String, trackIds: List<String>, fresh: Boolean = true) {
        if (d == null || daos.value !== d) return
        val unchanged = membership.value[id] == trackIds
        membership.update { it + (id to trackIds) }
        // This *is* the replacement the mark was asking for.
        if (fresh) staleMembership.update { it - id }
        if (unchanged) return
        runCatching {
            d.setPlaylistTracks(id, trackIds.mapIndexed { index, trackId -> PlaylistTrackEntity(id, index, trackId) })
        }
    }

    /**
     * Playlists held but not yet re-read from the server: everything loaded from
     * disk, and anything a refused edit left uncertain. A stale list still opens
     * and plays; the mark only tells the priming to fetch it again.
     */
    private val staleMembership = MutableStateFlow<Set<String>>(emptySet())

    private fun markMembershipStale(id: String) {
        staleMembership.update { it + id }
    }

    /** Whether a prime should run for [id] even though membership is already held. */
    private fun membershipWanted(id: String): Boolean =
        id !in membership.value || id in staleMembership.value

    /**
     * Apply a local edit to held membership, if any is held for this playlist —
     * an edit is not enough to build one from, and the next prime fetches it whole.
     */
    private suspend fun editMembership(d: LibraryDao?, id: String, edit: (List<String>) -> List<String>) {
        val current = membership.value[id] ?: return
        storeMembership(d, id, edit(current), fresh = false)
    }

    /** Forget one playlist's membership everywhere. */
    private suspend fun dropMembership(d: LibraryDao?, id: String) {
        if (d == null || daos.value !== d) return
        membership.update { it - id }
        staleMembership.update { it - id }
        runCatching { d.clearPlaylistTracks(id) }
    }

    /**
     * Fill [membership] from the database that just opened — only what isn't
     * already known, since a prime that finished meanwhile is newer than the
     * disk. What loads is marked stale, to be read again when the server can be asked.
     */
    private suspend fun seedPlaylistTrackIds(from: LibraryDao) {
        val stored = runCatching { from.storedPlaylistTracks() }.getOrNull() ?: return
        if (stored.isEmpty() || daos.value !== from) return
        val grouped = stored.groupBy({ it.playlistId }, { it.trackId })
        val seeded = grouped.keys - membership.value.keys
        membership.update { known -> grouped + known }
        staleMembership.update { it + seeded }
    }

    /** [membership] without the stored copies still waiting to be re-read: what downloads go by. */
    fun freshPlaylistTrackIds(): Map<String, List<String>> {
        val stale = staleMembership.value
        return membership.value.filterKeys { it !in stale }
    }

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    /** Whether a chosen library narrows a playlist's songs — see SourceProvider.libraryNarrowsPlaylistSongs. */
    private val narrowsPlaylistSongs: StateFlow<Boolean> =
        settings.activeSource
            .map { it?.provider?.libraryNarrowsPlaylistSongs ?: true }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, true)

    /** The chosen library's songs, where it narrows the playlist list instead; null where it doesn't. */
    private val listingLibrary: StateFlow<ListingLibrary?> =
        combine(allTracks, selectedLibrary, hiddenLibraries, narrowsPlaylistSongs) { inLibrary, library, hidden, songsRule ->
            val narrows = LibraryView(library, hidden).narrows
            // Nothing read yet is a library still loading, not one with no songs.
            if (!songsRule && narrows && inLibrary.isNotEmpty()) {
                ListingLibrary(inViewIds(inLibrary), chosen = library != null)
            } else {
                if (!narrows) inViewIds = null
                null
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private class ListingLibrary(val songs: Set<String>, val chosen: Boolean)

    /** Every playlist the source lists, whichever library is chosen: for its own pages, the picker, and priming. */
    val allPlaylists: StateFlow<List<Playlist>> get() = _playlists

    /** Every cached song, whichever library it is in: a playlist can list songs beyond the chosen library. */
    val cachedTracks: StateFlow<List<Track>> get() = trackRows

    /**
     * The playlists as the UI should see them — narrowed offline, as the other
     * three lists are, and to the chosen library's where the source's own app
     * lists them that way (see [playlistListed]).
     *
     * A playlist whose membership isn't known stays visible: an empty
     * [Playlist.trackIds] means "not asked yet" far more often than "empty", and
     * hiding those would hide downloaded, playable playlists.
     */
    val playlists: StateFlow<List<Playlist>> =
        combine(_playlists, membership, downloadedTrackIds, offline, listingLibrary) {
                list, known, downloaded, offlineOnlyNow, listing ->
            list.filter { playlist ->
                val held = known[playlist.id]
                val ids = held ?: playlist.trackIds
                (listing == null || playlistListed(held, listing.songs, listing.chosen)) &&
                    (!offlineOnlyNow || ids.isEmpty() || ids.any { it in downloaded })
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The playlists, when they are files on this phone rather than on a server — see [PhonePlaylists]. */
    private suspend fun phonePlaylists(): PhonePlaylists? =
        settings.activeSource.first()?.phoneLibrary?.playlists

    /**
     * Re-read the playlists, keeping the last known set when the server can't be
     * reached. Playlists have no table; the cached copy lives in preferences —
     * enough to list them, and (via [playlistTracks]) to play what is downloaded.
     */
    suspend fun refreshPlaylists() {
        val sourceId = settings.activeSource.first()?.id ?: return
        val phone = phonePlaylists()
        if (phone != null) {
            // Read off the disk every time: another app may have changed the files.
            _playlists.value = phone.list()
            return
        }
        val d = daos.value
        val fetched = if (!metadataAllowed()) null else runCatching { serverClient.value?.getPlaylists(libraryId.first()) }.getOrNull()
        if (fetched != null) {
            _playlists.value = fetched
            settings.setCachedPlaylists(sourceId, json.encodeToString(fetched))
            reconcileMembership(d, fetched)
            return
        }
        if (_playlists.value.isEmpty()) _playlists.value = loadCachedPlaylists()
    }

    /** Forget the membership of playlists the server no longer lists. */
    private suspend fun reconcileMembership(d: LibraryDao?, fetched: List<Playlist>) {
        if (d == null || daos.value !== d) return
        val live = fetched.mapTo(HashSet()) { it.id }
        membership.update { known -> known.filterKeys { it in live } }
        staleMembership.update { marked -> marked.filterTo(HashSet()) { it in live } }
        runCatching {
            // Room expands an empty list to `NOT IN ()`, which SQLite rejects.
            if (live.isEmpty()) d.clearAllPlaylistTracks() else d.prunePlaylistTracks(live.toList())
        }
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
     * A playlist's songs — from the server, or rebuilt from held membership
     * against the library when it can't be asked. The rebuild keeps the order and
     * drops songs the library no longer holds, as the server would.
     */
    suspend fun playlistTracks(id: String): List<Track> = playlistView(id).tracks

    /** [playlistTracks] plus whether it's the whole playlist — see [PlaylistView]. */
    suspend fun playlistView(id: String): PlaylistView {
        val d = daos.value
        val (tracks, whole) = fetchPlaylistTracks(id)
        // Held at full membership, narrowed on the way out: the badge needs what
        // the playlist *is*, the page what it can play.
        val ids = tracks.map { track -> track.id }
        // Only a whole read is kept (a whole empty answer included). A rebuild
        // drops every id with no library row, so its ids are a *subset* of the
        // membership it was built from: kept, it would lose songs for good and
        // badge a shorter list than the playlist has. It fills the map only when
        // the map holds nothing for this playlist.
        if (whole) {
            storeMembership(d, id, ids)
        } else if (ids.isNotEmpty() && id !in membership.value) {
            membership.update { it + (id to ids) }
        }
        val inView = inLibraryView(d, tracks)
        // A song from the phone's own folder plays whatever the connection is.
        val downloaded = if (offline.value) downloadedTrackIds.value else null
        return PlaylistView(
            tracks,
            whole,
            tracks.map { inView(it) && (downloaded == null || it.id in downloaded || PhoneFiles.isPhoneFile(it.id)) },
        )
    }

    /**
     * Which of [tracks] a playlist page shows: the chosen library's, by the Songs
     * tab's rule, where the source's own app narrows a playlist's songs; otherwise
     * every song but a hidden library's — see SourceProvider.libraryNarrowsPlaylistSongs.
     *
     * Read from the database and the stored choice rather than the lists held
     * in memory, which are still loading when a playlist is opened at launch.
     */
    private suspend fun inLibraryView(d: LibraryDao?, tracks: List<Track>): (Track) -> Boolean {
        val source = settings.activeSource.first()
        val songsFollowLibrary = source?.provider?.libraryNarrowsPlaylistSongs ?: true
        val view = LibraryView(
            if (songsFollowLibrary) libraryId.first() else null,
            source?.hiddenLibraryIds?.filterNotNull()?.toSet().orEmpty(),
        )
        if (!view.narrows || d == null) return { true }
        val rows = runCatching {
            tracks.mapNotNullTo(HashSet()) { it.albumId }.toList().chunked(MAX_BOUND_IDS).flatMap { d.albumLibraries(it) }
        }.noteOom("playlist libraries").getOrNull() ?: return { true }
        val libraryOf = rows.associate { it.id to it.libraryId }
        return { track ->
            val albumId = track.albumId
            view.showsSong(albumId, albumId != null && albumId in libraryOf, albumId?.let { libraryOf[it] })
        }
    }

    /** A playlist's songs, and whether they are the playlist entire rather than what could be recovered. */
    private data class FetchedPlaylist(val tracks: List<Track>, val whole: Boolean)

    private suspend fun fetchPlaylistTracks(id: String): FetchedPlaylist {
        awaitPlaylistWrites()
        val phone = phonePlaylists()
        if (phone != null) {
            val ids = phone.trackIds(id)
            val tracks = getTracksByIds(ids)
            return FetchedPlaylist(tracks, tracks.size == ids.size)
        }
        // Asked only when the mode allows, as the playlist list is; otherwise
        // straight to the rebuild below, which needs no network.
        if (metadataAllowed()) {
            serverClient.value?.let { client ->
                runCatching { client.getPlaylistTracks(id) }.getOrNull()
                    ?.let { return FetchedPlaylist(it, whole = true) }
            }
        }
        // A rebuild is a guess at what the server holds — membership can be stale
        // and the library missing rows — so nothing built here is safe to edit
        // against. Held membership first: [Playlist.trackIds] is usually empty.
        val ids = membership.value[id]
            ?: (_playlists.value.firstOrNull { it.id == id } ?: return FetchedPlaylist(emptyList(), false)).trackIds
        return FetchedPlaylist(getTracksByIds(ids), whole = false)
    }

    /**
     * Primes running, by playlist id. The "already known" check sees only work
     * that has *finished*, and at launch two callers (App.boot and the library
     * shell) arrive seconds apart: without this both fetch the same playlist.
     */
    private val playlistPrimes = mutableMapOf<String, Deferred<Unit>>()
    private val playlistPrimeLock = Mutex()

    /**
     * Fill [membership] for [id] if it isn't already known or on its way, and
     * otherwise wait for the fetch already running.
     *
     * Asks the server directly rather than through [playlistTracks], and writes
     * nothing on failure: the offline rebuild is right for a screen that must
     * show *something*, but kept here it would freeze a passing failure.
     */
    suspend fun primePlaylistTrackIds(id: String) {
        if (!membershipWanted(id)) return
        val prime = playlistPrimeLock.withLock {
            // Again inside the lock: the fetch may have finished while this waited.
            if (!membershipWanted(id)) return
            val running = playlistPrimes[id]
            // A finished prime may have failed: the next caller gets a fresh
            // attempt, so a stale entry is never reused and never needs sweeping.
            if (running != null && running.isActive) {
                running
            } else {
                // In the repository's scope, not the caller's, so a screen going
                // away mid-fetch doesn't throw the shared download out with it.
                scope.async { primePlaylistOnce(id) }.also { playlistPrimes[id] = it }
            }
        }
        // Cancelling this caller cancels only its wait; the shared fetch runs on.
        prime.await()
    }

    private suspend fun primePlaylistOnce(id: String) {
        if (phonePlaylists() != null || serverClient.value == null) {
            playlistTracks(id)
            return
        }
        if (!metadataAllowed()) return
        val d = daos.value
        awaitPlaylistWrites()
        val tracks = runCatching { serverClient.value?.getPlaylistTracks(id) }.noteOom("playlist prime $id").getOrNull() ?: return
        storeMembership(d, id, tracks.map { track -> track.id })
    }

    /**
     * The same for a whole list, what the playlists tab needs: one request per
     * playlist (no server returns membership with the list), run in parallel
     * behind the sync's concurrency limit.
     */
    suspend fun primePlaylistTrackIds(ids: List<String>) = coroutineScope {
        // Every playlist's whole contents is the heaviest thing a launch does
        // after the sync, so on a metered link outside Make it Hurt it waits for
        // Wi-Fi (opening one playlist goes by metadataAllowed instead). Until
        // then the playlist_tracks rows serve — see storeMembership.
        if (!bulkAllowed() && phonePlaylists() == null) return@coroutineScope
        val gate = Semaphore(SYNC_CONCURRENCY)
        ids.filter { membershipWanted(it) }
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

    /** Playlists whose every song the chosen library shows is downloaded — drives the download badge. */
    val downloadedPlaylistIds: StateFlow<Set<String>> =
        combine(membership, downloadedTrackIds, playlistShown()) { known, downloaded, shown ->
            known.entries.mapNotNullTo(mutableSetOf()) { (playlistId, ids) ->
                playlistId.takeIf { playlistMarked(ids, downloaded, shown) }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * The songs a playlist page shows, where the chosen library narrows them: the
     * Songs tab's, or every library's but a hidden one's — see [inLibraryView].
     * Null where every song shows.
     */
    private fun playlistShown(): Flow<Set<String>?> =
        combine(
            combine(allTracks, trackRows, albumRows) { inLibrary, rows, albums -> Triple(inLibrary, rows, albums) },
            selectedLibrary,
            hiddenLibraries,
            narrowsPlaylistSongs,
        ) { (inLibrary, rows, albums), library, hidden, songsRule ->
            val narrows = LibraryView(library, hidden).narrows
            if (!narrows) inViewIds = null
            if (hidden.isEmpty()) unhidden = null
            when {
                narrows && songsRule -> inViewIds(inLibrary)
                !songsRule && hidden.isNotEmpty() -> unhiddenIds(rows, albums, hidden)
                else -> null
            }
        }

    private class Unhidden(val rows: List<Track>, val albums: List<AlbumEntity>, val hidden: Set<String>, val ids: Set<String>)

    // Memoised on its inputs, so a download landing doesn't rebuild it.
    @Volatile private var unhidden: Unhidden? = null

    /** The cached songs in no hidden library, by the same rule a playlist page uses. */
    private fun unhiddenIds(rows: List<Track>, albums: List<AlbumEntity>, hidden: Set<String>): Set<String> {
        unhidden?.takeIf { it.rows === rows && it.albums === albums && it.hidden == hidden }?.let { return it.ids }
        val view = LibraryView(null, hidden)
        val libraryOf = albums.associate { it.id to it.libraryId }
        val ids = rows.filter { track ->
            val albumId = track.albumId
            view.showsSong(albumId, albumId != null && albumId in libraryOf, albumId?.let { libraryOf[it] })
        }.mapTo(HashSet()) { it.id }
        unhidden = Unhidden(rows, albums, hidden, ids)
        return ids
    }

    // Memoised on the list instance, so a download landing doesn't rebuild it.
    @Volatile private var inViewIds: Pair<List<Track>, Set<String>>? = null

    private fun inViewIds(list: List<Track>): Set<String> =
        inViewIds?.takeIf { it.first === list }?.second
            ?: list.mapTo(HashSet()) { it.id }.also { inViewIds = list to it }

    suspend fun createPlaylist(name: String): WriteResult = createPlaylist(name, emptyList())

    /**
     * [trackIds] are the playlist's opening contents, not a hint — see
     * [MusicServer.createPlaylist]. Callers must not add them again.
     *
     * This and the writes after it say when they didn't land — see [said] — and
     * answer how it went, for a caller with more to do after.
     */
    suspend fun createPlaylist(name: String, trackIds: List<String>): WriteResult = playlistMutex.withLock {
        val result = playlistWrite(
            phone = { it.create(name.trim(), trackIds) != null },
            server = { it.createPlaylist(name.trim(), trackIds) },
        )
        refreshPlaylists()
        said("createPlaylist", result)
    }

    suspend fun renamePlaylist(id: String, name: String): WriteResult = playlistMutex.withLock {
        val result = playlistWrite(
            phone = { it.rename(id, name.trim()) },
            server = { it.renamePlaylist(id, name.trim()) },
        )
        refreshPlaylists()
        said("renamePlaylist($id)", result)
    }

    suspend fun deletePlaylist(id: String): WriteResult = playlistMutex.withLock {
        val d = daos.value
        val result = playlistWrite(
            phone = { it.delete(id) },
            server = { it.deletePlaylist(id) },
        )
        if (result == WriteResult.Saved) dropMembership(d, id)
        refreshPlaylists()
        said("deletePlaylist($id)", result)
    }

    /**
     * Append one track. Sequential by contract: the server's update appends, so
     * concurrent calls would race the order — [playlistMutex] holds the contract
     * across calls, not just within one caller's loop.
     */
    suspend fun addToPlaylist(id: String, trackId: String): WriteResult = playlistMutex.withLock {
        val d = daos.value
        val result = playlistWrite(
            phone = { it.add(id, trackId) },
            server = { it.addToPlaylist(id, trackId) },
        )
        if (result == WriteResult.Saved) editMembership(d, id) { it + trackId }
        said("addToPlaylist($id)", result)
    }

    /**
     * Remove the entries [at] these positions in the playlist, each given with the
     * song expected there — see [MusicServer.removeFromPlaylistAt]. Whether they
     * all went: callers show the edit at once and take it back on a false.
     */
    suspend fun removeFromPlaylistAt(id: String, at: Map<Int, String>): Boolean = playlistMutex.withLock {
        val d = daos.value
        playlistEdit(
            "removeFromPlaylistAt($id)",
            phone = { it.removeAt(id, at) },
            server = { it.removeFromPlaylistAt(id, at) },
        ).also { ok ->
            if (ok) editMembership(d, id) { ids -> ids.filterIndexed { index, _ -> index !in at.keys } }
            else markMembershipStale(id)
        }
    }

    /** Whether the new order landed — see [removeFromPlaylistAt]. */
    suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>): Boolean = playlistMutex.withLock {
        val d = daos.value
        playlistEdit(
            "reorderPlaylist($id)",
            phone = { it.reorder(id, orderedSongIds) },
            server = { it.reorderPlaylist(id, orderedSongIds) },
        ).also { ok ->
            // The new order *is* the new membership. A refusal leaves the server's
            // order unknown (Plex's can fail partway), so it is read again, and the
            // stored copy serves until then.
            if (ok) editMembership(d, id) { orderedSongIds } else markMembershipStale(id)
        }
    }

    /** A playlist write, to the phone's playlist file or to the server. */
    private suspend fun playlistWrite(
        phone: suspend (PhonePlaylists) -> Boolean,
        server: suspend (MusicServer) -> Unit,
    ): WriteResult {
        val files = phonePlaylists()
        return if (files != null) fileWrite(phone(files)) else serverWrite(server)
    }

    /**
     * A playlist edit that answers whether it landed. The client's own answer,
     * not merely "it didn't throw": some clients catch their transport errors, so
     * a refused write — or no client at all — would otherwise read as done.
     */
    private suspend fun playlistEdit(
        what: String,
        phone: suspend (PhonePlaylists) -> Boolean,
        server: suspend (MusicServer) -> Boolean,
    ): Boolean {
        val files = phonePlaylists()
        if (files != null) return phone(files)
        return runCatching { serverClient.value?.let { server(it) } ?: false }
            .onFailure { android.util.Log.w("AmpSync", "$what failed: ${it.message}", it) }
            .getOrDefault(false)
    }

    /** No client at all is a server that can't be reached, not one that said no. */
    private suspend fun serverWrite(block: suspend (MusicServer) -> Unit): WriteResult =
        serverClient.value?.let { client -> attemptWrite { block(client) } } ?: WriteResult.Unreachable(UNREACHABLE)

    /** The phone's own playlists are files, and a file that wouldn't change is the only way one fails. */
    private fun fileWrite(done: Boolean): WriteResult =
        if (done) WriteResult.Saved else WriteResult.Refused("Couldn't change the playlist file")

    /**
     * A playlist write that didn't land is said, and that is all: none of these
     * show their change before the server has it, so there is nothing to take back.
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

    // --- Sync ----------------------------------------------------------------

    /** The server's libraries (music folders); empty if unsupported or on error. */
    suspend fun musicFolders(): List<MusicFolder> =
        runCatching { serverClient.value?.getMusicFolders().orEmpty() }.getOrDefault(emptyList())

    /** Full incremental sync of the currently selected library. Safe to repeat. */
    suspend fun sync() = syncScoped(libraryId.first())

    /**
     * Switch to [musicFolderId]: cancel any sync in flight and sync just that
     * library, serialized through [syncMutex].
     *
     * **Nothing is thrown away**: every library stays cached, tagged with where it
     * came from, and choosing one is a filter — see [allAlbums]. The sync is a
     * refresh, not what makes the switch work; failing, or never running for want
     * of a signal, it leaves the library showing what was last cached for it.
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
     * Drop the sync in flight, which is for a source we have left. Its writes are
     * already pinned to their own database; this saves the fetches, and stops its
     * failure being reported as *this* source being unreachable.
     */
    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * Start the sync state over for the source just switched to, from the time it
     * last finished a sync. One state serves the whole app: carried across, the
     * new source would show the old one's time, error and scan refusal, and the
     * floor in [syncInBackground] would turn its first sync away.
     */
    fun adoptSource(lastSyncedMs: Long) {
        scanRefused = false
        _syncState.value = SyncState(lastSyncedMs = lastSyncedMs)
    }

    /**
     * Put back a `syncing` claim that [runSync] turned out to have no work for.
     * Only the flag: an error an earlier run set still stands.
     */
    private fun releaseSyncClaim() {
        _syncState.update { it.copy(syncing = false, phase = "") }
    }

    /**
     * Try the active source's other addresses for the same server, and keep one
     * that answers — see SourceProvider.relocate. True when the address changed;
     * saving the source rebuilds the client, and playback and downloads follow.
     */
    private suspend fun repoint(): Boolean {
        val source = settings.activeSource.first() ?: return false
        val moved = source.provider.relocate(source) ?: return false
        android.util.Log.i("AmpSync", "${source.kind.name.lowercase()} moved: ${source.baseUrl} -> ${moved.baseUrl}")
        settings.saveSource(moved)
        return true
    }

    /**
     * The sync itself, of [musicFolderId] (null = all libraries); callers
     * serialize through [syncMutex].
     *
     * `dao` and `client` are pinned for the whole run, and every helper takes the
     * pinned ones, never the [dao] or [serverClient] properties — those follow the
     * active source, and a switch mid-run would land one server's albums and songs
     * in another's library. The local scan needs the same.
     *
     * Each early return releases the claim the caller staked (callers set
     * `syncing` before launching, to turn duplicates away): left standing, it
     * would turn away every later sync for the life of the process.
     * runLocalScan owns the flag across its whole run.
     */
    private suspend fun runSync(musicFolderId: String?, allowRepoint: Boolean = true) {
        val source = settings.activeSource.first()
        val dao = daos.value ?: return releaseSyncClaim()
        // The phone's own music has no server to ask; it is read off the disk.
        val phone = source?.phoneLibrary
        if (source != null && phone != null) {
            runLocalScan(dao, source.id, phone)
            return
        }
        if (!metadataAllowed()) return releaseSyncClaim()
        // A build that changed how a track is read refills the cache once: the
        // incremental filter below sees only what the *server* changed — see
        // MusicSource.parserGeneration.
        val refillForParser =
            source != null && source.parserGeneration != TRACK_PARSER_GENERATION
        val client = serverClient.value ?: return releaseSyncClaim()
        _syncState.update { it.copy(syncing = true, error = null, phase = "Connecting") }
        syncingDao.value = dao
        // What the run wrote, so the re-read at its end covers that and no more.
        val wrote = SyncWrites()
        try {
            // Read before the first write — see reportDroppedCovers.
            val coversBefore = coverIdsOrNull(dao)
            // Rows with no library recorded show in *every* library (see
            // allAlbums), and a scoped sync can tag only the folder it fetched.
            // So while untagged rows exist on a server with several libraries,
            // each folder's album list (cheap, no songs) stamps the rows it names.
            val untagged = dao.untaggedAlbumCount()
            if (untagged > 0) {
                val folders = client.getMusicFolders()
                if (folders.size >= 2) {
                    _syncState.update { it.copy(phase = "Sorting libraries") }
                    var stamped = 0
                    var named = 0
                    for (folder in folders) {
                        val ids = client.getAllAlbums(folder.id).map { it.id }
                        named += ids.size
                        ids.chunked(500).forEach { chunk -> stamped += dao.tagAlbums(folder.id, chunk) }
                    }
                    if (stamped > 0) wrote.albums = true
                    // Still untagged after every folder has spoken: an album the
                    // server has nowhere (its id gone or changed, as a re-import
                    // does), which no scoped prune would ever reach. The lists just
                    // read are the whole server, so it goes — unless they were
                    // empty, the main prune's rule.
                    val dead = if (named > 0) dao.untaggedAlbumIds() else emptyList()
                    if (dead.isNotEmpty()) {
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
            // "All libraries" is fanned out per folder and merged: an unscoped
            // getAlbumList2 returns only the server's default library. A hidden
            // library is left out — not wanted on this phone, data included.
            val hidden = source?.hiddenLibraryIds?.filterNotNull()?.toSet().orEmpty()
            val folderIds: List<String?> = if (musicFolderId != null) {
                listOf(musicFolderId)
            } else {
                client.getMusicFolders().map { it.id }.filterNot { it in hidden }.ifEmpty { listOf(null) }
            }

            // Null when the server didn't answer: likes then stay as they are,
            // rather than being reconciled against nothing (unliking everything)
            // or failing the whole sync over them.
            val starred = runCatching { folderIds.map { client.getStarred(it) }.mergeStarred() }
                .noteOom("sync: favourites")
                .onFailure { android.util.Log.w("AmpSync", "favourites unread, likes kept: $it") }
                .getOrNull()
            val cached = dao.allAlbumsSnapshot().associateBy { it.id }

            _syncState.update { it.copy(phase = "Fetching albums") }
            // Each album keeps the folder it came from, written onto its row — see
            // AlbumEntity.libraryId. An album in two folders keeps the first.
            val fetched = folderIds
                .flatMap { folder ->
                    val list = client.getAllAlbums(folder)
                    android.util.Log.i("AmpSync", "folder=$folder returned ${list.size} albums")
                    list.map { it to folder }
                }
                .distinctBy { (album, _) -> album.id }
            val serverAlbums = fetched.map { (album, _) ->
                album.copy(liked = starred?.let { album.id in it.albumIds } ?: cached[album.id]?.liked ?: album.liked)
            }
            val libraryOf = fetched.associate { (album, folder) -> album.id to folder }
            val serverAlbumById = serverAlbums.associateBy { it.id }

            dao.upsertAlbums(serverAlbums.map { it.toEntity(libraryOf[it.id]) })
            if (serverAlbums.isNotEmpty()) wrote.albums = true

            // Prune albums (and their tracks) the server no longer has — never on
            // an empty answer. A stale scope, a 200 with an empty body or a gone
            // section id look exactly like an emptied library, and pruning on one
            // deletes the whole cache for good (the next sync asks the same way).
            if (serverAlbums.isEmpty()) {
                android.util.Log.w("AmpSync", "returned no albums; keeping ${cached.size} cached")
            } else {
                // Only within the library just fetched: one folder's answer says
                // nothing about the others. A scoped sync leaves untagged rows
                // alone (no fetch speaks for them), and a hidden library's rows
                // were never asked about, so they stay for when it is shown again.
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
                // And the tracks belonging to no album here at all, which the album
                // prune can't reach — see LibraryDao.orphanedTrackCount. Under the
                // same empty-answer rule.
                val orphans = dao.orphanedTrackCount()
                if (orphans > 0) {
                    android.util.Log.w("AmpSync", "dropping $orphans track(s) belonging to no album here")
                    dao.deleteOrphanedTracks()
                    wrote.tracks = true
                }
            }

            // Only new or changed albums have their songs re-fetched — and "changed"
            // includes "its songs aren't here": albums are written before their
            // songs, so an interrupted sync leaves complete-looking album rows with
            // no tracks. The real per-album track count makes that self-heal.
            val cachedTrackCounts = dao.trackCountsByAlbum().associate { it.albumId to it.tracks }
            // Why each album was chosen, counted for the log: a sync walking the
            // whole library must be told apart from one that has everything to fetch.
            val reasons = mutableMapOf<String, Int>()
            var unchanged = 0
            val toFetch = if (refillForParser) serverAlbums else serverAlbums.filter { album ->
                val prev = cached[album.id]
                val cachedCount = cachedTrackCounts[album.id] ?: 0
                val why = when {
                    prev == null -> "new album"
                    cachedCount == 0 -> "no songs cached"
                    // A song count of 0 means "not sent" (Plex leaves leafCount
                    // off), not zero: compared as a count, every album looks
                    // changed on every sync. Ask when the server last touched the
                    // album instead (Plex moves updatedAt with its contents). A
                    // server sending neither leaves both at 0, which keeps the
                    // cache; rows from before the column read 0 and walk once.
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
            // And the albums whose songs changed without their count moving (a
            // re-tag, tags a server finished reading late), as far as the server
            // can say — see songChangesSince.
            val songCheck = source?.let { settings.songCheck(it.id) }
            // Asked on a refill too: it hands over the first mark.
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

            // Counted in albums, one request each: labelled as songs it would read
            // as a library being cut short.
            _syncState.update { it.copy(phase = "Albums 0/${albumsToFetch.size}") }
            val likedSongs = starred?.songIds ?: dao.likedTrackIds().toHashSet()
            val unread = fetchAndStoreSongs(dao, client, albumsToFetch, likedSongs, libraryOf, wrote)
            // Settled only once every album it covers is read; otherwise the old
            // mark stays, to be asked again next time.
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

            // Only now: a run that threw part-way refills again next time.
            if (refillForParser && source != null) {
                settings.setParserGeneration(source.id, TRACK_PARSER_GENERATION)
            }

            // An album whose art changed has a new cover id, which its songs
            // (not re-fetched for that) don't carry yet: point them at it, so the
            // old one is seen to be let go of just below.
            runCatching { dao.collapseTrackCovers() }.noteOom("sync: cover collapse")
                .onSuccess { if (it > 0) wrote.tracks = true }
            reportDroppedCovers(dao, source?.id, coversBefore)
            if (wrote.tracks || wrote.albums || wrote.likedArtists) rereadLists(tracks = wrote.tracks)
            // The lists read freely again — after the re-read is asked for, so a
            // list this wakes sees it is superseded: see pacedWhileSyncing.
            syncingDao.compareAndSet(dao, null)

            val finishedAt = System.currentTimeMillis()
            _syncState.value = SyncState(
                syncing = false,
                lastSyncedMs = finishedAt,
            )
            // The source this ran for, not whichever is active by now.
            runCatching { source?.id?.let { settings.setLastSyncedMs(it, finishedAt) } }
            learnServerFeatures(dao, client, source)
            onSyncSucceeded?.invoke()
        } catch (e: CancellationException) {
            // A switch cancelled this sync; not an error.
            _syncState.update { it.copy(syncing = false) }
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AmpSync", "sync failed: ${e::class.simpleName}: ${e.message}", e)
            // The address may simply have moved — see repoint. Only once, so a
            // dead server can't loop.
            if (allowRepoint && repoint()) {
                runSync(musicFolderId, allowRepoint = false)
                return
            }
            _syncState.update { it.copy(syncing = false, error = syncErrorMessage(e)) }
            onSyncFailed?.invoke(isAuthFailure(e))
        } finally {
            syncingDao.compareAndSet(dao, null)
        }
    }

    /**
     * Ask the server what it implements, once a sync has just proved it answers:
     * the probes that need a real song need a library, and a server that just
     * served a whole sync is reachable and logged in, so an endpoint it calls
     * missing is missing. Anything thrown leaves what was known. See [ServerFeatures].
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
            // Re-read the source, which may have been edited during a long sync:
            // only the features are ours to write.
            val current = settings.sources.first().firstOrNull { it.id == source.id }
                ?: return@runCatching
            settings.saveSource(current.copy(features = learned))
        }.noteOom("sync: server features")
    }

    /**
     * Rebuild the library from the files on this phone — wholesale: the
     * filesystem *is* the index, a scan takes seconds, and a local source has no
     * likes, ratings or play counts to reconcile.
     */
    private suspend fun runLocalScan(dao: LibraryDao, sourceId: String, phone: PhoneLibrary) {
        _syncState.update { it.copy(syncing = true, error = null, phase = "Reading this phone") }
        try {
            val access = phone.access()
            if (access != PhoneAccess.GRANTED) {
                _syncState.value = SyncState(
                    syncing = false,
                    // Blocked is not "not yet allowed": the prompt can't change a
                    // LightOS policy, so say where the wall is.
                    error = when (access) {
                        PhoneAccess.BLOCKED_BY_LIGHTOS -> "LightOS is blocking this tool's music access"
                        else -> "Allow music access to read this phone"
                    },
                )
                return
            }
            val scan = phone.scan { count ->
                _syncState.update { it.copy(phase = "$count tracks") }
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
            _syncState.update { it.copy(syncing = false) }
            throw e
        } catch (e: Exception) {
            _syncState.update { it.copy(syncing = false, error = e.message ?: "Couldn't read the music folder") }
        }
    }

    /**
     * Told when a sync finishes — the server answered — so App can report it
     * reachable and follow up downloads and covers. Callbacks, set by App, so the
     * repository needn't know about any of them.
     */
    var onSyncSucceeded: (() -> Unit)? = null

    /**
     * Told when a sync gives up. The flag is true when the server answered and
     * refused us — see [isAuthFailure]; not to be read as unreachable.
     */
    var onSyncFailed: ((authFailure: Boolean) -> Unit)? = null

    /**
     * Told which covers a finished sync left nothing pointing at, so their files
     * can go — see ArtworkLoader.drop. Names the source the run was pinned to,
     * which may no longer be the one on screen.
     */
    var onCoversDropped: (suspend (sourceId: String, coverArtIds: Set<String>) -> Unit)? = null

    private suspend fun coverIdsOrNull(dao: LibraryDao): Set<String>? =
        runCatching { dao.allCoverArtIds().toSet() }.noteOom("sync: cover ids").getOrNull()

    /** See [reread]; the tracks too when [tracks], the run having written to them. */
    private fun rereadLists(tracks: Boolean) {
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
     * Name the covers that were in this database before the run and aren't now:
     * a removed album's, or the old id of art that changed (Navidrome and Plex
     * ids carry the date the art was touched). The artwork cache can't find
     * those itself — its files are named by a hash of the id.
     *
     * Asked of the database, so it covers every way a run changes what is pointed
     * at. Only on success: a half-finished sync's covers are not orphans. What
     * this misses ages out — see ArtworkLoader.expireUnclaimed.
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
        // Fetch concurrently, write in batches: a transaction per album would be
        // an invalidation of the tracks table, and a whole re-read, per album.
        albums.chunked(SYNC_WRITE_BATCH).forEach { batch ->
            val fetched = batch.map { album ->
                async {
                    gate.withPermit {
                        yieldToPlayback()
                        // Null when the server didn't answer, which is not an album
                        // with no songs: read as one, its songs would be deleted.
                        val songs = try {
                            client.getAlbumTracks(album.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }?.map { it.copy(liked = it.id in starredSongIds) }
                        val n = done.incrementAndGet()
                        if (n % 10 == 0 || n == albums.size) {
                            _syncState.update { it.copy(phase = "Albums $n/${albums.size}") }
                        }
                        songs
                    }
                }
            }.awaitAll()
            // A switch away cancels this sync; a fetched batch must not be written on the way out.
            ensureActive()
            val read = batch.indices.filter { fetched[it] != null }
            unread += batch.size - read.size
            // Only the albums whose songs differ from the cache — see songsChanged:
            // rewritten unchanged, an album's rows move in the table, which costs
            // a whole re-read of the library as surely as a real edit.
            val cachedRows = dao.tracksForAlbums(read.map { batch[it].id }).groupBy { it.albumId }
            val changed = read.mapNotNull { i ->
                val rows = fetched[i].orEmpty().map { it.toEntity() }
                (batch[i].id to rows).takeIf { songsChanged(cachedRows[batch[i].id].orEmpty(), rows) }
            }
            if (changed.isNotEmpty()) {
                dao.replaceAlbumTracks(changed.map { it.first }, changed.flatMap { it.second })
                wrote.tracks = true
            }
            // Write back the count we got for any album whose server sent none, so
            // the next sync has something to compare against.
            val counted = batch.mapIndexedNotNull { index, album ->
                val songs = fetched.getOrNull(index)?.size ?: 0
                album.copy(songCount = songs).takeIf { album.songCount == 0 && songs > 0 }
            }
            // With its library tag: an upsert replaces the whole row, and an
            // untagged album shows in every library — see allAlbums.
            if (counted.isNotEmpty()) {
                dao.upsertAlbums(counted.map { it.toEntity(libraryOf[it.id]) })
                wrote.albums = true
            }
        }
        unread
    }

    /** Union of the per-folder liked results (one call per library). */
    private fun List<Starred>.mergeStarred(): Starred =
        if (size == 1) first() else Starred(
            songIds = flatMapTo(mutableSetOf()) { it.songIds },
            albumIds = flatMapTo(mutableSetOf()) { it.albumIds },
            artistNames = flatMapTo(mutableSetOf()) { it.artistNames },
        )

    /**
     * An automatic sync, unless one is running or ran within the floor. [force]
     * skips the floors, for a library just shown again, which may have nothing
     * to show until it is fetched. Sync Now goes through [scanAndSyncInBackground].
     */
    fun syncInBackground(force: Boolean = false) {
        // Claimed before the launch: runSync says "syncing" only once it holds
        // the mutex, so a second call before then would queue a whole second
        // sync behind the first. Callers ask freely (the boot screen on every re-entry).
        if (_syncState.value.syncing) return
        // And a floor between runs, which the claim doesn't give. "in 0 until",
        // not "<": a time in the future (a sync recorded while the clock was
        // wrong) is not a recent sync, and would stop automatic syncs until the
        // clock caught up.
        if (!force && System.currentTimeMillis() - _syncState.value.lastSyncedMs in 0 until SYNC_MIN_INTERVAL_MS) return
        _syncState.update { it.copy(syncing = true, error = null, phase = "Connecting") }
        syncJob = scope.launch {
            try {
                // The floor above knows only this process, so the stored time is
                // asked too — and on a metered link the floor is hours: an
                // automatic sync is hundreds of requests for a library that
                // rarely changed.
                val last = runCatching {
                    settings.activeSource.first()?.id?.let { settings.lastSyncedMs(it) }
                }.getOrNull() ?: 0L
                val floor = if (bulkAllowed()) SYNC_MIN_INTERVAL_MS else SYNC_MIN_INTERVAL_METERED_MS
                if (!force && System.currentTimeMillis() - last in 0 until floor) {
                    android.util.Log.i("AmpSync", "launch sync skipped: last one ${(System.currentTimeMillis() - last) / 60_000} min ago")
                    _syncState.update { it.copy(syncing = false, phase = "", lastSyncedMs = last) }
                    return@launch
                }
                sync()
            } catch (e: Throwable) {
                // runSync releases the claim on its own paths, but a cancellation
                // or a failed settings read can end this before it starts — and a
                // claim left standing turns every later sync away.
                _syncState.update { it.copy(syncing = false) }
                throw e
            }
        }
    }

    /**
     * Set when the server would not be told to scan — see [scanAndSyncInBackground].
     * Read by the Sync Now row, so a refusal isn't mistaken for nothing new.
     */
    @Volatile
    var scanRefused: Boolean = false
        private set

    /**
     * Sync Now: ask the server to look for new files, wait a while for its scan,
     * then sync. A sync pulls only what the server already knows. The wait is
     * bounded — a scan can take as long as it likes, and what it finds later
     * arrives with the next sync. Automatic syncs never ask for a scan.
     */
    fun scanAndSyncInBackground() {
        // Claimed before anything is awaited: asking for a scan is a network
        // call, and meanwhile a second tap would queue a second scan-and-sync.
        if (_syncState.value.syncing) return
        _syncState.update { it.copy(syncing = true, error = null, phase = "Scanning server") }
        val previous = syncJob
        syncJob = scope.launch {
            previous?.cancelAndJoin()
            val client = serverClient.value
            val folder = libraryId.first()
            // A server known to have no scan call isn't asked — see
            // MusicSource.supportsServerScan; the sync still runs.
            val canScan = settings.activeSource.first()?.supportsServerScan ?: true
            val outcome = if (client != null && canScan) {
                runCatching { client.startServerScan(folder) }
                    .getOrDefault(ScanRequest.REFUSED)
            } else {
                ScanRequest.ABSENT
            }
            val asked = outcome == ScanRequest.STARTED
            // Said plainly rather than looking like an empty scan (a library
            // shared with you on Plex: scanning is the owner's). A server with no
            // such call has refused nothing.
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
     * An album's songs read again from its server, and stored — what opening its
     * page does, so a record someone is looking at is never older than the
     * server. Null where the server can't be asked or didn't answer.
     *
     * Likes stay as this phone has them: one made offline may not have reached
     * the server yet, and a sync reconciles the rest.
     */
    suspend fun refreshAlbumTracks(albumId: String): List<Track>? {
        if (!metadataAllowed() || offline.value) return null
        // Both ends pinned, and the write dropped if the source changed meanwhile — see runSync.
        val dao = daos.value ?: return null
        val client = serverClient.value ?: return null
        val fresh = runCatching { client.getAlbumTracks(albumId) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val cached = dao.tracksForAlbum(albumId)
        val liked = cached.associate { it.id to it.liked }
        val merged = fresh.map { it.copy(liked = liked[it.id] ?: it.liked) }
        if (daos.value !== dao) return null
        // Written only if something moved — see fetchAndStoreSongs.
        val rows = merged.map { it.toEntity() }
        if (songsChanged(cached, rows)) {
            dao.replaceAlbumTracks(listOf(albumId), rows)
        }
        return narrowedToDownloads(merged).sortedWith(TRACK_ORDER)
    }

    /** What of these will actually play, offline (as [tracks] narrows); all of them otherwise. */
    private fun narrowedToDownloads(tracks: List<Track>): List<Track> {
        if (!offline.value) return tracks
        val downloaded = downloadedTrackIds.value
        return tracks.filter { it.id in downloaded }
    }

    /** These albums' songs from the cache, album after album, each in its running order. */
    suspend fun albumQueue(albumIds: List<String>): List<Track> =
        albumIds.flatMap { id ->
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
     * An artist's most popular songs, in popularity order: a short top-tracks
     * list (Navidrome's comes from its Last.fm agent), not the discography.
     *
     * Kept in the source's database, so an artist's page opens with it, offline
     * too (narrowed to downloads like every list). Popularity drifts over weeks,
     * so a stored list is served as it stands and refreshed in the background
     * once older than [TOP_SONGS_TTL_MS]. Only ids are stored, matched back onto
     * the library's rows — see TopSongEntity.
     *
     * An empty answer is never stored: "ranks nothing" and "couldn't be reached"
     * look the same here, and kept, a moment's failure would stay.
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
            // Stored, but none of it playable here (offline, nothing downloaded).
            // Online, ask again rather than show nothing.
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
        // Fresh from the server, a song the library doesn't hold is shown this
        // once — it plays through the server like any other.
        val result = answered.map { byId[it.id] ?: it }
        topSongs[name] = result
        return result
    }

    /**
     * A radio seeded by [seed]: the song itself, then what the server thinks
     * follows from it. Server rows are swapped for cached ones so likes, plays
     * and downloads agree with the rest of the UI, and the seed can't play twice.
     * Empty when the server has nothing: the caller says so rather than shuffling
     * the library and calling it radio.
     */
    suspend fun radioFrom(seed: Track): List<Track> {
        val client = serverClient.value ?: return emptyList()
        val answered = client.getSimilarSongs(seed.id, RADIO_LENGTH)
        // Matched after the answer, on only the songs it named: a whole-library map
        // made before the call would hold that library for as long as the server took.
        val named = answered.mapTo(HashSet()) { it.id }
        val byId = tracks.value.filter { it.id in named }.associateBy { it.id }
        val similar = answered
            .filter { it.id != seed.id }
            .distinctBy { it.id }
            .map { byId[it.id] ?: it }
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

    // --- Likes, ratings and plays --------------------------------------------

    /**
     * The server, when it may be spoken to *and* the mode allows the bytes; null
     * otherwise, so a like or rating is queued rather than sent over a link the
     * mode forbids (as PlaybackController queues plays).
     */
    private fun reachableClient(): MusicServer? =
        if (metadataAllowed()) serverClient.value else null

    // One like or rating at a time, in the order tapped: side by side, two taps
    // on the same heart could reach the server the wrong way round.
    private val likeWrites = Mutex()

    /** How many times each thing has been liked or rated — see [mirror]. */
    private val taps = ConcurrentHashMap<String, Int>()

    /**
     * The server's half of a like or a rating the phone already shows.
     *
     * Saved: anything older still queued for the same thing is dropped, since
     * sent later it would undo this. Unreachable: queued for when the server is
     * back. Refused: the phone's half is taken back with [undo] (as the next sync
     * would, silently) and said, when [say] — only while no later tap on the same
     * thing has replaced this one, which must not be taken back with it.
     *
     * [client] is the one there at the tap, like the dao [undo] writes to: a
     * source switched away from meanwhile still gets its own answer.
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
     * Like or unlike a song. The local row is the truth the moment it's tapped,
     * and stays so when the server can't be told — it is told later. A server
     * that answers no is different: that like is taken back and said — see [mirror].
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
     * Like an artist: stored by name (the app's artists come from track tags) and
     * mirrored to the server's artist of that name. A name the server has no
     * artist for (a featured guest, a tag-only spelling) is refused: it could
     * never be told, and the next sync would drop it unseen.
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
     * Server-side id for an artist name, from a lazily built index. A name the
     * index lacks has it read again (the artist may be newer); a failed read
     * throws, so it counts as unreachable rather than as no such artist. The
     * index is the active source's: a write for a source switched away from
     * asks its own server and leaves the index alone.
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

    /**
     * Rate a song or album, 0 to clear: applied locally at once and queued when
     * the server can't be reached, as a like is. One the server refuses goes back
     * to [previous], and the answer is the caller's to say — see [mirror].
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
     * Note a play against the cached row, so Recently Played is right at once;
     * the next sync brings the server's own figures, other clients' plays included.
     */
    suspend fun markPlayed(trackId: String) {
        dao.markPlayed(trackId, System.currentTimeMillis())
    }

    /**
     * Send everything that happened while the server couldn't be told — through
     * [reachableClient], so a queue held back by the mode isn't sent the moment
     * this is called. Only this source's entries: the queue holds every source's.
     * Refusals are said once, however many; the log has the rest.
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

    // --- Search --------------------------------------------------------------

    /**
     * Folded search text for every track ("olafur" must reach "Ólafur Arnalds"),
     * cached on the list instance because folding the whole library per
     * keystroke is visibly slow. Kept only while that list is the library: it
     * holds every song in it.
     */
    @Volatile private var haystack: Pair<List<Track>, List<String>>? = null

    init {
        scope.launch { tracks.collect { list -> if (haystack?.first !== list) haystack = null } }
    }

    private fun haystack(list: List<Track>): List<String> {
        haystack?.let { (forList, hay) -> if (forList === list) return hay }
        val hay = list.map { titleKey(it.title + "\n" + it.artist + "\n" + it.album) }
        haystack = list to hay
        // A newer list may have arrived while this one was folded; then drop it here.
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
        // Albums and artists are far fewer than tracks, so they fold in place.
        val matchingAlbums = albums.value.filter {
            titleKey(it.title).contains(q) || titleKey(it.artist).contains(q)
        }.take(SEARCH_LIMIT)
        val matchingArtists = artists.value.filter {
            titleKey(it.name).contains(q)
        }.take(SEARCH_LIMIT)
        return SearchResults(matchingArtists, matchingAlbums, matchingTracks)
    }

    suspend fun clearCache() {
        dao.clearTracks()
        dao.clearAlbums()
        dao.clearAllTopSongs()
        dao.clearAllPlaylistTracks()
        forgetDerived()
    }

    /**
     * Throw away everything held in memory *about* the library, leaving the rows
     * alone — on a source switch. None of it is keyed by source, so kept it would
     * answer for the new one: [topSongs] by artist name, the playlists (held in
     * memory, not in the database that switches), the memos.
     *
     * Separate from [clearCache], which also deletes rows: a switch must not
     * delete the rows of the source being switched *to*.
     */
    fun forgetDerived() {
        _playlists.value = emptyList()
        membership.value = emptyMap()
        staleMembership.value = emptySet()
        topSongs.clear()
        artistIds = null
        _artistImages.value = emptyMap()
        haystack = null
        // Not lastTracksRead: the database hasn't changed over yet, and a write
        // the cancelled sync lands first is read against it; trackRows drops it
        // when the lists change over. Each of these holds the source being left.
        albumSizes = null
        artistSizes = null
        trackIndex = null
        inViewIds = null
        unhidden = null
        // Stopped, these would still offer the last source's — see genres.
        tagValues.forEach { it.value = emptyList() }
    }

    companion object {
        /**
         * Bump when a change to how a track is *parsed* makes the cached rows
         * wrong — not for the server's own changes, which the incremental sync
         * catches. Each source then refetches every album's songs once (see
         * MusicSource.parserGeneration). 1: album artist and composer from
         * OpenSubsonic's display* fields; 2: multi-artist credits from its
         * structured `artists`.
         */
        const val TRACK_PARSER_GENERATION = 2

        /** Albums whose songs are written per transaction. */
        private const val SYNC_WRITE_BATCH = 40

        /** Album fetches in flight at once, in a sync and in priming playlists. */
        private const val SYNC_CONCURRENCY = 6

        /** How long an automatic sync waits after the last one finished; Sync Now and the background job mean "now". */
        private const val SYNC_MIN_INTERVAL_MS = 5L * 60 * 1000

        /** The same floor on a metered link, where an automatic sync costs the user data and their first minutes. */
        private const val SYNC_MIN_INTERVAL_METERED_MS = 6L * 60 * 60 * 1000

        /** How long Sync Now waits on a server scan before syncing anyway, and how often it asks. */
        private const val SCAN_WAIT_MS = 30_000L
        private const val SCAN_POLL_MS = 1_500L

        /** Rows per page of a library read: one CursorWindow fill, and the new objects held at once. See readTracks. */
        private const val TRACK_PAGE = 1_000

        /** At most one read of the library this often while its own sync writes. See pacedWhileSyncing. */
        private const val SYNC_REREAD_MS = 10_000L

        /** How long the tag lists go on after the last page showing them, so one page leading to the next doesn't start them over. */
        private const val TAG_VALUES_KEEP_MS = 5_000L

        private const val SEARCH_LIMIT = 100

        /** How many songs a radio asks the server for — Subsonic's own default. */
        private const val RADIO_LENGTH = 50

        /** How long a stored popular-songs list is served before being refreshed. */
        private const val TOP_SONGS_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Chronological by release date; albums with no date sort to the end. */
        private val RELEASE_ORDER: Comparator<Album> = compareBy(
            { if (it.releaseDate == 0L) Long.MAX_VALUE else it.releaseDate },
            { it.title.lowercase() },
        )

        private val TRACK_ORDER: Comparator<Track> =
            compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title.lowercase() })
    }
}

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
 * A walk that has gone round rests for [WALK_REST_MS], or a server that can't
 * say anything (or scans on a schedule, so always "may have changed") would
 * keep one going for ever.
 *
 * A server that names what changed but misses some asks for a walk as well
 * ([SongChanges.Albums.alsoWalk]): its named albums are read every sync and its
 * mark moves with them, while the walk goes on beside on its own cursor and
 * rest, settling no mark — the two share one [SongCheck] without undoing each other.
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
 * Plays this phone counted first are not news: markPlayed stamps them here at
 * once, and the server's figures never match the stamp (its own clock, a
 * scrobble on its way), so every album just heard would be rewritten. A count
 * higher than the cached one is a play somewhere else, and is.
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
 * Whether a failure was the server turning us away rather than not being there:
 * an unreachable server leaves only the downloads to show, a refused login
 * leaves the cached library fine and the credentials stale.
 *
 * Subsonic says so in its own error codes, with no status — see
 * ServerException.refusedLogin. Anything else is read from the message.
 */
internal fun isAuthFailure(e: Exception): Boolean {
    if ((e as? ServerException)?.refusedLogin == true) return true
    val code = STATUS_IN_MESSAGE.find(e.message.orEmpty())?.value?.toIntOrNull()
    return code == 401 || code == 403
}

private val STATUS_IN_MESSAGE = Regex("""\b(\d{3})\b""")

/**
 * A sync failure in words the person holding the phone can act on, short enough
 * for the one row that shows it. A 5xx says the server is down, not the app or
 * the login.
 */
internal fun syncErrorMessage(e: Exception): String {
    // Only the status the exception carries: a timeout's message names its URL,
    // and a `Container-Size=500` in it is not a 500.
    val code = (e as? ServerException)?.status
    if ((e as? ServerException)?.refusedLogin == true) return LOGIN_REFUSED
    if (code == null && e is java.io.IOException) return "Couldn't reach the server"
    if (code == null) return e.message?.takeIf { it.length < 60 } ?: "Couldn't reach the server"
    return serverDownWords(code) ?: when (code) {
        401, 403 -> LOGIN_REFUSED
        404 -> "That library isn't on the server any more"
        else -> "The server said $code"
    }
}

private const val LOGIN_REFUSED = "The server refused the login. Sign in again in Sources."

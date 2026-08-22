package com.sublunar.amp.data

import com.sublunar.amp.data.db.AlbumEntity
import com.sublunar.amp.data.db.DownloadFile
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.LikedArtistEntity
import com.sublunar.amp.data.db.TopSongEntity
import com.sublunar.amp.data.db.toAlbum
import com.sublunar.amp.data.db.toEntity
import com.sublunar.amp.data.db.toTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
    /** Where likes and ratings go when the server can't be told about them. */
    private val pending: PendingActions,
    /** For the handful of things cached in preferences rather than in a table. */
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The database in use right now, for the imperative half of this class. */
    private val dao: LibraryDao get() = daos.value ?: error("Library database not ready")

    /** [daos] as a flow of tables, for the observed half. */
    private fun <T> observing(select: (LibraryDao) -> Flow<T>): Flow<T> =
        daos.flatMapLatest { current -> current?.let(select) ?: emptyFlow() }

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
        observing { it.observeDownloads() }
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
                val orphans = runCatching { d.orphanedTrackCount() }.getOrDefault(0)
                if (orphans > 0) {
                    android.util.Log.w("AmpSync", "dropping $orphans orphaned track(s) on open")
                    runCatching { d.deleteOrphanedTracks() }
                }
            }
        }
    }

    /**
     * Every cached track, whichever library it belongs to.
     *
     * The one mapping of the table; [allTracks] narrows this rather than
     * observing again, so choosing a library costs a filter and not a second
     * scan of ten thousand rows. Downloads read from here — what is on the disk
     * is on the disk regardless of which library is being browsed.
     */
    private val trackRows: StateFlow<List<Track>> =
        observing { it.observeTracks() }.map { rows -> rows.map { row -> row.toTrack() } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Which of the server's libraries is chosen, or null for all of them. */
    private val selectedLibrary: StateFlow<String?> =
        libraryId.stateIn(scope, SharingStarted.Eagerly, null)

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
        observing { it.observeAlbums() }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val allAlbums: StateFlow<List<Album>> =
        combine(albumRows, selectedLibrary) { rows, library ->
            rows.asSequence()
                .filter { library == null || it.libraryId == null || it.libraryId == library }
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
     * Downloading has to work from this, never from [tracks]: in Wi-Fi Only mode
     * (or whenever the server is unreachable) [tracks] narrows to what is already
     * downloaded, so feeding it to the downloader asks it to fetch the things it
     * has already fetched — the queue comes out empty and downloads stop dead.
     */
    val fullTracks: StateFlow<List<Track>> get() = allTracks

    /** The same libraries' albums — see [downloadableTracks]. */
    val downloadableAlbums: StateFlow<List<Album>> =
        combine(albumRows, settings.activeSource) { rows, source ->
            val hidden = source?.hiddenLibraryIds?.filterNotNull()?.toSet().orEmpty()
            rows.asSequence()
                .filter { hidden.isEmpty() || it.libraryId == null || it.libraryId !in hidden }
                .map { it.toAlbum() }
                .toList()
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Everything in the libraries kept on the Sources page — what the downloader
     * works from.
     *
     * Downloads used to have a library setting of their own, which restated a
     * choice already made a level up and could disagree with it. Hiding a
     * library there says you are not interested in it; fetching it anyway, in
     * the background, onto a phone with a size limit, is not a reading of that.
     *
     * Not [allTracks]: that narrows to the one library being browsed, and what
     * to *keep* is a wider question than what to look at right now.
     */
    val downloadableTracks: StateFlow<List<Track>> =
        combine(trackRows, albumRows, settings.activeSource) { rows, albums, source ->
            val hidden = source?.hiddenLibraryIds?.filterNotNull()?.toSet().orEmpty()
            if (hidden.isEmpty()) {
                rows
            } else {
                val allowed = albums.asSequence()
                    .filter { it.libraryId == null || it.libraryId !in hidden }
                    .mapTo(HashSet()) { it.id }
                rows.filter { it.albumId == null || it.albumId in allowed }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
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
    private var albumSizesFor: List<Track>? = null
    private var albumSizes: Map<String, Int> = emptyMap()

    private fun albumSizes(list: List<Track>): Map<String, Int> {
        if (albumSizesFor === list) return albumSizes
        albumSizes = list.mapNotNull { it.albumId }.groupingBy { it }.eachCount()
        albumSizesFor = list
        return albumSizes
    }

    /**
     * Albums whose every cached track is downloaded — drives the download badge.
     *
     * Counts downloaded rows per album and compares against the album's size, so
     * the per-download cost is the number of downloads rather than the size of the
     * library, and no track lists are allocated.
     */
    val downloadedAlbumIds: StateFlow<Set<String>> =
        combine(allTracks, downloadFiles) { list, rows ->
            if (rows.isEmpty()) return@combine emptySet()
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
    private var artistSizesFor: List<Track>? = null
    private var artistSizes: Map<String, Int> = emptyMap()

    private fun artistSizes(list: List<Track>): Map<String, Int> {
        if (artistSizesFor === list) return artistSizes
        artistSizes = list.flatMap { it.albumArtistNames() }.groupingBy { it }.eachCount()
        artistSizesFor = list
        return artistSizes
    }

    /**
     * Artists whose every cached track is downloaded — drives the artist list's
     * download badge, the same way [downloadedAlbumIds] drives the album one.
     */
    val downloadedArtistNames: StateFlow<Set<String>> =
        combine(allTracks, downloadFiles) { list, rows ->
            if (rows.isEmpty()) return@combine emptySet()
            val sizes = artistSizes(list)
            // The shared index, not one of its own: this recomputes every time a
            // download lands, and building a ten-thousand-entry map per finished
            // track is exactly what that memo exists to avoid.
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

    // Track lookup for the Downloads page, memoised on the library list instance so
    // a completed download doesn't rebuild a ten-thousand-entry map.
    private var trackIndexFor: List<Track>? = null
    private var trackIndex: Map<String, Track> = emptyMap()

    private fun trackIndex(list: List<Track>): Map<String, Track> {
        if (trackIndexFor === list) return trackIndex
        trackIndex = list.associateBy { it.id }
        trackIndexFor = list
        return trackIndex
    }

    /** Downloaded tracks, newest first — the Downloads page. */
    val downloads: Flow<List<Track>> =
        // Every library's, not the chosen one's: this page is about what is on
        // the phone, and a file does not stop being downloaded because you are
        // looking at a different library.
        combine(downloadFiles, trackRows) { rows, library ->
            val byId = trackIndex(library)
            // Sorted here rather than in SQL: this flow is cold, so the ordering
            // is done when the Downloads page is open instead of on every read
            // of the shared list.
            rows.sortedByDescending { it.downloadedAtMs }.mapNotNull { byId[it.trackId] }
        }

    /**
     * The tag values this library actually uses.
     *
     * Derived from the cache rather than fetched: `getGenres` exists, but there
     * is no equivalent for composers, and a list built from the tracks on hand is
     * the same answer for all three. Empty means the server doesn't fill that
     * field — which is how the More page decides whether to offer it at all.
     */
    val genres: StateFlow<List<String>> =
        tracksFlowOf { it.genre }.stateIn(scope, SharingStarted.Lazily, emptyList())

    val composers: StateFlow<List<String>> =
        tracksFlowOf { it.composer }.stateIn(scope, SharingStarted.Lazily, emptyList())

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
     * fact about what is on screen. The downloader reads this for that reason —
     * see App.topUpDownloads.
     */
    val likedArtistNames: StateFlow<Set<String>> =
        observing { it.observeLikedArtists() }.map { names -> names.toSet() }
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
        val client = serverClient.value ?: return
        val refs = runCatching { client.getArtistIndex(libraryId.first()) }.getOrNull() ?: return
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
     * Whether playlists live on this phone rather than on a server.
     *
     * The local source has no server to ask, exactly as with the library scan —
     * see [runLocalScan]. Playlists are files in the music folder instead; see
     * [LocalPlaylists].
     */
    private suspend fun playlistsAreLocal(): Boolean =
        settings.activeSource.first()?.kind == SourceKind.LOCAL

    suspend fun refreshPlaylists() {
        val sourceId = settings.activeSource.first()?.id ?: return
        if (playlistsAreLocal()) {
            // Read straight off the disk every time: the files are the truth,
            // and another app (or an adb push) may have changed them.
            _playlists.value = LocalPlaylists.list()
            return
        }
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
    suspend fun playlistTracks(id: String): List<Track> {
        val tracks = fetchPlaylistTracks(id)
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
        val downloaded = downloadedTrackIds.value
        return if (offline.value) tracks.filter { it.id in downloaded } else tracks
    }

    private suspend fun fetchPlaylistTracks(id: String): List<Track> {
        if (playlistsAreLocal()) return getTracksByIds(LocalPlaylists.trackIds(id))
        serverClient.value?.let { client ->
            runCatching { client.getPlaylistTracks(id) }.getOrNull()?.let { return it }
        }
        val ids = (_playlists.value.firstOrNull { it.id == id } ?: return emptyList()).trackIds
        return getTracksByIds(ids)
    }

    /**
     * Fills the membership cache for [id] if it isn't already known.
     *
     * Talks to the server directly rather than through [playlistTracks], and
     * skips the cache write on failure — [playlistTracks]'s offline rebuild is
     * the right fallback for a screen that must show *something*, but caching
     * that guess here would freeze a transient failure as "confirmed empty"
     * for good, since this only ever runs once per id.
     */
    suspend fun primePlaylistTrackIds(id: String) {
        if (id in _playlistTrackIds.value) return
        if (playlistsAreLocal() || serverClient.value == null) {
            playlistTracks(id)
            return
        }
        val tracks = runCatching { serverClient.value?.getPlaylistTracks(id) }.getOrNull() ?: return
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
        val gate = Semaphore(SYNC_CONCURRENCY)
        ids.filterNot { it in _playlistTrackIds.value }
            .map { id -> launch { gate.withPermit { primePlaylistTrackIds(id) } } }
            .joinAll()
    }

    /** Playlists whose every known track is downloaded — drives the download badge. */
    val downloadedPlaylistIds: StateFlow<Set<String>> =
        combine(_playlistTrackIds, downloadedTrackIds) { membership, downloaded ->
            membership.entries.mapNotNullTo(mutableSetOf()) { (playlistId, ids) ->
                playlistId.takeIf { ids.isNotEmpty() && ids.all { trackId -> trackId in downloaded } }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    suspend fun createPlaylist(name: String) = createPlaylist(name, emptyList())

    /**
     * [trackIds] are the playlist's opening contents, not a hint — see
     * [MusicServer.createPlaylist]. Callers must not add them again.
     */
    suspend fun createPlaylist(name: String, trackIds: List<String>) {
        if (playlistsAreLocal()) {
            LocalPlaylists.create(name.trim(), trackIds)
        } else {
            runCatching { serverClient.value?.createPlaylist(name.trim(), trackIds) }
        }
        refreshPlaylists()
    }

    suspend fun renamePlaylist(id: String, name: String) {
        if (playlistsAreLocal()) {
            LocalPlaylists.rename(id, name.trim())
        } else {
            runCatching { serverClient.value?.renamePlaylist(id, name.trim()) }
        }
        refreshPlaylists()
    }

    suspend fun deletePlaylist(id: String) {
        if (playlistsAreLocal()) {
            LocalPlaylists.delete(id)
        } else {
            runCatching { serverClient.value?.deletePlaylist(id) }
        }
        refreshPlaylists()
    }

    /**
     * Append one track. Sequential by contract: the server's update appends, so
     * concurrent calls would race the order.
     */
    suspend fun addToPlaylist(id: String, trackId: String) {
        if (playlistsAreLocal()) {
            LocalPlaylists.add(id, trackId)
        } else {
            runCatching { serverClient.value?.addToPlaylist(id, trackId) }
        }
    }

    suspend fun removeFromPlaylistAt(id: String, index: Int) {
        if (playlistsAreLocal()) {
            LocalPlaylists.removeAt(id, index)
        } else {
            runCatching { serverClient.value?.removeFromPlaylistAt(id, index) }
        }
    }

    suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>) {
        if (playlistsAreLocal()) {
            LocalPlaylists.reorder(id, orderedSongIds)
        } else {
            runCatching { serverClient.value?.reorderPlaylist(id, orderedSongIds) }
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

    /** The actual scoped sync (null = all libraries); callers serialize via [syncMutex]. */
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
    /**
     * Whether a failure was the server turning us away rather than not being
     * there at all.
     *
     * The difference matters: an unreachable server means the library can only
     * show what is downloaded, while a refused login means the library is fine
     * and the credentials are stale. Treating the second as the first hides a
     * perfectly good cached library and says nothing about why.
     */
    private fun isAuthFailure(e: Exception): Boolean {
        val code = Regex("""\b(\d{3})\b""").find(e.message.orEmpty())?.value?.toIntOrNull()
        return code == 401 || code == 403
    }

    private fun syncErrorMessage(e: Exception): String {
        val code = Regex("""\b(\d{3})\b""").find(e.message.orEmpty())?.value?.toIntOrNull()
        return when {
            code == null -> e.message?.takeIf { it.length < 60 } ?: "Couldn't reach the server"
            // 52x are Cloudflare's: it answered, the server behind it didn't.
            code in 520..529 -> "Your server isn't responding ($code)"
            code in 500..599 -> "The server had an error ($code)"
            code == 401 || code == 403 -> "The server refused the login"
            code == 404 -> "That library isn't on the server any more"
            else -> "The server said $code"
        }
    }

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
        val found = PlexAccount.firstReachable(ordered, source.token) ?: return false
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
        val dao = daos.value ?: return
        // The phone's own music has no server to ask; it is read off the disk.
        if (source?.kind == SourceKind.LOCAL) {
            runLocalScan(dao)
            return
        }
        // A build that changed how a track is read has to refill the cache once,
        // because the incremental filter below can only see what the *server*
        // changed — see MusicSource.parserGeneration.
        val refillForParser =
            source != null && source.parserGeneration != TRACK_PARSER_GENERATION
        val client = serverClient.value ?: return
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
        try {
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
            val folderIds: List<String?> = if (musicFolderId != null) {
                listOf(musicFolderId)
            } else {
                client.getMusicFolders().map { it.id }.ifEmpty { listOf(null) }
            }

            val starred = folderIds.map { client.getStarred(it) }.mergeStarred()

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
                album.copy(liked = album.id in starred.albumIds)
            }
            val libraryOf = fetched.associate { (album, folder) -> album.id to folder }
            val serverAlbumById = serverAlbums.associateBy { it.id }

            val cached = dao.allAlbumsSnapshot().associateBy { it.id }
            android.util.Log.i(
                "AmpSync",
                "scope=$musicFolderId cached rows by library tag: " +
                    cached.values.groupBy { it.libraryId }.mapValues { it.value.size },
            )

            dao.upsertAlbums(serverAlbums.map { it.toEntity(libraryOf[it.id]) })

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
                val inScope = if (musicFolderId == null) {
                    cached
                } else {
                    cached.filterValues { it.libraryId == musicFolderId }
                }
                val staleAlbumIds = inScope.keys - serverAlbumById.keys
                if (staleAlbumIds.isNotEmpty()) {
                    staleAlbumIds.forEach { dao.deleteTracksForAlbum(it) }
                    dao.deleteAlbums(staleAlbumIds.toList())
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
                }
            }

            // Only new or changed albums need their songs re-fetched — but "changed"
            // has to include "its songs aren't actually here". Albums are written
            // before their songs, so a sync interrupted in between leaves the album
            // rows looking complete while the tracks are missing; comparing only
            // against the album cache then skips them forever and the library never
            // recovers. Checking the real per-album track count makes it self-heal.
            val cachedTrackCounts = dao.trackCountsByAlbum().associate { it.albumId to it.tracks }
            val toFetch = if (refillForParser) serverAlbums else serverAlbums.filter { album ->
                val prev = cached[album.id]
                val cachedCount = cachedTrackCounts[album.id] ?: 0
                prev == null ||
                    prev.songCount != album.songCount ||
                    cachedCount != album.songCount ||
                    // A server that doesn't tell us how many songs an album has
                    // (Plex often leaves leafCount off) would otherwise match 0
                    // against 0 for ever, and those albums would stay empty no
                    // matter how many times the library was synced. Nothing
                    // cached always means fetch.
                    cachedCount == 0
            }

            // Counted in albums, because that is what it is walking — one
            // request per album for its songs. Labelled "Songs" it read as a
            // song count, and a library with more songs than albums then looks
            // like it is being cut short.
            _syncState.value = _syncState.value.copy(phase = "Albums 0/${toFetch.size}")
            fetchAndStoreSongs(dao, client, toFetch, starred.songIds, libraryOf)

            // Reconcile track likes across the whole library (cheap two-query pass).
            dao.replaceTrackLikes(starred.songIds.toList())

            dao.replaceLikedArtists(starred.artistNames.map { LikedArtistEntity(it) })

            // Only now: a run that threw part-way leaves the old number in place
            // so the refill is attempted again rather than being marked done.
            if (refillForParser && source != null) {
                settings.setParserGeneration(source.id, TRACK_PARSER_GENERATION)
            }

            _syncState.value = SyncState(
                syncing = false,
                lastSyncedMs = System.currentTimeMillis(),
            )
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
    private suspend fun runLocalScan(dao: LibraryDao) {
        _syncState.value = _syncState.value.copy(
            syncing = true,
            error = null,
            phase = "Reading this phone",
        )
        try {
            if (!LocalLibrary.permitted()) {
                _syncState.value = SyncState(
                    syncing = false,
                    error = "Allow music access to read this phone",
                )
                return
            }
            val scan = LocalLibrary.scan { count ->
                _syncState.value = _syncState.value.copy(phase = "$count tracks")
            }
            dao.clearTracks()
            dao.clearAlbums()
            dao.upsertAlbums(scan.albums.map { it.toEntity() })
            dao.upsertTracks(scan.tracks.map { it.toEntity() })
            _syncState.value = SyncState(
                syncing = false,
                lastSyncedMs = System.currentTimeMillis(),
            )
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
    ) = coroutineScope {
        val gate = Semaphore(SYNC_CONCURRENCY)
        val done = AtomicInteger(0)
        // Fetch concurrently, but write in batches: one transaction per album meant
        // ~1300 invalidations of the tracks table across a full sync, each one
        // re-running every observer's whole-table query while rows moved underneath
        // it. Network stays parallel; the database sees a handful of swaps.
        albums.chunked(SYNC_WRITE_BATCH).forEach { batch ->
            val fetched = batch.map { album ->
                async {
                    gate.withPermit {
                        val songs = try {
                            client.getAlbumTracks(album.id)
                        } catch (_: Exception) {
                            emptyList()
                        }.map { it.copy(liked = it.id in starredSongIds) }
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
            dao.replaceAlbumTracks(
                batch.map { it.id },
                fetched.flatten().map { it.toEntity() },
            )
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
            if (counted.isNotEmpty()) dao.upsertAlbums(counted.map { it.toEntity(libraryOf[it.id]) })
        }
    }

    fun syncInBackground() {
        syncJob = scope.launch { sync() }
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
            val asked = client != null &&
                runCatching { client.startServerScan(folder) }.getOrDefault(false)
            // Said plainly rather than left to look like an empty scan. On Plex
            // this is what a library someone shared with you does — scanning
            // belongs to whoever owns the server.
            scanRefused = client != null && !asked
            if (asked) {
                var waited = 0L
                while (waited < SCAN_WAIT_MS) {
                    delay(SCAN_POLL_MS)
                    waited += SCAN_POLL_MS
                    val busy = runCatching { client.serverScanning(folder) }.getOrDefault(false)
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
        val byId = dao.tracksByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toTrack() }
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
        val byId = tracks.value.associateBy { it.id }
        val answered = client.getSimilarSongs(seed.id, RADIO_LENGTH)
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
     * Like or unlike a song.
     *
     * The local row is the truth the moment it's tapped, and stays that way even
     * if the server can't be told: undoing the user's action because the train
     * went into a tunnel is worse than telling Navidrome about it later.
     */
    suspend fun setTrackLiked(track: Track, liked: Boolean) {
        dao.setTrackLiked(track.id, liked)
        val client = serverClient.value
        val kind = if (liked) PendingAction.Kind.STAR_SONG else PendingAction.Kind.UNSTAR_SONG
        if (client == null) {
            pending.add(PendingAction(kind, track.id))
            return
        }
        try {
            if (liked) client.starSong(track.id) else client.unstarSong(track.id)
        } catch (_: Exception) {
            pending.add(PendingAction(kind, track.id))
        }
    }

    /**
     * Like an artist. Stored locally by name (our Artist rows are derived from
     * track tags), and mirrored to the server when that name resolves to a real
     * server-side artist so other clients see the same favourites.
     */
    suspend fun setArtistLiked(name: String, liked: Boolean) {
        if (liked) dao.likeArtists(listOf(LikedArtistEntity(name))) else dao.unlikeArtist(name)
        val kind = if (liked) PendingAction.Kind.STAR_ARTIST else PendingAction.Kind.UNSTAR_ARTIST
        val client = serverClient.value
        if (client == null) {
            pending.add(PendingAction(kind, name))
            return
        }
        try {
            val id = artistIdFor(name, client) ?: return
            if (liked) client.starArtist(id) else client.unstarArtist(id)
        } catch (_: Exception) {
            pending.add(PendingAction(kind, name))
        }
    }

    /** Server-side id for a display name, from a lazily built index. */
    private suspend fun artistIdFor(name: String, client: MusicServer): String? {
        val ids = artistIds ?: client.getArtistIndex()
            .associate { it.name to it.id }
            .also { artistIds = it }
        return ids[name]
    }

    suspend fun setAlbumLiked(album: Album, liked: Boolean) {
        dao.setAlbumLiked(album.id, liked)
        val kind = if (liked) PendingAction.Kind.STAR_ALBUM else PendingAction.Kind.UNSTAR_ALBUM
        val client = serverClient.value
        if (client == null) {
            pending.add(PendingAction(kind, album.id))
            return
        }
        try {
            if (liked) client.starAlbum(album.id) else client.unstarAlbum(album.id)
        } catch (_: Exception) {
            pending.add(PendingAction(kind, album.id))
        }
    }

    // --- Search --------------------------------------------------------------

    /**
     * Folded search text for every track, rebuilt only when the library changes.
     *
     * Matching has to compare folded-to-folded, or typing "olafur" can never reach
     * "Ólafur Arnalds". Folding the query is free; folding the haystack is not, so
     * doing it per keystroke across ten thousand tracks would be visibly slow —
     * hence the cache, keyed on the list instance the flow last emitted.
     */
    private var haystackFor: List<Track>? = null
    private var haystack: List<String> = emptyList()

    private fun haystack(list: List<Track>): List<String> {
        if (haystackFor === list) return haystack
        haystack = list.map { titleKey(it.title + "\n" + it.artist + "\n" + it.album) }
        haystackFor = list
        return haystack
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
     * Applied locally either way and queued when the server can't be reached, so
     * rating a record on a plane behaves like liking one does.
     */
    suspend fun setRating(id: String, stars: Int, isAlbum: Boolean): Boolean {
        if (isAlbum) dao.setAlbumRating(id, stars) else dao.setTrackRating(id, stars)
        val kind = if (isAlbum) PendingAction.Kind.RATE_ALBUM else PendingAction.Kind.RATE_SONG
        val client = serverClient.value
        if (client == null || !client.setRating(id, stars)) {
            pending.add(PendingAction(kind, id, value = stars))
        }
        return true
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

    /** Send everything that happened while the server was unreachable. */
    suspend fun flushPending(): Int {
        val client = serverClient.value ?: return 0
        return pending.flush(client) { name -> runCatching { artistIdFor(name, client) }.getOrNull() }
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
        haystackFor = null
        haystack = emptyList()
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
private const val RADIO_LENGTH = 50

/** How long a stored popular-songs list is served before being refreshed. */
private const val TOP_SONGS_TTL_MS = 7L * 24 * 60 * 60 * 1000

package com.sublunar.amp.data

import com.sublunar.amp.data.db.AlbumEntity
import com.sublunar.amp.data.db.DownloadFile
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.LikedArtistEntity
import com.sublunar.amp.data.db.MAX_BOUND_IDS
import com.sublunar.amp.data.db.TopSongEntity
import com.sublunar.amp.data.db.toAlbum
import com.sublunar.amp.data.db.toEntity
import com.sublunar.amp.data.db.toTrack
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One read of the chosen library's songs and what the badges and the playlist
 * narrowing work out from it, each on first use and then kept for as long as
 * this is the library. Held in [LibraryRepository.indexed], so it lets go of
 * its songs when the next read replaces it.
 */
internal class Indexed(val tracks: List<Track>) {
    /** How many tracks each album has. */
    val albumSizes: Map<String, Int> by lazy { tracks.mapNotNull { it.albumId }.groupingBy { it }.eachCount() }

    /** How many tracks each album artist has. */
    val artistSizes: Map<String, Int> by lazy { tracks.flatMap { it.albumArtistNames() }.groupingBy { it }.eachCount() }

    val byId: Map<String, Track> by lazy { tracks.associateBy { it.id } }

    val ids: Set<String> by lazy { tracks.mapTo(HashSet()) { it.id } }
}

data class SearchResults(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/**
 * The library as the screens read it: lists observed from the active source's
 * Room cache (instant on launch, kept fresh by [LibrarySync]), reads of albums,
 * artists and radio, likes and ratings, and search. Playlists are
 * [LibraryPlaylists]'.
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
    /** The persisted library choice, read as stored — see LibrarySync's. */
    private val libraryId: Flow<String?>,
    /** True when only downloaded media should be visible (offline / Wi-Fi only). */
    offlineOnly: Flow<Boolean>,
    /** Whether the server may be spoken to at all — see App.metadataAllowed. */
    private val metadataAllowed: () -> Boolean,
    /** Where likes and ratings go when the server can't be told about them. */
    private val pending: PendingActions,
    /** Puts a line in front of the user — a write the server refused, after its screen has gone. See App.tell. */
    private val tell: (String) -> Unit,
    /** For the handful of things cached in preferences rather than in a table. */
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    /** What the sync has written, and to which database — see [observing]. */
    private val sync: LibrarySync,
) {
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
     * Each list is also read afresh whenever [rereads] moves — see
     * LibrarySync.reread — without blanking: same source, so an empty list would
     * only flicker.
     *
     * A [paced] list is read at most once every [SYNC_REREAD_MS] while its own
     * source syncs — see [pacedWhileSyncing].
     */
    private fun <T> observing(
        empty: T,
        rereads: StateFlow<Int> = sync.reread,
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
            if (sync.syncingDao.value === dao) {
                withTimeoutOrNull(SYNC_REREAD_MS) {
                    combine(sync.syncingDao, localWrites) { syncing, n -> syncing !== dao || n != writes }.first { it }
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

    internal val offline: StateFlow<Boolean> =
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
            }
        }
    }

    /**
     * The last whole read of the tracks table, and the database it came from:
     * what the next read of the same database reuses unchanged songs from. Only
     * ever the browsed database's — [cachedTracks] drops it when the lists change
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
     * table, which [fullTracks] narrows by a filter rather than a second scan. A
     * playlist can list songs beyond the chosen library.
     *
     * Room says when to read: it re-counts the table after every write and hands
     * each count on, changed or not, so a count arriving is the signal and its
     * value only sizes the read. [readTracks] keeps the songs that didn't change.
     */
    val cachedTracks: StateFlow<List<Track>> =
        observing(emptyList(), sync.rereadTracks, paced = true) { dao ->
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
    internal val selectedLibrary: StateFlow<String?> =
        libraryId.stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The libraries the user hid, which are hidden everywhere — All Libraries,
     * search, the tabs, playlists — and not fetched by a sync. Their rows stay
     * cached, so showing one again is instant. See MusicSource.hiddenLibraryIds.
     */
    internal val hiddenLibraries: StateFlow<Set<String>> =
        settings.activeSource
            .map { it?.hiddenLibraries.orEmpty() }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** The album table as stored, library tags and all. */
    internal val albumRows: StateFlow<List<AlbumEntity>> =
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
     *
     * Ignores the offline narrowing. The download top-up's trigger keys on this,
     * never on [tracks], which offline narrows to what is downloaded and so moves
     * on the wrong changes — see App.boot.
     */
    val fullTracks: StateFlow<List<Track>> =
        combine(cachedTracks, allAlbums) { rows, albums ->
            val visible = albums.mapTo(HashSet()) { it.id }
            rows.filter { it.albumId == null || it.albumId in visible }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The library as the UI should see it: offline, narrowed to what's playable,
     * so browsing, search and queue-building all follow without each screen checking.
     */
    val tracks: StateFlow<List<Track>> =
        combine(fullTracks, downloadedTrackIds, offline) { list, downloaded, offlineOnlyNow ->
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

    /**
     * [fullTracks] with what is worked out from it, each part on first use — see
     * [Indexed]. Built again only when the library changes, so a completed
     * download costs the downloads, not a pass over the library.
     */
    internal val indexed: StateFlow<Indexed> =
        fullTracks.map(::Indexed).stateIn(scope, SharingStarted.Eagerly, Indexed(emptyList()))

    /**
     * Albums whose every cached track is downloaded — drives the download badge.
     * Counts downloaded rows per album against the album's size, so a download
     * costs the number of downloads, not the size of the library.
     */
    val downloadedAlbumIds: StateFlow<Set<String>> =
        combine(indexed, downloadFiles) { library, rows ->
            if (rows.isEmpty()) return@combine emptySet()
            val sizes = library.albumSizes
            val have = HashMap<String, Int>()
            for (row in rows) {
                val album = row.albumId ?: continue
                have[album] = (have[album] ?: 0) + 1
            }
            have.entries.mapNotNullTo(mutableSetOf()) { (album, n) ->
                album.takeIf { sizes[album] == n }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * Artists whose every cached track is downloaded — drives the artist list's
     * download badge, the same way [downloadedAlbumIds] drives the album one.
     */
    val downloadedArtistNames: StateFlow<Set<String>> =
        combine(indexed, downloadFiles) { library, rows ->
            if (rows.isEmpty()) return@combine emptySet()
            val sizes = library.artistSizes
            val byId = library.byId
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

    /**
     * Each artist's picture from the server's own record, keyed by name — filled
     * by [primeArtistImages]. Null until the server has been asked: an empty map
     * is a server with no artist pictures, not one to ask again.
     */
    private val _artistImages = MutableStateFlow<Map<String, String>?>(null)

    val artists: StateFlow<List<Artist>> =
        combine(tracks, likedArtistNames, _artistImages) { list, liked, images ->
            deriveArtists(list, liked, images.orEmpty())
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** One pass over every credit, tallied per name in the order names first appear. */
    private fun deriveArtists(
        list: List<Track>,
        liked: Set<String>,
        images: Map<String, String>,
    ): List<Artist> {
        val tallies = LinkedHashMap<String, ArtistTally>()
        for (track in list) {
            for (name in track.albumArtistNames()) {
                val tally = tallies.getOrPut(name, ::ArtistTally)
                track.albumId?.let { tally.albumIds += it }
                tally.tracks++
                tally.plays += track.playCount
            }
        }
        return tallies.map { (name, tally) ->
            Artist(
                name = name,
                imageId = images[name],
                albumCount = tally.albumIds.size,
                trackCount = tally.tracks,
                playCount = tally.plays,
                liked = name in liked,
            )
        }.sortedByKeyOnce { sortName(it.name) }
    }

    private class ArtistTally {
        val albumIds = HashSet<String>()
        var tracks = 0
        var plays = 0
    }

    /**
     * Fetch the server's artist index for its pictures, once per source: one
     * request, asked by the pages that show artists rather than by every sync.
     * Failure leaves the map as it was, and rows show their placeholder.
     */
    suspend fun primeArtistImages() {
        if (_artistImages.value != null) return
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        val refs = runCatching { client.getArtistIndex(libraryId.first()) }.noteOom("artist index").getOrNull() ?: return
        // The same call is the id index that liking needs.
        if (artistIds == null) artistIds = refs.associate { it.name to it.id }
        _artistImages.value = refs
            .mapNotNull { ref -> ref.imageId?.takeIf { it.isNotBlank() }?.let { ref.name to it } }
            .toMap()
    }

    // Whether there is anything liked, which is all the library index asks:
    // eager, so a page's first frame already knows.
    val hasLikedArtists: StateFlow<Boolean> =
        artists.map { list -> list.any { it.liked } }.stateIn(scope, SharingStarted.Eagerly, false)

    val hasLikedTracks: StateFlow<Boolean> =
        tracks.map { list -> list.any { it.liked } }.stateIn(scope, SharingStarted.Eagerly, false)

    val hasLikedAlbums: StateFlow<Boolean> =
        albums.map { list -> list.any { it.liked } }.stateIn(scope, SharingStarted.Eagerly, false)

    // Artist name -> server artist id, resolved once and reused for liking.
    private var artistIds: Map<String, String>? = null

    // Artist name -> popular songs, so revisiting an artist page doesn't ask the
    // server again, and the order stays stable within a session.
    private val topSongs = ConcurrentHashMap<String, List<Track>>()

    // --- Reads ---------------------------------------------------------------

    /** An album's tracks, from cache when present, otherwise fetched and cached. */
    suspend fun getAlbumTracks(albumId: String): List<Track> {
        // Both ends pinned, and the write dropped if the source changed meanwhile — see runSync.
        val pinned = dao
        val client = serverClient.value
        val cached = pinned.tracksForAlbum(albumId).map { it.toTrack() }
        if (cached.isNotEmpty()) return narrowedToDownloads(cached).sortedWith(TRACK_ORDER)
        if (client == null) return emptyList()
        val fresh = client.getAlbumTracks(albumId)
        if (daos.value === pinned) pinned.upsertTracks(fresh.map { it.toEntity() })
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
        val pinned = daos.value ?: return emptyList()
        val client = serverClient.value ?: return emptyList()
        val answered = runCatching { client.getTopSongs(name) }.getOrDefault(emptyList())
        if (answered.isEmpty()) return emptyList()
        // A switch meanwhile: the answer is the server left behind's, for neither
        // the new database nor the memo forgetDerived has just emptied.
        if (daos.value !== pinned) return emptyList()
        val now = System.currentTimeMillis()
        runCatching {
            pinned.replaceTopSongs(name, answered.mapIndexed { i, t -> TopSongEntity(name, i, t.id, now) })
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
        val answered = client.getSimilarSongs(seed.id)
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
     * The server's half of a like or a rating the phone already shows: sent,
     * or queued for when the server is back — see PendingActions.attempt. A
     * refusal takes the phone's half back with [undo] (as the next sync would,
     * silently) and says so, when [say] — only while no later tap on the same
     * thing has replaced this one, which must not be taken back with it.
     *
     * [client] is the one there at the tap, like the dao [undo] writes to: a
     * source switched away from meanwhile still gets its own answer.
     */
    private suspend fun mirror(
        action: PendingAction,
        client: MusicServer?,
        say: Boolean,
        undo: suspend () -> Unit,
    ): WriteResult {
        val key = "${action.sourceId}:${action.kind.group()}:${action.id}"
        val tap = taps.merge(key, 1, Int::plus)!!
        return likeWrites.withLock {
            val result = pending.attempt(action, client) { name -> client?.let { artistIdFor(name, it) } }
            if (client != null && result != WriteResult.Saved) {
                android.util.Log.w("AmpSync", "${action.kind} ${action.id} → $result")
            }
            if (result is WriteResult.Refused && taps[key] == tap) {
                undo()
                if (say) tell("Couldn't save\n${result.reason}")
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
        mirror(
            PendingAction(likeKind(LikeTarget.SONG, liked), track.id, sourceId = source), client, say = true,
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
        mirror(
            PendingAction(likeKind(LikeTarget.ARTIST, liked), name, sourceId = source), client, say = true,
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
        mirror(
            PendingAction(likeKind(LikeTarget.ALBUM, liked), album.id, sourceId = source), client, say = true,
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
        forgetDerived()
    }

    /**
     * Throw away everything held in memory *about* the library, leaving the rows
     * alone — on a source switch. None of it is keyed by source, so kept it would
     * answer for the new one: [topSongs] by artist name, the memos. The playlists
     * have their own — see LibraryPlaylists.forget.
     *
     * Separate from [clearCache], which also deletes rows: a switch must not
     * delete the rows of the source being switched *to*.
     */
    fun forgetDerived() {
        topSongs.clear()
        artistIds = null
        _artistImages.value = null
        haystack = null
        // Not lastTracksRead: the database hasn't changed over yet, and a write
        // the cancelled sync lands first is read against it; cachedTracks drops it
        // when the lists change over, and [indexed] goes with them.
        // Stopped, these would still offer the last source's — see genres.
        tagValues.forEach { it.value = emptyList() }
    }

    companion object {
        /** Rows per page of a library read: one CursorWindow fill, and the new objects held at once. See readTracks. */
        private const val TRACK_PAGE = 1_000

        /** At most one read of the library this often while its own sync writes. See pacedWhileSyncing. */
        private const val SYNC_REREAD_MS = 10_000L

        /** How long the tag lists go on after the last page showing them, so one page leading to the next doesn't start them over. */
        private const val TAG_VALUES_KEEP_MS = 5_000L

        private const val SEARCH_LIMIT = 100

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

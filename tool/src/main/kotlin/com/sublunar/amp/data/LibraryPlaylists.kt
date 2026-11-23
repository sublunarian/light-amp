package com.sublunar.amp.data

import com.sublunar.amp.data.db.AlbumEntity
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.MAX_BOUND_IDS
import com.sublunar.amp.data.db.PlaylistTrackEntity
import com.sublunar.amp.log.Log
import com.sublunar.amp.log.logName
import com.sublunar.amp.source.PhonePlaylists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json

/**
 * A playlist's songs: [all] of them in order, and which are [shown].
 *
 * A song is shown when the chosen library holds it and, offline, when it can
 * play. [tracks] is what a page lists and plays. Edits address [all]:
 * [LibraryPlaylists.removeFromPlaylistAt] takes an index into the *server's*
 * list, and [LibraryPlaylists.reorderPlaylist] takes an order that has to name
 * every song or the ones left out are the ones lost.
 *
 * [complete] is whether [all] is the server's own list rather than a rebuild
 * from cached membership the library no longer has every song for. A caller
 * that edits must check it first.
 */
data class PlaylistView(
    val all: List<Track>,
    val complete: Boolean,
    val shown: List<Boolean> = all.map { true },
    /**
     * Whether a page may edit it: a whole read, or — while edits are being kept
     * for later — the phone's own copy with every song found, which is what
     * those edits are made against.
     */
    val editable: Boolean = complete,
) {
    val tracks: List<Track> = all.filterIndexed { i, _ -> shown[i] }
}

/**
 * The active source's playlists: the list (cached in preferences), which songs
 * each holds (in memory, and in the source's playlist_tracks table), and the
 * writes, one at a time. Songs come from [library]'s cache.
 */
class LibraryPlaylists(
    private val daos: StateFlow<LibraryDao?>,
    private val serverClient: StateFlow<MusicServer?>,
    /** Whether the server may be spoken to at all — see App.metadataAllowed. */
    private val metadataAllowed: () -> Boolean,
    /** Whether bulk background fetching is worth the link now: false on a metered link outside Unrestricted. */
    private val bulkAllowed: () -> Boolean,
    /** Suspends while the player starts a stream, so background work steps aside — see App.yieldToPlayback. */
    private val yieldToPlayback: suspend () -> Unit,
    /** Puts a line in front of the user — a write the server refused, after its screen has gone. See App.tell. */
    private val tell: (String) -> Unit,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Edits made while the server couldn't be told — see [PlaylistEdits]. */
    private val edits = PlaylistEdits(settings)

    /**
     * Playlists made on the phone that have reached the server since, by their
     * phone id: a page opened before then still edits by the old one.
     */
    private val sentIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun current(id: String): String = sentIds[id] ?: id

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

    init {
        // Membership comes back with its database; forget has emptied the map by
        // now on a switch. After the state above, which a fast first read touches.
        scope.launch {
            daos.filterNotNull().collect { d -> seedPlaylistTrackIds(d) }
        }
    }

    /** [membership] without the stored copies still waiting to be re-read: what downloads go by. */
    fun freshPlaylistTrackIds(): Map<String, List<String>> {
        val stale = staleMembership.value
        return membership.value.filterKeys { it !in stale }
    }

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())

    /** Every playlist the source lists, whichever library is chosen: for its own pages, the picker, and priming. */
    val allPlaylists: StateFlow<List<Playlist>> get() = _playlists

    /**
     * The playlists as the UI should see them: every one, whichever library is
     * chosen — a playlist is something you made, and one that vanished or shrank
     * on a library switch would read as lost — narrowed only offline, as the
     * other three lists are.
     *
     * A playlist whose membership isn't known stays visible: an empty
     * [Playlist.trackIds] means "not asked yet" far more often than "empty", and
     * hiding those would hide downloaded, playable playlists.
     */
    val playlists: StateFlow<List<Playlist>> =
        combine(_playlists, membership, library.downloadedTrackIds, library.offline) {
                list, known, downloaded, offlineOnlyNow ->
            list.filter { playlist ->
                val ids = known[playlist.id] ?: playlist.trackIds
                !offlineOnlyNow || ids.isEmpty() || ids.any { it in downloaded }
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
        val fetched = if (!metadataAllowed()) {
            null
        } else {
            runCatching { serverClient.value?.getPlaylists() }
                .onFailure { if (it !is CancellationException) Log.w("AmpSync", "playlist list unread: ${it.logName()}") }
                .getOrNull()
        }
        if (fetched != null) {
            // The server's list as stored, and as the phone has it shown.
            val shown = withEdits(fetched, edits.read(sourceId), System.currentTimeMillis())
            _playlists.value = shown
            settings.setCachedPlaylists(sourceId, json.encodeToString(fetched))
            reconcileMembership(d, shown)
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
        val cached = runCatching {
            json.decodeFromString<List<Playlist>>(settings.cachedPlaylists(sourceId))
        }.getOrDefault(emptyList())
        return withEdits(cached, edits.read(sourceId), System.currentTimeMillis())
    }

    /** Whether edits to [id] are waiting to be sent: the server's copy of it is behind the phone's. */
    private suspend fun waiting(id: String): Boolean {
        if (isPhoneMade(id)) return true
        val sourceId = settings.activeSource.first()?.id ?: return false
        return edits.read(sourceId).any { it.playlistId == id }
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
        return present(d, id, fetchPlaylistTracks(id))
    }

    /**
     * What a playlist page shows over time: the stored copy at once, then the server's,
     * which takes half a minute over a whole library's worth of songs — see [playlistUpdates].
     * Only the server's waits out a save in progress; the stored copy is never editable.
     */
    fun playlistViews(id: String): Flow<PlaylistView> = flow {
        val d = daos.value
        val phone = phonePlaylists()
        if (phone != null) {
            awaitPlaylistWrites()
            emit(present(d, id, phonePlaylist(phone, id)))
            return@flow
        }
        playlistUpdates(
            stored = { rebuildPlaylist(id) },
            server = { awaitPlaylistWrites(); askServer(id) },
        ).collect { emit(present(d, id, it)) }
    }

    /**
     * Keep what [view] says about the playlist, then narrow it to what the
     * page can show: the chosen library's songs, and only what is on the phone
     * while the lists are narrowed to downloads. [d] is the database the
     * caller started with — see [storeMembership].
     */
    private suspend fun present(d: LibraryDao?, id: String, view: PlaylistView): PlaylistView {
        val (tracks, whole, _, editable) = view
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
        val downloaded = if (library.offline.value) library.downloadedTrackIds.value else null
        return PlaylistView(
            tracks,
            whole,
            tracks.map { inView(it) && (downloaded == null || it.id in downloaded || PhoneFiles.isPhoneFile(it.id)) },
            editable,
        )
    }

    /**
     * Which of [tracks] a playlist page shows: every song but a hidden library's,
     * whichever library is chosen (see [playlists]).
     *
     * Read from the database and the stored choice rather than the lists held
     * in memory, which are still loading when a playlist is opened at launch.
     */
    private suspend fun inLibraryView(d: LibraryDao?, tracks: List<Track>): (Track) -> Boolean {
        val source = settings.activeSource.first()
        val view = LibraryView(null, source?.hiddenLibraries.orEmpty())
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

    /** The songs, and whether they're the playlist entire rather than what could be recovered. */
    private suspend fun fetchPlaylistTracks(id: String): PlaylistView {
        awaitPlaylistWrites()
        phonePlaylists()?.let { return phonePlaylist(it, id) }
        return askServer(id) ?: rebuildPlaylist(id)
    }

    private suspend fun phonePlaylist(phone: PhonePlaylists, id: String): PlaylistView {
        val ids = phone.trackIds(id)
        val tracks = library.getTracksByIds(ids)
        return PlaylistView(tracks, tracks.size == ids.size)
    }

    /** The server's copy, or null when the mode forbids asking, the server is silent, or the request fails. */
    private suspend fun askServer(id: String): PlaylistView? {
        // Asked only when the mode allows, as the playlist list is.
        if (!metadataAllowed()) return null
        // Nor while the lists are down to downloads: the server was found silent, and
        // asking again would wait out the connect timeout.
        if (library.offline.value) return null
        // Behind the phone's copy until what's waiting is sent: the phone's is the one to show.
        if (waiting(id)) return null
        val client = serverClient.value ?: return null
        val askedAtMs = monotonicMs()
        val tracks = try {
            client.getPlaylistTracks(id)
        } catch (e: CancellationException) {
            // The page closing goes on. Otherwise it is the client closed under the
            // request, its source having moved: a failure like any other.
            currentCoroutineContext().ensureActive()
            Log.w("AmpSync", "playlist read cut off after ${monotonicMs() - askedAtMs} ms: its client closed")
            return null
        } catch (e: Exception) {
            Log.w("AmpSync", "playlist read failed after ${monotonicMs() - askedAtMs} ms: ${e.logName()}")
            return null
        }
        return PlaylistView(tracks, complete = true)
    }

    /** A playlist rebuilt from the membership already held, against the local library. */
    private suspend fun rebuildPlaylist(id: String): PlaylistView {
        // A rebuild is a guess at what the server holds — membership can be stale
        // and the library missing rows — so nothing built here is safe to edit
        // against. Held membership first: [Playlist.trackIds] is usually empty.
        val ids = membership.value[id]
            ?: (_playlists.value.firstOrNull { it.id == id } ?: return PlaylistView(emptyList(), false)).trackIds
        val tracks = library.getTracksByIds(ids)
        // Editable where edits will be kept rather than sent, and only with every
        // song found: positions into a list missing some would name others.
        val keeping = !metadataAllowed() || library.offline.value || waiting(id)
        return PlaylistView(tracks, complete = false, editable = keeping && tracks.size == ids.size)
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
                // In the app's scope, not the caller's, so a screen going
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
        // The phone's copy is ahead until what's waiting is sent.
        if (waiting(id)) return
        val d = daos.value
        awaitPlaylistWrites()
        val ids = runCatching { serverClient.value?.getPlaylistTrackIds(id) }.noteOom("playlist prime").getOrNull() ?: return
        storeMembership(d, id, ids)
    }

    /**
     * The same for a whole list, what the playlists tab needs: one request per
     * playlist (no server returns membership with the list), run in parallel
     * behind the sync's concurrency limit.
     */
    suspend fun primePlaylistTrackIds(ids: List<String>) = coroutineScope {
        // Every playlist's whole contents is the heaviest thing a launch does
        // after the sync, so on a metered link outside Unrestricted it waits for
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

    /** Playlists whose every shown song is downloaded — drives the download badge. */
    val downloadedPlaylistIds: StateFlow<Set<String>> =
        combine(membership, library.downloadedTrackIds, playlistShown()) { known, downloaded, shown ->
            known.entries.mapNotNullTo(mutableSetOf()) { (playlistId, ids) ->
                playlistId.takeIf { playlistMarked(ids, downloaded, shown) }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * The songs a playlist page shows: every library's but a hidden one's — see
     * [inLibraryView]. Null where every song shows.
     */
    private fun playlistShown(): Flow<Set<String>?> =
        combine(library.cachedTracks, library.albumRows, library.hiddenLibraries) { rows, albums, hidden ->
            if (hidden.isEmpty()) {
                unhidden = null
                null
            } else {
                unhiddenIds(rows, albums, hidden)
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

    suspend fun createPlaylist(name: String): WriteResult = createPlaylist(name, emptyList())

    /**
     * [trackIds] are the playlist's opening contents, not a hint — see
     * [MusicServer.createPlaylist]. Callers must not add them again.
     *
     * This and the writes after it say when they didn't land — see [said] — and
     * answer how it went, for a caller with more to do after.
     */
    suspend fun createPlaylist(name: String, trackIds: List<String>): WriteResult = playlistMutex.withLock {
        val files = phonePlaylists()
        val result = if (files != null) {
            fileWrite(files.create(name.trim(), trackIds) != null)
        } else {
            val d = daos.value
            var made = false
            sendOrKeep(
                d,
                PlaylistEdit(PlaylistEdit.Kind.CREATE, newPhoneMadeId(), name.trim(), trackIds),
                // Kept only if none of it reached the server, or a second copy would follow.
                keepable = { !made },
            ) { client ->
                // What was just saved is what the server holds: kept, the playlist opens at once.
                createInChunks(client, name.trim(), trackIds, onMade = { made = true }) { storeMembership(d, it, trackIds) }
            }
        }
        refreshPlaylists()
        said("createPlaylist", result)
    }

    suspend fun renamePlaylist(id: String, name: String): WriteResult = playlistMutex.withLock {
        val target = current(id)
        val result = playlistWrite(
            daos.value,
            PlaylistEdit(PlaylistEdit.Kind.RENAME, target, name = name.trim()),
            phone = { it.rename(target, name.trim()) },
            server = { it.renamePlaylist(target, name.trim()) },
        )
        refreshPlaylists()
        said("renamePlaylist", result)
    }

    suspend fun deletePlaylist(id: String): WriteResult = playlistMutex.withLock {
        val d = daos.value
        val target = current(id)
        val result = playlistWrite(
            d,
            PlaylistEdit(PlaylistEdit.Kind.DELETE, target),
            phone = { it.delete(target) },
            server = { it.deletePlaylist(target) },
            landed = { dropMembership(d, target) },
        )
        refreshPlaylists()
        said("deletePlaylist", result)
    }

    /**
     * Append one track. Sequential by contract: the server's update appends, so
     * concurrent calls would race the order — [playlistMutex] holds the contract
     * across calls, not just within one caller's loop.
     */
    suspend fun addToPlaylist(id: String, trackId: String): WriteResult = playlistMutex.withLock {
        val d = daos.value
        val target = current(id)
        val result = playlistWrite(
            d,
            PlaylistEdit(PlaylistEdit.Kind.ADD, target, trackIds = listOf(trackId)),
            phone = { it.add(target, trackId) },
            server = { it.addToPlaylist(target, listOf(trackId)) },
            landed = { editMembership(d, target) { it + trackId } },
        )
        said("addToPlaylist", result)
    }

    /**
     * Remove the entries [at] these positions in the playlist, each given with the
     * song expected there — see [MusicServer.removeFromPlaylistAt]. Whether they
     * all went: callers show the edit at once and take it back on a false.
     *
     * Kept for later, it goes as the whole list the phone has without them, so the
     * songs named must be where the page saw them in the phone's copy.
     */
    suspend fun removeFromPlaylistAt(id: String, at: Map<Int, String>): Boolean = playlistMutex.withLock {
        val d = daos.value
        val target = current(id)
        val after = membership.value[target]
            ?.takeIf { held -> at.all { (index, song) -> held.getOrNull(index) == song } }
            ?.filterIndexed { index, _ -> index !in at.keys }
        val edited = playlistEdit(
            "removeFromPlaylistAt",
            d,
            after?.let { PlaylistEdit(PlaylistEdit.Kind.SET, target, trackIds = it) },
            phone = { it.removeAt(target, at) },
            server = { it.removeFromPlaylistAt(target, at) },
        )
        when (edited) {
            Edited.SENT -> editMembership(d, target) { ids -> ids.filterIndexed { index, _ -> index !in at.keys } }
            Edited.KEPT -> Unit
            Edited.FAILED -> markMembershipStale(target)
        }
        edited != Edited.FAILED
    }

    /** Whether the new order landed — see [removeFromPlaylistAt]. */
    suspend fun reorderPlaylist(id: String, orderedSongIds: List<String>): Boolean = playlistMutex.withLock {
        val d = daos.value
        val target = current(id)
        // Kept only as the same songs the phone holds, moved: anything else would lose some.
        val keepAs = membership.value[target]
            ?.takeIf { held -> held.sorted() == orderedSongIds.sorted() }
            ?.let { PlaylistEdit(PlaylistEdit.Kind.SET, target, trackIds = orderedSongIds) }
        val edited = playlistEdit(
            "reorderPlaylist",
            d,
            keepAs,
            phone = { it.reorder(target, orderedSongIds) },
            server = { it.reorderPlaylist(target, orderedSongIds) },
        )
        // The new order *is* the new membership. A refusal leaves the server's
        // order unknown (Plex's can fail partway), so it is read again, and the
        // stored copy serves until then.
        when (edited) {
            Edited.SENT -> editMembership(d, target) { orderedSongIds }
            Edited.KEPT -> Unit
            Edited.FAILED -> markMembershipStale(target)
        }
        edited != Edited.FAILED
    }

    /** How an edit went: sent and landed, kept to send later, or neither. */
    private enum class Edited { SENT, KEPT, FAILED }

    /**
     * A playlist write, to the phone's playlist file or to the server, or kept as
     * [edit] for later — see [sendOrKeep]. [landed] is the phone's copy following
     * a write that went through; a kept one is followed by [keep] instead.
     */
    private suspend fun playlistWrite(
        d: LibraryDao?,
        edit: PlaylistEdit,
        phone: suspend (PhonePlaylists) -> Boolean,
        server: suspend (MusicServer) -> Unit,
        landed: suspend () -> Unit = {},
    ): WriteResult {
        val files = phonePlaylists()
        if (files != null) return fileWrite(phone(files)).also { if (it == WriteResult.Saved) landed() }
        var sent = false
        val result = sendOrKeep(d, edit) { client -> attemptWrite { server(client) }.also { sent = it == WriteResult.Saved } }
        if (sent) landed()
        return result
    }

    /**
     * A playlist edit that answers whether it landed. The client's own answer,
     * not merely "it didn't throw": some clients catch their transport errors, so
     * a refused write — or no client at all — would otherwise read as done. Kept
     * as [keepAs] where [sendOrKeep] would keep it; never kept without one.
     */
    private suspend fun playlistEdit(
        what: String,
        d: LibraryDao?,
        keepAs: PlaylistEdit?,
        phone: suspend (PhonePlaylists) -> Boolean,
        server: suspend (MusicServer) -> Boolean,
    ): Edited {
        val files = phonePlaylists()
        if (files != null) return if (phone(files)) Edited.SENT else Edited.FAILED
        val sourceId = settings.activeSource.first()?.id
        val client = serverClient.value
        if (sourceId != null && keepAs != null && (client == null || mustKeep(sourceId))) {
            keep(d, sourceId, keepAs)
            return Edited.KEPT
        }
        if (client == null) return Edited.FAILED
        var landed = false
        val result = attemptWrite { landed = server(client) }
        if (result is WriteResult.Saved && landed) return Edited.SENT
        Log.w("AmpSync", "$what → ${if (result is WriteResult.Saved) "refused" else result::class.simpleName}")
        if (result is WriteResult.Unreachable && sourceId != null && keepAs != null) {
            keep(d, sourceId, keepAs)
            return Edited.KEPT
        }
        return Edited.FAILED
    }

    /**
     * Send [edit] now through [send], or keep it for later: kept when the server
     * can't be asked or edits are already waiting that it mustn't overtake, and
     * when sending found the server unreachable and [keepable] says a resend is safe.
     */
    private suspend fun sendOrKeep(
        d: LibraryDao?,
        edit: PlaylistEdit,
        keepable: () -> Boolean = { true },
        send: suspend (MusicServer) -> WriteResult,
    ): WriteResult {
        val sourceId = settings.activeSource.first()?.id ?: return WriteResult.Unreachable(UNREACHABLE)
        val client = serverClient.value
        if (client == null || mustKeep(sourceId)) return keep(d, sourceId, edit)
        val result = send(client)
        return if (result is WriteResult.Unreachable && keepable()) keep(d, sourceId, edit) else result
    }

    /** Whether an edit can't go now: the server can't be asked, or edits it mustn't overtake are waiting. */
    private suspend fun mustKeep(sourceId: String): Boolean =
        !metadataAllowed() || library.offline.value || edits.read(sourceId).isNotEmpty()

    /**
     * Keep [edit] to send when the server is back, and show it on the phone now:
     * the phone has it, so it's saved. Said the first time something is kept.
     */
    private suspend fun keep(d: LibraryDao?, sourceId: String, edit: PlaylistEdit): WriteResult {
        val id = edit.playlistId
        when (edit.kind) {
            PlaylistEdit.Kind.CREATE, PlaylistEdit.Kind.SET -> storeMembership(d, id, edit.trackIds, fresh = false)
            PlaylistEdit.Kind.ADD -> editMembership(d, id) { it + edit.trackIds }
            PlaylistEdit.Kind.DELETE -> dropMembership(d, id)
            PlaylistEdit.Kind.RENAME -> Unit
        }
        _playlists.update { withEdits(it, listOf(edit), System.currentTimeMillis()) }
        if (edits.add(sourceId, edit)) tell(KEPT_NOTICE)
        // The server may be back already, with nothing yet to say so.
        if (metadataAllowed() && !library.offline.value) scope.launch { flushEdits() }
        return WriteResult.Saved
    }

    /**
     * Send the edits kept while the server couldn't be told, in order, stopping at
     * the first it doesn't answer. One it refuses is dropped with everything after
     * it for the same playlist, and said: it would get the same answer every time.
     */
    suspend fun flushEdits() {
        if (phonePlaylists() != null || !metadataAllowed()) return
        val sourceId = settings.activeSource.first()?.id ?: return
        val client = serverClient.value ?: return
        var sent = false
        var refusal: String? = null
        playlistMutex.withLock {
            val d = daos.value
            var queue = edits.read(sourceId)
            var going = true
            while (going && queue.isNotEmpty()) {
                // Another source since: its playlists aren't these.
                if (serverClient.value !== client || settings.activeSource.first()?.id != sourceId) return@withLock
                val edit = queue.first()
                val (result, instead) = send(client, d, edit)
                when (result) {
                    WriteResult.Saved -> {
                        sent = true
                        queue = instead + queue.drop(1)
                    }
                    is WriteResult.Unreachable -> {
                        queue = instead + queue.drop(1)
                        going = false
                    }
                    is WriteResult.Refused -> {
                        refusal = refusal ?: result.reason
                        Log.w("AmpSync", "kept ${edit.kind} refused")
                        queue = queue.drop(1).filterNot { it.playlistId == edit.playlistId }
                        markMembershipStale(edit.playlistId)
                    }
                }
                edits.write(sourceId, queue)
            }
        }
        if (sent || refusal != null) refreshPlaylists()
        refusal?.let { tell("Couldn't sync a playlist change made offline\n$it") }
    }

    /**
     * Send one kept edit: how it went, and what takes its place at the head of the
     * queue — the rest of a list that went partway, to send next.
     */
    private suspend fun send(client: MusicServer, d: LibraryDao?, edit: PlaylistEdit): Pair<WriteResult, List<PlaylistEdit>> {
        val id = edit.playlistId
        var instead = listOf(edit)
        val result = when (edit.kind) {
            PlaylistEdit.Kind.CREATE -> {
                var made: String? = null
                var rest = emptyList<String>()
                val sent = attemptWrite {
                    val chunks = edit.trackIds.chunked(client.playlistIdsPerRequest())
                    rest = chunks.drop(1).flatten()
                    made = client.createPlaylist(edit.name, chunks.firstOrNull().orEmpty())
                    if (made == null && rest.isNotEmpty()) throw RefusedWrite("The server didn't say which playlist it made")
                }
                if (sent == WriteResult.Saved) {
                    instead = emptyList()
                    madeOnServer(d, id, made, edit)
                    made?.let { real -> if (rest.isNotEmpty()) instead = listOf(PlaylistEdit(PlaylistEdit.Kind.ADD, real, trackIds = rest)) }
                }
                sent
            }
            PlaylistEdit.Kind.ADD -> {
                var done = 0
                val sent = attemptWrite {
                    for (chunk in edit.trackIds.chunked(client.playlistIdsPerRequest())) {
                        client.addToPlaylist(id, chunk)
                        done += chunk.size
                    }
                }
                instead = if (sent == WriteResult.Saved) emptyList() else listOf(edit.copy(trackIds = edit.trackIds.drop(done)))
                sent
            }
            PlaylistEdit.Kind.RENAME -> attemptWrite { client.renamePlaylist(id, edit.name) }
            PlaylistEdit.Kind.DELETE -> attemptWrite { client.deletePlaylist(id) }
            PlaylistEdit.Kind.SET -> attemptWrite { replaceOnServer(client, id, edit.trackIds) }
        }
        if (result == WriteResult.Saved && edit.kind != PlaylistEdit.Kind.CREATE) {
            instead = instead.filterNot { it === edit }
            // Read again when next wanted: the server's copy is the one to keep.
            if (edit.kind != PlaylistEdit.Kind.DELETE) markMembershipStale(id)
        }
        return result to instead
    }

    /** The phone-made playlist [phoneId] is on the server now, as [made]: everything held follows it there. */
    private suspend fun madeOnServer(d: LibraryDao?, phoneId: String, made: String?, edit: PlaylistEdit) {
        dropMembership(d, phoneId)
        if (made == null) {
            _playlists.update { list -> list.filterNot { it.id == phoneId } }
            return
        }
        sentIds[phoneId] = made
        storeMembership(d, made, edit.trackIds, fresh = false)
        _playlists.update { list -> list.map { if (it.id == phoneId) it.copy(id = made) else it } }
    }

    /**
     * Make the server's copy of [id] exactly [target], with the calls every server
     * has: removals, appends, then a reorder if one is still needed. Read first,
     * so a run cut off partway is simply run again.
     */
    private suspend fun replaceOnServer(client: MusicServer, id: String, target: List<String>) {
        val plan = replacement(client.getPlaylistTrackIds(id), target)
        if (plan.removeAt.isNotEmpty() && !client.removeFromPlaylistAt(id, plan.removeAt)) {
            throw RefusedWrite("The server wouldn't take songs out")
        }
        for (chunk in plan.append.chunked(client.playlistIdsPerRequest())) client.addToPlaylist(id, chunk)
        if (plan.reorder && !client.reorderPlaylist(id, target)) throw RefusedWrite("The server wouldn't reorder it")
    }

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
        // The kind of failure only: a reason can be the server's own words.
        Log.w("AmpSync", "$what → ${result::class.simpleName}")
        tell("Couldn't save\n$reason")
        return result
    }

    /** Drop everything held in memory for the source being left — see LibraryRepository.forgetDerived. */
    fun forget() {
        _playlists.value = emptyList()
        membership.value = emptyMap()
        staleMembership.value = emptySet()
        unhidden = null
    }

    /** [forget], and the stored membership too — see LibraryRepository.clearCache. */
    suspend fun clearCache() {
        daos.value?.clearAllPlaylistTracks()
        forget()
    }
}

/**
 * A playlist page's answers: [stored] at once when it has songs, as it needs no network,
 * then [server]'s. With neither, the empty stored copy comes once, so the page stops loading.
 */
internal fun playlistUpdates(
    stored: suspend () -> PlaylistView,
    server: suspend () -> PlaylistView?,
): Flow<PlaylistView> = flow {
    val held = stored()
    if (held.tracks.isNotEmpty()) emit(held)
    val fresh = server()
    if (fresh != null) emit(fresh) else if (held.tracks.isEmpty()) emit(held)
}

/**
 * Make playlist [name] holding [ids] on [client]: created with the first
 * [MusicServer.playlistIdsPerRequest] and the rest appended as many at a time.
 * A save that stops after the playlist was made says how many songs went in;
 * one that lands whole hands the new playlist's id to [onSaved].
 */
internal suspend fun createInChunks(
    client: MusicServer,
    name: String,
    ids: List<String>,
    /** The playlist is on the server now, whatever comes of the rest. */
    onMade: () -> Unit = {},
    onSaved: suspend (String) -> Unit = {},
): WriteResult {
    var saved = -1
    var made: String? = null
    val result = attemptWrite {
        // Inside the attempt: on Subsonic, the chunk size is itself a request.
        val chunks = ids.chunked(client.playlistIdsPerRequest())
        val id = client.createPlaylist(name, chunks.firstOrNull().orEmpty())
        onMade()
        made = id
        saved = chunks.firstOrNull()?.size ?: 0
        for (chunk in chunks.drop(1)) {
            client.addToPlaylist(id ?: throw RefusedWrite("The server didn't say which playlist it made"), chunk)
            saved += chunk.size
        }
    }
    if (saved < 0) return result
    val partly = "Only $saved of ${ids.size} songs saved"
    return when (result) {
        WriteResult.Saved -> result.also { made?.let { onSaved(it) } }
        is WriteResult.Unreachable -> result.copy(reason = "$partly. ${result.reason}")
        is WriteResult.Refused -> result.copy(reason = "$partly. ${result.reason}")
    }
}

/** Said the first time a playlist edit is kept for later — see LibraryPlaylists.keep. */
private const val KEPT_NOTICE = "Saved on the phone\nIt syncs when your server is back"

package com.sublunar.amp.data

import com.sublunar.amp.data.db.AlbumEntity
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.MAX_BOUND_IDS
import com.sublunar.amp.data.db.PlaylistTrackEntity
import com.sublunar.amp.source.PhonePlaylists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
data class PlaylistView(val all: List<Track>, val complete: Boolean, val shown: List<Boolean> = all.map { true }) {
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
    /** The persisted library choice, read as stored — see LibrarySync's. */
    private val libraryId: Flow<String?>,
    /** Whether the server may be spoken to at all — see App.metadataAllowed. */
    private val metadataAllowed: () -> Boolean,
    /** Whether bulk background fetching is worth the link now: false on a metered link outside Make it Hurt. */
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

    /** Whether a chosen library narrows a playlist's songs — see SourceProvider.libraryNarrowsPlaylistSongs. */
    private val narrowsPlaylistSongs: StateFlow<Boolean> =
        settings.activeSource
            .map { it?.provider?.libraryNarrowsPlaylistSongs ?: true }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, true)

    /** The chosen library's songs, where it narrows the playlist list instead; null where it doesn't. */
    private val listingLibrary: StateFlow<ListingLibrary?> =
        combine(library.indexed, library.selectedLibrary, library.hiddenLibraries, narrowsPlaylistSongs) {
                inLibrary, selected, hidden, songsRule ->
            val narrows = LibraryView(selected, hidden).narrows
            // Nothing read yet is a library still loading, not one with no songs.
            if (!songsRule && narrows && inLibrary.tracks.isNotEmpty()) {
                ListingLibrary(inLibrary.ids, chosen = selected != null)
            } else {
                null
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private class ListingLibrary(val songs: Set<String>, val chosen: Boolean)

    /** Every playlist the source lists, whichever library is chosen: for its own pages, the picker, and priming. */
    val allPlaylists: StateFlow<List<Playlist>> get() = _playlists

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
        combine(_playlists, membership, library.downloadedTrackIds, library.offline, listingLibrary) {
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
        val downloaded = if (library.offline.value) library.downloadedTrackIds.value else null
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
            source?.hiddenLibraries.orEmpty(),
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
            val tracks = library.getTracksByIds(ids)
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
        return FetchedPlaylist(library.getTracksByIds(ids), whole = false)
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
        val d = daos.value
        awaitPlaylistWrites()
        val ids = runCatching { serverClient.value?.getPlaylistTrackIds(id) }.noteOom("playlist prime $id").getOrNull() ?: return
        storeMembership(d, id, ids)
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
        combine(membership, library.downloadedTrackIds, playlistShown()) { known, downloaded, shown ->
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
            combine(library.indexed, library.cachedTracks, library.albumRows) { inLibrary, rows, albums ->
                Triple(inLibrary, rows, albums)
            },
            library.selectedLibrary,
            library.hiddenLibraries,
            narrowsPlaylistSongs,
        ) { (inLibrary, rows, albums), selected, hidden, songsRule ->
            val narrows = LibraryView(selected, hidden).narrows
            if (hidden.isEmpty()) unhidden = null
            when {
                narrows && songsRule -> inLibrary.ids
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
            serverClient.value?.let { createInChunks(it, name.trim(), trackIds) } ?: WriteResult.Unreachable(UNREACHABLE)
        }
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
            server = { it.addToPlaylist(id, listOf(trackId)) },
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
 * Make playlist [name] holding [ids] on [client]: created with the first
 * [MusicServer.playlistIdsPerRequest] and the rest appended as many at a time.
 * A save that stops after the playlist was made says how many songs went in.
 */
internal suspend fun createInChunks(client: MusicServer, name: String, ids: List<String>): WriteResult {
    val chunks = ids.chunked(client.playlistIdsPerRequest())
    var saved = -1
    val result = attemptWrite {
        val id = client.createPlaylist(name, chunks.firstOrNull().orEmpty())
        saved = chunks.firstOrNull()?.size ?: 0
        for (chunk in chunks.drop(1)) {
            client.addToPlaylist(id ?: throw RefusedWrite("The server didn't say which playlist it made"), chunk)
            saved += chunk.size
        }
    }
    if (saved < 0) return result
    val partly = "Only $saved of ${ids.size} songs saved"
    return when (result) {
        WriteResult.Saved -> result
        is WriteResult.Unreachable -> result.copy(reason = "$partly. ${result.reason}")
        is WriteResult.Refused -> result.copy(reason = "$partly. ${result.reason}")
    }
}

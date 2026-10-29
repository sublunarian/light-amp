package com.sublunar.amp.data

import com.sublunar.amp.data.db.AlbumEntity
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.db.toEntity
import com.sublunar.amp.source.PhoneAccess
import com.sublunar.amp.source.PhoneLibrary
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

/**
 * Keeps the active source's cached library in step with its server — or, for
 * the phone's own music, with its files: the incremental [sync], Sync Now's
 * scan, and the one [syncState] the screens show. The lists read what this
 * writes — see LibraryRepository.
 */
class LibrarySync(
    private val daos: StateFlow<LibraryDao?>,
    private val serverClient: StateFlow<MusicServer?>,
    // The persisted selection as a raw flow, not a StateFlow: [sync] must read
    // the stored value, and a stateIn's initial null would race the DataStore
    // load at boot and sync the wrong (unscoped) library.
    private val libraryId: Flow<String?>,
    /** Whether the server may be spoken to at all — see App.metadataAllowed. */
    private val metadataAllowed: () -> Boolean,
    /** Whether bulk background fetching is worth the link now: false on a metered link outside Make it Hurt. */
    private val bulkAllowed: () -> Boolean,
    /** Suspends while the player starts a stream, so background work steps aside — see App.yieldToPlayback. */
    private val yieldToPlayback: suspend () -> Unit,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    /** The pooled client for a source record — App.clientFor. See [repoint]. */
    private val clientFor: (MusicSource) -> MusicServer?,
    /**
     * Told when a sync finishes — the server answered — so App can report it
     * reachable and follow up downloads and covers. Callbacks from App, so the
     * sync needn't know about any of them.
     */
    private val onSyncSucceeded: () -> Unit,
    /**
     * Told when a sync gives up. The flag is true when the server answered and
     * refused us — see [isAuthFailure]; not to be read as unreachable.
     */
    private val onSyncFailed: (authFailure: Boolean) -> Unit,
    /**
     * Told which covers a finished sync left nothing pointing at, so their files
     * can go — see ArtworkLoader.drop. Names the source the run was pinned to,
     * which may no longer be the one on screen.
     */
    private val onCoversDropped: suspend (sourceId: String, coverArtIds: Set<String>) -> Unit,
) {
    /**
     * Moved once a sync has finished writing, so every list reads its table again
     * (see LibraryRepository.observing) rather than trusting Room to have noticed
     * the writes: Room 2.7.0 (the
     * version Light's store builder pins) can leave an observed query showing what
     * it read before a write landed — a first sync's albums stayed invisible.
     *
     * The tracks have [rereadTracks], moved only by a sync that wrote songs: every
     * sync rewrites album rows, and re-reading the whole library after one that
     * changed no song is a second copy of it for nothing.
     */
    private val _reread = MutableStateFlow(0)
    val reread: StateFlow<Int> = _reread
    private val _rereadTracks = MutableStateFlow(0)
    val rereadTracks: StateFlow<Int> = _rereadTracks

    /** The database a sync is writing to right now, if one is — see LibraryRepository.pacedWhileSyncing. */
    private val _syncingDao = MutableStateFlow<LibraryDao?>(null)
    val syncingDao: StateFlow<LibraryDao?> = _syncingDao

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState

    // Serializes syncs and library switches so they never write the cache
    // concurrently; the job lets a switch cancel the sync in flight.
    private val syncMutex = Mutex()
    private var syncJob: Job? = null

    /** Full incremental sync of the currently selected library. Safe to repeat. */
    suspend fun sync() = syncScoped(libraryId.first())

    /**
     * Switch to [musicFolderId]: cancel any sync in flight and sync just that
     * library, serialized through [syncMutex].
     *
     * **Nothing is thrown away**: every library stays cached, tagged with where it
     * came from, and choosing one is a filter — see LibraryRepository.allAlbums.
     * The sync is a refresh, not what makes the switch work; failing, or never
     * running for want of a signal, it leaves the library showing what was last
     * cached for it.
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
     * that answers — see SourceProvider.relocate. Saving the source rebuilds the
     * client, and playback and downloads follow; the answer is the moved
     * address's client, null when nothing moved.
     *
     * Asked of the pool rather than read from [serverClient], which App swaps
     * only once the saved source reaches its collector: read now, it is still the
     * client of the address that just failed. The pool is keyed on the record, so
     * App's swap then takes this same client.
     */
    private suspend fun repoint(): MusicServer? {
        val source = settings.activeSource.first() ?: return null
        val moved = source.provider.relocate(source) ?: return null
        android.util.Log.i("AmpSync", "${source.kind.name.lowercase()} moved: ${hostOf(source.baseUrl)} -> ${hostOf(moved.baseUrl)}")
        settings.saveSource(moved)
        return clientFor(moved)
    }

    /**
     * The active source's client, if [started]'s record changed under this run, which closes the
     * run's client: a reachability check moved it to another address (see SourceRoute), where
     * relocating could pick a worse one, or, with [clientClosed], any change at all.
     */
    private suspend fun movedDuringRun(started: MusicSource?, clientClosed: Boolean = false): MusicServer? {
        val now = settings.activeSource.first() ?: return null
        if (started == null || now.id != started.id) return null
        if (!clientClosed && now.baseUrl == started.baseUrl) return null
        android.util.Log.i("AmpSync", "the source changed during the sync; trying again on its new client")
        return clientFor(now)
    }

    /**
     * The sync itself, of [musicFolderId] (null = all libraries); callers
     * serialize through [syncMutex].
     *
     * `dao` and `client` are pinned for the whole run, and every helper takes the
     * pinned ones, never [daos] or [serverClient] read again — those follow the
     * active source, and a switch mid-run would land one server's albums and songs
     * in another's library. The local scan needs the same.
     *
     * Each early return releases the claim the caller staked (callers set
     * `syncing` before launching, to turn duplicates away): left standing, it
     * would turn away every later sync for the life of the process.
     * runLocalScan owns the flag across its whole run.
     */
    private suspend fun runSync(musicFolderId: String?, repointed: MusicServer? = null) {
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
        val client = repointed ?: serverClient.value ?: return releaseSyncClaim()
        _syncState.update { it.copy(syncing = true, error = null, phase = "Connecting") }
        _syncingDao.value = dao
        // What the run wrote, so the re-read at its end covers that and no more.
        val wrote = SyncWrites()
        // Asked once a run, for the fan-out and for the libraries remembered at
        // the end (a failed run's too); up front, as learnServerFeatures may close this client.
        var folders: List<MusicFolder>? = null
        try {
            // Read before the first write — see reportDroppedCovers.
            val coversBefore = coverIdsOrNull(dao)
            val hidden = source?.hiddenLibraries.orEmpty()
            folders = when {
                musicFolderId == null -> client.getMusicFolders()
                source?.supportsLibraries == true -> runCatching { client.getMusicFolders() }.getOrNull()
                else -> null
            }
            // "All libraries" is fanned out per folder and merged: an unscoped
            // getAlbumList2 returns only the server's default library. A hidden
            // library is left out — not wanted on this phone, data included.
            val folderIds: List<String?> = if (musicFolderId != null) {
                listOf(musicFolderId)
            } else {
                folders.orEmpty().map { it.id }.filterNot { it in hidden }.ifEmpty { listOf(null) }
            }
            val starred = readStarred(client, folderIds)
            val cached = dao.allAlbumsSnapshot().associateBy { it.id }

            // Blank, read "Syncing": every sync reads the album index, new albums or not.
            _syncState.update { it.copy(phase = "") }
            val (serverAlbums, libraryOf) = fetchAlbums(client, folderIds, starred, cached)
            dao.upsertAlbums(serverAlbums.map { it.toEntity(libraryOf[it.id]) })
            if (serverAlbums.isNotEmpty()) wrote.albums = true
            pruneGone(dao, cached, serverAlbums, musicFolderId, hidden, wrote)

            val cachedTrackCounts = dao.trackCountsByAlbum().associate { it.albumId to it.tracks }
            val songCheck = source?.let { settings.songCheck(it.id) }
            val changes = songChanges(client, songCheck, musicFolderId, folderIds)
            val reread = songCheck?.let {
                rereadFor(
                    changes, it, serverAlbums,
                    library = musicFolderId, bulk = bulkAllowed(), nowMs = System.currentTimeMillis(),
                )
            }
            val fetch = albumsNeedingSongs(serverAlbums, cached, cachedTrackCounts, reread, refill = refillForParser)
            logSongFetch(fetch, serverAlbums.size, refillForParser, changes)

            // Counted in albums, one request each: labelled as songs it would read
            // as a library being cut short. Only the news is counted — see albumsNeedingSongs.
            _syncState.update { it.copy(phase = albumsPhase(0, fetch.news.size)) }
            val likedSongs = starred?.songIds ?: dao.likedTrackIds().toHashSet()
            val unread = fetchAndStoreSongs(dao, client, fetch.albums, fetch.news, likedSongs, libraryOf, wrote)
            // Settled only once every album it covers is read; otherwise the old
            // mark stays, to be asked again next time.
            if (source != null && reread != null && unread == 0 && reread.next != songCheck) {
                settings.setSongCheck(source.id, reread.next)
            }
            if (starred != null) reconcileLikes(dao, starred, wrote)
            // Only now: a run that threw part-way refills again next time.
            if (refillForParser && source != null) {
                settings.setParserGeneration(source.id, TRACK_PARSER_GENERATION)
            }

            finishRun(dao, client, source, coversBefore, wrote)
            onSyncSucceeded()
            rememberLibraries(source, folders)
        } catch (e: CancellationException) {
            // A switch cancelled this sync; not an error. With the run still active it is the
            // run's client closed under it, by a new record for the source: tried again on the new one.
            if (repointed == null && currentCoroutineContext().isActive) {
                movedDuringRun(source, clientClosed = true)?.let { replaced ->
                    runSync(musicFolderId, repointed = replaced)
                    return
                }
            }
            _syncState.update { it.copy(syncing = false) }
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AmpSync", "sync failed: ${e::class.simpleName}")
            // The address may simply have moved, under this run or away from
            // it. Only once, so a dead server can't loop.
            if (repointed == null) {
                (movedDuringRun(source) ?: repoint())?.let { moved ->
                    runSync(musicFolderId, repointed = moved)
                    return
                }
            }
            _syncState.update { it.copy(syncing = false, error = syncErrorMessage(e)) }
            onSyncFailed(isAuthFailure(e))
            rememberLibraries(source, folders)
        } finally {
            _syncingDao.compareAndSet(dao, null)
        }
    }

    /**
     * The server's likes, merged across [folderIds]. Null when it didn't answer:
     * likes then stay as they are, rather than being reconciled against nothing
     * (unliking everything) or failing the whole sync over them.
     */
    private suspend fun readStarred(client: MusicServer, folderIds: List<String?>): Starred? =
        runCatching { folderIds.map { client.getStarred(it) }.mergeStarred() }
            .noteOom("sync: favourites")
            .onFailure { android.util.Log.w("AmpSync", "favourites unread, likes kept: ${it::class.simpleName}") }
            .getOrNull()

    /** The server's albums across [folderIds], and which folder each came from. */
    private data class ServerAlbums(val albums: List<Album>, val libraryOf: Map<String, String?>)

    private suspend fun fetchAlbums(
        client: MusicServer,
        folderIds: List<String?>,
        starred: Starred?,
        cached: Map<String, AlbumEntity>,
    ): ServerAlbums {
        // Each album keeps the folder it came from, written onto its row — see
        // AlbumEntity.libraryId. An album in two folders keeps the first.
        val fetched = folderIds
            .flatMap { folder ->
                val list = client.getAllAlbums(folder)
                android.util.Log.i("AmpSync", "folder=$folder returned ${list.size} albums")
                list.map { it to folder }
            }
            .distinctBy { (album, _) -> album.id }
        val albums = fetched.map { (album, _) ->
            album.copy(liked = starred?.let { album.id in it.albumIds } ?: cached[album.id]?.liked ?: album.liked)
        }
        return ServerAlbums(albums, fetched.associate { (album, folder) -> album.id to folder })
    }

    /**
     * Prune albums (and their tracks) the server no longer has — never on
     * an empty answer. A stale scope, a 200 with an empty body or a gone
     * section id look exactly like an emptied library, and pruning on one
     * deletes the whole cache for good (the next sync asks the same way).
     */
    private suspend fun pruneGone(
        dao: LibraryDao,
        cached: Map<String, AlbumEntity>,
        serverAlbums: List<Album>,
        musicFolderId: String?,
        hidden: Set<String>,
        wrote: SyncWrites,
    ) {
        if (serverAlbums.isEmpty()) {
            android.util.Log.w("AmpSync", "returned no albums; keeping ${cached.size} cached")
            return
        }
        // Only within the library just fetched: one folder's answer says
        // nothing about the others. A scoped sync leaves untagged rows
        // alone (no fetch speaks for them), and a hidden library's rows
        // were never asked about, so they stay for when it is shown again.
        val inScope = if (musicFolderId == null) {
            cached.filterValues { it.libraryId == null || it.libraryId !in hidden }
        } else {
            cached.filterValues { it.libraryId == musicFolderId }
        }
        val staleAlbumIds = inScope.keys - serverAlbums.mapTo(HashSet()) { it.id }
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

    /**
     * The albums whose songs changed without their count moving (a re-tag, tags
     * a server finished reading late), as far as the server can say — see
     * songChangesSince. Asked on a refill too: it hands over the first mark.
     */
    private suspend fun songChanges(
        client: MusicServer,
        check: SongCheck?,
        library: String?,
        folderIds: List<String?>,
    ): SongChanges? {
        if (check == null) return null
        return runCatching { client.songChangesSince(check.markFor(library), folderIds) }
            .noteOom("sync: song changes")
            .onFailure { android.util.Log.w("AmpSync", "song changes: couldn't ask: ${it::class.simpleName}") }
            .getOrNull()
    }

    /** Blank, which reads "Syncing", when there is nothing new to count. */
    private fun albumsPhase(done: Int, total: Int): String = if (total == 0) "" else "Albums $done/$total"

    private fun logSongFetch(fetch: SongFetch, albumCount: Int, refill: Boolean, changes: SongChanges?) {
        android.util.Log.i(
            "AmpSync",
            if (refill) {
                "parser refill: fetching songs for all $albumCount albums"
            } else {
                "fetching songs for ${fetch.albums.size} of $albumCount albums ${fetch.reasons}" +
                    (if (fetch.untouched > 0) "; ${fetch.untouched} uncounted and untouched, cache kept" else "") +
                    "; song changes: ${changes?.let { it::class.simpleName } ?: "not asked"}"
            },
        )
    }

    /** Track likes across the whole library, writing only the ones that moved — see LibraryDao.replaceTrackLikes. */
    private suspend fun reconcileLikes(dao: LibraryDao, starred: Starred, wrote: SyncWrites) {
        if (dao.replaceTrackLikes(starred.songIds.toList()) > 0) wrote.tracks = true
        if (dao.replaceLikedArtists(starred.artistNames) > 0) wrote.likedArtists = true
    }

    /** A run's last writes and its end, once everything it fetched is stored. */
    private suspend fun finishRun(
        dao: LibraryDao,
        client: MusicServer,
        source: MusicSource?,
        coversBefore: Set<String>?,
        wrote: SyncWrites,
    ) {
        // An album whose art changed has a new cover id, which its songs
        // (not re-fetched for that) don't carry yet: point them at it, so the
        // old one is seen to be let go of just below.
        runCatching { dao.collapseTrackCovers() }.noteOom("sync: cover collapse")
            .onSuccess { if (it > 0) wrote.tracks = true }
        reportDroppedCovers(dao, source?.id, coversBefore)
        if (wrote.tracks || wrote.albums || wrote.likedArtists) rereadLists(tracks = wrote.tracks)
        // The lists read freely again — after the re-read is asked for, so a
        // list this wakes sees it is superseded: see LibraryRepository.pacedWhileSyncing.
        _syncingDao.compareAndSet(dao, null)

        val finishedAt = System.currentTimeMillis()
        _syncState.value = SyncState(
            syncing = false,
            lastSyncedMs = finishedAt,
        )
        // The source this ran for, not whichever is active by now.
        runCatching { source?.id?.let { settings.setLastSyncedMs(it, finishedAt) } }
        learnServerFeatures(dao, client, source)
    }

    /**
     * Whatever folders the server reported, remembered on [source], so the
     * Sources page can list them instantly and offline. Null when it wasn't asked
     * or didn't answer.
     */
    private suspend fun rememberLibraries(source: MusicSource?, folders: List<MusicFolder>?) {
        if (source == null || !source.supportsLibraries) return
        // Empty means "couldn't ask" (a network failure, a client not up yet)
        // as often as "none", and written through it would wipe libraries
        // already found. None looks the same anyway.
        if (folders.isNullOrEmpty()) return
        runCatching { settings.setSourceLibraries(source.id, folders.map { SourceLibrary(it.id, it.name) }) }
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
            dao.upsertTracks(scan.tracks)
            reportDroppedCovers(dao, sourceId, coversBefore)
            rereadLists(tracks = true)
            val finishedAt = System.currentTimeMillis()
            _syncState.value = SyncState(
                syncing = false,
                lastSyncedMs = finishedAt,
            )
            runCatching { settings.setLastSyncedMs(sourceId, finishedAt) }
            onSyncSucceeded()
        } catch (e: CancellationException) {
            _syncState.update { it.copy(syncing = false) }
            throw e
        } catch (e: Exception) {
            _syncState.update { it.copy(syncing = false, error = e.message ?: "Couldn't read the music folder") }
        }
    }

    private suspend fun coverIdsOrNull(dao: LibraryDao): Set<String>? =
        runCatching { dao.allCoverArtIds().toSet() }.noteOom("sync: cover ids").getOrNull()

    /** See [reread]; the tracks too when [tracks], the run having written to them. */
    private fun rereadLists(tracks: Boolean) {
        _reread.update { it + 1 }
        if (tracks) _rereadTracks.update { it + 1 }
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
        runCatching { onCoversDropped(sourceId, dropped) }
            .noteOom("sync: dropped covers")
            .onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun fetchAndStoreSongs(
        /** The sync's own database — never the property, which follows the active source. */
        dao: LibraryDao,
        client: MusicServer,
        albums: List<Album>,
        /** The albums the progress counts. */
        news: Set<String>,
        starredSongIds: Set<String>,
        /** Which library each album came from — see the count write-back below. */
        libraryOf: Map<String, String?>,
        wrote: SyncWrites,
    ): Int = coroutineScope {
        val gate = Semaphore(SYNC_CONCURRENCY)
        val newsDone = AtomicInteger(0)
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
                        if (album.id in news) {
                            val n = newsDone.incrementAndGet()
                            if (n % 10 == 0 || n == news.size) {
                                _syncState.update { it.copy(phase = albumsPhase(n, news.size)) }
                            }
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
                val rows = fetched[i].orEmpty()
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
     * The periodic job's sync: none while one runs or within the floor of the
     * last, so a Sync Now isn't repeated minutes later. Awaited, so the job
     * holds the process while it syncs.
     */
    suspend fun syncUnlessRecent() {
        val state = _syncState.value
        val why = when {
            state.syncing -> "one is running"
            syncedWithin(state.lastSyncedMs, SYNC_MIN_INTERVAL_MS) ->
                "last one ${(System.currentTimeMillis() - state.lastSyncedMs) / 60_000} min ago"
            else -> null
        }
        if (why != null) {
            android.util.Log.i("AmpSync", "background sync skipped: $why")
            return
        }
        sync()
    }

    /**
     * "in 0 until", not "<": a time in the future (a sync recorded while the
     * clock was wrong) is not a recent sync, and would stop automatic syncs
     * until the clock caught up.
     */
    private fun syncedWithin(lastSyncedMs: Long, floorMs: Long): Boolean =
        System.currentTimeMillis() - lastSyncedMs in 0 until floorMs

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
        // And a floor between runs, which the claim doesn't give.
        if (!force && syncedWithin(_syncState.value.lastSyncedMs, SYNC_MIN_INTERVAL_MS)) return
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
                if (!force && syncedWithin(last, floor)) {
                    android.util.Log.i("AmpSync", "launch sync skipped: last one ${(System.currentTimeMillis() - last) / 60_000} min ago")
                    _syncState.update { it.copy(syncing = false, phase = "", lastSyncedMs = last) }
                    // No sync to read the libraries, so they are asked for alone.
                    val source = settings.activeSource.first()
                    if (source != null && source.supportsLibraries && metadataAllowed()) {
                        val client = clientFor(source)
                        rememberLibraries(source, client?.let { runCatching { it.getMusicFolders() }.getOrNull() })
                    }
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
            try {
                previous?.cancelAndJoin()
                askForScan()
                sync()
            } catch (e: Throwable) {
                // As in syncInBackground: a claim left standing turns every later sync away.
                _syncState.update { it.copy(syncing = false) }
                throw e
            }
        }
    }

    /** The scan half of Sync Now: asked, and waited on for at most [SCAN_WAIT_MS]. */
    private suspend fun askForScan() {
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
        // Said plainly rather than looking like an empty scan (a library
        // shared with you on Plex: scanning is the owner's). A server with no
        // such call has refused nothing.
        scanRefused = outcome == ScanRequest.REFUSED
        if (outcome != ScanRequest.STARTED) return
        var waited = 0L
        while (waited < SCAN_WAIT_MS) {
            delay(SCAN_POLL_MS)
            waited += SCAN_POLL_MS
            val busy = runCatching { client?.serverScanning(folder) }.getOrDefault(false) == true
            if (!busy) break
        }
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

        /** How long an automatic or background sync waits after the last one finished; Sync Now means "now". */
        private const val SYNC_MIN_INTERVAL_MS = 5L * 60 * 1000

        /** The same floor on a metered link, where an automatic sync costs the user data and their first minutes. */
        private const val SYNC_MIN_INTERVAL_METERED_MS = 6L * 60 * 60 * 1000

        /** How long Sync Now waits on a server scan before syncing anyway, and how often it asks. */
        private const val SCAN_WAIT_MS = 30_000L
        private const val SCAN_POLL_MS = 1_500L
    }
}

/** Album fetches in flight at once, in a sync and in priming playlists. */
internal const val SYNC_CONCURRENCY = 6

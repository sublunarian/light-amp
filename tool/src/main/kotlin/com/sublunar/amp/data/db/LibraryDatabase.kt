package com.sublunar.amp.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.OnConflictStrategy
import androidx.room.Insert
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.forEachTrackPage
import kotlinx.coroutines.flow.Flow

/**
 * Indexed on albumId because a sync asks by album — each batch deletes and
 * compares its albums' songs one album at a time, as an album page reads them.
 * Without it each is a whole-table scan, seconds per batch on a large library.
 */
@Entity(tableName = "tracks", indices = [Index("albumId")])
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumId: String?,
    val coverArtId: String?,
    val durationMs: Long,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val playCount: Int,
    val lastPlayedMs: Long,
    val liked: Boolean,
    /** Star rating 1–5, 0 when unrated. */
    val rating: Int = 0,
    val genre: String = "",
    val composer: String = "",
    /** Plex hands out a file path for original-quality playback; see [Track]. */
    val streamPath: String = "",
    /** Loudness-normalisation gain in dB from the server; see [Track.gainDb]. */
    val gainDb: Float? = null,
)

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val coverArtId: String?,
    val durationMs: Long,
    val songCount: Int,
    val year: Int?,
    val releaseDate: Long,
    val createdMs: Long,
    /**
     * The server's own last-changed stamp; see [Album.updatedMs].
     *
     * The SQL default is what lets this arrive as an auto-migration: rows
     * written before it existed read 0, which the sync filter treats as
     * "never heard a stamp for this one" and re-fetches once.
     */
    @ColumnInfo(defaultValue = "0")
    val updatedMs: Long = 0,
    val playCount: Int,
    val lastPlayedMs: Long,
    val liked: Boolean,
    /** Star rating 1–5, 0 when unrated. */
    val rating: Int = 0,
    val genre: String = "",
    /**
     * Which of the server's libraries this album came from, or null where there
     * is none to name (a server that lists none, the phone's own files) or the
     * row predates the column. Recorded so choosing a library is a filter over
     * the cache, which works offline, rather than a fetch — see
     * LibraryRepository.allAlbums.
     */
    val libraryId: String? = null,
)

/**
 * Liked artists, stored by name. The Artists list is derived from track tags
 * rather than fetched, so a name is the only stable key we can join on; the
 * server's own artist ids are resolved on demand when starring.
 */
@Entity(tableName = "liked_artists")
data class LikedArtistEntity(@PrimaryKey val name: String)

/**
 * One entry of an artist's popular-songs list, as the server ranked it.
 *
 * Ids only: a popular song the cached library doesn't hold is left out when read
 * back, never written in — rows from outside the library are how one server's
 * songs end up in another's. Refreshed quietly once [fetchedAtMs] is old — see
 * LibraryRepository.topSongsForArtist.
 */
@Entity(tableName = "top_songs", primaryKeys = ["artist", "position"])
data class TopSongEntity(
    val artist: String,
    val position: Int,
    val trackId: String,
    val fetchedAtMs: Long,
)

/**
 * One downloaded track: the index over its file on disk — format, size, and any
 * lyrics captured alongside for offline playback.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackId: String,
    val albumId: String?,
    val fileName: String,
    val format: String,
    val bytes: Long,
    val lyrics: String?,
    val downloadedAtMs: Long,
)

/**
 * One song's place in one playlist.
 *
 * Playlists themselves are not a table: they are fetched live and cached in
 * preferences (see LibraryPlaylists.refreshPlaylists). Membership is here
 * because no server sends it with the list, it costs a fetch per playlist, and
 * it is needed exactly when the server can't be asked: for the download badge
 * and the offline rebuild of a playlist's page.
 */
@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "position"])
data class PlaylistTrackEntity(
    val playlistId: String,
    val position: Int,
    val trackId: String,
)

/** An album, and the library it is in — see [LibraryDao.albumLibraries]. */
data class AlbumLibraryRow(val id: String, val libraryId: String?)

/** One playlist's membership, in order — see [LibraryDao.storedPlaylistTracks]. */
data class PlaylistTrackRow(
    val playlistId: String,
    val trackId: String,
)

/** A downloaded file, without the lyrics blob — see [LibraryDao.observeDownloads]. */
data class DownloadFile(
    val trackId: String,
    val albumId: String?,
    val fileName: String,
    val format: String,
    val bytes: Long,
    val downloadedAtMs: Long,
)

/** Cached track count for one album. */
data class AlbumTrackCount(val albumId: String, val tracks: Int)

/** A song as one page of [LibraryDao.readTracks] has it: with the rowid it is stored at. */
data class TrackRow(val rowId: Long, @Embedded val track: Track)

/** The most ids one statement binds (Android 14's SQLite takes 32,766); a longer list goes in pieces. */
const val MAX_BOUND_IDS = 30_000

/**
 * A track a download top-up may fetch: not in a library its source hides. An
 * album with no library recorded shows everywhere, a track with no album is kept,
 * and with nothing hidden nothing is narrowed at all.
 */
private const val KEPT =
    "(:noneHidden OR albumId IS NULL OR albumId IN " +
        "(SELECT id FROM albums WHERE libraryId IS NULL OR libraryId NOT IN (:hidden)))"

@Dao
interface LibraryDao {

    // --- tracks and albums ---------------------------------------------------

    /**
     * Re-run by Room after every write to the tracks table: the signal the library
     * list reads the table again on, through [readTracks] — not read for the number.
     */
    @Query("SELECT COUNT(*) FROM tracks")
    fun observeTrackCount(): Flow<Int>

    /** One page of [readTracks]: the rows stored after rowid [after], in storage order. */
    @Query("SELECT rowid AS rowId, * FROM tracks WHERE rowid > :after ORDER BY rowid LIMIT :limit")
    suspend fun trackPage(after: Long, limit: Int): List<TrackRow>

    /**
     * The whole table, [pageSize] rows at a time, in storage order — the order a
     * plain `SELECT * FROM tracks` has, which every sort and search downstream
     * relies on. Each page goes to [consume] before the next is read, so a read
     * holds one page of new rows, not a second library (see TrackRead).
     *
     * One [Transaction], as the album reads below: a result this size takes
     * several CursorWindow fills, each its own snapshot unless held together, and
     * a sync writing between two of them throws "Couldn't read row N from
     * CursorWindow" (a source switch starts a full read and a full sync at once).
     * Pages likewise: a batch landing between two would move songs across the
     * boundary. Writes wait while it reads.
     */
    @Transaction
    suspend fun readTracks(pageSize: Int, consume: (List<TrackRow>) -> Unit) {
        forEachTrackPage(pageSize, { after, limit -> trackPage(after, limit) }, consume)
    }

    @Transaction
    @Query("SELECT * FROM albums")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Transaction
    @Query("SELECT * FROM albums")
    suspend fun allAlbumsSnapshot(): List<AlbumEntity>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId")
    suspend fun tracksForAlbum(albumId: String): List<TrackEntity>

    /** The cached rows of several albums at once — one sync batch's, see LibrarySync.fetchAndStoreSongs. */
    @Query("SELECT * FROM tracks WHERE albumId IN (:albumIds)")
    suspend fun tracksForAlbums(albumIds: List<String>): List<TrackEntity>

    /** Straight into [Track], one object a row. At most [MAX_BOUND_IDS] ids at a time. */
    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun tracksByIds(ids: List<String>): List<Track>

    /** Which library each album is in, for a playlist narrowed to one. At most [MAX_BOUND_IDS] ids at a time. */
    @Query("SELECT id, libraryId FROM albums WHERE id IN (:ids)")
    suspend fun albumLibraries(ids: List<String>): List<AlbumLibraryRow>

    /** Any one song, for the probes that need a real id — see ServerFeatures. */
    @Query("SELECT id FROM tracks LIMIT 1")
    suspend fun anyTrackId(): String?

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE albumId = :albumId")
    suspend fun deleteTracksForAlbum(albumId: String)

    /**
     * Replace the tracks of several albums in one transaction. Album by album, a
     * sync would invalidate the tracks table twice per album, each a whole read of
     * the library again (see LibraryRepository.readTracks); batched, it is once
     * per batch, and each swap is atomic.
     */
    @Transaction
    suspend fun replaceAlbumTracks(albumIds: List<String>, tracks: List<TrackEntity>) {
        albumIds.forEach { deleteTracksForAlbum(it) }
        upsertTracks(tracks)
    }

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums WHERE id IN (:ids)")
    suspend fun deleteAlbums(ids: List<String>)

    /**
     * Tracks belonging to no album this library holds — what a cross-source write
     * leaves behind. Pruning walks albums, so these are invisible to it and would
     * stay for good.
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE albumId IS NOT NULL AND albumId NOT IN (SELECT id FROM albums)")
    suspend fun orphanedTrackCount(): Int

    @Query("DELETE FROM tracks WHERE albumId IS NOT NULL AND albumId NOT IN (SELECT id FROM albums)")
    suspend fun deleteOrphanedTracks()

    /** How many tracks are actually cached per album, for sync reconciliation. */
    @Query("SELECT albumId AS albumId, COUNT(*) AS tracks FROM tracks WHERE albumId IS NOT NULL GROUP BY albumId")
    suspend fun trackCountsByAlbum(): List<AlbumTrackCount>

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    // --- likes, ratings and plays --------------------------------------------

    /**
     * Make the liked tracks exactly [ids], in one go; answers how many rows
     * changed. Only rows whose like moves are written: Room re-reads every
     * observer of a table after any write touching it, and an update matching
     * nothing fires nothing. One transaction, so the likes are never seen half moved.
     */
    @Transaction
    suspend fun replaceTrackLikes(ids: List<String>): Int {
        if (ids.size <= MAX_BOUND_IDS) return unlikeTracksOtherThan(ids) + likeTracks(ids)
        // More than one statement can bind: the same two updates, in pieces.
        val keep = ids.toHashSet()
        return likedTrackIds().filterNot { it in keep }.chunked(MAX_BOUND_IDS).sumOf { unlikeTracks(it) } +
            ids.chunked(MAX_BOUND_IDS).sumOf { likeTracks(it) }
    }

    @Query("UPDATE tracks SET liked = 0 WHERE liked = 1 AND id NOT IN (:ids)")
    suspend fun unlikeTracksOtherThan(ids: List<String>): Int

    @Query("UPDATE tracks SET liked = 0 WHERE liked = 1 AND id IN (:ids)")
    suspend fun unlikeTracks(ids: List<String>): Int

    @Query("UPDATE tracks SET liked = 1 WHERE liked = 0 AND id IN (:ids)")
    suspend fun likeTracks(ids: List<String>): Int

    @Query("SELECT id FROM tracks WHERE liked = 1")
    suspend fun likedTrackIds(): List<String>

    @Query("UPDATE tracks SET liked = :liked WHERE id = :id")
    suspend fun setTrackLiked(id: String, liked: Boolean)

    @Query("UPDATE albums SET liked = :liked WHERE id = :id")
    suspend fun setAlbumLiked(id: String, liked: Boolean)

    @Query("UPDATE tracks SET rating = :stars WHERE id = :id")
    suspend fun setTrackRating(id: String, stars: Int)

    @Query("UPDATE albums SET rating = :stars WHERE id = :id")
    suspend fun setAlbumRating(id: String, stars: Int)

    /**
     * Record a play locally, so Recently Played and Most Played move as you
     * listen rather than at the next sync.
     */
    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedMs = :atMs WHERE id = :id")
    suspend fun markPlayed(id: String, atMs: Long)

    // --- liked artists -------------------------------------------------------

    @Query("SELECT name FROM liked_artists")
    fun observeLikedArtists(): Flow<List<String>>

    /** The same, once — for the sync, and for the download top-up, which reads every source's. */
    @Query("SELECT name FROM liked_artists")
    suspend fun likedArtistNames(): List<String>

    @Upsert
    suspend fun likeArtists(artists: List<LikedArtistEntity>)

    /** Rowids, -1 for a name already there — which, unlike an upsert, writes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun likeArtistsIfNew(artists: List<LikedArtistEntity>): List<Long>

    /** Make the liked artists exactly [names], writing only what moves, as [replaceTrackLikes]; answers how many rows changed. */
    @Transaction
    suspend fun replaceLikedArtists(names: Set<String>): Int {
        val gone = likedArtistNames().filterNot { it in names }
        gone.chunked(MAX_BOUND_IDS).forEach { unlikeArtists(it) }
        return gone.size + likeArtistsIfNew(names.map(::LikedArtistEntity)).count { it != -1L }
    }

    @Query("DELETE FROM liked_artists WHERE name = :name")
    suspend fun unlikeArtist(name: String)

    @Query("DELETE FROM liked_artists WHERE name IN (:names)")
    suspend fun unlikeArtists(names: List<String>)

    @Query("DELETE FROM liked_artists")
    suspend fun clearLikedArtists()

    // --- popular songs -------------------------------------------------------

    @Query("SELECT * FROM top_songs WHERE artist = :artist ORDER BY position")
    suspend fun topSongs(artist: String): List<TopSongEntity>

    @Query("DELETE FROM top_songs WHERE artist = :artist")
    suspend fun clearTopSongs(artist: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopSongs(rows: List<TopSongEntity>)

    /** The server's list for one artist, replacing whatever was held. */
    @Transaction
    suspend fun replaceTopSongs(artist: String, rows: List<TopSongEntity>) {
        clearTopSongs(artist)
        if (rows.isNotEmpty()) insertTopSongs(rows)
    }

    @Query("DELETE FROM top_songs")
    suspend fun clearAllTopSongs()

    // --- covers --------------------------------------------------------------

    /** Every cover this source's cache names, so Log Out can drop them from disk. */
    @Query(
        "SELECT DISTINCT coverArtId FROM albums WHERE coverArtId IS NOT NULL " +
            "UNION SELECT DISTINCT coverArtId FROM tracks WHERE coverArtId IS NOT NULL",
    )
    suspend fun allCoverArtIds(): List<String>

    /**
     * Point every track at its album's cover. Rows from clients that once gave
     * songs covers of their own (Navidrome's per-file `mf-` ids, Jellyfin's
     * per-track images) held a copy of the sleeve each, and an album whose art
     * changes leaves its songs on the old id. Idempotent and cheap: runs at
     * launch (App.boot) and after every sync.
     */
    @Query(
        "UPDATE tracks SET coverArtId = (SELECT a.coverArtId FROM albums a WHERE a.id = tracks.albumId) " +
            "WHERE albumId IS NOT NULL " +
            "AND (SELECT a.coverArtId FROM albums a WHERE a.id = tracks.albumId) IS NOT NULL " +
            "AND coverArtId IS NOT (SELECT a.coverArtId FROM albums a WHERE a.id = tracks.albumId)",
    )
    suspend fun collapseTrackCovers(): Int

    /** The covers of downloaded songs — the sleeves an offline library needs. */
    @Query(
        "SELECT DISTINCT t.coverArtId FROM tracks t JOIN downloads d ON d.trackId = t.id " +
            "WHERE t.coverArtId IS NOT NULL",
    )
    suspend fun downloadedCoverArtIds(): List<String>

    // --- downloads -----------------------------------------------------------

    /**
     * The one observed read of this table — see LibraryRepository.downloadFiles.
     *
     * A projection, not `SELECT *`: the lyrics column holds a whole song's text,
     * and selecting it for every row pushes the query past the 2MB CursorWindow,
     * where Room fails mid-read with "Couldn't read row N". [download] fetches
     * the words one row at a time.
     */
    @Transaction
    @Query("SELECT trackId, albumId, fileName, format, bytes, downloadedAtMs FROM downloads")
    fun observeDownloads(): Flow<List<DownloadFile>>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    suspend fun download(trackId: String): DownloadEntity?

    /** Ids already downloaded, so a bulk enqueue can filter in one query. */
    @Query("SELECT trackId FROM downloads")
    suspend fun downloadedIds(): List<String>

    /**
     * Downloads with no words stored — see Downloader.refillMissingLyrics. Ids
     * only: the lyrics column across every row blows the CursorWindow, as
     * [observeDownloads] explains.
     */
    @Query("SELECT trackId FROM downloads WHERE lyrics IS NULL LIMIT :limit")
    suspend fun downloadsMissingLyrics(limit: Int): List<String>

    @Upsert
    suspend fun upsertDownload(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun deleteDownload(trackId: String)

    @Query("DELETE FROM downloads")
    suspend fun clearDownloads()

    /**
     * Give downloads back the album they belong to. A rebuilt database (the SDK's
     * drop-everything fallback) is re-indexed from the files on disk, whose names
     * carry only a track id — see Downloader.reindexFromDisk; without this the
     * "whole album downloaded" mark is gone until each track is fetched again.
     */
    @Query(
        "UPDATE downloads SET albumId = " +
            "(SELECT albumId FROM tracks WHERE tracks.id = downloads.trackId) " +
            "WHERE albumId IS NULL",
    )
    suspend fun backfillDownloadAlbums()

    // --- download top-up -----------------------------------------------------
    //
    // What an offline mode wants, as ids, so Downloader.topUp never holds a
    // source's library as tracks. Each takes the source's hidden libraries (see
    // [KEPT]); in rowid order.

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE $KEPT)")
    suspend fun hasKeptTracks(hidden: List<String>, noneHidden: Boolean): Boolean

    @Query("SELECT id FROM tracks WHERE liked = 1 AND $KEPT ORDER BY rowid")
    suspend fun likedKeptTrackIds(hidden: List<String>, noneHidden: Boolean): List<String>

    /** Which of [ids] — a playlist's songs, say — this library holds. */
    @Query("SELECT id FROM tracks WHERE id IN (:ids) AND $KEPT ORDER BY rowid")
    suspend fun keptTrackIdsAmong(ids: List<String>, hidden: List<String>, noneHidden: Boolean): List<String>

    @Query(
        "SELECT id FROM tracks WHERE albumId IN " +
            "(SELECT id FROM albums WHERE liked = 1 AND (libraryId IS NULL OR libraryId NOT IN (:hidden))) " +
            "ORDER BY rowid",
    )
    suspend fun likedAlbumTrackIds(hidden: List<String>): List<String>

    /** Every album-artist credit as stored, once each — split in Kotlin by Track.albumArtistNames' rule. */
    @Query("SELECT DISTINCT albumArtist FROM tracks")
    suspend fun albumArtistCredits(): List<String>

    /** The track artists standing in for these blank credits — see Track.albumArtistNames. */
    @Query("SELECT DISTINCT artist FROM tracks WHERE albumArtist IN (:credits)")
    suspend fun artistsCreditedAs(credits: List<String>): List<String>

    @Query("SELECT id FROM tracks WHERE albumArtist IN (:credits) AND $KEPT ORDER BY rowid")
    suspend fun keptTrackIdsCredited(credits: List<String>, hidden: List<String>, noneHidden: Boolean): List<String>

    @Query("SELECT id FROM tracks WHERE albumArtist IN (:credits) AND artist IN (:artists) AND $KEPT ORDER BY rowid")
    suspend fun keptTrackIdsByArtist(
        credits: List<String>,
        artists: List<String>,
        hidden: List<String>,
        noneHidden: Boolean,
    ): List<String>

    /** Everything else Everything mode wants: whatever isn't on the phone yet. */
    @Query("SELECT id FROM tracks WHERE id NOT IN (SELECT trackId FROM downloads) AND $KEPT ORDER BY rowid")
    suspend fun keptTrackIdsNotDownloaded(hidden: List<String>, noneHidden: Boolean): List<String>

    // --- playlist membership -------------------------------------------------

    /**
     * All stored membership, read once when a database opens — see
     * LibraryPlaylists.seedPlaylistTrackIds. Read, not observed: its one writer
     * already holds the live answer in memory, and observing it back would open
     * a window where a second caller sees "not known yet" and fetches again.
     * Ordered so each playlist keeps its own order.
     */
    @Query("SELECT playlistId, trackId FROM playlist_tracks ORDER BY playlistId, position")
    suspend fun storedPlaylistTracks(): List<PlaylistTrackRow>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTracks(rows: List<PlaylistTrackEntity>)

    /** Replace one playlist's membership. Rows are positional: clear first, or the old tail survives. */
    @Transaction
    suspend fun setPlaylistTracks(playlistId: String, rows: List<PlaylistTrackEntity>) {
        clearPlaylistTracks(playlistId)
        if (rows.isNotEmpty()) insertPlaylistTracks(rows)
    }

    /** Drop membership for playlists the server no longer lists. */
    @Query("DELETE FROM playlist_tracks WHERE playlistId NOT IN (:keep)")
    suspend fun prunePlaylistTracks(keep: List<String>)

    @Query("DELETE FROM playlist_tracks")
    suspend fun clearAllPlaylistTracks()
}

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        LikedArtistEntity::class,
        DownloadEntity::class,
        TopSongEntity::class,
        PlaylistTrackEntity::class,
    ],
    version = 14,
    exportSchema = true,
    // Generated from the committed schemas in tool/src/main/assets/schemas (assets,
    // because that is what Light's store builder copies). Every schema change adds
    // a step here rather than lean on the SDK's drop-everything fallback.
    autoMigrations = [
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        // The index on tracks.albumId: Room copies the tracks table into an indexed
        // one, once, inside SQLite (a tool can't hand Room a bare CREATE INDEX).
        // Storage order is kept; rowids are renumbered, which nothing keeps.
        AutoMigration(from = 12, to = 13),
        // Adds playlist_tracks, empty: "nothing primed yet", refilled as on a fresh install.
        AutoMigration(from = 13, to = 14),
    ],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

private const val LIBRARY_DB_NAME = "amp-library.db"

/**
 * The Room file a source's library lives in. The legacy source keeps the original
 * name, from before sources existed — a persisted name, pinned by tests.
 */
internal fun libraryDatabaseName(sourceId: String): String =
    if (sourceId == AppSettings.LEGACY_SOURCE_ID) LIBRARY_DB_NAME else "$LIBRARY_DB_NAME-$sourceId"

fun TrackEntity.toTrack(): Track = Track(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    albumId = albumId,
    coverArtId = coverArtId,
    durationMs = durationMs,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    playCount = playCount,
    lastPlayedMs = lastPlayedMs,
    liked = liked,
    rating = rating,
    genre = genre,
    composer = composer,
    streamPath = streamPath,
    gainDb = gainDb,
)

fun Track.toEntity(): TrackEntity = TrackEntity(
    id = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    albumId = albumId,
    coverArtId = coverArtId,
    durationMs = durationMs,
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
    playCount = playCount,
    lastPlayedMs = lastPlayedMs,
    liked = liked,
    rating = rating,
    genre = genre,
    composer = composer,
    streamPath = streamPath,
    gainDb = gainDb,
)

fun AlbumEntity.toAlbum(): Album = Album(
    id = id,
    title = title,
    artist = artist,
    coverArtId = coverArtId,
    durationMs = durationMs,
    songCount = songCount,
    year = year,
    releaseDate = releaseDate,
    createdMs = createdMs,
    updatedMs = updatedMs,
    playCount = playCount,
    lastPlayedMs = lastPlayedMs,
    liked = liked,
    rating = rating,
    genre = genre,
)

fun Album.toEntity(libraryId: String? = null): AlbumEntity = AlbumEntity(
    libraryId = libraryId,
    id = id,
    title = title,
    artist = artist,
    coverArtId = coverArtId,
    durationMs = durationMs,
    songCount = songCount,
    year = year,
    releaseDate = releaseDate,
    createdMs = createdMs,
    updatedMs = updatedMs,
    playCount = playCount,
    lastPlayedMs = lastPlayedMs,
    liked = liked,
    rating = rating,
    genre = genre,
)

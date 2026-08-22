package com.sublunar.amp.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.OnConflictStrategy
import androidx.room.Insert
import androidx.room.AutoMigration
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.sublunar.amp.data.Album
import com.sublunar.amp.data.Track
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tracks")
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
    /** Navidrome star rating, 0 when unrated. */
    val rating: Int = 0,
    val genre: String = "",
    val composer: String = "",
    /** Plex hands out a file path for original-quality playback; see [Track]. */
    val streamPath: String = "",
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
    val playCount: Int,
    val lastPlayedMs: Long,
    val liked: Boolean,
    /** Navidrome star rating, 0 when unrated. */
    val rating: Int = 0,
    val genre: String = "",
    /**
     * Which of the server's libraries this album came from — a Subsonic music
     * folder, or null where the server has only one (or the row predates this).
     *
     * Recorded so switching library is a filter over the cache rather than a
     * fetch. It used to be clear-and-re-sync, which cannot work with no server
     * to sync from: offline, the switch emptied the library and left it empty
     * while the downloaded files sat on disk. See LibraryRepository.allAlbums.
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
 * Ids only — the tracks themselves are library rows, and a popular song that
 * isn't in the cached library is left out when read back rather than written
 * in: rows from outside the library are how one server's songs once ended up
 * in another's. Kept per source with the rest of its cache, and refreshed
 * quietly once [fetchedAtMs] is old — see LibraryRepository.topSongsForArtist.
 */
@Entity(tableName = "top_songs", primaryKeys = ["artist", "position"])
data class TopSongEntity(
    val artist: String,
    val position: Int,
    val trackId: String,
    val fetchedAtMs: Long,
)

/**
 * One downloaded track. The audio lives on disk under the tool's files dir; this
 * row is the index over it (what format it's in, how big it is, and any lyrics
 * captured alongside so offline playback can still show them).
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

/** Just enough of a download row to decide whether an album is complete. */
@Dao
interface LibraryDao {

    /**
     * The whole table, and the reason these carry [Transaction].
     *
     * A result this size doesn't fit one CursorWindow, so reading it means
     * several fills — and each fill is its own snapshot unless something holds
     * them together. A sync writing underneath between two of them throws
     * "Couldn't read row N from CursorWindow" and takes the app down; switching
     * source is the reliable way to see it, because that starts a full read and
     * a full sync at the same moment. Making each write atomic (see
     * [replaceAlbumTracks]) narrowed the window without closing it. A read
     * transaction closes it: the whole list comes from one snapshot, and in WAL
     * mode it doesn't hold writers up to get it.
     */
    @Transaction
    @Query("SELECT * FROM tracks")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Transaction
    @Query("SELECT * FROM albums")
    fun observeAlbums(): Flow<List<AlbumEntity>>

    @Transaction
    @Query("SELECT * FROM albums")
    suspend fun allAlbumsSnapshot(): List<AlbumEntity>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId")
    suspend fun tracksForAlbum(albumId: String): List<TrackEntity>

    @Query("DELETE FROM tracks WHERE albumId = :albumId")
    suspend fun deleteTracksForAlbum(albumId: String)

    /**
     * Tracks belonging to no album this library holds.
     *
     * Pruning walks the albums it knows about, so a track whose album row was
     * never written is invisible to it — and stays for good, because every later
     * sync asks the same album-shaped question. That is what a cross-source
     * write leaves behind: another server's songs, with none of its albums, in a
     * library that has no idea they are there.
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE albumId IS NOT NULL AND albumId NOT IN (SELECT id FROM albums)")
    suspend fun orphanedTrackCount(): Int

    @Query("DELETE FROM tracks WHERE albumId IS NOT NULL AND albumId NOT IN (SELECT id FROM albums)")
    suspend fun deleteOrphanedTracks()

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun tracksByIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    /**
     * Replace the tracks of several albums in one transaction.
     *
     * A sync that deletes and re-inserts album by album invalidates the tracks
     * table twice per album, and every invalidation re-runs the observers' full
     * `SELECT * FROM tracks`. At eight thousand rows that cursor pages its window,
     * and rows shifting underneath it between fills throws "Couldn't read row N
     * from CursorWindow". Batching cuts the invalidations by the batch size and
     * keeps each swap atomic.
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
     * Swap the whole set of liked tracks in one go.
     *
     * Clearing and re-liking as two writes leaves a moment where nothing is
     * liked, and every observer sees it: open Liked Songs while a sync is
     * passing through that gap and the page reads "No liked songs" for a beat
     * before the list comes back. One transaction, one invalidation, no gap.
     */
    @Transaction
    suspend fun replaceTrackLikes(ids: List<String>) {
        clearTrackLikes()
        if (ids.isNotEmpty()) likeTracks(ids)
    }

    /** The same for artists, which the sync rewrites the same way. */
    @Transaction
    suspend fun replaceLikedArtists(artists: List<LikedArtistEntity>) {
        clearLikedArtists()
        if (artists.isNotEmpty()) likeArtists(artists)
    }

    @Query("UPDATE tracks SET liked = 0")
    suspend fun clearTrackLikes()

    @Query("UPDATE tracks SET liked = 1 WHERE id IN (:ids)")
    suspend fun likeTracks(ids: List<String>)

    @Query("UPDATE tracks SET liked = :liked WHERE id = :id")
    suspend fun setTrackLiked(id: String, liked: Boolean)

    @Query("UPDATE albums SET liked = :liked WHERE id = :id")
    suspend fun setAlbumLiked(id: String, liked: Boolean)

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    // --- liked artists -------------------------------------------------------

    @Query("SELECT name FROM liked_artists")
    fun observeLikedArtists(): Flow<List<String>>

    @Upsert
    suspend fun likeArtists(artists: List<LikedArtistEntity>)

    @Query("DELETE FROM liked_artists WHERE name = :name")
    suspend fun unlikeArtist(name: String)

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

    // --- downloads -----------------------------------------------------------

    /** Ids already downloaded, so a bulk enqueue can filter in one query. */
    @Query("SELECT trackId FROM downloads")
    suspend fun downloadedIds(): List<String>

    /** Every cover this source's cache names, so Log Out can drop them from disk. */
    @Query(
        "SELECT DISTINCT coverArtId FROM albums WHERE coverArtId IS NOT NULL " +
            "UNION SELECT DISTINCT coverArtId FROM tracks WHERE coverArtId IS NOT NULL",
    )
    suspend fun allCoverArtIds(): List<String>

    /**
     * Point every track at its album's cover, once.
     *
     * Rows synced before the clients did this themselves carry a cover id of
     * their own — Navidrome's per-file `mf-` ids, Jellyfin's per-track images —
     * and every one was a separate copy of the same sleeve on disk. Idempotent
     * and cheap, so it simply runs at each launch; see App.boot.
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

    /** Albums with no library recorded — see LibraryRepository's retag pass. */
    @Query("SELECT COUNT(*) FROM albums WHERE libraryId IS NULL")
    suspend fun untaggedAlbumCount(): Int

    @Query("SELECT id FROM albums WHERE libraryId IS NULL")
    suspend fun untaggedAlbumIds(): List<String>

    /** Stamp these albums as belonging to [libraryId]; answers how many rows took it. */
    @Query("UPDATE albums SET libraryId = :libraryId WHERE id IN (:ids)")
    suspend fun tagAlbums(libraryId: String, ids: List<String>): Int

    /** How many tracks are actually cached per album, for sync reconciliation. */
    @Query("SELECT albumId AS albumId, COUNT(*) AS tracks FROM tracks WHERE albumId IS NOT NULL GROUP BY albumId")
    suspend fun trackCountsByAlbum(): List<AlbumTrackCount>

    /**
     * The one observed read of this table — see [LibraryRepository.downloadFiles],
     * which shares it out to everything derived from downloads.
     *
     * A projection, not `SELECT *`: the lyrics column holds a whole song's text,
     * and selecting it for every row pushes the query past the 2MB CursorWindow,
     * where Room fails mid-read with "Couldn't read row N" — which is what
     * deleting a download used to crash on. Nothing observing this needs the
     * words; [download] fetches them one row at a time.
     */
    @Transaction
    @Query("SELECT trackId, albumId, fileName, format, bytes, downloadedAtMs FROM downloads")
    fun observeDownloads(): Flow<List<DownloadFile>>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    suspend fun download(trackId: String): DownloadEntity?

    /**
     * Downloads with no words stored — see [Downloader.refillMissingLyrics].
     *
     * Ids only, deliberately: selecting the lyrics column across every row is
     * exactly what blows the CursorWindow, as the projection above explains.
     */
    @Query("SELECT trackId FROM downloads WHERE lyrics IS NULL LIMIT :limit")
    suspend fun downloadsMissingLyrics(limit: Int): List<String>

    @Query("SELECT IFNULL(SUM(bytes), 0) FROM downloads")
    suspend fun downloadedBytes(): Long

    @Upsert
    suspend fun upsertDownload(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE trackId = :trackId")
    suspend fun deleteDownload(trackId: String)

    @Query("DELETE FROM downloads")
    suspend fun clearDownloads()

    /**
     * Give downloads back the album they belong to.
     *
     * A schema bump drops every table, and the index is then rebuilt from the
     * files on disk — which carry a track id in their name and nothing else. Rows
     * reindexed that way have no albumId, so the "whole album downloaded" mark
     * vanished from every album list until each track was fetched again. This
     * puts it back from the library as soon as there is a library to ask.
     */
    @Query(
        "UPDATE downloads SET albumId = " +
            "(SELECT albumId FROM tracks WHERE tracks.id = downloads.trackId) " +
            "WHERE albumId IS NULL",
    )
    suspend fun backfillDownloadAlbums()

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
}

@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        LikedArtistEntity::class,
        DownloadEntity::class,
        TopSongEntity::class,
    ],
    version = 10,
    exportSchema = true,
    // Generated from the committed schemas (tool/schemas), so existing installs
    // keep their libraries and downloads across the bump. Every schema change
    // from here on should add a step here rather than lean on the SDK's
    // drop-everything fallback.
    autoMigrations = [AutoMigration(from = 9, to = 10)],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

// --- entity <-> domain ------------------------------------------------------

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
    playCount = playCount,
    lastPlayedMs = lastPlayedMs,
    liked = liked,
    rating = rating,
    genre = genre,
)

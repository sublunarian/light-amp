package com.sublunar.amp.data.db

import androidx.room.Dao
import androidx.room.Database
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
    val compilation: Boolean = false,
)

/**
 * Liked artists, stored by name. The Artists list is derived from track tags
 * rather than fetched, so a name is the only stable key we can join on; the
 * server's own artist ids are resolved on demand when starring.
 */
@Entity(tableName = "liked_artists")
data class LikedArtistEntity(@PrimaryKey val name: String)

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

    // --- downloads -----------------------------------------------------------

    /** Ids already downloaded, so a bulk enqueue can filter in one query. */
    @Query("SELECT trackId FROM downloads")
    suspend fun downloadedIds(): List<String>

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
    ],
    version = 7,
    exportSchema = false,
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
    compilation = compilation,
)

fun Album.toEntity(): AlbumEntity = AlbumEntity(
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
    compilation = compilation,
)

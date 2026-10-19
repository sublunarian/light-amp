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
 * Indexed on albumId because a sync asks by album: every batch deletes forty
 * albums' songs one album at a time, and an album page and each batch's
 * comparison read them back the same way. Without an index each of those was
 * a scan of the whole table — on a large library a batch's write took
 * seconds, all of it holding the writer up.
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
    /** Navidrome star rating, 0 when unrated. */
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

/** A song as one page of [LibraryDao.readTracks] has it: with the rowid it is stored at. */
data class TrackRow(val rowId: Long, @Embedded val track: Track)

/**
 * The most ids one statement binds. The SQLite in Android 14 takes 32,766
 * variables, which is what a single `IN (:ids)` here always leaned on; a list
 * longer than this is sent in pieces.
 */
const val MAX_BOUND_IDS = 30_000

/**
 * A track a download top-up may fetch: not in a library its source hides —
 * the same narrowing App.topUpDownloads once made over the whole table in
 * Kotlin. An album with no library recorded shows everywhere, a track with no
 * album is kept, and with nothing hidden nothing is narrowed at all.
 */
private const val KEPT =
    "(:noneHidden OR albumId IS NULL OR albumId IN " +
        "(SELECT id FROM albums WHERE libraryId IS NULL OR libraryId NOT IN (:hidden)))"

/** Just enough of a download row to decide whether an album is complete. */
@Dao
interface LibraryDao {

    /**
     * Moves on every write to the tracks table: Room runs it again after each
     * one. Not read for the number — it is what LibraryRepository's library
     * list reads the table again on, through [readTracks].
     */
    @Query("SELECT COUNT(*) FROM tracks")
    fun observeTrackCount(): Flow<Int>

    /** One page of [readTracks]: the rows stored after rowid [after], in storage order. */
    @Query("SELECT rowid AS rowId, * FROM tracks WHERE rowid > :after ORDER BY rowid LIMIT :limit")
    suspend fun trackPage(after: Long, limit: Int): List<TrackRow>

    /**
     * The whole table, [pageSize] rows at a time, in storage order — the order
     * a plain `SELECT * FROM tracks` has, which the library list and every
     * sort and search downstream of it have always had.
     *
     * Paged so a read holds one page of new rows at a time, not a whole second
     * library: each page goes to [consume] before the next is read, and only
     * the rows it finds changed are kept (see TrackRead).
     *
     * And one transaction, the reason this and the albums below carry
     * [Transaction]. A result this size doesn't fit one CursorWindow, so
     * reading it means several fills — and each fill is its own snapshot
     * unless something holds them together. A sync writing underneath between
     * two of them threw "Couldn't read row N from CursorWindow" and took the
     * app down; switching source is the reliable way to see it, because that
     * starts a full read and a full sync at the same moment. Pages are the
     * same: a batch landing between two of them would move songs from one
     * side of the boundary to the other. In one transaction every page comes
     * from the same snapshot.
     *
     * Writes wait while it reads. They waited for the single read it replaces
     * too — Room ran that on its one transaction thread, where every write
     * goes — and a read that keeps the rows it already has is the shorter wait.
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

    /** The cached rows of several albums at once — one sync batch's, see LibraryRepository.fetchAndStoreSongs. */
    @Query("SELECT * FROM tracks WHERE albumId IN (:albumIds)")
    suspend fun tracksForAlbums(albumIds: List<String>): List<TrackEntity>

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

    /** Straight into [Track]: reading the entity and copying it made two objects a row. At most [MAX_BOUND_IDS] ids at a time. */
    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun tracksByIds(ids: List<String>): List<Track>

    /** Any one song, for the probes that need a real id — see ServerFeatures. */
    @Query("SELECT id FROM tracks LIMIT 1")
    suspend fun anyTrackId(): String?

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    /**
     * Replace the tracks of several albums in one transaction.
     *
     * A sync that deletes and re-inserts album by album invalidates the tracks
     * table twice per album, and every invalidation asks for a whole read of
     * the library again (see LibraryRepository.readTracks). Batching cuts the
     * invalidations by the batch size and keeps each swap atomic.
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
     * Make the liked tracks exactly [ids], in one go; answers how many rows
     * changed.
     *
     * Only the rows whose like moves are written. Room re-reads every observer
     * of a table after a write that touched any row of it, so clearing every
     * like and setting them again — what this was — rebuilt the whole library
     * list on every sync, Plex's included, which has no likes at all. An
     * update that matches nothing fires nothing.
     *
     * One transaction still: two writes leave a moment where the likes are
     * half moved, and Liked Songs open during a sync would show it.
     */
    @Transaction
    suspend fun replaceTrackLikes(ids: List<String>): Int {
        if (ids.size <= MAX_BOUND_IDS) return unlikeTracksOtherThan(ids) + likeTracks(ids)
        // More than one statement can bind: the same two updates, in pieces.
        val keep = ids.toHashSet()
        return likedTrackIds().filterNot { it in keep }.chunked(MAX_BOUND_IDS).sumOf { unlikeTracks(it) } +
            ids.chunked(MAX_BOUND_IDS).sumOf { likeTracks(it) }
    }

    /** The same for artists, which the sync replaces the same way; answers how many rows changed. */
    @Transaction
    suspend fun replaceLikedArtists(names: Set<String>): Int {
        val gone = likedArtistNames().filterNot { it in names }
        gone.chunked(MAX_BOUND_IDS).forEach { unlikeArtists(it) }
        return gone.size + likeArtistsIfNew(names.map(::LikedArtistEntity)).count { it != -1L }
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

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

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

    // --- downloads -----------------------------------------------------------

    /** Ids already downloaded, so a bulk enqueue can filter in one query. */
    @Query("SELECT trackId FROM downloads")
    suspend fun downloadedIds(): List<String>

    // --- download top-up -----------------------------------------------------
    //
    // What an offline mode wants, as ids, so App.topUpDownloads never holds a
    // source's library as tracks — that was a whole second copy of it, kept
    // through a playlist fetch, and in Everything mode kept again in the queue.
    // Each takes the source's hidden libraries; see [KEPT]. In rowid order,
    // the order the whole-table read had.

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
    version = 13,
    exportSchema = true,
    // Generated from the committed schemas (tool/src/main/assets/schemas), so
    // existing installs keep their libraries and downloads across the bump —
    // they sit in assets because that is what Light's store builder copies.
    // Every schema change
    // from here on should add a step here rather than lean on the SDK's
    // drop-everything fallback.
    autoMigrations = [
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        // The index on tracks.albumId. Room counts a new index as a change it
        // can't make in place, so this copies the tracks table into a new one
        // and indexes that: once, at the first open after the update, inside
        // SQLite rather than on the heap. A bare CREATE INDEX would be cheaper,
        // but a tool has no way to hand Room a migration of its own. The copy
        // keeps the storage order; it renumbers the rowids, which nothing keeps.
        AutoMigration(from = 12, to = 13),
    ],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}

private const val LIBRARY_DB_NAME = "amp-library.db"

/**
 * The Room file a source's library lives in.
 *
 * The first server keeps the original file name, so an install from before
 * sources existed finds its cache rather than syncing afresh.
 */
internal fun libraryDatabaseName(sourceId: String): String =
    if (sourceId == AppSettings.LEGACY_SOURCE_ID) LIBRARY_DB_NAME else "$LIBRARY_DB_NAME-$sourceId"

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

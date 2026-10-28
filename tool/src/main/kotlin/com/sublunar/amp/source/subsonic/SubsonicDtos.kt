package com.sublunar.amp.source.subsonic

import com.sublunar.amp.data.Album
import com.sublunar.amp.data.LyricLine
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.Track
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serializable views of the Subsonic JSON responses. Every field is optional so
 * a single envelope type can decode any endpoint; the client reads only the
 * branch it asked for. Unknown keys are ignored by the Json configuration.
 */
@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicBody? = null,
)

@Serializable
data class SubsonicBody(
    val status: String = "failed",
    val version: String? = null,
    /** OpenSubsonic servers name themselves and their own version in every reply. */
    val type: String? = null,
    val serverVersion: String? = null,
    val error: SubsonicError? = null,
    val albumList2: AlbumListDto? = null,
    val album: AlbumDto? = null,
    val starred2: Starred2Dto? = null,
    /** getStarred, for servers without getStarred2 — see SubsonicClient.getStarred. */
    val starred: Starred2Dto? = null,
    val playlists: PlaylistsDto? = null,
    val playlist: PlaylistDto? = null,
    val lyricsList: LyricsListDto? = null,
    val lyrics: PlainLyricsDto? = null,
    val musicFolders: MusicFoldersDto? = null,
    val artists: ArtistsRootDto? = null,
    val topSongs: TopSongsDto? = null,
    val similarSongs: SimilarSongsDto? = null,
    val scanStatus: ScanStatusDto? = null,
    val playQueue: PlayQueueDto? = null,
    /**
     * getOpenSubsonicExtensions: what a server offers beyond Subsonic's own
     * calls. Kept as it came, because only the names are read, and a shape
     * nobody expected must not cost the rest of the reply.
     */
    val openSubsonicExtensions: JsonElement? = null,
) {
    /** The names [openSubsonicExtensions] lists, whatever else each entry carries. */
    val extensionNames: List<String>
        get() = (openSubsonicExtensions as? JsonArray).orEmpty().mapNotNull { entry ->
            ((entry as? JsonObject)?.get("name") as? JsonPrimitive)?.content
        }
}

/**
 * getPlayQueue: what this account was last playing, on whichever client saved it.
 *
 * `current` is a song id, and servers disagree about its type — so it is decoded
 * as a raw primitive and read back as text, the same treatment [MusicFolderDto]
 * needs for the same reason.
 */
@Serializable
data class PlayQueueDto(
    val current: JsonPrimitive? = null,
    val position: Long? = null,
    val entry: List<SongDto> = emptyList(),
) {
    val currentId: String? get() = current?.content?.takeIf { it.isNotBlank() }
}

/** getScanStatus: whether the server is walking its music folders. */
@Serializable
data class ScanStatusDto(
    val scanning: Boolean = false,
    /** When the last scan ran (OpenSubsonic); see SubsonicClient.songChangesSince. */
    val lastScan: String? = null,
)

/** getArtists: artists grouped into alphabetical index buckets. */
@Serializable
data class ArtistsRootDto(val index: List<ArtistIndexDto> = emptyList())

@Serializable
data class ArtistIndexDto(val artist: List<ArtistDto> = emptyList())

@Serializable
data class ArtistDto(
    val id: String,
    val name: String? = null,
)

@Serializable
data class TopSongsDto(val song: List<SongDto> = emptyList())

@Serializable
data class SimilarSongsDto(val song: List<SongDto> = emptyList())

@Serializable
data class MusicFoldersDto(val musicFolder: List<MusicFolderDto> = emptyList())

@Serializable
data class MusicFolderDto(
    // Subsonic returns a numeric id; Navidrome libraries may use other shapes,
    // so decode as a raw primitive and read `.content` for a stable String.
    val id: JsonPrimitive? = null,
    val name: String? = null,
)

@Serializable
data class SubsonicError(val code: Int = 0, val message: String? = null)

/** getPlaylist read for its song ids alone: nothing else of each entry is kept. */
@Serializable
class PlaylistIdsEnvelope(@SerialName("subsonic-response") val response: PlaylistIdsBody? = null)

@Serializable
class PlaylistIdsBody(
    val status: String = "failed",
    val error: SubsonicError? = null,
    val playlist: PlaylistIds? = null,
)

@Serializable
class PlaylistIds(val entry: List<EntryId> = emptyList())

@Serializable
class EntryId(val id: String)

@Serializable
data class AlbumListDto(val album: List<AlbumDto> = emptyList())

/** getStarred2's `starred2`, and getStarred's `starred`: same lists, ids alike. */
@Serializable
data class Starred2Dto(
    val song: List<SongDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val artist: List<ArtistDto> = emptyList(),
)

@Serializable
data class PlaylistsDto(val playlist: List<PlaylistDto> = emptyList())

@Serializable
data class AlbumDto(
    val id: String,
    val name: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val created: String? = null,
    val played: String? = null,
    val playCount: Int? = null,
    // OpenSubsonic full release dates. `year` alone can't order two releases from
    // the same year, so prefer these when the server sends them.
    val originalReleaseDate: ItemDateDto? = null,
    val releaseDate: ItemDateDto? = null,
    /**
     * OpenSubsonic album version — Navidrome fills this from the MusicBrainz
     * release comment ("printed in EU", "price code CA 835") and *also* appends it
     * to [name] so clients that don't know the field still see it. Kept so the
     * appended copy can be removed exactly rather than guessed at.
     */
    val version: String? = null,
    /** Subsonic userRating, 1–5; absent or 0 means unrated. */
    val userRating: Int? = null,
    val genre: String? = null,
    /**
     * OpenSubsonic's structured album-artist credit, and the reliable one: the
     * legacy [artist] flattens several names into one string with whichever
     * separator the server likes. Taking the names from here means the app does
     * its own joining and never has to guess where one ends.
     */
    val artists: List<AlbumArtistDto> = emptyList(),
    /** OpenSubsonic's single-string form of the same credit. */
    val displayArtist: String? = null,
    val song: List<SongDto> = emptyList(),
)

/**
 * The record's own artist: the structured credit joined with the separator the
 * app splits on (see Track.albumArtistNames), so several names come apart again
 * cleanly; else the single-string forms.
 */
internal val AlbumDto.recordArtist: String?
    get() = artists.mapNotNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ")
        ?: displayArtist?.takeIf { it.isNotBlank() }
        ?: artist

/** One name in an album's artist credit. Everything optional: servers vary. */
@Serializable
data class AlbumArtistDto(val name: String? = null)

/** OpenSubsonic ItemDate: any component may be missing. */
@Serializable
data class ItemDateDto(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
)

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String? = null,
    val songCount: Int? = null,
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
data class SongDto(
    val id: String,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    /**
     * OpenSubsonic's name for the same thing, and the one Navidrome actually
     * sends: the base Subsonic Child has no albumArtist field at all, so the
     * plain spelling above stays only for servers that add it themselves.
     */
    val displayAlbumArtist: String? = null,
    val albumId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val coverArt: String? = null,
    val duration: Int? = null,
    val starred: String? = null,
    /** Subsonic userRating, 1–5; absent or 0 means unrated. */
    val userRating: Int? = null,
    val playCount: Int? = null,
    val played: String? = null,
    /** Tag fields Navidrome sends when the file carries them. */
    val genre: String? = null,
    val composer: String? = null,
    /** The composer under OpenSubsonic's name, as with [displayAlbumArtist]: a Child has no plain `composer`. */
    val displayComposer: String? = null,
    /**
     * OpenSubsonic ReplayGain, sent when the file is tagged. Gains are dB
     * relative to reference loudness — negative for loud masters.
     */
    val replayGain: ReplayGainDto? = null,
)

@Serializable
data class ReplayGainDto(
    val trackGain: Float? = null,
    val albumGain: Float? = null,
)

@Serializable
data class LyricsListDto(val structuredLyrics: List<StructuredLyricDto> = emptyList())

@Serializable
data class StructuredLyricDto(
    val synced: Boolean = false,
    /** Milliseconds to shift every line by; positive shows the words sooner, as LRC's `[offset:]`. */
    val offset: Long? = null,
    val line: List<LyricLineDto> = emptyList(),
) {
    /** The timed lines, in order, shifted by [offset]; untimed and empty ones are left out. */
    fun timedLines(): List<LyricLine> = line
        .filter { !it.value.isNullOrBlank() && it.start != null }
        .map { LyricLine(timeMs = (it.start!! - (offset ?: 0L)).coerceAtLeast(0L), text = it.value.orEmpty().trim()) }
        .sortedBy { it.timeMs }
}

@Serializable
data class LyricLineDto(val start: Long? = null, val value: String? = null)

@Serializable
data class PlainLyricsDto(val value: String? = null)

internal fun AlbumDto.toAlbum(): Album = Album(
    id = id,
    title = albumTitle(name ?: title, version),
    artist = artist ?: "Unknown Artist",
    coverArtId = coverArt ?: id,
    durationMs = (duration ?: 0).toLong() * 1000L,
    songCount = songCount ?: 0,
    year = year,
    releaseDate = releaseDateKey(),
    createdMs = parseInstantMs(created),
    playCount = playCount ?: 0,
    lastPlayedMs = parseInstantMs(played),
    rating = userRating ?: 0,
    genre = genre.orEmpty(),
)

/**
 * The album's own name, with Navidrome's appended release comment removed.
 *
 * Navidrome sends the MusicBrainz disambiguation both as [AlbumDto.version]
 * and glued onto the end of the name, so "Alive 2007" arrives as
 * "Alive 2007 (printed in EU)". Only an exact match for the version string is
 * stripped — a trailing parenthesis is not evidence by itself, or "Apostrophe
 * (’)" and "Enter the Wu-Tang (36 Chambers)" would lose part of their real
 * titles. No version, or a name that doesn't end with it, means no change.
 */
internal fun albumTitle(raw: String?, version: String?): String {
    val name = raw?.trim().orEmpty().ifEmpty { return "Unknown Album" }
    val comment = version?.trim().orEmpty()
    if (comment.isEmpty()) return name
    for (suffix in listOf(" ($comment)", " [$comment]", " $comment")) {
        if (name.endsWith(suffix, ignoreCase = true)) {
            return name.dropLast(suffix.length).trim().ifEmpty { name }
        }
    }
    return name
}

/**
 * Release date flattened to a sortable YYYYMMDD number, 0 when unknown.
 * Prefers the *original* release date so a discography reads chronologically
 * rather than by remaster date; missing month/day sort before the same year's
 * dated releases.
 */
private fun AlbumDto.releaseDateKey(): Long {
    val date = originalReleaseDate?.takeIf { it.year != null }
        ?: releaseDate?.takeIf { it.year != null }
    val y = date?.year ?: year ?: return 0L
    return y * 10_000L + (date?.month ?: 0) * 100L + (date?.day ?: 0)
}

internal fun SongDto.toTrack(
    albumName: String? = null,
    albumArtistName: String? = null,
    /**
     * The parent album's cover id, where the caller has the album. Preferred
     * over the song's own: Navidrome gives every file an `mf-` id of its own
     * for the same sleeve, and keyed by those the cover cache held one copy
     * per song.
     */
    albumCoverArt: String? = null,
): Track {
    val coverId = albumCoverArt ?: coverArt ?: albumId ?: id
    return Track(
        id = id,
        title = title ?: "Unknown Title",
        artist = artist ?: "Unknown Artist",
        album = albumName ?: album ?: "Unknown Album",
        // The album's answer first — see getAlbumTracks. The track's own
        // artist is the last resort rather than the second, because it is
        // the one value here that is known not to be an album artist.
        albumArtist = albumArtistName
            ?: displayAlbumArtist?.takeIf { it.isNotBlank() }
            ?: albumArtist
            ?: artist
            ?: "Unknown Artist",
        albumId = albumId,
        coverArtId = coverId,
        durationMs = (duration ?: 0).toLong() * 1000L,
        trackNumber = track,
        discNumber = discNumber,
        year = year,
        playCount = playCount ?: 0,
        lastPlayedMs = parseInstantMs(played),
        liked = starred != null,
        rating = userRating ?: 0,
        genre = genre.orEmpty(),
        composer = displayComposer?.takeIf { it.isNotBlank() } ?: composer.orEmpty(),
        // Track gain first; a file tagged only album-wise still normalises.
        gainDb = replayGain?.trackGain ?: replayGain?.albumGain,
    )
}

internal fun PlaylistDto.toPlaylist(): Playlist = Playlist(
    id = id,
    name = name ?: "Playlist",
    coverArtId = coverArt,
    createdAt = parseInstantMs(created),
    updatedAt = parseInstantMs(changed),
    trackIds = emptyList(),
)

private fun parseInstantMs(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}

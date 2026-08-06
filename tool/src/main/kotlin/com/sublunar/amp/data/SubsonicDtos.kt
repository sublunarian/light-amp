package com.sublunar.amp.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    val error: SubsonicError? = null,
    val albumList2: AlbumListDto? = null,
    val album: AlbumDto? = null,
    val starred2: Starred2Dto? = null,
    val playlists: PlaylistsDto? = null,
    val playlist: PlaylistDto? = null,
    val lyricsList: LyricsListDto? = null,
    val lyrics: PlainLyricsDto? = null,
    val musicFolders: MusicFoldersDto? = null,
    val artists: ArtistsRootDto? = null,
    val topSongs: TopSongsDto? = null,
    val scanStatus: ScanStatusDto? = null,
)

/** getScanStatus: whether the server is walking its music folders. */
@Serializable
data class ScanStatusDto(val scanning: Boolean = false, val count: Long? = null)

/** getArtists: artists grouped into alphabetical index buckets. */
@Serializable
data class ArtistsRootDto(val index: List<ArtistIndexDto> = emptyList())

@Serializable
data class ArtistIndexDto(
    val name: String? = null,
    val artist: List<ArtistDto> = emptyList(),
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String? = null,
    val albumCount: Int? = null,
    val starred: String? = null,
)

@Serializable
data class TopSongsDto(val song: List<SongDto> = emptyList())

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

@Serializable
data class AlbumListDto(val album: List<AlbumDto> = emptyList())

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
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val created: String? = null,
    val played: String? = null,
    val playCount: Int? = null,
    val starred: String? = null,
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
    /**
     * Navidrome's compilation flag, spelled two ways across versions of the
     * API — `isCompilation` in OpenSubsonic, `compilation` before it.
     */
    val isCompilation: Boolean? = null,
    val compilation: Boolean? = null,
    val genre: String? = null,
    val song: List<SongDto> = emptyList(),
)

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
    val comment: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null,
    val entry: List<SongDto> = emptyList(),
)

@Serializable
data class SongDto(
    val id: String,
    val parent: String? = null,
    val title: String? = null,
    val name: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
    val path: String? = null,
    val created: String? = null,
    val starred: String? = null,
    /** Subsonic userRating, 1–5; absent or 0 means unrated. */
    val userRating: Int? = null,
    val playCount: Int? = null,
    val played: String? = null,
    /** Tag fields Navidrome sends when the file carries them. */
    val genre: String? = null,
    val composer: String? = null,
)

@Serializable
data class LyricsListDto(val structuredLyrics: List<StructuredLyricDto> = emptyList())

@Serializable
data class StructuredLyricDto(
    val synced: Boolean = false,
    val lang: String? = null,
    val offset: Long? = null,
    val line: List<LyricLineDto> = emptyList(),
)

@Serializable
data class LyricLineDto(val start: Long? = null, val value: String? = null)

@Serializable
data class PlainLyricsDto(val value: String? = null)

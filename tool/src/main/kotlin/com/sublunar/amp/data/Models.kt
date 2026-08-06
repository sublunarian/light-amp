package com.sublunar.amp.data

/** A single playable song. Mirrors the fields the UI and playback layers need. */
data class Track(
    val id: String,
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
    val liked: Boolean = false,
    /** Navidrome star rating 1–5, or 0 when unrated. */
    val rating: Int = 0,
    /** Tags, when the server sends them — blank means "this library has none". */
    val genre: String = "",
    val composer: String = "",
    /**
     * Where the server keeps the actual file, when it will name one.
     *
     * Subsonic streams by song id and never needs this; Plex will hand over the
     * file untouched, but only from a path it gives out alongside the track's
     * metadata. Blank means "ask by id", which is what every other server wants.
     */
    val streamPath: String = "",
)

/** An album as listed by the server (songs are fetched separately on demand). */
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val coverArtId: String?,
    val durationMs: Long,
    val songCount: Int,
    val year: Int?,
    // Release date as a sortable YYYYMMDD number (0 when the server sends none).
    // Ordering a discography needs finer granularity than the year alone.
    val releaseDate: Long = 0L,
    val createdMs: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0L,
    val liked: Boolean = false,
    /** Navidrome star rating 1–5, or 0 when unrated. */
    val rating: Int = 0,
    val genre: String = "",
    /** A record by several artists — "Various Artists" and its kin. */
    val compilation: Boolean = false,
)

/** Derived client-side by grouping tracks; the server is album-centric. */
data class Artist(
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0L,
    val liked: Boolean = false,
)

@kotlinx.serialization.Serializable
data class Playlist(
    val id: String,
    val name: String,
    val coverArtId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val trackIds: List<String>,
)

/** Starred ids returned together by getStarred2. */
data class Starred(
    val songIds: Set<String>,
    val albumIds: Set<String>,
    // Artists are matched by name: our Artist list is derived from track tags,
    // so the server's artist ids don't line up with it.
    val artistNames: Set<String> = emptySet(),
)

/** A server-side artist entry, used only to resolve ids for starring. */
data class ArtistRef(val id: String, val name: String)

/** A server library / music folder (Navidrome exposes each library as one). */
data class MusicFolder(val id: String, val name: String)

/**
 * Streaming format. "raw" streams the original file untouched; the others ask
 * Navidrome to transcode on the fly to something the device can always decode.
 */
enum class StreamFormat(val id: String, val maxBitRate: Int?) {
    MP3("mp3", 320),
    OPUS("opus", 192),
    FLAC("flac", null),
    RAW("raw", null);

    companion object {
        /**
         * Opus at 192 kbps: transparent for practically all material, ~40% the
         * size of MP3 320, and measurably cheaper for the server to transcode
         * (~26x realtime against MP3's ~12x on this library's ALAC sources).
         * Both streaming and downloads resolve their default through here.
         */
        val DEFAULT = OPUS

        fun fromId(id: String?): StreamFormat =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

enum class RepeatMode { OFF, TRACK, QUEUE }

data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

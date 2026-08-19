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

/**
 * Every album artist a track is credited to.
 *
 * A record with more than one arrives as a single string — "Johann Sebastian
 * Bach; Glenn Gould" — because that is what both the tag and the server's
 * display field are, so a client that reads it whole invents a compound artist
 * that exists on exactly one record. Each name is its own artist, and a record
 * counts toward all of them.
 *
 * Split on the semicolon **only**. A comma sits inside plenty of single names —
 * "Earth, Wind & Fire", "Emerson, Lake & Palmer", "Crosby, Stills & Nash" — and
 * splitting on it would tear those into artists who never existed. (The tag
 * lists take the opposite view: see LibraryRepository.splitTag, where a comma
 * really does separate two genres.)
 */
fun Track.albumArtistNames(): List<String> {
    val raw = albumArtist.ifBlank { artist }
    val names = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    return names.ifEmpty { listOfNotNull(raw.takeIf { it.isNotBlank() }) }
}

/**
 * The one to show where only one will fit — a record's first credit, which is
 * the one it is filed under everywhere else.
 */
fun Track.primaryAlbumArtist(): String =
    albumArtistNames().firstOrNull() ?: albumArtist.ifBlank { artist }

/**
 * One tag field, split into the values it actually holds.
 *
 * Navidrome joins a multi-valued tag with a comma or a semicolon, so "Jazz; Soul"
 * is two genres rather than one oddly named one. Unlike an artist credit — see
 * [albumArtistNames] — a comma here really is a separator, because a genre or a
 * composer is a single name and not a band called "Earth, Wind & Fire".
 */
fun splitTagValues(raw: String): List<String> =
    raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * What a library list has been narrowed to. Blank means "not narrowed".
 *
 * The two are independent and combine: a genre *and* a composer leaves what
 * carries both, which is the only reading that lets either be set without
 * silently clearing the other.
 */
data class TagFilter(val genre: String = "", val composer: String = "") {
    val isEmpty: Boolean get() = genre.isEmpty() && composer.isEmpty()
}

/** Whether this track carries [value] as one of its genres. */
fun Track.hasGenre(value: String): Boolean =
    splitTagValues(genre).any { it.equals(value, ignoreCase = true) }

/** Whether this track carries [value] as one of its composers. */
fun Track.hasComposer(value: String): Boolean =
    splitTagValues(composer).any { it.equals(value, ignoreCase = true) }

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
)

/** Derived client-side by grouping tracks; the server is album-centric. */
data class Artist(
    val name: String,
    /** The server's own picture of them, when it has one — see ArtistRef. */
    val imageId: String? = null,
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
    /**
     * The server owns this one's contents — Plex's smart playlists, which it
     * builds from a filter.
     *
     * They arrive from the same endpoint as ordinary playlists and are real
     * objects on the server, so a delete here would really delete one. Adding
     * to one is refused outright. Shown, because a smart playlist someone made
     * is still theirs to listen to — but not offered as something to change.
     */
    val readOnly: Boolean = false,
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
/**
 * An artist as the *server* knows them, which is not how the app does.
 *
 * The library's artists are derived from track tags, so they have names and
 * nothing else. This is the bridge to the server's own record of the same
 * artist: its id, for starring, and its picture.
 */
data class ArtistRef(
    val id: String,
    val name: String,
    /**
     * Cover id for their picture, in whatever form that server's coverArtUrl
     * takes: a path on Plex, the artist's own id on Subsonic — Navidrome serves
     * artist art from getCoverArt when given one.
     */
    val imageId: String? = null,
)

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

package com.sublunar.amp.data

/** A single playable song. */
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
    /** Star rating 1–5, or 0 when unrated. */
    val rating: Int = 0,
    /** Tags, when the server sends them — blank means "this library has none". */
    val genre: String = "",
    val composer: String = "",
    /**
     * Where the server keeps the actual file, when it will name one: Plex hands
     * over the original only from a path it gives out with the track's metadata.
     * Blank means "ask by id", which is what every other server wants.
     */
    val streamPath: String = "",
    /**
     * Loudness-normalisation gain in dB, when the server knows one (ReplayGain
     * track gain, or Jellyfin's own LUFS measurement). Stored but not applied:
     * the SDK has no way to set a player's volume yet (light-sdk#298), and
     * kept, ReplayGain can come back without every song being read again.
     */
    val gainDb: Float? = null,
    /**
     * The file's format as the server names it — a suffix or codec such as
     * flac, mp3, m4a, alac — lowercased; blank where the server didn't say.
     * With [bitRateKbps], decides whether converting it is worth anything:
     * see MusicSource.formatFor.
     */
    val codec: String = "",
    /** The file's own bitrate in kbps, where the server gives one. */
    val bitRateKbps: Int? = null,
    /**
     * Everyone the song itself credits, joined with "; " as [albumArtist] is, from
     * the server's own list. Blank where it sends none: [artist] stands in.
     */
    val artists: String = "",
)

/**
 * Whether the file is lossless: true for FLAC, ALAC, WAV and their kin, false
 * for MP3, AAC, Opus, Vorbis and the like, null when nothing says. An MP4
 * file can hold either, so it goes by its bitrate: no lossy codec is encoded
 * at 500 kbps or more, nor any lossless one below it.
 */
fun Track.isLossless(): Boolean? = when (codec) {
    in LOSSLESS_CODECS -> true
    in LOSSY_CODECS -> false
    in MP4_CONTAINERS -> bitRateKbps?.takeIf { it > 0 }?.let { it >= MP4_LOSSLESS_KBPS }
    else -> null
}

private val LOSSLESS_CODECS =
    setOf("flac", "alac", "wav", "wave", "aiff", "aif", "aifc", "ape", "wv", "tta", "dsf", "dff", "pcm")
private val LOSSY_CODECS =
    setOf("mp3", "aac", "opus", "ogg", "oga", "vorbis", "wma", "mpc", "mp2", "ac3", "eac3", "webm")
private val MP4_CONTAINERS = setOf("m4a", "mp4", "m4b")
private const val MP4_LOSSLESS_KBPS = 500

/**
 * Every album artist a track is credited to.
 *
 * Several arrive as one string ("Johann Sebastian Bach; Glenn Gould"); read
 * whole, that invents a compound artist owning one record. Each name is its own
 * artist, and the record counts toward all of them.
 *
 * Split on the semicolon **only**: a comma sits inside plenty of single names
 * ("Earth, Wind & Fire"). Tag values are the opposite — see [splitTagValues].
 */
fun Track.albumArtistNames(): List<String> = creditNames(albumArtist.ifBlank { artist })

/** The song's own artists, one name each: a compilation's real performers, a feature as its own name. */
fun Track.songArtistNames(): List<String> = creditNames(artists.ifBlank { artist })

/** Which credit makes someone an artist: the record's, or the song's own. */
enum class ArtistCredit {
    ALBUM,
    SONG,
    ;

    fun names(track: Track): List<String> = if (this == ALBUM) track.albumArtistNames() else track.songArtistNames()
}

/** [albumArtistNames] for one stored credit, without a track — the download top-up reads credits, not rows. */
fun creditNames(raw: String): List<String> {
    val names = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    return names.ifEmpty { listOfNotNull(raw.takeIf { it.isNotBlank() }) }
}

/** The one to show where only one fits: the first credit, which it is filed under everywhere else. */
fun Track.primaryAlbumArtist(): String =
    albumArtistNames().firstOrNull() ?: albumArtist.ifBlank { artist }

/**
 * One tag field, split into the values it holds. Servers join a multi-valued tag
 * with a comma or a semicolon, so both separate here — unlike an artist credit
 * (see [albumArtistNames]), a genre has no comma inside its name.
 */
fun splitTagValues(raw: String): List<String> =
    raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }

/** The genre a library list has been narrowed to; blank means "not narrowed". */
data class TagFilter(val genre: String = "") {
    val isEmpty: Boolean get() = genre.isEmpty()
}

/** Whether this track carries [value] as one of its genres. */
fun Track.hasGenre(value: String): Boolean =
    splitTagValues(genre).any { it.equals(value, ignoreCase = true) }

/** An album as listed by the server; its songs are fetched separately. */
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val coverArtId: String?,
    val durationMs: Long,
    val songCount: Int,
    val year: Int?,
    /** Release date as a sortable YYYYMMDD number, 0 when the server sends none. */
    val releaseDate: Long = 0L,
    val createdMs: Long = 0L,
    /**
     * When the *server* last changed this album, 0 where it doesn't say: the only
     * sign of change from a server that sends no song count (Plex) — see
     * LibrarySync.runSync.
     */
    val updatedMs: Long = 0L,
    val playCount: Int = 0,
    val lastPlayedMs: Long = 0L,
    val liked: Boolean = false,
    /** Star rating 1–5, or 0 when unrated. */
    val rating: Int = 0,
    val genre: String = "",
    /** What kind of record it is — see [ReleaseType]. Album where nothing says. */
    val releaseType: ReleaseType = ReleaseType.ALBUM,
)

/** Derived from the tracks' album-artist credits, not fetched. */
data class Artist(
    val name: String,
    /** The server's own picture of them, when it has one — see ArtistRef. */
    val imageId: String? = null,
    val albumCount: Int,
    val trackCount: Int,
    val playCount: Int = 0,
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
     * The server owns this one's contents — Plex's smart playlists, built from a
     * filter. Shown and playable, never offered for editing: they are real
     * objects on the server, so a delete would really delete one.
     */
    val readOnly: Boolean = false,
)

/** What the server has liked, read in one go by a sync. */
data class Starred(
    val songIds: Set<String>,
    val albumIds: Set<String>,
    /** By name: the app's artists are derived from track tags, so the server's artist ids don't line up. */
    val artistNames: Set<String> = emptySet(),
)

/**
 * An artist as the *server* knows them: the bridge from the app's name-only
 * artists to the server's record — its id, for liking, and its picture.
 */
data class ArtistRef(
    val id: String,
    val name: String,
    /**
     * Cover id for their picture, in that server's coverArtUrl form: a path on
     * Plex, the artist's own id on Subsonic (Navidrome serves artist art from
     * getCoverArt).
     */
    val imageId: String? = null,
)

/** A server library: a Subsonic music folder, a Plex section, a Jellyfin library. */
data class MusicFolder(val id: String, val name: String)

/**
 * Streaming format. "raw" streams the original file untouched; the others ask
 * the server to transcode on the fly to something the device can always decode.
 */
enum class StreamFormat(val id: String, val maxBitRate: Int?) {
    MP3("mp3", 320),
    OPUS("opus", 192),
    /**
     * No longer offered anywhere: asked of most servers it quietly becomes
     * something else (Navidrome sends Opus when it has no FLAC encoder, or for
     * any lossy file), and the original already is the lossless choice. Kept so
     * copies downloaded in it still read, and a stored choice of it falls to
     * the original (see MusicSource.supported).
     */
    FLAC("flac", null),
    RAW("raw", null);

    companion object {
        /**
         * Opus at 192 kbps: transparent for practically all material, ~40% the
         * size of MP3 320, and cheaper for the server to transcode. The default
         * where data or room costs something — cellular and downloads; Wi-Fi
         * defaults to the original (see MusicSource.wifiFormat).
         */
        val DEFAULT = OPUS

        fun fromId(id: String?): StreamFormat =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Rough audio quality order, used to decide whether streaming beats a download. */
val StreamFormat.qualityRank: Int
    get() = when (this) {
        StreamFormat.MP3 -> 1
        StreamFormat.OPUS -> 2
        StreamFormat.FLAC -> 3
        StreamFormat.RAW -> 4
    }

enum class RepeatMode { OFF, TRACK, QUEUE }

data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

/**
 * What kind of record an album is, from its MusicBrainz release type: a primary
 * type (album, EP, single) and any secondary ones (live, soundtrack,
 * compilation), as servers pass them on and files carry them. Listed in the
 * order an artist's page groups them by.
 */
enum class ReleaseType(val heading: String) {
    ALBUM("Albums"),
    EP("EPs"),
    SINGLE("Singles"),
    SOUNDTRACK("Soundtracks"),
    COMPILATION("Compilations"),
    LIVE("Live Albums"),
    ;

    /** Rank in [of]'s order, most specific first: live, soundtrack, compilation, EP, single, album. */
    val specificity: Int get() = SPECIFICITY.indexOf(this)

    companion object {
        private val SPECIFICITY = listOf(LIVE, SOUNDTRACK, COMPILATION, EP, SINGLE, ALBUM)

        /**
         * The most specific of [types], so a live album or a soundtrack is filed
         * as that rather than as an album: live, then soundtrack, compilation,
         * EP, single. Types come as a list or joined ("album; live"); any other
         * (broadcast, remix, spokenword…) is an album. [compilation] is a
         * server's or file's own compilation flag.
         */
        fun of(types: Collection<String>, compilation: Boolean = false): ReleaseType {
            val all = types.flatMap { it.split(';', ',', '/') }.map { it.trim().lowercase() }.toSet()
            return when {
                "live" in all -> LIVE
                "soundtrack" in all -> SOUNDTRACK
                compilation || "compilation" in all -> COMPILATION
                "ep" in all -> EP
                "single" in all -> SINGLE
                else -> ALBUM
            }
        }

        /** As stored: the name, or Album for a row from before the column. */
        fun stored(name: String): ReleaseType = entries.firstOrNull { it.name == name } ?: ALBUM
    }
}

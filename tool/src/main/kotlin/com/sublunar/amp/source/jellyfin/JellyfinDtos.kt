package com.sublunar.amp.source.jellyfin

import com.sublunar.amp.data.Album
import com.sublunar.amp.data.LyricLine
import com.sublunar.amp.data.Lyrics
import com.sublunar.amp.data.MusicFolder
import com.sublunar.amp.data.Playlist
import com.sublunar.amp.data.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Just enough of Jellyfin's JSON to build the app's own models.
 *
 * Jellyfin is plain JSON with PascalCase names and no envelope beyond a list
 * wrapper: a query returns `{ "Items": [...], "TotalRecordCount": n }`, and a
 * single item returns the item itself. Every field here is optional with a
 * default, because Jellyfin omits what a library hasn't got rather than sending
 * nulls, and a record with no year, no cover and no genre is ordinary.
 *
 * **Times are ticks.** Jellyfin counts in 100-nanosecond units throughout —
 * durations, positions, everything. A tick is not a millisecond and reading one
 * as the other is out by a factor of ten thousand, so the conversion is named
 * ([TICKS_PER_MS]) rather than written as a magic number at each site.
 */
@Serializable
data class JellyfinItems(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
    /** How many there are altogether, which is how paging knows when to stop. */
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

@Serializable
data class JellyfinItem(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    /** "Audio" for a song or a playlist of songs; "Video" and others are left out — see JellyfinClient.getPlaylists. */
    @SerialName("MediaType") val mediaType: String? = null,
    /** What a view holds, "music" or "movies" and so on — see [musicLibraries]. Only set on the user's views. */
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    /** On a playlist, when songs were last added to it — the nearest thing it has to "last changed". */
    @SerialName("DateLastMediaAdded") val dateLastMediaAdded: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<JellyfinNamed> = emptyList(),
    /** The performers on *this* track, which on a compilation are not the album artist — see [toTrack]. */
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("ArtistItems") val artistItems: List<JellyfinNamed> = emptyList(),
    @SerialName("Genres") val genres: List<String> = emptyList(),
    /**
     * The track's gain in dB against −18 LUFS (10.9+), ReplayGain's own
     * reference: −18 less the loudness Jellyfin's normalisation scan measured,
     * otherwise the file's REPLAYGAIN_TRACK_GAIN tag. Absent where it has
     * neither.
     */
    @SerialName("NormalizationGain") val normalizationGain: Float? = null,
    /**
     * The track's album's gain, on the same scale (12.0+, on tracks): from the
     * scan, otherwise the file's REPLAYGAIN_ALBUM_GAIN. Sent without being
     * asked for in `Fields`, like [normalizationGain].
     */
    @SerialName("AlbumNormalizationGain") val albumNormalizationGain: Float? = null,
    /**
     * Everyone credited on the track, composers among them.
     *
     * Jellyfin has no composer field of its own: a composer is a person with
     * [JellyfinPerson.type] "Composer", and the list only arrives when "People"
     * is asked for — see FIELDS.
     */
    @SerialName("People") val people: List<JellyfinPerson> = emptyList(),
    /** Which images the item actually has; "Primary" is the cover — see [imageId]. */
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    /** On a track, its album's cover, which [imageId] prefers to the track's own. */
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    /**
     * The playlist entry's id. Removes and moves name an entry, not a song,
     * since one song can sit in a playlist twice; but since Jellyfin 12 this is
     * the song's id — see JellyfinClient.removeFromPlaylistAt.
     */
    @SerialName("PlaylistItemId") val playlistItemId: String? = null,
)

/** An id-and-name pair, which Jellyfin uses wherever it references something. */
@Serializable
data class JellyfinNamed(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

/** A credited person; [type] is Jellyfin's PersonType — "Composer" and friends. */
@Serializable
data class JellyfinPerson(
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
)

@Serializable
data class JellyfinUserData(
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)

/** `/Users/Me`: what the account is allowed. */
@Serializable
data class JellyfinMe(
    @SerialName("Policy") val policy: JellyfinPolicy = JellyfinPolicy(),
)

@Serializable
data class JellyfinPolicy(
    /** "Allow media downloading" in the server's user settings; on unless its owner turned it off. */
    @SerialName("EnableContentDownloading") val enableContentDownloading: Boolean = true,
)

/** What `/Users/AuthenticateByName` hands back. */
@Serializable
data class JellyfinAuthResult(
    @SerialName("User") val user: JellyfinNamed = JellyfinNamed(),
    @SerialName("AccessToken") val accessToken: String = "",
)

/** The public bits of `/System/Info/Public`, used to name a server. */
@Serializable
data class JellyfinPublicInfo(
    @SerialName("ServerName") val serverName: String = "",
    @SerialName("Version") val version: String = "",
    @SerialName("Id") val id: String = "",
)

/** One scheduled task, which is how a library scan reports that it is running. */
@Serializable
data class JellyfinTask(
    @SerialName("Key") val key: String = "",
    @SerialName("State") val state: String = "",
)

/** `/Audio/{id}/Lyrics`, present from Jellyfin 10.9. */
@Serializable
data class JellyfinLyrics(
    @SerialName("Metadata") val metadata: JellyfinLyricMetadata? = null,
    @SerialName("Lyrics") val lyrics: List<JellyfinLyricLine> = emptyList(),
)

@Serializable
data class JellyfinLyricMetadata(
    /** Ticks to shift every line by; positive shows the words sooner, as LRC's `[offset:]`. */
    @SerialName("Offset") val offset: Long? = null,
)

@Serializable
data class JellyfinLyricLine(
    /** Ticks from the start, absent on an unsynced sheet. */
    @SerialName("Start") val start: Long? = null,
    @SerialName("Text") val text: String = "",
)

/** Jellyfin's clock: 100-nanosecond units, so ten thousand to the millisecond. */
const val TICKS_PER_MS = 10_000L

/**
 * A Jellyfin date to epoch millis, or 0 when there isn't one.
 *
 * Jellyfin writes ISO-8601 with a variable number of fractional-second digits
 * and sometimes no zone at all, which is more shapes than any one parser here
 * handles — and none of the dates this reads are worth a dependency. Only the
 * date part is taken, which is all the app sorts and displays by.
 */
fun jellyfinDateMs(value: String?): Long {
    val (year, month, day) = yearMonthDay(value) ?: return 0L
    return java.util.GregorianCalendar(
        java.util.TimeZone.getTimeZone("UTC"),
    ).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis
}

/** The same date as a sortable YYYYMMDD, which is how [Album.releaseDate] reads. */
fun jellyfinDateNumber(value: String?): Long {
    val (year, month, day) = yearMonthDay(value) ?: return 0L
    return year * 10_000L + month * 100L + day
}

/** The `YYYY-MM-DD` a Jellyfin date starts with, or null when it doesn't. */
private fun yearMonthDay(value: String?): Triple<Int, Int, Int>? {
    val text = value?.takeIf { it.length >= 10 } ?: return null
    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val month = text.substring(5, 7).toIntOrNull() ?: return null
    val day = text.substring(8, 10).toIntOrNull() ?: return null
    return Triple(year, month, day)
}

/**
 * The cover id for an item, or null when it hasn't got one.
 *
 * Jellyfin serves `/Items/{anything}/Images/Primary` and answers 404 only when
 * the bytes are asked for, so "has a cover" is decided from [JellyfinItem.imageTags].
 * The album's picture comes first where the item has one: a track's embedded
 * art is the same sleeve, and keyed by the track the cover cache held one copy
 * per song.
 */
fun JellyfinItem.imageId(): String? = when {
    !albumPrimaryImageTag.isNullOrBlank() && !albumId.isNullOrBlank() -> albumId
    imageTags.containsKey("Primary") -> id
    else -> null
}

/** The music collections among a user's views; a library of films has no place here. */
internal fun JellyfinItems.musicLibraries(): List<MusicFolder> =
    items
        .filter { it.collectionType.equals("music", ignoreCase = true) }
        .map { MusicFolder(id = it.id, name = it.name) }

/**
 * Timed where the file carried timings, shifted by the sheet's offset, and
 * plain where it didn't; null when there are no words.
 */
internal fun JellyfinLyrics.toLyrics(): Lyrics? {
    val shiftMs = (metadata?.offset ?: 0L) / TICKS_PER_MS
    val lines = lyrics
        .filter { it.text.isNotBlank() }
        .map { line ->
            LyricLine(line.start?.let { ticks -> (ticks / TICKS_PER_MS - shiftMs).coerceAtLeast(0L) }, line.text.trim())
        }
    if (lines.isEmpty()) return null
    return Lyrics(lines, synced = lines.any { it.timeMs != null })
}

/** Jellyfin's own record of an album, as one of the app's. */
internal fun JellyfinItem.toAlbum(): Album = Album(
    id = id,
    title = name,
    artist = recordArtist,
    coverArtId = imageId(),
    durationMs = (runTimeTicks ?: 0L) / TICKS_PER_MS,
    songCount = childCount ?: 0,
    year = productionYear,
    releaseDate = jellyfinDateNumber(premiereDate),
    createdMs = jellyfinDateMs(dateCreated),
    playCount = userData?.playCount ?: 0,
    lastPlayedMs = jellyfinDateMs(userData?.lastPlayedDate),
    liked = userData?.isFavorite ?: false,
    genre = genres.firstOrNull().orEmpty(),
)

/** The record's artist, by whichever of its two spellings the server filled in. */
private val JellyfinItem.recordArtist: String
    get() = albumArtist ?: albumArtists.firstOrNull()?.name ?: "Unknown Artist"

internal fun JellyfinItem.toTrack(): Track = Track(
    id = id,
    title = name,
    // The track's own performers, not the record's: on a compilation the
    // album artist is "Various Artists" for every row, and reading that
    // here would put a shelf of songs by nobody in the songs list.
    artist = artists.firstOrNull()
        ?: artistItems.firstOrNull()?.name
        ?: albumArtist
        ?: "Unknown Artist",
    album = album ?: "Unknown Album",
    albumArtist = recordArtist,
    albumId = albumId,
    coverArtId = imageId(),
    durationMs = (runTimeTicks ?: 0L) / TICKS_PER_MS,
    trackNumber = indexNumber,
    discNumber = parentIndexNumber,
    year = productionYear,
    playCount = userData?.playCount ?: 0,
    lastPlayedMs = jellyfinDateMs(userData?.lastPlayedDate),
    liked = userData?.isFavorite ?: false,
    genre = genres.firstOrNull().orEmpty(),
    // Jellyfin files a composer as a credited person rather than a tag, so the
    // Composers page is built out of People — see JellyfinItem.people. Joined
    // with the separator the tag lists already split on.
    composer = people.filter { it.type.equals("Composer", ignoreCase = true) }
        .joinToString("; ") { it.name }
        .trim(),
    // The track's own gain first; a file tagged only album-wise still evens out.
    gainDb = normalizationGain ?: albumNormalizationGain,
)

internal fun JellyfinItem.toPlaylist(): Playlist = Playlist(
    id = id,
    name = name,
    coverArtId = imageId(),
    createdAt = jellyfinDateMs(dateCreated),
    // When songs were last added, which is what moves a playlist up "Recently
    // updated"; Jellyfin keeps no date for a removal, a reorder or a rename. Its
    // creation where it has neither.
    updatedAt = jellyfinDateMs(dateLastMediaAdded ?: dateCreated),
    trackIds = emptyList(),
)

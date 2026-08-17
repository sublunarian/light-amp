package com.sublunar.amp.data

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
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("Type") val type: String = "",
    /**
     * What a view holds — "music" is the one this app cares about.
     *
     * Only set on the entries returned by `/Users/{id}/Views`; a library of
     * films answers "movies" and has no business in a music app.
     */
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<JellyfinNamed> = emptyList(),
    /**
     * The performers on *this* track, which is not the record's artist.
     *
     * A compilation's every track carries the album artist "Various Artists",
     * so a client that reads only that shows a shelf of records by nobody. The
     * track's own artists are here.
     */
    @SerialName("Artists") val artists: List<String> = emptyList(),
    @SerialName("ArtistItems") val artistItems: List<JellyfinNamed> = emptyList(),
    @SerialName("Genres") val genres: List<String> = emptyList(),
    /**
     * Which images the item actually has.
     *
     * Jellyfin serves an image URL for any id whether or not one exists —
     * answering 404 at fetch time — so this is the only way to know before
     * asking. "Primary" is the cover.
     */
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    /** An album's own cover, on a track that hasn't got one of its own. */
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    /**
     * The playlist entry's own id, which is not the track's.
     *
     * The same song can sit in a playlist twice, so removing "that one" needs
     * the entry rather than the song — see JellyfinClient.removeFromPlaylistAt.
     */
    @SerialName("PlaylistItemId") val playlistItemId: String? = null,
)

/** An id-and-name pair, which Jellyfin uses wherever it references something. */
@Serializable
data class JellyfinNamed(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

@Serializable
data class JellyfinUserData(
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
)

/** What `/Users/AuthenticateByName` hands back. */
@Serializable
data class JellyfinAuthResult(
    @SerialName("User") val user: JellyfinNamed = JellyfinNamed(),
    @SerialName("AccessToken") val accessToken: String = "",
    @SerialName("ServerId") val serverId: String = "",
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
    @SerialName("Lyrics") val lyrics: List<JellyfinLyricLine> = emptyList(),
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
    val text = value?.takeIf { it.length >= 10 } ?: return 0L
    val year = text.substring(0, 4).toIntOrNull() ?: return 0L
    val month = text.substring(5, 7).toIntOrNull() ?: return 0L
    val day = text.substring(8, 10).toIntOrNull() ?: return 0L
    return java.util.GregorianCalendar(
        java.util.TimeZone.getTimeZone("UTC"),
    ).apply {
        clear()
        set(year, month - 1, day)
    }.timeInMillis
}

/** The same date as a sortable YYYYMMDD, which is how [Album.releaseDate] reads. */
fun jellyfinDateNumber(value: String?): Long {
    val text = value?.takeIf { it.length >= 10 } ?: return 0L
    val year = text.substring(0, 4).toIntOrNull() ?: return 0L
    val month = text.substring(5, 7).toIntOrNull() ?: return 0L
    val day = text.substring(8, 10).toIntOrNull() ?: return 0L
    return year * 10_000L + month * 100L + day
}

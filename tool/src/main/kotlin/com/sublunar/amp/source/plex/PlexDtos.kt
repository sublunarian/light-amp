package com.sublunar.amp.source.plex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Just enough of Plex's JSON to build the app's own models.
 *
 * Plex answers XML unless asked for `Accept: application/json`. Every JSON
 * response is one `MediaContainer` holding a list under a name that
 * depends on what was asked for: `Directory` for library sections, `Metadata`
 * for anything with a rating key. Fields are all optional because Plex omits
 * rather than nulls.
 */
@Serializable
data class PlexResponse(@SerialName("MediaContainer") val container: PlexContainer = PlexContainer())

@Serializable
data class PlexContainer(
    /** How many came back in *this* response. */
    val size: Int = 0,
    /** How many there are altogether, when the server pages the result. */
    val totalSize: Int? = null,
    @SerialName("machineIdentifier") val machineIdentifier: String? = null,
    @SerialName("Directory") val directories: List<PlexDirectory> = emptyList(),
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
)

/** A library section: `type` is "artist" for a music library. */
@Serializable
data class PlexDirectory(
    val key: String = "",
    val type: String = "",
    val title: String = "",
    /** Set while the server is walking this section's files. See [isPlexTrue]. */
    val refreshing: JsonElement? = null,
) {
    val isRefreshing: Boolean get() = refreshing.isPlexTrue
}

@Serializable
data class PlexMetadata(
    /** Set on a smart playlist, one Plex builds from a filter. See [isPlexTrue]. */
    val smart: JsonElement? = null,
    val ratingKey: String = "",
    val type: String = "",
    val title: String = "",
    /**
     * A track's *own* artist, sent only when it differs from the album artist.
     * On a compilation this is the one that matters: [grandparentTitle] would
     * put "Various Artists" against every song.
     */
    val originalTitle: String? = null,
    /** The album, on a track; the artist, on an album. */
    val parentTitle: String? = null,
    /** The artist, on a track. */
    val grandparentTitle: String? = null,
    val parentRatingKey: String? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val year: Int? = null,
    val originallyAvailableAt: String? = null,
    /** Track number within its disc; disc number is [parentIndex] on a track. */
    val index: Int? = null,
    val parentIndex: Int? = null,
    val duration: Long? = null,
    val leafCount: Int? = null,
    val addedAt: Long? = null,
    val updatedAt: Long? = null,
    /** A playlist's mosaic cover. */
    val composite: String? = null,
    val lastViewedAt: Long? = null,
    val viewCount: Int? = null,
    /** Plex rates 0–10; the app works in 0–5 stars. */
    val userRating: Float? = null,
    /**
     * A track's entry *within a playlist*, which is not its rating key: the
     * same song can sit in a playlist twice, and removing or moving one of them
     * needs the id of that entry.
     */
    val playlistItemID: Long? = null,
    @SerialName("Genre") val genres: List<PlexTag> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
) {
    /** Plex builds this one from a filter, so its contents can't be edited. */
    val isSmart: Boolean get() = smart.isPlexTrue
}

/**
 * Plex writes a flag as `true` in some responses and `1` in others. Declared as
 * a Boolean, `coerceInputValues` would quietly read the `1` as false.
 */
private val JsonElement?.isPlexTrue: Boolean
    get() = (this as? JsonPrimitive)?.content?.lowercase() in setOf("true", "1")

/** Plex's tag shape: genres, moods, styles all arrive as `{ "tag": "..." }`. */
@Serializable
data class PlexTag(val tag: String = "")

@Serializable
data class PlexMedia(
    @SerialName("Part") val parts: List<PlexPart> = emptyList(),
)

@Serializable
data class PlexPart(
    val key: String = "",
    @SerialName("Stream") val streams: List<PlexStream> = emptyList(),
)

/**
 * One track of media inside a file: `streamType` is 1 for video, 2 for audio,
 * 3 for subtitles and 4 for lyrics.
 *
 * Most lyrics come from Plex's LyricFind agent and carry no file on disk, so
 * nothing here tells a fetchable lyric stream from a dud: the only way to find
 * out is to ask `/library/streams/{id}`, which now and then answers 404.
 */
@Serializable
data class PlexStream(
    val streamType: Int = 0,
    val key: String? = null,
)

// --- plex.tv, for linking and for finding the user's servers ----------------

@Serializable
data class PlexPin(
    val id: Long = 0,
    val code: String = "",
    val authToken: String? = null,
)

@Serializable
data class PlexResource(
    val name: String = "",
    val clientIdentifier: String = "",
    val provides: String = "",
    val accessToken: String? = null,
    val owned: Boolean = false,
    /** "Plex for Apple TV", "Plexamp", …: which app the device is. */
    val product: String = "",
    val connections: List<PlexConnection> = emptyList(),
)

@Serializable
data class PlexConnection(
    val uri: String = "",
    val address: String = "",
    val port: Int = 0,
    val local: Boolean = false,
    val relay: Boolean = false,
)

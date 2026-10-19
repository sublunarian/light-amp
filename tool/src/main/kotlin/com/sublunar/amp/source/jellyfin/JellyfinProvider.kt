package com.sublunar.amp.source.jellyfin

import com.sublunar.amp.data.JellyfinClient
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider

/**
 * A Jellyfin server's music library.
 *
 * Of the fields on a MusicSource, `username`, `token`, `userId` and
 * `deviceId` are this kind's own. Everything else on the record is shared.
 */
object JellyfinProvider : SourceProvider {
    override val kind = SourceKind.JELLYFIN

    override val streamFormats: List<StreamFormat> get() = JellyfinClient.STREAM_FORMATS

    /**
     * Closer to Subsonic than Plex is — real favourites, playlists that can be
     * created empty — but with no star ratings: Jellyfin has the favourite and
     * not the stars, Plex the stars and not the favourite, the two missing
     * opposite halves of the same idea.
     */
    override fun capabilities(features: ServerFeatures?) = SourceCapabilities(
        likes = true,
        ratings = false,
        radio = true,
        emptyPlaylists = true,
        downloads = true,
        originalDownload = true,
        serverScan = true,
        libraries = true,
    )

    override fun createClient(source: MusicSource): MusicServer? =
        if (source.baseUrl.isBlank() || source.token.isBlank() || source.userId.isBlank()) {
            null
        } else {
            JellyfinClient(
                source.baseUrl,
                source.token,
                source.userId,
                source.playerName.ifBlank { null },
                source.deviceId.ifBlank { JellyfinClient.LEGACY_DEVICE_ID },
            )
        }
}

package com.sublunar.amp.source.plex

import com.sublunar.amp.data.PlexClient
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider

/**
 * A Plex Media Server's music library.
 *
 * Of the fields on a MusicSource, `token`, `machineIdentifier`, `deviceId`
 * and `connections` are this kind's own. Everything else on the record is
 * shared.
 */
object PlexProvider : SourceProvider {
    override val kind = SourceKind.PLEX

    override val streamFormats: List<StreamFormat> get() = PlexClient.STREAM_FORMATS

    /**
     * Plex has star ratings but no separate favourite, so nothing for a heart
     * to sync to. And it creates a playlist *from* something, with no call for
     * an empty one — which is why PlexAmp makes you pick the songs first — so
     * "New Playlist" on its own isn't offered, though filing songs into a new
     * playlist still is.
     */
    override fun capabilities(features: ServerFeatures?) = SourceCapabilities(
        likes = false,
        ratings = true,
        radio = true,
        emptyPlaylists = false,
        downloads = true,
        originalDownload = true,
        serverScan = true,
        libraries = true,
    )
}

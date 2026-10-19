package com.sublunar.amp.source.local

import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider

/**
 * The phone's own music folder — the same files the Light Music app plays.
 *
 * No field on a MusicSource is this kind's own: there is nothing to sign in
 * to and nowhere to point.
 */
object LocalProvider : SourceProvider {
    override val kind = SourceKind.LOCAL

    /** The phone's own music is never transcoded at all. */
    override val streamFormats: List<StreamFormat> get() = emptyList()

    /**
     * There is no server behind it, so everything that lives on one — likes,
     * ratings, radio, a scan to ask for — is unavailable, and downloading
     * means nothing when the audio is already here. Playlists are the
     * phone's own m3u8 files, which can start empty. The original download
     * reads as possible only because there is nothing to narrow: no screen
     * offers a download here.
     */
    override fun capabilities(features: ServerFeatures?) = SourceCapabilities(
        likes = false,
        ratings = false,
        radio = false,
        emptyPlaylists = true,
        downloads = false,
        originalDownload = true,
        serverScan = false,
        libraries = false,
    )
}

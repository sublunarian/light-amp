package com.sublunar.amp.source.subsonic

import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.SubsonicClient
import com.sublunar.amp.data.SubsonicConfig
import com.sublunar.amp.data.allows
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider

/**
 * A Subsonic-compatible server: Navidrome, Airsonic, gonic, Ampache, Bandcamp…
 *
 * Of the fields on a MusicSource, `username` and `password` are this kind's
 * own. Everything else on the record is shared.
 */
object SubsonicProvider : SourceProvider {
    override val kind = SourceKind.SUBSONIC

    override val streamFormats: List<StreamFormat> get() = SubsonicClient.STREAM_FORMATS

    /**
     * Asked of this server rather than assumed of the protocol: not every
     * server that speaks Subsonic has every call — `setRating`,
     * `getSimilarSongs`, `download`, the scan — so each follows what the
     * probes found. See ServerFeatures.
     */
    override fun capabilities(features: ServerFeatures?) = SourceCapabilities(
        likes = true,
        ratings = features.allows(ServerFeatures.SET_RATING),
        radio = features.allows(ServerFeatures.SIMILAR_SONGS),
        emptyPlaylists = true,
        downloads = true,
        originalDownload = features.allows(ServerFeatures.DOWNLOAD),
        serverScan = features.allows(ServerFeatures.SCAN_STATUS),
        libraries = true,
    )

    override fun createClient(source: MusicSource): MusicServer? =
        source.toSubsonicConfig()?.let { SubsonicClient(it) }
}

/**
 * Null unless this is a Subsonic server with somewhere to point. The kind is
 * checked here rather than left to the caller: the server form falls back to
 * whichever source is active, and that can be any kind.
 */
fun MusicSource.toSubsonicConfig(): SubsonicConfig? =
    if (kind == SourceKind.SUBSONIC && baseUrl.isNotBlank()) {
        SubsonicConfig(baseUrl, username, password, playerName.ifBlank { null })
    } else {
        null
    }

package com.sublunar.amp.source.plex

import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.PlexClient
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.source.AddAction
import com.sublunar.amp.source.AddEntry
import com.sublunar.amp.source.AddIcon
import com.sublunar.amp.source.ConnectionRow
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider
import com.sublunar.amp.source.SourceUi
import com.sublunar.amp.ui.screens.PlexLinkScreen

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

    override fun createClient(source: MusicSource): MusicServer? =
        if (source.baseUrl.isBlank() || source.token.isBlank()) {
            null
        } else {
            PlexClient(
                source.baseUrl,
                source.token,
                source.machineIdentifier,
                source.playerName.ifBlank { null },
                source.plexClientIdentifier,
            )
        }

    override val ui: SourceUi get() = PlexUi
}

private object PlexUi : SourceUi {
    override val addEntries = listOf(AddEntry("Plex Server", AddIcon.ADD, AddAction.OpenScreen { PlexLinkScreen(it) }))

    // Plex signs in by token, so there are no fields to edit — a different
    // server or account means linking again, which updates this source in
    // place. The row still answers where the music comes from.
    override fun connection(source: MusicSource) = ConnectionRow()
}

/** Who a Plex link says it is — see MusicSource.deviceId. */
val MusicSource.plexClientIdentifier: String get() = deviceId.ifBlank { PlexClient.LEGACY_CLIENT_ID }

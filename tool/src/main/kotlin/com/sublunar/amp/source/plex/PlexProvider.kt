package com.sublunar.amp.source.plex

import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.playback.output.OutputFamily
import com.sublunar.amp.source.AddAction
import com.sublunar.amp.source.AddEntry
import com.sublunar.amp.source.AddIcon
import com.sublunar.amp.source.ConnectionRow
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider
import com.sublunar.amp.source.SourceUi
import com.sublunar.amp.source.plex.companion.PlexCompanionFamily

/**
 * A Plex Media Server's music library.
 *
 * Of the fields on a MusicSource, `token`, `machineIdentifier`, `deviceId`
 * and `connections` are this kind's own. Everything else on the record is
 * shared.
 */
object PlexProvider : SourceProvider {
    override val kind = SourceKind.PLEX

    /**
     * The original file, or MP3: Plex's universal music transcoder produces
     * MP3 whatever is asked for, with nothing in the response to admit it,
     * so the formats it can't serve are absent rather than silently swapped.
     */
    override val streamFormats: List<StreamFormat> = listOf(StreamFormat.RAW, StreamFormat.MP3)

    // Plexamp lists the chosen library's playlists and plays each one whole.
    override val libraryNarrowsPlaylistSongs: Boolean get() = false

    /**
     * Plex has star ratings but no separate favourite, so nothing for a heart
     * to sync to. And it has no call for an empty playlist, so "New Playlist"
     * on its own isn't offered, though filing songs into a new one still is.
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

    /** The account's Plex players, such as an Apple TV or another Plexamp, steered directly. */
    override val outputFamilies: List<OutputFamily> get() = listOf(PlexCompanionFamily)

    /** Matched by the server's own id, so a server re-linked from another address is still the one it was. */
    override fun isSameServer(existing: MusicSource, incoming: MusicSource): Boolean =
        existing.machineIdentifier.isNotBlank() && existing.machineIdentifier == incoming.machineIdentifier

    /**
     * The server's other advertised addresses, tried for one that answers.
     * The address that answered at link time is usually the LAN one, which
     * dies the moment the phone leaves that network.
     */
    override suspend fun relocate(source: MusicSource): MusicSource? {
        if (source.connections.isEmpty()) return null
        val found = PlexAccount.firstReachable(
            relocationOrder(source), source.token, source.plexClientIdentifier, source.machineIdentifier,
        ) ?: return null
        return if (found == source.baseUrl) null else source.copy(baseUrl = found)
    }

    /** What plex.tv lists for this server now — see PlexAccount.advertised. */
    override suspend fun advertisedAddresses(source: MusicSource): List<String>? =
        PlexAccount.advertised(source.machineIdentifier, source.token, source.plexClientIdentifier)

    override suspend fun firstAnswering(uris: List<String>, source: MusicSource): String? =
        PlexAccount.firstReachable(uris, source.token, source.plexClientIdentifier, source.machineIdentifier)
}

/**
 * The other addresses first, and the one that just failed last. A minus on a
 * list drops only the first match, so a stored address listed twice is still
 * tried once early.
 */
internal fun relocationOrder(source: MusicSource): List<String> = (source.connections - source.baseUrl) + source.baseUrl

private object PlexUi : SourceUi {
    override val addEntries = listOf(AddEntry("Plex Server", AddIcon.ADD, AddAction.OpenScreen { PlexLinkScreen(it) }))

    // Plex signs in by token, so there are no fields to edit: a different
    // server or account means linking again, which updates this source in
    // place. The row still states where the music comes from.
    override fun connection(source: MusicSource) = ConnectionRow()
}

/** Who a Plex link says it is — see MusicSource.deviceId. */
val MusicSource.plexClientIdentifier: String get() = deviceId.ifBlank { PlexClient.LEGACY_CLIENT_ID }

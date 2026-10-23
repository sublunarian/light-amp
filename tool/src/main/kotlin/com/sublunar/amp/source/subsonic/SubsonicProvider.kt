package com.sublunar.amp.source.subsonic

import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.allows
import com.sublunar.amp.data.promises
import com.sublunar.amp.source.AddAction
import com.sublunar.amp.source.AddEntry
import com.sublunar.amp.source.AddIcon
import com.sublunar.amp.source.ConnectionRow
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider
import com.sublunar.amp.source.SourceUi

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

    // Built again whenever the record changes, its features with it, so a
    // server found to take playback reports is sent them from then on.
    override fun createClient(source: MusicSource): MusicServer? =
        source.toSubsonicConfig()?.let {
            SubsonicClient(it, reportsPlayback = source.features.promises(ServerFeatures.REPORT_PLAYBACK))
        }

    override val ui: SourceUi get() = SubsonicUi
}

private object SubsonicUi : SourceUi {
    // Bandcamp needs nothing self-hosted, so it is offered with the phone's own
    // music, ahead of the servers. It is a Subsonic server underneath — the
    // choice only fills in its address and says where its credentials come from.
    override val addEntries = listOf(
        AddEntry(
            "Bandcamp",
            AddIcon.ADD,
            AddAction.OpenScreen { ServerScreen(it, sourceId = null, adding = true, bandcamp = true) },
            selfHosted = false,
        ),
        AddEntry("Subsonic Server", AddIcon.ADD, AddAction.OpenScreen { ServerScreen(it, sourceId = null, adding = true) }),
    )

    // Named because "Subsonic" is the protocol, not the thing most people
    // run — someone with a Navidrome server needs telling that the Subsonic
    // choice is theirs.
    override val addNote = "Subsonic covers Navidrome, Airsonic and Ampache"

    override fun connection(source: MusicSource) = ConnectionRow(open = { ServerScreen(it, sourceId = source.id) })
}

/**
 * Null unless this is a Subsonic server with somewhere to point. The kind is
 * checked here rather than left to the caller: ServerScreen falls back to the
 * active source when the one it edits isn't listed, and that can be any kind.
 */
fun MusicSource.toSubsonicConfig(): SubsonicConfig? =
    if (kind == SourceKind.SUBSONIC && baseUrl.isNotBlank()) {
        SubsonicConfig(baseUrl, username, password, playerName.ifBlank { null })
    } else {
        null
    }

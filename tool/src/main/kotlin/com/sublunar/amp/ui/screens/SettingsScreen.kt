package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.formatBytes
import com.sublunar.amp.data.formatGb
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.DataMode
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.launch

fun formatLabel(format: StreamFormat): String = when (format) {
    StreamFormat.MP3 -> "MP3 (320 kbps)"
    StreamFormat.OPUS -> "Opus (192 kbps)"
    StreamFormat.FLAC -> "FLAC (lossless)"
    StreamFormat.RAW -> "Original file"
}

/** The format alone, for a subtitle that pairs it with another fact. */
fun shortFormatLabel(format: StreamFormat): String = when (format) {
    StreamFormat.MP3 -> "MP3"
    StreamFormat.OPUS -> "Opus"
    StreamFormat.FLAC -> "FLAC"
    StreamFormat.RAW -> "Original"
}

/** Both connections on one line, for the server rows on the Data page. */
private fun streamingSummary(server: MusicSource): String =
    if (server.wifiFormat == server.cellularFormat) {
        shortFormatLabel(server.wifiFormat)
    } else {
        "Wi-Fi ${shortFormatLabel(server.wifiFormat)} · " +
            "Cellular ${shortFormatLabel(server.cellularFormat)}"
    }

/** "3 minutes ago" and friends, for the last-synced line. */
fun relativeTime(ms: Long): String {
    val delta = System.currentTimeMillis() - ms
    if (delta < 0) return "just now"
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/**
 * The hub: five categories, each a page, each row stating where its page stands.
 *
 * This used to be one long list — playback rows, seven appearance switches and
 * About in a single scroll, with the rest buried on each server's page. Five
 * doors read faster than eighteen rows, and the subtitles answer the common
 * question ("what is it set to?") without opening anything.
 */
class SettingsScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val dataMode by App.settings.dataMode.collectAsState(initial = DataMode.WIFI_ONLY)
        val downloadLimit by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        // Off the disk, once per visit: what the phone actually holds, whatever
        // the per-source indexes say.
        val used = remember { App.downloads.usedBytesEverywhere() }
        val sourceNames = sources.joinToString(", ") { it.name }
        // Any server, not the active source: with a server and the phone's own
        // music side by side, streaming and downloads still deserve their pages
        // while the local source happens to be the one playing. Only a phone
        // with no servers at all has nothing to stream and nothing to fetch.
        val hasServers = sources.any { it.kind != SourceKind.LOCAL }

        ListScreen(onBack = { goBack() }, title = "Settings") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item {
                    TextRow(title = "Appearance") { go { AppearanceScreen(it) } }
                }
                // Everything about a source that is set once — its address, its
                // name, how it logs in. Switching *between* them stays on the
                // Sources page under More, where it is reached while listening
                // rather than while configuring.
                item {
                    TextRow(
                        title = "Sources",
                        subtitle = sourceNames,
                        onClick = { go { SourceListScreen(it) } },
                    )
                }
                if (hasServers) {
                    item {
                        TextRow(
                            title = "Data",
                            subtitle = dataModeLabel(dataMode),
                            onClick = { go { DataScreen(it) } },
                        )
                    }
                    item {
                        TextRow(
                            title = "Offline",
                            subtitle = if (used == 0L) {
                                "Nothing downloaded"
                            } else {
                                "${formatGb(used)} / ${formatGb(downloadLimit)}"
                            },
                            onClick = { go { OfflineScreen(it) } },
                        )
                    }
                }
                item { TextRow(title = "About") { go { AboutScreen(it) } } }
            }
        }
    }
}

/** How the app looks — every switch, none of them about any one source. */
class AppearanceScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val invert by App.settings.invertColors.collectAsState(initial = false)
        val karaoke by App.settings.karaokeLyrics.collectAsState(initial = true)
        val hideArtistImages by App.settings.hideArtistImages.collectAsState(initial = true)
        val hideDownloadIcons by App.hideDownloadIcons.collectAsState()
        val artwork by App.settings.artwork.collectAsState(initial = ArtworkMode.SMALL)
        val layoutMode by App.settings.layoutMode.collectAsState(initial = LayoutMode.STANDARD)

        ListScreen(onBack = { goBack() }, title = "Appearance") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item {
                    ToggleRow("Invert Colors", invert) {
                        App.scope.launch { App.settings.setInvertColors(!invert) }
                    }
                }
                item {
                    // Two states, so a switch rather than a page: covers in the
                    // player's panel and beside every row, or none anywhere —
                    // which also stops the app fetching and decoding them.
                    //
                    // Phrased as the thing switching it on does. "Album Artwork"
                    // was the one switch here that was on by default and turned
                    // something off, which is the odd one out in a list of
                    // switches that all add something.
                    ToggleRow("Hide Artwork", artwork == ArtworkMode.NONE) {
                        App.scope.launch {
                            App.settings.setArtwork(
                                if (artwork == ArtworkMode.NONE) {
                                    ArtworkMode.SMALL
                                } else {
                                    ArtworkMode.NONE
                                },
                            )
                        }
                    }
                }
                item {
                    // Only offered while there is artwork to single out: with
                    // Hide Artwork on, the artists have already lost theirs.
                    if (artwork != ArtworkMode.NONE) {
                        ToggleRow("Hide Artist Photos", hideArtistImages) {
                            App.scope.launch {
                                App.settings.setHideArtistImages(!hideArtistImages)
                            }
                        }
                    }
                }
                item {
                    // The marks go; the layout does not move with them — see
                    // AppSettings.hideDownloadIcons.
                    ToggleRow("Hide Download Icons", hideDownloadIcons) {
                        App.scope.launch {
                            App.settings.setHideDownloadIcons(!hideDownloadIcons)
                        }
                    }
                }
                item {
                    // On is the three buttons the phone itself uses, with the
                    // library as a page rather than a row of tabs. Off — the
                    // default, and what a fresh install gets — spreads the four
                    // tabs across the bar. Phrased as the thing switching it on
                    // does, like its neighbours.
                    val simplified = layoutMode == LayoutMode.SIMPLIFIED
                    ToggleRow("Simplified Library View", simplified) {
                        App.scope.launch {
                            App.settings.setLayoutMode(
                                if (simplified) LayoutMode.STANDARD else LayoutMode.SIMPLIFIED,
                            )
                        }
                    }
                }
                item {
                    ToggleRow("Karaoke Lyrics", karaoke) {
                        App.scope.launch { App.settings.setKaraokeLyrics(!karaoke) }
                    }
                }
            }
        }
    }
}

/**
 * What playing costs over the network.
 *
 * Data Mode and each server's streaming quality together: the mode says when to
 * stream at all, the qualities say how much a stream weighs, and choosing either
 * means thinking about the other. Download quality is deliberately absent — a
 * download is kept, and it lives with the rest of what the phone holds, on
 * [OfflineScreen].
 *
 * The qualities stay per server even here, because they are facts about servers:
 * two servers can hold the same album in different formats, and only one of them
 * may be able to transcode it — see MusicSource.streamFormats.
 */
class DataScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val dataMode by App.settings.dataMode.collectAsState(initial = DataMode.WIFI_ONLY)
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        // The phone's own music never crosses the network.
        val servers = sources.filter { it.kind != SourceKind.LOCAL }

        ListScreen(onBack = { goBack() }, title = "Data") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item {
                    TextRow(
                        title = "Data Mode",
                        subtitle = dataModeLabel(dataMode),
                        onClick = { go { DataModeScreen(it) } },
                    )
                }
                // Streaming twice over, because the two connections are
                // different bargains: on Wi-Fi the bytes are free and the answer
                // is usually "the best you have", on cellular they are not.
                // Stating both beats one setting and a data mode that silently
                // overrides it.
                if (servers.isNotEmpty()) item { SectionLabel("Streaming Quality") }
                if (servers.size == 1) {
                    // One server: its two rows sit right here.
                    val server = servers.first()
                    item(key = "${server.id}/wifi") {
                        TextRow(
                            title = "On Wi-Fi",
                            subtitle = formatLabel(server.wifiFormat),
                            onClick = { go { StreamFormatScreen(it, server.id) } },
                        )
                    }
                    item(key = "${server.id}/cellular") {
                        TextRow(
                            title = "On Cellular",
                            subtitle = formatLabel(server.cellularFormat),
                            onClick = { go { CellularFormatScreen(it, server.id) } },
                        )
                    }
                } else {
                    // Several: a row per server under the one heading. This page
                    // first gave each server a section of On Wi-Fi/On Cellular
                    // rows, and a bare server name over those said nothing about
                    // what the rows set — the heading says it once, and each
                    // row's subtitle states its server's answer.
                    servers.forEach { server ->
                        item(key = server.id) {
                            TextRow(
                                title = server.name,
                                subtitle = streamingSummary(server),
                                onClick = { go { SourceStreamingScreen(it, server.id) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the phone holds: the downloaded music, the budget it fits in, what each
 * server fetches without being asked, and the way to clear the lot.
 *
 * One page for all of it because it is one decision — what does this phone
 * carry, at what fidelity, up to what size. Auto-Download and Download Quality
 * sit here per server rather than on the server's own page: that page is about
 * how a server is reached, where these are knobs you come back to.
 */
class OfflineScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    /*
     * Off the disk rather than the index, and held on the screen rather than
     * in the composition: pushing the confirmation tears this page's
     * composition down (the activity composes only the top of the back stack —
     * see ServerScreen), so a remember{} written by the Delete callback would
     * update state the rebuilt composition never reads.
     */
    private var used by mutableStateOf(App.downloads.usedBytesEverywhere())

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val servers = sources.filter { it.supportsDownloads }
        val downloadLimit by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        // Again each time the page comes back to the top — the pages this one
        // leads to are the ones that move the number.
        LaunchedEffect(Unit) { used = App.downloads.usedBytesEverywhere() }

        ListScreen(onBack = { goBack() }, title = "Offline") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // What is already on the phone. A page of songs, but reached
                // from here rather than from the library: it is about storage —
                // how much is used, what to clear — which is the question this
                // screen answers, and the songs are how you answer it.
                item {
                    TextRow(
                        title = "Downloads",
                        subtitle = if (used == 0L) "Nothing downloaded yet" else formatBytes(used),
                        onClick = { go { DownloadsScreen(it) } },
                    )
                }
                // Beside Downloads rather than inside a source: one budget for
                // the phone, which is the thing that runs out of room.
                item {
                    TextRow(
                        title = "Storage Limit",
                        subtitle = formatBytes(downloadLimit),
                        onClick = { go { SizeLimitScreen(it) } },
                    )
                }
                if (servers.size == 1) {
                    // One server: its rows sit right here, and Delete All below
                    // already means it.
                    val server = servers.first()
                    item(key = "${server.id}/auto") {
                        TextRow(
                            title = "Auto-Download",
                            subtitle = offlineModeLabel(server.offlineMode),
                            onClick = { go { OfflineModeScreen(it, server.id) } },
                        )
                    }
                    item(key = "${server.id}/quality") {
                        TextRow(
                            title = "Download Quality",
                            subtitle = formatLabel(server.downloadFormat),
                            onClick = { go { SourceDownloadFormatScreen(it, server.id) } },
                        )
                    }
                } else if (servers.size > 1) {
                    // A row per server under one heading, as on the Data page —
                    // a bare server name over settings rows didn't say what
                    // they governed. Each server's page also carries its own
                    // Delete, which the all-servers row below can't offer.
                    item { SectionLabel("What each server downloads") }
                    servers.forEach { server ->
                        item(key = server.id) {
                            TextRow(
                                title = server.name,
                                subtitle = "${offlineModeLabel(server.offlineMode)} · " +
                                    shortFormatLabel(server.downloadFormat),
                                onClick = { go { SourceOfflineScreen(it, server.id) } },
                            )
                        }
                    }
                }
                // Every server's audio, not the active one's — see
                // App.deleteAllDownloads. The label keeps the row from reading
                // as part of the server list above it.
                if (servers.size > 1) {
                    item { SectionLabel("Remove") }
                }
                item {
                    TextRow(
                        title = "Delete All Downloads",
                        subtitle = if (servers.size > 1) "Every server's downloads" else null,
                    ) {
                        navigateTo<Boolean>(
                            {
                                ConfirmScreen(
                                    it,
                                    title = "Delete All Downloads",
                                    message = "This removes every source's downloaded music " +
                                        "from this phone. Your libraries on the servers are " +
                                        "untouched.",
                                    confirmLabel = "Delete",
                                )
                            },
                            resultCallback = { confirmed ->
                                if (confirmed == true) {
                                    App.scope.launch {
                                        App.deleteAllDownloads()
                                        used = App.downloads.usedBytesEverywhere()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Streaming quality for one source.
 *
 * Takes the source rather than reading whichever is active: the settings behind
 * a source's page are that source's, and it isn't necessarily the one playing.
 */
class StreamFormatScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }
        val current = source?.wifiFormat ?: StreamFormat.DEFAULT
        ListScreen(onBack = { goBack() }, title = "On Wi-Fi") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // Only what this server will actually send — see
                // MusicSource.streamFormats.
                items(source?.streamFormats ?: StreamFormat.entries.toList()) { format ->
                    TextRow(
                        title = formatLabel(format),
                        onClick = {
                            App.scope.launch { App.settings.setStreamFormat(sourceId, format) }
                            goBack()
                        },
                        trailing = { if (format == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}

/** Streaming quality on cellular; the counterpart to [StreamFormatScreen]. */
class CellularFormatScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }
        val current = source?.cellularFormat ?: StreamFormat.DEFAULT
        ListScreen(onBack = { goBack() }, title = "On cellular") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(source?.streamFormats ?: StreamFormat.entries.toList()) { format ->
                    TextRow(
                        title = formatLabel(format),
                        onClick = {
                            App.scope.launch { App.settings.setCellularFormat(sourceId, format) }
                            goBack()
                        },
                        trailing = { if (format == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}

/** Download quality for one source; the counterpart to [StreamFormatScreen]. */
class SourceDownloadFormatScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }
        val current = source?.downloadFormat ?: StreamFormat.DEFAULT
        ListScreen(onBack = { goBack() }, title = "Download quality") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(source?.streamFormats ?: StreamFormat.entries.toList()) { format ->
                    TextRow(
                        title = formatLabel(format),
                        onClick = {
                            App.scope.launch {
                                App.settings.setSourceDownloadFormat(sourceId, format)
                            }
                            goBack()
                        },
                        trailing = { if (format == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}


/**
 * One server's streaming quality, reached from its row on the Data page.
 *
 * Only exists for the several-servers case — with one server, its two rows sit
 * on the Data page itself.
 */
class SourceStreamingScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Streaming Quality") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (source == null) return@ScrollableList
                // The header names the server; this names what the rows set.
                item { SectionLabel("Streaming Quality") }
                item {
                    TextRow(
                        title = "On Wi-Fi",
                        subtitle = formatLabel(source.wifiFormat),
                        onClick = { go { StreamFormatScreen(it, source.id) } },
                    )
                }
                item {
                    TextRow(
                        title = "On Cellular",
                        subtitle = formatLabel(source.cellularFormat),
                        onClick = { go { CellularFormatScreen(it, source.id) } },
                    )
                }
            }
        }
    }
}

/**
 * One server's download behaviour, reached from its row on the Offline page:
 * what it fetches without being asked, at what quality, and the way to clear
 * only its music. Only exists for the several-servers case — with one server,
 * the rows sit on the Offline page itself, where Delete All already means it.
 */
class SourceOfflineScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Offline") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                if (source == null) return@ScrollableList
                item {
                    TextRow(
                        title = "Auto-Download",
                        subtitle = offlineModeLabel(source.offlineMode),
                        onClick = { go { OfflineModeScreen(it, source.id) } },
                    )
                }
                item {
                    TextRow(
                        title = "Download Quality",
                        subtitle = formatLabel(source.downloadFormat),
                        onClick = { go { SourceDownloadFormatScreen(it, source.id) } },
                    )
                }
                item { SectionLabel("Remove") }
                item {
                    TextRow(
                        title = "Delete Downloads",
                        subtitle = "This server's music only",
                    ) {
                        navigateTo<Boolean>(
                            {
                                ConfirmScreen(
                                    it,
                                    title = "Delete Downloads",
                                    message = "This removes ${source.name}'s downloaded " +
                                        "music from this phone. The library on the server " +
                                        "is untouched.",
                                    confirmLabel = "Delete",
                                )
                            },
                            resultCallback = { confirmed ->
                                if (confirmed == true) {
                                    App.scope.launch { App.deleteDownloadsFor(source) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, onToggle: () -> Unit) {
    TextRow(
        title = label,
        onClick = onToggle,
        trailing = {
            LightIcon(
                icon = if (value) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = 2.2f,
            )
        },
    )
}

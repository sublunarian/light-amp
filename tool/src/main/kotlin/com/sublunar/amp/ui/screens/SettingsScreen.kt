package com.sublunar.amp.ui.screens

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import com.sublunar.amp.App
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.formatBytes
import com.sublunar.amp.data.formatGb
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A source's download quality as its settings row shows it: the choice, and
 * plainly when this server can't meet it, since the row is where that gets fixed.
 */
private fun downloadQualityLabel(source: MusicSource): String = when {
    source.downloadsRefused -> "Not allowed by the server"
    source.downloadFormatAvailable -> formatLabel(source.downloadFormat)
    else -> "${formatLabel(source.downloadFormat)} — unavailable"
}

private fun formatLabel(format: StreamFormat): String = when (format) {
    StreamFormat.MP3 -> "MP3 (320 kbps)"
    StreamFormat.OPUS -> "Opus (192 kbps)"
    StreamFormat.FLAC -> "FLAC (lossless)"
    StreamFormat.RAW -> "Original file"
}

/** The format alone, for a subtitle that pairs it with another fact. */
private fun shortFormatLabel(format: StreamFormat): String = when (format) {
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

/** The hub: a row per category, each stating where its page stands. */
class SettingsScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val dataMode by App.settings.dataMode.state.collectAsState()
        val downloadLimit by App.settings.downloadLimit.state.collectAsState()
        // Off the disk, once per visit: what the phone actually holds. A walk of
        // every downloaded file, so off Main; a blank value until it returns keeps
        // the row label-over-value, so nothing below it moves when it lands.
        val used = produceState<Long?>(null) {
            value = withContext(Dispatchers.IO) { App.downloads.usedBytesEverywhere() }
        }.value
        val sourceNames = sources.joinToString(", ") { it.name }
        // Any server, not the active source: streaming and downloads still have
        // their pages while the phone's own music is the one playing.
        val hasServers = sources.any { it.hasServer }

        ListScreen(onBack = { goBack() }, title = "Settings") {
            ScrollableList {
                item {
                    TextRow(title = "Appearance") { go { AppearanceScreen(it) } }
                }
                item {
                    TextRow(title = "Playback") { go { PlaybackScreen(it) } }
                }
                // What is set once per source. Switching between them is on the
                // Sources page under More, reached while listening.
                item {
                    TextRow(
                        title = "Sources",
                        value = sourceNames,
                        onClick = { go { SourceListScreen(it) } },
                    )
                }
                if (hasServers) {
                    item {
                        TextRow(
                            title = "Data",
                            value = dataModeLabel(dataMode),
                            onClick = { go { DataScreen(it) } },
                        )
                    }
                    item {
                        TextRow(
                            title = "Offline",
                            value = when (used) {
                                null -> " "
                                0L -> "Nothing downloaded"
                                else -> "${formatGb(used)} / ${formatGb(downloadLimit)}"
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

/** How the audio itself is treated on the way out — levels, not sources. */
class PlaybackScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val replayGain by App.settings.replayGain.state.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Playback") {
            ScrollableList {
                item {
                    // From the loudness the server measured. Only ever turns a
                    // track down, so it cannot clip. A library with no
                    // measurements plays untouched.
                    ToggleRow("Replay Gain", replayGain) {
                        App.scope.launch { App.settings.replayGain.set(!replayGain) }
                    }
                }
            }
        }
    }
}

/** How the app looks — every switch, none of them about any one source. */
class AppearanceScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val invert by App.settings.invertColors.state.collectAsState()
        val karaoke by App.settings.karaokeLyrics.state.collectAsState()
        val hideArtistImages by App.settings.hideArtistImages.state.collectAsState()
        val hideDownloadIcons by App.settings.hideDownloadIcons.state.collectAsState()
        val artwork by App.settings.artwork.state.collectAsState()
        val layoutMode by App.settings.layoutMode.state.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Appearance") {
            ScrollableList {
                item {
                    ToggleRow("Invert Colors", invert) {
                        App.scope.launch { App.settings.invertColors.set(!invert) }
                    }
                }
                item {
                    // Covers everywhere or none, which also stops the app
                    // fetching and decoding them. Phrased as what switching it
                    // on does, like its neighbours.
                    ToggleRow("Hide Artwork", artwork == ArtworkMode.NONE) {
                        App.scope.launch {
                            App.settings.artwork.set(
                                if (artwork == ArtworkMode.NONE) {
                                    ArtworkMode.SMALL
                                } else {
                                    ArtworkMode.NONE
                                },
                            )
                        }
                    }
                }
                // With Hide Artwork on, the artists have already lost theirs.
                if (artwork != ArtworkMode.NONE) {
                    item {
                        ToggleRow("Hide Artist Photos", hideArtistImages) {
                            App.scope.launch {
                                App.settings.hideArtistImages.set(!hideArtistImages)
                            }
                        }
                    }
                }
                item {
                    // The marks go; the layout does not move with them — see
                    // AppSettings.hideDownloadIcons.
                    ToggleRow("Hide Download Icons", hideDownloadIcons) {
                        App.scope.launch {
                            App.settings.hideDownloadIcons.set(!hideDownloadIcons)
                        }
                    }
                }
                item {
                    // On is the phone's own three buttons with the library as a
                    // page. Off, the default, spreads the four tabs across the bar.
                    val simplified = layoutMode == LayoutMode.SIMPLIFIED
                    ToggleRow("Simplified Library View", simplified) {
                        App.scope.launch {
                            App.settings.layoutMode.set(
                                if (simplified) LayoutMode.STANDARD else LayoutMode.SIMPLIFIED,
                            )
                        }
                    }
                }
                item {
                    ToggleRow("Karaoke Lyrics", karaoke) {
                        App.scope.launch { App.settings.karaokeLyrics.set(!karaoke) }
                    }
                }
            }
        }
    }
}

/**
 * What playing costs over the network: Data Mode (when to stream at all) and each
 * server's streaming quality (how much a stream weighs). Download quality is on
 * [OfflineScreen], with the rest of what the phone holds.
 *
 * Qualities are per server because only some servers can transcode — see
 * MusicSource.streamFormats.
 */
class DataScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val dataMode by App.settings.dataMode.state.collectAsState()
        val sources by App.settings.sources.state.collectAsState()
        // The phone's own music never crosses the network.
        val servers = sources.filter { it.hasServer }

        ListScreen(onBack = { goBack() }, title = "Data") {
            ScrollableList {
                item {
                    TextRow(
                        title = "Data Mode",
                        value = dataModeLabel(dataMode),
                        onClick = { go { DataModeScreen(it) } },
                    )
                }
                // Wi-Fi and cellular separately: on Wi-Fi the bytes are free, on
                // cellular they are not.
                if (servers.isNotEmpty()) item { SectionLabel("Streaming Quality") }
                if (servers.size == 1) {
                    // One server: its two rows sit right here.
                    val server = servers.first()
                    item(key = "${server.id}/wifi") {
                        TextRow(
                            title = "On Wi-Fi",
                            value = formatLabel(server.wifiFormat),
                            onClick = { go { FormatPickerScreen(it, server.id, FormatSlot.WIFI) } },
                        )
                    }
                    item(key = "${server.id}/cellular") {
                        TextRow(
                            title = "On Cellular",
                            value = formatLabel(server.cellularFormat),
                            onClick = { go { FormatPickerScreen(it, server.id, FormatSlot.CELLULAR) } },
                        )
                    }
                } else {
                    // Several: a row per server under the one heading, each
                    // subtitle stating that server's answer.
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
 */
class OfflineScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    // Off the disk rather than the index, and off Main; null until first
    // measured, and shown blank so the row keeps its shape. Held on the screen,
    // not in a remember: pushing the confirmation tears this page's composition
    // down (see ARCHITECTURE.md, the SDK composes only the top of the back
    // stack), so state written by the Delete callback would never be read.
    private var used by mutableStateOf<Long?>(null)

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val servers = sources.filter { it.supportsDownloads }
        val downloadLimit by App.settings.downloadLimit.state.collectAsState()
        // Again each time the page comes back to the top: the pages this one
        // leads to are the ones that move the number.
        LaunchedEffect(Unit) {
            used = withContext(Dispatchers.IO) { App.downloads.usedBytesEverywhere() }
        }

        ListScreen(onBack = { goBack() }, title = "Offline") {
            ScrollableList {
                // A page of songs, reached from here because it is about storage.
                item {
                    TextRow(
                        title = "Downloads",
                        value = when (val bytes = used) {
                            null -> " "
                            0L -> "Nothing downloaded yet"
                            else -> formatBytes(bytes)
                        },
                        onClick = { go { DownloadsScreen(it) } },
                    )
                }
                // One budget for the phone, which is what runs out of room.
                item {
                    TextRow(
                        title = "Storage Limit",
                        value = formatBytes(downloadLimit),
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
                            value = offlineModeLabel(server.offlineMode),
                            onClick = { go { OfflineModeScreen(it, server.id) } },
                        )
                    }
                    item(key = "${server.id}/quality") {
                        TextRow(
                            title = "Download Quality",
                            value = downloadQualityLabel(server),
                            onClick = { go { FormatPickerScreen(it, server.id, FormatSlot.DOWNLOAD) } },
                        )
                    }
                } else if (servers.size > 1) {
                    // A row per server under one heading, as on the Data page.
                    // Each server's page also has its own Delete.
                    item { SectionLabel("What each server downloads") }
                    servers.forEach { server ->
                        item(key = server.id) {
                            TextRow(
                                title = server.name,
                                subtitle = "${offlineModeLabel(server.offlineMode)} · " +
                                    shortFormatLabel(server.downloadFormat) +
                                    when {
                                        server.downloadsRefused -> " — not allowed"
                                        server.downloadFormatAvailable -> ""
                                        else -> " — unavailable"
                                    },
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

/** Which of a source's three qualities a [FormatPickerScreen] sets, under the title it carries. */
enum class FormatSlot(val title: String) {
    WIFI("On Wi-Fi"),
    CELLULAR("On cellular"),
    DOWNLOAD("Download quality"),
    ;

    fun current(source: MusicSource): StreamFormat = when (this) {
        WIFI -> source.wifiFormat
        CELLULAR -> source.cellularFormat
        DOWNLOAD -> source.downloadFormat
    }

    /**
     * Only what this server will actually send — see MusicSource.streamFormats.
     * Downloads have their own list: a server with no `download` call can still
     * download, but never the original file — see downloadFormats.
     */
    fun choices(source: MusicSource): List<StreamFormat> =
        if (this == DOWNLOAD) source.downloadFormats else source.streamFormats

    suspend fun set(sourceId: String, format: StreamFormat) {
        when (this) {
            WIFI -> App.settings.setStreamFormat(sourceId, format)
            CELLULAR -> App.settings.setCellularFormat(sourceId, format)
            DOWNLOAD -> App.settings.setSourceDownloadFormat(sourceId, format)
        }
    }
}

/** One quality of one source, which isn't necessarily the active one. */
class FormatPickerScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
    private val slot: FormatSlot,
) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val source = sources.firstOrNull { it.id == sourceId }
        val current = source?.let(slot::current) ?: StreamFormat.DEFAULT
        ListScreen(onBack = { goBack() }, title = slot.title) {
            ScrollableList {
                items(source?.let(slot::choices) ?: StreamFormat.entries.toList()) { format ->
                    TextRow(
                        title = formatLabel(format),
                        onClick = {
                            App.scope.launch { slot.set(sourceId, format) }
                            goBack()
                        },
                        checked = format == current,
                    )
                }
            }
        }
    }
}

/**
 * One server's streaming quality, reached from its row on the Data page when
 * there are several. With one server its two rows sit on the Data page itself.
 */
class SourceStreamingScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val source = sources.firstOrNull { it.id == sourceId }

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Streaming Quality") {
            ScrollableList {
                if (source == null) return@ScrollableList
                // The header names the server; this names what the rows set.
                item { SectionLabel("Streaming Quality") }
                item {
                    TextRow(
                        title = "On Wi-Fi",
                        value = formatLabel(source.wifiFormat),
                        onClick = { go { FormatPickerScreen(it, source.id, FormatSlot.WIFI) } },
                    )
                }
                item {
                    TextRow(
                        title = "On Cellular",
                        value = formatLabel(source.cellularFormat),
                        onClick = { go { FormatPickerScreen(it, source.id, FormatSlot.CELLULAR) } },
                    )
                }
            }
        }
    }
}

/**
 * One server's download behaviour, reached from its row on the Offline page when
 * there are several: what it fetches unasked, at what quality, and the way to
 * clear only its music.
 */
class SourceOfflineScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val source = sources.firstOrNull { it.id == sourceId }

        ListScreen(onBack = { goBack() }, title = source?.name ?: "Offline") {
            ScrollableList {
                if (source == null) return@ScrollableList
                item {
                    TextRow(
                        title = "Auto-Download",
                        value = offlineModeLabel(source.offlineMode),
                        onClick = { go { OfflineModeScreen(it, source.id) } },
                    )
                }
                item {
                    TextRow(
                        title = "Download Quality",
                        value = downloadQualityLabel(source),
                        onClick = { go { FormatPickerScreen(it, source.id, FormatSlot.DOWNLOAD) } },
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

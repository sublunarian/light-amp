package com.sublunar.amp.ui.screens

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sublunar.amp.App
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.LayoutMode
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.formatBytes
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
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

/** The hub: one plain row per page. */
class SettingsScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        // Any server, not the active source: streaming and downloads still have
        // their page while the phone's own music is the one playing. Data Mode
        // stays for everyone: the phone's own music still looks up lyrics.
        val hasServers = sources.any { it.hasServer }
        val dataMode by App.settings.dataMode.state.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Settings", settings = true) {
            ScrollableList {
                item { TextRow(title = "Appearance") { go { AppearanceScreen(it) } } }
                item { TextRow(title = "Sources") { go { SourceListScreen(it) } } }
                // The one row that says where it stands: the mode decides what
                // works away from Wi-Fi, so it's worth seeing without a tap.
                item {
                    TextRow(title = "Data Mode", subtitle = dataModeLabel(dataMode)) {
                        go { DataModeScreen(it) }
                    }
                }
                if (hasServers) {
                    item { TextRow(title = "Streams & Downloads") { go { StreamsDownloadsScreen(it) } } }
                }
                item { TextRow(title = "Playback") { go { PlaybackScreen(it) } } }
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

        ListScreen(onBack = { goBack() }, title = "Playback", settings = true) {
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
        val sdkFontStyles by App.settings.sdkFontStyles.state.collectAsState()
        val albumShuffleRow by App.settings.albumShuffleRow.state.collectAsState()
        val outlineTabIcons by App.settings.outlineTabIcons.state.collectAsState()
        val brighterText by App.settings.brighterText.state.collectAsState()
        val sdkTabBar by App.settings.sdkTabBar.state.collectAsState()
        val artwork by App.settings.artwork.state.collectAsState()
        val layoutMode by App.settings.layoutMode.state.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Appearance", settings = true) {
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
                    ToggleRow("Use SDK Font Styles", sdkFontStyles) {
                        App.scope.launch { App.settings.sdkFontStyles.set(!sdkFontStyles) }
                    }
                }
                item {
                    ToggleRow("Album Shuffle Row", albumShuffleRow) {
                        App.scope.launch { App.settings.albumShuffleRow.set(!albumShuffleRow) }
                    }
                }
                item {
                    ToggleRow("Outline Tab Icons", outlineTabIcons) {
                        App.scope.launch { App.settings.outlineTabIcons.set(!outlineTabIcons) }
                    }
                }
                item {
                    ToggleRow("Brighter Text", brighterText) {
                        App.scope.launch { App.settings.brighterText.set(!brighterText) }
                    }
                }
                item {
                    ToggleRow("SDK Tab Bar", sdkTabBar) {
                        App.scope.launch { App.settings.sdkTabBar.set(!sdkTabBar) }
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
 * What playing and keeping music costs: each server's qualities and what it
 * fetches unasked, then the phone's storage.
 *
 * Qualities are per server because only some servers can transcode — see
 * MusicSource.streamFormats.
 */
class StreamsDownloadsScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Streams & Downloads", settings = true) {
            ScrollableList {
                ServerSetting.entries.forEach { setting ->
                    val servers = setting.servers(sources)
                    if (servers.isNotEmpty()) {
                        item(key = setting.name) {
                            // One server: straight to its choice. Several: which first.
                            TextRow(title = setting.title) {
                                val only = servers.singleOrNull()
                                if (only != null) setting.open(this@StreamsDownloadsScreen, only)
                                else go { ServerSettingScreen(it, setting) }
                            }
                        }
                    }
                }
                item { TextRow(title = "Storage") { go { StorageScreen(it) } } }
            }
        }
    }
}

/**
 * What the phone holds: the budget downloads fit in (one for the phone, which is
 * what runs out of room), the downloads themselves, and the way to clear them.
 */
class StorageScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    // Off the disk and off Main; null until measured. Held on the screen, not in
    // a remember: pushing the confirmation tears this page's composition down
    // (the SDK composes only the top of the back stack), so state written by
    // the Clear callback would never be read.
    private var used by mutableStateOf<Pair<Long, Long>?>(null)

    private suspend fun measure() {
        used = withContext(Dispatchers.IO) { App.downloads.usedBytesEverywhere() to App.artwork.cacheBytes() }
    }

    @Composable
    override fun Content() {
        val limit by App.settings.downloadLimit.state.collectAsState()
        val reserve by App.cacheReserve.bytes.collectAsState()
        // Again each time the page comes back to the top: the pages it leads to move the numbers.
        LaunchedEffect(Unit) { measure() }

        ListScreen(onBack = { goBack() }, title = "Storage", settings = true) {
            ScrollableList {
                // The limit's two shares, neither hidden: the cache's is kept for
                // it so downloads can't crowd out new covers — see CacheReserve.
                item {
                    SectionLabel(
                        used?.let { (downloads, cache) ->
                            "Downloads ${formatBytes(downloads)} of ${formatBytes(limit - reserve)} · " +
                                "Cache ${formatBytes(cache)} of ${formatBytes(reserve)}"
                        } ?: " ",
                    )
                }
                item { TextRow(title = "Storage Limit") { go { SizeLimitScreen(it) } } }
                item { TextRow(title = "Downloads") { go { DownloadsScreen(it) } } }
                // Every server's audio, not the active one's — see App.deleteAllDownloads.
                item {
                    TextRow(title = "Clear All Downloads") {
                        navigateTo<Boolean>(
                            {
                                ConfirmScreen(
                                    it,
                                    title = "Clear All Downloads",
                                    message = "This removes every source's downloaded music " +
                                        "from this phone. Your libraries on the servers are " +
                                        "untouched.",
                                    confirmLabel = "Clear",
                                )
                            },
                            resultCallback = { confirmed ->
                                if (confirmed == true) {
                                    App.scope.launch {
                                        App.deleteAllDownloads()
                                        measure()
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

/** A setting each server keeps its own of, set from [StreamsDownloadsScreen]. */
enum class ServerSetting(val title: String) {
    WIFI("Wi-Fi Quality"),
    CELLULAR("Cellular Quality"),
    DOWNLOAD("Download Quality"),
    AUTO_DOWNLOAD("Auto-Download"),
    ;

    /** The servers it applies to: any that streams, or only those that download. */
    fun servers(sources: List<MusicSource>): List<MusicSource> = when (this) {
        WIFI, CELLULAR -> sources.filter { it.hasServer }
        DOWNLOAD, AUTO_DOWNLOAD -> sources.filter { it.supportsDownloads }
    }

    /** What it is set to on [server], for the list of servers. */
    fun value(server: MusicSource): String = when (this) {
        WIFI -> formatLabel(server.wifiFormat)
        CELLULAR -> formatLabel(server.cellularFormat)
        DOWNLOAD -> downloadQualityLabel(server)
        AUTO_DOWNLOAD -> offlineModeLabel(server.offlineMode)
    }

    fun open(from: SimpleLightScreen<*>, server: MusicSource) {
        from.go {
            when (this) {
                WIFI -> FormatPickerScreen(it, server.id, FormatSlot.WIFI)
                CELLULAR -> FormatPickerScreen(it, server.id, FormatSlot.CELLULAR)
                DOWNLOAD -> FormatPickerScreen(it, server.id, FormatSlot.DOWNLOAD)
                AUTO_DOWNLOAD -> OfflineModeScreen(it, server.id)
            }
        }
    }
}

/** With several servers, which one's [setting] to change, each saying its own. */
class ServerSettingScreen(
    sealed: SealedLightActivity,
    private val setting: ServerSetting,
) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        ListScreen(onBack = { goBack() }, title = setting.title, settings = true) {
            ScrollableList {
                items(setting.servers(sources), key = { it.id }) { server ->
                    TextRow(title = server.name, value = setting.value(server)) {
                        setting.open(this@ServerSettingScreen, server)
                    }
                }
            }
        }
    }
}

/** Which of a source's three qualities a [FormatPickerScreen] sets, under the title it carries. */
enum class FormatSlot(val title: String) {
    WIFI("Wi-Fi Quality"),
    CELLULAR("Cellular Quality"),
    DOWNLOAD("Download Quality"),
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
        ListScreen(onBack = { goBack() }, title = slot.title, settings = true) {
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

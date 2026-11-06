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
    source.downloadFormatAvailable -> formatLabel(source.downloadFormat, source)
    else -> "${formatLabel(source.downloadFormat, source)} — unavailable"
}

/** A format as [source] is asked for it: at its own bitrate, which a server may cap lower. */
internal fun formatLabel(format: StreamFormat, source: MusicSource?): String {
    source?.let { it.provider.qualityLabel(it, format) }?.let { return it }
    val kbps = source?.kbps(format) ?: format.maxBitRate
    return when (format) {
        StreamFormat.MP3 -> "MP3 ($kbps kbps)"
        StreamFormat.OPUS -> "Opus ($kbps kbps)"
        StreamFormat.FLAC -> "FLAC (lossless)"
        StreamFormat.RAW -> "Original file"
    }
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

        ListScreen(onBack = { goBack() }, title = "Settings", headingRows = true) {
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
                item { TextRow(title = "Test") { go { TestScreen(it) } } }
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

        ListScreen(onBack = { goBack() }, title = "Playback", headingRows = true) {
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

/** Every cover in the app, and where else pictures go once there are any. */
class ArtworkScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val artwork by App.settings.artwork.state.collectAsState()
        val listArtwork by App.settings.listArtwork.state.collectAsState()
        val hideArtistImages by App.settings.hideArtistImages.state.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Artwork", headingRows = true) {
            ScrollableList {
                item {
                    // Covers everywhere or none, which also stops the app
                    // fetching and decoding them.
                    val shown = artwork != ArtworkMode.NONE
                    ToggleRow("Enable Artwork", shown) {
                        App.scope.launch {
                            App.settings.artwork.set(if (shown) ArtworkMode.NONE else ArtworkMode.SMALL)
                        }
                    }
                }
                // With Enable Artwork off, the lists and the artists have already lost theirs.
                if (artwork != ArtworkMode.NONE) {
                    item {
                        ToggleRow("Album Art in Lists", listArtwork) {
                            App.scope.launch { App.settings.listArtwork.set(!listArtwork) }
                        }
                    }
                    item {
                        ToggleRow("Artist Thumbnails", !hideArtistImages) {
                            App.scope.launch {
                                App.settings.hideArtistImages.set(!hideArtistImages)
                            }
                        }
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
        val syncedLyrics by App.settings.syncedLyrics.state.collectAsState()
        val hideDownloadIcons by App.settings.hideDownloadIcons.state.collectAsState()
        val sources by App.settings.sources.state.collectAsState()
        val layoutMode by App.settings.layoutMode.state.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Appearance", headingRows = true) {
            ScrollableList {
                item { TextRow(title = "Artwork") { go { ArtworkScreen(it) } } }
                // Only where something can be downloaded: the phone's own music
                // is all on the phone, so it never wears the mark.
                if (sources.any { it.supportsDownloads }) {
                    item {
                        // The marks go; the layout does not move with them — see
                        // AppSettings.hideDownloadIcons.
                        ToggleRow("Download Icons", !hideDownloadIcons) {
                            App.scope.launch {
                                App.settings.hideDownloadIcons.set(!hideDownloadIcons)
                            }
                        }
                    }
                }
                item {
                    // On spreads the four tabs across the bar. Off, the default, is
                    // the phone's own three buttons with the library as a page.
                    val tabs = layoutMode == LayoutMode.STANDARD
                    ToggleRow("Library Tabs", tabs) {
                        // Each layout's own front page, as at launch: the index
                        // for the three buttons, the last tab for the tabs.
                        LibraryNav.libraryIndex.value = tabs
                        LibraryNav.indexFolder.value = null
                        App.scope.launch {
                            App.settings.layoutMode.set(
                                if (tabs) LayoutMode.SIMPLIFIED else LayoutMode.STANDARD,
                            )
                        }
                    }
                }
                item {
                    ToggleRow("Synced Lyrics", syncedLyrics) {
                        App.scope.launch { App.settings.syncedLyrics.set(!syncedLyrics) }
                    }
                }
                item {
                    ToggleRow("Invert Colors", invert) {
                        App.scope.launch { App.settings.invertColors.set(!invert) }
                    }
                }
            }
        }
    }
}

/** Trials: looks being tried out, each to be kept or dropped. */
class TestScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val hideSongShortcuts by App.settings.hideSongShortcuts.state.collectAsState()
        val tallAlbumRows by App.settings.tallAlbumRows.state.collectAsState()
        val tallArtistRecords by App.settings.tallArtistRecords.state.collectAsState()
        val headingRowLabels by App.settings.headingRowLabels.state.collectAsState()
        val narrowListMargin by App.settings.narrowListMargin.state.collectAsState()

        // Rows a step below the other settings pages', so a trial's whole name fits.
        ListScreen(onBack = { goBack() }, title = "Test") {
            ScrollableList {
                item {
                    ToggleRow("Hide Song Shortcuts", hideSongShortcuts) {
                        App.scope.launch { App.settings.hideSongShortcuts.set(!hideSongShortcuts) }
                    }
                }
                item {
                    ToggleRow("Tall Album Rows", tallAlbumRows) {
                        App.scope.launch { App.settings.tallAlbumRows.set(!tallAlbumRows) }
                    }
                }
                item {
                    ToggleRow("Tall Artist Records", tallArtistRecords) {
                        App.scope.launch { App.settings.tallArtistRecords.set(!tallArtistRecords) }
                    }
                }
                item {
                    ToggleRow("Heading Row Labels", headingRowLabels) {
                        App.scope.launch { App.settings.headingRowLabels.set(!headingRowLabels) }
                    }
                }
                item {
                    ToggleRow("40px List Margin", narrowListMargin) {
                        App.scope.launch { App.settings.narrowListMargin.set(!narrowListMargin) }
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

        ListScreen(onBack = { goBack() }, title = "Streams & Downloads", headingRows = true) {
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
                item { TextRow(title = "Downloads") { go { DownloadsOverviewScreen(it) } } }
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

        ListScreen(onBack = { goBack() }, title = "Storage", headingRows = true) {
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
                // Every server's audio, or one server's when there are several
                // — see ClearDownloadsScreen.
                item {
                    TextRow(title = "Clear Downloads") {
                        val servers = App.settings.sources.value.filter { it.supportsDownloads }
                        if (servers.size > 1) {
                            go { ClearDownloadsScreen(it) }
                        } else {
                            confirmClearDownloads(null) { measure() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clear Downloads with several servers: all of them, or one, each with what it
 * takes up, so the choice says what it frees.
 */
class ClearDownloadsScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    // Off the disk; held on the screen, as Storage's figures are.
    private var sizes by mutableStateOf<Map<String, Long>>(emptyMap())

    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val servers = sources.filter { it.supportsDownloads }
        LaunchedEffect(servers) {
            sizes = withContext(Dispatchers.IO) { servers.associate { it.id to App.downloads.usedBytes(it.id) } }
        }
        ListScreen(onBack = { goBack() }, title = "Clear Downloads", headingRows = true) {
            ScrollableList {
                item {
                    TextRow(title = "All Servers", value = sizes.takeIf { it.isNotEmpty() }?.let { formatBytes(it.values.sum()) }) {
                        confirmClearDownloads(null) { goBack() }
                    }
                }
                items(servers, key = { it.id }) { source ->
                    TextRow(title = source.name, value = sizes[source.id]?.let { formatBytes(it) }) {
                        confirmClearDownloads(source) { goBack() }
                    }
                }
            }
        }
    }
}

/**
 * Ask, then clear [source]'s downloads, or every server's when null. Auto-Download
 * starts again straight after — see App.deleteAllDownloads. [then] runs once done.
 */
private fun SimpleLightScreen<*>.confirmClearDownloads(source: MusicSource?, then: suspend () -> Unit) {
    navigateTo<Boolean>(
        {
            ConfirmScreen(
                it,
                title = if (source == null) "Clear All Downloads" else "Clear ${source.name} Downloads",
                message = if (source == null) {
                    "This removes every source's downloaded music from this phone. Your " +
                        "libraries on the servers are untouched."
                } else {
                    "This removes ${source.name}'s downloaded music from this phone. Its " +
                        "library on the server is untouched."
                },
                confirmLabel = "Clear",
            )
        },
        resultCallback = { confirmed ->
            if (confirmed == true) {
                App.scope.launch {
                    if (source == null) App.deleteAllDownloads() else App.deleteDownloadsFor(source)
                    withContext(Dispatchers.Main) { then() }
                }
            }
        },
    )
}

/** A setting each server keeps its own of, set from [StreamsDownloadsScreen]. */
enum class ServerSetting(val title: String) {
    WIFI("Wi-Fi Quality"),
    CELLULAR("Cellular Quality"),
    DOWNLOAD("Download Quality"),
    AUTO_DOWNLOAD("Auto-Download"),
    ;

    /**
     * The servers it applies to: any that streams, or only those that download
     * — and, for a quality, only those with more than one to choose between
     * (Bandcamp has one), so a choice that isn't one is never shown.
     */
    fun servers(sources: List<MusicSource>): List<MusicSource> = when (this) {
        WIFI, CELLULAR -> sources.filter { it.hasServer && it.streamFormats.size > 1 }
        DOWNLOAD -> sources.filter { it.supportsDownloads && it.downloadFormats.size > 1 }
        AUTO_DOWNLOAD -> sources.filter { it.supportsDownloads }
    }

    /** What it is set to on [server], for the list of servers. */
    fun value(server: MusicSource): String = when (this) {
        WIFI -> formatLabel(server.wifiFormat, server)
        CELLULAR -> formatLabel(server.cellularFormat, server)
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
        ListScreen(onBack = { goBack() }, title = setting.title, headingRows = true) {
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
        ListScreen(onBack = { goBack() }, title = slot.title, headingRows = true) {
            ScrollableList {
                items(source?.let(slot::choices) ?: StreamFormat.entries.toList()) { format ->
                    TextRow(
                        title = formatLabel(format, source),
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

/** A switch before its label, as on the phone's own settings pages (APN Enabled). */
@Composable
fun ToggleRow(label: String, value: Boolean, onToggle: () -> Unit) {
    TextRow(
        title = label,
        onClick = onToggle,
        leading = {
            LightIcon(
                icon = if (value) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = 2.2f,
            )
        },
    )
}

package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.formatBytes
import com.sublunar.amp.data.ArtworkMode
import com.sublunar.amp.data.DataMode
import com.sublunar.amp.data.LayoutMode
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

class SettingsScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val invert by App.settings.invertColors.collectAsState(initial = false)
        val karaoke by App.settings.karaokeLyrics.collectAsState(initial = true)
        val monochrome by App.settings.monochromeArtwork.collectAsState(initial = true)
        val hideArtistImages by App.settings.hideArtistImages.collectAsState(initial = false)
        val hideDownloadIcons by App.hideDownloadIcons.collectAsState()
        val artwork by App.settings.artwork.collectAsState(initial = ArtworkMode.SMALL)
        val layoutMode by App.settings.layoutMode.collectAsState(initial = LayoutMode.STANDARD)
        val downloadLimit by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        val dataMode by App.settings.dataMode.collectAsState(initial = DataMode.WIFI_ONLY)
        // Nothing to download when the audio is already on the phone — see
        // MusicSource.supportsDownloads.
        val source by App.source.collectAsState()
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val sourceNames = sources.joinToString(", ") { it.name }
        val config by App.settings.serverConfig.collectAsState(initial = null)
        val sync by App.library.syncState.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Settings") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // Streaming quality lives on the server's own page: it is a
                // property of what that server can transcode, not of the app.
                item { SectionLabel("Playback") }
                item {
                    TextRow(
                        title = "Data Mode",
                        subtitle = dataModeLabel(dataMode),
                        onClick = { go { DataModeScreen(it) } },
                    )
                }
                // What is already on the phone. A page of songs, but reached
                // from here rather than from the library: it is about storage —
                // how much is used, what to clear — which is the question this
                // screen answers, and the songs are how you answer it.
                if (source.supportsDownloads) {
                    item { TextRow(title = "Downloads") { go { DownloadsScreen(it) } } }
                    // Beside Downloads rather than inside a source: one budget
                    // for the phone, which is the thing that runs out of room.
                    item {
                        TextRow(
                            title = "Storage Limit",
                            subtitle = formatBytes(downloadLimit),
                            onClick = { go { SizeLimitScreen(it) } },
                        )
                    }
                }
                // Everything about a source that is set once — its address, its
                // quality, what it downloads. Switching *between* them stays on
                // the Sources page under More, where it is reached while
                // listening rather than while configuring.
                item {
                    TextRow(
                        title = "Sources",
                        subtitle = sourceNames,
                        onClick = { go { SourceListScreen(it) } },
                    )
                }

                item { SectionLabel("Appearance") }
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
                // Under the switch it depends on, and gone when that switch
                // hides the covers: there is nothing left for it to colour.
                if (artwork != ArtworkMode.NONE) {
                    item {
                        ToggleRow("Monochrome Artwork", monochrome) {
                            App.scope.launch {
                                App.settings.setMonochromeArtwork(!monochrome)
                            }
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

                // About lives here rather than on More: it's a page about the
                // app, which is what this screen is for.
                item { SectionLabel("About") }
                item { TextRow(title = "About Amp") { go { AboutScreen(it) } } }
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

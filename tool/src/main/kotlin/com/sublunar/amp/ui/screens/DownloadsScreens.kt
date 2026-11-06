package com.sublunar.amp.ui.screens

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sublunar.amp.App
import com.sublunar.amp.data.DataMode
import com.sublunar.amp.data.Downloader
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.OfflineMode
import com.sublunar.amp.data.formatBytes
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

fun offlineModeLabel(mode: OfflineMode): String = when (mode) {
    OfflineMode.MANUAL -> "Manual"
    OfflineMode.FAVORITES -> "Favorites"
    OfflineMode.ALL -> "Everything"
}

private fun offlineModeDetail(mode: OfflineMode): String = when (mode) {
    OfflineMode.MANUAL -> "Hold a song, album or artist to download it"
    OfflineMode.FAVORITES -> "Liked albums, liked songs and playlists"
    OfflineMode.ALL -> "Favorites first, then the rest of the library"
}

fun dataModeLabel(mode: DataMode): String = when (mode) {
    DataMode.MAKE_IT_HURT -> "Make it Hurt"
    DataMode.LOW_DATA -> "Low Data"
    DataMode.WIFI_ONLY -> "Wi-Fi Only"
}

/** The explanations under each mode wrap in full: a capped one is cut mid-sentence on the LP3. */
private const val MODE_DETAIL_LINES = Int.MAX_VALUE

private fun dataModeDetail(mode: DataMode): String = when (mode) {
    DataMode.MAKE_IT_HURT -> "No limits. Amp uses cellular data for everything when there's no Wi-Fi"
    DataMode.LOW_DATA -> "Uses cellular data for music, lyrics and the artwork you look at. Downloads wait for Wi-Fi"
    DataMode.WIFI_ONLY -> "Never uses cellular data"
}

/** A row per server whose downloads are stuck, saying why and, where it can, what to do. */
internal fun LazyListScope.downloadProblems(
    screen: SimpleLightScreen<*>,
    unmet: List<MusicSource>,
    refused: List<MusicSource>,
    parked: List<MusicSource>,
    holds: Map<String, Downloader.Hold>,
) {
    unmet.forEach { source ->
        item(key = "unmet/${source.id}") {
            if (source.downloadsRefused) {
                RefusedDownloadsRow(source)
            } else {
                TextRow(
                    title = "${source.name} can't keep original files",
                    subtitle = "Choose another download quality",
                    subtitleLines = 2,
                    onClick = { screen.go { FormatPickerScreen(it, source.id, FormatSlot.DOWNLOAD) } },
                )
            }
        }
    }
    refused.forEach { source ->
        item(key = "refused/${source.id}") {
            if ((holds[source.id] as? Downloader.Hold.Refused)?.status == 401) {
                TextRow(
                    title = "${source.name} refused the login",
                    subtitle = "Sign in again in Sources",
                    subtitleLines = 2,
                    onClick = { screen.go { SourceDetailScreen(it, source.id) } },
                )
            } else {
                RefusedDownloadsRow(source)
            }
        }
    }
    parked.forEach { source ->
        item(key = "parked/${source.id}") {
            // A tap is someone saying the server is back: try now.
            TextRow(
                title = "${source.name} isn't answering",
                subtitle = listOfNotNull("Its downloads wait for it", (holds[source.id] as? Downloader.Hold.Parked)?.error).joinToString(" · "),
                subtitleLines = 2,
                onClick = { App.downloader.tryNow(source.id) },
            )
        }
    }
}

@Composable
private fun RefusedDownloadsRow(source: MusicSource) {
    TextRow(
        title = "${source.name} won't let this account download",
        subtitle = "Its owner can allow it in the server's user settings",
        subtitleLines = 2,
    )
}

/**
 * What one server downloads without being asked. Titled "Auto-Download", not after
 * the OfflineMode setting, which sounds like a switch that takes the app offline.
 */
class OfflineModeScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.state.collectAsState()
        val current = sources.firstOrNull { it.id == sourceId }?.offlineMode ?: OfflineMode.MANUAL
        ListScreen(onBack = { goBack() }, title = "Auto-Download", headingRows = true) {
            ScrollableList {
                items(OfflineMode.entries.toList()) { mode ->
                    TextRow(
                        title = offlineModeLabel(mode),
                        subtitle = offlineModeDetail(mode),
                        subtitleLines = MODE_DETAIL_LINES,
                        onClick = {
                            App.scope.launch { App.settings.setSourceOfflineMode(sourceId, mode) }
                            goBack()
                        },
                        checked = mode == current,
                    )
                }
            }
        }
    }
}

class DataModeScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.dataMode.state.collectAsState()
        ListScreen(onBack = { goBack() }, title = "Data Mode", headingRows = true) {
            ScrollableList {
                items(DataMode.entries.toList()) { mode ->
                    TextRow(
                        title = dataModeLabel(mode),
                        subtitle = dataModeDetail(mode),
                        subtitleLines = MODE_DETAIL_LINES,
                        onClick = {
                            App.scope.launch { App.settings.dataMode.set(mode) }
                            goBack()
                        },
                        checked = mode == current,
                    )
                }
            }
        }
    }
}

/**
 * Size budget. The offered values stop at what the device can actually spare (see
 * [com.sublunar.amp.data.DownloadStore.maxSelectableBytes]).
 */
class SizeLimitScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.downloadLimit.state.collectAsState()
        val max = remember { App.downloads.maxSelectableBytes() }
        val free = remember { App.downloads.freeBytes() }
        val used = remember { App.downloads.usedBytesEverywhere() }
        val options = remember(max) {
            val gb = 1024L * 1024 * 1024
            listOf(1L, 2L, 5L, 10L, 20L, 32L, 48L).map { it * gb }.filter { it < max } + listOf(max)
        }

        ListScreen(onBack = { goBack() }, title = "Storage Limit", headingRows = true) {
            ScrollableList {
                // Both numbers, because a limit is chosen against both.
                item {
                    SectionLabel(
                        "${formatBytes(used)} downloaded · ${formatBytes(free)} free",
                    )
                }
                items(options) { bytes ->
                    TextRow(
                        title = formatBytes(bytes),
                        subtitle = if (bytes == max) "Maximum" else null,
                        onClick = {
                            App.scope.launch { App.settings.downloadLimit.set(bytes) }
                            goBack()
                        },
                        checked = bytes == current,
                    )
                }
            }
        }
    }
}

/** Generic destructive confirmation; returns true only if confirmed. */
class ConfirmScreen(
    sealed: SealedLightActivity,
    private val title: String,
    private val message: String,
    private val confirmLabel: String,
) : AmpScreen<Boolean>(sealed) {
    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack(false) }, title = title) {
            ScrollableList {
                item { SectionLabel(message) }
                item { TextRow(title = confirmLabel) { goBack(true) } }
                item { TextRow(title = "Cancel") { goBack(false) } }
            }
        }
    }
}

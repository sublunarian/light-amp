package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import com.sublunar.amp.data.Track
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.DataMode
import com.sublunar.amp.data.OfflineMode
import com.sublunar.amp.data.formatBytes
import com.sublunar.amp.data.formatGb
import com.sublunar.amp.data.sortSongs
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.n
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.launch

fun offlineModeLabel(mode: OfflineMode): String = when (mode) {
    OfflineMode.MANUAL -> "Manual"
    OfflineMode.FAVORITES -> "Favorites"
    OfflineMode.ALL -> "Everything"
}

private fun offlineModeDetail(mode: OfflineMode): String = when (mode) {
    OfflineMode.MANUAL -> "You choose what to download"
    OfflineMode.FAVORITES -> "Liked albums, liked songs and playlists"
    OfflineMode.ALL -> "Favorites first, then the rest of the library"
}

fun dataModeLabel(mode: DataMode): String = when (mode) {
    DataMode.MAKE_IT_HURT -> "Make it Hurt"
    DataMode.LOW_DATA -> "Low Data"
    DataMode.WIFI_ONLY -> "WiFi Only"
}

/**
 * Room for the longest of the six explanations below to wrap rather than be cut
 * off mid-sentence. Two lines covers them all at the widest of these screens'
 * layouts; the cap stays so a row can't grow without bound if one is reworded.
 */
private const val MODE_DETAIL_LINES = 2

private fun dataModeDetail(mode: DataMode): String = when (mode) {
    DataMode.MAKE_IT_HURT -> "Stream on cellular even when a download would do"
    DataMode.LOW_DATA -> "Off Wi-Fi, play the download rather than streaming"
    DataMode.WIFI_ONLY -> "Streams on Wi-Fi; downloads only on cellular"
}

/** Everything the user has downloaded, newest first. */
class DownloadsScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        // Every source's downloads, not the active one's — see
        // App.downloadsBySource. Read once when the page opens rather than
        // observed: it reaches into databases the app is not otherwise holding
        // open, and this is a page you look at rather than one that changes
        // under you.
        var bySource by remember { mutableStateOf<List<Pair<String, List<Track>>>>(emptyList()) }
        LaunchedEffect(Unit) { bySource = App.downloadsBySource() }
        // The song lists' own order, chosen from the header — this page used to
        // be stuck with whatever order the store handed back.
        val sort by App.songSort.collectAsState()
        val reversed by App.songSortReversed.collectAsState()
        val sorted = remember(bySource, sort, reversed) {
            bySource.map { (name, list) -> name to sortSongs(list, sort, reversed) }
        }
        val total = remember(sorted) { sorted.sumOf { it.second.size } }
        val named = sorted.size > 1
        // Across every source, off the disk: this page is about what the phone
        // is holding, and the budget it is measured against covers all of it.
        val bytes = remember { App.downloads.usedBytesEverywhere() }
        val limit by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        val progress by App.downloader.progress.collectAsState()
        val paused by App.settings.downloadsPaused.collectAsState(initial = false)

        // No tab bar and no menu: this is reached from Settings, and it is a
        // page about storage rather than a way of browsing the library. Back
        // returns to the settings it was opened from, which is the only route
        // in. Its order still follows the song lists' — see App.songSort — it
        // simply isn't set from here any more.
        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
            AppHeader(
                onBack = { goBack() },
                title = "Downloads",
                fitTitle = true,
            )
            if (total == 0 && !progress.active && bytes == 0L) {
                EmptyState("Nothing downloaded yet")
                return@Column
            }
            LibraryList(
                anchor = "downloaded-songs",
                modifier = Modifier.fillMaxSize(),
            ) {
                if (progress.active || paused) {
                    item {
                        // Separate from pause: one stops for now, the other throws
                        // away a queue that can take an hour to rebuild, and they
                        // should never be the same tap.
                        TextRow(
                            title = "Cancel Downloads",
                            subtitle = "Clears what's still queued",
                            onClick = { App.downloader.cancelAll() },
                            trailing = { AppIcon(AppIcons.Close, size = n(24)) },
                        )
                    }
                    item {
                        // Pause rather than cancel: the X threw away a queue that
                        // takes an hour to rebuild, and "stop for now" is what
                        // anyone actually wants from a running download.
                        TextRow(
                            // The track, not a position in the batch: the batch is
                            // the whole library and the size limit stops it long
                            // before the end, so "N of 10105" told the user nothing.
                            title = progress.currentTitle ?: "Downloading",
                            subtitle = if (paused) "Paused" else "Downloading",
                            onClick = { App.downloader.setUserPaused(!paused) },
                            trailing = {
                                AppIcon(
                                    if (paused) AppIcons.PlayArrow else AppIcons.Pause,
                                    size = n(24),
                                )
                            },
                        )
                    }
                }
                item {
                    SectionLabel("$total tracks · ${formatGb(bytes)} / ${formatGb(limit)}")
                }
                // A list to read, not to play from: a track from a source that
                // isn't the current one resolves its file and its stream through
                // the wrong server, so tapping it could only mislead. The
                // library is where music is played from; this is where you see
                // what the phone is holding.
                sorted.forEach { (source, list) ->
                    // Named only when there is more than one, since with a
                    // single source the heading would repeat the obvious.
                    if (named) item(key = "src-$source") { SectionLabel(source) }
                    items(list, key = { "$source-${it.id}" }) { track ->
                        TrackRow(
                            title = track.title,
                            subtitle = track.artist,
                            coverArtId = track.coverArtId,
                            downloaded = true,
                            onClick = {},
                        )
                    }
                }
            }
            }
        }
    }
}

class OfflineModeScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val current = sources.firstOrNull { it.id == sourceId }?.offlineMode ?: OfflineMode.MANUAL
        ListScreen(onBack = { goBack() }, title = "Offline Mode") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(OfflineMode.entries.toList()) { mode ->
                    TextRow(
                        title = offlineModeLabel(mode),
                        // A sentence, not a label — see TextRow.subtitleLines.
                        subtitle = offlineModeDetail(mode),
                        subtitleLines = MODE_DETAIL_LINES,
                        onClick = {
                            App.scope.launch { App.settings.setSourceOfflineMode(sourceId, mode) }
                            goBack()
                        },
                        trailing = { if (mode == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}

class DataModeScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.dataMode.collectAsState(initial = DataMode.WIFI_ONLY)
        ListScreen(onBack = { goBack() }, title = "Data Mode") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(DataMode.entries.toList()) { mode ->
                    TextRow(
                        title = dataModeLabel(mode),
                        // A sentence, not a label — see TextRow.subtitleLines.
                        subtitle = dataModeDetail(mode),
                        subtitleLines = MODE_DETAIL_LINES,
                        onClick = {
                            App.scope.launch { App.settings.setDataMode(mode) }
                            goBack()
                        },
                        trailing = { if (mode == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}


/**
 * Size budget. The offered values stop at what the device can actually spare —
 * see [com.sublunar.amp.data.DownloadStore.maxSelectableBytes].
 */
class SizeLimitScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        val max = remember { App.downloads.maxSelectableBytes() }
        val free = remember { App.downloads.freeBytes() }
        val used = remember { App.downloads.usedBytesEverywhere() }
        val options = remember(max) {
            val gb = 1024L * 1024 * 1024
            listOf(1L, 2L, 5L, 10L, 20L, 32L, 48L).map { it * gb }.filter { it < max } + listOf(max)
        }

        ListScreen(onBack = { goBack() }, title = "Size Limit") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // Both numbers, because a limit is chosen against both: free
                // space alone makes the options look arbitrarily small next to
                // what is already downloaded.
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
                            App.scope.launch { App.settings.setDownloadLimit(bytes) }
                            goBack()
                        },
                        trailing = { if (bytes == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
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
) : SimpleLightScreen<Boolean>(sealed) {
    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack(false) }, title = title) {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel(message) }
                item { TextRow(title = confirmLabel) { goBack(true) } }
                item { TextRow(title = "Cancel") { goBack(false) } }
            }
        }
    }
}

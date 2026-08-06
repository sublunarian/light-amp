package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.DataMode
import com.sublunar.amp.data.OfflineMode
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.formatBytes
import com.sublunar.amp.data.formatGb
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SplitActionRow
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.PlayerTheme
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
        val tracks by App.library.downloads.collectAsState(initial = emptyList())
        val bytes by App.library.downloadedBytes.collectAsState()
        val limit = App.source.collectAsState().value.downloadLimit
        // The global count, not `tracks.size`: the list below is scoped to the
        // library in view, while the downloads themselves are not.
        val downloadedCount by App.library.downloadedTrackIds.collectAsState()
        val progress by App.downloader.progress.collectAsState()
        val paused by App.settings.downloadsPaused.collectAsState(initial = false)

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
            AppHeader(
                onBack = { goBack() },
                title = "Downloaded Songs",
                searchAction = { openLibrarySearch(withKeyboard = true) },
                rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
            )
            if (tracks.isEmpty() && !progress.active && bytes == 0L) {
                EmptyState("Nothing downloaded yet")
                return@Column
            }
            ScrollableList(modifier = Modifier.fillMaxSize()) {
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
                // Storage used against the budget, whatever the list shows: the
                // rows are scoped to the selected library, so they can be empty
                // while there are gigabytes downloaded from another one.
                item {
                    SectionLabel(
                        "${downloadedCount.size} tracks · " +
                            "${formatGb(bytes)} / ${formatGb(limit)}",
                    )
                }
                if (tracks.isNotEmpty()) {
                    item {
                        SplitActionRow(
                            leftIcon = AppIcons.PlayArrow,
                            leftLabel = "Play",
                            onLeft = {
                                App.playback.playQueue(tracks, 0)
                                go { NowPlayingScreen(it) }
                            },
                            rightIcon = AppIcons.Shuffle,
                            rightLabel = "Shuffle",
                            onRight = {
                                App.playback.playQueue(shuffled(tracks), 0)
                                go { NowPlayingScreen(it) }
                            },
                        )
                    }
                    itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                        TrackRow(
                            title = track.title,
                            subtitle = track.artist,
                            coverArtId = track.coverArtId,
                            downloaded = true,
                            onClick = {
                                App.playback.playQueue(tracks, index)
                                go { NowPlayingScreen(it) }
                            },
                            onLongClick = { go { TrackActionsScreen(it, track.id) } },
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * One source's downloads: what to fetch, how much of the phone to spend, and
 * what to throw away.
 *
 * Per source rather than app-wide because the downloads are: each source keeps
 * its own folder and its own rows, so "everything, up to 20GB" said once for the
 * whole app meant something different depending on which source you were looking
 * at — the budget was being weighed against one source's usage either way.
 */
class DownloadSettingsScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val source = sources.firstOrNull { it.id == sourceId }
        val used by App.library.downloadedBytes.collectAsState()
        val progress by App.downloader.progress.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Downloads") {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (source == null) return@LazyColumn
                val mode = source.offlineMode
                val limit = source.downloadLimit
                val lyrics = source.wantsLyrics
                item { SectionLabel("Using ${formatBytes(used)} of ${formatBytes(limit)}") }
                if (progress.limitReached) {
                    item { SectionLabel("Size limit reached — raise it to download more") }
                }

                item { SectionLabel("What to download") }
                item {
                    TextRow(
                        title = "Offline Mode",
                        subtitle = offlineModeLabel(mode),
                        onClick = { go { OfflineModeScreen(it, sourceId) } },
                    )
                }
                item {
                    TextRow(
                        title = "Size Limit",
                        subtitle = formatBytes(limit),
                        onClick = { go { SizeLimitScreen(it, sourceId) } },
                    )
                }
                item {
                    TextRow(
                        title = "Library",
                        subtitle = source.downloadLibraryId?.let { "One library" } ?: "Current library",
                        onClick = { go { DownloadLibraryScreen(it, sourceId) } },
                    )
                }

                item { SectionLabel("Quality") }
                if (source.kind != SourceKind.LOCAL) {
                    item {
                        TextRow(
                            title = "Download Format",
                            subtitle = formatLabel(source.downloadFormat),
                            onClick = { go { SourceDownloadFormatScreen(it, sourceId) } },
                        )
                    }
                }
                item {
                    ToggleRow("Include Lyrics", lyrics) {
                        App.scope.launch {
                            App.settings.setSourceDownloadLyrics(sourceId, !lyrics)
                        }
                    }
                }

                item { SectionLabel("Storage") }
                item {
                    TextRow(title = "Delete All Downloads") {
                        navigateTo<Boolean>(
                            {
                                ConfirmScreen(
                                    it,
                                    title = "Delete All Downloads",
                                    message = "This removes ${formatBytes(used)} of downloaded " +
                                        "music from this phone. Your library on the server is " +
                                        "untouched.",
                                    confirmLabel = "Delete",
                                )
                            },
                            resultCallback = { confirmed ->
                                if (confirmed == true) {
                                    App.scope.launch { App.downloader.deleteEverything() }
                                }
                            },
                        )
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(OfflineMode.entries.toList()) { mode ->
                    TextRow(
                        title = offlineModeLabel(mode),
                        subtitle = offlineModeDetail(mode),
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(DataMode.entries.toList()) { mode ->
                    TextRow(
                        title = dataModeLabel(mode),
                        subtitle = dataModeDetail(mode),
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
class SizeLimitScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val current = sources.firstOrNull { it.id == sourceId }?.downloadLimit ?: 0L
        val max = remember { App.downloads.maxSelectableBytes() }
        val free = remember { App.downloads.freeBytes() }
        val options = remember(max) {
            val gb = 1024L * 1024 * 1024
            listOf(1L, 2L, 5L, 10L, 20L, 32L, 48L).map { it * gb }.filter { it < max } + listOf(max)
        }

        ListScreen(onBack = { goBack() }, title = "Size Limit") {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel("${formatBytes(free)} free on this phone") }
                items(options) { bytes ->
                    TextRow(
                        title = formatBytes(bytes),
                        subtitle = if (bytes == max) "Maximum" else null,
                        onClick = {
                            App.scope.launch {
                                App.settings.setSourceDownloadLimit(sourceId, bytes)
                            }
                            goBack()
                        },
                        trailing = { if (bytes == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
            }
        }
    }
}

class DownloadLibraryScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val current = sources.firstOrNull { it.id == sourceId }?.downloadLibraryId
        var folders by remember { mutableStateOf<List<com.sublunar.amp.data.MusicFolder>>(emptyList()) }
        LaunchedEffect(Unit) { folders = App.library.musicFolders() }

        ListScreen(onBack = { goBack() }, title = "Download Library") {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    TextRow(
                        title = "Current library",
                        subtitle = "Follow whatever the app is browsing",
                        onClick = {
                            App.scope.launch {
                                App.settings.setSourceDownloadLibrary(sourceId, null)
                            }
                            goBack()
                        },
                        trailing = { if (current == null) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
                    )
                }
                items(folders, key = { it.id }) { folder ->
                    TextRow(
                        title = folder.name,
                        onClick = {
                            App.scope.launch {
                                App.settings.setSourceDownloadLibrary(sourceId, folder.id)
                            }
                            goBack()
                        },
                        trailing = {
                            if (current == folder.id) LightIcon(LightIcons.ACCEPT, size = 1.4f)
                        },
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { SectionLabel(message) }
                item { TextRow(title = confirmLabel) { goBack(true) } }
                item { TextRow(title = "Cancel") { goBack(false) } }
            }
        }
    }
}

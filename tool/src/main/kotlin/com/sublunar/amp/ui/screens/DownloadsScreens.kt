package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
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
import com.sublunar.amp.data.DataMode
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.OfflineMode
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.formatBytes
import com.sublunar.amp.data.formatGb
import com.sublunar.amp.data.sortSongs
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    DataMode.MAKE_IT_HURT -> "No limits — streaming, downloads and artwork all use cellular data when there's no Wi-Fi"
    DataMode.LOW_DATA -> "Streams music on cellular data. Downloads and library artwork wait for Wi-Fi; the cover of what's playing doesn't"
    DataMode.WIFI_ONLY -> "Never uses cellular data. Without Wi-Fi, only your downloads play"
}

/** Everything the phone holds, from every source, in the song lists' order. */
class DownloadsScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    // While casting, the rocker belongs to the speaker (see handleVolumeKey).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        // Every source's downloads (App.downloadsBySource), read again each time a
        // download lands. Keyed on the downloader's count of completed transfers
        // rather than observing every source, so the other sources' databases are
        // opened for a moment, not held open while the page is up. The active
        // source's set is watched too, for a download removed from a library page.
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val progress by App.downloader.progress.collectAsState()
        var bySource by remember { mutableStateOf<List<Pair<String, List<Track>>>>(emptyList()) }
        val sort by App.songSort.collectAsState()
        val reversed by App.songSortReversed.collectAsState()
        val sorted = remember(bySource, sort, reversed) {
            bySource.map { (name, list) -> name to sortSongs(list, sort, reversed) }
        }
        val total = remember(sorted) { sorted.sumOf { it.second.size } }
        val named = sorted.size > 1
        // Bytes across every source, as the budget counts them, read with the lists
        // so the figure and the songs it counts agree.
        var bytes by remember { mutableStateOf(0L) }
        LaunchedEffect(downloadedIds, progress.completed) {
            // Both touch the disk, so off the frame. A change mid-read restarts it.
            val read = withContext(Dispatchers.IO) {
                App.downloadsBySource() to App.downloads.usedBytesEverywhere()
            }
            bySource = read.first
            bytes = read.second
        }
        val limit by App.settings.downloadLimit.collectAsState(
            initial = AppSettings.DEFAULT_DOWNLOAD_LIMIT,
        )
        val paused by App.settings.downloadsPaused.collectAsState(initial = false)
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        // The running download names its server only when there are several.
        val severalServers = sources.count { it.supportsDownloads } > 1
        // Why nothing is arriving, read from the settings and the downloader: servers
        // that can't serve the chosen quality (their downloads wait rather than come
        // down as something else), that refused the last download
        // (Downloader.refusals), or that stopped answering (Downloader.parkings).
        val unmet = sources.filter { it.supportsDownloads && !it.downloadFormatAvailable }
        val refusedStatus by App.downloader.refused.collectAsState()
        val refused = sources.filter { it.id in refusedStatus && it !in unmet }
        val parkedError by App.downloader.parked.collectAsState()
        val parked = sources.filter { it.id in parkedError && it !in unmet && it !in refused }

        // No tab bar and no menu: a page about storage, reached from Settings.
        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(
                    onBack = { goBack() },
                    title = "Downloads",
                    fitTitle = true,
                )
                val nothingToShow = total == 0 && !progress.active && bytes == 0L &&
                    unmet.isEmpty() && refused.isEmpty() && parked.isEmpty()
                if (nothingToShow) {
                    // Says how, not just that.
                    EmptyState("Nothing downloaded yet.\nHold a song, album or artist and choose Download.")
                    return@Column
                }
                LibraryList(
                    anchor = "downloaded-songs",
                    modifier = Modifier.fillMaxSize(),
                ) {
                    downloadProblems(unmet, refused, refusedStatus, parked, parkedError)
                    if (progress.active || paused) {
                        item {
                            // Separate from pause: this throws away a queue that can
                            // take an hour to rebuild.
                            TextRow(
                                title = "Cancel Downloads",
                                subtitle = "Clears what's still queued",
                                onClick = { App.downloader.cancelAll() },
                                trailing = { AppIcon(AppIcons.Close, size = px(61)) },
                            )
                        }
                        item {
                            // Held, and why the last try failed.
                            val heldError = progress.error.takeIf { progress.currentSource == null }
                            TextRow(
                                // The track, not a position in a batch the size limit
                                // stops long before its end.
                                title = progress.currentTitle ?: "Downloading",
                                subtitle = when {
                                    paused -> "Paused"
                                    heldError != null -> heldError
                                    severalServers && progress.currentSource != null ->
                                        "Downloading · ${progress.currentSource}"
                                    else -> "Downloading"
                                },
                                onClick = { App.downloader.setUserPaused(!paused) },
                                trailing = {
                                    AppIcon(
                                        if (paused) AppIcons.PlayArrow else AppIcons.Pause,
                                        size = px(61),
                                    )
                                },
                            )
                        }
                    }
                    item {
                        SectionLabel("$total tracks · ${formatGb(bytes)} / ${formatGb(limit)}")
                    }
                    // A list to read, not to play from: a track from a source that
                    // isn't the current one would resolve through the wrong server.
                    sorted.forEach { (source, list) ->
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

    /** A row per server whose downloads are stuck, saying why and, where it can, what to do. */
    private fun LazyListScope.downloadProblems(
        unmet: List<MusicSource>,
        refused: List<MusicSource>,
        refusedStatus: Map<String, Int>,
        parked: List<MusicSource>,
        parkedError: Map<String, String?>,
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
                        onClick = { go { SourceDownloadFormatScreen(it, source.id) } },
                    )
                }
            }
        }
        refused.forEach { source ->
            item(key = "refused/${source.id}") {
                if (refusedStatus[source.id] == 401) {
                    TextRow(
                        title = "${source.name} refused the login",
                        subtitle = "Sign in again in Sources",
                        subtitleLines = 2,
                        onClick = { go { SourceDetailScreen(it, source.id) } },
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
                    subtitle = listOfNotNull("Its downloads wait for it", parkedError[source.id]).joinToString(" · "),
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
}

/**
 * What one server downloads without being asked. Titled "Auto-Download", not after
 * the OfflineMode setting, which sounds like a switch that takes the app offline.
 */
class OfflineModeScreen(
    sealed: SealedLightActivity,
    private val sourceId: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val sources by App.settings.sources.collectAsState(initial = emptyList())
        val current = sources.firstOrNull { it.id == sourceId }?.offlineMode ?: OfflineMode.MANUAL
        ListScreen(onBack = { goBack() }, title = "Auto-Download") {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                items(OfflineMode.entries.toList()) { mode ->
                    TextRow(
                        title = offlineModeLabel(mode),
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
 * Size budget. The offered values stop at what the device can actually spare (see
 * [com.sublunar.amp.data.DownloadStore.maxSelectableBytes]).
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

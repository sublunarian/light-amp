package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Column
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
import com.sublunar.amp.data.Downloader
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.OfflineMode
import com.sublunar.amp.data.formatBytes
import com.sublunar.amp.data.formatGb
import com.sublunar.amp.data.primaryAlbumArtist
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the downloaded albums are ordered. Largest first: freeing room is what the page is for. */
enum class DownloadSort(val label: String) {
    LARGEST("Largest"),
    RECENT("Recently Downloaded"),
    NAME("Name"),
}

/** One album's worth of the queue, or a source's automatic lane as a whole ([auto]). */
private class QueueGroup(
    val source: MusicSource,
    val title: String,
    val subtitle: String,
    val coverArtId: String?,
    val trackIds: List<String>,
    val auto: Boolean,
)

/** One album's downloads: what is on the phone of it, and how much room it takes. */
private class DownloadedAlbum(
    val source: MusicSource,
    val albumId: String?,
    val title: String,
    val artist: String,
    val coverArtId: String?,
    val trackIds: List<String>,
    val bytes: Long,
    val newestMs: Long,
    /** Every song of it is one its server's Auto-Download wants: removed, it would come back. */
    val kept: Boolean,
)

/**
 * What the library pages can't say about downloads.
 * Not a list of every song — the tabs' Downloaded filter already browses what is
 * offline — but whether it's working, what is coming, and what takes the room:
 * the queue by album, with Download First and Cancel, then the downloaded albums
 * with their sizes, largest first.
 */
class DownloadsOverviewScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    // Held on the screen: the SDK composes only the top of the stack, so a
    // remembered value would be lost under the sort picker.
    private var sort by mutableStateOf(DownloadSort.LARGEST)

    @Composable
    override fun Content() {
        val progress by App.downloader.progress.collectAsState()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val paused by App.settings.downloadsPaused.state.collectAsState()
        val sources by App.settings.sources.state.collectAsState()
        val active by App.settings.activeSource.state.collectAsState()
        val limit by App.settings.downloadLimit.state.collectAsState()
        val reserve by App.cacheReserve.bytes.collectAsState()
        val holds by App.downloader.holds.collectAsState()
        var queue by remember { mutableStateOf<List<QueueGroup>>(emptyList()) }
        var albums by remember { mutableStateOf<List<DownloadedAlbum>>(emptyList()) }
        var bytes by remember { mutableStateOf(0L) }
        // Read again as the queue moves and as downloads land or go, off the frame.
        LaunchedEffect(progress.pending, progress.completed, downloadedIds, sources) {
            val read = withContext(Dispatchers.IO) {
                Triple(queueGroups(sources), downloadedAlbums(sources), App.downloads.usedBytesEverywhere())
            }
            queue = read.first
            albums = read.second
            bytes = read.third
        }
        val sorted = remember(albums, sort) {
            when (sort) {
                DownloadSort.LARGEST -> albums.sortedByDescending { it.bytes }
                DownloadSort.RECENT -> albums.sortedByDescending { it.newestMs }
                DownloadSort.NAME -> albums.sortedBy { it.title.lowercase() }
            }
        }
        val severalServers = sources.count { it.supportsDownloads } > 1
        val unmet = sources.filter { it.supportsDownloads && !it.downloadFormatAvailable }
        val refused = sources.filter { holds[it.id] is Downloader.Hold.Refused && it !in unmet }
        val parked = sources.filter { holds[it.id] is Downloader.Hold.Parked && it !in unmet }
        val asked = queue.filter { !it.auto }

        PlayerTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                AppHeader(
                    onBack = { goBack() },
                    title = "Downloads",
                    rightAction = HeaderAction(AppIcons.Sort) { chooseSort() },
                )
                val nothingToShow = albums.isEmpty() && queue.isEmpty() && !progress.active &&
                    unmet.isEmpty() && refused.isEmpty() && parked.isEmpty()
                if (nothingToShow) {
                    EmptyState("Nothing downloaded yet.\nHold a song, album or artist and choose Download.")
                    return@Column
                }
                LibraryList(anchor = "downloads-overview") {
                    downloadProblems(this@DownloadsOverviewScreen, unmet, refused, parked, holds)
                    if (progress.active || paused) {
                        item(key = "status") {
                            val heldError = progress.error.takeIf { progress.currentSource == null }
                            TextRow(
                                title = progress.currentTitle ?: "Downloading",
                                subtitle = listOfNotNull(
                                    when {
                                        paused -> "Paused"
                                        heldError != null -> heldError
                                        else -> "Downloading"
                                    },
                                    progress.currentSource.takeIf { severalServers && !paused && heldError == null },
                                    "${"%,d".format(progress.pending)} left".takeIf { progress.pending > 1 },
                                ).joinToString(" · "),
                                onClick = { App.downloader.setUserPaused(!paused) },
                                trailing = { AppIcon(if (paused) AppIcons.PlayArrow else AppIcons.Pause, size = px(61)) },
                            )
                        }
                    }
                    if (queue.isNotEmpty()) {
                        item(key = "queue-label") { SectionLabel("Queue") }
                        items(queue, key = { "q-${it.source.id}-${it.auto}-${it.title}-${it.trackIds.first()}" }) { group ->
                            if (group.auto) {
                                // Its songs come back at the next top-up whatever is
                                // cancelled here: the mode is what decides them.
                                TextRow(
                                    title = group.title,
                                    subtitle = group.subtitle,
                                    onClick = { go { OfflineModeScreen(it, group.source.id) } },
                                )
                            } else {
                                TrackRow(
                                    title = group.title,
                                    subtitle = group.subtitle,
                                    coverArtId = group.coverArtId,
                                    coverSource = group.source.id,
                                    onClick = { go { QueuedGroupScreen(it, group.title, group.source.id, group.trackIds) } },
                                )
                            }
                        }
                        if (asked.isNotEmpty()) {
                            item(key = "clear-queue") {
                                TextRow(title = "Clear Queue", subtitle = "What you asked for; Auto-Download carries on") {
                                    confirmClear(asked)
                                }
                            }
                        }
                    }
                    item(key = "downloaded-label") {
                        val songs = albums.sumOf { it.trackIds.size }
                        SectionLabel("${"%,d".format(songs)} songs · ${formatGb(bytes)} of ${formatGb(limit - reserve)}")
                    }
                    val bySource = sorted.groupBy { it.source }
                    bySource.forEach { (source, list) ->
                        if (bySource.size > 1) item(key = "src-${source.id}") { SectionLabel(source.name) }
                        items(list, key = { "d-${source.id}-${it.albumId}-${it.title}" }) { album ->
                            TrackRow(
                                title = album.title,
                                subtitle = "${album.artist} · ${formatBytes(album.bytes)}",
                                coverArtId = album.coverArtId,
                                coverSource = source.id,
                                downloaded = true,
                                onClick = {
                                    go {
                                        DownloadedAlbumScreen(
                                            it,
                                            title = album.title,
                                            subtitle = album.artist,
                                            source = source,
                                            albumId = album.albumId.takeIf { source.id == active?.id },
                                            trackIds = album.trackIds,
                                            kept = album.kept,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun chooseSort() {
        navigateTo<DownloadSort?>(
            { DownloadSortScreen(it, sort) },
            resultCallback = { chosen -> if (chosen != null) sort = chosen },
        )
    }

    private fun confirmClear(asked: List<QueueGroup>) {
        navigateTo<Boolean>(
            {
                ConfirmScreen(
                    it,
                    title = "Clear Queue",
                    message = "Stops the downloads you asked for. What's already on the phone stays.",
                    confirmLabel = "Clear",
                )
            },
            resultCallback = { yes ->
                if (yes == true) asked.forEach { App.downloader.cancelQueued(it.source.id, it.trackIds) }
            },
        )
    }

    /** The queue by album, asked-for first, then each source's automatic lane as one row. */
    private suspend fun queueGroups(sources: List<MusicSource>): List<QueueGroup> {
        val byId = sources.associateBy { it.id }
        val picked = App.downloader.queued()
        val manual = picked.filter { it.manual }.map { it.entry }
            .groupBy { it.sourceId to (it.track?.albumId ?: it.trackId) }
            .mapNotNull { (key, entries) ->
                val source = byId[key.first] ?: return@mapNotNull null
                val track = entries.first().track
                QueueGroup(
                    source = source,
                    title = track?.album?.ifBlank { null } ?: track?.title ?: "Songs",
                    subtitle = listOfNotNull(
                        track?.primaryAlbumArtist(),
                        "${entries.size} ${if (entries.size == 1) "song" else "songs"} waiting",
                    ).joinToString(" · "),
                    coverArtId = track?.coverArtId,
                    trackIds = entries.map { it.trackId },
                    auto = false,
                )
            }
        val auto = picked.filter { !it.manual }.groupBy { it.entry.sourceId }.mapNotNull { (sourceId, entries) ->
            val source = byId[sourceId] ?: return@mapNotNull null
            QueueGroup(
                source = source,
                title = if (byId.values.count { it.supportsDownloads } > 1) "Auto-Download · ${source.name}" else "Auto-Download",
                subtitle = "${"%,d".format(entries.size)} songs waiting · Change in Auto-Download",
                coverArtId = null,
                trackIds = entries.map { it.entry.trackId },
                auto = true,
            )
        }
        return manual + auto
    }

    /** Every source's downloads by album, with their size and whether Auto-Download keeps them. */
    private suspend fun downloadedAlbums(sources: List<MusicSource>): List<DownloadedAlbum> {
        val files = App.downloadFilesBySource()
        return App.downloadsBySource().flatMap { (source, tracks) ->
            val fileOf = files[source.id].orEmpty().associateBy { it.trackId }
            val wanted: (String) -> Boolean = when (source.offlineMode) {
                OfflineMode.ALL -> { _ -> true }
                OfflineMode.FAVORITES -> App.downloader.autoKept(source.id)?.let { set -> { id: String -> id in set } } ?: { _ -> false }
                OfflineMode.MANUAL -> { _ -> false }
            }
            tracks.groupBy { it.albumId ?: it.id }.map { (_, songs) ->
                val first = songs.first()
                val rows = songs.mapNotNull { fileOf[it.id] }
                DownloadedAlbum(
                    source = source,
                    albumId = first.albumId,
                    title = first.album.ifBlank { first.title },
                    artist = first.primaryAlbumArtist(),
                    coverArtId = first.coverArtId,
                    trackIds = songs.map { it.id },
                    bytes = rows.sumOf { it.bytes },
                    newestMs = rows.maxOfOrNull { it.downloadedAtMs } ?: 0L,
                    kept = songs.all { wanted(it.id) },
                )
            }
        }
    }
}

/** A queued album's actions: put it first, or cancel it. */
class QueuedGroupScreen(
    sealed: SealedLightActivity,
    private val title: String,
    private val sourceId: String,
    private val trackIds: List<String>,
) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = title, subtitle = "${trackIds.size} waiting", headingRows = true) {
            ActionList {
                TextRow(title = "Download First") {
                    App.downloader.downloadFirst(sourceId, trackIds)
                    goBack()
                }
                TextRow(title = "Cancel") {
                    App.downloader.cancelQueued(sourceId, trackIds)
                    goBack()
                }
            }
        }
    }
}

/** A downloaded album's actions: open it (the browsed source's only), or remove what is on the phone. */
class DownloadedAlbumScreen(
    sealed: SealedLightActivity,
    private val title: String,
    private val subtitle: String,
    private val source: MusicSource,
    /** Null where it can't be opened: another source's album would resolve through the wrong server. */
    private val albumId: String?,
    private val trackIds: List<String>,
    private val kept: Boolean,
) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack() }, title = title, subtitle = subtitle, headingRows = true) {
            ActionList {
                if (albumId != null) {
                    TextRow(title = "Open Album") { go { AlbumDetailScreen(it, albumId) } }
                }
                if (kept) {
                    // Removed, Auto-Download would fetch it straight back.
                    TextRow(title = "Kept by Auto-Download", subtitle = "Change in Auto-Download") {
                        go { OfflineModeScreen(it, source.id) }
                    }
                } else {
                    TextRow(title = "Remove Download") {
                        App.scope.launch { App.downloader.removeFrom(source, trackIds) }
                        goBack()
                    }
                }
            }
        }
    }
}

/** The Downloads page's order. */
class DownloadSortScreen(sealed: SealedLightActivity, private val current: DownloadSort) : AmpScreen<DownloadSort?>(sealed) {
    @Composable
    override fun Content() {
        ListScreen(onBack = { goBack(null) }, title = "Sort by", subtitle = "Downloads", headingRows = true) {
            ScrollableList {
                items(DownloadSort.entries.toList()) { option ->
                    TextRow(title = option.label, checked = option == current) { goBack(option) }
                }
            }
        }
    }
}

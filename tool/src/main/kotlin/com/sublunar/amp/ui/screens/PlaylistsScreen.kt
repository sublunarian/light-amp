package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.sublunar.amp.App
import com.sublunar.amp.data.PlaylistView
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AutoScroll
import com.sublunar.amp.ui.components.CenteredNote
import com.sublunar.amp.ui.components.DRAG_HANDLE_PX
import com.sublunar.amp.ui.components.DropIndicatorLine
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.NOTICE_MS
import com.sublunar.amp.ui.components.NoticePill
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.ROW_H_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.Selections
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.dragReorderContainer
import com.sublunar.amp.ui.components.dragRowTarget
import com.sublunar.amp.ui.components.dropInsertIndex
import com.sublunar.amp.ui.components.placeBlock
import com.sublunar.amp.ui.components.rememberDragReorderState
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlaylistDetailScreen(
    sealed: SealedLightActivity,
    private val playlistId: String,
    private val playlistName: String,
) : AmpScreen<Unit>(sealed) {

    // Held on the screen instance (the SDK disposes the composition under a pushed
    // sheet), so local edits survive the track-options sheet without a reload.
    private val entries = mutableStateOf<List<PlaylistEntry>?>(null)

    // Up briefly after a write fails: the list has just gone back to how it was,
    // which otherwise looks like the edit never happened.
    private val saveFailed = mutableStateOf(false)

    // Edits are written one at a time in the order made (see [edit]). lastEdit is
    // the tail of that line. editEpoch moves on when a write fails, which tells the
    // edits queued behind it that the list they were made against is gone.
    private var lastEdit: Job? = null
    @Volatile private var editEpoch = 0

    // Whether [entries] is the server's whole playlist rather than a rebuild (see
    // PlaylistView). Removing and reordering address the server's copy by position,
    // so neither is offered on a rebuild. Rows another library holds are in [entries]
    // too, just not shown, so a playlist narrowed to one library is still whole.
    // Read at load, the same snapshot as the list.
    private val wholeList = mutableStateOf(true)

    // Whether the library is down to its downloads, kept live: a playlist left open
    // when Wi-Fi drops would otherwise keep offering edits that can only fail.
    private val offline = mutableStateOf(false)

    /**
     * Whether this playlist may be edited: online (an edit touches the network, which
     * offline modes forbid), and whole (a partial list can't be addressed by position).
     */
    private fun canEdit(): Boolean = wholeList.value && !offline.value

    // A playlist can hold a song twice, so rows use a synthetic key, not track.id.
    private var nextEntryKey = 0
    private fun newEntryKey(): String = "e${nextEntryKey++}"

    @Composable
    override fun Content() {
        LaunchedEffect(playlistId) {
            if (entries.value == null) {
                // Ticks left from an earlier visit name rows of a list read afresh here.
                Selections.of("playlist:$playlistId").clear()
                val view = App.playlists.playlistView(playlistId)
                wholeList.value = view.complete
                entries.value = entriesOf(view)
            }
        }
        // Collected for the life of the screen: the half of canEdit that can change
        // while the playlist sits open.
        LaunchedEffect(Unit) { App.offlineOnly.collect { offline.value = it } }

        val selection = rememberSelection("playlist:$playlistId")

        // The stored name, not the one this screen was opened with: the corner menu
        // can rename the playlist while this page is under it.
        val liveName = App.playlists.allPlaylists.collectAsState().value
            .firstOrNull { it.id == playlistId }?.name ?: playlistName

        LibrarySubPage {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    if (selection.active) {
                        SelectionHeader(
                            selection = selection,
                            // No delete at all where it could only fail.
                            onDelete = if (!canEdit()) null else {
                                {
                                    removeSongs(selection.selected)
                                    // Stay in edit mode, empty: pruning a playlist is
                                    // usually more than one pass.
                                    selection.begin()
                                }
                            },
                            onConfirm = {
                                openSelectionActions(
                                    selection.pick(shownEntries()) { it.key }.map { it.track },
                                    selection,
                                )
                            },
                        )
                    } else {
                        AppHeader(
                            onBack = { goBack() },
                            title = liveName,
                            // The playlist's own menu (a playlist has no order to
                            // choose), the same as a long-press on its row.
                            rightAction = HeaderAction(
                                AppIcons.MoreVert,
                                onLongClick = { go { SettingsScreen(it) } },
                            ) {
                                go { PlaylistActionsScreen(it, playlistId, liveName, fromDetail = true) }
                            },
                            fitTitle = true,
                        )
                    }
                    when (val list = entries.value) {
                        null -> CenteredNote("Loading…")
                        else -> {
                            val shown = remember(list) { list.filter { it.shown } }
                            when {
                                shown.isNotEmpty() -> TrackList(shown, selection)
                                list.isEmpty() || offline.value -> CenteredNote("Empty playlist")
                                else -> CenteredNote("No songs in this library")
                            }
                        }
                    }
                }
                if (saveFailed.value) NoticePill("Couldn't save")
            }
        }
    }

    /**
     * One playlist row: [key] is a synthetic per-row id, [track] what it holds. [shown]
     * is false for a song the chosen library doesn't hold, which stays in place for the
     * edits to work against but isn't listed.
     */
    private data class PlaylistEntry(val key: String, val track: Track, val shown: Boolean = true)

    private fun entriesOf(view: PlaylistView): List<PlaylistEntry> =
        view.all.mapIndexed { i, track -> PlaylistEntry(newEntryKey(), track, view.shown[i]) }

    private fun shownEntries(): List<PlaylistEntry> = entries.value.orEmpty().filter { it.shown }

    /** The tracks. Edit mode is one mode: the grab bars appear and rows become selectable together. */
    @Composable
    private fun TrackList(list: List<PlaylistEntry>, selection: SelectionState) {
        val editing = selection.active
        val drag = rememberDragReorderState<String>()
        val rowPx = with(LocalDensity.current) { px(ROW_H_PX).toPx() }
        val downloaded by App.library.downloadedTrackIds.collectAsState()
        val headerCount = if (editing) 0 else 2
        val listState = rememberListAnchor("playlist:$playlistId", headerCount)
        val orderedKeys = remember(list) { list.map { it.key } }
        // Where the drag would land right now; null beforeKey means "at the very bottom".
        val dropTarget: DropTarget? = drag.draggingIndex?.let { from ->
            val target = dragRowTarget(list.size, from, drag.dragOffsetY, rowPx)
            val movingIndices = drag.draggingKeys.mapNotNull { orderedKeys.indexOf(it).takeIf { i -> i >= 0 } }.toSet()
            // Keyed on the row-granular target, not dragOffsetY, so it only recomputes
            // when the drag crosses into a new row.
            remember(list, from, drag.draggingKeys, target) {
                val insertAt = dropInsertIndex(list.size, from, movingIndices, target)
                val remaining = list.filterIndexed { i, _ -> i !in movingIndices }
                DropTarget(remaining.getOrNull(insertAt)?.key)
            }
        }

        drag.AutoScroll(listState, rowPx)

        Box(
            Modifier
                .fillMaxSize()
                .dragReorderContainer(
                    state = drag,
                    enabled = editing && canEdit(),
                    restartKey = list,
                    orderedKeys = orderedKeys,
                    rowPx = rowPx,
                    groupOf = { hitKey ->
                        if (hitKey in selection.selected && selection.count > 1) selection.selected else setOf(hitKey)
                    },
                    onDrop = { movingIndices, insertAt -> reorderGroup(movingIndices, insertAt) },
                ),
        ) {
            ScrollableList(state = listState) {
                if (!editing) {
                    item {
                        PlayAllRow(AppIcons.Shuffle, "Shuffle") {
                            App.playback.playQueue(shuffled(list.map { it.track }), 0)
                            go { NowPlayingScreen(it) }
                        }
                    }
                    item { PlayAllRow(AppIcons.Dehaze, "Edit") { selection.begin() } }
                }
                itemsIndexed(list, key = { _, e -> e.key }) { index, entry ->
                    val track = entry.track
                    val isDragging = entry.key in drag.draggingKeys
                    // Clear coords on dispose: LazyColumn recycles nodes, so a stale
                    // entry would report the next occupant's position.
                    DisposableEffect(entry.key) {
                        onDispose { drag.clear(entry.key) }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        TrackRow(
                            title = track.title,
                            subtitle = track.artist,
                            coverArtId = track.coverArtId,
                            modifier = Modifier
                                .onGloballyPositioned { drag.rowCoords[entry.key] = it }
                                // Real row goes invisible; DragOverlay draws the floating stand-in.
                                .alpha(if (isDragging) 0f else 1f),
                            onClick = {
                                if (editing) {
                                    selection.toggle(entry.key)
                                } else {
                                    App.playback.playQueue(list.map { it.track }, index)
                                    go { NowPlayingScreen(it) }
                                }
                            },
                            onLongClick = {
                                if (!editing) {
                                    go {
                                        TrackActionsScreen(
                                            it, track.id,
                                            onSelect = { selection.begin(entry.key) },
                                            // Absent where it could only fail, as the delete icon.
                                            onRemoveFromPlaylist =
                                                if (canEdit()) ({ removeSongs(setOf(entry.key)) }) else null,
                                            known = track,
                                        )
                                    }
                                }
                            },
                            downloaded = track.id in downloaded,
                            selected = if (editing) entry.key in selection.selected else null,
                            trailing = if (editing && canEdit()) {
                                {
                                    AppIcon(
                                        AppIcons.Dehaze,
                                        size = px(DRAG_HANDLE_PX),
                                        modifier = Modifier.onGloballyPositioned { drag.iconCoords[entry.key] = it },
                                    )
                                }
                            } else {
                                null
                            },
                        )
                        if (dropTarget?.beforeKey == entry.key) DropIndicatorLine(Modifier.align(Alignment.TopStart))
                    }
                }
                if (dropTarget != null && dropTarget.beforeKey == null) {
                    item { DropIndicatorLine() }
                }
            }
            if (drag.draggingIndex != null) {
                DragOverlay(list, drag.draggingKeys, drag.dragStartTops, drag.fingerOffsetY, selection, downloaded)
            }
        }
    }

    /**
     * Floating copy of the row(s) being dragged, drawn outside the LazyColumn so it
     * survives the real row being recycled by auto-scroll. [fingerOffsetY] excludes
     * auto-scroll's own contribution, since the overlay's position shouldn't move twice.
     */
    @Composable
    private fun BoxScope.DragOverlay(
        list: List<PlaylistEntry>,
        draggingKeys: Set<String>,
        dragStartTops: Map<String, Float>,
        fingerOffsetY: Float,
        selection: SelectionState,
        downloaded: Set<String>,
    ) {
        list.forEach { entry ->
            if (entry.key !in draggingKeys) return@forEach
            val top = dragStartTops[entry.key] ?: return@forEach
            TrackRow(
                title = entry.track.title,
                subtitle = entry.track.artist,
                coverArtId = entry.track.coverArtId,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .graphicsLayer { translationY = top + fingerOffsetY }
                    .padding(start = px(LIST_EDGE_PX), end = px(SCROLLBAR_LANE_PX)),
                onClick = {},
                downloaded = entry.track.id in downloaded,
                selected = entry.key in selection.selected,
                trailing = { Spacer(Modifier.width(px(DRAG_HANDLE_PX))) },
            )
        }
    }

    private data class DropTarget(val beforeKey: String?)

    /**
     * Show an edit now and write it behind the screen's back.
     *
     * [after] goes on screen at once. [write] waits its turn behind every earlier
     * edit, which keeps positions honest: each edit is worked out against the list
     * the edits before it leave, which is the list the server holds once they land.
     *
     * A failed write breaks that chain, so the edits queued behind it are dropped
     * unsent ([editEpoch]). The screen goes back to the server's own copy where it
     * can be read (a Plex edit is many requests and can stop partway), otherwise to
     * [entries] as they were before the failed edit.
     */
    private fun edit(after: List<PlaylistEntry>, write: suspend () -> Boolean) {
        val before = entries.value ?: return
        entries.value = after
        val previous = lastEdit
        val epoch = editEpoch
        lastEdit = App.scope.launch {
            previous?.join()
            if (epoch != editEpoch) return@launch
            if (write()) {
                // The playlists page's track counts, not this write's own success.
                App.playlists.refreshPlaylists()
                return@launch
            }
            editEpoch++
            val view = App.playlists.playlistView(playlistId)
            if (view.complete && view.all.map { it.id } != before.map { it.track.id }) {
                // New rows, new keys: what was ticked no longer names anything.
                Selections.of("playlist:$playlistId").let { if (it.active) it.begin() }
                entries.value = entriesOf(view)
            } else {
                entries.value = before
            }
            saveFailed.value = true
            delay(NOTICE_MS)
            saveFailed.value = false
        }
    }

    /**
     * Remove rows by their keys, not their songs: a playlist can hold a song twice,
     * and ticking one means that one. The server is told positions in the whole
     * playlist, shown or not (see [MusicServer.removeFromPlaylistAt]).
     */
    private fun removeSongs(keys: Set<String>) {
        // Positions into [entries] name other songs unless it is the whole playlist.
        if (!canEdit()) return
        val current = entries.value ?: return
        // Only rows on screen: another library's songs are never the user's to remove here.
        val doomed = current.filter { it.shown && it.key in keys }.mapTo(HashSet()) { it.key }
        // Each position with the song seen there, so a playlist edited elsewhere in
        // the meantime is refused rather than mis-pruned.
        val at = current.indices.filter { current[it].key in doomed }.associateWith { current[it].track.id }
        if (at.isEmpty()) return
        edit(current.filterNot { it.key in doomed }) {
            App.playlists.removeFromPlaylistAt(playlistId, at)
        }
    }

    /**
     * Move the shown rows at [indices], in their order, as a block to [insertAt] among
     * the rest shown. Rows that aren't shown keep their places (see [placeBlock]).
     */
    private fun reorderGroup(indices: Set<Int>, insertAt: Int) {
        // A partial order would be an instruction to lose everything it omits.
        if (!canEdit()) return
        val current = entries.value ?: return
        val shown = current.filter { it.shown }
        val moving = shown.filterIndexed { i, _ -> i in indices }.mapTo(HashSet()) { it.key }
        if (moving.isEmpty() || moving.size == shown.size) return
        val remaining = shown.filterNot { it.key in moving }
        val before = remaining.getOrNull(insertAt.coerceIn(0, remaining.size))?.key
        val newList = placeBlock(current, { it.key }, moving, before, remaining.last().key)
        if (newList == current) return
        edit(newList) { App.playlists.reorderPlaylist(playlistId, newList.map { it.track.id }) }
    }
}

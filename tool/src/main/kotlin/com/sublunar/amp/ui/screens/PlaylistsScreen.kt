package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberDragReorderState
import com.sublunar.amp.ui.components.dragReorderContainer
import com.sublunar.amp.ui.components.dragRowTarget
import com.sublunar.amp.ui.components.dropInsertIndex
import com.sublunar.amp.ui.components.AutoScroll
import com.sublunar.amp.ui.components.DropIndicatorLine
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.AppArtwork
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.LibraryList
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.PlayAllRow
import com.sublunar.amp.ui.components.SelectionArtwork
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.Selections
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.pxSp
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_SUB_LINE_PX
import com.sublunar.amp.ui.components.ROW_SUB_PX
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Long enough to read, not just to register as a flicker.
private const val ERROR_FLASH_MS = 2500L

class PlaylistDetailScreen(
    sealed: SealedLightActivity,
    private val playlistId: String,
    private val playlistName: String,
) : SimpleLightScreen<Unit>(sealed) {

    // Held on the screen instance so local edits (remove / reorder) survive the
    // push/pop of the track-options sheet without a fresh server round-trip.
    private val entries = mutableStateOf<List<PlaylistEntry>?>(null)

    // Up briefly after a write fails -- the list has just gone back to how it was,
    // and without this that looks like the edit simply never happened.
    private val saveFailed = mutableStateOf(false)

    // Edits are shown at once and written behind the screen's back, one at a time
    // in the order they were made -- see [edit]. lastEdit is the tail of that line;
    // editEpoch moves on when a write fails, which is how the edits queued behind
    // it learn that the list they were made against is gone.
    private var lastEdit: Job? = null
    @Volatile private var editEpoch = 0

    // Whether what's on screen is the whole playlist -- see PlaylistView. Removing and
    // reordering both address the server's copy by position, so neither is offered when
    // this list is only part of it: the indices would point at other songs entirely.
    // Read at load and not revisited, which is the same snapshot the list itself is.
    private val wholeList = mutableStateOf(true)

    // Whether the library is down to its downloads right now, unlike wholeList kept live:
    // a playlist opened on Wi-Fi and still open when it drops would otherwise keep
    // offering edits that can only fail.
    private val offline = mutableStateOf(false)

    /**
     * Whether this playlist may be edited at all.
     *
     * Both halves have to hold. Offline there is nothing to edit *against*: the write
     * would fail, and under Wi-Fi Only on metered data it shouldn't even be attempted --
     * a playlist edit is the app touching the network in a mode where the user asked it
     * not to. And a partial list can't be addressed by position whatever the connection.
     */
    private fun canEdit(): Boolean = wholeList.value && !offline.value

    // Duplicate songs can appear in a playlist, so rows use a synthetic key, not track.id.
    private var nextEntryKey = 0
    private fun newEntryKey(): String = "e${nextEntryKey++}"

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        LaunchedEffect(playlistId) {
            if (entries.value == null) {
                val view = App.library.playlistView(playlistId)
                wholeList.value = view.complete
                entries.value = view.tracks.map { PlaylistEntry(newEntryKey(), it) }
            }
        }
        // Collected for the life of the screen, not read once: this is the half of
        // canEdit that can change while the playlist sits open.
        LaunchedEffect(Unit) { App.offlineOnly.collect { offline.value = it } }

        val selection = rememberSelection("playlist:$playlistId")

        // The stored name, not the one this screen was opened with: the corner
        // menu can rename the playlist while this page is under it, and the
        // constructor's copy would keep saying the old name until reopened.
        val liveName = App.library.playlists.collectAsState().value
            .firstOrNull { it.id == playlistId }?.name ?: playlistName

        LibrarySubPage(LibraryPage.PLAYLIST, liveName) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    if (selection.active) {
                        SelectionHeader(
                            selection = selection,
                            // No delete offered at all when this isn't the whole
                            // playlist: an offer that could only ever fail is worse
                            // than the icon not being there.
                            onDelete = if (!canEdit()) null else {
                                {
                                    removeSongs(selection.selected)
                                    // Stay in edit mode with an empty selection: pruning a
                                    // playlist is usually more than one pass, and the X is
                                    // right there when it isn't.
                                    selection.begin()
                                }
                            },
                            onConfirm = {
                                openSelectionActions(
                                    selection.pick(entries.value.orEmpty()) { it.key }.map { it.track },
                                    selection,
                                )
                            },
                        )
                    } else {
                        AppHeader(
                            onBack = { goBack() },
                            title = liveName,
                            // The playlist's own menu, not the library's sort: a
                            // playlist plays in its own order, so the corner that
                            // offered a dead sort now offers its verbs — play, rename,
                            // delete. Same menu as a long-press on its row; see the
                            // album page, which made the same trade.
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
                        null -> Centered("Loading…")
                        else -> if (list.isEmpty()) Centered("Empty playlist") else TrackList(list, selection)
                    }
                }
                if (saveFailed.value) SaveFailedNotice()
            }
        }
    }

    /** Dead center and above the list: the rows themselves have just gone back to how they were. */
    @Composable
    private fun BoxScope.SaveFailedNotice() {
        AppText(
            "Couldn't save",
            pxSp(ROW_TITLE_PX),
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(px(24)))
                // The page's own background/content pair, so it follows the phone's
                // theme and its invertColors setting.
                .background(LightThemeTokens.colors.background)
                .padding(horizontal = px(44), vertical = px(28)),
        )
    }

    /** One playlist row: [key] is a synthetic per-row id (see [entries]); [track] is what it shows. */
    private data class PlaylistEntry(val key: String, val track: Track)

    /**
     * Edit mode is one mode, not two: the grab bars appear and rows become
     * selectable together. Both are things you do to the playlist rather than
     * with it, and splitting them would mean two toggles competing for the same
     * corner of a header that has no spare room.
     */
    @Composable
    private fun TrackList(list: List<PlaylistEntry>, selection: SelectionState) {
        val editing = selection.active
        val drag = rememberDragReorderState<String>()
        val rowPx = with(LocalDensity.current) { px(160).toPx() }
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
            LibraryList(
                anchor = "playlist:$playlistId",
                headerCount = headerCount,
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
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
                    // entry would silently report the next occupant's position.
                    DisposableEffect(entry.key) {
                        onDispose { drag.clear(entry.key) }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(px(160))
                                .onGloballyPositioned { drag.rowCoords[entry.key] = it }
                                // Real row goes invisible; DragOverlay draws the floating stand-in.
                                .alpha(if (isDragging) 0f else 1f)
                                .rowClickable(
                                    onClick = {
                                        if (editing) {
                                            selection.toggle(entry.key)
                                        } else {
                                            App.playback.playQueue(list.map { it.track }, index)
                                            go { NowPlayingScreen(it) }
                                        }
                                    },
                                    onLongClick = {
                                        if (editing) return@rowClickable
                                        go {
                                            TrackActionsScreen(
                                                it, track.id,
                                                onSelect = { selection.begin(entry.key) },
                                                // Absent, not failing: see the delete icon above.
                                                onRemoveFromPlaylist =
                                                    if (canEdit()) ({ removeSong(index) }) else null,
                                            )
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (editing) {
                                SelectionArtwork(track.coverArtId, entry.key in selection.selected)
                            } else {
                                AppArtwork(track.coverArtId, size = px(128))
                            }
                            Spacer(Modifier.width(px(ROW_GAP_PX)))
                            Column(Modifier.weight(1f)) {
                                AppText(track.title, pxSp(ROW_TITLE_PX), lineHeight = pxSp(ROW_TITLE_LINE_PX), maxLines = 1)
                                AppText(track.artist, pxSp(ROW_SUB_PX), lineHeight = pxSp(ROW_SUB_LINE_PX), dim = true, maxLines = 1)
                            }
                            if (editing && canEdit()) {
                                // Drag handle for reordering within the playlist.
                                AppIcon(
                                    AppIcons.Dehaze,
                                    size = px(51),
                                    modifier = Modifier.onGloballyPositioned { drag.iconCoords[entry.key] = it },
                                )
                            }
                        }
                        if (dropTarget?.beforeKey == entry.key) DropIndicatorLine(Modifier.align(Alignment.TopStart))
                    }
                }
                if (dropTarget != null && dropTarget.beforeKey == null) {
                    item { DropIndicatorLine() }
                }
            }
            if (drag.draggingIndex != null) {
                DragOverlay(list, drag.draggingKeys, drag.dragStartTops, drag.fingerOffsetY, selection)
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
    ) {
        list.forEach { entry ->
            if (entry.key !in draggingKeys) return@forEach
            val top = dragStartTops[entry.key] ?: return@forEach
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(px(160))
                    .align(Alignment.TopStart)
                    .graphicsLayer { translationY = top + fingerOffsetY }
                    .padding(start = px(LIST_EDGE_PX), end = px(SCROLLBAR_LANE_PX)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionArtwork(entry.track.coverArtId, entry.key in selection.selected)
                Spacer(Modifier.width(px(ROW_GAP_PX)))
                Column(Modifier.weight(1f)) {
                    AppText(entry.track.title, pxSp(ROW_TITLE_PX), lineHeight = pxSp(ROW_TITLE_LINE_PX), maxLines = 1)
                    AppText(entry.track.artist, pxSp(ROW_SUB_PX), lineHeight = pxSp(ROW_SUB_LINE_PX), dim = true, maxLines = 1)
                }
                Spacer(Modifier.width(px(51)))
            }
        }
    }

    private data class DropTarget(val beforeKey: String?)

    @Composable
    private fun Centered(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text, pxSp(LightType.DETAIL_PX), dim = true)
        }
    }

    /**
     * Show an edit now and write it behind the screen's back.
     *
     * [after] goes on screen at once: a row that snaps back to where it was and
     * jumps a moment later is a worse answer to a drop than the drop itself, and
     * on most servers the write is one quick request. [write] then takes its turn
     * behind every edit made before it, which is what keeps positions honest --
     * each edit is worked out against the list the edits before it leave, and that
     * is exactly the list the server holds once they have landed, so an index into
     * one is an index into the other.
     *
     * A write that fails breaks that chain of reasoning for everything queued
     * behind it, so they are dropped unsent ([editEpoch]) rather than aimed at a
     * list that never came to be. The screen goes back to the server's own copy
     * where it can be read -- a Plex edit is many requests and can stop partway --
     * and otherwise to [entries] as they stood before the edit that failed.
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
                App.library.refreshPlaylists()
                return@launch
            }
            editEpoch++
            val view = App.library.playlistView(playlistId)
            if (view.complete && view.tracks.map { it.id } != before.map { it.track.id }) {
                // New rows, new keys: what was ticked no longer names anything.
                Selections.of("playlist:$playlistId").let { if (it.active) it.begin() }
                entries.value = view.tracks.map { PlaylistEntry(newEntryKey(), it) }
            } else {
                entries.value = before
            }
            saveFailed.value = true
            delay(ERROR_FLASH_MS)
            saveFailed.value = false
        }
    }

    private fun removeSong(index: Int) {
        val entry = entries.value?.getOrNull(index) ?: return
        removeSongs(setOf(entry.key))
    }

    /**
     * Remove rows by their keys, not their songs: a playlist can hold a song twice,
     * and ticking one of them means that one. The server is told positions, all of
     * them read against the list as it stands now -- see [MusicServer.removeFromPlaylistAt].
     */
    private fun removeSongs(keys: Set<String>) {
        // The indices below are into what's shown; if that isn't the whole playlist
        // they name different songs on the server. See [canEdit].
        if (!canEdit()) return
        val current = entries.value ?: return
        // Each position with the song seen there, so a playlist that has been edited
        // from somewhere else in the meantime is refused rather than mis-pruned.
        val at = current.indices.filter { current[it].key in keys }.associateWith { current[it].track.id }
        if (at.isEmpty()) return
        edit(current.filterNot { it.key in keys }) {
            App.library.removeFromPlaylistAt(playlistId, at)
        }
    }

    /**
     * Move the rows at [indices] as a block to position [insertAt] among the rest: pull
     * them out in their current relative order, then reinsert them there. For a lone
     * dragged row this is the familiar single-row reorder; for a multi-row selection the
     * whole set rides along together, so moving one selected row moves them all.
     */
    private fun reorderGroup(indices: Set<Int>, insertAt: Int) {
        // A partial order would be an instruction to lose everything it omits.
        if (!canEdit()) return
        val current = entries.value ?: return
        val moving = current.filterIndexed { i, _ -> i in indices }
        if (moving.isEmpty() || moving.size == current.size) return
        val remaining = current.filterIndexed { i, _ -> i !in indices }
        val newList = remaining.toMutableList().apply { addAll(insertAt.coerceIn(0, remaining.size), moving) }
        if (newList == current) return
        edit(newList) { App.library.reorderPlaylist(playlistId, newList.map { it.track.id }) }
    }

}

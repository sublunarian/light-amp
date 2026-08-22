package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
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
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.n
import com.sublunar.amp.ui.nSp
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// A thicker bar than the Now Playing tab underline (3px): it has to read at a
// glance across the full row width while a finger is on top of the handle.
private const val DROP_LINE_HEIGHT_PX = 9

// Faint enough that the check bubble reads clearly on top of it, but present
// enough that an album cover is still recognizable behind the handful of
// tracks it applies to — the whole point is telling albums apart at a glance.
private const val EDIT_ARTWORK_ALPHA = 0.55f

class PlaylistDetailScreen(
    sealed: SealedLightActivity,
    private val playlistId: String,
    private val playlistName: String,
) : SimpleLightScreen<Unit>(sealed) {

    // Held on the screen instance so local edits (remove / reorder) survive the
    // push/pop of the track-options sheet without a fresh server round-trip.
    private val entries = mutableStateOf<List<PlaylistEntry>?>(null)

    // A playlist can contain the same song twice on purpose (see
    // LocalPlaylists.add), so selection/drag/reorder can't key off track.id --
    // two rows would select and move as one. Each row gets its own synthetic
    // key instead, assigned once on load and carried through local edits.
    private var nextEntryKey = 0
    private fun newEntryKey(): String = "e${nextEntryKey++}"

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        LaunchedEffect(playlistId) {
            if (entries.value == null) {
                entries.value = App.library.playlistTracks(playlistId).map { PlaylistEntry(newEntryKey(), it) }
            }
        }

        val selection = rememberSelection("playlist:$playlistId")

        // The stored name, not the one this screen was opened with: the corner
        // menu can rename the playlist while this page is under it, and the
        // constructor's copy would keep saying the old name until reopened.
        val liveName = App.library.playlists.collectAsState().value
            .firstOrNull { it.id == playlistId }?.name ?: playlistName

        LibrarySubPage(LibraryPage.PLAYLIST, liveName) {
            if (selection.active) {
                SelectionHeader(
                    selection = selection,
                    onDelete = {
                        removeSongs(selection.selected)
                        // Stay in edit mode with an empty selection: pruning a
                        // playlist is usually more than one pass, and the X is
                        // right there when it isn't.
                        selection.begin()
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
    }

    /**
     * Edit mode is one mode, not two: the grab bars appear and rows become
     * selectable together. Both are things you do to the playlist rather than
     * with it, and splitting them would mean two toggles competing for the same
     * corner of a header that has no spare room.
     */
    /** One playlist row: [key] is a synthetic per-row id (see [entries]); [track] is what it shows. */
    private data class PlaylistEntry(val key: String, val track: Track)

    @Composable
    private fun TrackList(list: List<PlaylistEntry>, selection: SelectionState) {
        val editing = selection.active
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffsetY by remember { mutableStateOf(0f) }
        // Normally just the dragged row, but grabbing the handle of a row that's
        // part of a multi-row selection carries the whole selection along with it.
        var draggingKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
        val rowPx = with(LocalDensity.current) { px(160).toPx() }
        // Where the drag would land right now, so the line can track a finger
        // that hasn't lifted yet — null beforeKey means "at the very bottom".
        val dropTarget: DropTarget? = draggingIndex?.let { from ->
            DropTarget(dropAnchor(list, from, draggingKeys, dragRowTarget(list, from, dragOffsetY, rowPx)))
        }

        LibraryList(
            anchor = "playlist:$playlistId",
            headerCount = if (editing) 0 else 2,
                modifier = Modifier.fillMaxSize(),
            ) {
            if (!editing) {
                item {
                    PlayAllRow(AppIcons.Shuffle, "Shuffle") {
                        App.playback.playQueue(shuffled(list.map { it.track }), 0)
                        go { NowPlayingScreen(it) }
                    }
                }
                // Same glyph as the handles it reveals, so the row says what it does.
                item { PlayAllRow(AppIcons.Dehaze, "Edit") { selection.begin() } }
            }
            itemsIndexed(list, key = { _, e -> e.key }) { index, entry ->
                val track = entry.track
                val isDragging = entry.key in draggingKeys
                Column(Modifier.fillMaxWidth()) {
                    if (dropTarget?.beforeKey == entry.key) DropIndicatorLine()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(px(160))
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
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
                                            onRemoveFromPlaylist = { removeSong(index) },
                                        )
                                    }
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (editing) {
                            val checked = entry.key in selection.selected
                            Box(Modifier.size(px(128)), contentAlignment = Alignment.Center) {
                                // Dimmed cover behind the bubble, so a run of same-artist
                                // rows still reads as "this many albums" while selecting.
                                AppArtwork(
                                    track.coverArtId,
                                    size = px(128),
                                    modifier = Modifier.alpha(EDIT_ARTWORK_ALPHA),
                                )
                                AppIcon(
                                    if (checked) AppIcons.Selected else AppIcons.Unselected,
                                    size = n(26),
                                    tint = if (checked) {
                                        LightThemeTokens.colors.content
                                    } else {
                                        LightThemeTokens.colors.contentSecondary
                                    },
                                )
                            }
                        } else {
                            AppArtwork(track.coverArtId, size = px(128))
                        }
                        Spacer(Modifier.width(n(15)))
                        Column(Modifier.weight(1f)) {
                            AppText(track.title, nSp(18), lineHeight = nSp(22), maxLines = 1)
                            AppText(track.artist, nSp(15), lineHeight = nSp(19), dim = true, maxLines = 1)
                        }
                        // Drag the handle to reorder the song within the playlist.
                        if (editing) AppIcon(
                            AppIcons.Dehaze,
                            size = n(20),
                            modifier = Modifier.pointerInput(list.size) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingIndex = index
                                        dragOffsetY = 0f
                                        draggingKeys =
                                            if (entry.key in selection.selected && selection.count > 1) {
                                                selection.selected
                                            } else {
                                                setOf(entry.key)
                                            }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffsetY += amount.y
                                    },
                                    onDragEnd = {
                                        val from = draggingIndex
                                        if (from != null) {
                                            val target = dragRowTarget(list, from, dragOffsetY, rowPx)
                                            reorderGroup(dropAnchor(list, from, draggingKeys, target), draggingKeys)
                                        }
                                        draggingIndex = null
                                        dragOffsetY = 0f
                                        draggingKeys = emptySet()
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        dragOffsetY = 0f
                                        draggingKeys = emptySet()
                                    },
                                )
                            },
                        )
                    }
                }
            }
            if (dropTarget != null && dropTarget.beforeKey == null) {
                item { DropIndicatorLine() }
            }
        }
    }

    private data class DropTarget(val beforeKey: String?)

    /** Where row [from] would land right now, clamped to the list's bounds. */
    private fun dragRowTarget(list: List<PlaylistEntry>, from: Int, dragOffsetY: Float, rowPx: Float): Int =
        (from + (dragOffsetY / rowPx).roundToInt()).coerceIn(0, list.lastIndex)

    /**
     * The row the dragged block would land in front of right now, or null
     * meaning "at the very end". [target] is worked out as if only the grabbed
     * row ([from]) were moving — the same math a solo drag has always used —
     * so a multi-row selection tracks the finger exactly like a single row
     * would, instead of running out of room early because the rest of the
     * selection shrinks the pool of remaining rows to land among. The other
     * selected rows just ride along: from that anchor point we walk forward to
     * the nearest row that isn't itself part of the drag, since the block
     * can't be inserted in front of a row that's also about to move.
     *
     * Used for both the preview line and the actual drop ([reorderGroup]), so
     * the two can never disagree about where the drag will land.
     */
    private fun dropAnchor(list: List<PlaylistEntry>, from: Int, keys: Set<String>, target: Int): String? {
        val soloRemaining = list.filterIndexed { i, _ -> i != from }
        val anchorKey = soloRemaining.getOrNull(target.coerceIn(0, soloRemaining.size))?.key
        val anchorIndex = anchorKey?.let { key -> list.indexOfFirst { it.key == key } } ?: list.size
        return list.drop(anchorIndex).firstOrNull { it.key !in keys }?.key
    }

    @Composable
    private fun DropIndicatorLine() {
        Box(
            Modifier
                .fillMaxWidth()
                .height(px(DROP_LINE_HEIGHT_PX))
                .background(LightThemeTokens.colors.content),
        )
    }

    @Composable
    private fun Centered(text: String) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text, nSp(16), dim = true)
        }
    }

    private fun removeSong(index: Int) {
        val current = entries.value ?: return
        if (index !in current.indices) return
        entries.value = current.toMutableList().apply { removeAt(index) }
        App.scope.launch {
            App.library.removeFromPlaylistAt(playlistId, index)
            App.library.refreshPlaylists()
        }
    }

    /**
     * Remove several at once by rewriting the playlist with the survivors: the
     * per-index endpoint would shift every index behind each removal, and one
     * request can't half-apply.
     */
    private fun removeSongs(keys: Set<String>) {
        val current = entries.value ?: return
        if (keys.isEmpty()) return
        val remaining = current.filterNot { it.key in keys }
        if (remaining.size == current.size) return
        entries.value = remaining
        App.scope.launch {
            App.library.reorderPlaylist(playlistId, remaining.map { it.track.id })
            App.library.refreshPlaylists()
        }
    }

    /**
     * Move [keys] as a block to just in front of [beforeKey] (or the very end,
     * when null): pull them out in their current relative order, then reinsert
     * them there. For a lone dragged row this is the familiar single-row
     * reorder; for a multi-row selection the whole set rides along together,
     * so moving one selected row moves them all.
     */
    private fun reorderGroup(beforeKey: String?, keys: Set<String>) {
        val current = entries.value ?: return
        if (keys.isEmpty()) return
        val moving = current.filter { it.key in keys }
        if (moving.isEmpty() || moving.size == current.size) return
        val remaining = current.filterNot { it.key in keys }
        val insertAt = beforeKey?.let { key -> remaining.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }
            ?: remaining.size
        val newList = remaining.toMutableList().apply { addAll(insertAt, moving) }
        if (newList == current) return
        entries.value = newList
        App.scope.launch {
            App.library.reorderPlaylist(playlistId, newList.map { it.track.id })
            App.library.refreshPlaylists()
        }
    }
}

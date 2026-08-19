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
private const val EDIT_ARTWORK_ALPHA = 0.35f

class PlaylistDetailScreen(
    sealed: SealedLightActivity,
    private val playlistId: String,
    private val playlistName: String,
) : SimpleLightScreen<Unit>(sealed) {

    // Held on the screen instance so local edits (remove / reorder) survive the
    // push/pop of the track-options sheet without a fresh server round-trip.
    private val tracks = mutableStateOf<List<Track>?>(null)

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        LaunchedEffect(playlistId) {
            if (tracks.value == null) {
                tracks.value = App.library.playlistTracks(playlistId)
            }
        }

        val selection = rememberSelection("playlist:$playlistId")

        LibrarySubPage(LibraryPage.PLAYLIST, playlistName) {
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
                            selection.pick(tracks.value.orEmpty()) { it.id },
                            selection,
                        )
                    },
                )
            } else {
                AppHeader(
                    onBack = { goBack() },
                    title = playlistName,
                    rightAction = libraryCornerAction(LibraryPage.PLAYLIST, playlistName),
                    fitTitle = true,
                )
            }
            when (val list = tracks.value) {
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
    @Composable
    private fun TrackList(list: List<Track>, selection: SelectionState) {
        val editing = selection.active
        var draggingIndex by remember { mutableStateOf<Int?>(null) }
        var dragOffsetY by remember { mutableStateOf(0f) }
        // Normally just the dragged row, but grabbing the handle of a row that's
        // part of a multi-row selection carries the whole selection along with it.
        var draggingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        val rowPx = with(LocalDensity.current) { px(160).toPx() }
        // Where the drag would land right now, so the line can track a finger
        // that hasn't lifted yet — null beforeId means "at the very bottom".
        val dropTarget: DropTarget? = draggingIndex?.let { from ->
            dropTargetFor(list, draggingIds, dragRowTarget(list, from, dragOffsetY, rowPx))
        }

        LibraryList(
            anchor = "playlist:$playlistId",
            headerCount = if (editing) 0 else 2,
                modifier = Modifier.fillMaxSize(),
            ) {
            if (!editing) {
                item {
                    PlayAllRow(AppIcons.Shuffle, "Shuffle") {
                        App.playback.playQueue(shuffled(list), 0)
                        go { NowPlayingScreen(it) }
                    }
                }
                // Same glyph as the handles it reveals, so the row says what it does.
                item { PlayAllRow(AppIcons.Dehaze, "Edit") { selection.begin() } }
            }
            itemsIndexed(list, key = { i, t -> "$i-${t.id}" }) { index, track ->
                val isDragging = track.id in draggingIds
                Column(Modifier.fillMaxWidth()) {
                    if (dropTarget?.beforeId == track.id) DropIndicatorLine()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(px(160))
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                            .rowClickable(
                                onClick = {
                                    if (editing) {
                                        selection.toggle(track.id)
                                    } else {
                                        App.playback.playQueue(list, index)
                                        go { NowPlayingScreen(it) }
                                    }
                                },
                                onLongClick = {
                                    if (editing) return@rowClickable
                                    go {
                                        TrackActionsScreen(
                                            it, track.id,
                                            onSelect = { selection.begin(track.id) },
                                            onRemoveFromPlaylist = { removeSong(index) },
                                        )
                                    }
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (editing) {
                            val checked = track.id in selection.selected
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
                                        draggingIds =
                                            if (track.id in selection.selected && selection.count > 1) {
                                                selection.selected
                                            } else {
                                                setOf(track.id)
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
                                            if (target != from) reorderGroup(target, draggingIds)
                                        }
                                        draggingIndex = null
                                        dragOffsetY = 0f
                                        draggingIds = emptySet()
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        dragOffsetY = 0f
                                        draggingIds = emptySet()
                                    },
                                )
                            },
                        )
                    }
                }
            }
            if (dropTarget != null && dropTarget.beforeId == null) {
                item { DropIndicatorLine() }
            }
        }
    }

    private data class DropTarget(val beforeId: String?)

    /** Where row [from] would land right now, clamped to the list's bounds. */
    private fun dragRowTarget(list: List<Track>, from: Int, dragOffsetY: Float, rowPx: Float): Int =
        (from + (dragOffsetY / rowPx).roundToInt()).coerceIn(0, list.lastIndex)

    /**
     * The remaining (non-moving) row that [target] would land in front of, or a
     * null id meaning "at the very end" — the same split [reorderGroup] uses to
     * insert the block, kept here so the preview line never disagrees with where
     * the drop will actually go.
     */
    private fun dropTargetFor(list: List<Track>, ids: Set<String>, target: Int): DropTarget {
        val remaining = list.filterNot { it.id in ids }
        return DropTarget(remaining.getOrNull(target.coerceIn(0, remaining.size))?.id)
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
        val current = tracks.value ?: return
        if (index !in current.indices) return
        tracks.value = current.toMutableList().apply { removeAt(index) }
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
    private fun removeSongs(ids: Set<String>) {
        val current = tracks.value ?: return
        if (ids.isEmpty()) return
        val remaining = current.filterNot { it.id in ids }
        if (remaining.size == current.size) return
        tracks.value = remaining
        App.scope.launch {
            App.library.reorderPlaylist(playlistId, remaining.map { it.id })
            App.library.refreshPlaylists()
        }
    }

    /**
     * Move [ids] as a block to [target]: pull them out in their current relative
     * order, then reinsert them at [target] read against what's left. For a lone
     * dragged row this is the familiar single-row reorder (`target` is exactly
     * where it lands); for a multi-row selection the whole set rides along
     * together, so moving one selected row moves them all.
     */
    private fun reorderGroup(target: Int, ids: Set<String>) {
        val current = tracks.value ?: return
        if (ids.isEmpty()) return
        val moving = current.filter { it.id in ids }
        if (moving.isEmpty() || moving.size == current.size) return
        val remaining = current.filterNot { it.id in ids }
        val insertAt = target.coerceIn(0, remaining.size)
        val newList = remaining.toMutableList().apply { addAll(insertAt, moving) }
        if (newList == current) return
        tracks.value = newList
        App.scope.launch {
            App.library.reorderPlaylist(playlistId, newList.map { it.id })
            App.library.refreshPlaylists()
        }
    }
}

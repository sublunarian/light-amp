package com.sublunar.amp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import com.sublunar.amp.App
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.LightType
import com.sublunar.amp.ui.components.AppArtwork
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.AutoScroll
import com.sublunar.amp.ui.components.DRAG_HANDLE_PX
import com.sublunar.amp.ui.components.DropIndicatorLine
import com.sublunar.amp.ui.components.HEADER_BAR_PX
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.ListScrollBar
import com.sublunar.amp.ui.components.ROW_ART_PX
import com.sublunar.amp.ui.components.ROW_GAP_PX
import com.sublunar.amp.ui.components.ROW_LEAD_PX
import com.sublunar.amp.ui.components.ROW_SUB_LINE_PX
import com.sublunar.amp.ui.components.ROW_SUB_PX
import com.sublunar.amp.ui.components.ROW_TITLE_LINE_PX
import com.sublunar.amp.ui.components.ROW_TITLE_PX
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.SelectionArtwork
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.SelectionState
import com.sublunar.amp.ui.components.TextRole
import com.sublunar.amp.ui.components.appClickable
import com.sublunar.amp.ui.components.dragReorderContainer
import com.sublunar.amp.ui.components.dropTarget
import com.sublunar.amp.ui.components.rememberDragReorderState
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.components.rowClickable
import com.sublunar.amp.ui.px
import com.sublunar.amp.ui.pxSp
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightThemeTokens

/** Where every other list's covers start: its edge and the download mark's slot. */
private const val QUEUE_START_PX = LIST_EDGE_PX + ROW_LEAD_PX

private const val QUEUE_ROW_PX = 160

/** A queue row's key. The same track can be queued twice, so its place is part of it. */
internal fun queueKey(index: Int, track: Track) = "$index-${track.id}"

/**
 * The row [key] names in [queue], read back from the key's own place rather than
 * a lookup built over a queue that can be the whole library; null once that
 * place holds another track.
 */
internal fun queueIndexOf(key: String, queue: List<Track>): Int? =
    key.substringBefore('-').toIntOrNull()?.takeIf { it in queue.indices && queueKey(it, queue[it]) == key }

/**
 * The queue: a header naming the page (while selecting, the count and the
 * selection controls), the list, and shuffle and repeat in a bar at the foot.
 */
@Composable
internal fun SimpleLightScreen<*>.QueuePlayer(queue: List<Track>, index: Int, onBack: () -> Unit) {
    val shuffle by App.playback.shuffle.collectAsState()
    val repeat by App.playback.repeatMode.collectAsState()
    val selection = rememberSelection("queue")

    Box(modifier = Modifier.fillMaxSize()) {
        if (selection.active) {
            SelectionHeader(
                selection = selection,
                onDelete = {
                    App.playback.removeFromQueue(selection.selected)
                    // Stay in selection mode with nothing chosen: pruning a
                    // queue is usually more than one pass.
                    selection.begin()
                },
                onConfirm = {
                    openSelectionActions(
                        selection.pick(queue) { it.id },
                        selection,
                        fromQueue = true,
                    )
                },
            )
        } else {
            AppHeader(
                onBack = onBack,
                titleContent = {
                    QueueTitle(
                        // The page, not the track: that one is the row marked
                        // as playing.
                        title = "Queue",
                        // Only with something current: "0 of 12" would be a
                        // position no row is at.
                        subtitle = if (index in queue.indices) {
                            "%,d of %,d".format(index + 1, queue.size)
                        } else {
                            null
                        },
                    )
                },
            )
        }
        val listState = rememberLazyListState()
        Box(
            // Whatever the screen leaves between the header and the bar.
            modifier = Modifier
                .fillMaxSize()
                .padding(top = px(QUEUE_TOP_PX), bottom = px(QUEUE_END_PX)),
        ) {
            QueueView(
                queue,
                index,
                selection,
                Modifier.fillMaxSize(),
                listState = listState,
            )
            // Outside the list's margins, against the screen edge, so it stays
            // clear of the drag handles.
            ListScrollBar(listState)
        }
        // Shuffle and repeat as a bar of their own: the queue is where you set
        // how it will be played.
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(px(HEADER_BAR_PX))
                // Both glyphs' outer edges land where the simplified nav
                // bar's do — see EDGE_GLYPH_PX. Their slots are the bar's
                // own square, so the inset is that distance less the slack
                // the glyph already has inside one.
                .padding(
                    horizontal = px(EDGE_GLYPH_PX) -
                        (px(HEADER_BAR_PX) - px(QUEUE_TOGGLE_ICON_PX)) / 2,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueToggle(AppIcons.Shuffle, active = shuffle) { App.playback.toggleShuffle() }
            Spacer(Modifier.weight(1f))
            QueueToggle(
                if (repeat == RepeatMode.TRACK) AppIcons.RepeatOne else AppIcons.Repeat,
                active = repeat != RepeatMode.OFF,
            ) { App.playback.cycleRepeat() }
        }
    }
}

/**
 * The queue's page title: its name, and where in it you are. A column even with
 * nothing under it, so the title doesn't shift as a second line arrives.
 */
@Composable
private fun QueueTitle(title: String, subtitle: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AppText(
            title,
            pxSp(LightType.FINE_PX),
            role = TextRole.Subheading,
            maxLines = 1,
            align = TextAlign.Center,
        )
        if (subtitle != null) {
            AppText(
                subtitle,
                pxSp(QUEUE_SUBTITLE_PX),
                lineHeight = pxSp(QUEUE_SUBTITLE_LINE_PX),
                dim = true,
                maxLines = 1,
                align = TextAlign.Center,
            )
        }
    }
}

/**
 * Shuffle or repeat at the foot of the queue, in a square the bar's height. The
 * glyph stays full strength and the underline carries the state: dimming it
 * made an available control look disabled.
 */
@Composable
private fun QueueToggle(icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.size(px(HEADER_BAR_PX)).appClickable(onClick = onClick),
    ) {
        AppIcon(icon, size = px(QUEUE_TOGGLE_ICON_PX))
        Box(
            modifier = Modifier
                .padding(top = px(UNDERLINE_GAP_PX))
                .width(px(UNDERLINE_W_PX))
                .height(px(UNDERLINE_H_PX))
                .background(
                    if (active) LightThemeTokens.colors.content else Color.Transparent,
                ),
        )
    }
}

@Composable
private fun SimpleLightScreen<*>.QueueView(
    queue: List<Track>,
    index: Int,
    selection: SelectionState,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val drag = rememberDragReorderState<String>()
    val artworkHidden by App.hideArtwork.collectAsState()
    val rowPx = with(LocalDensity.current) { px(QUEUE_ROW_PX).toPx() }
    // Read unconditionally, branch on the result — see ARCHITECTURE.md on
    // conditional composable calls.
    val rules by App.rules.collectAsState()
    val onPhone by App.library.downloadedTrackIds.collectAsState()
    val canStream = rules.mayStream
    // Only the upcoming part of the queue, past the current track, can be dragged into.
    val minIndex = index + 1
    val orderedKeys = remember(queue) { queue.mapIndexed(::queueKey) }
    val dropTarget = drag.dropTarget(orderedKeys, rowPx, minIndex)

    // The playing track stays at the top. Opening the page jumps there
    // outright, while a track ending animates, so the list is seen to move.
    var anchored by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        val target = index.coerceAtLeast(0)
        if (anchored) listState.animateScrollToItem(target) else listState.scrollToItem(target)
        anchored = true
    }

    drag.AutoScroll(listState, rowPx)

    Box(
        modifier
            .dragReorderContainer(
                state = drag,
                enabled = queue.size > minIndex,
                restartKey = queue to index,
                orderedKeys = orderedKeys,
                rowPx = rowPx,
                minIndex = minIndex,
                groupOf = { hitKey ->
                    val hitId = queueIndexOf(hitKey, queue)?.let { queue[it] }?.id
                    if (hitId != null && hitId in selection.selected && selection.count > 1) {
                        orderedKeys.filterIndexed { i, _ -> i >= minIndex && queue[i].id in selection.selected }
                            .toSet()
                    } else {
                        setOf(hitKey)
                    }
                },
                onDrop = { movingIndices, insertAt -> App.playback.moveGroupInQueue(movingIndices, insertAt) },
            ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(start = px(QUEUE_START_PX), end = px(SCROLLBAR_LANE_PX)),
        ) {
            itemsIndexed(queue, key = ::queueKey) { i, track ->
                val isCurrent = i == index
                val isPast = i < index
                // A row that has to wait for the server stays in the queue,
                // stepped back like a played row. It plays again the moment
                // the rules allow — see LinkRules.
                val mustWait = !canStream && track.id !in onPhone && !PhoneFiles.isPhoneFile(track.id)
                val rowKey = queueKey(i, track)
                val isDragging = rowKey in drag.draggingKeys
                // Clear coords on dispose: LazyColumn recycles nodes, so a stale
                // entry would silently report the next occupant's position.
                DisposableEffect(rowKey) {
                    onDispose { drag.clear(rowKey) }
                }
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(px(QUEUE_ROW_PX))
                            .onGloballyPositioned { drag.rowCoords[rowKey] = it }
                            // Real row goes invisible; DragOverlay draws the floating stand-in.
                            .alpha(if (isDragging) 0f else if (isPast || mustWait) 0.5f else 1f)
                            .rowClickable(
                                onClick = {
                                    when {
                                        selection.active -> selection.toggle(track.id)
                                        isCurrent -> App.playback.togglePlayPause()
                                        else -> App.playback.jumpTo(i)
                                    }
                                },
                                onLongClick = { if (!selection.active) openQueueOptions(i, selection) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QueueRowContent(track, isCurrent, selection, artworkHidden)
                        // Drag handle for reordering within the upcoming part of the queue.
                        if (!isPast && !isCurrent) {
                            AppIcon(
                                AppIcons.Dehaze,
                                size = px(DRAG_HANDLE_PX),
                                modifier = Modifier.onGloballyPositioned { drag.iconCoords[rowKey] = it },
                            )
                        }
                    }
                    if (dropTarget?.beforeKey == rowKey) DropIndicatorLine(Modifier.align(Alignment.TopStart))
                }
            }
            if (dropTarget != null && dropTarget.beforeKey == null) {
                item { DropIndicatorLine() }
            }
        }
        if (drag.draggingIndex != null) {
            // Found by key rather than by walking the queue, in queue order.
            val dragged = drag.draggingKeys.mapNotNull { queueIndexOf(it, queue) }.sorted()
            DragOverlay(queue, dragged, drag.dragStartTops, drag.fingerOffsetY, artworkHidden, selection)
        }
    }
}

/** A queue row's cover and lines, shared by the list and the floating copy being dragged. */
@Composable
private fun RowScope.QueueRowContent(
    track: Track,
    isCurrent: Boolean,
    selection: SelectionState,
    artworkHidden: Boolean,
) {
    if (selection.active) {
        SelectionArtwork(track.coverArtId, track.id in selection.selected)
        Spacer(Modifier.width(px(ROW_GAP_PX)))
    } else if (!artworkHidden) {
        AppArtwork(track.coverArtId, size = px(ROW_ART_PX))
        Spacer(Modifier.width(px(ROW_GAP_PX)))
    }
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isCurrent) {
                AppIcon(AppIcons.Waveform, size = px(46))
                Spacer(Modifier.width(px(15)))
            }
            AppText(
                track.title,
                pxSp(ROW_TITLE_PX),
                lineHeight = pxSp(ROW_TITLE_LINE_PX),
                maxLines = 1,
            )
        }
        AppText(
            track.artist,
            pxSp(ROW_SUB_PX),
            lineHeight = pxSp(ROW_SUB_LINE_PX),
            dim = true,
            maxLines = 1,
        )
    }
}

/**
 * Floating copy of the row(s) being dragged, drawn outside the LazyColumn so it
 * survives the real row being recycled by auto-scroll. [fingerOffsetY] excludes
 * auto-scroll's own contribution, since the overlay's position shouldn't move twice.
 */
@Composable
private fun BoxScope.DragOverlay(
    queue: List<Track>,
    /** The dragged rows' places in [queue], in queue order. */
    dragged: List<Int>,
    dragStartTops: Map<String, Float>,
    fingerOffsetY: Float,
    artworkHidden: Boolean,
    selection: SelectionState,
) {
    dragged.forEach { i ->
        val track = queue[i]
        val top = dragStartTops[queueKey(i, track)] ?: return@forEach
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(px(QUEUE_ROW_PX))
                .align(Alignment.TopStart)
                .graphicsLayer { translationY = top + fingerOffsetY }
                .padding(start = px(QUEUE_START_PX), end = px(SCROLLBAR_LANE_PX)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueRowContent(track, isCurrent = false, selection, artworkHidden)
            Spacer(Modifier.width(px(DRAG_HANDLE_PX)))
        }
    }
}

private fun SimpleLightScreen<*>.openQueueOptions(index: Int, selection: SelectionState) {
    val track = App.playback.queue.value.getOrNull(index) ?: return
    go {
        TrackActionsScreen(
            it,
            track.id,
            queueIndex = index,
            onSelect = { selection.begin(track.id) },
        )
    }
}

/** The queue list's bounds: between the header and the shuffle/repeat bar. */
private const val QUEUE_TOP_PX = HEADER_BAR_PX
private const val QUEUE_END_PX = HEADER_BAR_PX

/** The underline that marks a queue toggle as on. */
private const val UNDERLINE_GAP_PX = 3
private const val UNDERLINE_W_PX = 51
private const val UNDERLINE_H_PX = 3

/** The queue header's second line, the size of the title card's own. */
private const val QUEUE_SUBTITLE_PX = 36
private const val QUEUE_SUBTITLE_LINE_PX = 42

/**
 * Shuffle and repeat at the foot of the queue, sized off the Light music app's
 * own pair: they are the page's only controls, and at header size they read as
 * an afterthought.
 */
private const val QUEUE_TOGGLE_ICON_PX = 90

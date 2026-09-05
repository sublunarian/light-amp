package com.sublunar.amp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

// Thicker than the Now Playing tab underline so it reads under a finger.
private const val DROP_LINE_HEIGHT_PX = 9

/** Marks where a dragged row (or group) will land if dropped now. */
@Composable
fun DropIndicatorLine() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(px(DROP_LINE_HEIGHT_PX))
            .background(LightThemeTokens.colors.content),
    )
}

/**
 * Shared engine behind both the Now Playing queue and playlist drag-to-reorder: tracks
 * which row(s) [K] are being dragged, their coordinates, and how far auto-scroll has
 * carried the list. One row can drag a whole group along with it (a multi-selection).
 */
class DragReorderState<K> {
    var draggingIndex by mutableStateOf<Int?>(null)
    var draggingKeys by mutableStateOf<Set<K>>(emptySet())
    var anchorKey by mutableStateOf<K?>(null)
    var dragOffsetY by mutableStateOf(0f)
    var autoScrolledPx by mutableStateOf(0f)
    var dragStartTops by mutableStateOf<Map<K, Float>>(emptyMap())
    var containerCoords by mutableStateOf<LayoutCoordinates?>(null)
    val rowCoords = mutableMapOf<K, LayoutCoordinates>()
    val iconCoords = mutableMapOf<K, LayoutCoordinates>()

    /** The finger's own movement, with auto-scroll's contribution subtracted back out. */
    val fingerOffsetY: Float get() = dragOffsetY - autoScrolledPx

    fun clear(key: K) {
        rowCoords.remove(key)
        iconCoords.remove(key)
    }

    fun end() {
        draggingIndex = null
        draggingKeys = emptySet()
        anchorKey = null
        dragOffsetY = 0f
        autoScrolledPx = 0f
        dragStartTops = emptyMap()
    }
}

@Composable
fun <K> rememberDragReorderState(): DragReorderState<K> = remember { DragReorderState() }

/** Where index [from] would land right now, clamped to [minIndex]..lastIndex. */
fun dragRowTarget(size: Int, from: Int, dragOffsetY: Float, rowPx: Float, minIndex: Int = 0): Int =
    (from + (dragOffsetY / rowPx).roundToInt()).coerceIn(minIndex, size - 1)

/**
 * Position the dragged block would land at within the list once the moving rows are
 * pulled out — an index into that "remaining" list. [target] is worked out as if only
 * [from] were moving, so a multi-row selection tracks the finger exactly like a single
 * row would, instead of running out of room early as the selection shrinks the pool of
 * rows left to land among.
 */
fun dropInsertIndex(size: Int, from: Int, movingIndices: Set<Int>, target: Int): Int {
    val soloSize = size - 1
    val clampedTarget = target.coerceIn(0, soloSize)
    val anchorIndex = when {
        clampedTarget == soloSize -> size
        clampedTarget < from -> clampedTarget
        else -> clampedTarget + 1
    }
    return (0 until anchorIndex).count { it !in movingIndices }
}

/** Auto-scrolls [listState] while a drag is pinned against the top or bottom edge. */
@Composable
fun <K> DragReorderState<K>.AutoScroll(listState: LazyListState, rowPx: Float) {
    LaunchedEffect(draggingIndex) {
        val key = anchorKey ?: return@LaunchedEffect
        val startTop = dragStartTops[key] ?: return@LaunchedEffect
        var lastFrameMs = 0L
        while (isActive) {
            val frameMs = withFrameMillis { it }
            val dtMs = if (lastFrameMs == 0L) 0L else (frameMs - lastFrameMs).coerceAtMost(48L)
            lastFrameMs = frameMs
            if (dtMs == 0L) continue
            val viewportHeight = containerCoords?.size?.height?.toFloat() ?: continue
            val pointerY = startTop + rowPx / 2f + fingerOffsetY
            val overTop = rowPx - pointerY
            val overBottom = pointerY - (viewportHeight - rowPx)
            val pull = when {
                overTop > 0f -> -(overTop / rowPx).coerceIn(0f, 1f)
                overBottom > 0f -> (overBottom / rowPx).coerceIn(0f, 1f)
                else -> 0f
            }
            if (pull != 0f) {
                val delta = pull * rowPx * 6f * dtMs / 1000f
                val consumed = listState.scrollBy(delta)
                dragOffsetY += consumed
                autoScrolledPx += consumed
            }
        }
    }
}

/**
 * Drag handling lives on this container rather than each row's icon, since LazyColumn
 * can recycle a row (and cancel its pointerInput) mid-drag. Consuming the Initial pass
 * beats LazyColumn's own scroll gesture and the row's click handler.
 *
 * [orderedKeys] is the current display order; [groupOf] picks which rows move together
 * when a given key is grabbed (a multi-selection, or just that one row); [onDrop] gets
 * the dragged rows' original indices and where to reinsert them among the rest.
 */
fun <K> Modifier.dragReorderContainer(
    state: DragReorderState<K>,
    enabled: Boolean,
    restartKey: Any?,
    orderedKeys: List<K>,
    rowPx: Float,
    minIndex: Int = 0,
    groupOf: (K) -> Set<K> = { setOf(it) },
    onDrop: (movingIndices: Set<Int>, insertAt: Int) -> Unit,
): Modifier = this
    .onGloballyPositioned { state.containerCoords = it }
    .then(
        if (!enabled) {
            Modifier
        } else {
            Modifier.pointerInput(restartKey) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val container = state.containerCoords?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    val hitKey = state.iconCoords.entries.firstOrNull { (_, coords) ->
                        coords.isAttached && container.localBoundingBoxOf(coords).contains(down.position)
                    }?.key ?: return@awaitEachGesture
                    val fromIndex = orderedKeys.indexOf(hitKey)
                    if (fromIndex < 0) return@awaitEachGesture
                    down.consume()
                    val groupKeys = groupOf(hitKey)
                    val movingIndices = groupKeys.mapNotNull { k -> orderedKeys.indexOf(k).takeIf { it >= 0 } }.toSet()
                    state.dragStartTops = groupKeys.mapNotNull { k ->
                        state.rowCoords[k]?.takeIf { it.isAttached }
                            ?.let { k to container.localPositionOf(it, Offset.Zero).y }
                    }.toMap()
                    state.anchorKey = hitKey
                    state.draggingIndex = fromIndex
                    state.draggingKeys = groupKeys
                    state.dragOffsetY = 0f
                    state.autoScrolledPx = 0f
                    try {
                        val pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            state.dragOffsetY += change.positionChange().y
                            change.consume()
                        }
                        val target = dragRowTarget(orderedKeys.size, fromIndex, state.dragOffsetY, rowPx, minIndex)
                        // A tap on the handle, or a drag that came back to the row it
                        // started on, is not a reorder. Without this the block is still
                        // gathered: for a non-contiguous selection dropInsertIndex has
                        // no "leave it alone" answer, so the list would jump and a write
                        // would go out for a gesture that moved nothing.
                        if (target != fromIndex) {
                            val insertAt = dropInsertIndex(orderedKeys.size, fromIndex, movingIndices, target)
                            onDrop(movingIndices, insertAt)
                        }
                    } finally {
                        state.end()
                    }
                }
            }
        },
    )

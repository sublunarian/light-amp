package com.sublunar.amp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The thin bar down the right edge of a Light list, drawn the way the LP3 draws
 * its own: a hairline for the whole travel and a thicker bar over what's visible.
 * Drawn only when there is something to scroll (otherwise it reads as a border).
 */
@Composable
fun BoxScope.ListScrollBar(state: LazyListState) = ListScrollBar(rememberScrollTarget(state))

@Composable
fun BoxScope.ListScrollBar(target: ScrollTarget, lane: Dp = px(SCROLLBAR_LANE_PX)) {
    val total = target.totalItems
    val visible = target.visibleItems
    if (total == 0 || visible == 0 || visible >= total) return

    val fraction = (visible.toFloat() / total).coerceAtLeast(MIN_FRACTION)
    val first = target.firstVisibleIndex.toFloat() / total
    val travel = (first / (1f - fraction).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)

    val scope = rememberCoroutineScope()
    // The gesture reads the current target: a ScrollTarget is rebuilt on every
    // scroll, so keying the pointer input on it would cancel the drag at once.
    val latest by rememberUpdatedState(target)

    BoxWithConstraints(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .width(lane)
            .pointerInput(Unit) {
                // Where in the list a touch at this height points. The thumb's
                // length comes off the travel and half of it off the touch, so the
                // thumb centres on the finger instead of jumping a thumb down.
                fun indexAt(y: Float): Int {
                    val now = latest
                    if (now.totalItems == 0) return 0
                    val frac = (now.visibleItems.toFloat() / now.totalItems)
                        .coerceAtLeast(MIN_FRACTION)
                    val thumb = size.height * frac
                    val travelPx = (size.height - thumb).coerceAtLeast(1f)
                    val furthest = (now.totalItems - now.visibleItems).coerceAtLeast(0)
                    val at = ((y - thumb / 2f) / travelPx).coerceIn(0f, 1f)
                    return (at * furthest).roundToInt()
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    scope.launch { latest.scrollTo(indexAt(down.position.y)) }
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        scope.launch { latest.scrollTo(indexAt(change.position.y)) }
                        if (!change.pressed) break
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(px(TRACK_PX))
                .background(LightThemeTokens.colors.content),
        )
        val barHeight = maxHeight * fraction
        Box(
            modifier = Modifier
                .offset(y = (maxHeight - barHeight) * travel)
                .width(px(THUMB_PX))
                .height(barHeight)
                .background(LightThemeTokens.colors.content),
        )
    }
}

/**
 * The lane the bar lives in, which lists inset their content by. The A–Z strip's
 * width on purpose: both centre in their lane, so the bar and the letters share an
 * axis and swapping one for the other doesn't shift anything sideways.
 */
const val SCROLLBAR_LANE_PX = INDEX_STRIP_PX

/** Below this the bar is too short to grab hold of visually. */
private const val MIN_FRACTION = 0.06f
private const val TRACK_PX = 3
private const val THUMB_PX = 9

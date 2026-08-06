package com.sublunar.amp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * The thin bar down the right edge of a Light list.
 *
 * Position and length come from the rows the layout actually reports, so it
 * stays honest on a list whose rows aren't all the same height, and it is drawn
 * only when there is something to scroll — on a short list it would be a full-
 * height line that never moves, which reads as a border.
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
    // The gesture reads the *current* target, not the one it was built with: a
    // ScrollTarget is rebuilt on every scroll, so keying the pointer input on it
    // would tear the detector down and cancel the drag the moment it moved the
    // list — which is to say, immediately.
    val latest by rememberUpdatedState(target)

    // Measures the space it's in rather than being told: every list that wants a
    // bar is a different height, and passing that height around was one more
    // number to keep in step with the layout.
    BoxWithConstraints(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .width(lane)
            .pointerInput(Unit) {
                // Where in the list a touch at this height points. The thumb's
                // own length is taken off the travel and half of it off the
                // touch, so the bar centres on your finger rather than starting
                // there — otherwise the list jumps down by a thumb's worth the
                // instant you grab it.
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
        // The same shape the LP3 draws elsewhere — a hairline for the whole
        // travel, with a thicker bar over the part you can see — so a list's
        // scrollbar reads like the rest of the system's.
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
 * The lane the bar lives in — lists inset their content by this much.
 *
 * The same width as the A–Z strip on purpose: both are centred in their lane, so
 * matching lanes put the bar and the letters on one axis. A list that swaps one
 * for the other (All Albums for Liked Albums, say) then doesn't appear to shift
 * its furniture sideways.
 */
const val SCROLLBAR_LANE_PX = INDEX_STRIP_PX

/** Below this the bar is too short to grab hold of visually. */
private const val MIN_FRACTION = 0.06f
private const val TRACK_PX = 3
private const val THUMB_PX = 9

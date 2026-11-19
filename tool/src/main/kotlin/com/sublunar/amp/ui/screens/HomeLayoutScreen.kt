package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import com.sublunar.amp.App
import com.sublunar.amp.data.HomeCategory
import com.sublunar.amp.data.HomeLayout
import com.sublunar.amp.ui.components.AutoScroll
import com.sublunar.amp.ui.components.DragHandle
import com.sublunar.amp.ui.components.HEADING_ROW_PX
import com.sublunar.amp.ui.components.LIST_EDGE_PX
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SCROLLBAR_LANE_PX
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.dragReorderContainer
import com.sublunar.amp.ui.components.gapShift
import com.sublunar.amp.ui.components.rememberDragReorderState
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalLightColors

/**
 * What the simple layout's library home lists: each category switched on or off
 * at its start, those off grey below those on. Those on are put in order with the
 * grab bar at their end, as the queue's songs are; those off keep theirs, out of reach.
 */
class HomeLayoutScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val layout by App.settings.homeLayout.state.collectAsState()
        val order = layout.order
        // Only those on are dragged, and only among themselves: the ones off sit below, untouched.
        val listed = layout.listed
        val drag = rememberDragReorderState<HomeCategory>()
        val rowPx = with(LocalDensity.current) { px(HEADING_ROW_PX).toPx() }
        val listState = rememberLazyListState()
        drag.AutoScroll(listState, rowPx)

        ListScreen(onBack = { goBack() }, title = "Library Home", headingRows = true) {
            Box(
                Modifier
                    .fillMaxSize()
                    .dragReorderContainer(
                        state = drag,
                        enabled = true,
                        restartKey = order,
                        orderedKeys = listed,
                        rowPx = rowPx,
                        onDrop = { moving, insertAt ->
                            val category = listed[moving.first()]
                            // At once: the stored write lands a frame late, and the row would jump back first.
                            App.settings.homeLayout.setNow(layout.moved(category, insertAt))
                        },
                    ),
            ) {
                ScrollableList(state = listState) {
                    itemsIndexed(order, key = { _, c -> c }) { index, category ->
                        DisposableEffect(category) { onDispose { drag.clear(category) } }
                        // Past the last one on, a row is never in the dragged one's way.
                        val gap = drag.gapShift(index, listed.size, rowPx)
                        Box(Modifier.fillMaxWidth().graphicsLayer { translationY = gap }) {
                            CategoryRow(
                                category,
                                layout,
                                modifier = Modifier
                                    .onGloballyPositioned { drag.rowCoords[category] = it }
                                    // The floating copy below stands in for it while it moves.
                                    .alpha(if (category in drag.draggingKeys) 0f else 1f),
                                onHandle = { drag.iconCoords[category] = it },
                            )
                        }
                    }
                }
                drag.anchorKey?.let { held ->
                    val top = drag.dragStartTops[held]
                    if (top != null) FloatingRow(held, layout, top + drag.fingerOffsetY)
                }
            }
        }
    }

    @Composable
    private fun CategoryRow(
        category: HomeCategory,
        layout: HomeLayout,
        modifier: Modifier = Modifier,
        onHandle: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
    ) {
        val on = category in layout.shown
        val colors = LightThemeTokens.colors
        // Off, the whole row takes the grey: its switch, name and grab bar.
        CompositionLocalProvider(LocalLightColors provides if (on) colors else colors.copy(content = colors.contentSecondary)) {
            TextRow(
                title = category.title,
                modifier = modifier,
                leading = { ToggleMark(on) },
                trailing = if (on) ({ DragHandle(onHandle) }) else null,
            ) {
                App.settings.homeLayout.setNow(layout.toggled(category))
            }
        }
    }

    /** The row being dragged, drawn over the list so it follows the finger. */
    @Composable
    private fun BoxScope.FloatingRow(category: HomeCategory, layout: HomeLayout, topPx: Float) {
        CategoryRow(
            category,
            layout,
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer { translationY = topPx }
                .padding(start = px(LIST_EDGE_PX), end = px(SCROLLBAR_LANE_PX)),
        )
    }
}

package com.sublunar.amp.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * What the A–Z strip and the scroll bar need from a list — nothing more than
 * where it is, how long it is, and how to move it.
 *
 * An abstraction rather than a `LazyListState` because the album lists can be a
 * grid, and a grid's state is a different type carrying the same four facts.
 * Both furnishings work in item indices, which a grid honours as well as a
 * column does, so the maths behind them doesn't change at all.
 */
class ScrollTarget(
    val firstVisibleIndex: Int,
    val totalItems: Int,
    val visibleItems: Int,
    val scrollTo: suspend (Int) -> Unit,
)

@Composable
fun rememberScrollTarget(state: LazyListState): ScrollTarget {
    val info = state.layoutInfo
    return remember(state, state.firstVisibleItemIndex, info.totalItemsCount, info.visibleItemsInfo.size) {
        ScrollTarget(
            firstVisibleIndex = state.firstVisibleItemIndex,
            totalItems = info.totalItemsCount,
            visibleItems = info.visibleItemsInfo.size,
            scrollTo = { state.scrollToItem(it) },
        )
    }
}

@Composable
fun rememberScrollTarget(state: LazyGridState): ScrollTarget {
    val info = state.layoutInfo
    return remember(state, state.firstVisibleItemIndex, info.totalItemsCount, info.visibleItemsInfo.size) {
        ScrollTarget(
            firstVisibleIndex = state.firstVisibleItemIndex,
            totalItems = info.totalItemsCount,
            visibleItems = info.visibleItemsInfo.size,
            scrollTo = { state.scrollToItem(it) },
        )
    }
}

/**
 * The same four facts from a [ScrollState] — an ordinary scrolling column.
 *
 * The action sheets are built from plain rows rather than lazy items, so they
 * have pixels where a list has indices. Dividing by a nominal row height turns
 * one into the other: the bar only needs proportions, and being a row out on a
 * sheet of a dozen rows is invisible.
 */
@Composable
fun rememberScrollTarget(state: ScrollState, rowPx: Int = SCROLL_ROW_PX): ScrollTarget {
    val viewport = state.viewportSize
    val content = viewport + state.maxValue
    return remember(state, state.value, viewport, content) {
        val rows = (content / rowPx).coerceAtLeast(1)
        val onScreen = (viewport / rowPx).coerceAtLeast(1)
        ScrollTarget(
            firstVisibleIndex = state.value / rowPx,
            totalItems = rows,
            visibleItems = onScreen.coerceAtMost(rows),
            scrollTo = { state.scrollTo(it * rowPx) },
        )
    }
}

/** A text row's height, near enough for a bar that only shows proportions. */
private const val SCROLL_ROW_PX = 108

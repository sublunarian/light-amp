package com.sublunar.amp.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * What the A–Z strip and the scroll bar need from a list: where it is, how long it
 * is, and how to move it, in item indices. Not a `LazyListState` because the album
 * lists can be a grid, whose state is a different type carrying the same facts.
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
 * The same facts from a plain scrolling column (the action sheets), which has
 * pixels where a list has indices. A nominal row height converts one into the
 * other: the bar only shows proportions.
 */
@Composable
fun rememberScrollTarget(state: ScrollState): ScrollTarget {
    val viewport = state.viewportSize
    val content = viewport + state.maxValue
    return remember(state, state.value, viewport, content) {
        val rows = (content / SCROLL_ROW_PX).coerceAtLeast(1)
        val onScreen = (viewport / SCROLL_ROW_PX).coerceAtLeast(1)
        ScrollTarget(
            firstVisibleIndex = state.value / SCROLL_ROW_PX,
            totalItems = rows,
            visibleItems = onScreen.coerceAtMost(rows),
            scrollTo = { state.scrollTo(it * SCROLL_ROW_PX) },
        )
    }
}

/** A text row's height, near enough for proportions. */
private const val SCROLL_ROW_PX = 108

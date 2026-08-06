package com.sublunar.amp.ui.components

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

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
    /**
     * How far down the list is, in items, counting the part of the top one scrolled
     * off: a list a part-row too long scrolls without its first item changing, and
     * the bar's thumb still has to move.
     */
    val position: Float = firstVisibleIndex.toFloat(),
    /** How many items the screen holds, in the same fractional measure. */
    val extent: Float = visibleItems.toFloat(),
)

@Composable
fun rememberScrollTarget(state: LazyListState): ScrollTarget {
    val info = state.layoutInfo
    // Only rows wholly on screen count as shown: a list a part-row too long still
    // scrolls, and with every row partly showing it would look as if it didn't.
    val shown = info.visibleItemsInfo.count {
        it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset
    }.coerceAtLeast(1)
    val items = info.visibleItemsInfo
    val top = items.firstOrNull()
    val position = state.firstVisibleItemIndex +
        if (top == null || top.size == 0) 0f else state.firstVisibleItemScrollOffset.toFloat() / top.size
    val averageSize = if (items.isEmpty()) 0f else items.sumOf { it.size }.toFloat() / items.size
    val extent = if (averageSize == 0f) shown.toFloat() else info.viewportSize.height / averageSize
    return remember(state, info.totalItemsCount, shown, position, extent) {
        ScrollTarget(
            firstVisibleIndex = state.firstVisibleItemIndex,
            totalItems = info.totalItemsCount,
            visibleItems = shown,
            scrollTo = { state.scrollToItem(it) },
            position = position,
            extent = extent.coerceAtMost(info.totalItemsCount.toFloat()),
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
 * The same facts from a plain scrolling column (the action sheets), counted in
 * pixels where a list counts items: the bar only shows proportions, and a sheet
 * a few pixels too long still has a bar.
 */
@Composable
fun rememberScrollTarget(state: ScrollState): ScrollTarget {
    val viewport = state.viewportSize
    val content = viewport + state.maxValue
    return remember(state, state.value, viewport, content) {
        ScrollTarget(
            firstVisibleIndex = state.value,
            totalItems = content,
            visibleItems = viewport,
            scrollTo = { state.scrollTo(it) },
        )
    }
}

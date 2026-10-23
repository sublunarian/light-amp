package com.sublunar.amp.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a list was scrolled to, remembered across a trip to another screen. The
 * SDK composes only the top of the back stack, so opening a long-press sheet tears
 * down the list underneath and a plain `rememberLazyListState` comes back at row one.
 *
 * Positions are stored in content coordinates, with the header rows above the list
 * subtracted, so a list that drops those rows in selection mode comes back to the
 * same song.
 */
object ScrollAnchors {

    private data class Anchor(val index: Int, val offset: Int)

    private val anchors = mutableMapOf<String, Anchor>()

    internal fun save(owner: String, index: Int, offset: Int) {
        anchors[owner] = Anchor(index.coerceAtLeast(0), offset.coerceAtLeast(0))
    }

    internal fun load(owner: String): Pair<Int, Int> =
        anchors[owner]?.let { it.index to it.offset } ?: (0 to 0)

    /**
     * Send this list back to the top, now. [clear] only shows the next time a list
     * is built, so a list already on screen needs a signal its live state can hear.
     */
    fun requestTop(owner: String) {
        clear(owner)
        _topRequests.value = TopRequest(owner, _topRequests.value.count + 1)
    }

    data class TopRequest(val owner: String = "", val count: Int = 0) {
        /** Whether this names that list, or a view of it ("tab:albums/grid"). */
        fun names(other: String): Boolean =
            count > 0 && (other == owner || other.startsWith("$owner/"))
    }

    /**
     * Answered once: the request outlives the list that asked, and a list rebuilt
     * later would read the standing value and throw away its restored position.
     */
    fun consumeTopRequest() {
        _topRequests.value = TopRequest()
    }

    private val _topRequests = MutableStateFlow(TopRequest())
    val topRequests: StateFlow<TopRequest> = _topRequests

    /**
     * Forget where this list was, and every view of it: "tab:albums" also clears
     * "tab:albums/grid", so the answer doesn't depend on which view is showing.
     */
    fun clear(owner: String) {
        anchors.keys.removeAll { key -> key == owner || key.startsWith("$owner/") }
    }
}

/**
 * A [LazyListState] that resumes where this list last was. [headerCount] is how
 * many rows sit above the content, and may differ between the save and the restore.
 */
@Composable
fun rememberListAnchor(owner: String, headerCount: Int = 0): LazyListState {
    val state = remember(owner) {
        val (index, offset) = ScrollAnchors.load(owner)
        // Only a mid-content anchor keeps its offset: landing back in the header
        // rows should show them from the top.
        if (index <= 0) LazyListState() else LazyListState(index + headerCount, offset)
    }
    AnchorEffects(
        owner,
        headerCount,
        firstIndex = { state.firstVisibleItemIndex },
        firstOffset = { state.firstVisibleItemScrollOffset },
        scrollToTop = { state.scrollToItem(0) },
    )
    return state
}

/**
 * The same, for a grid of covers. A grid needs its own [owner]: its item index lands
 * on screen according to the column count, so a list position would be far off.
 */
@Composable
fun rememberGridAnchor(owner: String, headerCount: Int = 0): LazyGridState {
    val state = remember(owner) {
        val (index, offset) = ScrollAnchors.load(owner)
        if (index <= 0) LazyGridState() else LazyGridState(index + headerCount, offset)
    }
    AnchorEffects(
        owner,
        headerCount,
        firstIndex = { state.firstVisibleItemIndex },
        firstOffset = { state.firstVisibleItemScrollOffset },
        scrollToTop = { state.scrollToItem(0) },
    )
    return state
}

/**
 * Answers a tap on the tab already showing (see [ScrollAnchors.requestTop]) and
 * saves the position when the list goes. Keyed on [headerCount] too, so a change
 * saves under the old geometry first.
 */
@Composable
private fun AnchorEffects(
    owner: String,
    headerCount: Int,
    firstIndex: () -> Int,
    firstOffset: () -> Int,
    scrollToTop: suspend () -> Unit,
) {
    val top by ScrollAnchors.topRequests.collectAsState()
    LaunchedEffect(top) {
        if (top.names(owner)) {
            scrollToTop()
            ScrollAnchors.consumeTopRequest()
        }
    }
    DisposableEffect(owner, headerCount) {
        onDispose { ScrollAnchors.save(owner, firstIndex() - headerCount, firstOffset()) }
    }
}

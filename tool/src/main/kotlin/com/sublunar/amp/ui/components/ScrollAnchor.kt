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
 * Where a list was scrolled to, remembered across a trip to another screen.
 *
 * The SDK composes only the top of the back stack, so opening the long-press
 * sheet tears down the list underneath it and a plain `rememberLazyListState`
 * comes back at row one — halfway down eight thousand songs, that's the whole
 * scroll lost to a single tap.
 *
 * Positions are stored in *content* coordinates, with the header rows above the
 * list (Liked / Shuffle / Play) subtracted out. A list that drops those rows on
 * entering selection mode therefore still comes back to the same song rather
 * than three rows off.
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
     * Forget where these lists were — used when one is asked to start over.
     *
     * Matches a name and anything under it, so a list and the grid of the same
     * content ("tab:albums" and "tab:albums/grid") are forgotten together. They
     * hold different numbers for the same shelf, and resetting one while keeping
     * the other means the answer depends on which view you happen to be in.
     */
    /**
     * Send these lists back to the top, now.
     *
     * [clear] only forgets where a list *was*, which shows the next time one is
     * built — a list already on screen stays exactly where it is. Tapping the
     * tab you are already on is a request about this list, so it needs a signal
     * the live state can hear.
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
     * Answered once. The request outlives the list that asked for it — leaving a
     * tab and coming back builds a new one, which reads the same standing value
     * and would scroll to the top again, throwing away the position that had
     * just been restored.
     */
    fun consumeTopRequest() {
        _topRequests.value = TopRequest()
    }

    private val _topRequests = MutableStateFlow(TopRequest())
    val topRequests: StateFlow<TopRequest> = _topRequests

    fun clear(vararg owners: String) {
        anchors.keys.removeAll { key -> owners.any { key == it || key.startsWith("$it/") } }
    }
}

/**
 * A [LazyListState] that resumes where this list last was.
 *
 * [headerCount] is how many rows sit above the content, and may differ between
 * the save and the restore — that's the point.
 */
@Composable
fun rememberListAnchor(owner: String, headerCount: Int = 0): LazyListState {
    val state = remember(owner) {
        val (index, offset) = ScrollAnchors.load(owner)
        // Only a mid-content anchor keeps its offset; landing back in the header
        // rows should show them from the top.
        if (index <= 0) LazyListState() else LazyListState(index + headerCount, offset)
    }
    // Keyed on headerCount too, so a change saves under the old geometry first.
    // A tap on the tab already showing asks this list, not the next one built
    // from it, to go back to the top — see [ScrollAnchors.requestTop].
    val top by ScrollAnchors.topRequests.collectAsState()
    LaunchedEffect(top) {
        if (top.names(owner)) {
            state.scrollToItem(0)
            ScrollAnchors.consumeTopRequest()
        }
    }
    DisposableEffect(owner, headerCount) {
        onDispose {
            ScrollAnchors.save(
                owner,
                state.firstVisibleItemIndex - headerCount,
                state.firstVisibleItemScrollOffset,
            )
        }
    }
    return state
}

/**
 * The same, for a grid of covers.
 *
 * Needs its own [owner] rather than sharing the list's: a grid's item index is
 * the album's position, and where that lands on screen depends on how many
 * columns are showing. Restoring a list position into a grid would put you
 * roughly three times too far down.
 */
@Composable
fun rememberGridAnchor(owner: String, headerCount: Int = 0): LazyGridState {
    val state = remember(owner) {
        val (index, offset) = ScrollAnchors.load(owner)
        if (index <= 0) LazyGridState() else LazyGridState(index + headerCount, offset)
    }
    // A tap on the tab already showing asks this list, not the next one built
    // from it, to go back to the top — see [ScrollAnchors.requestTop].
    val top by ScrollAnchors.topRequests.collectAsState()
    LaunchedEffect(top) {
        if (top.names(owner)) {
            state.scrollToItem(0)
            ScrollAnchors.consumeTopRequest()
        }
    }
    DisposableEffect(owner, headerCount) {
        onDispose {
            ScrollAnchors.save(
                owner,
                state.firstVisibleItemIndex - headerCount,
                state.firstVisibleItemScrollOffset,
            )
        }
    }
    return state
}

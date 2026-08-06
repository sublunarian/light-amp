package com.sublunar.amp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Multi-select over a track list.
 *
 * Ids rather than rows, so a refresh of the underlying list can't strand a stale
 * copy of a track.
 */
class SelectionState {

    var active by mutableStateOf(false)
        private set

    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    val count: Int get() = selected.size

    /** Enter selection mode, optionally with the track that was long-pressed. */
    fun begin(firstId: String? = null) {
        active = true
        selected = firstId?.let { setOf(it) } ?: emptySet()
    }

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun clear() {
        active = false
        selected = emptySet()
    }

    /** The chosen tracks in the order they appear in [list], not tap order. */
    fun <T> pick(list: List<T>, id: (T) -> String): List<T> =
        list.filter { id(it) in selected }
}

/**
 * Selection state that outlives the screen's composition.
 *
 * Selection mode is entered from the long-press sheet, which is a screen of its
 * own — and the SDK composes only the top of the back stack, so the list's
 * composition is torn down while that sheet is up. A `remember` would come back
 * empty, losing the very selection the sheet just started. Keeping the state here
 * makes the round trip invisible.
 *
 * Keyed by [owner] ("songs", "playlist:42") so two lists never share one, and
 * dropped when the selection ends so nothing accumulates.
 */
object Selections {
    private val states = mutableMapOf<String, SelectionState>()

    fun of(owner: String): SelectionState = states.getOrPut(owner) { SelectionState() }

    /** Leaving the library for good — no list should come back mid-selection. */
    fun clearAll() {
        states.values.forEach { it.clear() }
        states.clear()
    }
}

@Composable
fun rememberSelection(owner: String): SelectionState = remember(owner) { Selections.of(owner) }

/**
 * The header a list wears while selecting: the count replaces the title, X leaves
 * the mode, and "+" takes the now-playing button's place — the two are never both
 * wanted at once, and that corner is the only spot with a full 160px hit target.
 *
 * [onDelete], where the list can be deleted from, lands in the search button's
 * slot: same 80px square, same 120px to the left of "+", so the pair sits exactly
 * where the header's two right-hand controls always sit. Destructive, so it is
 * its own button rather than a line buried in the "+" sheet.
 */
@Composable
fun SelectionHeader(
    selection: SelectionState,
    onDelete: (() -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    AppHeader(
        leftAction = HeaderAction(AppIcons.Close) { selection.clear() },
        title = selectionTitle(selection.count),
        searchAction = onDelete?.let { delete -> { if (selection.count > 0) delete() } },
        searchIcon = AppIcons.DeleteOutline,
        rightAction = HeaderAction(AppIcons.Add) { if (selection.count > 0) onConfirm() },
    )
}

/** "3 selected", or a prompt while nothing is chosen yet. */
private fun selectionTitle(count: Int): String =
    if (count == 0) "Select songs" else "$count selected"

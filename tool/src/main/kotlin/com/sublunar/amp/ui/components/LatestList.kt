package com.sublunar.amp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * The list a lazy list is showing, for its rows to read rather than capture.
 *
 * A lazy list keeps rows it has scrolled past, composed as they last were, to
 * reuse later — and a row whose lambdas captured the list it came from keeps
 * that whole list alive with it. Each one can hold a different, older copy of
 * the library: a heap dump taken during a large library's first sync found
 * three such copies besides the current one. A row that reads through this
 * holds the handle, which lets go of its list once a newer one is on screen.
 */
class LatestList<T> internal constructor(list: List<T>, private val holder: LatestHolder<T>) {
    /** The list this handle was made for, until a newer one is applied — see [rememberLatest]. */
    internal var own: List<T>? = list

    /** This handle's own list while it is the newest; after that, the one on screen. */
    val items: List<T> get() = own ?: holder.items

    operator fun get(index: Int): T? = items.getOrNull(index)
}

/**
 * Deliberately not snapshot state: a state object keeps an earlier value in a
 * record until a newer write reuses it, which is the same pin over again.
 */
internal class LatestHolder<T> {
    var items: List<T> = emptyList()
    var handle: LatestList<T>? = null
    var applied: LatestList<T>? = null
}

/**
 * A handle on [items] for a lazy list's rows — see [LatestList].
 *
 * The handle reads its own list, not only the last one applied: the rows on
 * screen are composed again in the same pass as the list that changed, before
 * that pass is applied, and reading the last applied list they showed the one
 * before — and kept showing it, since nothing tells them it moved. Once a newer
 * handle is applied, an older one reads through to whatever is on screen, so
 * a row parked for reuse holds no list of its own.
 */
@Composable
fun <T> rememberLatest(items: List<T>): LatestList<T> {
    val holder = remember { LatestHolder<T>() }
    // A new handle for each new list: a lazy list skips a row whose lambda is
    // unchanged, so rows reading through one handle for ever would go on
    // showing whatever they were first composed with.
    val handle = holder.handle?.takeIf { it.own === items }
        ?: LatestList(items, holder).also { holder.handle = it }
    SideEffect {
        holder.items = items
        holder.applied?.takeIf { it !== handle }?.own = null
        holder.applied = handle
    }
    return handle
}

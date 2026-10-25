package com.sublunar.amp.playback

import com.sublunar.amp.data.Track

/**
 * The most queue rows the player holds at once. Media3 mirrors the player's
 * whole timeline into the legacy MediaSession queue, loading artwork for
 * every entry: eight thousand tracks ran the process out of memory there,
 * and building that many stream URLs blocked a tap for seconds. So the
 * controller keeps the whole queue and gives the player a window of it.
 */
internal const val PLAYER_WINDOW = 500

/** How many rows before the playing one a window built or slid around it keeps. */
internal const val WINDOW_BEHIND = 50

/**
 * How near either edge of its window playback may come before the window
 * slides: far enough out that the player always has a next track to buffer
 * for a gapless start.
 */
internal const val WINDOW_MARGIN = 25

/**
 * The rows of the queue the player holds, [start] until [end]: player
 * position p is queue row start + p. Rows outside it are the controller's
 * alone, and an edit there changes the player not at all.
 */
internal data class QueueWindow(val start: Int, val end: Int) {
    val size: Int get() = end - start

    operator fun contains(row: Int): Boolean = row in start until end

    /** The player's position for queue [row], or null when it doesn't hold that row. */
    fun positionOf(row: Int): Int? = if (row in this) row - start else null

    /** The queue row at player [position]; -1, the player's "nothing", stays -1. */
    fun rowAt(position: Int): Int = if (position < 0) -1 else start + position

    fun isWhole(queueSize: Int): Boolean = start == 0 && end == queueSize

    /** Whether [row] is this window's last with more of the queue after it: a player that ends there ends short of the queue. */
    fun endsShortAt(row: Int, queueSize: Int): Boolean = row >= 0 && row == end - 1 && end < queueSize

    /** This window once queue [row] is removed, and the player position to remove with it, if it holds the row. */
    fun removing(row: Int): Pair<QueueWindow, Int?> = when {
        row < start -> QueueWindow(start - 1, end - 1) to null
        row < end -> QueueWindow(start, end - 1) to row - start
        else -> this to null
    }

    /**
     * Where to slide to, with playback on [row] inside this window: when it
     * has come within [WINDOW_MARGIN] of an edge with more queue beyond it,
     * or the window has outgrown [PLAYER_WINDOW]. Null to stay put.
     */
    fun slidFor(queueSize: Int, row: Int): QueueWindow? {
        val nearEnd = end < queueSize && end - 1 - row < WINDOW_MARGIN
        val nearStart = start > 0 && row - start < WINDOW_MARGIN
        if (!nearEnd && !nearStart && size <= PLAYER_WINDOW) return null
        return windowAround(queueSize, row).takeIf { it != this }
    }

    /**
     * Whether the player's own next ([step] 1) or previous (-1) from queue
     * [row] lands on [target]: it holds the neighbour, or [loops] (repeat-all)
     * a window that is the whole queue. Otherwise the controller has to move
     * it there itself.
     */
    fun stepReaches(row: Int, step: Int, target: Int, loops: Boolean): Boolean {
        val position = positionOf(row) ?: return false
        val landed = when {
            position + step in 0 until size -> position + step
            loops -> (position + step + size) % size
            else -> return false
        }
        return rowAt(landed) == target
    }

    companion object {
        val EMPTY = QueueWindow(0, 0)
    }
}

/**
 * The window built around [row] in a queue of [queueSize]: [WINDOW_BEHIND]
 * rows before it and [PLAYER_WINDOW] in all, where the queue has them.
 */
internal fun windowAround(queueSize: Int, row: Int): QueueWindow {
    val start = (row - WINDOW_BEHIND).coerceIn(0, maxOf(0, queueSize - PLAYER_WINDOW))
    return QueueWindow(start, minOf(queueSize, start + PLAYER_WINDOW))
}

/** Put queue [rows] where the player's positions [from] until [to] are: one replaceRange. */
internal data class WindowEdit(val from: Int, val to: Int, val rows: IntRange)

/**
 * The edits that take the player from window [from] to [to]. Both hold the
 * playing row and neither edit touches it, so it plays on. The tail end goes
 * first, so the head's positions are still the ones worked out here.
 */
internal fun slideEdits(from: QueueWindow, to: QueueWindow): List<WindowEdit> = buildList {
    when {
        to.end > from.end -> add(WindowEdit(from.size, from.size, from.end until to.end))
        to.end < from.end -> add(WindowEdit(to.end - from.start, from.size, IntRange.EMPTY))
    }
    when {
        to.start > from.start -> add(WindowEdit(0, to.start - from.start, IntRange.EMPTY))
        to.start < from.start -> add(WindowEdit(0, 0, to.start until from.start))
    }
}

/**
 * The window a rearranged [queue] gets around its playing row, now at
 * [row], and the edits that take the player there from the rows it [held],
 * where it was playing position [heldAt]. The playing row is never touched,
 * so it plays on; on either side of it only the stretch that differs is
 * replaced, as one edit, tail first. One replace rather than an edit per
 * row: each is a timeline update, and a few hundred lock Main and, with
 * artwork, exhaust the heap. The window keeps as many rows behind as it had
 * and fills up to [PLAYER_WINDOW] ahead.
 */
internal fun followingEdit(
    held: List<Track>,
    heldAt: Int,
    queue: List<Track>,
    row: Int,
): Pair<QueueWindow, List<WindowEdit>> {
    val behind = minOf(row, heldAt)
    val ahead = minOf(queue.size - row - 1, PLAYER_WINDOW - 1 - behind)
    val window = QueueWindow(row - behind, row + 1 + ahead)
    val edits = buildList {
        changedStretch(held.subList(heldAt + 1, held.size), queue.subList(row + 1, window.end))
            ?.let { (old, new) -> add(WindowEdit(heldAt + 1 + old.first, heldAt + 2 + old.last, new.shift(row + 1))) }
        changedStretch(held.subList(0, heldAt), queue.subList(window.start, row))
            ?.let { (old, new) -> add(WindowEdit(old.first, old.last + 1, new.shift(window.start))) }
    }
    return window to edits
}

/**
 * Where [old] and [new] differ, once what they share at either end is set
 * aside: that stretch's indices in each, either possibly empty. Null when
 * they are the same.
 */
internal fun <T> changedStretch(old: List<T>, new: List<T>): Pair<IntRange, IntRange>? {
    val shorter = minOf(old.size, new.size)
    var front = 0
    while (front < shorter && old[front] == new[front]) front++
    if (front == old.size && front == new.size) return null
    var back = 0
    while (back < shorter - front && old[old.size - 1 - back] == new[new.size - 1 - back]) back++
    return (front until old.size - back) to (front until new.size - back)
}

private fun IntRange.shift(by: Int): IntRange = (first + by)..(last + by)

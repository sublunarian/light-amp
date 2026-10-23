package com.sublunar.amp.data

/**
 * The moves that turn [current] into [wanted], as (key, the key it goes right
 * after, or null for the top), to be made in this order. The longest run of
 * keys already in order stays put, so moving one song is one move however long
 * the playlist. Keys are unique, and [wanted] holds the same ones as [current].
 *
 * When [wanted]'s first key is already first, it stays put, so no move needs
 * the top — see PlexClient.reorderPlaylist.
 */
internal fun <K> placementMoves(current: List<K>, wanted: List<K>): List<Pair<K, K?>> {
    if (wanted.isEmpty()) return emptyList()
    val at = current.withIndex().associate { (i, key) -> key to i }
    val positions = wanted.map { at.getValue(it) }
    val kept = if (positions[0] == 0) {
        longestRising(positions.drop(1)).mapTo(HashSet()) { it + 1 }.apply { add(0) }
    } else {
        longestRising(positions)
    }
    return wanted.indices.filter { it !in kept }.map { i -> wanted[i] to wanted.getOrNull(i - 1) }
}

/** Indices of one longest strictly rising run in [values]. */
private fun longestRising(values: List<Int>): Set<Int> {
    val tails = IntArray(values.size)
    val previous = IntArray(values.size) { -1 }
    var length = 0
    for (i in values.indices) {
        var low = 0
        var high = length
        while (low < high) {
            val mid = (low + high) / 2
            if (values[tails[mid]] < values[i]) low = mid + 1 else high = mid
        }
        previous[i] = if (low > 0) tails[low - 1] else -1
        tails[low] = i
        if (low == length) length++
    }
    val run = HashSet<Int>()
    var i = if (length > 0) tails[length - 1] else -1
    while (i >= 0) {
        run.add(i)
        i = previous[i]
    }
    return run
}

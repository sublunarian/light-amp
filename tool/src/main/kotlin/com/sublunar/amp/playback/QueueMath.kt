package com.sublunar.amp.playback

import com.sublunar.amp.data.Track
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import kotlin.random.Random

/** The most tracks the player is given at once, and how many before the start are kept — see [capped]. */
internal const val MAX_QUEUE = 500
private const val QUEUE_LOOKBACK = 50

/**
 * Trim a queue to what the player can hold, centred on the starting track
 * with a little history behind it. Media3 mirrors the whole timeline into
 * the legacy MediaSession queue, loading artwork for every entry: eight
 * thousand tracks ran the process out of memory there, and building that
 * many stream URLs blocked the tap for seconds.
 *
 * A copy either way, never the caller's list or a view of it: a sublist
 * kept as the queue kept the whole sorted library it came from alive after
 * the library had moved on.
 */
internal fun capped(tracks: List<Track>, startIndex: Int): Pair<List<Track>, Int> {
    if (tracks.size <= MAX_QUEUE) return tracks.toList() to startIndex
    val first = (startIndex - QUEUE_LOOKBACK).coerceIn(0, tracks.size - MAX_QUEUE)
    return tracks.subList(first, first + MAX_QUEUE).toList() to (startIndex - first)
}

/**
 * The positions a shuffled queue of [size] tracks plays: [start] first, then
 * the rest in random order, at most [MAX_QUEUE] in all.
 */
internal fun shuffledFrom(size: Int, start: Int, random: Random = Random): List<Int> {
    val rest = IntArray(size - 1) { if (it < start) it else it + 1 }.apply { shuffle(random) }
    return listOf(start) + rest.take(MAX_QUEUE - 1)
}

/** [tracks] sorted back into the relative order they had in [original]. */
internal fun restoreOrder(tracks: List<Track>, original: List<Track>): List<Track> {
    val rank = original.withIndex().associate { (i, t) -> t.id to i }
    // Tracks queued after shuffling have no pre-shuffle rank; keep them last.
    return tracks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
}

/** [index] as the player takes it in a queue of [size]: wrapped under repeat-queue, otherwise as given. */
internal fun wrappedIndex(index: Int, size: Int, repeatQueue: Boolean): Int =
    if (size > 0 && repeatQueue) ((index % size) + size) % size else index

/**
 * [queue] with the tracks at [fromIndices] moved, as a block in their order,
 * to [insertAt] among the rest, and the index the block lands on. Null when
 * an index is out of range, the block is the whole queue, or nothing moves.
 */
internal fun movedAsBlock(queue: List<Track>, fromIndices: Set<Int>, insertAt: Int): Pair<List<Track>, Int>? {
    if (fromIndices.isEmpty() || fromIndices.any { it !in queue.indices }) return null
    val moving = queue.filterIndexed { i, _ -> i in fromIndices }
    if (moving.size == queue.size) return null
    val remaining = queue.filterIndexed { i, _ -> i !in fromIndices }
    val landsAt = insertAt.coerceIn(0, remaining.size)
    val moved = remaining.toMutableList().apply { addAll(landsAt, moving) }
    return if (moved == queue) null else moved to landsAt
}

/**
 * The single moves (from, to) that turn [from] into [to], the same tracks in
 * another order, applied one after another. Tracks are matched by identity:
 * the queue can hold the same song twice.
 */
internal fun stepwiseMoves(from: List<Track>, to: List<Track>): List<Pair<Int, Int>> {
    val working = from.toMutableList()
    val moves = mutableListOf<Pair<Int, Int>>()
    to.forEachIndexed { target, track ->
        val current = working.indexOfFirst { it === track }
        if (current != target) {
            moves += current to target
            working.add(target, working.removeAt(current))
        }
    }
    return moves
}

/** The most a far end's last report is carried on: one that stopped reporting isn't carried into the next song. */
private const val CARRY_ON_CAP_MS = 2_000L

/**
 * Where a far end that reported [reportedMs] has got to [sinceReportMs]
 * later: carried on while [playing], as its song is, by at most
 * [CARRY_ON_CAP_MS], and never past [durationMs] where that is known.
 */
internal fun carriedOn(
    reportedMs: Long,
    sinceReportMs: Long,
    playing: Boolean,
    durationMs: Long,
): Long {
    if (!playing) return reportedMs
    val at = reportedMs + sinceReportMs.coerceIn(0L, CARRY_ON_CAP_MS)
    return if (durationMs > 0L) at.coerceAtMost(durationMs) else at
}

/**
 * Whether a reopen has to put the queue back into the player — see the
 * re-cue in [PlaybackController.attachHandle]. Only for a handle that
 * connected holding nothing and making no sound, under the very queue and
 * track the controller carried across. Silence is asked for as well as
 * emptiness: a service making sound has a queue, and cueing over it would
 * stop the music the user came back to.
 */
internal fun needsRecue(
    carriedOver: Boolean,
    playerIndex: Int,
    playerPlaying: Boolean,
    casting: Boolean,
    parked: Boolean,
): Boolean = carriedOver && playerIndex == NO_MEDIA_ITEM && !playerPlaying && !casting && !parked

/**
 * Where in its track a handle that has just connected leaves playback, when
 * the service is on another track than the screen shows — see the landing
 * in [PlaybackController.attachHandle]. Null when there is nothing to land
 * on: the same track, an empty player, a queue just cued back in, a cast
 * (its renderer says where playback is), or a park (the service sits on the
 * failed track, and the screen's marker is the one the user moved).
 *
 * The service's own position, read from the same snapshot as its index.
 * Except on the track a next or previous pressed while the handle connected
 * went to ([pickedIndex]): that press reached the service after the
 * snapshot, so the track starts from the top.
 */
internal fun connectLanding(
    playerIndex: Int,
    playerPositionMs: Long,
    shownIndex: Int,
    pickedIndex: Int,
    recued: Boolean,
    casting: Boolean,
    parked: Boolean,
): Long? = when {
    recued || casting || parked -> null
    playerIndex < 0 || playerIndex == shownIndex -> null
    playerIndex == pickedIndex -> 0L
    else -> playerPositionMs
}

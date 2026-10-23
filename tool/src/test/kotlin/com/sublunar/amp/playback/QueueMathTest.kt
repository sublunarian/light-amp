package com.sublunar.amp.playback

import com.sublunar.amp.data.Track
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class QueueMathTest {

    private fun track(id: String) = Track(
        id = id, title = id, artist = "", album = "", albumArtist = "", albumId = null, coverArtId = null,
        durationMs = 0L, trackNumber = null, discNumber = null, year = null, playCount = 0, lastPlayedMs = 0L,
    )

    private fun tracks(n: Int) = List(n) { track("t$it") }

    private fun ids(list: List<Track>) = list.map { it.id }

    @Test
    fun `a queue the player can hold is kept whole, as a copy`() {
        val all = tracks(10)
        val (window, start) = capped(all, 4)
        assertEquals(all, window)
        assertNotSame(all, window)
        assertEquals(4, start)
    }

    @Test
    fun `a long queue keeps a little history behind the start`() {
        val all = tracks(2_000)
        val (window, start) = capped(all, 1_000)
        assertEquals(MAX_QUEUE, window.size)
        assertEquals("t950", window.first().id)
        assertEquals(50, start)
        assertEquals("t1000", window[start].id)
    }

    @Test
    fun `a long queue started near either end keeps its full window`() {
        val all = tracks(2_000)
        assertEquals(10 to "t0", capped(all, 10).let { (w, s) -> s to w.first().id })
        val (window, start) = capped(all, 1_990)
        assertEquals("t1500", window.first().id)
        assertEquals("t1990", window[start].id)
    }

    @Test
    fun `a shuffle starts on the tapped track and plays every other one once`() {
        val picked = shuffledFrom(size = 20, start = 7, random = Random(1))
        assertEquals(7, picked.first())
        assertEquals((0 until 20).toList(), picked.sorted())
    }

    @Test
    fun `a shuffle of a long list is capped`() {
        val picked = shuffledFrom(size = 2_000, start = 1_500, random = Random(1))
        assertEquals(MAX_QUEUE, picked.size)
        assertEquals(1_500, picked.first())
        assertEquals(MAX_QUEUE, picked.toSet().size)
    }

    @Test
    fun `unshuffling puts tracks back in their old order, and new ones last`() {
        val (a, b, c, d) = tracks(4)
        val added = track("new")
        assertEquals(listOf(a, b, d, added), restoreOrder(listOf(d, added, b, a), original = listOf(a, b, c, d)))
    }

    @Test
    fun `indices wrap only under repeat-queue`() {
        assertEquals(0, wrappedIndex(5, size = 5, repeatQueue = true))
        assertEquals(4, wrappedIndex(-1, size = 5, repeatQueue = true))
        assertEquals(5, wrappedIndex(5, size = 5, repeatQueue = false))
        assertEquals(-1, wrappedIndex(-1, size = 5, repeatQueue = false))
        assertEquals(3, wrappedIndex(3, size = 0, repeatQueue = true))
    }

    @Test
    fun `a block moves in its own order to its place among the rest`() {
        val q = tracks(6)
        val (moved, landsAt) = movedAsBlock(q, setOf(4, 1), insertAt = 0)!!
        assertEquals(listOf("t1", "t4", "t0", "t2", "t3", "t5"), ids(moved))
        assertEquals(0, landsAt)
        val (toEnd, end) = movedAsBlock(q, setOf(0, 2), insertAt = 99)!!
        assertEquals(listOf("t1", "t3", "t4", "t5", "t0", "t2"), ids(toEnd))
        assertEquals(4, end)
    }

    @Test
    fun `a move that changes nothing, moves everything or names a missing row is no move`() {
        val q = tracks(4)
        assertNull(movedAsBlock(q, setOf(1, 2), insertAt = 1))
        assertNull(movedAsBlock(q, setOf(0, 1, 2, 3), insertAt = 0))
        assertNull(movedAsBlock(q, setOf(9), insertAt = 0))
        assertNull(movedAsBlock(q, emptySet(), insertAt = 0))
    }

    @Test
    fun `stepwise moves replayed on the old queue give the new one, the same song twice included`() {
        val (a, b, c) = tracks(3)
        val again = track("t0")
        val from = listOf(a, b, again, c)
        val to = listOf(c, again, a, b)
        val replayed = from.toMutableList()
        for ((f, t) in stepwiseMoves(from, to)) replayed.add(t, replayed.removeAt(f))
        assertEquals(to.map { System.identityHashCode(it) }, replayed.map { System.identityHashCode(it) })
    }
}

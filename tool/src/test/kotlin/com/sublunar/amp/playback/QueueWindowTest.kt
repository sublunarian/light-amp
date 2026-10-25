package com.sublunar.amp.playback

import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.Track
import com.sublunar.amp.playback.output.QueueEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QueueWindowTest {

    private fun track(id: String) = Track(
        id = id, title = id, artist = "", album = "", albumArtist = "", albumId = null, coverArtId = null,
        durationMs = 0L, trackNumber = null, discNumber = null, year = null, playCount = 0, lastPlayedMs = 0L,
    )

    private fun tracks(n: Int, prefix: String = "t") = List(n) { track("$prefix$it") }

    private fun ids(list: List<Track>) = list.map { it.id }

    /** The player's rows after [edits], each replacing positions with rows of [queue]. */
    private fun List<Track>.edited(edits: List<WindowEdit>, queue: List<Track>): List<Track> {
        val player = toMutableList()
        for (edit in edits) {
            player.subList(edit.from, edit.to).clear()
            player.addAll(edit.from, queue.slice(edit.rows))
        }
        return player
    }

    /** No edit covers [position], the playing row, as the player sees it when that edit is made. */
    private fun assertPlayingUntouched(edits: List<WindowEdit>, position: Int) {
        var playing = position
        // Tail first, then head: only a head edit moves the playing row.
        for (edit in edits) {
            assertFalse(playing in edit.from until edit.to, "an edit covers the playing row")
            if (edit.to <= playing) playing += edit.rows.count() - (edit.to - edit.from)
        }
    }

    @Test
    fun `a queue the player can hold is its own window`() {
        assertEquals(QueueWindow(0, 10), windowAround(10, 4))
        assertTrue(windowAround(10, 4).isWhole(10))
    }

    @Test
    fun `a long queue keeps a little history behind the playing row`() {
        val window = windowAround(2_000, 1_000)
        assertEquals(QueueWindow(950, 1_450), window)
        assertEquals(WINDOW_BEHIND, window.positionOf(1_000))
        assertEquals(1_000, window.rowAt(WINDOW_BEHIND))
        assertEquals(-1, window.rowAt(-1))
    }

    @Test
    fun `a window built near either end of a long queue is still full`() {
        assertEquals(QueueWindow(0, PLAYER_WINDOW), windowAround(2_000, 10))
        assertEquals(QueueWindow(1_500, 2_000), windowAround(2_000, 1_990))
    }

    @Test
    fun `a window stays put while playback is well inside it`() {
        val window = windowAround(2_000, 1_000)
        assertNull(window.slidFor(2_000, 1_000))
        assertNull(window.slidFor(2_000, window.end - 1 - WINDOW_MARGIN))
        // The queue's own ends are no reason to move.
        assertNull(QueueWindow(1_500, 2_000).slidFor(2_000, 1_999))
        assertNull(QueueWindow(0, PLAYER_WINDOW).slidFor(2_000, 0))
    }

    @Test
    fun `a window slides around playback that nears either edge, and never in a loop`() {
        val window = windowAround(2_000, 1_000)
        val forward = window.slidFor(2_000, window.end - WINDOW_MARGIN)!!
        assertEquals(windowAround(2_000, window.end - WINDOW_MARGIN), forward)
        assertNull(forward.slidFor(2_000, window.end - WINDOW_MARGIN))
        val back = window.slidFor(2_000, window.start + WINDOW_MARGIN - 1)!!
        assertEquals(windowAround(2_000, window.start + WINDOW_MARGIN - 1), back)
        assertNull(back.slidFor(2_000, window.start + WINDOW_MARGIN - 1))
    }

    @Test
    fun `a window grown past its bound by an edit is trimmed`() {
        assertEquals(QueueWindow(0, PLAYER_WINDOW), QueueWindow(0, PLAYER_WINDOW + 3).slidFor(2_000, 10))
    }

    @Test
    fun `sliding replaces the ends of the window and leaves the playing row be`() {
        val queue = tracks(2_000)
        for ((from, row) in listOf(QueueWindow(950, 1_450) to 1_430, QueueWindow(950, 1_450) to 960)) {
            val to = from.slidFor(queue.size, row)!!
            val edits = slideEdits(from, to)
            assertEquals(queue.subList(to.start, to.end), queue.subList(from.start, from.end).edited(edits, queue))
            assertPlayingUntouched(edits, from.positionOf(row)!!)
            assertTrue(edits.size <= 2)
        }
    }

    @Test
    fun `only the last row of a window with more queue after it ends short of the queue`() {
        val window = QueueWindow(950, 1_450)
        assertTrue(window.endsShortAt(1_449, 2_000))
        assertFalse(window.endsShortAt(1_448, 2_000))
        assertFalse(QueueWindow(1_500, 2_000).endsShortAt(1_999, 2_000))
        assertFalse(QueueWindow.EMPTY.endsShortAt(-1, 0))
    }

    @Test
    fun `a removed row leaves the player alone unless it holds it`() {
        val window = QueueWindow(10, 20)
        assertEquals(QueueWindow(9, 19) to null, window.removing(3))
        assertEquals(QueueWindow(10, 19) to 5, window.removing(15))
        assertEquals(window to null, window.removing(25))
    }

    @Test
    fun `the player steps itself only to a neighbour it holds, or round a whole queue it loops`() {
        val window = QueueWindow(10, 20)
        assertTrue(window.stepReaches(12, +1, 13, loops = false))
        assertTrue(window.stepReaches(12, -1, 11, loops = false))
        assertFalse(window.stepReaches(19, +1, 20, loops = false))
        assertFalse(window.stepReaches(10, -1, 9, loops = false))
        assertFalse(window.stepReaches(25, +1, 26, loops = false))
        val whole = QueueWindow(0, 10)
        assertTrue(whole.stepReaches(9, +1, 0, loops = true))
        assertTrue(whole.stepReaches(0, -1, 9, loops = true))
        assertFalse(whole.stepReaches(9, +1, 0, loops = false))
    }

    @Test
    fun `stretches compare what lies between a shared start and end`() {
        val (a, b, c, d) = tracks(4)
        assertNull(changedStretch(listOf(a, b), listOf(a, b)))
        assertEquals((1 until 1) to (1 until 2), changedStretch(listOf(a, c), listOf(a, b, c)))
        assertEquals((1 until 2) to (1 until 1), changedStretch(listOf(a, b, c), listOf(a, c)))
        assertEquals((0 until 3) to (0 until 3), changedStretch(listOf(a, b, c), listOf(c, a, b)))
        assertEquals((2 until 2) to (2 until 3), changedStretch(listOf(a, b), listOf(a, b, d)))
    }

    @Test
    fun `adding to a queue the window reaches the end of appends to the player, within its bound`() {
        val queue = tracks(10)
        val added = queue + tracks(PLAYER_WINDOW, prefix = "n")
        val (window, edits) = followingEdit(queue, heldAt = 3, queue = added, row = 3)
        assertEquals(listOf(WindowEdit(10, 10, 10 until PLAYER_WINDOW)), edits)
        assertEquals(QueueWindow(0, PLAYER_WINDOW), window)
    }

    @Test
    fun `adding to a full window leaves the player alone`() {
        val queue = tracks(PLAYER_WINDOW)
        val (window, edits) = followingEdit(queue, heldAt = 3, queue = queue + tracks(5, prefix = "n"), row = 3)
        assertEquals(emptyList(), edits)
        assertEquals(QueueWindow(0, PLAYER_WINDOW), window)
    }

    @Test
    fun `a full window playing its queue's last row takes the added rows in by sliding`() {
        for (size in listOf(PLAYER_WINDOW, PLAYER_WINDOW + 20)) {
            val queue = tracks(size)
            val last = queue.lastIndex
            val held = windowAround(size, last)
            val grown = queue + tracks(3, prefix = "n")
            val (window, edits) = followingEdit(queue.subList(held.start, held.end), held.positionOf(last)!!, grown, last)
            assertEquals(emptyList(), edits)
            val to = window.slidFor(grown.size, last)!!
            val slide = slideEdits(window, to)
            assertEquals(grown.subList(to.start, to.end), grown.subList(window.start, window.end).edited(slide, grown))
            assertPlayingUntouched(slide, window.positionOf(last)!!)
            assertEquals(grown.size, to.end)
        }
    }

    @Test
    fun `a rearranged queue is followed with one edit after the playing row, which stays put`() {
        val queue = tracks(2_000)
        val held = queue.subList(950, 1_450)
        val heldAt = 50
        val next = playedNext(queue, 1_000, tracks(12, prefix = "n"))
        val (window, edits) = followingEdit(held, heldAt, next.queue, next.row)
        assertEquals(1, edits.size)
        assertPlayingUntouched(edits, heldAt)
        assertEquals(next.queue.subList(window.start, window.end), held.edited(edits, next.queue))
        assertEquals(PLAYER_WINDOW, window.size)
        assertEquals(heldAt, window.positionOf(next.row))
    }

    @Test
    fun `a track pulled from behind the playing row changes the history side too`() {
        val queue = tracks(30)
        val pulled = queue[5]
        val next = playedNext(queue, 10, listOf(pulled))
        assertEquals(9, next.row)
        val (window, edits) = followingEdit(queue, heldAt = 10, queue = next.queue, row = next.row)
        assertPlayingUntouched(edits, 10)
        assertEquals(next.queue.subList(window.start, window.end), queue.edited(edits, next.queue))
    }

    @Test
    fun `playing next keeps the order given, after the playing row, moving what is already queued`() {
        val queue = tracks(6)
        val new = track("n")
        val next = playedNext(queue, 1, listOf(new, queue[4], queue[1]))
        assertEquals(listOf("t0", "t1", "n", "t4", "t2", "t3", "t5"), ids(next.queue))
        assertEquals(1, next.row)
        assertEquals(
            listOf(QueueEdit.Moved(listOf("t4"), "n"), QueueEdit.PlayNext(listOf("n"))),
            next.told,
        )
        assertSame(new, next.queue[2])
    }

    @Test
    fun `a row is found again after a block move, the block's own rows included`() {
        val queue = tracks(8)
        val block = setOf(1, 5)
        for (insertAt in 0..6) {
            val (moved, landsAt) = movedAsBlock(queue, block, insertAt) ?: continue
            for (row in queue.indices) {
                assertSame(queue[row], moved[rowAfterBlockMove(row, block, landsAt)])
            }
        }
    }

    @Test
    fun `the saved queue is centred on the playing row`() {
        assertEquals(0 until 10, savedRows(10, 7))
        assertEquals(4_500 until 5_500, savedRows(20_000, 5_000))
        assertEquals(19_000 until 20_000, savedRows(20_000, 19_990))
        assertEquals(0 until AppSettings.MAX_SAVED_QUEUE, savedRows(20_000, -1))
    }
}

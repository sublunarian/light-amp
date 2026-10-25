package com.sublunar.amp.ui.screens

import com.sublunar.amp.data.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueKeyTest {

    private fun track(id: String) = Track(
        id = id, title = id, artist = "", album = "", albumArtist = "", albumId = null, coverArtId = null,
        durationMs = 0L, trackNumber = null, discNumber = null, year = null, playCount = 0, lastPlayedMs = 0L,
    )

    @Test
    fun `a key finds its own row, the same track queued twice included`() {
        val queue = listOf(track("a"), track("b"), track("a"))
        queue.forEachIndexed { i, t -> assertEquals(i, queueIndexOf(queueKey(i, t), queue)) }
    }

    @Test
    fun `an id with dashes in it still reads back`() {
        val queue = listOf(track("x"), track("f-1-2"))
        assertEquals(1, queueIndexOf(queueKey(1, queue[1]), queue))
    }

    @Test
    fun `a key whose place now holds another track, or is past the end, finds nothing`() {
        val queue = listOf(track("a"), track("b"))
        assertNull(queueIndexOf("0-b", queue))
        assertNull(queueIndexOf("2-a", queue))
        assertNull(queueIndexOf("junk", queue))
    }
}

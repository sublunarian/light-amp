package com.sublunar.amp.playback.output

import com.sublunar.amp.playback.wrappedIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviousRestartsTest {

    @Test
    fun `early in a song with one before it goes back`() {
        assertFalse(previousRestarts(positionMs = 1_000L, previousIndex = 2, queueSize = 5))
    }

    @Test
    fun `past the intro restarts`() {
        assertTrue(previousRestarts(positionMs = PREVIOUS_RESTARTS_AFTER_MS + 1, previousIndex = 2, queueSize = 5))
    }

    @Test
    fun `the first song restarts however early`() {
        assertTrue(previousRestarts(positionMs = 0L, previousIndex = -1, queueSize = 5))
        assertTrue(previousRestarts(positionMs = 2_000L, previousIndex = -1, queueSize = 1))
    }

    @Test
    fun `under repeat-all the first song goes back to the last`() {
        val previous = wrappedIndex(-1, size = 5, repeatQueue = true)
        assertEquals(4, previous)
        assertFalse(previousRestarts(positionMs = 1_000L, previousIndex = previous, queueSize = 5))
    }
}

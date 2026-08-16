package com.sublunar.amp.playback

import com.thelightphone.sdk.cast.DlnaState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class CastStallCounterTest {

    /**
     * The readings the Denon actually gave while a cast failed to start, taken
     * from a capture on 2026-08-15. The renderer had been handed a stream, was
     * not playing it, and was answering only intermittently.
     *
     * Restarting needs six in a row. Counting only STOPPED, this run peaked at
     * two and then went back to zero, which is why the cast was never recovered
     * and sat in silence until it was restarted by hand.
     */
    private val whenACastNeverStarted = listOf(
        DlnaState.UNKNOWN,
        DlnaState.UNKNOWN,
        DlnaState.UNKNOWN,
        DlnaState.STOPPED,
        DlnaState.STOPPED,
        DlnaState.UNKNOWN,
        DlnaState.UNKNOWN,
    )

    @Test
    fun `a renderer that flaps between stopped and unanswered still trips the restart`() {
        val stall = CastStallCounter()
        whenACastNeverStarted.forEach { stall.observe(it) }

        assertEquals(7, stall.notPlaying)
        assertTrue(stall.notPlaying >= 6, "six in a row is the restart threshold")
    }

    @Test
    fun `going quiet is never evidence a playing track has ended`() {
        val stall = CastStallCounter()
        whenACastNeverStarted.forEach { stall.observe(it) }

        // The last two readings were unanswered, so the strict count is back to
        // zero: advancing past a track demands the renderer actually say so.
        assertEquals(0, stall.stopped)
    }

    @Test
    fun `an unanswered poll between two stops does not add up to an ending`() {
        val stall = CastStallCounter()
        stall.observe(DlnaState.STOPPED)
        stall.observe(DlnaState.UNKNOWN)
        stall.observe(DlnaState.STOPPED)

        assertEquals(1, stall.stopped)
        assertEquals(3, stall.notPlaying)
    }

    @Test
    fun `a track that really ends reaches the advance threshold`() {
        val stall = CastStallCounter()
        stall.observe(DlnaState.PLAYING)
        stall.observe(DlnaState.STOPPED)
        stall.observe(DlnaState.STOPPED)

        assertEquals(2, stall.stopped)
    }

    @Test
    fun `playing clears both counts`() {
        val stall = CastStallCounter()
        repeat(5) { stall.observe(DlnaState.STOPPED) }
        stall.observe(DlnaState.PLAYING)

        assertEquals(0, stall.stopped)
        assertEquals(0, stall.notPlaying)
    }

    @Test
    fun `pausing is not a stall`() {
        val stall = CastStallCounter()
        repeat(5) { stall.observe(DlnaState.UNKNOWN) }
        stall.observe(DlnaState.PAUSED)

        assertEquals(0, stall.notPlaying)
    }

    @Test
    fun `transitioning earns patience rather than a restart`() {
        val stall = CastStallCounter()
        repeat(5) { stall.observe(DlnaState.UNKNOWN) }
        stall.observe(DlnaState.TRANSITIONING)

        assertEquals(0, stall.stopped)
        assertEquals(0, stall.notPlaying)
    }
}

package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How long a source's downloads wait after its server stops answering, and
 * when the worker stops trying it at all. The rule that matters most is the
 * one from before this existed: one blip must never park downloads.
 */
class SourceBackOffTest {

    private var clock = 0L

    /**
     * [times] failed tries of [sourceId] in a row, from a clean start, each
     * the moment the last one's wait ran out — the soonest the worker could
     * have tried. What each try said about parking; the clock is left at the
     * moment the next try would be.
     */
    private fun SourceBackOff.failInARow(sourceId: String, times: Int): List<Boolean> =
        (1..times).map { count ->
            val parks = fail(sourceId, clock)
            clock += SourceBackOff.waitAfter(count)
            parks
        }

    @Test
    fun `waits double from two seconds to a minute`() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L),
            (1..7).map { SourceBackOff.waitAfter(it) },
        )
    }

    @Test
    fun `a failed try holds the source until its wait runs out`() {
        val backOff = SourceBackOff()
        assertFalse(backOff.waiting("a", 0L))
        backOff.fail("a", 0L)
        assertTrue(backOff.waiting("a", 1_999L))
        assertFalse(backOff.waiting("a", 2_000L))
    }

    @Test
    fun `fourteen failures in a row never park, the fifteenth does`() {
        val backOff = SourceBackOff()
        assertEquals(List(14) { false } + true, backOff.failInARow("a", 15))
    }

    @Test
    fun `parking takes ten minutes of trying at the least`() {
        val backOff = SourceBackOff()
        val firstAt = clock
        backOff.failInARow("a", SourceBackOff.PARK_AFTER_FAILURES - 1)
        // The soonest the fifteenth try can happen.
        assertTrue(backOff.waiting("a", clock - 1))
        assertTrue(backOff.fail("a", clock))
        assertTrue(clock - firstAt >= 10 * 60 * 1000L, "parked after ${clock - firstAt} ms")
    }

    @Test
    fun `one success anywhere in the run starts the count again`() {
        val backOff = SourceBackOff()
        assertFalse(backOff.failInARow("a", 13).any { it })
        // The fourteenth, and the success comes while it is still waiting.
        assertFalse(backOff.fail("a", clock))
        backOff.clear("a")
        assertFalse(backOff.waiting("a", clock))
        assertFalse(backOff.failInARow("a", 14).any { it })
    }

    @Test
    fun `a hold for everyone forgets every run`() {
        val backOff = SourceBackOff()
        backOff.failInARow("a", 14)
        backOff.fail("b", clock)
        backOff.clearAll()
        assertFalse(backOff.waiting("b", clock))
        assertFalse(backOff.fail("a", clock))
    }

    @Test
    fun `one source failing never parks or holds another`() {
        val backOff = SourceBackOff()
        assertTrue(backOff.failInARow("a", 15).last())
        assertTrue(backOff.waiting("a", clock - 1))
        assertFalse(backOff.waiting("b", clock - 1))
        assertFalse(backOff.fail("b", clock))
    }
}

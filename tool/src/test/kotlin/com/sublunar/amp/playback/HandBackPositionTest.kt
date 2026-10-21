package com.sublunar.amp.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/** Where the phone picks a song up from a far end that reports its place once a second — see carriedOn. */
class HandBackPositionTest {

    @Test
    fun `a playing far end is carried on by the time since it reported`() {
        assertEquals(60_800L, carriedOn(reportedMs = 60_000L, sinceReportMs = 800L, playing = true, durationMs = 200_000L))
    }

    @Test
    fun `a paused one is where it said`() {
        assertEquals(60_000L, carriedOn(reportedMs = 60_000L, sinceReportMs = 800L, playing = false, durationMs = 200_000L))
    }

    @Test
    fun `a far end gone quiet is carried no more than the cap`() {
        assertEquals(62_000L, carriedOn(reportedMs = 60_000L, sinceReportMs = 30_000L, playing = true, durationMs = 200_000L))
    }

    @Test
    fun `never past the end of the song, nor back from a clock gone wrong`() {
        assertEquals(200_000L, carriedOn(reportedMs = 199_500L, sinceReportMs = 1_500L, playing = true, durationMs = 200_000L))
        assertEquals(60_000L, carriedOn(reportedMs = 60_000L, sinceReportMs = -5_000L, playing = true, durationMs = 200_000L))
    }
}

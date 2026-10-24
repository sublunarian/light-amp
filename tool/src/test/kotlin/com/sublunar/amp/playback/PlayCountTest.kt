package com.sublunar.amp.playback

import com.sublunar.amp.data.Track
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayCountTest {

    private fun track(id: String) = Track(
        id = id, title = id, artist = "", album = "", albumArtist = "", albumId = null, coverArtId = null,
        durationMs = 0L, trackNumber = null, discNumber = null, year = null, playCount = 0, lastPlayedMs = 0L,
    )

    private val a = track("a")
    private val b = track("b")

    @Test
    fun `the first song heard is a play`() {
        assertTrue(startsAPlay(null, listOf(a), 0, a))
    }

    @Test
    fun `another song is a new play, even one with the same id`() {
        val q = listOf(a, b)
        assertTrue(startsAPlay(PlayAt(q, 0, a), q, 1, b))
        val again = track("a")
        assertTrue(startsAPlay(PlayAt(listOf(a, again), 0, a), listOf(a, again), 1, again))
    }

    @Test
    fun `the same song queued twice in a row is two plays`() {
        val q = listOf(a, a)
        assertTrue(startsAPlay(PlayAt(q, 0, a), q, 1, a))
    }

    @Test
    fun `an edit that moves the playing song is the same play`() {
        val before = listOf(b, a)
        val after = listOf(a)
        assertFalse(startsAPlay(PlayAt(before, 1, a), after, 0, a))
        assertFalse(startsAPlay(PlayAt(before, 1, a), before + b, 1, a))
    }

    @Test
    fun `nothing moving is the same play`() {
        val q = listOf(a, b)
        assertFalse(startsAPlay(PlayAt(q, 0, a), q, 0, a))
    }

    @Test
    fun `a song read near its end and then near its start ended into itself`() {
        assertTrue(endedIntoItself(beforeMs = 239_800L, nowMs = 200L, lengthMs = 240_000L))
        assertTrue(endedIntoItself(beforeMs = 238_000L, nowMs = 1_500L, lengthMs = 240_000L))
    }

    @Test
    fun `a seek back from the middle is not a song ending`() {
        assertFalse(endedIntoItself(beforeMs = 120_000L, nowMs = 0L, lengthMs = 240_000L))
        assertFalse(endedIntoItself(beforeMs = 239_800L, nowMs = 60_000L, lengthMs = 240_000L))
    }

    @Test
    fun `a song of a few seconds playing on is not a song ending`() {
        assertFalse(endedIntoItself(beforeMs = 2_500L, nowMs = 2_750L, lengthMs = 5_000L))
        assertTrue(endedIntoItself(beforeMs = 4_900L, nowMs = 100L, lengthMs = 5_000L))
    }

    @Test
    fun `without a length nothing can be said`() {
        assertFalse(endedIntoItself(beforeMs = 239_800L, nowMs = 0L, lengthMs = 0L))
    }
}

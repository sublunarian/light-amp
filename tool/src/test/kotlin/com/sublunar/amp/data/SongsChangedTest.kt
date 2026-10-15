package com.sublunar.amp.data

import com.sublunar.amp.data.db.TrackEntity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When an album fetched again is written back — see songsChanged. Every write
 * reads the whole library again, so one that changes nothing is the thing to
 * avoid, and a play counted here first is the commonest way to look changed.
 */
class SongsChangedTest {

    private fun row(id: String, title: String = id, plays: Int = 0, playedMs: Long = 0L) = TrackEntity(
        id = id, title = title, artist = "", album = "", albumArtist = "", albumId = "al",
        coverArtId = null, durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = plays, lastPlayedMs = playedMs, liked = false,
    )

    @Test
    fun `the same songs in another order are no change`() {
        assertFalse(songsChanged(listOf(row("1"), row("2")), listOf(row("2"), row("1"))))
    }

    @Test
    fun `a song added, gone or retitled is a change`() {
        val cached = listOf(row("1"), row("2"))
        assertTrue(songsChanged(cached, cached + row("3")))
        assertTrue(songsChanged(cached, listOf(row("1"))))
        assertTrue(songsChanged(cached, listOf(row("1"), row("2", title = "new"))))
    }

    @Test
    fun `a play counted here that the server has stamped differently is no change`() {
        val cached = listOf(row("1", plays = 4, playedMs = 1_700_000_000_123L))
        assertFalse(songsChanged(cached, listOf(row("1", plays = 4, playedMs = 1_700_000_000_000L))))
        assertFalse(songsChanged(cached, listOf(row("1", plays = 3, playedMs = 1_600_000_000_000L))))
    }

    @Test
    fun `a play somewhere else is a change`() {
        val cached = listOf(row("1", plays = 4, playedMs = 1_700_000_000_123L))
        assertTrue(songsChanged(cached, listOf(row("1", plays = 5, playedMs = 1_700_000_500_000L))))
    }
}

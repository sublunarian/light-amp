package com.sublunar.amp.playback

import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.TimelineState.PAUSED
import com.sublunar.amp.data.TimelineState.PLAYING
import com.sublunar.amp.data.TimelineState.STOPPED
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * What the server is told as the song changes and as playback ends — issue
 * #20. Two servers' worth of ids: Plex's, one session ("S") for the whole
 * listening; Jellyfin's, one per song of a queue ("q1:A").
 */
class SessionHandOverTest {

    private val sent = mutableListOf<TimelineReport>()
    private val newsFlags = mutableListOf<Boolean>()
    private var mayGo = true

    private val sessions = SessionHandOver { report, news ->
        if (mayGo) {
            sent += report
            newsFlags += news
        }
        mayGo
    }

    private fun said() = sent.map { "${it.state} ${it.trackId} @${it.sessionId}" }

    /** A song announced as playing, and the record of it cleared, so each test reads only what follows. */
    private fun playing(sessionId: String, trackId: String, positionMs: Long = 30_000L, durationMs: Long = 200_000L) {
        sessions.report(TimelineReport(sessionId, trackId, PLAYING, positionMs, durationMs))
        sent.clear()
        newsFlags.clear()
    }

    // --- One session for the whole listening (Plex) --------------------------

    @Test
    fun `a tap on a song in another list moves the one session to it`() {
        playing("S", "A")
        sessions.songIs("S", "B", live = true, durationMs = 180_000L)
        assertEquals(listOf("PLAYING B @S"), said())
        assertEquals(TimelineReport("S", "B", PLAYING, 0L, 180_000L), sent.single())
    }

    @Test
    fun `next and gapless are the same session changing song, with no stop between`() {
        playing("S", "A")
        sessions.songIs("S", "B", live = true, durationMs = 180_000L)
        sessions.songIs("S", "C", live = true, durationMs = 240_000L)
        assertEquals(listOf("PLAYING B @S", "PLAYING C @S"), said())
        assertTrue(sent.none { it.state == STOPPED })
    }

    @Test
    fun `the same song again in the same session says nothing new`() {
        playing("S", "A")
        sessions.songIs("S", "A", live = true, durationMs = 200_000L)
        assertEquals(emptyList<String>(), said())
    }

    @Test
    fun `next while paused keeps the session, paused, on the new song`() {
        sessions.report(TimelineReport("S", "A", PAUSED, 30_000L, 200_000L))
        sent.clear()
        sessions.songIs("S", "B", live = false, durationMs = 180_000L)
        assertEquals(listOf("PAUSED B @S"), said())
    }

    @Test
    fun `stop ends the session where playback got to`() {
        playing("S", "A")
        sessions.close("A", positionMs = 61_000L, durationMs = 200_000L)
        assertEquals(TimelineReport("S", "A", STOPPED, 61_000L, 200_000L), sent.single())
        assertNull(sessions.openSession)
        // Nothing left to stop a second time.
        sessions.close("A", 61_000L, 200_000L)
        assertEquals(1, sent.size)
    }

    // --- One session per song (Jellyfin) -------------------------------------

    /**
     * The reporter's path. The tap mints the new queue's ids before the old
     * song is stopped; the stop must still go out under the id the old song
     * was announced as, never one worked out from the new queue.
     */
    @Test
    fun `a tap on another list stops the old song under the id it was announced as`() {
        playing("q1:A", "A")
        sessions.songIs("q2:B", "B", live = true, durationMs = 180_000L)
        assertEquals(listOf("STOPPED A @q1:A", "PLAYING B @q2:B"), said())
        // From its start, with the length it was announced at, as a song left behind always was.
        assertEquals(TimelineReport("q1:A", "A", STOPPED, 0L, 200_000L), sent.first())
    }

    @Test
    fun `next stops the old song before the new one is announced`() {
        playing("q1:A", "A")
        sessions.songIs("q1:B", "B", live = true, durationMs = 180_000L)
        assertEquals(listOf("STOPPED A @q1:A", "PLAYING B @q1:B"), said())
    }

    @Test
    fun `the same song from a new list is a new session`() {
        playing("q1:A", "A")
        sessions.songIs("q2:A", "A", live = true, durationMs = 200_000L)
        assertEquals(listOf("STOPPED A @q1:A", "PLAYING A @q2:A"), said())
    }

    /** A heartbeat, a press of play, anything reported under another id: what was open is stopped first. */
    @Test
    fun `any report under another session stops the open one first`() {
        playing("q1:A", "A")
        sessions.report(TimelineReport("q2:B", "B", PLAYING, 4_000L, 180_000L))
        assertEquals(listOf("STOPPED A @q1:A", "PLAYING B @q2:B"), said())
    }

    // --- Either -----------------------------------------------------------------

    @Test
    fun `a heartbeat is not news`() {
        playing("S", "A")
        sessions.report(TimelineReport("S", "A", PLAYING, 40_000L, 200_000L))
        assertEquals(listOf("PLAYING A @S"), said())
        assertEquals(listOf(false), newsFlags)
    }

    @Test
    fun `a restored queue says nothing until it is played`() {
        sessions.songIs("S", "A", live = false, durationMs = 200_000L)
        assertEquals(emptyList<String>(), said())
        sessions.report(TimelineReport("S", "A", PLAYING, 151_000L, 200_000L))
        assertEquals(listOf("PLAYING A @S"), said())
        assertEquals(151_000L, sent.single().positionMs)
    }

    /** A back-out closed the session while the service played on; the reopen finds it part-way through. */
    @Test
    fun `a reopen onto a song still playing opens its session where it has got to`() {
        playing("S", "A")
        sessions.close()
        sent.clear()
        sessions.songIs("S", "B", live = true, durationMs = 180_000L, positionMs = 95_000L)
        assertEquals(TimelineReport("S", "B", PLAYING, 95_000L, 180_000L), sent.single())
        // Never past the song's end, whatever the reading says.
        playing("q1:C", "C")
        sessions.songIs("q1:D", "D", live = true, durationMs = 60_000L, positionMs = 75_000L)
        assertEquals(60_000L, sent.last().positionMs)
    }

    @Test
    fun `a song moved to after a stop opens nothing until it plays`() {
        playing("S", "A")
        sessions.close()
        sent.clear()
        sessions.songIs("S", "B", live = false, durationMs = 180_000L)
        assertEquals(emptyList<String>(), said())
    }

    /** Leaving, the queue running out, a park: what is open ends, from its start if it isn't the song playback is on. */
    @Test
    fun `closing stops the open session as it was announced, whatever the song now is`() {
        playing("q1:A", "A")
        sessions.close("B", positionMs = 5_000L, durationMs = 180_000L)
        assertEquals(TimelineReport("q1:A", "A", STOPPED, 0L, 200_000L), sent.single())
    }

    @Test
    fun `closing with nothing open sends nothing`() {
        sessions.close("A", 1_000L, 200_000L)
        assertEquals(emptyList<String>(), said())
    }

    /**
     * Held back by the data mode: nothing goes, and the session stays as it
     * was, so the first report that may go stops the right one.
     */
    @Test
    fun `a report that can't go leaves the open session to be stopped later`() {
        playing("q1:A", "A")
        mayGo = false
        sessions.songIs("q1:B", "B", live = true, durationMs = 180_000L)
        assertEquals(emptyList<String>(), said())
        assertEquals("A", sessions.openSession?.trackId)
        mayGo = true
        sessions.report(TimelineReport("q1:C", "C", PLAYING, 0L, 240_000L))
        assertEquals(listOf("STOPPED A @q1:A", "PLAYING C @q1:C"), said())
    }

    @Test
    fun `a stop that can't go is tried again at the next close`() {
        playing("S", "A")
        mayGo = false
        sessions.close()
        assertEquals("A", sessions.openSession?.trackId)
        mayGo = true
        sessions.close()
        assertEquals(listOf("STOPPED A @S"), said())
        assertNull(sessions.openSession)
    }

    @Test
    fun `pausing and playing again are news, on the same session`() {
        playing("S", "A")
        sessions.report(TimelineReport("S", "A", PAUSED, 31_000L, 200_000L))
        sessions.report(TimelineReport("S", "A", PLAYING, 31_000L, 200_000L))
        assertEquals(listOf("PAUSED A @S", "PLAYING A @S"), said())
        assertEquals(listOf(true, true), newsFlags)
        assertEquals(TimelineState.PLAYING, sessions.openSession?.state)
    }
}

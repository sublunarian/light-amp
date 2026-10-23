package com.sublunar.amp.playback.output.dlna

import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.StreamFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What decides the format a renderer is sent and the track it is armed with
 * next. A wrong answer here doesn't fail: the renderer stalls in silence, or
 * the queue stops a track early, so the answers are pinned.
 */
class DlnaFormatsTest {

    @Test
    fun `a container is matched under any of the names it goes by`() {
        assertTrue(sinkAccepts(setOf("audio/x-m4a"), "audio/mp4"))
        assertTrue(sinkAccepts(setOf("audio/mp4"), "audio/aac"))
        assertTrue(sinkAccepts(setOf("audio/mp3"), "audio/mpeg"))
        assertTrue(sinkAccepts(setOf("audio/x-flac"), "audio/flac"))
        assertTrue(sinkAccepts(setOf("application/ogg"), "audio/opus"))
        assertTrue(sinkAccepts(setOf("audio/x-wav"), "audio/wav"))
    }

    @Test
    fun `a parameter after the type is not part of it`() {
        assertTrue(sinkAccepts(setOf("audio/flac;rate=96000"), "audio/flac"))
        assertTrue(sinkAccepts(setOf(" audio/mpeg ; dlna"), "audio/mp3"))
    }

    @Test
    fun `a sink that doesn't list the container turns it away`() {
        assertFalse(sinkAccepts(setOf("audio/mpeg", "audio/mp4"), "audio/flac"))
        assertFalse(sinkAccepts(setOf("audio/mpeg"), "audio/ogg"))
        assertFalse(sinkAccepts(setOf("audio/mpeg"), "audio/x-aiff"))
    }

    @Test
    fun `a renderer that lists nothing, or a stream of unknown container, is taken at its word`() {
        assertTrue(sinkAccepts(emptySet(), "audio/flac"))
        assertTrue(sinkAccepts(setOf("audio/mpeg"), "*"))
    }

    @Test
    fun `each format is assumed to arrive as its usual container`() {
        assertEquals("audio/mpeg", assumedMime(StreamFormat.MP3))
        assertEquals("audio/ogg", assumedMime(StreamFormat.OPUS))
        assertEquals("audio/flac", assumedMime(StreamFormat.FLAC))
        assertEquals("*", assumedMime(StreamFormat.RAW))
    }

    @Test
    fun `the next track follows the repeat mode, and only repeat-all wraps`() {
        assertEquals(3, nextCastIndex(2, 5, RepeatMode.OFF))
        assertNull(nextCastIndex(4, 5, RepeatMode.OFF))
        assertEquals(0, nextCastIndex(4, 5, RepeatMode.QUEUE))
        assertEquals(3, nextCastIndex(2, 5, RepeatMode.QUEUE))
        assertEquals(4, nextCastIndex(4, 5, RepeatMode.TRACK))
        assertEquals(0, nextCastIndex(0, 1, RepeatMode.QUEUE))
    }

    @Test
    fun `an empty queue has no next track whatever the mode`() {
        for (mode in RepeatMode.entries) assertNull(nextCastIndex(0, 0, mode))
    }

    @Test
    fun `a stop a minute into a song is a pause there, not its end`() {
        assertTrue(stoppedPartWay(heardMs = 77_000L, totalMs = 163_000L))
    }

    @Test
    fun `a stop in the last seconds is the song ending`() {
        assertFalse(stoppedPartWay(heardMs = 160_000L, totalMs = 163_000L))
        assertFalse(stoppedPartWay(heardMs = 163_000L, totalMs = 163_000L))
    }

    @Test
    fun `without a length, or never heard, a stop is the end`() {
        assertFalse(stoppedPartWay(heardMs = 77_000L, totalMs = 0L))
        assertFalse(stoppedPartWay(heardMs = -1L, totalMs = 163_000L))
    }
}

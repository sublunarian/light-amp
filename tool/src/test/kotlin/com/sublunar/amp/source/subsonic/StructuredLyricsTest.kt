package com.sublunar.amp.source.subsonic

import kotlin.test.Test
import kotlin.test.assertEquals

/** OpenSubsonic's structured lyrics: an offset shifts every line, positive meaning sooner. */
class StructuredLyricsTest {

    private fun lyric(offset: Long?) = StructuredLyricDto(
        synced = true,
        offset = offset,
        line = listOf(
            LyricLineDto(start = 20_000, value = "Two"),
            LyricLineDto(start = 10_000, value = " One "),
            LyricLineDto(start = 15_000, value = " "),
            LyricLineDto(start = null, value = "Untimed"),
            LyricLineDto(start = 100, value = "Intro"),
        ),
    )

    @Test
    fun `timed lines come in order, without blanks or untimed ones`() {
        val lines = lyric(offset = null).timedLines()
        assertEquals(listOf("Intro", "One", "Two"), lines.map { it.text })
        assertEquals(listOf(100L, 10_000L, 20_000L), lines.map { it.timeMs })
    }

    @Test
    fun `a positive offset shows the words sooner, never before the start`() {
        assertEquals(listOf(0L, 9_500L, 19_500L), lyric(offset = 500).timedLines().map { it.timeMs })
    }

    @Test
    fun `a negative offset shows them later`() {
        assertEquals(listOf(200L, 10_100L, 20_100L), lyric(offset = -100).timedLines().map { it.timeMs })
    }
}

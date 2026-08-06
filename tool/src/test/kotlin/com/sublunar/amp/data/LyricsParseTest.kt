package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The LRC parser is what turns lrclib's payload into a highlightable timeline. */
class LyricsParseTest {

    @Test
    fun `parses mm ss xx stamps into milliseconds`() {
        val parsed = LyricsRepository.parseLrc(
            """
            [00:12.34]First line
            [01:05.7]Second line
            [02:00]Third line
            """.trimIndent(),
        )
        assertTrue(parsed!!.synced)
        assertEquals(listOf(12_340L, 65_700L, 120_000L), parsed.lines.map { it.timeMs })
        assertEquals("First line", parsed.lines[0].text)
    }

    @Test
    fun `a line with several stamps becomes several lines`() {
        val parsed = LyricsRepository.parseLrc("[00:10.00][01:10.00]Chorus")!!
        assertEquals(listOf(10_000L, 70_000L), parsed.lines.map { it.timeMs })
        assertTrue(parsed.lines.all { it.text == "Chorus" })
    }

    @Test
    fun `metadata tags and instrumental gaps are dropped`() {
        val parsed = LyricsRepository.parseLrc(
            """
            [ar:Some Artist]
            [ti:Some Title]
            [00:05.00]
            [00:08.00]Only real line
            """.trimIndent(),
        )!!
        assertEquals(1, parsed.lines.size)
        assertEquals("Only real line", parsed.lines[0].text)
    }

    @Test
    fun `output is ordered by time even when the source is not`() {
        val parsed = LyricsRepository.parseLrc("[00:30.00]Later\n[00:10.00]Earlier")!!
        assertEquals(listOf("Earlier", "Later"), parsed.lines.map { it.text })
    }

    @Test
    fun `plain text with no stamps is not synced lyrics`() {
        assertNull(LyricsRepository.parseLrc("Just some words\nAnd some more"))
    }
}

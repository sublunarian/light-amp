package com.sublunar.amp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatinLettersTest {

    private fun lines(vararg text: String) = text.map { LyricLine(null, it) }

    @Test
    fun `Latin lyrics, accents and all, need nothing`() {
        assertFalse(LatinLetters.needed(lines("Ça plane pour moi", "Smells like teen spirit", "1, 2, 3 — ¡olé!")))
    }

    @Test
    fun `one line in another script is enough to offer it`() {
        assertTrue(LatinLetters.needed(lines("Intro", "Привет")))
        assertTrue(LatinLetters.needed(lines("사랑해")))
        assertTrue(LatinLetters.needed(lines("ሰላም")))
    }
}

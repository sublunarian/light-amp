package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** A classical album's shared work names — see [workTitles]. */
class WorkTitlesTest {

    @Test
    fun `tracks sharing a work keep only what follows it`() {
        val titles = workTitles(
            listOf("Goldberg Variations, BWV 988: I. Aria", "Goldberg Variations, BWV 988: II. Variatio 1 a 1 clav."),
        )
        assertEquals(
            listOf(
                WorkTitle("Goldberg Variations, BWV 988", "I. Aria"),
                WorkTitle("Goldberg Variations, BWV 988", "II. Variatio 1 a 1 clav."),
            ),
            titles,
        )
    }

    @Test
    fun `a lone colon is part of the title`() {
        val titles = workTitles(listOf("Intro", "Interlude: Rain", "Outro"))
        assertEquals(listOf(null, null, null), titles.map { it.work })
        assertEquals("Interlude: Rain", titles[1].title)
    }

    @Test
    fun `each work is its own run`() {
        val works = workTitles(listOf("A: 1", "A: 2", "Song", "B: 1", "B: 2")).map { it.work }
        assertEquals(listOf("A", "A", null, "B", "B"), works)
    }
}

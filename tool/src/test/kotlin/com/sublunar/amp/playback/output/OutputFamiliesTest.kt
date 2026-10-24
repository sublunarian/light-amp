package com.sublunar.amp.playback.output

import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.source.Providers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Output page lists every family without naming one, so the order it
 * asks and shows them in, and the ids its rows are keyed by, are pinned here.
 */
class OutputFamiliesTest {

    private fun family(name: String) = object : OutputFamily {
        override val id = name
        override val logTag = "Amp"
        override val reportsItself = false
        override val needsLan = false
        override fun create(host: OutputHost): Output = error("never made here")
    }

    @Test
    fun `a source's own outputs are asked first and shown after the phone's`() {
        val players = family("players")
        val speakers = family("speakers")
        val other = family("other")
        val families = OutputFamilies(ofSource = listOf(players), ofPhone = listOf(speakers, other))
        assertEquals(listOf(players, speakers, other), families.searchOrder)
        assertEquals(listOf(speakers, other, players), families.listOrder)
    }

    @Test
    fun `every family there is has an id of its own`() {
        val all = Outputs.phone + Providers.all.flatMap { it.outputFamilies }
        val ids = all.map { it.id }
        assertTrue(ids.none { it.isBlank() }, "$ids")
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `only a Plex source offers outputs of its own`() {
        assertEquals(
            listOf(SourceKind.PLEX),
            Providers.all.filter { it.outputFamilies.isNotEmpty() }.map { it.kind },
        )
    }
}

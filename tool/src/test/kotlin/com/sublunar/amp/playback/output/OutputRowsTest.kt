package com.sublunar.amp.playback.output

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One row per speaker on the Output page: a device two families both offer
 * is listed once, under the first of them that can play what's on.
 */
class OutputRowsTest {

    private fun family(name: String) = object : OutputFamily {
        override val id = name
        override val logTag = "Amp"
        override val reportsItself = false
        override val needsLan = true
        override fun create(host: OutputHost): Output = error("never made here")
    }

    private class Target(
        override val id: String,
        override val name: String = id,
        override val address: String? = null,
        override val hardwareId: String? = null,
    ) : OutputTarget

    private val dlna = family("dlna")
    private val airplay = family("airplay")
    private val families = OutputFamilies(ofSource = emptyList(), ofPhone = listOf(dlna, airplay))

    // The Denon as each family finds it: the same address, and the MAC on both.
    private val denonDlna = Target("uuid:4d72d99f-0c85-12ca-0080-0005cdd62096", "Denon AVR-X1500H", "192.168.1.30", "0005cdd62096")
    private val denonAirPlay = Target("0005cdd62096", "Denon AVR-X1500H", "192.168.1.30", "0005cdd62096")
    private val airport = Target("54e43a010203", "Den Shelf", "192.168.1.52", "54e43a010203")

    private val found = mapOf(dlna.id to listOf(denonDlna), airplay.id to listOf(denonAirPlay, airport))

    private fun names(rows: List<Pair<OutputFamily, OutputTarget>>) = rows.map { (f, t) -> "${f.id}:${t.name}" }

    @Test
    fun `a speaker both families offer is listed once, under the first`() {
        val rows = families.rows(found, active = null) { true }
        assertEquals(listOf("dlna:Denon AVR-X1500H", "airplay:Den Shelf"), names(rows))
    }

    @Test
    fun `where the first can't play the source, the second has the row`() {
        val rows = families.rows(found, active = null) { it !== dlna }
        assertEquals(listOf("airplay:Denon AVR-X1500H", "airplay:Den Shelf"), names(rows))
    }

    @Test
    fun `what is playing keeps its row, and its twin gives way`() {
        val active = ActiveOutput(airplay, denonAirPlay, NoOutput)
        val rows = families.rows(found, active) { true }
        assertEquals(listOf("airplay:Denon AVR-X1500H", "airplay:Den Shelf"), names(rows))
    }

    @Test
    fun `what is playing is listed before any search has found it`() {
        val active = ActiveOutput(dlna, denonDlna, NoOutput)
        val rows = families.rows(emptyMap(), active) { true }
        assertEquals(listOf("dlna:Denon AVR-X1500H"), names(rows))
    }

    @Test
    fun `one address is one device only when each family has one target there`() {
        // A computer offering two AirPlay speakers and one DLNA renderer at one address, no MACs alike.
        val box = "192.168.1.9"
        val renderer = Target("uuid:random-0000", "Box", box, "aaaaaaaaaaaa")
        val kitchen = Target("111111111111", "Kitchen", box, "111111111111")
        val porch = Target("222222222222", "Porch", box, "222222222222")
        val rows = families.rows(mapOf(dlna.id to listOf(renderer), airplay.id to listOf(kitchen, porch)), null) { true }
        assertEquals(listOf("dlna:Box", "airplay:Kitchen", "airplay:Porch"), names(rows))
    }

    @Test
    fun `one address and one target in each family is one device, MAC or not`() {
        val tv = Target("uuid:philips-tv", "TV", "192.168.1.40", null)
        val tvAirPlay = Target("abcdefabcdef", "TV", "192.168.1.40", "abcdefabcdef")
        val rows = families.rows(mapOf(dlna.id to listOf(tv), airplay.id to listOf(tvAirPlay)), null) { true }
        assertEquals(listOf("dlna:TV"), names(rows))
    }

    @Test
    fun `two renderers of one family are never merged`() {
        val main = Target("uuid:a", "Zone 1", "192.168.1.30", "0005cdd62096")
        val zone2 = Target("uuid:b", "Zone 2", "192.168.1.30", "0005cdd62096")
        val rows = families.rows(mapOf(dlna.id to listOf(main, zone2)), null) { true }
        assertEquals(listOf("dlna:Zone 1", "dlna:Zone 2"), names(rows))
    }

    private object NoOutput : Output {
        override fun play() {}
        override fun pause() {}
        override fun seekTo(ms: Long) {}
        override fun next() {}
        override fun previous() {}
        override fun jumpTo(index: Int) {}
        override fun setVolume(level: Float) {}
        override fun newQueue() {}
        override val volumeControl get() = error("unused")
        override suspend fun find(): List<OutputTarget> = emptyList()
        override fun connect(target: OutputTarget, fromMs: Long, play: Boolean) {}
        override fun disconnect(target: OutputTarget, why: Disconnect) {}
        override fun stopPlayback() {}
    }
}

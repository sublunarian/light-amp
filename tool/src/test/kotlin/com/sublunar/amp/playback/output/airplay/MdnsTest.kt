package com.sublunar.amp.playback.output.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real replies to Amp's question, captured from a Denon AVR-X1500H and an
 * AirPort Express on AirPlay 2 firmware, with the MAC addresses, keys, IP
 * addresses and the AirPort's name changed (to strings of the same length,
 * so the packets' name compression still points where it did).
 */
class MdnsTest {

    @Test
    fun `a Denon's reply carries the whole service`() {
        val services = Mdns.assemble("_raop._tcp.local", Mdns.parse(DENON, DENON.size)).services
        val denon = services.single()
        assertEquals("0005CD0A0B0C@Denon AVR-X1500H._raop._tcp.local", denon.instance)
        assertEquals("Denon-AVR-X1500H.local", denon.host)
        assertEquals(7000, denon.port)
        assertEquals("192.168.1.30", denon.address)
        assertEquals("0,4", denon.txt["et"])
        assertEquals("0,1", denon.txt["cn"])
        assertEquals("0,1,2", denon.txt["md"])
        assertEquals("UDP", denon.txt["tp"])
        assertEquals("Denon AVR-X1500H", denon.txt["am"])
    }

    @Test
    fun `an AirPort's self-assigned address is passed over for its real one`() {
        val airport = Mdns.assemble("_raop._tcp.local", Mdns.parse(AIRPORT, AIRPORT.size)).services.single()
        assertEquals("54E43A010203@Den Shelf._raop._tcp.local", airport.instance)
        assertEquals("192.168.1.52", airport.address)
        assertEquals("AirPort10,115", airport.txt["am"])
        assertEquals("2", airport.txt["md"])
    }

    @Test
    fun `a reply with only the pointer asks for the rest`() {
        val pointerOnly = listOf(Mdns.Record("_raop._tcp.local", Mdns.TYPE_PTR, target = "AA@Shelf._raop._tcp.local"))
        val assembled = Mdns.assemble("_raop._tcp.local", pointerOnly)
        assertTrue(assembled.services.isEmpty())
        assertEquals(
            listOf("AA@Shelf._raop._tcp.local" to Mdns.TYPE_SRV, "AA@Shelf._raop._tcp.local" to Mdns.TYPE_TXT),
            assembled.missing,
        )
    }

    @Test
    fun `the question asks for a unicast answer`() {
        val q = Mdns.query(listOf("_raop._tcp.local" to Mdns.TYPE_PTR))
        val tail = q.takeLast(4).map { it.toInt() and 0xff }
        assertEquals(listOf(0, 12, 0x80, 0x01), tail)
        assertEquals(1, ((q[4].toInt() and 0xff) shl 8) or (q[5].toInt() and 0xff))
    }

    @Test
    fun `a speaker named with a dot is asked about by its own label`() {
        assertEquals(
            listOf("0011AABBCCDD@Hi-Fi 2.0", "_raop", "_tcp", "local"),
            Mdns.labelsOf("0011AABBCCDD@Hi-Fi 2.0._raop._tcp.local", "_raop._tcp.local"),
        )
        assertEquals(listOf("Den-Shelf", "local"), Mdns.labelsOf("Den-Shelf.local", "_raop._tcp.local"))
    }

    @Test
    fun `text records keep the first of a key and ignore empty strings`() {
        val bytes = byteArrayOf(4, 'a'.b, '='.b, '1'.b, '2'.b, 0, 3, 'a'.b, '='.b, '9'.b, 2, 'p'.b, 'w'.b)
        assertEquals(mapOf("a" to "12", "pw" to ""), Mdns.txtOf(bytes, 0, bytes.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a pointer loop is refused`() {
        // One answer whose name points at itself.
        val loop = hex("000084000000000100000000c00c")
        Mdns.parse(loop, loop.size)
    }

    private val Char.b: Byte get() = code.toByte()

    private companion object {
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        val DENON = hex(
            "000084000001000100000004055f72616f70045f746370056c6f63616c00000c8001c00c000c00010000000a00201d" +
                "3030303543443041304230434044656e6f6e204156522d583135303048c00cc02e002100010000000a0019000000" +
                "001b581044656e6f6e2d4156522d583135303048c017c02e001000010000000a00c006636e3d302c310764613d74" +
                "7275650665743d302c341766743d307834343546384130302c3078383031433334301066763d7032302e332e3133" +
                "392e313733086d643d302c312c3213616d3d44656e6f6e204156522d5831353030480673663d3078340674703d55" +
                "445008766e3d36353533370876733d3336362e3043706b3d64373664343333306631343436626561623063313166" +
                "646563623931636533373562633866626263626465356330393934313634643833393966373637633435c0600001" +
                "00010000000a0004c0a8011ec060001c00010000000a0010fe800000000000000205cdfffe0a0b0c",
        )

        val AIRPORT = hex(
            "000084000001000100000005055f72616f70045f746370056c6f63616c00000c8001c00c000c00010000000a0019" +
                "163534453433413031303230334044656e205368656c66c00cc02e002100010000000a0012000000001b58094465" +
                "6e2d5368656c66c017c02e001000010000000a00b506636e3d302c310764613d747275650665743d302c34156674" +
                "3d307834343544304130302c307831433334300e66763d7032302e37383130302e33046d643d3210616d3d416972" +
                "506f727431302c3131350673663d3078340674703d55445008766e3d36353533370876733d3336362e3043706b3d" +
                "633661353338373737373333306264626437323130646666303736636532656638376230623132356563316437646" +
                "13061366562386339656264363966653239c059001c00010000000a0010fe80000000000000ae7f3efffe010203c0" +
                "59000100010000000a0004c0a80134c059000100010000000a0004a9fe99d1",
        )
    }
}

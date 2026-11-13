package com.sublunar.amp.playback.output.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which speakers are listed, against the text records of a Denon AVR, an
 * AirPort Express on AirPlay 2 firmware, an Apple TV on tvOS 27 and a Mac.
 */
class AirPlayReceiverTest {

    private fun service(name: String, txt: Map<String, String>) =
        Mdns.Service("$name._raop._tcp.local", "host.local", 7000, "192.168.1.30", txt)

    private val denon = service(
        "0005CDD62096@Denon AVR-X1500H",
        mapOf("cn" to "0,1", "da" to "true", "et" to "0,4", "ft" to "0x445F8A00,0x801C340", "md" to "0,1,2", "am" to "Denon AVR-X1500H", "sf" to "0x4", "tp" to "UDP", "vs" to "366.0"),
    )
    private val airport = service(
        "54E43A010203@Den Shelf",
        mapOf("cn" to "0,1", "et" to "0,4", "md" to "2", "am" to "AirPort10,115", "sf" to "0x4", "tp" to "UDP"),
    )
    private val appleTv = service(
        "36DF92D0503E@Living Room",
        mapOf("cn" to "0,1,2,3", "et" to "0,3,5", "sf" to "0x644", "md" to "0,1,2", "am" to "AppleTV14,1", "tp" to "UDP", "ov" to "27.2"),
    )
    private val mac = service(
        "06AF6801C210@Laptop",
        mapOf("cn" to "0,1,2,3", "et" to "0,3,5", "sf" to "0x204", "am" to "MacBookPro18,3", "tp" to "UDP"),
    )

    @Test
    fun `the Denon and the AirPort are listed, with what their displays take`() {
        val d = AirPlayReceiver.from(denon)!!
        assertEquals("0005cdd62096@denon avr-x1500h", d.id)
        assertEquals("0005cdd62096", d.deviceId)
        assertEquals("Denon AVR-X1500H", d.name)
        assertTrue(d.showsText && d.showsArtwork && d.showsProgress && d.takesAuthSetup)
        val a = AirPlayReceiver.from(airport)!!
        assertEquals("Den Shelf", a.name)
        assertTrue(!a.showsText && !a.showsArtwork && a.showsProgress)
    }

    @Test
    fun `an Apple TV and a Mac pair with their code, one that doesn't say how is left out`() {
        assertNull(AirPlayReceiver.whyNot(appleTv))
        assertTrue(AirPlayReceiver.from(appleTv)!!.pairsWithCode)
        // The user's MacBook Pro as it announces itself: pairing wanted, HomeKit pairing offered.
        val laptop = service(
            "06AF6801C210@Viscero",
            mapOf("cn" to "0,1,2,3", "et" to "0,3,5", "ft" to "0x4A7FCFD5,0x38174FDE", "sf" to "0x204", "am" to "MacBookPro18,3", "tp" to "UDP"),
        )
        assertNull(AirPlayReceiver.whyNot(laptop))
        assertTrue(AirPlayReceiver.from(laptop)!!.pairsWithCode)
        // No features said: pairing of an unknown kind.
        assertEquals("needs pairing", AirPlayReceiver.whyNot(mac))
    }

    @Test
    fun `a HomePod that offers pairing without a code is listed for it`() {
        val homePod = service("AABBCCDDEE01@Kitchen", mapOf("am" to "AudioAccessory5,1", "ov" to "27.1", "ft" to "0x4A7FDFD5,0x00010000"))
        assertNull(AirPlayReceiver.whyNot(homePod))
        val r = AirPlayReceiver.from(homePod)!!
        assertTrue(r.pairsWithoutCode)
        assertTrue(!r.pairsWithCode)
    }

    @Test
    fun `a Mac that lets only its owner's account play is known by its AirPlay record`() {
        // The user's MacBook Pro set to Current User, then to anyone on the network.
        val currentUser = Mdns.Service("Viscero._airplay._tcp.local", "v.local", 7000, "192.168.2.10", mapOf("act" to "2", "acl" to "0", "deviceid" to "06:AF:68:01:C2:10"))
        val anyone = Mdns.Service("Viscero._airplay._tcp.local", "v.local", 7000, "192.168.2.10", mapOf("acl" to "0", "deviceid" to "06:AF:68:01:C2:10"))
        assertEquals(setOf("06af6801c210"), AirPlayFamily.ownerOnly(listOf(currentUser)))
        assertTrue(AirPlayFamily.ownerOnly(listOf(anyone)).isEmpty())
    }

    @Test
    fun `an Apple TV set to a password is left out`() {
        val passworded = service("AABBCCDDEEFF@TV", mapOf("am" to "AppleTV14,1", "sf" to "0x284"))
        assertEquals("needs a password", AirPlayReceiver.whyNot(passworded))
    }

    @Test
    fun `what each requirement looks like`() {
        fun why(txt: Map<String, String>) = AirPlayReceiver.whyNot(service("AABBCCDDEEFF@X", txt))
        assertNull(why(mapOf("tp" to "UDP")))
        assertEquals("needs a password", why(mapOf("pw" to "true")))
        assertEquals("needs a password", why(mapOf("sf" to "0x80")))
        assertEquals("needs a PIN", why(mapOf("sf" to "0x8")))
        assertEquals("needs encryption", why(mapOf("et" to "1")))
        assertEquals("needs encryption", why(mapOf("ek" to "1")))
        assertEquals("no UDP transport", why(mapOf("tp" to "TCP")))
        assertEquals("no Apple Lossless", why(mapOf("cn" to "2,3")))
        assertEquals("not CD audio", why(mapOf("sr" to "48000")))
        assertEquals("a Mac", why(mapOf("am" to "Mac14,2")))
        // Every Mac's name, the laptop on the user's network among them.
        for (model in listOf("MacBookPro18,3", "MacBookAir10,1", "iMac21,1", "iMacPro1,1", "Macmini9,1", "MacPro7,1")) {
            assertEquals(model, "a Mac", why(mapOf("am" to model, "sf" to "0x4")))
        }
        assertEquals("an Apple device on OS 27", why(mapOf("am" to "AudioAccessory5,1", "osvers" to "27.0")))
        assertNull(why(mapOf("am" to "AudioAccessory5,1", "ov" to "26.4")))
        assertEquals("no device id", AirPlayReceiver.whyNot(service("Speaker", emptyMap())))
    }
}

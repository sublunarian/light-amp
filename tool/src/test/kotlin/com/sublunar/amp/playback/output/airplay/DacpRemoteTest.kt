package com.sublunar.amp.playback.output.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class DacpRemoteTest {

    @Test
    fun `the requests speakers send become commands, and anything else is left alone`() {
        val cases = mapOf(
            "/ctrl-int/1/setproperty?dmcp.device-volume=-24.875000" to DacpCommand.SpeakerVolume(-24.875),
            "/ctrl-int/1/setproperty?dmcp.device-volume=0.000000" to DacpCommand.SpeakerVolume(0.0),
            "/ctrl-int/1/discrete-pause" to DacpCommand.PlayPause,
            "/ctrl-int/1/playpause" to DacpCommand.PlayPause,
            "/ctrl-int/1/play" to DacpCommand.Play,
            "/ctrl-int/1/pause" to DacpCommand.Pause,
            "/ctrl-int/1/stop" to DacpCommand.Pause,
            "/ctrl-int/1/nextitem" to DacpCommand.Next,
            "/ctrl-int/1/previtem" to DacpCommand.Previous,
            "/ctrl-int/1/volumeup" to DacpCommand.VolumeStep(up = true),
            "/ctrl-int/1/volumedown" to DacpCommand.VolumeStep(up = false),
        )
        for ((target, command) in cases) assertEquals(target, command, DacpRemote.parse(target))
        assertNull(DacpRemote.parse("/ctrl-int/1/setproperty?dmcp.device-volume=nan"))
        assertNull(DacpRemote.parse("/ctrl-int/1/setproperty?dacp.shufflestate=1"))
        assertNull(DacpRemote.parse("/ctrl-int/1/getproperty?properties=dmcp.volume"))
        assertNull(DacpRemote.parse("/login"))
    }

    @Test
    fun `a call with this session's Active-Remote is acted on, one with another is refused`() {
        val heard = CopyOnWriteArrayList<DacpCommand>()
        val remote = DacpRemote(log = {}, accepts = { it == "42" }, volumeNow = { 0.5f }, onCommand = { _, c -> heard += c })
        val server = remote.listen(InetAddress.getLoopbackAddress())!!
        try {
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                s.soTimeout = 2_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                // Two requests on one connection, as the Denon sends them.
                out.write(request("/ctrl-int/1/setproperty?dmcp.device-volume=-12.500000", "42"))
                assertEquals("HTTP/1.1 204 No Content", statusOf(input))
                out.write(request("/ctrl-int/1/discrete-pause", "7"))
                assertEquals("HTTP/1.1 403 Forbidden", statusOf(input))
                out.write(request("/ctrl-int/1/getproperty?properties=dmcp.volume", "42"))
                assertEquals("HTTP/1.1 200 OK", statusOf(input))
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (heard.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(listOf<DacpCommand>(DacpCommand.SpeakerVolume(-12.5)), heard.toList())
        } finally {
            remote.stop()
        }
    }

    @Test
    fun `the volume answer is a cmgt holding status 200 and the level as a percentage`() {
        val remote = DacpRemote(log = {}, accepts = { true }, volumeNow = { 0.5f }, onCommand = { _, _ -> })
        val server = remote.listen(InetAddress.getLoopbackAddress())!!
        try {
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                s.soTimeout = 2_000
                s.getOutputStream().write(request("/ctrl-int/1/getproperty?properties=dmcp.volume", "1"))
                val input = BufferedInputStream(s.getInputStream())
                assertEquals("HTTP/1.1 200 OK", statusOf(input))
                val body = ByteArray(32)
                val n = input.read(body)
                val expected = DacpRemote.dmap(
                    "cmgt",
                    DacpRemote.dmap("mstt", byteArrayOf(0, 0, 0, 200.toByte())) + DacpRemote.dmap("cmvo", byteArrayOf(0, 0, 0, 50)),
                )
                assertEquals(expected.toList(), body.copyOf(n).toList())
            }
        } finally {
            remote.stop()
        }
    }

    @Test
    fun `a remote id is 16 hex digits and never starts with 0`() {
        val random = Random(1)
        repeat(2_000) {
            val id = DacpRecords.newId(random)
            assertTrue(id, Regex("[1-9A-F][0-9A-F]{15}").matches(id))
        }
    }

    @Test
    fun `the announcement says where the remote is, as a speaker reads it`() {
        val records = records()
        val bytes = records.announcement()
        val found = Mdns.parse(bytes, bytes.size)
        val ptr = found.single { it.type == DacpRecords.PTR && it.name == DacpRecords.SERVICE }
        assertEquals("iTunes_Ctrl_1A2B3C4D5E6F7081._dacp._tcp.local", ptr.target)
        val srv = found.single { it.type == DacpRecords.SRV }
        assertEquals(3689, srv.port)
        assertEquals("amp-6f7081.local", srv.target)
        assertEquals("192.168.2.12", found.single { it.type == DacpRecords.A }.target)
        assertEquals("1A2B3C4D5E6F7081", found.single { it.type == DacpRecords.TXT }.txt["dbid"])
        assertTrue("nothing naming an Apple OS", found.single { it.type == DacpRecords.TXT }.txt.keys.none { it == "ossi" })
    }

    @Test
    fun `the goodbye is the PTR with no time to live`() {
        val bye = records().goodbye()
        val parsed = DacpRecords.Query.parse(bye, bye.size)
        assertTrue(parsed.isResponse)
        assertEquals(0L, parsed.knownPtrs.single().third)
    }

    @Test
    fun `lookups are answered the way they asked, and others not at all`() {
        val records = records()
        val instance = records.instance
        val legacy = query(0x1234, instance, DacpRecords.SRV, unicastWanted = false)
        val reply = records.replyTo(legacy, legacy.size, fromPort = 50_000, direct = false)!!
        assertEquals(DacpRecords.Way.LEGACY, reply.way)
        assertEquals(0x1234, DacpRecords.Query.parse(reply.bytes, reply.bytes.size).id)
        val srv = Mdns.parse(reply.bytes, reply.bytes.size).single { it.type == DacpRecords.SRV }
        assertEquals(3689, srv.port)

        val qu = query(0, DacpRecords.SERVICE, DacpRecords.PTR, unicastWanted = true)
        assertEquals(DacpRecords.Way.UNICAST, records.replyTo(qu, qu.size, fromPort = 5353, direct = false)!!.way)
        val qm = query(0, DacpRecords.SERVICE, DacpRecords.PTR, unicastWanted = false)
        assertEquals(DacpRecords.Way.MULTICAST, records.replyTo(qm, qm.size, fromPort = 5353, direct = false)!!.way)

        val other = query(0, "_raop._tcp.local", DacpRecords.PTR, unicastWanted = false)
        assertNull(records.replyTo(other, other.size, fromPort = 5353, direct = false))
    }

    private fun records() = DacpRecords(
        "1A2B3C4D5E6F7081",
        3689,
        InetAddress.getByName("192.168.2.12") as Inet4Address,
        InetAddress.getByName("fe80::1") as Inet6Address,
    )

    private fun query(id: Int, name: String, type: Int, unicastWanted: Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v ushr 8)
            out.write(v and 0xff)
        }
        u16(id)
        u16(0)
        u16(1)
        u16(0)
        u16(0)
        u16(0)
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(0)
        u16(type)
        u16(if (unicastWanted) 0x8001 else 1)
        return out.toByteArray()
    }

    private fun request(target: String, activeRemote: String): ByteArray =
        "GET $target HTTP/1.1\r\nHost: amp\r\nActive-Remote: $activeRemote\r\nUser-Agent: AirPlay/366.0\r\n\r\n".toByteArray()

    /** The status line, with the headers read past; a 200's body is left to read. */
    private fun statusOf(input: BufferedInputStream): String {
        val status = line(input)
        var length = 0
        while (true) {
            val h = line(input)
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toInt()
        }
        if (!status.contains("200")) repeat(length) { input.read() }
        return status
    }

    private fun line(input: BufferedInputStream): String {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) return b.toString()
            if (c != '\r'.code) b.append(c.toChar())
        }
    }
}

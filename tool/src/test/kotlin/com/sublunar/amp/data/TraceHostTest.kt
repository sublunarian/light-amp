package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trace names the server by host and says when that host can only be
 * reached from home — the first question when something works on Wi-Fi and
 * not on cellular. These pin the classes of address it has to tell apart.
 */
class TraceHostTest {

    @Test
    fun `private addresses are marked LAN only`() {
        assertEquals("http://192.168.1.5:4533 [LAN only]", Trace.host("http://192.168.1.5:4533/rest"))
        assertEquals("http://10.0.0.2 [LAN only]", Trace.host("http://10.0.0.2"))
        assertEquals("http://172.20.1.1:32400 [LAN only]", Trace.host("http://172.20.1.1:32400"))
    }

    @Test
    fun `public addresses carry no mark`() {
        assertEquals("https://music.example.com", Trace.host("https://music.example.com/"))
        assertEquals("http://172.32.0.1", Trace.host("http://172.32.0.1"))
    }

    @Test
    fun `tailscale and plex direct are named`() {
        assertEquals("https://box.tail1234.ts.net [Tailscale]", Trace.host("https://box.tail1234.ts.net"))
        assertEquals("http://100.101.2.3 [CGNAT/Tailscale]", Trace.host("http://100.101.2.3"))
        assertEquals(
            "https://192-168-1-5.abc123.plex.direct:32400 [plex.direct → LAN only]",
            Trace.host("https://192-168-1-5.abc123.plex.direct:32400"),
        )
        assertEquals(
            "https://203-0-113-9.abc123.plex.direct:32400 [plex.direct]",
            Trace.host("https://203-0-113-9.abc123.plex.direct:32400"),
        )
    }

    @Test
    fun `no address says so`() {
        assertEquals("(no address)", Trace.host(""))
    }
}

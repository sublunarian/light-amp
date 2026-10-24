package com.sublunar.amp.source.plex.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * GDM replies are HTTP-shaped but not HTTP, and the parser is a hand-rolled
 * header scan — the part worth pinning down, since a device that fails to
 * parse is a device that silently isn't there.
 */
class PlexGdmTest {

    private val appleTv = """
        HTTP/1.0 200 OK
        Name: Living Room
        Port: 32500
        Product: Plex for Apple TV
        Content-Type: plex/media-player
        Protocol: plex
        Protocol-Version: 3
        Protocol-Capabilities: timeline,playback,navigation,mirror,playqueues
        Resource-Identifier: abc123def
        Version: 8.20.1
    """.trimIndent()

    @Test
    fun `a player reply becomes a player at the address it came from`() {
        val p = PlexGdm.parseReply(appleTv, "192.168.2.102")!!
        assertEquals("abc123def", p.id)
        assertEquals("Living Room", p.name)
        // The address is where it answered from, never what the reply claims.
        assertEquals("http://192.168.2.102:32500", p.directUrl)
    }

    @Test
    fun `something that cannot play is not somewhere sound can go`() {
        val controller = appleTv.replace(
            "Protocol-Capabilities: timeline,playback,navigation,mirror,playqueues",
            "Protocol-Capabilities: timeline,navigation",
        )
        assertNull(PlexGdm.parseReply(controller, "192.168.2.50"))
    }

    @Test
    fun `a reply missing what it takes to be reached is dropped`() {
        assertNull(PlexGdm.parseReply(appleTv.replace("Port: 32500", ""), "192.168.2.102"))
        assertNull(PlexGdm.parseReply(appleTv.replace("Resource-Identifier: abc123def", ""), "192.168.2.102"))
        assertNull(PlexGdm.parseReply(appleTv, ""))
        assertNull(PlexGdm.parseReply("", "192.168.2.102"))
    }

    @Test
    fun `headers are matched however they are cased`() {
        val shouty = appleTv.replace("Resource-Identifier:", "RESOURCE-IDENTIFIER:")
            .replace("Port:", "PORT:")
            .replace("Protocol-Capabilities:", "protocol-capabilities:")
        assertEquals("abc123def", PlexGdm.parseReply(shouty, "192.168.2.102")?.id)
    }

    @Test
    fun `a nameless player still has something to show`() {
        val nameless = appleTv.replace("Name: Living Room", "Name:")
        assertEquals("Plex for Apple TV", PlexGdm.parseReply(nameless, "192.168.2.102")?.name)
    }
}

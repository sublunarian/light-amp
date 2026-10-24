package com.sublunar.amp.source.plex.companion

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A pushing player keeps its connection open for the answer, so the listener
 * reads exactly one request and stops: whatever follows it is never waited for.
 */
class PlexCompanionListenerTest {

    private val timeline = """<MediaContainer><Timeline type="music" state="playing" time="1000"/></MediaContainer>"""

    private fun read(text: String) = readHttpRequest(ByteArrayInputStream(text.toByteArray()))

    @Test
    fun `a push is read to its content length and no further`() {
        val head = "POST /:/timeline HTTP/1.1\r\nHost: phone\r\nContent-Length: ${timeline.length}\r\n\r\n"
        assertEquals(head + timeline, read(head + timeline + "never sent, never waited for"))
    }

    @Test
    fun `the header name is matched whatever its case`() {
        val head = "POST /:/timeline HTTP/1.1\r\ncontent-length: ${timeline.length}\r\n\r\n"
        assertEquals(head + timeline, read(head + timeline + "more"))
    }

    @Test
    fun `a request without a body ends at its head`() {
        val head = "GET /:/timeline?state=playing HTTP/1.1\r\nHost: phone\r\n\r\n"
        assertEquals(head, read(head + timeline))
    }

    @Test
    fun `a body cut short is read as far as it goes`() {
        val head = "POST /:/timeline HTTP/1.1\r\nContent-Length: 500\r\n\r\n"
        assertEquals(head + timeline, read(head + timeline))
    }
}

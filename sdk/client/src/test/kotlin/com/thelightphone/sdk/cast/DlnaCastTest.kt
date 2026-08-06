package com.thelightphone.sdk.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsing tests for the DLNA control point. Discovery itself needs a renderer on
 * the network, but the fragile part is reading real-world device documents —
 * namespace prefixes, several services per device, relative control URLs — so
 * these pin that down against representative payloads.
 */
class DlnaCastTest {

    // Shaped like a real MediaRenderer description: namespaced root, a
    // RenderingControl service listed *before* AVTransport, and relative URLs.
    private val description = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living Room Speaker</friendlyName>
            <manufacturer>Acme</manufacturer>
            <UDN>uuid:9ab0c000-f668-11de-9976-00a0de9dcbef</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
                <controlURL>/MediaRenderer/RenderingControl/Control</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <controlURL>/MediaRenderer/AVTransport/Control</controlURL>
                <eventSubURL>/MediaRenderer/AVTransport/Event</eventSubURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `picks the AVTransport control url, not the first service`() {
        assertEquals(
            "/MediaRenderer/AVTransport/Control",
            DlnaCast.controlUrlFor(description, "AVTransport"),
        )
    }

    @Test
    fun `reads friendly name and udn`() {
        assertEquals("Living Room Speaker", DlnaCast.tagValue(description, "friendlyName"))
        assertEquals(
            "uuid:9ab0c000-f668-11de-9976-00a0de9dcbef",
            DlnaCast.tagValue(description, "UDN"),
        )
    }

    @Test
    fun `returns null when the device has no AVTransport service`() {
        val rendererOnly = description.replace("AVTransport", "ConnectionManager")
        assertNull(DlnaCast.controlUrlFor(rendererOnly, "AVTransport"))
    }

    @Test
    fun `reads tags that carry a namespace prefix`() {
        val prefixed = "<u:CurrentTransportState>PLAYING</u:CurrentTransportState>"
        assertEquals("PLAYING", DlnaCast.tagValue(prefixed, "CurrentTransportState"))
    }

    @Test
    fun `ssdp location header is case insensitive`() {
        val response = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "Location: http://192.168.1.50:1400/xml/device_description.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertEquals(
            "http://192.168.1.50:1400/xml/device_description.xml",
            DlnaCast.headerValue(response, "LOCATION"),
        )
    }

    @Test
    fun `missing ssdp header yields null`() {
        assertNull(DlnaCast.headerValue("HTTP/1.1 200 OK\r\n\r\n", "LOCATION"))
    }

    @Test
    fun `parses position info clock values`() {
        val response = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <Track>1</Track>
                  <TrackDuration>0:04:13</TrackDuration>
                  <RelTime>0:01:07.000</RelTime>
                </u:GetPositionInfoResponse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        assertEquals(253_000L, DlnaCast.parseClock(DlnaCast.tagValue(response, "TrackDuration")))
        // Fractional seconds are common and must not break the parse.
        assertEquals(67_000L, DlnaCast.parseClock(DlnaCast.tagValue(response, "RelTime")))
    }

    @Test
    fun `unset clock values parse to zero rather than throwing`() {
        assertEquals(0L, DlnaCast.parseClock(null))
        assertEquals(0L, DlnaCast.parseClock("NOT_IMPLEMENTED"))
        assertEquals(0L, DlnaCast.parseClock(""))
    }

    @Test
    fun `formats clock values as upnp expects`() {
        assertEquals("0:00:00", DlnaCast.formatClock(0))
        assertEquals("0:01:07", DlnaCast.formatClock(67_000))
        assertEquals("1:01:01", DlnaCast.formatClock(3_661_000))
        // Negative positions would otherwise render as "-1:-1:-1".
        assertEquals("0:00:00", DlnaCast.formatClock(-5_000))
    }

    @Test
    fun `didl metadata escapes the stream url and title`() {
        val didl = DlnaCast.didlMetadata(
            url = "http://host/rest/stream.view?id=1&format=mp3",
            title = "Fear & Delight",
            artist = "The Correspondents",
            album = "Puppet Loosely Strung",
            durationMs = 253_000,
        )
        // A raw ampersand here makes the renderer reject the whole envelope.
        assertTrue("&amp;format=mp3" in didl, "stream url ampersand not escaped: $didl")
        assertTrue("Fear &amp; Delight" in didl, "title ampersand not escaped: $didl")
        assertTrue("duration=\"0:04:13\"" in didl, "duration missing: $didl")
        assertTrue("object.item.audioItem.musicTrack" in didl)
    }

    @Test
    fun `finds the RenderingControl endpoint separately from AVTransport`() {
        assertEquals(
            "/MediaRenderer/RenderingControl/Control",
            DlnaCast.controlUrlFor(description, "RenderingControl"),
        )
    }

    @Test
    fun `fader maps onto the old app's 0-74 cast volume window`() {
        // Matches services/castVolume.ts: a full fader is 74, not 100.
        assertEquals(0, DlnaCast.fractionToVolume(0f))
        assertEquals(37, DlnaCast.fractionToVolume(0.5f))
        assertEquals(74, DlnaCast.fractionToVolume(1f))
        // Out-of-range input must not escape the window.
        assertEquals(74, DlnaCast.fractionToVolume(2f))
        assertEquals(0, DlnaCast.fractionToVolume(-1f))
    }

    @Test
    fun `renderer volume maps back to a fader position`() {
        assertEquals(1f, DlnaCast.volumeToFraction(74))
        assertEquals(0f, DlnaCast.volumeToFraction(0))
        // A device already above our ceiling shouldn't overflow the fader.
        assertEquals(1f, DlnaCast.volumeToFraction(100))
    }

    @Test
    fun `sniffs the container from magic bytes, not the content type`() {
        fun head(vararg parts: Any): Pair<ByteArray, Int> {
            val bytes = parts.flatMap { part ->
                when (part) {
                    is String -> part.map { it.code.toByte() }
                    is Int -> listOf(part.toByte())
                    else -> emptyList()
                }
            }.toByteArray().copyOf(12)
            return bytes to 12
        }
        // The case that broke real playback: Navidrome sends Content-Type
        // audio/flac with an Ogg-FLAC body, which audio/flac renderers can't play.
        val (ogg, oggLen) = head("OggS")
        assertEquals("audio/ogg", DlnaCast.sniffContainer(ogg, oggLen))

        val (flac, flacLen) = head("fLaC")
        assertEquals("audio/flac", DlnaCast.sniffContainer(flac, flacLen))

        val (mp4, mp4Len) = head(0, 0, 0, 0x1C, "ftyp")
        assertEquals("audio/mp4", DlnaCast.sniffContainer(mp4, mp4Len))

        val (id3, id3Len) = head("ID3")
        assertEquals("audio/mpeg", DlnaCast.sniffContainer(id3, id3Len))

        val (sync, syncLen) = head(0xFF, 0xFB)
        assertEquals("audio/mpeg", DlnaCast.sniffContainer(sync, syncLen))

        val (wav, wavLen) = head("RIFF")
        assertEquals("audio/wav", DlnaCast.sniffContainer(wav, wavLen))

        // Nothing recognisable must not guess.
        assertNull(DlnaCast.sniffContainer(ByteArray(12), 12))
        // A truncated read must not index past what was actually read.
        assertNull(DlnaCast.sniffContainer("fL".map { it.code.toByte() }.toByteArray().copyOf(12), 2))
    }
}

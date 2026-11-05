package com.sublunar.amp.playback.output.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a renderer tells the Output page of its device, to be listed once beside its AirPlay twin. */
class DlnaTargetTest {

    @Test
    fun `a renderer's MAC is read off the end of its id, when it ends in one`() {
        assertEquals("0005cdd62096", macAtEnd("uuid:4d72d99f-0c85-12ca-0080-0005cdd62096"))
        assertNull(macAtEnd("uuid:RINCON_347E5C9C5B4701400_MR"))
        assertNull(macAtEnd("short"))
    }

    @Test
    fun `its address is its control endpoint's host`() {
        val target = DlnaTarget(
            DlnaRenderer(
                id = "uuid:4d72d99f-0c85-12ca-0080-0005cdd62096",
                name = "Denon AVR-X1500H",
                controlUrl = "http://192.168.1.30:60006/upnp/control/renderer_dvc/AVTransport",
                renderingControlUrl = null,
                connectionManagerUrl = null,
            ),
        )
        assertEquals("192.168.1.30", target.address)
        assertEquals("0005cdd62096", target.hardwareId)
    }
}

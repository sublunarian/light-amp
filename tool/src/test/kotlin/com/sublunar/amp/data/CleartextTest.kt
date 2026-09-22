package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The platform's answer is passed in, so these hold the one thing that is Amp's
 * own: which addresses get asked about at all, and with which host.
 */
class CleartextTest {

    private val nothingPermitted: (String) -> Boolean = { false }
    private val everythingPermitted: (String) -> Boolean = { true }

    @Test
    fun `plain http is refused where the platform says no`() {
        assertTrue(Cleartext.refuses("http://192.168.1.10:4533", nothingPermitted))
        assertTrue(Cleartext.refuses("  HTTP://music.local/  ", nothingPermitted))
    }

    @Test
    fun `nothing is refused where the platform says yes, as in a side-loaded build`() {
        assertFalse(Cleartext.refuses("http://192.168.1.10:4533", everythingPermitted))
    }

    @Test
    fun `https is never refused and never asked about`() {
        var asked = false
        assertFalse(Cleartext.refuses("https://music.example.com") { asked = true; false })
        assertFalse(asked)
    }

    @Test
    fun `an address without a scheme is left to its https try`() {
        assertFalse(Cleartext.refuses("192.168.1.10:4533", nothingPermitted))
        assertFalse(Cleartext.refuses("music.example.com", nothingPermitted))
    }

    @Test
    fun `the platform is asked about the host alone`() {
        val asked = mutableListOf<String>()
        Cleartext.refuses("http://192.168.1.10:4533/rest") { asked += it; true }
        Cleartext.refuses("http://[fe80::1]:8096") { asked += it; true }
        assertEquals(listOf("192.168.1.10", "fe80::1"), asked)
    }

    @Test
    fun `the brief message is short enough for a sync row`() {
        assertTrue(Cleartext.REFUSED_BRIEF.length < 60)
        assertTrue(Cleartext.REFUSED.startsWith(Cleartext.REFUSED_BRIEF))
    }
}

package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerAddressTest {

    @Test
    fun `an address with a scheme is taken as typed`() {
        assertEquals(listOf("http://jellyfin.example.com"), ServerAddress.candidates("http://jellyfin.example.com"))
        assertEquals(listOf("HTTPS://192.168.1.10:8096"), ServerAddress.candidates("  HTTPS://192.168.1.10:8096/ "))
    }

    @Test
    fun `a public name gets https alone`() {
        assertEquals(listOf("https://jellyfin.example.com"), ServerAddress.candidates("jellyfin.example.com"))
        assertEquals(listOf("https://music.example.com:8443/jf"), ServerAddress.candidates("music.example.com:8443/jf/"))
        assertEquals(listOf("https://8.8.8.8:4533"), ServerAddress.candidates("8.8.8.8:4533"))
    }

    @Test
    fun `a home address gets https, then http`() {
        assertEquals(
            listOf("https://192.168.1.10:8096", "http://192.168.1.10:8096"),
            ServerAddress.candidates("192.168.1.10:8096"),
        )
        assertEquals(listOf("https://nas/jellyfin", "http://nas/jellyfin"), ServerAddress.candidates("nas/jellyfin"))
    }

    @Test
    fun `private, loopback, link-local and Tailscale ranges are home`() {
        for (host in listOf(
            "10.0.0.5", "127.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.1",
            "169.254.10.10", "100.64.0.1", "100.127.255.255",
        )) {
            assertTrue(ServerAddress.isLocal(host), host)
        }
    }

    @Test
    fun `the neighbours of those ranges are public`() {
        for (host in listOf("11.0.0.1", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.63.0.1", "100.128.0.1")) {
            assertFalse(ServerAddress.isLocal(host), host)
        }
    }

    @Test
    fun `IPv6 unique-local, link-local and loopback are home`() {
        assertEquals(
            listOf("https://[fd12::1]:8096", "http://[fd12::1]:8096"),
            ServerAddress.candidates("[fd12::1]:8096"),
        )
        assertTrue(ServerAddress.isLocal("[fe80::1]"))
        assertTrue(ServerAddress.isLocal("::1"))
        assertFalse(ServerAddress.isLocal("2001:db8::1"))
    }

    @Test
    fun `single-word names and home suffixes are home, lookalikes are not`() {
        for (host in listOf("nas", "localhost", "music.local", "NAS.LOCAL", "box.lan", "srv.home.arpa", "jf.internal", "music.local.")) {
            assertTrue(ServerAddress.isLocal(host), host)
        }
        for (host in listOf("evil-local.com", "music.local.example.com", "mylan.com", "home.arpa.example.com")) {
            assertFalse(ServerAddress.isLocal(host), host)
        }
    }

    @Test
    fun `an address that won't parse is still tried as https alone`() {
        assertEquals(listOf("https://my server"), ServerAddress.candidates("my server"))
    }

    @Test
    fun `Subsonic drops the rest suffix before the same rule`() {
        assertEquals(
            listOf("https://192.168.1.10:4533", "http://192.168.1.10:4533"),
            SubsonicConfig.candidates("192.168.1.10:4533/rest/"),
        )
        assertEquals(listOf("https://music.example.com"), SubsonicConfig.candidates("music.example.com/REST"))
    }
}

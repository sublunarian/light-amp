package com.sublunar.amp.source.subsonic

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which address a Subsonic source keeps when its http:// address is redirected. */
class LandedBaseTest {

    @Test
    fun `an upgrade to https on the same host is kept`() {
        assertEquals(
            "https://music.example.com",
            landedBase("http://music.example.com", Url("https://music.example.com/rest/ping.view")),
        )
        assertEquals(
            "https://example.com/navidrome",
            landedBase("http://example.com:8080/navidrome", Url("https://example.com/navidrome/rest/ping.view?x=1")),
        )
    }

    @Test
    fun `another host, plain http, or a moved path keeps the typed address`() {
        val typed = "http://music.example.com"
        assertEquals(typed, landedBase(typed, Url("https://auth.example.com/rest/ping.view")))
        assertEquals(typed, landedBase(typed, Url("http://music.example.com/rest/ping.view")))
        assertEquals(typed, landedBase(typed, Url("https://music.example.com/login?next=/rest/ping.view")))
    }
}

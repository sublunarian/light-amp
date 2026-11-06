package com.sublunar.amp.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogScrubberTest {

    private fun scrubbed(text: String) = LogScrubber.scrub(text)

    private fun assertGone(text: String, vararg secrets: String) {
        val out = scrubbed(text)
        secrets.forEach { assertFalse(it in out, "\"$it\" survived in: $out") }
    }

    @Test
    fun `a Subsonic request URL goes whole, login and all`() {
        val timeout = scrubbed("timeout [url=http://192.168.1.5:4533/rest/stream.view?u=sam&t=0a1b2c&s=x9y8&v=1.16.1, request_timeout=500 ms]")
        assertTrue(timeout.startsWith("timeout [url=<url>") && timeout.endsWith("request_timeout=500 ms]"), timeout)
        assertGone(timeout, "192.168", "4533", "u=sam", "0a1b2c", "x9y8")
        assertGone("https://music.example.com/rest/ping.view?u=sam&t=abc&s=def", "music.example.com", "u=sam", "abc", "def")
    }

    @Test
    fun `a URL escaped inside JSON is still a URL`() {
        assertGone("""{"next":"https:\/\/navidrome.lan\/rest\/x?u=sam&t=abc"}""", "navidrome.lan", "u=sam")
    }

    @Test
    fun `query secrets without their URL lose their values`() {
        assertEquals("?u=…&t=…&s=…", scrubbed("?u=sam&t=0a1b&s=salt"))
        assertEquals("&ApiKey=…", scrubbed("&ApiKey=deadbeefcafe"))
        assertEquals("&X-Plex-Token=…", scrubbed("&X-Plex-Token=abcDEF123"))
    }

    @Test
    fun `login headers lose their values in every spelling`() {
        assertEquals("X-Plex-Token: …", scrubbed("X-Plex-Token: abcdEFGH1234"))
        assertEquals("(Authorization, …", scrubbed("(Authorization, Basic dXNlcjpwYXNz)").substringBefore(")"))
        assertEquals("cookie=…", scrubbed("cookie=session=abc"))
        assertEquals("sent Basic …", scrubbed("sent Basic dXNlcjpwYXNzd29yZA=="))
        assertEquals("Bearer …", scrubbed("Bearer eyJhbGciOiJIUzI1NiJ9.abc"))
    }

    @Test
    fun `a path on the phone goes to the end of the line, spaces and all`() {
        assertEquals("standing in for <path>", scrubbed("standing in for file:/storage/emulated/0/Music/Pink Floyd/The Wall/01 In the Flesh.flac"))
        assertEquals("<path>", scrubbed("/data/user/0/com.sublunar.amp/files/downloads/src-1/abc.opus"))
        assertEquals("playlist <path>", scrubbed("playlist m3u:/sdcard/Music/Amp/Playlists/Road Trip.m3u8"))
    }

    @Test
    fun `addresses and names of machines go`() {
        assertEquals("player at <ip>", scrubbed("player at 192.168.2.12"))
        assertEquals("<host>", scrubbed("192-168-1-5.0123456789abcdef0123456789abcdef.plex.direct"))
        assertEquals("on <host>", scrubbed("on navidrome.lan:4533"))
        assertEquals("on <host>", scrubbed("on music.home"))
        assertEquals("<ip>", scrubbed("fe80::1%wlan0"))
        assertEquals("<ip>", scrubbed("2001:db8:85a3:0:0:8a2e:370:7334"))
        assertEquals("speaker <mac>", scrubbed("speaker a4:5e:60:c1:22:9f"))
        assertEquals("mail <email>", scrubbed("mail sam@example.com"))
    }

    @Test
    fun `ids and tokens go`() {
        assertEquals("udn <id>", scrubbed("udn 5f9ec1b3-ed18-4d5c-a0f2-4c3a1b2c3d4e"))
        assertEquals("cover <id>", scrubbed("cover 9e107d9d372bb6826bd81d3542a419d6"))
        assertEquals("token <token>", scrubbed("token AbCdEfGhIj1234567890xy"))
        assertEquals("song <token>", scrubbed("song 4z9hMuXKK7e5AtqWvB1lvo"))
    }

    @Test
    fun `a line can't be broken in two or reordered`() {
        assertEquals("a b c d", scrubbed("a\nb\u2028c\u202Ed"))
    }

    @Test
    fun `a runaway line is cut`() {
        val out = scrubbed("x".repeat(5000))
        assertEquals(LogScrubber.MAX_CHARS, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun `what Amp's own lines say comes through untouched`() {
        listOf(
            "sync done subsonic/3fa2 in 12.3 s: 712 albums, songs read for 40 (0 unread)",
            "seek to 1:02:33 at idx=7, REL_TIME 0:03:21.000",
            "urn:schemas-upnp-org:service:AVTransport:1 http-get:*:audio/flac:*",
            "server navidrome 0.53.3, plex 1.42.1.10028, jellyfin 10.11.2",
            "player error ERROR_CODE_IO_NETWORK_CONNECTION_FAILED at idx=3 (stream)",
            "sync failed: SocketTimeoutException>SocketTimeoutException status=503 ETIMEDOUT",
            "  at data.LibraryRepository.getAlbumTracks(LibraryRepository.kt:569)",
            "  at androidx.media3.session.MediaControllerImplBase\$\$ExternalSyntheticLambda0.run(D8\$\$SyntheticClass:0)",
            "decoder c2.android.flac.decoder, stream proxy on 127.0.0.1:41234",
            "rules: connected=true unmetered=false wifi=false mode=LOW_DATA → talk=true",
        ).forEach { assertEquals(it, scrubbed(it)) }
    }
}

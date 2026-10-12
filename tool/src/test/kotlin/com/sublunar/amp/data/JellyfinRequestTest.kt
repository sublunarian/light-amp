package com.sublunar.amp.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The shape of what Amp sends a Jellyfin server, where a wrong shape costs
 * nothing at compile time and everything on a real server.
 */
class JellyfinRequestTest {

    private val client = JellyfinClient("https://jf.example", "tok", "u1")
    private val track = JellyfinItem(id = "t1", name = "Gone").toTrack()

    @Test
    fun `media URLs carry the token the way Jellyfin 12 still reads it`() {
        // `api_key` is legacy authorization, off on every 12.x server: the
        // request arrives with no token, and a transcode or a download is 401.
        val urls = buildList {
            StreamFormat.entries.forEach {
                add(client.streamUrl("t1", it))
                add(client.downloadUrl(track, it))
            }
            add(client.coverArtUrl("al").orEmpty())
            add(client.coverArtUrl("al", 600).orEmpty())
        }
        urls.forEach { url ->
            assertTrue("ApiKey=tok" in url, url)
            assertFalse("api_key" in url, url)
        }
    }

    @Test
    fun `a rename sends the name and nothing that could replace the tracks`() {
        // Any `Ids` — an empty list included — is taken as the playlist's new
        // contents, so a rename that sent one emptied the playlist.
        assertEquals(setOf("Name"), JellyfinClient.renameBody("Road").keys)
    }

    @Test
    fun `a stream names the device its source signed in as`() {
        val own = JellyfinClient("https://jf.example", "tok", "u1", deviceId = "amp-1234")
        assertTrue("DeviceId=amp-1234" in own.streamUrl("t1", StreamFormat.OPUS))
        assertTrue("DeviceId=${JellyfinClient.LEGACY_DEVICE_ID}" in client.streamUrl("t1", StreamFormat.OPUS))
    }

    @Test
    fun `the authorization header survives any player name`() {
        // A curly apostrophe can't go in a header at all, and a quote or a comma
        // ends its field early; the server percent-decodes every value.
        val header = JellyfinClient.authorization("Armin\u2019s \"Amp\", x+y", "tok", "amp-1")
        assertTrue("Client=\"Armin%E2%80%99s %22Amp%22%2C x%2By\"" in header, header)
        assertTrue("Device=\"Light Phone III\"" in header, header)
        assertTrue("DeviceId=\"amp-1\"" in header, header)
        assertTrue(header.all { it in ' '..'~' }, header)
    }

    @Test
    fun `asking for Opus can't come back as some other Ogg`() {
        // "ogg" alone direct-plays any Ogg file, Vorbis included.
        assertTrue("Container=ogg%7Copus" in client.streamUrl("t1", StreamFormat.OPUS))
        assertTrue("Container=mp3%7Cmp3" in client.streamUrl("t1", StreamFormat.MP3))
    }

    @Test
    fun `removing one copy of a song held twice is refused`() {
        val held = listOf("a", "b", "a")
        assertFalse(JellyfinClient.takesEveryCopy(held, listOf("a")))
        assertTrue(JellyfinClient.takesEveryCopy(held, listOf("a", "a")))
        assertTrue(JellyfinClient.takesEveryCopy(held, listOf("b")))
    }

    @Test
    fun `the per-user routes are told by version, read as numbers`() {
        assertEquals(false, JellyfinClient.hasUserRoutes("10.8.13"))
        assertEquals(true, JellyfinClient.hasUserRoutes("10.9.0"))
        // Before 10.9 as text.
        assertEquals(true, JellyfinClient.hasUserRoutes("10.10.0"))
        assertEquals(true, JellyfinClient.hasUserRoutes("10.11.11"))
        // There is no 11.
        assertEquals(true, JellyfinClient.hasUserRoutes("12.2.0"))
        assertEquals(true, JellyfinClient.hasUserRoutes("13.0.0"))
        assertEquals(true, JellyfinClient.hasUserRoutes("10.9.0-rc1"))
        assertNull(JellyfinClient.hasUserRoutes(""))
        assertNull(JellyfinClient.hasUserRoutes("10"))
    }

    @Test
    fun `an account that may not keep copies offers no downloads, and keeps what it has`() {
        val source = MusicSource(id = "j", kind = SourceKind.JELLYFIN, name = "J")
        assertTrue(source.mayDownload)
        val refused = source.copy(features = ServerFeatures(missing = listOf(ServerFeatures.KEEP_COPIES)))
        assertTrue(refused.downloadsRefused)
        assertFalse(refused.mayDownload)
        assertFalse(refused.downloadFormatAvailable)
        // Still a source that has downloads: the ones already here stay listed and removable.
        assertTrue(refused.supportsDownloads)
    }
}

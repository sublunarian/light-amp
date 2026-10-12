package com.sublunar.amp.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}

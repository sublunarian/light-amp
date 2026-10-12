package com.sublunar.amp.data

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Where Amp asks Plex for a song's file. Plex serves some files on only one
 * of its two addresses for them, so which one each request names matters.
 */
class PlexRequestTest {

    private val client = PlexClient("http://plex.example:32400/", "tok")

    private fun track(streamPath: String = "/library/parts/42/1700000000/file.flac") = Track(
        id = "7", title = "Gone", artist = "", album = "", albumArtist = "", albumId = null,
        coverArtId = null, durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = 0, lastPlayedMs = 0, streamPath = streamPath,
    )

    private val part = "http://plex.example:32400/library/parts/42/1700000000/file.flac"

    @Test
    fun `the original plays from the part's direct-play address`() {
        assertEquals("$part?download=0&X-Plex-Token=tok", client.streamUrl(track(), StreamFormat.RAW))
    }

    @Test
    fun `an original download asks for the part as a download`() {
        // download=0 is a 500 for files Plex never finished analysing, and
        // download=1 is what Plex holds to the account's "Allow downloads".
        assertEquals("$part?download=1&X-Plex-Token=tok", client.downloadUrl(track(), StreamFormat.RAW))
    }

    @Test
    fun `the other address for an original is the same part as a download`() {
        assertEquals("$part?download=1&X-Plex-Token=tok", client.alternateOriginalUrl(track()))
    }

    @Test
    fun `a part path that already has a query keeps it`() {
        val queried = track(streamPath = "/library/parts/42/file.flac?x=1")
        assertEquals(
            "http://plex.example:32400/library/parts/42/file.flac?x=1&download=1&X-Plex-Token=tok",
            client.alternateOriginalUrl(queried),
        )
        assertTrue("?x=1&download=1&" in client.downloadUrl(queried, StreamFormat.RAW))
    }

    @Test
    fun `a song with no part has no other address, and asks the transcoder by id`() {
        val unplaced = track(streamPath = "")
        assertNull(client.alternateOriginalUrl(unplaced))
        val download = client.downloadUrl(unplaced, StreamFormat.RAW)
        assertTrue(download.startsWith("http://plex.example:32400/music/:/transcode/universal/start.mp3?"), download)
        assertTrue("directPlay=1" in download, download)
    }

    @Test
    fun `a transcoded download still comes from the transcoder`() {
        val download = client.downloadUrl(track(), StreamFormat.MP3)
        assertTrue(download.startsWith("http://plex.example:32400/music/:/transcode/universal/start.mp3?"), download)
        assertTrue("download=1" in download, download)
        assertTrue("/library/parts/" !in download, download)
    }

    @Test
    fun `other servers have no other address for an original`() {
        assertNull(JellyfinClient("https://jf.example", "tok", "u1").alternateOriginalUrl(track()))
    }
}

package com.sublunar.amp.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Who Amp says it is to Plex, link by link, and what the session policy is
 * per kind of server — issue #20.
 */
class PlexIdentityTest {

    private fun identifierOf(headers: List<Pair<String, String>>): String? =
        headers.firstOrNull { it.first == "X-Plex-Client-Identifier" }?.second

    private fun plexSource(deviceId: String) = MusicSource(
        id = "src-1",
        kind = SourceKind.PLEX,
        name = "Plex",
        baseUrl = "http://plex.example:32400",
        token = "tok",
        machineIdentifier = "mid",
        deviceId = deviceId,
    )

    /** A transcode URL carries the identity in its query, so it shows which identifier a client sends. */
    private fun streamOf(source: MusicSource): String =
        (source.toClient() as PlexClient).streamUrl("7", StreamFormat.MP3, sessionId = "S")

    @Test
    fun `a link of its own says so on every request`() {
        assertEquals("amp-1234", identifierOf(PlexClient.plexIdentity(clientIdentifier = "amp-1234")))
        val stream = PlexClient("http://plex.example:32400", "tok", clientIdentifier = "amp-1234")
            .streamUrl("7", StreamFormat.MP3, sessionId = "S")
        assertTrue("X-Plex-Client-Identifier=amp-1234" in stream, stream)
    }

    /** Links made before the identifier was kept go on as the device their tokens were issued to. */
    @Test
    fun `an older link keeps the identifier its token was issued to`() {
        assertEquals("com.sublunar.amp", PlexClient.LEGACY_CLIENT_ID)
        assertEquals(PlexClient.LEGACY_CLIENT_ID, identifierOf(PlexClient.plexIdentity()))
        assertEquals(PlexClient.LEGACY_CLIENT_ID, plexSource(deviceId = "").plexClientIdentifier)
        assertTrue("X-Plex-Client-Identifier=com.sublunar.amp" in streamOf(plexSource(deviceId = "")))
    }

    @Test
    fun `a source keeps the identifier it was linked with`() {
        val source = plexSource(deviceId = "amp-abcd")
        assertEquals("amp-abcd", source.plexClientIdentifier)
        val stream = streamOf(source)
        assertTrue("X-Plex-Client-Identifier=amp-abcd" in stream, stream)
        assertFalse("com.sublunar.amp" in stream, stream)
    }

    @Test
    fun `each new link is a device of its own`() {
        val one = PlexClient.newClientIdentifier()
        val two = PlexClient.newClientIdentifier()
        assertNotEquals(one, two)
        assertNotEquals(PlexClient.LEGACY_CLIENT_ID, one)
        assertTrue(one.startsWith("amp-"), one)
    }

    @Test
    fun `only Plex keeps one session for the whole listening`() {
        assertTrue(PlexClient("http://plex.example:32400", "tok").oneSessionPerPlayer)
        assertFalse(JellyfinClient("https://jf.example", "tok", "u1").oneSessionPerPlayer)
    }

    /** The stream of every song carries the one session id it is handed — nothing per song is added. */
    @Test
    fun `songs streamed in one session carry the same id`() {
        val client = PlexClient("http://plex.example:32400", "tok")
        val a = client.streamUrl("7", StreamFormat.MP3, sessionId = "S")
        val b = client.streamUrl("8", StreamFormat.MP3, sessionId = "S")
        assertTrue("X-Plex-Session-Identifier=S&" in a, a)
        assertTrue("X-Plex-Session-Identifier=S&" in b, b)
    }

    private fun track(streamPath: String) = Track(
        id = "7", title = "Gone", artist = "", album = "", albumArtist = "", albumId = null,
        coverArtId = null, durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = 0, lastPlayedMs = 0, streamPath = streamPath,
    )

    /** Plex holds a tracked session to a decision per song, but only for what the transcoder serves. */
    @Test
    fun `only a transcoder stream needs a decision first`() {
        val plex = PlexClient("http://plex.example:32400", "tok")
        val part = track("/library/parts/42/1700000000/file.flac")
        assertTrue(plex.streamNeedsDecision(part, StreamFormat.MP3))
        assertFalse(plex.streamNeedsDecision(part, StreamFormat.RAW))
        // No part to name: the original comes through the transcoder too.
        assertTrue(plex.streamNeedsDecision(track(""), StreamFormat.RAW))
        assertFalse(JellyfinClient("https://jf.example", "tok", "u1").streamNeedsDecision(part, StreamFormat.MP3))
    }
}

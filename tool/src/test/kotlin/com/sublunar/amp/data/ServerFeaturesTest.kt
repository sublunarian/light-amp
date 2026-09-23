package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule the whole thing rests on: a Subsonic-shaped answer, an error
 * included, means the server has that endpoint, and only an answer that isn't
 * Subsonic at all — or the status of a route that doesn't exist — means it
 * hasn't. Everything a network or a login can throw has to read as neither.
 */
class ServerFeaturesTest {

    @Test
    fun `a server's own error still proves the endpoint is there`() {
        assertFalse(SubsonicException("The requested data was not found.").endpointMissing)
        assertFalse(SubsonicException("Wrong username or password.").endpointMissing)
    }

    @Test
    fun `an answer that isn't Subsonic, or a route that isn't there, is missing`() {
        assertTrue(SubsonicException("Unexpected response from server.", notSubsonic = true).endpointMissing)
        assertTrue(SubsonicException("Server returned HTTP 404.", status = 404).endpointMissing)
        assertTrue(SubsonicException("Server returned HTTP 501.", status = 501).endpointMissing)
    }

    @Test
    fun `a server having trouble is never read as a server missing something`() {
        assertFalse(SubsonicException("Server returned HTTP 500.", status = 500).endpointMissing)
        assertFalse(SubsonicException("Server returned HTTP 502.", status = 502).endpointMissing)
        assertFalse(SubsonicException("Server returned HTTP 401.", status = 401).endpointMissing)
    }

    private fun subsonic(features: ServerFeatures? = null) = MusicSource(
        id = "s",
        kind = SourceKind.SUBSONIC,
        name = "Server",
        features = features,
    )

    @Test
    fun `a server that has not been asked can do everything, as before`() {
        val source = subsonic()
        assertTrue(source.supportsRatings)
        assertTrue(source.supportsRadio)
        assertTrue(source.supportsOriginalDownload)
        assertTrue(StreamFormat.RAW in source.downloadFormats)
    }

    @Test
    fun `what a server answered for as missing is not offered`() {
        val source = subsonic(
            ServerFeatures(
                checkedVersion = "1.16.1/BandcampServer/1.0",
                missing = listOf(
                    ServerFeatures.SET_RATING,
                    ServerFeatures.SIMILAR_SONGS,
                    ServerFeatures.DOWNLOAD,
                ),
            ),
        )
        assertFalse(source.supportsRatings)
        assertFalse(source.supportsRadio)
        assertFalse(source.supportsOriginalDownload)
        assertFalse(StreamFormat.RAW in source.downloadFormats)
        // Downloading itself still works — only the original file is gone.
        assertTrue(source.supportsDownloads)
        assertTrue(source.downloadFormats.isNotEmpty())
        // And a stored choice of the original reads as the default rather than
        // as a download that can never be fetched.
        assertEquals(
            StreamFormat.DEFAULT,
            source.copy(downloadFormatId = StreamFormat.RAW.id).downloadFormat,
        )
    }

    @Test
    fun `one missing endpoint doesn't take the others with it`() {
        val source = subsonic(ServerFeatures(missing = listOf(ServerFeatures.SET_RATING)))
        assertFalse(source.supportsRatings)
        assertTrue(source.supportsRadio)
        assertTrue(source.supportsOriginalDownload)
    }

    @Test
    fun `a scan a server cannot run is not a scan it refused`() {
        assertFalse(subsonic(ServerFeatures(missing = listOf(ServerFeatures.SCAN_STATUS))).supportsServerScan)
        assertTrue(subsonic(ServerFeatures()).supportsServerScan)
        assertTrue(subsonic().supportsServerScan)
    }

    @Test
    fun `answers from an older set of probes are not trusted for a newer question`() {
        // What a build that never asked about scanning left behind: it cannot
        // speak for a question it didn't put, so the record has to be re-asked.
        val old = ServerFeatures(checkedVersion = "1.16.1/BandcampServer/1.0", generation = 1)
        assertTrue(old.generation != ServerFeatures.GENERATION)
    }

    @Test
    fun `the servers that answer for everything are unchanged`() {
        val navidrome = subsonic(ServerFeatures(checkedVersion = "1.16.1/navidrome/0.58"))
        assertTrue(navidrome.supportsRatings)
        assertTrue(navidrome.supportsRadio)
        assertTrue(navidrome.supportsOriginalDownload)
        assertEquals(navidrome.streamFormats, navidrome.downloadFormats)
    }
}

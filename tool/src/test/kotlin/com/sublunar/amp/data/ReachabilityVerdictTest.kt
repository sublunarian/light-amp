package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

/**
 * Whether a ping that failed was still answered by the server — see
 * [answeredBy]. A network failure never gets this far: Reachability reads an
 * IOException as nobody there before asking.
 */
class ReachabilityVerdictTest {

    @Test
    fun `a 5xx is something in front of the server saying it is down`() {
        assertFalse(answeredBy(SubsonicException("Server returned HTTP 502.", status = 502)))
        assertFalse(answeredBy(SubsonicException("Server returned HTTP 521.", status = 521)))
        assertFalse(answeredBy(SubsonicException("Server returned HTTP 503.", status = 503, notJson = true)))
    }

    @Test
    fun `any other status is the server answering`() {
        assertTrue(answeredBy(PlexException("Plex says 401 for /x", 401)))
        assertTrue(answeredBy(JellyfinException("Jellyfin says 403 for /x", 403)))
        assertTrue(answeredBy(PlexException("Plex says 404 for /x", 404)))
        assertTrue(answeredBy(JellyfinException("Jellyfin says 400 for /x", 400)))
    }

    /**
     * Pinned as it stands: a page that isn't JSON comes with no status, and
     * so counts as the server answering, though its KDoc says such a body is
     * more often a captive portal's.
     */
    @Test
    fun `no status at all counts as answered, a page where JSON should be included`() {
        assertTrue(answeredBy(SubsonicException("Wrong username or password", code = 40, envelope = true)))
        assertTrue(answeredBy(SubsonicException("Unexpected response from server.", notJson = true)))
        assertTrue(answeredBy(Exception("m".repeat(59))))
        assertTrue(answeredBy(Exception("m".repeat(60))))
        assertTrue(answeredBy(IllegalStateException()))
        assertTrue(answeredBy(RefusedWrite()))
    }

    @Test
    fun `a body that won't parse is not the server`() {
        assertFalse(answeredBy(SerializationException("bad")))
    }
}

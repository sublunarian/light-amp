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

    @Test
    fun `no status at all counts as answered`() {
        assertTrue(answeredBy(SubsonicException("Wrong username or password", code = 40, envelope = true)))
        assertTrue(answeredBy(Exception("m".repeat(59))))
        assertTrue(answeredBy(Exception("m".repeat(60))))
        assertTrue(answeredBy(IllegalStateException()))
        assertTrue(answeredBy(RefusedWrite()))
    }

    @Test
    fun `a body that won't parse is not the server`() {
        assertFalse(answeredBy(SerializationException("bad")))
    }

    /**
     * A captive portal answers a ping with its login page and a 200. That
     * used to count as the server answering: a page with no status, or any
     * status short of a 5xx, read like a refusal from the server itself.
     */
    @Test
    fun `a page where the server's JSON should be is not the server`() {
        assertFalse(answeredBy(SubsonicException("Unexpected response from server.", notJson = true)))
        assertFalse(answeredBy(SubsonicException("Server returned HTTP 404.", status = 404, notJson = true)))
        assertTrue(answeredBy(SubsonicException("Unexpected response from server.", notSubsonic = true)))
    }
}

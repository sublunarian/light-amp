package com.sublunar.amp.data

import com.sublunar.amp.source.jellyfin.JellyfinException
import com.sublunar.amp.source.subsonic.SubsonicException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

/**
 * What a failed sync says on the Sources page, and whether it counts as the
 * login being refused — which keeps the cached library showing rather than
 * narrowing it to downloads.
 */
class SyncFailureTest {

    @Test
    fun `a 5xx is the server being down, with a gateway's own codes told apart`() {
        assertEquals("The server had an error (502)", syncErrorMessage(SubsonicException("Server returned HTTP 502.", status = 502)))
        assertEquals("Your server isn't responding (521)", syncErrorMessage(SubsonicException("Server returned HTTP 521.", status = 521)))
        assertEquals(
            "The server had an error (503)",
            syncErrorMessage(SubsonicException("Server returned HTTP 503.", status = 503, notJson = true)),
        )
    }

    @Test
    fun `a 401 or 403 is the login refused, and says where to fix it`() {
        val plex = PlexException("Plex says 401 for /x", 401)
        val jellyfin = JellyfinException("Jellyfin says 403 for /x", 403)
        assertEquals("The server refused the login. Sign in again in Sources.", syncErrorMessage(plex))
        assertEquals("The server refused the login. Sign in again in Sources.", syncErrorMessage(jellyfin))
        assertTrue(isAuthFailure(plex))
        assertTrue(isAuthFailure(jellyfin))
    }

    /**
     * Subsonic says it in its own error codes, with no status: once read only
     * from the message, which carries no number, so a wrong password counted
     * as a server that might be gone and showed whatever words it came in.
     */
    @Test
    fun `a Subsonic login refusal is the login refused too`() {
        for (code in listOf(40, 41, 44)) {
            val refused = SubsonicException("Wrong username or password", code = code, envelope = true)
            assertTrue(isAuthFailure(refused), "code $code")
            assertEquals("The server refused the login. Sign in again in Sources.", syncErrorMessage(refused), "code $code")
        }
        val notAllowed = SubsonicException("Not authorized", code = 50, envelope = true)
        assertFalse(isAuthFailure(notAllowed))
        assertEquals("Not authorized", syncErrorMessage(notAllowed))
    }

    @Test
    fun `other statuses say what they are`() {
        assertEquals("That library isn't on the server any more", syncErrorMessage(PlexException("Plex says 404 for /x", 404)))
        assertEquals("The server said 400", syncErrorMessage(JellyfinException("Jellyfin says 400 for /x", 400)))
    }

    @Test
    fun `without a status, a short message is shown as it stands and a long one is not`() {
        assertEquals("Song not found", syncErrorMessage(SubsonicException("Song not found", code = 70, envelope = true)))
        assertEquals(
            "Unexpected response from server.",
            syncErrorMessage(SubsonicException("Unexpected response from server.", notJson = true)),
        )
        assertEquals("m".repeat(59), syncErrorMessage(Exception("m".repeat(59))))
        assertEquals("Couldn't reach the server", syncErrorMessage(Exception("m".repeat(60))))
        assertEquals("bad", syncErrorMessage(SerializationException("bad")))
        assertEquals("Couldn't reach the server", syncErrorMessage(IllegalStateException()))
        assertEquals("This server can't do that", syncErrorMessage(RefusedWrite()))
    }

    @Test
    fun `a network failure is unreachable, whatever its message names`() {
        assertEquals("Couldn't reach the server", syncErrorMessage(SocketTimeoutException("GET /rest/stream?id=401&c=amp")))
        assertEquals("Couldn't reach the server", syncErrorMessage(SocketTimeoutException("HTTP 500 /401")))
    }

    /**
     * Anything but a Subsonic login refusal is read from the message rather
     * than from a status, and is pinned as it stands: a number in a timeout's
     * URL counts.
     */
    @Test
    fun `otherwise a refused login is read from the message's first three-digit number`() {
        assertTrue(isAuthFailure(SocketTimeoutException("GET /rest/stream?id=401&c=amp")))
        assertFalse(isAuthFailure(SocketTimeoutException("HTTP 500 /401")))
        assertFalse(isAuthFailure(SubsonicException("Server returned HTTP 502.", status = 502)))
        assertFalse(isAuthFailure(PlexException("Plex says 404 for /x", 404)))
        assertFalse(isAuthFailure(SerializationException("bad")))
        assertFalse(isAuthFailure(IllegalStateException()))
    }
}

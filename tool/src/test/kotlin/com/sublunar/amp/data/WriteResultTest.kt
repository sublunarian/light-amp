package com.sublunar.amp.data

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException

/**
 * Which failed writes are worth sending again. Unreachable keeps a like in the
 * offline queue; refused takes it back and says so — and a refusal kept in the
 * queue would hold every play behind it, so anything not recognised has to be
 * a refusal rather than a "try later".
 */
class WriteResultTest {

    private fun unreachable(e: Exception): String = assertIs<WriteResult.Unreachable>(writeResultOf(e)).reason

    private fun refused(e: Exception): String = assertIs<WriteResult.Refused>(writeResultOf(e)).reason

    @Test
    fun `a 5xx is the server not answering, with a gateway's own codes told apart`() {
        assertEquals("The server had an error (502)", unreachable(SubsonicException("Server returned HTTP 502.", status = 502)))
        assertEquals("Your server isn't responding (521)", unreachable(PlexException("Plex says 521 for /:/rate", 521)))
        assertEquals("The server had an error (500)", unreachable(JellyfinException("Jellyfin says 500 for /Items/x", 500)))
    }

    @Test
    fun `no route, no name, no Wi-Fi and an unreadable answer are all unreachable`() {
        assertEquals(UNREACHABLE, unreachable(SocketTimeoutException("timeout")))
        assertEquals(UNREACHABLE, unreachable(UnknownHostException("music.example")))
        assertEquals(UNREACHABLE, unreachable(NetworkGateClosedException()))
        assertEquals(UNREACHABLE, unreachable(SerializationException("Unexpected JSON token")))
    }

    @Test
    fun `a portal's page where JSON should be is something in front of the server`() {
        assertEquals(UNREACHABLE, unreachable(SubsonicException("Unexpected response from server.", notJson = true)))
        assertEquals(
            UNREACHABLE,
            unreachable(SubsonicException("Server returned HTTP 404.", status = 404, notJson = true)),
        )
    }

    @Test
    fun `a Subsonic server without the call can't do it, even when it says so with a 501`() {
        assertEquals(CANT_DO_THAT, refused(SubsonicException("Unexpected response from server.", notSubsonic = true)))
        assertEquals(CANT_DO_THAT, refused(SubsonicException("Server returned HTTP 501.", status = 501)))
        assertEquals(CANT_DO_THAT, refused(SubsonicException("Server returned HTTP 404.", status = 404)))
    }

    @Test
    fun `Subsonic's own error codes are read for what they mean`() {
        assertEquals("The server doesn't have that", refused(SubsonicException("Song not found", code = 70, envelope = true)))
        assertEquals("The server doesn't allow that", refused(SubsonicException("Not authorized", code = 50, envelope = true)))
        assertEquals("The server refused the login", refused(SubsonicException("Wrong username or password", code = 40, envelope = true)))
        assertEquals("The server refused the login", refused(SubsonicException("Token auth unsupported", code = 41, envelope = true)))
        assertEquals("The server refused the login", refused(SubsonicException("Invalid API key", code = 44, envelope = true)))
    }

    @Test
    fun `any other Subsonic error is the server's own words, when they fit`() {
        assertEquals("Playlist is read-only", refused(SubsonicException("Playlist is read-only", code = 0, envelope = true)))
        assertEquals("The server said no", refused(SubsonicException("x".repeat(80), code = 0, envelope = true)))
    }

    @Test
    fun `Jellyfin and Plex statuses say what was refused, and a 401 is not called a login problem`() {
        assertEquals("The server doesn't have that", refused(JellyfinException("Jellyfin says 404 for /Items/x", 404)))
        assertEquals("The server doesn't allow that", refused(JellyfinException("Jellyfin says 401 for /Items/x", 401)))
        assertEquals("The server doesn't allow that", refused(JellyfinException("Jellyfin says 403 for /Items/x", 403)))
        assertEquals("The server said 400", refused(PlexException("Plex says 400 for /:/rate", 400)))
    }

    @Test
    fun `a write refused before it was sent keeps its reason`() {
        assertEquals("x", refused(RefusedWrite("x")))
        assertEquals(CANT_DO_THAT, refused(RefusedWrite()))
    }

    @Test
    fun `anything not recognised is refused, so a bug that always throws can't hold the queue shut`() {
        assertEquals("boom", refused(IllegalStateException("boom")))
        assertEquals("Couldn't save", refused(IllegalStateException("y".repeat(80))))
        assertEquals("Couldn't save", refused(IllegalStateException()))
        assertEquals("Couldn't save", refused(JellyfinException("z".repeat(80))))
    }

    @Test
    fun `attemptWrite says how a write went`() = runBlocking {
        assertEquals(WriteResult.Saved, attemptWrite { })
        assertEquals(WriteResult.Refused("x"), attemptWrite { throw RefusedWrite("x") })
        assertEquals(WriteResult.Unreachable(UNREACHABLE), attemptWrite { throw SocketTimeoutException() })
    }

    @Test
    fun `attemptWrite lets cancellation through rather than calling it a failure`() {
        assertFailsWith<CancellationException> {
            runBlocking { attemptWrite { throw CancellationException("left") } }
        }
    }
}

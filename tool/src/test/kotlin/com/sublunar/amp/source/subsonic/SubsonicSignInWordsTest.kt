package com.sublunar.amp.source.subsonic

import java.io.IOException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.SerializationException

/**
 * What the Subsonic sign-in form makes of a failed login check, and what it
 * says about it. The words are the point: each one has to send the user to
 * the fix that is actually theirs to make — the password, the address, or
 * simply trying again.
 */
class SubsonicSignInWordsTest {

    @Test
    fun `no failure is a login accepted`() {
        assertEquals(LoginVerdict.ACCEPTED, loginVerdict(null))
    }

    @Test
    fun `the server's own error, or a status meaning no, is the login refused`() {
        assertEquals(LoginVerdict.REFUSED, loginVerdict(SubsonicException("Wrong username or password", code = 40, envelope = true)))
        assertEquals(LoginVerdict.REFUSED, loginVerdict(SubsonicException("Server returned HTTP 401.", status = 401)))
        assertEquals(LoginVerdict.REFUSED, loginVerdict(SubsonicException("Server returned HTTP 403.", status = 403)))
        // How Bandcamp says "not this login".
        assertEquals(LoginVerdict.REFUSED, loginVerdict(SubsonicException("Server returned HTTP 500.", status = 500)))
    }

    @Test
    fun `a call the server lacks, or an answer it can't read, can't check the login`() {
        assertEquals(LoginVerdict.UNCHECKABLE, loginVerdict(SubsonicException("Server returned HTTP 404.", status = 404)))
        assertEquals(LoginVerdict.UNCHECKABLE, loginVerdict(SubsonicException("Unexpected response from server.", envelope = true)))
    }

    @Test
    fun `the network, or a page in front of the server, says nothing about the password`() {
        assertEquals(
            LoginVerdict.UNREACHED,
            loginVerdict(SubsonicException("Server returned HTTP 500.", status = 500, notJson = true)),
        )
        assertEquals(LoginVerdict.UNREACHED, loginVerdict(IOException("Connection reset")))
    }

    @Test
    fun `a Subsonic error wrapped by the HTTP client reads as the bare one`() {
        val refused = SubsonicException("Wrong username or password", code = 40, envelope = true)
        assertEquals(LoginVerdict.REFUSED, loginVerdict(IllegalStateException("wrapped", refused)))
        val missing = SubsonicException("Server returned HTTP 404.", status = 404)
        assertEquals(LoginVerdict.UNCHECKABLE, loginVerdict(IOException("wrapped", missing)))
    }

    @Test
    fun `a refused login quotes the server, when it said why`() {
        assertEquals(
            "Wrong username or password",
            refusedLogin(SubsonicException("Wrong username or password", code = 40, envelope = true)),
        )
        assertEquals(
            "That address answered, but not for this account. Check the username and password.",
            refusedLogin(SubsonicException("Request failed.", code = 0, envelope = true)),
        )
        assertEquals(
            "That address answered, but not for this account. Check the username and password.",
            refusedLogin(SubsonicException("Server returned HTTP 500.", status = 500)),
        )
    }

    private val webPage =
        "That address answered with a web page, not a music server. Check the address. " +
            "If the server uses plain HTTP, type http:// in front of it."

    @Test
    fun `a web page where the server should be says to check the address`() {
        assertEquals(webPage, explain(SubsonicException("Unexpected response from server.", notJson = true)))
        assertEquals(webPage, explain(SubsonicException("Server returned HTTP 404.", status = 404, notJson = true)))
        assertEquals(webPage, explain(SerializationException("Unexpected JSON token at offset 0")))
    }

    @Test
    fun `a gateway's error page keeps its own message`() {
        assertEquals(
            "Server returned HTTP 502.",
            explain(SubsonicException("Server returned HTTP 502.", status = 502, notJson = true)),
        )
    }

    @Test
    fun `the server's own words pass through`() {
        assertEquals(
            "Wrong username or password",
            explain(SubsonicException("Wrong username or password", code = 40, envelope = true)),
        )
    }

    @Test
    fun `nothing answering says what to check`() {
        assertEquals(
            "Couldn't find that address. Check the spelling, and that this phone is on the same network.",
            explain(UnknownHostException("music.example")),
        )
        assertEquals("Couldn't connect.", explain(null))
    }
}

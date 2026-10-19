package com.sublunar.amp.source.subsonic

import com.sublunar.amp.data.WriteResult
import com.sublunar.amp.data.answeredBy
import com.sublunar.amp.data.isAuthFailure
import com.sublunar.amp.data.syncErrorMessage
import com.sublunar.amp.data.writeResultOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bandcamp turns a login away with a bare 500 and nothing in it, and that is
 * read as the login refused on Bandcamp's address alone — anywhere else the
 * same answer is the server's own error.
 */
class BandcampLoginTest {

    private val bandcamp = "https://bandcamp.com/api/subsonic"

    @Test
    fun `an empty 500 from Bandcamp is the login turned away`() {
        assertTrue(bandcampTurnsLoginAway(bandcamp, 500, page = false, body = ""))
        assertTrue(bandcampTurnsLoginAway("$bandcamp/", 500, page = false, body = " \n"))
    }

    @Test
    fun `anything else is not`() {
        assertFalse(bandcampTurnsLoginAway("https://music.example.com", 500, page = false, body = ""))
        assertFalse(bandcampTurnsLoginAway(bandcamp, 502, page = false, body = ""))
        assertFalse(bandcampTurnsLoginAway(bandcamp, 500, page = true, body = ""))
        assertFalse(bandcampTurnsLoginAway(bandcamp, 500, page = false, body = "Internal error"))
        assertFalse(bandcampTurnsLoginAway(bandcamp, 500, page = false, body = null))
    }

    @Test
    fun `it reads as a refused login everywhere a failure is read`() {
        val refused = SubsonicException("Server returned HTTP 500.", 500, loginRefused = true)
        assertTrue(isAuthFailure(refused))
        assertEquals("The server refused the login. Sign in again in Sources.", syncErrorMessage(refused))
        assertTrue(answeredBy(refused), "a server that turned the login away is there")
        assertEquals(WriteResult.Refused("The server refused the login"), writeResultOf(refused))
    }

    @Test
    fun `the same 500 without it stays the server's error`() {
        val error = SubsonicException("Server returned HTTP 500.", 500)
        assertFalse(isAuthFailure(error))
        assertEquals("The server had an error (500)", syncErrorMessage(error))
        assertFalse(answeredBy(error))
        assertEquals(WriteResult.Unreachable("The server had an error (500)"), writeResultOf(error))
    }
}

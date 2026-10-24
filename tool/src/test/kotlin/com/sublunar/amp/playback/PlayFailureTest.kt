package com.sublunar.amp.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayFailureTest {

    private fun failure(
        fromFile: Boolean = false,
        networkError: Boolean = true,
        badHttpStatus: Boolean = false,
        status: Int? = null,
        doorRefused: Boolean = false,
    ) = playFailure(fromFile, networkError, badHttpStatus, status, doorRefused)

    @Test
    fun `a file on the phone is the file's fault, whatever else is said`() {
        assertEquals(PlayFailure.FILE, failure(fromFile = true))
        assertEquals(PlayFailure.FILE, failure(fromFile = true, networkError = false, doorRefused = true))
    }

    @Test
    fun `a stream that arrived and wouldn't decode is the format's fault`() {
        assertEquals(PlayFailure.FORMAT, failure(networkError = false))
        assertEquals(PlayFailure.FORMAT, failure(networkError = false, badHttpStatus = true, status = 500))
    }

    @Test
    fun `a fetch the door turned away never reached the server`() {
        assertEquals(PlayFailure.UNASKED, failure(doorRefused = true))
        assertEquals(PlayFailure.UNASKED, failure(doorRefused = true, badHttpStatus = true, status = 503))
    }

    @Test
    fun `a server error is told apart from other bad statuses`() {
        assertEquals(PlayFailure.SERVER_ERROR, failure(badHttpStatus = true, status = 500))
        assertEquals(PlayFailure.SERVER_ERROR, failure(badHttpStatus = true, status = 599))
        assertEquals(PlayFailure.BAD_STATUS, failure(badHttpStatus = true, status = 404))
        // The player saw a bad status the door didn't hear: still the server's answer.
        assertEquals(PlayFailure.BAD_STATUS, failure(badHttpStatus = true, status = null))
    }

    @Test
    fun `a status only the door heard is not the player's bad status`() {
        assertEquals(PlayFailure.NO_ANSWER, failure(status = 500))
        assertEquals(PlayFailure.NO_ANSWER, failure())
    }
}

package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScannedLoginTest {

    @Test
    fun `username on the first line, password on the second`() {
        assertEquals(ScannedLogin("fan_8f3k2", "Zq9-x1!pWm"), ScannedLogin.parse("fan_8f3k2\nZq9-x1!pWm"))
    }

    @Test
    fun `surrounding spaces, blank lines and Windows line endings don't matter`() {
        assertEquals(ScannedLogin("user", "pass"), ScannedLogin.parse("  user  \r\n\r\n  pass \r\n"))
    }

    @Test
    fun `two words on one line are read the same way`() {
        assertEquals(ScannedLogin("user", "pass"), ScannedLogin.parse("user pass"))
    }

    @Test
    fun `anything that isn't exactly a username and a password is not guessed at`() {
        assertNull(ScannedLogin.parse(""))
        assertNull(ScannedLogin.parse("justoneword"))
        assertNull(ScannedLogin.parse("https://bandcamp.com/api/subsonic\nuser\npass"))
        assertNull(ScannedLogin.parse("one two three"))
    }
}

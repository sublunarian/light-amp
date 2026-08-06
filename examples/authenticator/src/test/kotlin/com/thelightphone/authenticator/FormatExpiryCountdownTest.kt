package com.thelightphone.authenticator

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatExpiryCountdownTest {
    @Test
    fun formatsSingleDigitSeconds() {
        assertEquals("0:09", formatExpiryCountdown(9))
    }

    @Test
    fun formatsFullMinute() {
        assertEquals("0:30", formatExpiryCountdown(30))
    }

    @Test
    fun formatsOverOneMinute() {
        assertEquals("1:05", formatExpiryCountdown(65))
    }
}

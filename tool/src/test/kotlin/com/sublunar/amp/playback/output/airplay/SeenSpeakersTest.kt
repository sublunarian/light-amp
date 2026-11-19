package com.sublunar.amp.playback.output.airplay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SeenSpeakersTest {
    private val minute = 60 * 1_000_000_000L

    @After
    fun clear() = AirPlayFamily.forgetSeen()

    @Test
    fun `a speaker is asked directly for 75 minutes after it last answered`() {
        AirPlayFamily.remember(listOf("192.168.2.152"), now = 0)
        assertEquals(listOf("192.168.2.152"), AirPlayFamily.recentlySeen(now = 75 * minute))
        assertEquals(emptyList<String>(), AirPlayFamily.recentlySeen(now = 76 * minute))
    }

    @Test
    fun `answering again starts its 75 minutes over`() {
        AirPlayFamily.remember(listOf("192.168.2.152"), now = 0)
        AirPlayFamily.remember(listOf("192.168.2.152"), now = 60 * minute)
        assertEquals(listOf("192.168.2.152"), AirPlayFamily.recentlySeen(now = 120 * minute))
    }
}

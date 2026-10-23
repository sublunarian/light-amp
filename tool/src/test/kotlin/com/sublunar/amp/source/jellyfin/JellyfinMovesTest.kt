package com.sublunar.amp.source.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which servers put a moved entry where they're asked: 10.10.2 to 10.10.7 put one moved down a place further. */
class JellyfinMovesTest {

    @Test
    fun `moves land where asked except on 10_10_2 to 10_10_7`() {
        assertEquals(true, JellyfinClient.movesLandWhereAsked("10.9.11"))
        assertEquals(true, JellyfinClient.movesLandWhereAsked("10.10.1"))
        assertEquals(false, JellyfinClient.movesLandWhereAsked("10.10.2"))
        assertEquals(false, JellyfinClient.movesLandWhereAsked("10.10.7"))
        assertEquals(true, JellyfinClient.movesLandWhereAsked("10.11.0"))
        assertEquals(true, JellyfinClient.movesLandWhereAsked("12.3.0"))
        assertEquals(null, JellyfinClient.movesLandWhereAsked("unstable"))
    }
}

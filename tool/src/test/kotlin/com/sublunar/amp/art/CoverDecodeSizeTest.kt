package com.sublunar.amp.art

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How far a cover is shrunk as it is decoded — see ArtworkLoader.calculateInSampleSize. */
class CoverDecodeSizeTest {

    private fun sample(width: Int, height: Int, target: Int) = ArtworkLoader.calculateInSampleSize(width, height, target)

    @Test
    fun `a square cover shrinks only as far as the size it is drawn at`() {
        assertEquals(1, sample(1080, 1080, 1080))
        assertEquals(4, sample(8000, 8000, 1080))
        assertEquals(4, sample(3000, 9000, 1080))
    }

    @Test
    fun `a long, thin picture is shrunk by its long side too`() {
        assertEquals(32, sample(600, 90_000, 1080))
        val tall = sample(200, 270_000, 128)
        assertTrue(270_000 / tall <= 4096, "sample $tall")
    }
}

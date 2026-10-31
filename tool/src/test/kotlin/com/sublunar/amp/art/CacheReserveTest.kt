package com.sublunar.amp.art

import kotlin.test.Test
import kotlin.test.assertEquals

class CacheReserveTest {
    private val mb = 1024L * 1024
    private val gb = 1024 * mb

    @Test
    fun `a small library gets the floor`() {
        assertEquals(150 * mb, CacheReserve.reserveFor(albums = 100, limitBytes = 5 * gb))
    }

    @Test
    fun `a library is about 281 KB an album with its growth`() {
        // 1,400 albums × 225 KB × 1.25 ≈ 385 MB.
        assertEquals(1400L * 225 * 1024 * 5 / 4, CacheReserve.reserveFor(albums = 1400, limitBytes = 10 * gb))
    }

    @Test
    fun `never more than a quarter of the limit`() {
        assertEquals(256 * mb, CacheReserve.reserveFor(albums = 1400, limitBytes = 1 * gb))
    }

    @Test
    fun `never more than a gigabyte`() {
        assertEquals(1 * gb, CacheReserve.reserveFor(albums = 10_000, limitBytes = 48 * gb))
    }
}

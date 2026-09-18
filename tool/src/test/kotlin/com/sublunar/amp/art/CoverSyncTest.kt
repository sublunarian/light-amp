package com.sublunar.amp.art

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CoverSyncTest {
    /** A disk and a server small enough to read at a glance. */
    private class World(
        onDisk: Set<String> = emptySet(),
        val server: MutableMap<String, CoverFetch> = mutableMapOf(),
        var diskBytes: Long = 0L,
        val budget: Long = 1_000L,
        /** What making room can bring the cache down to — the part that may not be dropped. */
        var floorBytes: Long = 0L,
    ) {
        val disk = onDisk.toMutableSet()
        val asked = mutableListOf<String>()
        val knownMissing = mutableSetOf<String>()
        var roomMade = 0
        var allowed: () -> Boolean = { true }

        fun sync() = CoverSync(
            isOnDisk = { it in disk },
            fetch = { id -> asked += id; server[id] ?: CoverFetch.None },
            store = { id, bytes -> (bytes.isNotEmpty()).also { ok -> if (ok) { disk += id; diskBytes += bytes.size } } },
            cacheBytes = { diskBytes },
            makeRoom = { roomMade++; diskBytes = minOf(diskBytes, maxOf(floorBytes, budget / 2)); diskBytes },
            budgetBytes = budget,
            mayContinue = { allowed() },
            knownMissing = knownMissing,
        )
    }

    private fun cover(size: Int) = CoverFetch.Got(ByteArray(size) { 1 })

    @Test
    fun onlyWhatIsMissingIsAskedFor() = runBlocking {
        val w = World(onDisk = setOf("a"), server = mutableMapOf("b" to cover(10), "c" to cover(10)))
        val result = w.sync().run(listOf("a", "b", "c", "b", ""))
        assertEquals(listOf("b", "c"), w.asked)
        assertEquals(CoverSyncResult(wanted = 3, missing = 2, fetched = 2, fetchedBytes = 20, stopped = null), result)
        assertTrue(w.disk.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun nothingMissingAsksNothing() = runBlocking {
        val w = World(onDisk = setOf("a", "b"))
        val result = w.sync().run(listOf("a", "b"))
        assertTrue(w.asked.isEmpty())
        assertEquals(0, result.missing)
        assertNull(result.stopped)
    }

    @Test
    fun aCoverTheServerDoesNotHaveIsAskedForOnce() = runBlocking {
        val w = World(server = mutableMapOf("b" to cover(10)))
        w.sync().run(listOf("gone", "b"))
        w.asked.clear()
        w.sync().run(listOf("gone", "b"))
        assertTrue(w.asked.isEmpty(), "asked again for ${w.asked}")
    }

    @Test
    fun anAnswerThatIsNotAPictureIsNotKeptAndNotAskedForAgain() = runBlocking {
        val w = World(server = mutableMapOf("junk" to CoverFetch.Got(ByteArray(0))))
        val result = w.sync().run(listOf("junk"))
        assertFalse("junk" in w.disk)
        assertEquals(0, result.fetched)
        assertTrue("junk" in w.knownMissing)
    }

    @Test
    fun aFailureIsTriedAgainNextTime() = runBlocking {
        val w = World(server = mutableMapOf("a" to CoverFetch.Failed("timeout")))
        w.sync().run(listOf("a"))
        assertFalse("a" in w.knownMissing)
        w.server["a"] = cover(10)
        val again = w.sync().run(listOf("a"))
        assertEquals(1, again.fetched)
    }

    @Test
    fun aServerThatStopsAnsweringIsLeftAlone() = runBlocking {
        val ids = (1..50).map { "c$it" }
        val w = World(server = ids.associateWith { CoverFetch.Failed("timeout") as CoverFetch }.toMutableMap())
        val result = w.sync().run(ids)
        assertEquals(5, w.asked.size)
        assertTrue(result.stopped!!.startsWith("server not answering"))
    }

    @Test
    fun failuresOnlyCountInARow() = runBlocking {
        val ids = (1..12).map { "c$it" }
        val w = World(
            server = ids.associateWith { id ->
                if (id.removePrefix("c").toInt() % 3 == 0) cover(1) else CoverFetch.Failed("blip")
            }.toMutableMap(),
        )
        val result = w.sync().run(ids)
        assertNull(result.stopped)
        assertEquals(4, result.fetched)
    }

    @Test
    fun itStopsTheMomentItIsNoLongerAllowed() = runBlocking {
        val ids = (1..10).map { "c$it" }
        val w = World(server = ids.associateWith { cover(1) as CoverFetch }.toMutableMap())
        w.allowed = { w.asked.size < 3 }
        val result = w.sync().run(ids)
        assertEquals(3, result.fetched)
        assertEquals("no longer allowed", result.stopped)
    }

    @Test
    fun overTheBudgetItMakesRoomAndCarriesOn() = runBlocking {
        val ids = (1..6).map { "c$it" }
        val w = World(server = ids.associateWith { cover(100) as CoverFetch }.toMutableMap(), diskBytes = 950L, budget = 1_000L)
        val result = w.sync().run(ids)
        assertNull(result.stopped)
        assertEquals(6, result.fetched)
        assertTrue(w.roomMade >= 1)
    }

    @Test
    fun whenNoRoomCanBeMadeItStopsRatherThanFetchToEvict() = runBlocking {
        val ids = (1..6).map { "c$it" }
        val w = World(
            server = ids.associateWith { cover(100) as CoverFetch }.toMutableMap(),
            diskBytes = 990L, budget = 1_000L, floorBytes = 5_000L,
        )
        val result = w.sync().run(ids)
        assertEquals("cache full", result.stopped)
        assertEquals(1, result.fetched)
    }
}

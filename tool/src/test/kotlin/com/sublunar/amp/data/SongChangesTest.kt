package com.sublunar.amp.data

import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * What a sync re-reads when an album's songs may have changed without its count
 * moving — see [rereadFor]. A wrong answer here is invisible: the library just
 * goes on showing what it first saw.
 */
class SongChangesTest {

    private val NOW = 1_800_000_000_000L

    private fun album(id: String) = Album(id, id, "A", null, 0, 1, null)
    private val albums = (1..70).map { album("a%02d".format(it)) }

    @Test
    fun `a server that names the changed albums gets exactly those re-read`() {
        val r = rereadFor(SongChanges.Albums(setOf("a03", "zz"), "m2"), SongCheck(mark = "m1"), albums, whole = true, bulk = false, nowMs = NOW)!!
        assertEquals(listOf("a03"), r.albums.map { it.id })
        assertEquals(SongCheck(mark = "m2"), r.next)
    }

    @Test
    fun `a sync of one library re-reads but doesn't move the mark`() {
        // Changes in the libraries it didn't fetch would be marked as read.
        val check = SongCheck(mark = "m1")
        val r = rereadFor(SongChanges.Albums(setOf("a03"), "m2"), check, albums, whole = false, bulk = true, nowMs = NOW)!!
        assertEquals(1, r.albums.size)
        assertEquals(check, r.next)
    }

    @Test
    fun `reading everything once waits for a link that allows it`() {
        assertNull(rereadFor(SongChanges.Everything("m1"), SongCheck(), albums, whole = true, bulk = false, nowMs = NOW))
        val r = rereadFor(SongChanges.Everything("m1"), SongCheck(), albums, whole = true, bulk = true, nowMs = NOW)!!
        assertEquals(albums.size, r.albums.size)
        // Every album read is a walk gone round: one that follows rests from here.
        assertEquals(SongCheck(mark = "m1", walkedMs = NOW), r.next)
    }

    @Test
    fun `a server that names everything it changed is never walked`() {
        val r = rereadFor(SongChanges.Albums(setOf("a03"), "m2"), SongCheck(mark = "m1"), albums, whole = true, bulk = true, nowMs = NOW)!!
        assertEquals(listOf("a03"), r.albums.map { it.id })
        assertEquals(SongCheck(mark = "m2"), r.next)
    }

    @Test
    fun `a server that names its changes and walks too gets both, each keeping its own place`() {
        // Jellyfin: its loudness scan saves gains without stamping the songs,
        // so its list of changes misses them.
        fun ids(range: IntRange) = range.map { "a%02d".format(it) }
        val first = rereadFor(SongChanges.Albums(setOf("a65"), "m2", alsoWalk = true), SongCheck(mark = "m1"), albums, whole = true, bulk = true, nowMs = NOW)!!
        assertEquals(ids(1..30) + "a65", first.albums.map { it.id })
        assertEquals(SongCheck(mark = "m2", walkCursor = "a30"), first.next)

        // The server's mark moves on every sync, mid-walk too, and the walk goes on from its own place.
        val second = rereadFor(SongChanges.Albums(setOf("a03"), "m3", alsoWalk = true), first.next, albums, whole = true, bulk = true, nowMs = NOW)!!
        assertEquals(listOf("a03") + ids(31..60), second.albums.map { it.id })
        assertEquals(SongCheck(mark = "m3", walkCursor = "a60"), second.next)

        // Gone round: the walk rests, and settles no mark of its own.
        val third = rereadFor(SongChanges.Albums(emptySet(), "m4", alsoWalk = true), second.next, albums, whole = true, bulk = true, nowMs = NOW)!!
        assertEquals(ids(61..70), third.albums.map { it.id })
        assertEquals(SongCheck(mark = "m4", walkedMs = NOW), third.next)
    }

    @Test
    fun `the walk beside a server's changes rests after a round, and the named albums are still read`() {
        val done = SongCheck(mark = "m4", walkedMs = NOW)
        val soon = rereadFor(SongChanges.Albums(setOf("a65"), "m5", alsoWalk = true), done, albums, whole = true, bulk = true, nowMs = NOW + 60_000)!!
        assertEquals(listOf("a65"), soon.albums.map { it.id })
        assertEquals(SongCheck(mark = "m5", walkedMs = NOW), soon.next)
        val later = rereadFor(SongChanges.Albums(setOf("a65"), "m5", alsoWalk = true), done, albums, whole = true, bulk = true, nowMs = NOW + WALK_REST_MS)!!
        assertEquals(WALK_BATCH + 1, later.albums.size)
        assertEquals(SongCheck(mark = "m5", walkCursor = "a30", walkedMs = NOW), later.next)
    }

    @Test
    fun `the walk beside a server's changes waits for a whole sync on a link that allows it`() {
        val midway = SongCheck(mark = "m1", walkCursor = "a30")
        val changes = SongChanges.Albums(setOf("a03"), "m2", alsoWalk = true)
        // Metered: the named albums are read and the mark moves; the walk stays where it was.
        val metered = rereadFor(changes, midway, albums, whole = true, bulk = false, nowMs = NOW)!!
        assertEquals(listOf("a03"), metered.albums.map { it.id })
        assertEquals(midway.copy(mark = "m2"), metered.next)
        // One library: read, but neither the mark nor the walk moves.
        val oneLibrary = rereadFor(changes, midway, albums, whole = false, bulk = true, nowMs = NOW)!!
        assertEquals(listOf("a03"), oneLibrary.albums.map { it.id })
        assertEquals(midway, oneLibrary.next)
    }

    @Test
    fun `a walk goes round a batch at a time and settles the mark it began under`() {
        var check = SongCheck(mark = "scan1")
        val seen = mutableListOf<String>()
        var rounds = 0
        do {
            // A second scan mid-walk doesn't move what this walk covers.
            val scan = if (rounds == 0) "scan2" else "scan3"
            val r = rereadFor(SongChanges.Unknown(scan), check, albums, whole = true, bulk = true, nowMs = NOW)!!
            seen += r.albums.map { it.id }
            check = r.next
            rounds++
        } while (check.walkCursor != null)
        assertEquals(albums.map { it.id }, seen)
        assertEquals(3, rounds)
        assertEquals(SongCheck(mark = "scan2", walkedMs = NOW), check)
    }

    @Test
    fun `a finished walk rests before the next one starts`() {
        val done = SongCheck(mark = "scan2", walkedMs = NOW)
        val soon = rereadFor(SongChanges.Unknown("scan3"), done, albums, whole = true, bulk = true, nowMs = NOW + 60_000)!!
        assertEquals(0, soon.albums.size)
        assertEquals(done, soon.next)
        val later = rereadFor(SongChanges.Unknown("scan3"), done, albums, whole = true, bulk = true, nowMs = NOW + WALK_REST_MS)!!
        assertEquals(WALK_BATCH, later.albums.size)
    }

    @Test
    fun `nothing changed means nothing re-read`() {
        val r = rereadFor(SongChanges.Nothing("m1"), SongCheck(mark = "m1"), albums, whole = true, bulk = true, nowMs = NOW)!!
        assertEquals(0, r.albums.size)
    }

    @Test
    fun `the server's clock is read from its Date header`() {
        assertEquals(Instant.parse("2026-10-12T05:26:38Z"), serverClock("Mon, 12 Oct 2026 05:26:38 GMT"))
    }

    @Test
    fun `a walk reaching the end hands back no cursor`() {
        assertEquals(listOf("c") to null, nextWalk(listOf("a", "b", "c"), "b", 5))
        assertEquals(listOf("a", "b") to "b", nextWalk(listOf("a", "b", "c"), null, 2))
    }
}

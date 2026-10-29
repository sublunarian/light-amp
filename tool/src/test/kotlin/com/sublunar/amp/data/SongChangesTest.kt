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
        val r = rereadFor(SongChanges.Albums(setOf("a03", "zz"), "m2"), SongCheck(mark = "m1"), albums, library = null, bulk = false, nowMs = NOW)!!
        assertEquals(listOf("a03"), r.albums.map { it.id })
        assertEquals(SongCheck(mark = "m2"), r.next)
    }

    @Test
    fun `a sync of one library moves that library's mark, not the source's`() {
        // Changes in the libraries it didn't fetch would be marked as read.
        val check = SongCheck(mark = "m1")
        val r = rereadFor(SongChanges.Albums(setOf("a03"), "m2"), check, albums, library = "L1", bulk = true, nowMs = NOW)!!
        assertEquals(1, r.albums.size)
        assertEquals(check.copy(libraryMarks = mapOf("L1" to "m2")), r.next)
    }

    @Test
    fun `a library asks from its own mark, and the others from the source's`() {
        val check = SongCheck(mark = "m1", libraryMarks = mapOf("L1" to "m2"))
        assertEquals("m2", check.markFor("L1"))
        assertEquals("m1", check.markFor("L2"))
        assertEquals("m1", check.markFor(null))
    }

    @Test
    fun `a sync of every library settles every library`() {
        val check = SongCheck(mark = "m1", libraryMarks = mapOf("L1" to "m2"))
        val r = rereadFor(SongChanges.Albums(setOf("a03"), "m3"), check, albums, library = null, bulk = true, nowMs = NOW)!!
        assertEquals(SongCheck(mark = "m3"), r.next)
    }

    @Test
    fun `one library with nothing to compare with yet is read once, then compared from`() {
        assertNull(rereadFor(SongChanges.Everything("m1"), SongCheck(), albums, library = "L1", bulk = false, nowMs = NOW))
        val r = rereadFor(SongChanges.Everything("m1"), SongCheck(), albums, library = "L1", bulk = true, nowMs = NOW)!!
        assertEquals(albums.size, r.albums.size)
        assertEquals(SongCheck(libraryMarks = mapOf("L1" to "m1")), r.next)
    }

    @Test
    fun `one library starts watching for changes from its first sync`() {
        // Plex with no mark yet: watched from now, for this library alone.
        val r = rereadFor(SongChanges.Nothing("m1"), SongCheck(), albums, library = "L1", bulk = true, nowMs = NOW)!!
        assertEquals(SongCheck(libraryMarks = mapOf("L1" to "m1")), r.next)
    }

    @Test
    fun `reading everything once waits for a link that allows it`() {
        assertNull(rereadFor(SongChanges.Everything("m1"), SongCheck(), albums, library = null, bulk = false, nowMs = NOW))
        val r = rereadFor(SongChanges.Everything("m1"), SongCheck(), albums, library = null, bulk = true, nowMs = NOW)!!
        assertEquals(albums.size, r.albums.size)
        // Every album read is a walk gone round: one that follows rests from here.
        assertEquals(SongCheck(mark = "m1", walkedMs = NOW), r.next)
    }

    @Test
    fun `a server that names everything it changed is never walked`() {
        val r = rereadFor(SongChanges.Albums(setOf("a03"), "m2"), SongCheck(mark = "m1"), albums, library = null, bulk = true, nowMs = NOW)!!
        assertEquals(listOf("a03"), r.albums.map { it.id })
        assertEquals(SongCheck(mark = "m2"), r.next)
    }

    @Test
    fun `a server that names its changes and walks too gets both, each keeping its own place`() {
        // Jellyfin: its loudness scan saves gains without stamping the songs,
        // so its list of changes misses them.
        fun ids(range: IntRange) = range.map { "a%02d".format(it) }
        val first = rereadFor(SongChanges.Albums(setOf("a65"), "m2", alsoWalk = true), SongCheck(mark = "m1"), albums, library = null, bulk = true, nowMs = NOW)!!
        assertEquals(ids(1..30) + "a65", first.albums.map { it.id })
        assertEquals(SongCheck(mark = "m2", walkCursor = "a30"), first.next)
        assertEquals(setOf("a65"), first.serverChanged)

        // The server's mark moves on every sync, mid-walk too, and the walk goes on from its own place.
        val second = rereadFor(SongChanges.Albums(setOf("a03"), "m3", alsoWalk = true), first.next, albums, library = null, bulk = true, nowMs = NOW)!!
        assertEquals(listOf("a03") + ids(31..60), second.albums.map { it.id })
        assertEquals(SongCheck(mark = "m3", walkCursor = "a60"), second.next)

        // Gone round: the walk rests, and settles no mark of its own.
        val third = rereadFor(SongChanges.Albums(emptySet(), "m4", alsoWalk = true), second.next, albums, library = null, bulk = true, nowMs = NOW)!!
        assertEquals(ids(61..70), third.albums.map { it.id })
        assertEquals(SongCheck(mark = "m4", walkedMs = NOW), third.next)
    }

    @Test
    fun `the walk beside a server's changes rests after a round, and the named albums are still read`() {
        val done = SongCheck(mark = "m4", walkedMs = NOW)
        val soon = rereadFor(SongChanges.Albums(setOf("a65"), "m5", alsoWalk = true), done, albums, library = null, bulk = true, nowMs = NOW + 60_000)!!
        assertEquals(listOf("a65"), soon.albums.map { it.id })
        assertEquals(SongCheck(mark = "m5", walkedMs = NOW), soon.next)
        val later = rereadFor(SongChanges.Albums(setOf("a65"), "m5", alsoWalk = true), done, albums, library = null, bulk = true, nowMs = NOW + WALK_REST_MS)!!
        assertEquals(WALK_BATCH + 1, later.albums.size)
        assertEquals(SongCheck(mark = "m5", walkCursor = "a30", walkedMs = NOW), later.next)
    }

    @Test
    fun `the walk beside a server's changes waits for a whole sync on a link that allows it`() {
        val midway = SongCheck(mark = "m1", walkCursor = "a30")
        val changes = SongChanges.Albums(setOf("a03"), "m2", alsoWalk = true)
        // Metered: the named albums are read and the mark moves; the walk stays where it was.
        val metered = rereadFor(changes, midway, albums, library = null, bulk = false, nowMs = NOW)!!
        assertEquals(listOf("a03"), metered.albums.map { it.id })
        assertEquals(midway.copy(mark = "m2"), metered.next)
        // One library: read, and its own mark moves; the source's mark and the walk stay.
        val oneLibrary = rereadFor(changes, midway, albums, library = "L1", bulk = true, nowMs = NOW)!!
        assertEquals(listOf("a03"), oneLibrary.albums.map { it.id })
        assertEquals(midway.copy(libraryMarks = mapOf("L1" to "m2")), oneLibrary.next)
    }

    @Test
    fun `a walk goes round a batch at a time and settles the mark it began under`() {
        var check = SongCheck(mark = "scan1")
        val seen = mutableListOf<String>()
        var rounds = 0
        do {
            // A second scan mid-walk doesn't move what this walk covers.
            val scan = if (rounds == 0) "scan2" else "scan3"
            val r = rereadFor(SongChanges.Unknown(scan), check, albums, library = null, bulk = true, nowMs = NOW)!!
            seen += r.albums.map { it.id }
            // Amp's own check, not news for the sync's progress.
            assertEquals(emptySet(), r.serverChanged)
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
        val soon = rereadFor(SongChanges.Unknown("scan3"), done, albums, library = null, bulk = true, nowMs = NOW + 60_000)!!
        assertEquals(0, soon.albums.size)
        assertEquals(done, soon.next)
        val later = rereadFor(SongChanges.Unknown("scan3"), done, albums, library = null, bulk = true, nowMs = NOW + WALK_REST_MS)!!
        assertEquals(WALK_BATCH, later.albums.size)
    }

    @Test
    fun `nothing changed means nothing re-read`() {
        val r = rereadFor(SongChanges.Nothing("m1"), SongCheck(mark = "m1"), albums, library = null, bulk = true, nowMs = NOW)!!
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

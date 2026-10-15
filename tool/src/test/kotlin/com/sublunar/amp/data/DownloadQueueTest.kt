package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The order downloads are fetched in, and where a failed one goes back to.
 * Every source's downloads share one queue, so most of these are about two
 * sources sharing it without getting in each other's way.
 */
class DownloadQueueTest {

    private fun track(id: String) = Track(
        id = id, title = id, artist = "", album = "", albumArtist = "", albumId = null,
        coverArtId = null, durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = 0, lastPlayedMs = 0,
    )

    private fun entry(source: String, id: String) = QueuedDownload(source, track(id))

    private val all = { _: String -> true }

    private fun DownloadQueue.drain(eligible: (String) -> Boolean = all): List<String> =
        generateSequence { next(eligible) }
            .map { "${it.entry.sourceId}:${it.entry.trackId}" }
            .toList()

    @Test
    fun `manual lane drains before the automatic one, each in arrival order`() {
        val q = DownloadQueue()
        q.add(entry("a", "auto1"), manual = false)
        q.add(entry("a", "auto2"), manual = false)
        q.add(entry("a", "hand1"), manual = true)
        q.add(entry("a", "hand2"), manual = true)
        assertEquals(listOf("a:hand1", "a:hand2", "a:auto1", "a:auto2"), q.drain())
    }

    @Test
    fun `a manual pick promotes a track already waiting automatically`() {
        val q = DownloadQueue()
        q.add(entry("a", "x"), manual = false)
        q.add(entry("a", "y"), manual = false)
        q.add(entry("a", "y"), manual = true)
        assertEquals(2, q.size)
        assertEquals(listOf("a:y", "a:x"), q.drain())
    }

    @Test
    fun `an automatic add of something asked for by hand is dropped`() {
        val q = DownloadQueue()
        q.add(entry("a", "x"), manual = true)
        q.add(entry("a", "x"), manual = false)
        assertEquals(1, q.size)
        assertEquals(true, q.next(all)?.manual)
    }

    @Test
    fun `the same track id from two sources is two entries`() {
        val q = DownloadQueue()
        q.add(entry("navidrome", "42"), manual = false)
        q.add(entry("plex", "42"), manual = false)
        assertEquals(2, q.size)
        assertEquals(setOf("navidrome", "plex"), q.sourceIds())
        assertEquals(listOf("navidrome:42", "plex:42"), q.drain())
    }

    @Test
    fun `a held source is skipped, not waited for`() {
        val q = DownloadQueue()
        q.add(entry("plex", "p1"), manual = false)
        q.add(entry("navidrome", "n1"), manual = false)
        q.add(entry("plex", "p2"), manual = false)
        val picked = q.next { it != "plex" }
        assertEquals("n1", picked?.entry?.trackId)
        // Nothing else qualifies, but the queue is not empty — the caller waits.
        assertNull(q.next { it != "plex" })
        assertFalse(q.isEmpty())
        assertEquals(setOf("plex"), q.sourceIds())
    }

    @Test
    fun `a held manual entry does not block another source's automatic one`() {
        val q = DownloadQueue()
        q.add(entry("plex", "hand"), manual = true)
        q.add(entry("navidrome", "auto"), manual = false)
        val picked = q.next { it != "plex" }
        assertEquals("navidrome:auto", "${picked?.entry?.sourceId}:${picked?.entry?.trackId}")
        assertEquals(false, picked?.manual)
    }

    @Test
    fun `a failure goes back to the front until the third, then to the back`() {
        val q = DownloadQueue()
        q.add(entry("a", "bad"), manual = false)
        q.add(entry("a", "good"), manual = false)
        repeat(DownloadQueue.FAILURES_BEFORE_BACK - 1) {
            val picked = q.next(all)!!
            assertEquals("bad", picked.entry.trackId)
            assertFalse(q.fail(picked))
        }
        val third = q.next(all)!!
        assertEquals("bad", third.entry.trackId)
        assertTrue(q.fail(third))
        assertEquals(listOf("a:good", "a:bad"), q.drain())
    }

    @Test
    fun `a success forgets the strikes`() {
        val q = DownloadQueue()
        q.add(entry("a", "x"), manual = false)
        val first = q.next(all)!!
        q.fail(first)
        q.fail(q.next(all)!!)
        val third = q.next(all)!!
        assertEquals(2, q.strikes(third.entry.key))
        q.complete(third.entry)
        assertEquals(0, q.strikes(third.entry.key))
    }

    @Test
    fun `requeueing at the front keeps the rest in order and adds no strike`() {
        val q = DownloadQueue()
        q.add(entry("a", "1"), manual = true)
        q.add(entry("a", "2"), manual = true)
        q.add(entry("a", "3"), manual = true)
        val picked = q.next(all)!!
        q.requeueFront(picked)
        assertEquals(0, q.strikes(picked.entry.key))
        assertEquals(listOf("a:1", "a:2", "a:3"), q.drain())
    }

    @Test
    fun `a failed entry returns to the lane it came from`() {
        val q = DownloadQueue()
        q.add(entry("a", "hand"), manual = true)
        q.add(entry("a", "auto"), manual = false)
        val hand = q.next(all)!!
        q.fail(hand)
        // Still ahead of the automatic lane — it went back to the manual one.
        assertEquals("hand", q.next(all)?.entry?.trackId)
    }

    @Test
    fun `removing a source takes its entries from both lanes and nothing else`() {
        val q = DownloadQueue()
        q.add(entry("plex", "p-hand"), manual = true)
        q.add(entry("plex", "p-auto"), manual = false)
        q.add(entry("navidrome", "n-hand"), manual = true)
        q.add(entry("navidrome", "n-auto"), manual = false)
        val picked = q.next(all)!!
        q.fail(picked)
        assertEquals(2, q.removeSource("plex"))
        assertEquals(0, q.strikes(picked.entry.key))
        assertEquals(listOf("navidrome:n-hand", "navidrome:n-auto"), q.drain())
    }

    @Test
    fun `an offline mode's entry is an id alone, and one by hand keeps its track`() {
        val auto = QueuedDownload("a", "x")
        val hand = entry("a", "y")
        assertNull(auto.track)
        assertEquals("y", hand.track?.id)
        assertEquals(DownloadQueue.Key("a", "x"), auto.key)
        assertEquals(DownloadQueue.Key("a", "y"), hand.key)
    }

    @Test
    fun `id entries drain in the order they were queued, behind the manual lane`() {
        val q = DownloadQueue()
        q.add(QueuedDownload("a", "1"), manual = false)
        q.add(QueuedDownload("b", "2"), manual = false)
        q.add(QueuedDownload("a", "3"), manual = false)
        q.add(entry("a", "hand"), manual = true)
        assertEquals(listOf("a:hand", "a:1", "b:2", "a:3"), q.drain())
    }

    @Test
    fun `asking by hand for a track waiting by id promotes it, carrying the track`() {
        val q = DownloadQueue()
        q.add(QueuedDownload("a", "x"), manual = false)
        q.add(QueuedDownload("a", "z"), manual = false)
        q.add(entry("a", "x"), manual = true)
        assertEquals(2, q.size)
        val picked = q.next(all)!!
        assertTrue(picked.manual)
        assertEquals("x", picked.entry.track?.id)
        assertEquals(listOf("a:z"), q.drain())
    }

    @Test
    fun `an id entry queued again by a later pass keeps its place and is not doubled`() {
        val q = DownloadQueue()
        q.add(QueuedDownload("a", "1"), manual = false)
        q.add(QueuedDownload("a", "2"), manual = false)
        q.add(QueuedDownload("a", "1"), manual = false)
        assertEquals(2, q.size)
        assertEquals(listOf("a:1", "a:2"), q.drain())
    }

    @Test
    fun `a later pass's equal id entry leaves the one waiting in place`() {
        val q = DownloadQueue()
        val first = QueuedDownload("a", "1")
        q.add(first, manual = false)
        q.add(QueuedDownload("a", "1"), manual = false)
        assertSame(first, q.next(all)!!.entry)
    }

    @Test
    fun `an id entry that fails goes back to the front of the automatic lane, still an id`() {
        val q = DownloadQueue()
        q.add(QueuedDownload("a", "1"), manual = false)
        q.add(QueuedDownload("a", "2"), manual = false)
        val picked = q.next(all)!!
        assertFalse(q.fail(picked))
        val again = q.next(all)!!
        assertEquals("1", again.entry.trackId)
        assertNull(again.entry.track)
        assertEquals(false, again.manual)
        assertEquals(1, q.strikes(again.entry.key))
    }

    @Test
    fun `removing a source drops its id entries too`() {
        val q = DownloadQueue()
        q.add(QueuedDownload("plex", "p1"), manual = false)
        q.add(QueuedDownload("navidrome", "n1"), manual = false)
        assertEquals(1, q.removeSource("plex"))
        assertEquals(listOf("navidrome:n1"), q.drain())
    }

    @Test
    fun `clear empties everything`() {
        val q = DownloadQueue()
        q.add(entry("a", "1"), manual = true)
        q.add(entry("b", "2"), manual = false)
        q.clear()
        assertTrue(q.isEmpty())
        assertEquals(0, q.size)
        assertEquals(emptySet(), q.sourceIds())
    }
}

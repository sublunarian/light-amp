package com.sublunar.amp.data

import com.sublunar.amp.data.db.TrackRow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Reading the library again keeps the songs that didn't change — see TrackRead.
 * Every read used to be a whole new library of objects, so what matters is
 * which objects come back the same, and that the list is still exactly what a
 * plain read of the table in storage order would be.
 */
class TrackReadTest {

    /** A fresh String every call, as each row read from the database has. */
    private fun fresh(s: String) = StringBuilder(s).toString()

    private fun song(id: String, album: String = "a1", plays: Int = 0, year: Int? = null) = Track(
        id = id, title = "Song $id", artist = fresh("Artist $album"), album = fresh("Album $album"),
        albumArtist = fresh("Artist $album"), albumId = fresh(album), coverArtId = fresh("cover-$album"),
        durationMs = 1_000, trackNumber = 1, discNumber = 1, year = year, playCount = plays, lastPlayedMs = 0,
        genre = fresh("Jazz"), composer = fresh(""),
    )

    /** The table: rows by rowid, as the keyset query hands them out. */
    private class Table(rows: Map<Long, Track>) {
        private val rows = rows.toSortedMap()
        var pagesRead = 0
        fun page(after: Long, limit: Int): List<TrackRow> {
            pagesRead++
            return rows.tailMap(after + 1).entries.take(limit).map { TrackRow(it.key, it.value) }
        }
        val plain: List<Track> get() = rows.values.toList()
    }

    private fun read(table: Table, previous: TrackGeneration = TrackGeneration.EMPTY, pageSize: Int = 3): Pair<TrackGeneration, Int> {
        val read = TrackRead(previous, previous.size)
        runBlocking {
            forEachTrackPage(pageSize, { after, limit -> table.page(after, limit) }) { page ->
                page.forEach { read.add(it.rowId, it.track) }
            }
        }
        return read.finish() to read.reused
    }

    private fun tableOf(vararg rows: Pair<Long, Track>) = Table(rows.toMap())

    @Test
    fun `a read gives the table in storage order`() {
        val table = tableOf(5L to song("e"), 1L to song("a"), 3L to song("c"), 9L to song("z"))
        val (generation, reused) = read(table)
        assertEquals(table.plain, generation.tracks)
        assertEquals(listOf("a", "c", "e", "z"), generation.tracks.map { it.id })
        assertEquals(0, reused)
    }

    @Test
    fun `unchanged rows come back as the same objects, and the same list`() {
        val first = read(tableOf(1L to song("a"), 2L to song("b"), 3L to song("c"))).first
        val (second, reused) = read(tableOf(1L to song("a"), 2L to song("b"), 3L to song("c")), first)
        assertEquals(3, reused)
        assertSame(first, second)
        assertSame(first.tracks, second.tracks)
    }

    @Test
    fun `a changed row is new and the rest are the same objects`() {
        val first = read(tableOf(1L to song("a"), 2L to song("b"), 3L to song("c"))).first
        val (second, reused) = read(tableOf(1L to song("a"), 2L to song("b", plays = 1), 3L to song("c")), first)
        assertEquals(2, reused)
        assertSame(first.tracks[0], second.tracks[0])
        assertNotSame(first.tracks[1], second.tracks[1])
        assertEquals(1, second.tracks[1].playCount)
        assertSame(first.tracks[2], second.tracks[2])
    }

    @Test
    fun `a changed row shares its album's strings with the row it replaces`() {
        val first = read(tableOf(1L to song("a"), 2L to song("b"))).first
        val second = read(tableOf(1L to song("a"), 2L to song("b", plays = 1)), first).first
        val before = first.tracks[1]
        val after = second.tracks[1]
        assertSame(before.album, after.album)
        assertSame(before.albumArtist, after.albumArtist)
        assertSame(before.albumId, after.albumId)
        assertSame(before.coverArtId, after.coverArtId)
        assertSame(before.genre, after.genre)
    }

    @Test
    fun `inserted rows are new and in their place`() {
        val first = read(tableOf(1L to song("a"), 4L to song("d"))).first
        val (second, reused) = read(tableOf(1L to song("a"), 2L to song("b"), 4L to song("d"), 7L to song("g")), first)
        assertEquals(listOf("a", "b", "d", "g"), second.tracks.map { it.id })
        assertEquals(2, reused)
        assertSame(first.tracks[0], second.tracks[0])
        assertSame(first.tracks[1], second.tracks[2])
    }

    @Test
    fun `deleted rows are gone and their neighbours kept`() {
        val first = read(tableOf(1L to song("a"), 2L to song("b"), 3L to song("c"), 4L to song("d"))).first
        val (second, reused) = read(tableOf(1L to song("a"), 4L to song("d")), first)
        assertEquals(listOf("a", "d"), second.tracks.map { it.id })
        assertEquals(2, reused)
        assertSame(first.tracks[0], second.tracks[0])
        assertSame(first.tracks[3], second.tracks[1])
    }

    @Test
    fun `a row deleted and stored again moves to its new place, once`() {
        // What replacing an album does: its songs leave their rowids and come
        // back at the end of the table.
        val first = read(tableOf(1L to song("a"), 2L to song("b"), 3L to song("c"))).first
        val table = tableOf(1L to song("a"), 3L to song("c"), 4L to song("b"))
        val second = read(table, first).first
        assertEquals(table.plain, second.tracks)
        assertEquals(listOf("a", "c", "b"), second.tracks.map { it.id })
        assertSame(first.tracks[2], second.tracks[1])
    }

    @Test
    fun `a rowid given to another song is that song`() {
        // Without AUTOINCREMENT a rowid freed at the end of the table is handed out again.
        val first = read(tableOf(1L to song("a"), 2L to song("b"))).first
        val (second, reused) = read(tableOf(1L to song("a"), 2L to song("x", album = "a2")), first)
        assertEquals(listOf("a", "x"), second.tracks.map { it.id })
        assertEquals("Album a2", second.tracks[1].album)
        assertEquals(1, reused)
    }

    @Test
    fun `new rows of one album share one string per value`() {
        val (generation, _) = read(
            tableOf(1L to song("a", year = 1999), 2L to song("b", year = 1999), 3L to song("c", album = "a2")),
        )
        val (a, b, c) = generation.tracks
        assertSame(a.album, b.album)
        assertSame(a.albumArtist, b.albumArtist)
        assertSame(a.artist, b.artist)
        assertSame(a.albumId, b.albumId)
        assertSame(a.coverArtId, b.coverArtId)
        assertSame(a.genre, b.genre)
        assertSame(a.genre, c.genre)
        assertSame(a.composer, c.composer)
        assertSame(a.year, b.year)
        assertNotSame(a.album, c.album)
        assertEquals("Album a2", c.album)
    }

    @Test
    fun `the same list wherever the pages break`() {
        val rows = (1L..23L).filter { it % 4 != 0L }.associateWith { song("s$it", album = "a${it / 5}") }
        val table = Table(rows)
        val first = read(table, pageSize = 1).first
        assertEquals(table.plain, first.tracks)
        for (pageSize in 1..rows.size + 2) {
            val (again, reused) = read(table, first, pageSize)
            assertSame(first.tracks, again.tracks, "page size $pageSize")
            assertEquals(rows.size, reused)
            val cold = read(table, pageSize = pageSize).first
            assertEquals(table.plain, cold.tracks, "page size $pageSize")
            assertEquals(cold.tracks.size, cold.tracks.map { it.id }.toSet().size, "page size $pageSize")
        }
    }

    @Test
    fun `a page that ends the table ends the read`() {
        val table = tableOf(1L to song("a"), 2L to song("b"), 3L to song("c"))
        read(table, pageSize = 2)
        assertEquals(2, table.pagesRead)
        read(Table(emptyMap()), pageSize = 2).first.also { assertEquals(emptyList(), it.tracks) }
    }

    @Test
    fun `a read cancelled between pages reads no further`() {
        // A source switch, or a newer read asked for, while a library is read.
        val table = Table((1L..10L).associateWith { song("s$it") })
        runBlocking {
            launch {
                val reading = coroutineContext.job
                forEachTrackPage(2, { after, limit -> table.page(after, limit) }) {
                    if (table.pagesRead == 2) reading.cancel()
                }
            }.join()
        }
        assertEquals(2, table.pagesRead)
    }

    @Test
    fun `a row handed over twice is kept once`() {
        val read = TrackRead(TrackGeneration.EMPTY, 0)
        read.add(1, song("a"))
        read.add(2, song("b"))
        read.add(2, song("b"))
        read.add(1, song("a"))
        read.add(3, song("c"))
        assertEquals(listOf("a", "b", "c"), read.finish().tracks.map { it.id })
    }
}

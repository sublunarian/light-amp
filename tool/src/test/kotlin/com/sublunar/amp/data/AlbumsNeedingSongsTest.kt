package com.sublunar.amp.data

import com.sublunar.amp.data.db.AlbumEntity
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Which albums a sync reads the songs of — see [albumsNeedingSongs]. Too few and
 * the library keeps songs the server changed; too many and every sync walks the
 * whole library.
 */
class AlbumsNeedingSongsTest {

    private fun album(id: String, songs: Int, updatedMs: Long = 0) =
        Album(id, id, "A", null, 0, songs, null, updatedMs = updatedMs)

    private fun row(id: String, songs: Int, updatedMs: Long = 0) = AlbumEntity(
        id = id, title = id, artist = "A", coverArtId = null, durationMs = 0, songCount = songs,
        year = null, releaseDate = 0, createdMs = 0, updatedMs = updatedMs, playCount = 0,
        lastPlayedMs = 0, liked = false,
    )

    @Test
    fun `only new, emptied and re-counted albums are read, each with its reason`() {
        val server = listOf(
            album("new", 3),
            album("emptied", 3),
            album("recounted", 4),
            album("short", 3),
            album("same", 3),
        )
        val cached = listOf(row("emptied", 3), row("recounted", 3), row("short", 3), row("same", 3)).associateBy { it.id }
        val counts = mapOf("recounted" to 3, "short" to 2, "same" to 3)

        val fetch = albumsNeedingSongs(server, cached, counts, reread = null, refill = false)

        assertEquals(listOf("new", "emptied", "recounted", "short"), fetch.albums.map { it.id })
        assertEquals(
            mapOf(
                "new album" to 1,
                "no songs cached" to 1,
                "server's count changed" to 1,
                "cache short of the count" to 1,
            ),
            fetch.reasons,
        )
        assertEquals(0, fetch.untouched)
    }

    @Test
    fun `an album sent with no count is read when the server touched it, and left alone when it didn't`() {
        val server = listOf(album("touched", 0, updatedMs = 2), album("still", 0, updatedMs = 1))
        val cached = listOf(row("touched", 0, updatedMs = 1), row("still", 0, updatedMs = 1)).associateBy { it.id }
        val counts = mapOf("touched" to 5, "still" to 5)

        val fetch = albumsNeedingSongs(server, cached, counts, reread = null, refill = false)

        assertEquals(listOf("touched"), fetch.albums.map { it.id })
        assertEquals(mapOf("album changed on the server" to 1), fetch.reasons)
        assertEquals(1, fetch.untouched)
    }

    @Test
    fun `a reread adds its albums once, after the changed ones`() {
        val server = listOf(album("new", 3), album("a", 3), album("b", 3))
        val cached = listOf(row("a", 3), row("b", 3)).associateBy { it.id }
        val counts = mapOf("a" to 3, "b" to 3)
        val reread = Reread(listOf(album("new", 3), album("b", 3)), "walk", SongCheck())

        val fetch = albumsNeedingSongs(server, cached, counts, reread, refill = false)

        assertEquals(listOf("new", "b"), fetch.albums.map { it.id })
        assertEquals(mapOf("new album" to 1, "walk" to 1), fetch.reasons)
    }

    @Test
    fun `a parser refill reads every album`() {
        val server = listOf(album("a", 3), album("b", 3))
        val cached = server.map { row(it.id, 3) }.associateBy { it.id }
        val counts = mapOf("a" to 3, "b" to 3)

        val fetch = albumsNeedingSongs(server, cached, counts, Reread(listOf(album("a", 3)), "walk", SongCheck()), refill = true)

        assertEquals(listOf("a", "b"), fetch.albums.map { it.id })
        assertEquals(emptyMap(), fetch.reasons)
        assertEquals(emptySet(), fetch.news)
    }

    @Test
    fun `only albums new or changed on the server are news, not Amp's own checks`() {
        val server = listOf(
            album("new", 3),
            album("emptied", 3),
            album("recounted", 4),
            album("short", 3),
            album("walked", 3),
            album("named", 3),
        )
        val cached = listOf("emptied", "short", "walked", "named").associateWith { row(it, 3) } + ("recounted" to row("recounted", 3))
        val counts = mapOf("recounted" to 3, "short" to 2, "walked" to 3, "named" to 3)
        val reread = Reread(listOf(album("walked", 3), album("named", 3)), "server says changed, and walk", SongCheck(), serverChanged = setOf("named"))

        val fetch = albumsNeedingSongs(server, cached, counts, reread, refill = false)

        assertEquals(listOf("new", "emptied", "recounted", "short", "walked", "named"), fetch.albums.map { it.id })
        assertEquals(setOf("new", "emptied", "recounted", "named"), fetch.news)
    }

    @Test
    fun `a sync with nothing new has no news to count, however much it checks`() {
        val server = listOf(album("short", 3), album("a", 3), album("b", 3))
        val cached = server.map { row(it.id, 3) }.associateBy { it.id }
        val counts = mapOf("short" to 2, "a" to 3, "b" to 3)
        val walk = Reread(listOf(album("a", 3), album("b", 3)), "walk", SongCheck())

        val fetch = albumsNeedingSongs(server, cached, counts, walk, refill = false)

        assertEquals(3, fetch.albums.size)
        assertEquals(emptySet(), fetch.news)
    }

    @Test
    fun `an album the server named is news even when it was also short`() {
        val server = listOf(album("short", 3))
        val cached = mapOf("short" to row("short", 3))
        val counts = mapOf("short" to 2)
        val named = Reread(listOf(album("short", 3)), "server says changed", SongCheck(), serverChanged = setOf("short"))

        val fetch = albumsNeedingSongs(server, cached, counts, named, refill = false)

        assertEquals(listOf("short"), fetch.albums.map { it.id })
        assertEquals(setOf("short"), fetch.news)
    }
}

package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a library shows: the Songs tab and a playlist go by this one rule, so a
 * playlist under a chosen library holds exactly that library's songs.
 */
class LibraryViewTest {

    private val all = LibraryView(null, emptySet())
    private val music = LibraryView("music", emptySet())
    private val hidingOther = LibraryView(null, setOf("other"))

    @Test
    fun `nothing chosen and nothing hidden leaves nothing out`() {
        assertFalse(all.narrows)
        assertTrue(all.showsAlbum("music"))
        assertTrue(all.showsSong("a", hasAlbum = false, libraryId = null))
    }

    @Test
    fun `a chosen library shows its own albums and untagged ones, not another's`() {
        assertTrue(music.narrows)
        assertTrue(music.showsAlbum("music"))
        assertTrue(music.showsAlbum(null))
        assertFalse(music.showsAlbum("other"))
    }

    @Test
    fun `a hidden library never shows`() {
        assertFalse(hidingOther.showsAlbum("other"))
        assertTrue(hidingOther.showsAlbum("music"))
        assertFalse(LibraryView("other", setOf("other")).showsAlbum("other"))
    }

    @Test
    fun `a narrowed view leaves out a song it can't place`() {
        assertTrue(music.showsSong("a", hasAlbum = true, libraryId = "music"))
        assertFalse(music.showsSong("a", hasAlbum = true, libraryId = "other"))
        assertFalse(music.showsSong("a", hasAlbum = false, libraryId = null))
        assertTrue(music.showsSong(null, hasAlbum = false, libraryId = null))
    }

    @Test
    fun `the download mark goes by the songs shown`() {
        val ids = listOf("m1", "o1", "m2")
        assertTrue(playlistMarked(ids, setOf("m1", "m2"), shown = setOf("m1", "m2")))
        assertFalse(playlistMarked(ids, setOf("m1", "m2"), shown = null))
        assertFalse(playlistMarked(ids, setOf("m1"), shown = setOf("m1", "m2")))
        assertFalse(playlistMarked(ids, setOf("o1"), shown = emptySet()))
        assertFalse(playlistMarked(emptyList(), emptySet(), shown = null))
    }

    @Test
    fun `where the list follows the library, a playlist is listed by its songs`() {
        val music = setOf("m1", "m2")
        assertTrue(playlistListed(listOf("o1", "m2"), music, chosen = true))
        assertFalse(playlistListed(listOf("o1", "o2"), music, chosen = true))
        assertTrue(playlistListed(null, music, chosen = true))
        assertFalse(playlistListed(emptyList(), music, chosen = true))
        assertTrue(playlistListed(emptyList(), music, chosen = false))
    }

    @Test
    fun `a reorder must hold exactly the playlist's songs`() {
        assertTrue(isReorderOf(listOf("b", "a", "a"), listOf("a", "b", "a")))
        assertFalse(isReorderOf(listOf("a", "b", "c"), listOf("a", "b", "a")))
        assertFalse(isReorderOf(listOf("a", "b"), listOf("a", "b", "c")))
    }
}

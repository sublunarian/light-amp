package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryShownTest {

    private val three = MusicSource(
        id = "s",
        kind = SourceKind.SUBSONIC,
        name = "Navidrome",
        libraries = listOf(SourceLibrary("1", "Music"), SourceLibrary("3", "Kids"), SourceLibrary("5", "Live")),
    )

    @Test
    fun `hiding one of three keeps All Libraries and the browsed library`() {
        val after = three.copy(libraryId = "1").withLibraryShown("5", shown = false)
        assertEquals(listOf("5"), after.hiddenLibraryIds)
        assertEquals("1", after.libraryId)
        assertTrue(after.showsAllLibraries)
    }

    @Test
    fun `hiding the browsed library moves to All Libraries`() {
        val after = three.copy(libraryId = "3").withLibraryShown("3", shown = false)
        assertNull(after.libraryId)
    }

    @Test
    fun `hiding down to one drops All Libraries and browses the one left`() {
        val after = three.copy(libraryId = "1")
            .withLibraryShown("3", shown = false)
            .withLibraryShown("5", shown = false)
        assertEquals(listOf("3", "5"), after.hiddenLibraryIds)
        assertNull(after.libraryId)
        assertFalse(after.showsAllLibraries)
    }

    @Test
    fun `the last library can't be hidden`() {
        val one = three.withLibraryShown("3", shown = false).withLibraryShown("5", shown = false)
        assertEquals(one, one.withLibraryShown("1", shown = false))
    }

    @Test
    fun `an old hidden All Libraries row is dropped`() {
        val old = three.copy(hiddenLibraryIds = listOf(null, "5"))
        assertTrue(old.showsAllLibraries)
        assertEquals(listOf("5"), old.withLibraryShown("3", shown = true).hiddenLibraryIds)
    }

    @Test
    fun `showing a library again brings back All Libraries`() {
        val one = three.withLibraryShown("3", shown = false).withLibraryShown("5", shown = false)
        assertTrue(one.withLibraryShown("5", shown = true).showsAllLibraries)
    }
}

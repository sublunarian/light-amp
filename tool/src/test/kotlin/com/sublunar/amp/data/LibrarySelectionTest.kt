package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which libraries a source browses, as stored now and as older builds stored it. */
class LibrarySelectionTest {

    private val source = MusicSource(id = "s", kind = SourceKind.SUBSONIC, name = "Navidrome")

    @Test
    fun `nothing chosen is every library`() {
        assertNull(source.selectedLibraries)
    }

    @Test
    fun `one library is stored as it always was, and read the same`() {
        assertEquals(setOf("1"), source.copy(libraryId = "1").selectedLibraries)
    }

    @Test
    fun `several are read from the list`() {
        assertEquals(setOf("1", "5"), source.copy(libraryIds = listOf("1", "5")).selectedLibraries)
        // An empty list is no choice at all: every library.
        assertNull(source.copy(libraryIds = emptyList()).selectedLibraries)
    }
}

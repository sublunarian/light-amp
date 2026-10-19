package com.sublunar.amp.data

import com.sublunar.amp.data.db.libraryDatabaseName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Names that are stored, and read back by every later build: enum names in
 * the sources list, format ids, track ids and file names. Renaming one in the
 * code is renaming it on every phone that has it saved.
 */
class PersistedNamesTest {

    @Test
    fun `kinds of source are stored by name`() {
        assertEquals(listOf("SUBSONIC", "PLEX", "JELLYFIN", "LOCAL"), SourceKind.entries.map { it.name })
    }

    @Test
    fun `formats are stored by id, and anything else reads as the default`() {
        assertEquals(listOf("mp3", "opus", "flac", "raw"), StreamFormat.entries.map { it.id })
        assertEquals(StreamFormat.OPUS, StreamFormat.DEFAULT)
        assertEquals(StreamFormat.OPUS, StreamFormat.fromId(null))
        assertEquals(StreamFormat.OPUS, StreamFormat.fromId("x"))
    }

    @Test
    fun `offline modes are stored by name`() {
        assertEquals(listOf("MANUAL", "FAVORITES", "ALL"), OfflineMode.entries.map { it.name })
    }

    @Test
    fun `a phone file's track id is its path`() {
        assertEquals("file:", LocalLibrary.ID_PREFIX)
    }

    @Test
    fun `each source has a database file of its own, the first server the original one`() {
        assertEquals("amp-library.db", libraryDatabaseName("server"))
        assertEquals("amp-library.db-src-1", libraryDatabaseName("src-1"))
    }

    @Test
    fun `the two ids that are not generated`() {
        assertEquals("server", AppSettings.LEGACY_SOURCE_ID)
        assertEquals("local", MusicSource.LOCAL_ID)
    }
}

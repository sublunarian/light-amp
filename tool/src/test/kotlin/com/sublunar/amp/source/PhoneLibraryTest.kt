package com.sublunar.amp.source

import com.sublunar.amp.source.local.LocalPlaylists
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.source.local.LocalLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Which sources are music on this phone, and what the screens and the scan
 * read off it. Nothing here asks LightOS anything: only names are read.
 */
class PhoneLibraryTest {

    private fun source(kind: SourceKind) = MusicSource(id = "s", kind = kind, name = "Source")

    @Test
    fun `only the phone's own music is a phone library, and only it has no server`() {
        assertEquals(listOf(SourceKind.LOCAL), SourceKind.entries.filter { source(it).phoneLibrary != null })
        assertEquals(listOf(SourceKind.LOCAL), SourceKind.entries.filterNot { source(it).hasServer })
    }

    @Test
    fun `its one library is the phone itself`() {
        assertEquals("On This Phone", source(SourceKind.LOCAL).phoneLibrary!!.label)
    }

    @Test
    fun `its playlists are the m3u8 files`() {
        assertSame<PhonePlaylists>(LocalPlaylists, LocalLibrary.playlists)
    }
}

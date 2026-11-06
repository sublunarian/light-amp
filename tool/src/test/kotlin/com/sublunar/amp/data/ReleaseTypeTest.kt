package com.sublunar.amp.data

import com.sublunar.amp.ui.screens.recordGroups
import com.sublunar.amp.ui.screens.knowsReleaseTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How an album's release types decide its group on an artist's page. */
class ReleaseTypeTest {

    @Test
    fun `the most specific type wins`() {
        assertEquals(ReleaseType.ALBUM, ReleaseType.of(listOf("album")))
        assertEquals(ReleaseType.LIVE, ReleaseType.of(listOf("album", "live")))
        assertEquals(ReleaseType.SOUNDTRACK, ReleaseType.of(listOf("Album", "Soundtrack", "Compilation")))
        assertEquals(ReleaseType.LIVE, ReleaseType.of(listOf("single; live")))
        assertEquals(ReleaseType.EP, ReleaseType.of(listOf("EP")))
        assertEquals(ReleaseType.SINGLE, ReleaseType.of(listOf("single")))
        assertEquals(ReleaseType.COMPILATION, ReleaseType.of(listOf("album"), compilation = true))
        // Types it doesn't group by are albums, as is nothing at all.
        assertEquals(ReleaseType.ALBUM, ReleaseType.of(listOf("broadcast", "spokenword")))
        assertEquals(ReleaseType.ALBUM, ReleaseType.of(emptyList()))
    }

    @Test
    fun `stored names read back, and old rows are albums`() {
        assertEquals(ReleaseType.EP, ReleaseType.stored("EP"))
        assertEquals(ReleaseType.ALBUM, ReleaseType.stored(""))
    }

    private fun album(id: String, type: ReleaseType) = Album(
        id = id, title = id, artist = "A", coverArtId = null, durationMs = 0, songCount = 1, year = null,
        releaseType = type,
    )

    @Test
    fun `groups follow the page's order, Singles and EPs as one, each keeping the order it came in`() {
        val albums = listOf(
            album("s1", ReleaseType.SINGLE), album("a1", ReleaseType.ALBUM), album("l1", ReleaseType.LIVE),
            album("s2", ReleaseType.SINGLE), album("e1", ReleaseType.EP), album("st", ReleaseType.SOUNDTRACK),
        )
        assertEquals(
            listOf(
                "Albums" to listOf("a1"),
                "Singles & EPs" to listOf("s1", "s2", "e1"),
                "Soundtracks" to listOf("st"),
                "Live Albums" to listOf("l1"),
            ),
            recordGroups(albums).map { it.heading to it.albums.map { a -> a.id } },
        )
    }

    @Test
    fun `release types are known only where something is more than an album`() {
        assertFalse(knowsReleaseTypes(listOf(album("a", ReleaseType.ALBUM))))
        assertTrue(knowsReleaseTypes(listOf(album("a", ReleaseType.ALBUM), album("e", ReleaseType.EP))))
    }
}

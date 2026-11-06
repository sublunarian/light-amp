package com.sublunar.amp.source.plex

import com.sublunar.amp.data.ReleaseType
import kotlin.test.assertEquals
import org.junit.Test

/** How an album's type is put together from Plex's type filters — see typesByAlbum. */
class PlexReleaseTypeTest {

    @Test
    fun `each album gets every type whose filter returned it`() {
        val types = typesByAlbum(listOf("EP" to listOf("1", "2"), "Live" to listOf("2"), "Single" to emptyList()), total = 10)
        assertEquals(mapOf("1" to listOf("EP"), "2" to listOf("EP", "Live")), types)
        assertEquals(ReleaseType.LIVE, ReleaseType.of(types.getValue("2")))
        assertEquals(ReleaseType.EP, ReleaseType.of(types.getValue("1")))
    }

    @Test
    fun `a filter that returns the whole section was ignored, so it types nothing`() {
        val ignored = mutableListOf<String>()
        val types = typesByAlbum(
            listOf("EP" to listOf("1", "2", "3"), "Live" to listOf("3")),
            total = 3,
        ) { ignored += it }
        assertEquals(mapOf("3" to listOf("Live")), types)
        assertEquals(listOf("EP"), ignored)
    }

    @Test
    fun `a one-album section keeps its answer`() {
        assertEquals(mapOf("1" to listOf("Single")), typesByAlbum(listOf("Single" to listOf("1")), total = 1))
    }
}

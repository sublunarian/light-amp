package com.sublunar.amp.source

import com.sublunar.amp.data.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every kind has a provider. Looked up in a list, a missing one would be a
 * crash on the first source of that kind rather than a compile error, so it is
 * checked here.
 */
class ProvidersTest {

    @Test
    fun `every kind has exactly one provider, and it is that kind's`() {
        for (kind in SourceKind.entries) {
            assertEquals(1, Providers.all.count { it.kind == kind }, "$kind")
            assertEquals(kind, Providers.of(kind).kind)
        }
    }

    @Test
    fun `the kinds are offered in this order`() {
        assertEquals(
            listOf(SourceKind.LOCAL, SourceKind.PLEX, SourceKind.JELLYFIN, SourceKind.SUBSONIC),
            Providers.all.map { it.kind },
        )
    }
}

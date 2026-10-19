package com.sublunar.amp.source

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The Connection row on each kind's own page, and Log Out's word to the
 * server — as SourceDetailScreen had them before each kind said its own.
 */
class ConnectionRowTest {

    private fun source(kind: SourceKind) = MusicSource(id = "s", kind = kind, name = "Source")

    /** What the row says under the address, and whether it opens anything; "none" is no row. */
    private fun rowOf(kind: SourceKind): String {
        val source = source(kind)
        val row = source.provider.ui.connection(source) ?: return "none"
        return "${row.subtitle ?: "-"}, ${if (row.open == null) "states" else "opens"}"
    }

    @Test
    fun `each kind's Connection row`() {
        assertEquals(
            mapOf(
                SourceKind.SUBSONIC to "-, opens",
                SourceKind.PLEX to "-, states",
                SourceKind.JELLYFIN to "Sign in again, opens",
                SourceKind.LOCAL to "none",
            ),
            SourceKind.entries.associateWith { rowOf(it) },
        )
    }

    /** No kind is asked anything here: none has a client without its token, so nothing reaches the network. */
    @Test
    fun `Log Out's word to the server returns for every kind, and needs a client to say anything`() = runBlocking {
        for (kind in SourceKind.entries) {
            val source = source(kind).copy(baseUrl = "https://music.example", userId = "u1")
            source.provider.signOut(source)
        }
    }
}

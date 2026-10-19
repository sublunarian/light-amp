package com.sublunar.amp.source.plex

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.source.subsonic.SubsonicProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * Which addresses a Plex source tries after a failed sync, and in what order.
 * Nothing here reaches the network: what does is only asked with addresses.
 */
class PlexRelocationTest {

    private val lan = "http://192.168.1.10:32400"
    private val remote = "https://203-0-113-7.abc.plex.direct:32400"

    private fun plex(baseUrl: String, connections: List<String>) = MusicSource(
        id = "p",
        kind = SourceKind.PLEX,
        name = "Plex",
        baseUrl = baseUrl,
        token = "tok",
        connections = connections,
    )

    @Test
    fun `the other addresses come first, and the one that failed last`() {
        assertEquals(listOf(remote, lan), relocationOrder(plex(lan, listOf(lan, remote))))
    }

    @Test
    fun `an address that isn't among the advertised ones is still tried, last`() {
        val elsewhere = "http://plex.example:32400"
        assertEquals(listOf(lan, remote, elsewhere), relocationOrder(plex(elsewhere, listOf(lan, remote))))
    }

    @Test
    fun `with nothing else advertised there is nowhere to go`() = runBlocking {
        assertNull(PlexProvider.relocate(plex(lan, emptyList())))
    }

    @Test
    fun `a Subsonic source keeps the one address it was given`() = runBlocking {
        val subsonic = MusicSource("s", SourceKind.SUBSONIC, "Navidrome", baseUrl = lan, connections = listOf(remote))
        assertNull(SubsonicProvider.relocate(subsonic))
    }
}

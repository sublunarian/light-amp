package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a playlist page is shown, and when. The case that started it: off the
 * network, with every song downloaded, the page waited on a server that
 * couldn't answer and opened empty.
 */
class PlaylistUpdatesTest {

    private fun track(id: String) = Track(
        id = id, title = id, artist = "", album = "", albumArtist = "", albumId = null,
        coverArtId = null, durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = 0, lastPlayedMs = 0,
    )

    private fun view(vararg ids: String, complete: Boolean) = PlaylistView(ids.map(::track), complete)

    private val stored = view("a", "b", complete = false)
    private val server = view("a", "b", "c", complete = true)

    @Test
    fun `the stored copy comes first, then the server's`() = runBlocking {
        val answers = playlistUpdates(stored = { stored }, server = { server }).toList()
        assertEquals(listOf(stored, server), answers)
    }

    @Test
    fun `the stored copy is shown without waiting for the server`() = runBlocking {
        // A server that never answers — the dead LAN address. The first
        // answer still comes, at once.
        val first = withTimeoutOrNull(1_000) {
            playlistUpdates(stored = { stored }, server = { delay(60_000); server }).first()
        }
        assertEquals(stored, first)
    }

    @Test
    fun `no server answer leaves the stored copy standing`() = runBlocking {
        assertEquals(listOf(stored), playlistUpdates(stored = { stored }, server = { null }).toList())
    }

    @Test
    fun `nothing stored waits for the server`() = runBlocking {
        val empty = view(complete = false)
        assertEquals(listOf(server), playlistUpdates(stored = { empty }, server = { server }).toList())
    }

    @Test
    fun `nothing from either still ends the loading, once`() = runBlocking {
        val empty = view(complete = false)
        assertEquals(listOf(empty), playlistUpdates(stored = { empty }, server = { null }).toList())
    }

    @Test
    fun `a playlist the server says is empty is shown empty`() = runBlocking {
        val emptied = view(complete = true)
        assertEquals(listOf(stored, emptied), playlistUpdates(stored = { stored }, server = { emptied }).toList())
    }
}

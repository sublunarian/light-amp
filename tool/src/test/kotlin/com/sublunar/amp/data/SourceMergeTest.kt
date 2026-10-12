package com.sublunar.amp.data

import com.sublunar.amp.data.PlexClient.Companion.headerSafe
import kotlin.test.assertEquals
import org.junit.Test

/** What saving a source does to the list it joins — see [mergeSource]. */
class SourceMergeTest {

    private val jellyfin = MusicSource(
        id = "src-old",
        kind = SourceKind.JELLYFIN,
        name = "Jellyfin",
        baseUrl = "http://192.168.1.10:8096",
        token = "old-token",
        userId = "user-guid",
        streamFormatId = StreamFormat.MP3.id,
        playerName = "Kitchen",
    )

    @Test
    fun `signing the same Jellyfin account in again updates its source`() {
        // A fresh sign-in mints a fresh id; without the match it became a second
        // source over an empty library, and logged the first one out.
        val again = MusicSource(
            id = "src-new",
            kind = SourceKind.JELLYFIN,
            name = "Jellyfin",
            baseUrl = "https://jf.example",
            token = "new-token",
            userId = "user-guid",
            deviceId = "amp-1",
        )
        val (list, id) = mergeSource(listOf(jellyfin), again)
        assertEquals("src-old", id)
        val saved = list.single()
        assertEquals("new-token", saved.token)
        assertEquals("amp-1", saved.deviceId)
        assertEquals("https://jf.example", saved.baseUrl)
        // What the user chose for it stays.
        assertEquals(StreamFormat.MP3.id, saved.streamFormatId)
        assertEquals("Kitchen", saved.playerName)
    }

    @Test
    fun `another account on the same server is another source`() {
        val other = jellyfin.copy(id = "src-2", userId = "someone-else", token = "t2")
        val (list, id) = mergeSource(listOf(jellyfin), other)
        assertEquals("src-2", id)
        assertEquals(2, list.size)
    }

    @Test
    fun `an edit replaces the record it has the id of`() {
        val (list, id) = mergeSource(listOf(jellyfin), jellyfin.copy(streamFormatId = StreamFormat.OPUS.id))
        assertEquals("src-old", id)
        assertEquals(StreamFormat.OPUS.id, list.single().streamFormatId)
    }

    @Test
    fun `a Plex server re-linked keeps its source`() {
        val plex = MusicSource(id = "p1", kind = SourceKind.PLEX, name = "Plex", token = "a", machineIdentifier = "m1")
        val (list, id) = mergeSource(listOf(plex), plex.copy(id = "p2", token = "b"))
        assertEquals("p1", id)
        assertEquals("b", list.single().token)
    }

    @Test
    fun `a Plex player name is folded to what a header can carry`() {
        assertEquals("Armin's Amp", "Armin’s Amp".headerSafe())
        assertEquals("Amp", "東京".headerSafe())
    }
}

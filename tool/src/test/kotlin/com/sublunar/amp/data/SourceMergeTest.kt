package com.sublunar.amp.data

import com.sublunar.amp.source.plex.PlexClient.Companion.headerSafe
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
    fun `two Subsonic logins are two sources, even to the same server as the same user`() {
        val one = MusicSource(
            id = "s1",
            kind = SourceKind.SUBSONIC,
            name = "Navidrome",
            baseUrl = "https://music.example.com",
            username = "armin",
        )
        val (list, id) = mergeSource(listOf(one), one.copy(id = "s2"))
        assertEquals("s2", id)
        assertEquals(listOf("s1", "s2"), list.map { it.id })
    }

    @Test
    fun `a blank server or user id is never the same server`() {
        val plex = MusicSource(id = "p1", kind = SourceKind.PLEX, name = "Plex", token = "a")
        assertEquals(2, mergeSource(listOf(plex), plex.copy(id = "p2")).first.size)
        val unsigned = jellyfin.copy(userId = "")
        assertEquals(2, mergeSource(listOf(unsigned), unsigned.copy(id = "src-new")).first.size)
    }

    @Test
    fun `a re-link keeps the user's choices and takes the rest from the new sign-in`() {
        val old = MusicSource(
            id = "p-old",
            kind = SourceKind.PLEX,
            name = "Old name",
            baseUrl = "http://10.0.0.2:32400",
            username = "old-user",
            password = "old-pass",
            streamFormatId = "raw",
            cellularFormatId = "mp3",
            downloadFormatId = "raw",
            offlineModeName = "ALL",
            downloadLimitBytes = 1L,
            playerName = "Kitchen",
            token = "old-token",
            userId = "old-uid",
            machineIdentifier = "m1",
            deviceId = "amp-old",
            parserGeneration = 2,
            libraryId = "7",
            libraries = listOf(SourceLibrary("7", "Music")),
            hiddenLibraryIds = listOf("9"),
            connections = listOf("http://10.0.0.2:32400"),
            features = ServerFeatures(missing = listOf(ServerFeatures.KEEP_COPIES)),
        )
        val signIn = MusicSource(
            id = "p-new",
            kind = SourceKind.PLEX,
            name = "New name",
            baseUrl = "https://plex.example:32400",
            username = "new-user",
            password = "new-pass",
            token = "new-token",
            userId = "new-uid",
            machineIdentifier = "m1",
            deviceId = "amp-new",
            libraries = listOf(SourceLibrary("7", "Music"), SourceLibrary("9", "Audiobooks")),
            connections = listOf("https://plex.example:32400", "http://10.0.0.2:32400"),
        )
        val (list, id) = mergeSource(listOf(old), signIn)
        assertEquals("p-old", id)
        assertEquals(
            MusicSource(
                id = "p-old",
                kind = SourceKind.PLEX,
                name = "New name",
                baseUrl = "https://plex.example:32400",
                username = "new-user",
                password = "new-pass",
                streamFormatId = "raw",
                cellularFormatId = "mp3",
                downloadFormatId = "raw",
                offlineModeName = "ALL",
                downloadLimitBytes = 1L,
                playerName = "Kitchen",
                token = "new-token",
                userId = "new-uid",
                machineIdentifier = "m1",
                deviceId = "amp-new",
                parserGeneration = 2,
                libraryId = "7",
                libraries = listOf(SourceLibrary("7", "Music"), SourceLibrary("9", "Audiobooks")),
                hiddenLibraryIds = listOf("9"),
                connections = listOf("https://plex.example:32400", "http://10.0.0.2:32400"),
                features = ServerFeatures(missing = listOf(ServerFeatures.KEEP_COPIES)),
            ),
            list.single(),
        )
    }

    @Test
    fun `an edit that leaves a choice unset keeps the one stored`() {
        val chosen = jellyfin.copy(
            downloadFormatId = "flac",
            offlineModeName = "FAVORITES",
            downloadLimitBytes = 5L,
            libraryId = "a1",
        )
        val (list, _) = mergeSource(listOf(chosen), jellyfin.copy(name = "Renamed"))
        assertEquals(chosen.copy(name = "Renamed"), list.single())
    }

    @Test
    fun `a Plex player name is folded to what a header can carry`() {
        assertEquals("Armin's Amp", "Armin’s Amp".headerSafe())
        assertEquals("Amp", "東京".headerSafe())
    }
}

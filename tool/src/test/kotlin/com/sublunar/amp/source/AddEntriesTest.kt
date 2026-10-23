package com.sublunar.amp.source

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The ways to add a source, as Add Source and the first-run screen both show
 * them — pinned to the rows and wording, least effort first.
 */
class AddEntriesTest {

    private val labels = listOf("Music on This Phone", "Bandcamp", "Plex Server", "Jellyfin Server", "Subsonic Server")

    @Test
    fun `with no sources, every way to add one, in order`() {
        val entries = Providers.addEntries(emptyList())
        assertEquals(labels, entries.map { it.label })
        assertEquals(listOf(AddIcon.PHONE) + List(4) { AddIcon.ADD }, entries.map { it.icon })
    }

    @Test
    fun `the phone's own music is offered only while there is none`() {
        val local = MusicSource("local", SourceKind.LOCAL, "Local Music")
        val server = MusicSource("s", SourceKind.SUBSONIC, "Navidrome", baseUrl = "https://music.example")
        assertEquals(labels.drop(1), Providers.addEntries(listOf(server, local)).map { it.label })
        assertEquals(labels, Providers.addEntries(listOf(server)).map { it.label })
    }

    @Test
    fun `the phone's own music is added at once, as the one local source`() {
        val phone = Providers.addEntries(emptyList()).first()
        val source = assertIs<AddAction.AddAtOnce>(phone.action).source()
        assertEquals(MusicSource("local", SourceKind.LOCAL, "Local Music"), source)
        Providers.addEntries(emptyList()).drop(1).forEach { assertIs<AddAction.OpenScreen>(it.action) }
    }

    @Test
    fun `what the kinds cover is one line, about Subsonic`() {
        assertEquals(listOf("Subsonic covers Navidrome, Airsonic and Ampache"), Providers.addNotes)
    }
}

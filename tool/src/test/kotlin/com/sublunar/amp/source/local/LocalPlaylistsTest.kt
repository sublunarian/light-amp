package com.sublunar.amp.source.local

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What a phone playlist's id may point the playlist edits at. */
class LocalPlaylistsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `an id naming a file outside the playlists folder is never changed`() = runBlocking {
        val elsewhere = tmp.newFile("Mix.m3u8").apply { writeText("#EXTM3U\n") }
        val id = "m3u:" + elsewhere.absolutePath
        assertFalse(LocalPlaylists.add(id, "file:/storage/emulated/0/Music/Amp/a.mp3"))
        assertFalse(LocalPlaylists.rename(id, "Other"))
        assertFalse(LocalPlaylists.delete(id))
        assertEquals("#EXTM3U\n", elsewhere.readText())
    }
}

package com.sublunar.amp.source.local

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

    @Test
    fun `playlists and likes come back from Amp's own storage, and a name already there stays`() {
        val music = tmp.newFolder("Amp").canonicalFile
        val back = java.io.File(music, "Playlists")
        val own = tmp.newFolder("own").canonicalFile
        val song = java.io.File(music, "Artist/01.flac").apply { parentFile.mkdirs(); writeText("x") }
        java.io.File(own, "Mix.m3u8").writeText("#EXTM3U\n${song.path}\n")
        java.io.File(own, "_AmpFavorites.m3u8").writeText("#EXTM3U\n#AMP-ARTIST:Artist\n${song.path}\n")
        java.io.File(own, "Kept.m3u8").writeText("#EXTM3U\nnew\n")
        back.mkdirs()
        java.io.File(back, "Kept.m3u8").writeText("#EXTM3U\nold\n")

        LocalPlaylists.bringBack(own, back)

        assertEquals("#EXTM3U\n${song.path}\n", java.io.File(back, "Mix.m3u8").readText())
        // Likes are relative to their folder again, as other players read them.
        assertTrue(java.io.File(back, "_AmpFavorites.m3u8").readText().contains("../Artist/01.flac"))
        assertEquals("#EXTM3U\nold\n", java.io.File(back, "Kept.m3u8").readText())
        assertEquals(listOf("Kept.m3u8"), own.listFiles().orEmpty().map { it.name })
    }
}

package com.sublunar.amp.source.local

import com.sublunar.amp.data.Album
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.Track
import com.sublunar.amp.source.PhoneLikes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the favorites file holds, how it reads elsewhere, and what happens when Amp can't change it. */
class FavoritesFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var music: File
    private lateinit var folder: File
    private val saved get() = File(folder, "_AmpFavorites.m3u8")

    @Before
    fun folders() {
        music = tmp.root.canonicalFile
        folder = File(music, "Playlists").apply { mkdirs() }
    }

    private fun track(path: String, artist: String = "Nina Simone", title: String = "Sinnerman") = Track(
        id = PhoneFiles.ID_PREFIX + File(music, path).path,
        title = title,
        artist = artist,
        album = "Pastel Blues",
        albumArtist = artist,
        albumId = null,
        coverArtId = null,
        durationMs = 618_000,
        trackNumber = null,
        discNumber = null,
        year = null,
        playCount = 0,
        lastPlayedMs = 0,
    )

    private fun album(artist: String, title: String) = Album(
        id = LocalLibrary.albumId(artist, title),
        title = title,
        artist = artist,
        coverArtId = null,
        durationMs = 0,
        songCount = 1,
        year = null,
    )

    private fun likes(file: FavoritesFile): PhoneLikes? = runBlocking { file.whileHeld { it } }

    @Test
    fun `the file is a playlist other players can open, with paths relative to it`() = runBlocking {
        val file = FavoritesFile(folder)
        assertTrue(file.setArtist("Nina Simone", true))
        assertTrue(file.setAlbum(album("Nina Simone", "Pastel Blues"), true))
        assertTrue(file.setSong(track("Nina Simone/Pastel Blues/09 Sinnerman.flac"), true))
        assertEquals(
            """
            #EXTM3U
            #PLAYLIST:Amp Favorites
            #AMP-ARTIST:Nina Simone
            #AMP-ALBUM:Nina Simone|Pastel Blues
            #EXTINF:618,Nina Simone - Sinnerman
            ../Nina Simone/Pastel Blues/09 Sinnerman.flac

            """.trimIndent(),
            saved.readText(),
        )
    }

    @Test
    fun `likes read back as the ids the library uses, and unliking takes only that one`() = runBlocking {
        val file = FavoritesFile(folder)
        val first = track("a/1.flac")
        val second = track("a/2.flac")
        file.setSong(first, true)
        file.setSong(second, true)
        file.setArtist("Nina Simone", true)
        file.setAlbum(album("Nina Simone", "Pastel Blues"), true)
        file.setSong(first, false)
        val read = likes(FavoritesFile(folder))!!
        assertEquals(setOf(second.id), read.songIds)
        assertEquals(setOf("Nina Simone"), read.artistNames)
        assertEquals(setOf(LocalLibrary.albumId("Nina Simone", "Pastel Blues")), read.albumIds)
    }

    @Test
    fun `names holding the file's own punctuation come back whole`() = runBlocking {
        val artist = "A|B\\C\nD"
        val title = "Live | Loud\\"
        val file = FavoritesFile(folder)
        file.setArtist(artist, true)
        file.setAlbum(album(artist, title), true)
        val read = likes(FavoritesFile(folder))!!
        assertEquals(setOf(artist), read.artistNames)
        assertEquals(setOf(LocalLibrary.albumId(artist, title)), read.albumIds)
    }

    @Test
    fun `a song whose file has gone stays listed through other likes`() = runBlocking {
        saved.writeText("#EXTM3U\n#EXTINF:10,Gone - Song\n../gone/song.flac\n")
        FavoritesFile(folder).setArtist("Nina Simone", true)
        assertTrue("#EXTINF:10,Gone - Song\n../gone/song.flac" in saved.readText())
    }

    @Test
    fun `a file from a computer reads with its mark, line endings and absolute paths`() {
        val path = File(music, "a/1.flac").path
        saved.writeText("﻿#EXTM3U\r\n$path\r\n")
        assertEquals(setOf(PhoneFiles.ID_PREFIX + path), likes(FavoritesFile(folder))!!.songIds)
    }

    @Test
    fun `a file that can't be read is never written over`() = runBlocking {
        saved.writeText("#EXTM3U\n../a/1.flac\n")
        saved.setReadable(false)
        val file = FavoritesFile(folder)
        assertNull(likes(file))
        assertFalse(file.setArtist("Nina Simone", true))
        saved.setReadable(true)
        assertEquals("#EXTM3U\n../a/1.flac\n", saved.readText())
    }

    @Test
    fun `a file Amp can't change is kept, and a numbered copy carries on from it`() = runBlocking {
        saved.writeText("#EXTM3U\n../a/1.flac\n")
        saved.setLastModified(1_000)
        saved.setReadOnly()
        val file = FavoritesFile(folder, moveInto = { from, to -> to != saved && from.renameTo(to) })

        assertTrue(file.setArtist("Nina Simone", true))

        assertEquals("#EXTM3U\n../a/1.flac\n", saved.readText())
        val read = likes(file)!!
        assertEquals(setOf("Nina Simone"), read.artistNames)
        assertEquals(1, read.songIds.size)
        assertTrue(File(folder, "_AmpFavorites 2.m3u8").isFile)
    }

    @Test
    fun `the newest copy is the one that counts`() {
        File(folder, "_AmpFavorites 2.m3u8").apply {
            writeText("#EXTM3U\n#AMP-ARTIST:Older\n")
            setLastModified(1_000)
        }
        saved.writeText("#EXTM3U\n#AMP-ARTIST:Newer\n")
        saved.setLastModified(2_000)
        assertEquals(setOf("Newer"), likes(FavoritesFile(folder))!!.artistNames)
    }

    @Test
    fun `a save leaves nothing behind but the file`() = runBlocking {
        FavoritesFile(folder).setArtist("Nina Simone", true)
        assertEquals(listOf("_AmpFavorites.m3u8"), folder.list()!!.toList())
    }

    @Test
    fun `the favorites file is no playlist, and no playlist can take its name`() {
        assertTrue(FavoritesFile.isFavoritesFile("_AmpFavorites.m3u8"))
        assertTrue(FavoritesFile.isFavoritesFile("_ampfavorites 2.m3u8"))
        assertTrue(FavoritesFile.isFavoritesFile("_AmpFavorites.saving.m3u8"))
        assertFalse(FavoritesFile.isFavoritesFile("AmpFavorites.m3u8"))
        assertEquals("AmpFavorites.m3u8", LocalPlaylists.fileNameFor("_AmpFavorites"))
        assertEquals("_Mix.m3u8", LocalPlaylists.fileNameFor("_Mix"))
    }
}

package com.sublunar.amp.source.local

import com.sublunar.amp.data.ToolManagerFolders
import com.sublunar.amp.data.PhoneFiles
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Tool Manager's folders: what counts as a change, and how playlists from `Music/Amp` move in. */
class ToolManagerFoldersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var shared: File
    private lateinit var music: File
    private lateinit var playlists: File
    private lateinit var legacyLibrary: File
    private lateinit var legacy: File

    @Before
    fun folders() {
        val filesDir = tmp.newFolder("files")
        ToolManagerFolders.attach(filesDir)
        shared = File(filesDir, "shared").canonicalFile
        music = File(shared, "Music")
        playlists = File(shared, "Playlists")
        legacyLibrary = tmp.newFolder("Amp").canonicalFile
        legacy = File(legacyLibrary, "Playlists").apply { mkdirs() }
    }

    @Test
    fun `attach makes both folders where the Tool Manager serves them`() {
        assertEquals(music, ToolManagerFolders.musicDir)
        assertEquals(playlists, ToolManagerFolders.playlistsDir)
        assertTrue(music.isDirectory && playlists.isDirectory)
        assertFalse(ToolManagerFolders.playlistsMovedMarker!!.startsWith(shared))
    }

    @Test
    fun `an album added in a folder is a change, even with the computer's old dates`() {
        val old = 1_000_000L
        listOf(music, playlists).forEach { it.setLastModified(old) }
        assertFalse(ToolManagerFolders.changedSince(old))
        val album = File(music, "Album").apply { mkdirs() }
        File(album, "01.flac").apply { writeText("x"); setLastModified(old) }
        album.setLastModified(old)
        // Only the Music folder's own date moved, when the album folder arrived in it.
        music.setLastModified(old + 10_000)
        assertTrue(ToolManagerFolders.changedSince(old))
    }

    @Test
    fun `a song deep in the folders that changed is a change`() {
        val disc = File(music, "Artist/Album/CD1").apply { mkdirs() }
        val song = File(disc, "01.flac").apply { writeText("x") }
        val stamp = 2_000_000L
        generateSequence(disc) { it.parentFile }.takeWhile { it != shared.parentFile }.forEach { it.setLastModified(stamp) }
        playlists.setLastModified(stamp)
        song.setLastModified(stamp)
        assertFalse(ToolManagerFolders.changedSince(stamp))
        song.setLastModified(stamp + 10_000)
        assertTrue(ToolManagerFolders.changedSince(stamp))
    }

    @Test
    fun `playlists move with their songs where they were, and the old files go`() {
        val song = File(legacyLibrary, "Artist/01.flac").apply { parentFile.mkdirs(); writeText("x") }
        File(legacy, "Mix.m3u8").writeText("#EXTM3U\n../Artist/01.flac\n")
        assertTrue(LocalPlaylists.moveLegacy(legacy, playlists))
        assertEquals("#EXTM3U\n${song.path}\n", File(playlists, "Mix.m3u8").readText())
        assertFalse(File(legacy, "Mix.m3u8").exists())
    }

    @Test
    fun `likes move and still name the same songs`() {
        val song = File(legacyLibrary, "Artist/01.flac").apply { parentFile.mkdirs(); writeText("x") }
        val id = PhoneFiles.idFor(song)
        File(legacy, "_AmpFavorites.m3u8").writeText("#EXTM3U\n#AMP-ARTIST:Artist\n../Artist/01.flac\n")
        assertTrue(LocalPlaylists.moveLegacy(legacy, playlists))
        val moved = parseFavorites(File(playlists, "_AmpFavorites.m3u8").readText(), playlists)
        assertEquals(listOf(id), moved.songs.map { it.trackId })
        assertEquals(listOf("Artist"), moved.artists)
        // Outside the Tool Manager's library, so the path is written in full.
        assertTrue(File(playlists, "_AmpFavorites.m3u8").readText().contains(song.path))
    }

    @Test
    fun `a playlist already in the new folder is never written over`() {
        File(legacy, "Mix.m3u8").writeText("#EXTM3U\n/old.flac\n")
        File(playlists, "Mix.m3u8").writeText("#EXTM3U\n/new.flac\n")
        assertTrue(LocalPlaylists.moveLegacy(legacy, playlists))
        assertEquals("#EXTM3U\n/new.flac\n", File(playlists, "Mix.m3u8").readText())
    }

    @Test
    fun `likes of uploaded songs are relative, so the shared folder can be copied whole`() {
        val song = File(music, "Artist/01.flac").apply { parentFile.mkdirs(); writeText("x") }
        val content = parseFavorites("#EXTM3U\n${song.path}\n", playlists)
        assertEquals("../Music/Artist/01.flac", renderFavorites(content, playlists).lines().last { it.isNotBlank() })
    }

    @Test
    fun `an uploaded playlist may name songs from the Music folder`() {
        val song = File(music, "Artist/01.flac").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(PhoneFiles.idFor(song), LocalPlaylists.trackIdOfEntry("Artist\\01.flac", playlists))
        assertEquals(PhoneFiles.idFor(song), LocalPlaylists.trackIdOfEntry("../Music/Artist/01.flac", playlists))
    }

    private fun form(vararg answers: Pair<String, String>): String =
        kotlinx.serialization.json.Json.encodeToString(
            com.thelightphone.toolmanager.FormSubmission.serializer(),
            com.thelightphone.toolmanager.FormSubmission(
                answers.map { (id, text) ->
                    com.thelightphone.toolmanager.FormResponse(
                        com.thelightphone.toolmanager.FormItem(id = id, label = id, hint = "", hidden = id == "password", required = true),
                        text,
                    )
                },
            ),
        )

    @Test
    fun `a sent login is read, and every form file is deleted`() {
        val dir = File(shared, "BandcampLogin").apply { mkdirs() }
        File(dir, "form_1.json").apply { writeText(form("username" to "old", "password" to "x")); setLastModified(1_000_000) }
        File(dir, "form_2.json").apply { writeText(form("username" to " fan ", "password" to "s3cret")); setLastModified(2_000_000) }
        ToolManagerFolders.sentLogin.value = null
        ToolManagerFolders.takeSentLogin()
        val login = ToolManagerFolders.sentLogin.value!!
        assertEquals("fan", login.username)
        assertEquals("s3cret", login.password)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertFalse(login.toString().contains("s3cret"))
    }

    @Test
    fun `a form without both answers is no login`() {
        assertEquals(null, ToolManagerFolders.readLogin(form("username" to "fan", "password" to "")))
        assertEquals(null, ToolManagerFolders.readLogin(form("password" to "s3cret")))
    }

    @Test
    fun `the Tool Manager lists the form, kept out of backups`() {
        val form = ToolManagerFolders.manifest().roots
            .filterIsInstance<com.thelightphone.toolmanager.ClientLeafNode>()
            .single { it.spec is com.thelightphone.toolmanager.FormSpec }
        assertFalse(form.canBeBackedUp)
    }
}

package com.sublunar.amp.ui.screens

import com.sublunar.amp.data.Album
import com.sublunar.amp.data.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An artist's All Songs page, narrowed and ordered for one visit. */
class ArtistSongsViewTest {

    private fun track(id: String, title: String, album: String, played: Long, rating: Int, liked: Boolean = false) = Track(
        id = id, title = title, artist = "A", album = album, albumArtist = "A", albumId = album,
        coverArtId = null, durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = 0, lastPlayedMs = played, rating = rating, liked = liked,
    )

    // In the order they come: by album.
    private val songs = listOf(
        track("1", "Beta", "old", played = 2, rating = 1, liked = true),
        track("2", "Alpha", "old", played = 9, rating = 3),
        track("3", "Gamma", "new", played = 5, rating = 5, liked = true),
    )
    private val albums = listOf(
        Album("old", "Old", "A", null, 0, 2, null, createdMs = 1),
        Album("new", "New", "A", null, 0, 1, null, createdMs = 2),
    )

    private fun ids(view: ArtistSongsView, downloaded: Set<String> = emptySet()) =
        view.applyTo(songs, downloaded, albums).map { it.id }

    @Test
    fun `plain, the songs come by album`() {
        assertEquals(listOf("1", "2", "3"), ids(ArtistSongsView()))
        assertNull(ArtistSongsView().filterLine())
    }

    @Test
    fun `liked and downloaded narrow together`() {
        assertEquals(listOf("1", "3"), ids(ArtistSongsView(liked = true)))
        assertEquals(listOf("3"), ids(ArtistSongsView(liked = true, downloaded = true), downloaded = setOf("2", "3")))
        assertEquals("Liked · Downloaded", ArtistSongsView(liked = true, downloaded = true).filterLine())
    }

    @Test
    fun `each order`() {
        assertEquals(listOf("2", "1", "3"), ids(ArtistSongsView(sort = ArtistSongSort.TITLE)))
        assertEquals(listOf("2", "3", "1"), ids(ArtistSongsView(sort = ArtistSongSort.PLAYED)))
        assertEquals(listOf("3", "2", "1"), ids(ArtistSongsView(sort = ArtistSongSort.RATING)))
        assertEquals(listOf("3", "1", "2"), ids(ArtistSongsView(sort = ArtistSongSort.ADDED)))
    }

    @Test
    fun `the A-Z strip follows the alphabetical orders and only those`() {
        assertEquals(listOf('O', 'O', 'N'), ArtistSongsView().lettersOf(songs))
        assertEquals(listOf('B', 'A', 'G'), ArtistSongsView(sort = ArtistSongSort.TITLE).lettersOf(songs))
        for (sort in listOf(ArtistSongSort.ADDED, ArtistSongSort.PLAYED, ArtistSongSort.RATING)) {
            assertEquals(emptyList(), ArtistSongsView(sort = sort).lettersOf(songs))
        }
    }
}

package com.sublunar.amp.ui.screens

import com.sublunar.amp.data.Album
import com.sublunar.amp.data.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An artist's All Songs page, narrowed and ordered for one visit. */
class ArtistSongsViewTest {

    private fun track(id: String, title: String, album: String, plays: Int = 0, liked: Boolean = false) = Track(
        id = id, title = title, artist = "A", album = album, albumArtist = "A", albumId = album,
        coverArtId = null, durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = plays, lastPlayedMs = 0, liked = liked,
    )

    // In the order they come: by album.
    private val songs = listOf(
        track("1", "Beta", "old", plays = 2, liked = true),
        track("2", "Alpha", "old", plays = 9),
        track("3", "Gamma", "new", plays = 5, liked = true),
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
        assertEquals(listOf("2", "3", "1"), ids(ArtistSongsView(sort = ArtistSongSort.PLAYS)))
        assertEquals(listOf("3", "1", "2"), ids(ArtistSongsView(sort = ArtistSongSort.ADDED)))
    }
}

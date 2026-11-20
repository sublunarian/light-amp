package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the Liked and Downloaded pages and switches are told the library holds. */
class HoldingsTest {

    private fun track(id: String, album: String, artist: String, liked: Boolean = false, genre: String = "") = Track(
        id = id, title = id, artist = artist, album = album, albumArtist = artist, albumId = album,
        coverArtId = null, durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = 0, lastPlayedMs = 0, liked = liked, genre = genre,
    )

    private val songs = listOf(
        track("1", "a1", "Ana", liked = true, genre = "Jazz"),
        track("2", "a1", "Ana", genre = "Folk"),
        track("3", "a2", "Bo"),
    )
    private val albums = listOf(
        Album("a1", "One", "Ana", null, 0, 2, null),
        Album("a2", "Two", "Bo", null, 0, 1, null, liked = true),
    )
    private val artists = listOf(Artist("Ana", albumCount = 1, trackCount = 2), Artist("Bo", albumCount = 1, trackCount = 1, liked = true))

    @Test
    fun `nothing downloaded offers liked lists and no downloads`() {
        val held = Holdings.of(songs, albums, artists, emptySet())
        assertEquals(Holdings(genres = listOf("Folk", "Jazz"), likedArtists = true, likedAlbums = true, likedSongs = true), held)
    }

    @Test
    fun `liked and downloaded both go by the downloaded songs`() {
        // Only song 2 is on the phone: Ana's album and Ana count, Bo's don't.
        val held = Holdings.of(songs, albums, artists, setOf("2"))
        assertEquals(true, held.downloads)
        assertEquals(false, held.likedDownloadedSongs)
        assertEquals(false, held.likedDownloadedAlbums)
        assertEquals(false, held.likedDownloadedArtists)
        assertEquals(listOf("Folk"), held.downloadedGenres)
    }

    @Test
    fun `only losing a row counts as hiding one`() {
        val full = Holdings(genres = listOf("Jazz"), likedSongs = true, downloads = true)
        assertEquals(true, Holdings(genres = listOf("Jazz"), likedSongs = true).hidesAnyOf(full))
        assertEquals(true, Holdings(likedSongs = true, downloads = true).hidesAnyOf(full))
        assertEquals(false, full.copy(genres = listOf("Folk"), likedArtists = true).hidesAnyOf(full))
        assertEquals(false, full.hidesAnyOf(Holdings()))
    }

    @Test
    fun `a liked song downloaded is offered under Downloaded`() {
        val held = Holdings.of(songs, albums, artists, setOf("1", "3"))
        assertEquals(true, held.likedDownloadedSongs)
        assertEquals(true, held.likedDownloadedAlbums)
        assertEquals(true, held.likedDownloadedArtists)
        assertEquals(listOf("Jazz"), held.downloadedGenres)
    }
}

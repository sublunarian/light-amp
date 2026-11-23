package com.sublunar.amp.data

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sublunar.amp.data.HomeCategory.ALBUMS
import com.sublunar.amp.data.HomeCategory.ARTISTS
import com.sublunar.amp.data.HomeCategory.DOWNLOADED
import com.sublunar.amp.data.HomeCategory.FAVORITES
import com.sublunar.amp.data.HomeCategory.GENRES
import com.sublunar.amp.data.HomeCategory.PLAYLISTS
import com.sublunar.amp.data.HomeCategory.SONGS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** The library home's categories as stored: which are on, and their order. */
class HomeLayoutTest {

    @Test
    fun `by default the four tabs, Favorites and Downloaded, with Genres switched off`() {
        val layout = HomeLayout.DEFAULT
        assertEquals(listOf(PLAYLISTS, ARTISTS, ALBUMS, SONGS, FAVORITES, DOWNLOADED), layout.listed)
        assertEquals(HomeCategory.entries, layout.order)
    }

    @Test
    fun `nothing stored reads as the default`() {
        assertEquals(HomeLayout.DEFAULT, HomeLayout.decode(""))
        assertEquals(HomeLayout.DEFAULT, MemoryDataStore().settings().homeLayout.value)
    }

    @Test
    fun `what is written reads back the same`() {
        val layout = HomeLayout.DEFAULT
            .toggled(PLAYLISTS)
            .toggled(GENRES)
            .moved(GENRES, 0)
            .moved(SONGS, 1)
        assertEquals(listOf(GENRES, SONGS, ARTISTS, ALBUMS, FAVORITES, DOWNLOADED), layout.listed)
        assertEquals(layout, HomeLayout.decode(layout.encode()))
    }

    @Test
    fun `a switched-off category leads those off, and one switched on ends those on`() {
        val off = HomeLayout.DEFAULT.toggled(ARTISTS)
        assertEquals(listOf(PLAYLISTS, ALBUMS, SONGS, FAVORITES, DOWNLOADED, ARTISTS, GENRES), off.order)
        val on = off.toggled(GENRES)
        assertEquals(listOf(PLAYLISTS, ALBUMS, SONGS, FAVORITES, DOWNLOADED, GENRES, ARTISTS), on.order)
    }

    @Test
    fun `a stored layout with one off among those on reads with it at the bottom`() {
        assertEquals(
            listOf(PLAYLISTS, ALBUMS, ARTISTS, SONGS, FAVORITES, DOWNLOADED, GENRES),
            HomeLayout.decode("PLAYLISTS,-GENRES,ALBUMS").order,
        )
    }

    @Test
    fun `a move stays on its side of those on and those off`() {
        assertEquals(
            listOf(PLAYLISTS, ARTISTS, ALBUMS, SONGS, FAVORITES, DOWNLOADED, GENRES),
            HomeLayout.DEFAULT.moved(GENRES, -3).order,
        )
        assertEquals(
            listOf(ARTISTS, ALBUMS, SONGS, FAVORITES, DOWNLOADED, PLAYLISTS, GENRES),
            HomeLayout.DEFAULT.moved(PLAYLISTS, 99).order,
        )
    }

    @Test
    fun `an unknown name is skipped, a repeat counts once, and a missing category joins at the end`() {
        val layout = HomeLayout.decode("SONGS,-PLAYLISTS,COMPOSERS,SONGS, GENRES ,-ALBUMS")
        assertEquals(
            listOf(SONGS, GENRES, ARTISTS, FAVORITES, DOWNLOADED, PLAYLISTS, ALBUMS),
            layout.order,
        )
        // A missing one comes in as its default has it: Artists, Favorites and Downloaded on.
        assertEquals(listOf(SONGS, GENRES, ARTISTS, FAVORITES, DOWNLOADED), layout.listed)
    }

    @Test
    fun `everything switched off stays off`() {
        val none = HomeCategory.entries.fold(HomeLayout.DEFAULT) { layout, c ->
            if (c in layout.shown) layout.toggled(c) else layout
        }
        assertEquals(emptyList(), HomeLayout.decode(none.encode()).listed)
    }

    @Test
    fun `the setting starts from what is stored and follows each write`() = runBlocking {
        val settings = MemoryDataStore(
            preferencesOf(stringPreferencesKey("pref.homeCategories") to "ALBUMS,-PLAYLISTS"),
        ).settings()
        assertEquals(listOf(ALBUMS, ARTISTS, SONGS, FAVORITES, DOWNLOADED), settings.homeLayout.value.listed)
        settings.homeLayout.set(settings.homeLayout.value.toggled(PLAYLISTS))
        assertEquals(listOf(ALBUMS, ARTISTS, SONGS, FAVORITES, DOWNLOADED, PLAYLISTS), settings.homeLayout.value.listed)
    }

    @Test
    fun `a Genres list has each value once, whatever its case, in name order`() {
        val tracks = listOf(
            track("1", genre = "Rock; Jazz"),
            track("2", genre = "rock"),
            track("3", genre = "The Blues"),
            track("4", genre = ""),
        )
        assertEquals(listOf("Jazz", "Rock", "The Blues"), tagValuesIn(tracks) { splitTagValues(it.genre) })
    }

    private fun track(id: String, genre: String = "") = Track(
        id = id, title = id, artist = "", album = "", albumArtist = "", albumId = null, coverArtId = null,
        durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = 0, lastPlayedMs = 0, genre = genre,
    )
}

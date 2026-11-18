package com.sublunar.amp.data

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sublunar.amp.data.HomeCategory.ALBUMS
import com.sublunar.amp.data.HomeCategory.ARTISTS
import com.sublunar.amp.data.HomeCategory.COMPILATIONS
import com.sublunar.amp.data.HomeCategory.COMPOSERS
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
    fun `by default the four tabs, then the rest switched off`() {
        val layout = HomeLayout.DEFAULT
        assertEquals(listOf(PLAYLISTS, ARTISTS, ALBUMS, SONGS), layout.listed)
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
            .toggled(FAVORITES)
            .moved(FAVORITES, 0)
            .moved(SONGS, 1)
        assertEquals(listOf(FAVORITES, SONGS, ARTISTS, ALBUMS), layout.listed)
        assertEquals(layout, HomeLayout.decode(layout.encode()))
    }

    @Test
    fun `a switched-off category keeps its place`() {
        val off = HomeLayout.DEFAULT.moved(GENRES, 1).toggled(ARTISTS)
        assertEquals(listOf(PLAYLISTS, ALBUMS, SONGS), off.listed)
        val on = off.toggled(ARTISTS).toggled(GENRES)
        assertEquals(listOf(PLAYLISTS, GENRES, ARTISTS, ALBUMS, SONGS), on.listed)
    }

    @Test
    fun `a move past either end lands at that end`() {
        assertEquals(DOWNLOADED, HomeLayout.DEFAULT.moved(DOWNLOADED, -3).order.first())
        assertEquals(PLAYLISTS, HomeLayout.DEFAULT.moved(PLAYLISTS, 99).order.last())
    }

    @Test
    fun `an unknown name is skipped, a repeat counts once, and a missing category joins at the end`() {
        val layout = HomeLayout.decode("SONGS,-PLAYLISTS,PODCASTS,SONGS, GENRES ,-ALBUMS")
        assertEquals(
            listOf(SONGS, PLAYLISTS, GENRES, ALBUMS, ARTISTS, COMPILATIONS, COMPOSERS, FAVORITES, DOWNLOADED),
            layout.order,
        )
        // A missing one comes in as its default has it: Artists on, the rest off.
        assertEquals(listOf(SONGS, GENRES, ARTISTS), layout.listed)
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
        assertEquals(listOf(ALBUMS, ARTISTS, SONGS), settings.homeLayout.value.listed)
        settings.homeLayout.set(settings.homeLayout.value.toggled(PLAYLISTS))
        assertEquals(listOf(ALBUMS, PLAYLISTS, ARTISTS, SONGS), settings.homeLayout.value.listed)
    }

    @Test
    fun `composers split on tag separators and on Navidrome's bullet`() {
        assertEquals(listOf("Bach", "Handel", "Vivaldi", "Purcell"), splitComposers("Bach; Handel • Vivaldi, Purcell"))
    }

    @Test
    fun `a Genres or Composers list has each value once, whatever its case, in name order`() {
        val tracks = listOf(
            track("1", genre = "Rock; Jazz"),
            track("2", genre = "rock"),
            track("3", genre = "The Blues"),
            track("4", genre = ""),
        )
        assertEquals(listOf("Jazz", "Rock", "The Blues"), tagValuesIn(tracks) { splitTagValues(it.genre) })
    }

    @Test
    fun `a composer filter admits every song a composer shares`() {
        val shared = track("1", composer = "Lennon, McCartney")
        assertEquals(true, TagFilter(composer = "mccartney").admits(shared))
        assertEquals(false, TagFilter(composer = "Harrison").admits(shared))
        assertEquals(true, TagFilter().admits(shared))
    }

    private fun track(id: String, genre: String = "", composer: String = "") = Track(
        id = id, title = id, artist = "", album = "", albumArtist = "", albumId = null, coverArtId = null,
        durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = 0, lastPlayedMs = 0, genre = genre, composer = composer,
    )
}

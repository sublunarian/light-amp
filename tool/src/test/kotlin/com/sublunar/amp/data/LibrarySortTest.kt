package com.sublunar.amp.data

import com.sublunar.amp.ui.components.indexLetterOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sort key decides both list order and which A–Z bucket a row falls into;
 * these pin the folding against names that need it.
 */
class LibrarySortTest {

    @Test
    fun `accented names fold onto their base letter`() {
        assertEquals("olafur arnalds", titleKey("Ólafur Arnalds"))
        assertEquals("ethiopiques", titleKey("Éthiopiques"))
        assertEquals("bjork", titleKey("Björk"))
        assertEquals("sigur ros", titleKey("Sigur Rós"))
        assertEquals("motley crue", titleKey("Mötley Crüe"))
    }

    @Test
    fun `letters without a unicode decomposition still fold`() {
        // NFD leaves these alone, so they need the explicit table.
        assertEquals("oystein", titleKey("Øystein"))
        assertEquals("aether", titleKey("Æther"))
        assertEquals("strasse", titleKey("Straße"))
        assertEquals("lodz", titleKey("Łódz"))
        assertEquals("thorn", titleKey("Þorn"))
    }

    @Test
    fun `accented names index under the right letter, not hash`() {
        assertEquals('O', indexLetterOf(titleKey("Ólafur Arnalds")))
        assertEquals('E', indexLetterOf(titleKey("Éthiopiques")))
        assertEquals('B', indexLetterOf(titleKey("Björk")))
        assertEquals('O', indexLetterOf(titleKey("Øystein Sevåg")))
    }

    @Test
    fun `digits and non-latin scripts still bucket under hash`() {
        assertEquals('#', indexLetterOf(titleKey("10,000 Days")))
        assertEquals('#', indexLetterOf(titleKey("$0$")))
        // No sensible A-Z home; "#" is the right answer, not a fallback.
        assertEquals('#', indexLetterOf(titleKey("少年ナイフ")))
        assertEquals('#', indexLetterOf(titleKey("Мумий Тролль")))
    }

    @Test
    fun `article stripping still applies on top of folding`() {
        assertEquals("beatles", sortName("The Beatles"))
        assertEquals('B', indexLetterOf(sortName("The Beatles")))
        // Folding and stripping compose: article dropped, accent folded.
        assertEquals("eglise", sortName("La Église"))
        // A name that is only an article must not reduce to nothing.
        assertEquals("the", sortName("The"))
    }

    @Test
    fun `folded keys order accented names next to their unaccented peers`() {
        val artists = listOf("Wirtz", "Ólafur Arnalds", "Zappa", "Éthiopiques", "Air")
            .map { Artist(name = it, albumCount = 1, trackCount = 1) }
        assertEquals(
            listOf("Air", "Éthiopiques", "Ólafur Arnalds", "Wirtz", "Zappa"),
            sortArtists(artists, ArtistSort.NAME).map { it.name },
        )
    }

    private fun song(id: String, album: String, albumId: String?, disc: Int?, track: Int?) = Track(
        id = id, title = id, artist = "", album = album, albumArtist = "", albumId = albumId,
        coverArtId = null, durationMs = 0, trackNumber = track, discNumber = disc, year = null,
        playCount = 0, lastPlayedMs = 0,
    )

    @Test
    fun `recently added songs follow their album's date, newest first, each album in running order`() {
        val songs = listOf(
            song("old-1", "Old", "old", 1, 1),
            song("new-2", "New", "new", 1, 2),
            song("no-album", "", null, null, null),
            song("new-d2", "New", "new", 2, 1),
            song("new-1", "New", "new", 1, 1),
            song("unlisted", "Loose", "gone", 1, 1),
        )
        val added = mapOf("old" to 100L, "new" to 200L)
        // An album missing from the list counts as oldest; among those, by name as ever.
        assertEquals(
            listOf("new-1", "new-2", "new-d2", "old-1", "unlisted", "no-album"),
            sortSongs(songs, SongSort.DATE_ADDED, albumAdded = added).map { it.id },
        )
        assertEquals(
            listOf("no-album", "unlisted", "old-1", "new-d2", "new-2", "new-1"),
            sortSongs(songs, SongSort.DATE_ADDED, reversed = true, albumAdded = added).map { it.id },
        )
    }

    @Test
    fun `albums added at once order by name, and same-named ones stay whole`() {
        val songs = listOf(
            song("b-1", "Bravo", "b", 1, 1),
            song("a2-1", "Alpha", "a2", 1, 1),
            song("a1-2", "Alpha", "a1", 1, 2),
            song("a2-2", "Alpha", "a2", 1, 2),
            song("a1-1", "Alpha", "a1", 1, 1),
        )
        val added = mapOf("a1" to 5L, "a2" to 5L, "b" to 5L)
        assertEquals(
            listOf("a1-1", "a1-2", "a2-1", "a2-2", "b-1"),
            sortSongs(songs, SongSort.DATE_ADDED, albumAdded = added).map { it.id },
        )
    }
}

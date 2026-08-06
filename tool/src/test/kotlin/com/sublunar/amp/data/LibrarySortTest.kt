package com.sublunar.amp.data

import com.sublunar.amp.ui.components.indexLetterOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sort key decides both list order and which A–Z bucket a row falls into, so
 * accented names landing in "#" was a single bug in two places. These pin the
 * folding down against the names that actually exposed it.
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
}

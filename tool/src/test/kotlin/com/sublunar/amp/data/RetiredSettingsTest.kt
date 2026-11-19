package com.sublunar.amp.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings left on a choice that has since been taken away or reshaped. */
class RetiredSettingsTest {

    private fun launchedWith(list: String, stored: String) = MemoryDataStore(
        mutablePreferencesOf(
            stringPreferencesKey("pref.${list}Sort") to stored,
            booleanPreferencesKey("pref.${list}SortReversed") to true,
        ),
    ).settings()

    @Test
    fun `albums go back to their default, the right way up`() {
        for (removed in listOf("MOST_PLAYED", "RANDOM")) {
            val settings = launchedWith("album", removed)
            assertEquals(AlbumSort.TITLE, settings.albumSort.value)
            assertEquals(false, settings.albumSortReversed.value)
        }
    }

    @Test
    fun `songs and artists go back to theirs`() {
        val songs = launchedWith("song", "MOST_PLAYED")
        assertEquals(SongSort.TITLE, songs.songSort.value)
        assertEquals(false, songs.songSortReversed.value)
        val artists = launchedWith("artist", "MOST_PLAYED")
        assertEquals(ArtistSort.NAME, artists.artistSort.value)
        assertEquals(false, artists.artistSortReversed.value)
    }

    @Test
    fun `an order still offered stays as it was chosen`() {
        val settings = launchedWith("album", "YEAR")
        assertEquals(AlbumSort.YEAR, settings.albumSort.value)
        assertEquals(true, settings.albumSortReversed.value)
    }

    @Test
    fun `playlists start on title, and a chosen order stays`() {
        assertEquals(PlaylistSort.NAME, MemoryDataStore().settings().playlistSort.value)
        assertEquals(PlaylistSort.RECENTLY_UPDATED, launchedWith("playlist", "RECENTLY_UPDATED").playlistSort.value)
    }

    @Test
    fun `hidden download icons carry over as no indicator icon`() {
        val hidden = MemoryDataStore(mutablePreferencesOf(booleanPreferencesKey("pref.hideDownloadIcons") to true)).settings()
        assertEquals(RowMark.NONE, hidden.rowMark.value)
        val shown = MemoryDataStore(mutablePreferencesOf(booleanPreferencesKey("pref.hideDownloadIcons") to false)).settings()
        assertEquals(RowMark.DOWNLOADED, shown.rowMark.value)
    }

    @Test
    fun `the old switch is let go once it has been read`() {
        val store = MemoryDataStore(mutablePreferencesOf(booleanPreferencesKey("pref.hideDownloadIcons") to true))
        store.settings()
        assertEquals(mapOf<String, Any>("pref.rowMark" to "NONE"), store.all())
    }
}

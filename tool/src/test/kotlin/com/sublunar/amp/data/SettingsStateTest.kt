package com.sublunar.amp.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * What a screen's first frame reads: each setting's held state, which starts
 * from what was stored rather than from a placeholder, and follows every write.
 */
class SettingsStateTest {

    @Test
    fun `a setting's state starts from what is stored, not from its default`() {
        val settings = MemoryDataStore(
            preferencesOf(
                booleanPreferencesKey("pref.invertColors") to true,
                stringPreferencesKey("pref.artworkMode") to "NONE",
                stringPreferencesKey("pref.dataMode") to "WIFI_ONLY",
            ),
        ).settings()
        assertEquals(true, settings.invertColors.value)
        assertEquals(ArtworkMode.NONE, settings.artwork.value)
        assertEquals(DataMode.WIFI_ONLY, settings.dataMode.value)
        // Never written: the default.
        assertEquals(LayoutMode.STANDARD, settings.layoutMode.value)
    }

    @Test
    fun `and follows what is written`() = runBlocking {
        val settings = MemoryDataStore().settings()
        settings.albumSort.set(AlbumSort.YEAR)
        assertEquals(AlbumSort.YEAR, settings.albumSort.value)
    }

    @Test
    fun `a stored name this build doesn't know reads as the default`() {
        val settings = MemoryDataStore(preferencesOf(stringPreferencesKey("pref.albumSort") to "BOGUS")).settings()
        assertEquals(AlbumSort.TITLE, settings.albumSort.value)
    }

    @Test
    fun `the sources start from what is stored`() {
        val settings = MemoryDataStore(
            preferencesOf(
                stringPreferencesKey("pref.sources") to
                    """[{"id":"a","kind":"LOCAL","name":"A"},{"id":"b","kind":"LOCAL","name":"B","libraryId":"7"}]""",
                stringPreferencesKey("pref.activeSource") to "b",
            ),
        ).settings()
        assertEquals(listOf("a", "b"), settings.sources.value.map { it.id })
        assertEquals("b", settings.activeSource.value?.id)
        assertEquals("7", settings.libraryId.value)
    }

    @Test
    fun `the saved queue reads back in one`() = runBlocking {
        val settings = MemoryDataStore().settings()
        assertNull(settings.savedQueue())
        settings.saveQueue(listOf("t1", "t2", "t3"), index = 1, positionMs = 42_000L)
        assertEquals(SavedQueue(listOf("t1", "t2", "t3"), "t2", 42_000L), settings.savedQueue())
    }
}

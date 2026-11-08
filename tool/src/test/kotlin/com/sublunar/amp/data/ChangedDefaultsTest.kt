package com.sublunar.amp.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Simple and no covers are the defaults now. An install from before keeps the
 * look it had by default; a fresh one starts light and stays so on relaunch.
 */
class ChangedDefaultsTest {

    private val source = MusicSource("src-1", SourceKind.LOCAL, "Local Music")
    private val oldSources = """[{"id":"src-1","kind":"LOCAL","name":"Local Music"}]"""

    @Test
    fun `an install from before keeps Library Tabs and covers`() {
        val store = MemoryDataStore(mutablePreferencesOf(stringPreferencesKey("pref.sources") to oldSources))
        val settings = store.settings()
        assertEquals(LayoutMode.STANDARD, settings.layoutMode.value)
        assertEquals(ArtworkMode.SMALL, settings.artwork.value)
        assertEquals("STANDARD", store.raw("pref.layoutMode"))
        assertEquals("SMALL", store.raw("pref.artworkMode"))
    }

    @Test
    fun `a choice made before stands`() {
        val store = MemoryDataStore(
            mutablePreferencesOf(
                stringPreferencesKey("pref.sources") to oldSources,
                stringPreferencesKey("pref.layoutMode") to "SIMPLIFIED",
            ),
        )
        assertEquals(LayoutMode.SIMPLIFIED, store.settings().layoutMode.value)
    }

    @Test
    fun `a mode since dropped was read as covers on, and stays so`() {
        val store = MemoryDataStore(
            mutablePreferencesOf(
                stringPreferencesKey("pref.sources") to oldSources,
                stringPreferencesKey("pref.artworkMode") to "FULL",
            ),
        )
        assertEquals(ArtworkMode.SMALL, store.settings().artwork.value)
    }

    @Test
    fun `a fresh install starts Simple with no covers, and keeps that once it has a source`() = runBlocking {
        val store = MemoryDataStore()
        val first = store.settings()
        assertEquals(LayoutMode.SIMPLIFIED, first.layoutMode.value)
        assertEquals(ArtworkMode.NONE, first.artwork.value)
        first.saveSource(source)
        val relaunched = store.settings()
        assertEquals(LayoutMode.SIMPLIFIED, relaunched.layoutMode.value)
        assertEquals(ArtworkMode.NONE, relaunched.artwork.value)
    }

    @Test
    fun `an install from before keeps its records grouped by release type, by date`() {
        for (stored in listOf(null, "RELEASE_TYPE")) {
            val prefs = mutablePreferencesOf(stringPreferencesKey("pref.sources") to oldSources)
            if (stored != null) prefs[stringPreferencesKey("pref.artistAlbumOrder")] = stored
            val settings = MemoryDataStore(prefs).settings()
            assertEquals(true, settings.groupReleaseTypes.value)
            assertEquals(ArtistAlbumOrder.DATE_RELEASED, settings.artistAlbumOrder.value)
        }
    }

    @Test
    fun `an install from before that sorted its records by name keeps that, ungrouped`() {
        val store = MemoryDataStore(
            mutablePreferencesOf(
                stringPreferencesKey("pref.sources") to oldSources,
                stringPreferencesKey("pref.artistAlbumOrder") to "NAME",
            ),
        )
        val settings = store.settings()
        assertEquals(false, settings.groupReleaseTypes.value)
        assertEquals(ArtistAlbumOrder.NAME, settings.artistAlbumOrder.value)
    }

    @Test
    fun `a fresh install starts ungrouped by date, and keeps that once it has a source`() = runBlocking {
        val store = MemoryDataStore()
        val first = store.settings()
        assertEquals(false, first.groupReleaseTypes.value)
        assertEquals(ArtistAlbumOrder.DATE_RELEASED, first.artistAlbumOrder.value)
        first.saveSource(source)
        val relaunched = store.settings()
        assertEquals(false, relaunched.groupReleaseTypes.value)
        assertEquals(ArtistAlbumOrder.DATE_RELEASED, relaunched.artistAlbumOrder.value)
    }
}

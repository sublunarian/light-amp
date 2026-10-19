package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The names a source's state is stored under. An install reads them back
 * after every update, so a renamed key is a setting quietly lost — and the
 * per-source ones carry the source's id, which nothing may change either.
 */
class SettingsKeysTest {

    private val store = MemoryDataStore()
    private val settings = AppSettings(store)

    @Test
    fun `the chosen source`() = runBlocking {
        settings.setActiveSource("src-1")
        assertEquals("src-1", store.raw("pref.activeSource"))
    }

    @Test
    fun `when a source last synced`() = runBlocking {
        settings.setLastSyncedMs("src-1", 5)
        assertEquals("5", store.raw("state.lastSynced.src-1"))
    }

    @Test
    fun `how far a source's songs are checked`() = runBlocking {
        settings.setSongCheck("src-1", SongCheck(mark = "m"))
        assertEquals("""{"mark":"m"}""", store.raw("state.songCheck.src-1"))
    }

    @Test
    fun `a source's last known playlists`() = runBlocking {
        settings.setCachedPlaylists("src-1", "[]")
        assertEquals("[]", store.raw("state.playlists.src-1"))
    }
}

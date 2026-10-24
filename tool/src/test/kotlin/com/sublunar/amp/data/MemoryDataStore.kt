package com.sublunar.amp.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A DataStore held in memory, so [AppSettings] can be tested as it runs on the
 * phone — through its own reads and edits — with no file and no Android.
 *
 * Writes take turns, as the real one's do, and each keeps a frozen copy, so a
 * test reads back exactly what an edit left behind.
 */
internal class MemoryDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val writing = Mutex()

    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        writing.withLock { transform(state.value).toPreferences().also { state.value = it } }

    /** The stored string under [key], as the phone would hold it. */
    fun raw(key: String): String? = state.value[stringPreferencesKey(key)]

    /**
     * [AppSettings] over this store. Its states are held on whichever thread
     * writes, so each has caught up by the time a write returns.
     */
    fun settings(): AppSettings = AppSettings(this, CoroutineScope(Dispatchers.Unconfined))

    /** Everything stored, by key name, each value as the type it is stored as. */
    fun all(): Map<String, Any> = state.value.asMap().mapKeys { it.key.name }
}

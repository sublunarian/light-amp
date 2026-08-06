package com.thelightphone.sdk

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.shared.LightServerPushCredentials
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.map
import org.unifiedpush.android.connector.data.PushEndpoint
import kotlin.time.Instant

private val endpointKey = stringPreferencesKey("PUSH_ENDPOINT")
private val registrationDateKey = longPreferencesKey("PUSH_REGISTRATION")

class LightPushManager(private val context: Context) {
    private val dataStore by lazy { context.dataStore }

    val pushCredentialsFlow
        get() = dataStore.data.map {
            val endpoint = it[endpointKey] ?: return@map null
            val registrationDate = it[registrationDateKey]
                ?.run { Instant.fromEpochMilliseconds(this) }
                ?: return@map null
            LightServerPushCredentials(endpoint, registrationDate)
        }

    suspend fun updatePushCredentials(pushEndpoint: PushEndpoint) {
        // TODO save pubKeySet
        dataStore.edit { prefs ->
            prefs[endpointKey] = pushEndpoint.url
            prefs[registrationDateKey] = System.currentTimeMillis()
        }
    }

    suspend fun clearPushCredentials() {
        dataStore.edit { prefs ->
            prefs.remove(endpointKey)
            prefs.remove(registrationDateKey)
        }
    }
}
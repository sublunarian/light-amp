package com.thelightphone.sdk

import androidx.annotation.Keep
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

@Keep
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class EntryPoint

interface LightEntryPoint {
    suspend fun onToolCreate(serverData: StateFlow<LightServerData?>)

    suspend fun onPushNotification(data: ByteArray): Unit = Unit

    val enablePushNotifications: Boolean
        get() = false
}

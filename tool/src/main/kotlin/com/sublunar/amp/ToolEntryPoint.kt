package com.sublunar.amp

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import kotlinx.coroutines.flow.StateFlow

/**
 * Tool lifecycle hook. The player initializes its singletons lazily from the
 * first screen (which owns the activity context), so there is nothing to do
 * here yet. Push notifications are unused.
 */
@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {}

    override suspend fun onPushNotification(data: ByteArray) {}
}

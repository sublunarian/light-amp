package com.sublunar.amp

import com.sublunar.amp.data.ToolManagerFolders
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import com.thelightphone.toolmanager.ClientToolManifest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Required by the SDK. Amp boots from BootScreen instead (see App.boot), and uses no pushes. */
@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {}

    override suspend fun onPushNotification(data: ByteArray) {}

    override fun getToolManagerManifest(): ClientToolManifest = ToolManagerFolders.manifest()

    /**
     * Files came or went through the Tool Manager. A process with no booted app
     * has nothing to show them in; its next boot looks for changes itself.
     */
    override suspend fun onToolManagerDataUpdate() {
        if (App.isReady) App.scope.launch { App.librarySync.rescanPhoneIfChanged(afterUpload = true) }
    }
}

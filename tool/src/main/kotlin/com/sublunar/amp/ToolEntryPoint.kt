package com.sublunar.amp

import com.sublunar.amp.data.ToolManagerFolders
import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.sdk.shared.LightServerData
import com.thelightphone.toolmanager.ClientToolManifest
import kotlinx.coroutines.flow.StateFlow

/** Required by the SDK. Amp boots from BootScreen instead (see App.boot), and uses no pushes. */
@EntryPoint
object ToolEntryPoint : LightEntryPoint {
    override suspend fun onToolCreate(serverData: StateFlow<LightServerData?>) {}

    override suspend fun onPushNotification(data: ByteArray) {}

    override fun getToolManagerManifest(): ClientToolManifest = ToolManagerFolders.manifest()

    /**
     * A form was sent through the Tool Manager. A process with no booted app has
     * nothing to show it in; its next boot looks itself.
     */
    override suspend fun onToolManagerDataUpdate() {
        if (App.isReady) ToolManagerFolders.takeSentLogin()
    }
}

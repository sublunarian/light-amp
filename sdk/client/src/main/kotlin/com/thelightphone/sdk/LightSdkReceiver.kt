package com.thelightphone.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Marker receiver used by LightOS to discover installed SDK tools.
 * The intent filter and metadata are declared in the tool's AndroidManifest.
 */
class LightSdkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // No-op. This receiver exists so that LightOS can discover
        // this app via queryBroadcastReceivers with ACTION_SDK_MARKER.
    }
}

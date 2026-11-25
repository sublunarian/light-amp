package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import com.sublunar.amp.App
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * Every page Amp pushes. The volume rocker is LightOS's on every one of them,
 * casting or not: it turns the phone's media volume and shows its panel, and
 * while casting the output follows that level (see
 * PlaybackController's deviceVolume collector). On an older LightOS, where a
 * forwarded key turns the ringer, Amp steps the volume itself — see
 * PlaybackController.handleVolumeKey. The root, BootScreen, does the same.
 */
abstract class AmpScreen<R>(sealed: SealedLightActivity) : SimpleLightScreen<R>(sealed) {
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)
}

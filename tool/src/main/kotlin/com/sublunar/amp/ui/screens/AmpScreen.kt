package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import com.sublunar.amp.App
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * Every page Amp pushes. While casting, the volume rocker steers the speaker on
 * whichever page is up (see PlaybackController.handleVolumeKey); left to the
 * system, it would move the phone's own stream. The root, BootScreen, forwards
 * the keys itself.
 */
abstract class AmpScreen<R>(sealed: SealedLightActivity) : SimpleLightScreen<R>(sealed) {
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)
}

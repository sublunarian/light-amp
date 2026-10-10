package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sublunar.amp.App
import com.sublunar.amp.ui.PlayerTheme
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * Reads one QR code with the phone's camera and returns its text, or null when
 * dismissed with back.
 *
 * The SDK's own scanner, including its camera-permission request through
 * LightOS — the same one Light's Authenticator uses. Its barcode model was in
 * the APK already, through the SDK; the camera and scanning code that R8 used
 * to strip as unused is what using it adds, about 1 MB.
 */
class QrScanScreen(
    sealed: SealedLightActivity,
    private val title: String,
) : SimpleLightScreen<String?>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        var scanned by remember { mutableStateOf<String?>(null) }
        PlayerTheme {
            LightQrCodeScanner(
                title = title,
                onScanned = { scanned = it },
                onBack = { goBack(null) },
            )
        }
        // Not from inside onScanned: the scanner asks that navigating away wait
        // for a LaunchedEffect, so the camera is let go of in order.
        LaunchedEffect(scanned) {
            scanned?.let { goBack(it) }
        }
    }
}

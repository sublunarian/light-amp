package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sublunar.amp.ui.PlayerTheme
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.SealedLightActivity

/**
 * Reads one QR code with the phone's camera and returns its text, or null when
 * dismissed with back. The SDK's own scanner, camera-permission request
 * through LightOS included.
 */
class QrScanScreen(
    sealed: SealedLightActivity,
    private val title: String,
) : AmpScreen<String?>(sealed) {

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
        // for an effect, so the camera is released in order.
        LaunchedEffect(scanned) {
            scanned?.let { goBack(it) }
        }
    }
}

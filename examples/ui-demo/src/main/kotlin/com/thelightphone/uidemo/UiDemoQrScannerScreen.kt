package com.thelightphone.uidemo

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

class UiDemoQrScannerViewModel : LightViewModel<String>()

class UiDemoQrScannerScreen(sealedActivity: SealedLightActivity) :
    LightScreen<String, UiDemoQrScannerViewModel>(sealedActivity) {

    override val viewModelClass: Class<UiDemoQrScannerViewModel>
        get() = UiDemoQrScannerViewModel::class.java

    override fun createViewModel() = UiDemoQrScannerViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var pendingScan by remember { mutableStateOf<String?>(null) }

        LightTheme(colors = themeColors) {
            LightQrCodeScanner(
                title = "Scan QR Code",
                onScanned = { pendingScan = it },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }

        LaunchedEffect(pendingScan) {
            val value = pendingScan ?: return@LaunchedEffect
            pendingScan = null
            goBack(value)
        }
    }
}

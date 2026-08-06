package com.sublunar.amp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Wraps a screen in the Light theme, choosing the monochrome scheme from the
 * persisted "invert colors" preference, and fills the background.
 */
@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    val invert by App.settings.invertColors.collectAsState(initial = false)
    val colors = if (invert) LightThemeColors.Light else LightThemeColors.Dark
    LightTheme(colors = colors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

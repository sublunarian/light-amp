package com.sublunar.amp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.NoticePill
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Wraps a screen in the Light theme, choosing the monochrome scheme from the
 * persisted "invert colors" preference, fills the background, and provides the
 * [Scale] every size on the screen is drawn at — see [LocalScale].
 */
@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    val invert by App.settings.invertColors.collectAsState(initial = false)
    val colors = if (invert) LightThemeColors.Light else LightThemeColors.Dark
    LightTheme(colors = colors) {
        val scale = rememberScale()
        // The one place every screen passes through tells the artwork loader
        // how wide the panel really is — see ArtworkLoader.panelWidthPx.
        SideEffect { App.artwork.panelWidthPx = scale.windowWidthPx }
        CompositionLocalProvider(LocalScale provides scale) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                content()
                // Over every screen, so a write refused after its sheet has
                // closed is still said wherever the user has gone since.
                App.writeNotice.collectAsState().value?.let { NoticePill(it) }
            }
        }
    }
}

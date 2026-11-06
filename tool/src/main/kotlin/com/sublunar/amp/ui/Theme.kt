package com.sublunar.amp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
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
fun PlayerTheme(
    /** The library's pages and the player, as against menus and settings: see [LocalDimTextAlpha]. */
    library: Boolean = false,
    content: @Composable () -> Unit,
) {
    val invert by App.settings.invertColors.state.collectAsState()
    // Menus and settings have no grey.
    val dimAlpha = when {
        !library -> 1f
        invert -> GREY_ON_WHITE_ALPHA
        else -> GREY_ON_BLACK_ALPHA
    }
    val colors = if (invert) LightThemeColors.Light else LightThemeColors.Dark
    LightTheme(colors = colors) {
        val scale = rememberScale()
        // The one place every screen passes through tells the artwork loader
        // how wide the panel really is — see ArtworkLoader.panelWidthPx.
        SideEffect { App.artwork.panelWidthPx = scale.windowWidthPx }
        CompositionLocalProvider(
            LocalScale provides scale,
            LocalDimTextAlpha provides dimAlpha,
            LocalGreyText provides library,
        ) {
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

/**
 * How far what can't take a colour (the player's glyphs, the queue's past rows)
 * fades to reach the grey on this screen, white being 1: see [LocalGreyText].
 */
val LocalDimTextAlpha = compositionLocalOf { GREY_ON_BLACK_ALPHA }

/**
 * What can't be used right now (an index letter with nothing under it, a row
 * with no choice to make, a fader Amp can't move): fainter than any secondary
 * grey in either scheme, about #595959 over black and #A6A6A6 over white, and the
 * same on every page, menus included.
 */
const val DISABLED_ALPHA = 0.35f

/**
 * Whether dimmed text on this screen is drawn in the SDK's own grey (`LightText`'s
 * lighten): the library and player have it; menus and settings have no grey.
 * What can't take a colour fades to the same grey: #BBBBBB over black, #666666
 * over white.
 */
val LocalGreyText = compositionLocalOf { false }
private const val GREY_ON_BLACK_ALPHA = 0xBB / 255f
private const val GREY_ON_WHITE_ALPHA = 1f - 0x66 / 255f

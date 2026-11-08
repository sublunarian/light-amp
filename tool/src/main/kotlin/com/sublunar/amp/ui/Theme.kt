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
import androidx.compose.ui.graphics.lerp
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
    val dimAlpha = if (library) GREY_ALPHA else 1f
    val sdk = if (invert) LightThemeColors.Light else LightThemeColors.Dark
    // Amp's one grey in place of the SDK's, so its own components draw it too.
    val colors = sdk.copy(contentSecondary = lerp(sdk.background, sdk.content, GREY_ALPHA))
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
val LocalDimTextAlpha = compositionLocalOf { GREY_ALPHA }

/**
 * Amp's one grey, as a fade from the page to its text: #969696 over black and
 * #696969 over white. Darker than the SDK's #BBBBBB, so an unlit tab reads as off,
 * and still clear at the lowest screen brightness. Every grey in the app is this
 * one: secondary text, unlit tabs, the player's secondary controls, and
 * what can't be used right now.
 */
const val GREY_ALPHA = 0.59f

/** What can't be used right now (an index letter with nothing under it, a row with no choice to make): the grey. */
const val DISABLED_ALPHA = GREY_ALPHA

/**
 * Whether dimmed text on this screen is drawn in the grey (`LightText`'s
 * lighten): the library and player have it; menus and settings have no grey.
 * What can't take a colour fades to the same grey — see [GREY_ALPHA].
 */
val LocalGreyText = compositionLocalOf { false }

package com.sublunar.amp.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import com.sublunar.amp.ui.currentScale
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.sdk.refreshKeyboardOptions
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The last answer LightOS gave, so a later keyboard opens with the phone's
 * settings rather than the SDK's defaults while the question is asked again.
 */
private var lastKnown: KeyboardOptions? = null

/**
 * The keyboard as the phone has it set (swipe typing, key animation), asked of
 * LightOS once per keyboard. The SDK's `rememberKeyboardOptions` asks again on
 * every recomposition, which on the search page is a call per keystroke. No
 * answer from LightOS leaves the SDK's defaults.
 *
 * **No emoji anywhere in Amp**: nothing typed here is a message. The keyboard
 * draws a spacer where the emoji key would be when handed none, so an empty set
 * is its own no-emoji state.
 *
 * **No voice key either**: the keyboard draws one when the phone's setting says
 * so, but has no dictation behind it yet. `rememberKeyboardOptions` masks it the
 * same way, `refreshKeyboardOptions` doesn't. Drop this pin when the key does
 * something.
 */
@Composable
private fun rememberPhoneKeyboardOptions(): StateFlow<KeyboardOptions> {
    fun pinned(options: KeyboardOptions) = options.copy(
        emojis = emptyList(),
        displayVoice = false,
    )
    val flow = remember { MutableStateFlow(pinned(lastKnown ?: defaultKeyboardOptions())) }
    LaunchedEffect(Unit) {
        refreshKeyboardOptions()?.let {
            lastKnown = it
            flow.value = pinned(it)
        }
    }
    return flow
}

/**
 * The LP3 keyboard typing into [state], set up as the phone has it — for the
 * search page and [TextEntryScreen]. Built once per [state]: [onReturn] and
 * [onHaptic] are taken from the first composition.
 */
@Composable
internal fun rememberEditingKeyboard(
    state: TextFieldState,
    onReturn: () -> Unit,
    /** The search page passes none: its keys never buzzed. */
    onHaptic: () -> Unit = {},
): EnQwertyLp3KeyboardViewModel<Unit> {
    val options = rememberPhoneKeyboardOptions()
    // In remember rather than viewModel(): the tool doesn't carry
    // lifecycle-viewmodel-compose, and the view model lives exactly as long as
    // the typing does.
    return remember(state) {
        EnQwertyLp3KeyboardViewModel<Unit>(
            EditingKeyboardCallback(state, onReturn, onHaptic),
            keyboardOptionsFlow = options,
            optionsForLayout = { LayoutOptions(!it.isRootLayout) },
        )
    }
}

/**
 * Draws [content] (Light's keyboard, and the SDK bar under it) as if on an LP3,
 * scaled to this window's width. The keyboard is laid out in fixed dp for the
 * LP3's 360dp-wide panel, so on another phone it came out small while the rest
 * of Amp, sized from the window's width, did not. Here the density makes 360dp
 * span the window, and the configuration says the screen is 360dp wide, so the
 * SDK's grid units inside agree with it. On the LP3 both are what they already
 * are, and nothing changes.
 */
@Composable
internal fun AtLp3Scale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val window = LocalWindowInfo.current.containerSize
    val widthPx = currentScale().windowWidthPx
    val scaled = remember(widthPx, density.fontScale) { Density(widthPx / LP3_WIDTH_DP, density.fontScale) }
    val lp3Screen = remember(configuration, widthPx, window.height) {
        Configuration(configuration).apply {
            screenWidthDp = LP3_WIDTH_DP.toInt()
            screenHeightDp = (window.height / scaled.density).toInt()
        }
    }
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalConfiguration provides lp3Screen,
        content = content,
    )
}

/** The LP3's panel in dp: 1080px at density 3. */
private const val LP3_WIDTH_DP = 360f

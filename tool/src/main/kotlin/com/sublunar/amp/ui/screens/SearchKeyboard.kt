package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard

/**
 * The LP3's own keyboard, against the bottom of the search page.
 *
 * The SDK hosts it inside [com.thelightphone.sdk.ui.LightTextInputEditor], a
 * whole screen with the text at the top and nothing below, which is no use to a
 * search that shows its results narrowing as you type. The keyboard composable
 * and its view model are public, so the page keeps its own layout and places
 * the keyboard last. Nothing in the SDK is patched for this.
 */
@Composable
fun SearchKeyboard(state: TextFieldState, onReturn: () -> Unit) {
    val trimTopPx = with(LocalDensity.current) { px(KEYBOARD_TRIM_TOP_PX).roundToPx() }
    // The keys stay as the SDK draws them; only the empty padding it leaves
    // above them is cut, so the results get the room.
    Box(
        Modifier
            .clipToBounds()
            .layout { measurable, constraints ->
                val keyboard = measurable.measure(constraints)
                layout(keyboard.width, (keyboard.height - trimTopPx).coerceAtLeast(0)) {
                    keyboard.place(0, -trimTopPx)
                }
            },
    ) {
        AtLp3Scale {
            // The SDK editor's own button under the keys, as LightTextInputEditor
            // lays it out, worded for what it does here.
            LightEmbeddedLp3Keyboard(
                viewModel = rememberEditingKeyboard(state, onReturn),
                additionalBottomHeight = 5f.gridUnitsAsDp(),
                bottomBar = { LightBottomBar(items = listOf(LightBarButton.Text(text = "SEARCH", onClick = onReturn))) },
            )
        }
    }
}

/**
 * The SDK's 10dp of padding above the keyboard, which only separates it from a
 * text field the search page doesn't have under it.
 */
private const val KEYBOARD_TRIM_TOP_PX = 30

package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.sublunar.amp.App
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.ChoiceButton
import com.sublunar.amp.ui.components.rememberTapHaptics
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard

/**
 * Full-screen text entry backed by the LP3 keyboard. Returns the submitted text
 * as its navigation result, or null when dismissed with back.
 *
 * Holding the text swaps the keys for Notes' menu — see [TextEditor].
 */
class TextEntryScreen(
    sealed: SealedLightActivity,
    private val title: String,
    private val initial: String = "",
    private val submitLabel: String = "DONE",
) : SimpleLightScreen<String?>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        PlayerTheme {
            val state = rememberTextFieldState(initial)
            TextEditor(
                title = title,
                state = state,
                submitLabel = submitLabel,
                onSubmit = { goBack(state.text.toString()) },
                onBack = { goBack(null) },
            )
        }
    }
}

/**
 * The SDK's `LightTextInputEditor`, laid out the same way from the same public
 * parts, plus what Notes does when its text is held: its menu where the keys
 * were (see [EditMenu]), with a chevron beneath to bring the keys back.
 *
 * The SDK's editor has no way in for this (its text area takes every touch to
 * place the cursor), but the keyboard does: [LightEmbeddedLp3Keyboard]'s
 * `overlay` is drawn in place of the keys, with its own chevron in the bar.
 *
 * The hold lasts as long as the phone's own (`ViewConfiguration`'s long-press
 * timeout, as LightOS's text view uses), not Amp's slower row hold.
 *
 * **If the SDK's editor changes, this will not follow**, the same cost
 * [EditingKeyboardCallback] carries.
 */
@Composable
private fun TextEditor(
    title: String,
    state: TextFieldState,
    submitLabel: String,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val inputStyle = inputTextStyle()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var menu by remember { mutableStateOf(false) }
    // The thud every hold in Amp gives when it lands, and a tick per key when
    // the phone's own haptics setting is on, as the SDK's editor reads it.
    val (tick, thud) = rememberTapHaptics()
    val hapticsOn by rememberUpdatedState(LocalHapticsEnabled.current)
    val keyboard = rememberEditingKeyboard(
        state,
        onReturn = onSubmit,
        onHaptic = { if (hapticsOn) tick() },
    )

    fun placeCursor(position: Offset) {
        val layout = textLayout ?: return
        state.edit { selection = TextRange(layout.getOffsetForPosition(position)) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
            center = LightTopBarCenter.Text(title),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp())
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        placeCursor(down.position)
                        // Lifted in time is a tap, which has already placed the
                        // cursor. Moving first drags it, as in the SDK. Neither
                        // by the deadline is the hold.
                        var dragged = false
                        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val moved = (change.position - down.position).getDistance()
                                if (moved > viewConfiguration.touchSlop) {
                                    dragged = true
                                    break
                                }
                            }
                            true
                        }
                        when {
                            ended == null -> {
                                // Clear is all the menu holds, so an empty field
                                // has nothing to offer and the hold does nothing.
                                if (state.text.isNotEmpty()) {
                                    thud()
                                    menu = true
                                }
                                // Swallow the release so it doesn't also read as a tap.
                                waitForUpOrCancellation()
                            }
                            dragged -> drag(down.id) { change ->
                                placeCursor(change.position)
                                change.consume()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.TopStart,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                BasicText(
                    text = state.text.toString(),
                    style = inputStyle,
                    onTextLayout = { textLayout = it },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(INPUT_UNDERLINE_GAP_GRID_UNITS.gridUnitsAsDp()))
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(INPUT_UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                        .background(colors.content),
                )
            }
            textLayout?.let { layout ->
                val cursorPos = state.selection.min.coerceIn(0, layout.layoutInput.text.length)
                val rect = layout.getCursorRect(cursorPos)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                        .width(2.dp)
                        .height(with(LocalDensity.current) { rect.height.toDp() })
                        .background(colors.content),
                )
            }
        }

        LightEmbeddedLp3Keyboard(
            viewModel = keyboard,
            additionalBottomHeight = 5f.gridUnitsAsDp(),
            bottomBar = {
                LightBottomBar(
                    items = listOf(LightBarButton.Text(text = submitLabel, onClick = onSubmit)),
                )
            },
            // Only while the menu is up: the keyboard sends its chevron here
            // whenever this is set, and the number page's chevron (back to
            // letters) would otherwise close a menu that isn't open.
            onOverlayDismissed = if (menu) {
                { menu = false }
            } else {
                null
            },
            overlay = if (menu) {
                { EditMenu(state, onDone = { menu = false }) }
            } else {
                null
            },
        )
    }
}

/**
 * Notes' menu in the keys' place: Clear alone. Notes also offers Copy and Paste,
 * but LightOS keeps its own clipboard and nothing in the SDK reaches it. A tool
 * only gets Android's, which nothing else on the phone fills or reads. They
 * belong here once LightOS shares its clipboard with tools (light-sdk#45, see
 * SDK-GAPS.md).
 */
@Composable
private fun EditMenu(state: TextFieldState, onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        ChoiceButton("CLEAR") {
            state.clearText()
            onDone()
        }
    }
}

/** The SDK editor's input text: Heading, scaled to the screen's height as it does. */
@Composable
private fun inputTextStyle(): TextStyle {
    val heading = LightThemeTokens.typography.heading
    return heading.copy(
        color = LightThemeTokens.colors.content,
        fontSize = heading.fontSize.scaledForScreenHeight(),
        lineHeight = heading.lineHeight.scaledForScreenHeight(),
        letterSpacing = heading.letterSpacing.scaledForScreenHeight(),
    )
}

/** The SDK's own `scaledForScreenHeight`, which is `internal`. */
@Composable
private fun TextUnit.scaledForScreenHeight(): TextUnit =
    if (this == TextUnit.Unspecified) this else value.designVerticalPxToSp()

// The SDK editor's underline, restated because its constants are private.
private const val INPUT_UNDERLINE_THICKNESS_PX = 3f
private const val INPUT_UNDERLINE_GAP_GRID_UNITS = 0.5f

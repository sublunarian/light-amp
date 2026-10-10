package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
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
 * The keyboard as the phone has it set — swipe typing, key animation, the
 * voice key — asked of LightOS when a keyboard appears.
 *
 * The keyboard is a library every tool ships a copy of; the settings for it are
 * the phone's, and only LightOS knows them. Amp used to hand every keyboard the
 * SDK's fixed defaults, so a phone with swipe typing on typed without it here.
 * The SDK's own tools ask through `rememberKeyboardOptions`, which asks again
 * on every recomposition — on the search page that would be a call per
 * keystroke — so this asks once per keyboard and keeps the answer. LightOS not
 * answering, an older build or a hiccup, leaves the defaults in place, which is
 * exactly what the keyboard showed before.
 *
 * **No emoji, anywhere in Amp.** The phone's emoji set is a messaging
 * preference, and nothing typed here is a message: a search, a server address,
 * a playlist name, a password. The library draws the emoji key only when it is
 * handed some, and puts a spacer in its place otherwise — so an empty set is
 * the keyboard's own no-emoji state, not a layout of ours.
 *
 * **No voice key either, for now.** Keyboard 0.0.19 draws a dictation key when
 * the phone's setting says so, but the library has no dictation behind it yet —
 * Light's own `fix/hide-voice` masks it the same way in `rememberKeyboardOptions`,
 * which Amp doesn't use (see above). Drop this pin when the key does something.
 */
@Composable
fun rememberPhoneKeyboardOptions(): StateFlow<KeyboardOptions> {
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

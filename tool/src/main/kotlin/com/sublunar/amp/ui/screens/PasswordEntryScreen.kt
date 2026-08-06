package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import com.sublunar.amp.App
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sublunar.amp.ui.PlayerTheme
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Password entry on the LP3 keyboard, masked as you type.
 *
 * [LightTextInputEditor] renders `state.text` verbatim — there is no mask or
 * visual transformation — so handing it a password would print it on screen at
 * heading size. Rather than patch the SDK or rebuild the editor, this feeds it a
 * [TextFieldState] containing only bullets while keeping the real characters in a
 * separate buffer. The editor's chrome, layout and keyboard are the stock ones;
 * only the keystroke callback differs.
 *
 * The buffer is a [StringBuilder] that is wiped when the screen finishes, so the
 * password isn't left sitting in an immutable String for the GC to copy around.
 */
class PasswordEntryScreen(
    sealed: SealedLightActivity,
    private val title: String = "Password",
) : SimpleLightScreen<String?>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        PlayerTheme {
            // What the editor draws: one bullet per real character.
            val masked = rememberTextFieldState("")
            val secret = remember { StringBuilder() }
            val options = remember { MutableStateFlow(defaultKeyboardOptions()) }

            val callback = remember {
                MaskedKeyboardCallback(
                    masked = masked,
                    secret = secret,
                    onReturn = { finish(secret) },
                )
            }
            // Constructed directly rather than through `viewModel()`: that helper
            // lives behind an `implementation` dependency the tool can't see, and a
            // remembered instance is enough for a portrait-locked, single-use screen.
            val keyboard: Lp3KeyboardViewModel<*> = remember {
                EnQwertyLp3KeyboardViewModel<Unit>(
                    callback,
                    keyboardOptionsFlow = options,
                    optionsForLayout = { LayoutOptions(!it.isRootLayout) },
                )
            }

            LightTextInputEditor(
                title = title,
                state = masked,
                onSubmit = { finish(secret) },
                onBack = { cancel(secret, masked) },
                viewModel = keyboard,
                submitLabel = "DONE",
                singleLine = true,
            )
        }
    }

    private fun finish(secret: StringBuilder) {
        val value = secret.toString()
        secret.setLength(0)
        goBack(value)
    }

    private fun cancel(secret: StringBuilder, masked: TextFieldState) {
        secret.setLength(0)
        masked.clearText()
        goBack(null)
    }
}

/** One bullet per character, so the shape of the password never reaches the screen. */
private const val MASK = '•'

/**
 * Applies keystrokes to the hidden buffer and a matching bullet to the visible
 * state. Only the keys a password needs are handled; layout keys (case, numbers,
 * symbols, emoji) are the keyboard's own business and never reach here as text.
 */
private class MaskedKeyboardCallback(
    private val masked: TextFieldState,
    private val secret: StringBuilder,
    private val onReturn: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    private fun append(text: String) {
        if (text.isEmpty()) return
        secret.append(text)
        masked.edit { append(MASK.toString().repeat(text.length)) }
    }

    private fun backspace() {
        if (secret.isEmpty()) return
        // Surrogate-aware: an emoji is two chars but one bullet's worth of intent.
        val drop = if (secret.length >= 2 &&
            Character.isSurrogatePair(secret[secret.length - 2], secret[secret.length - 1])
        ) 2 else 1
        secret.setLength(secret.length - drop)
        masked.edit { if (length > 0) delete(length - 1, length) }
    }

    override fun onKeyReleased(code: Int) = append(String(Character.toChars(code)))

    override fun onKeyRepeated(code: Int) = append(String(Character.toChars(code)))

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> backspace()
            SpecialKey.Space -> append(" ")
            SpecialKey.Return, SpecialKey.Submit -> onReturn()
            else -> Unit
        }
    }

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Backspace) backspace()
        if (specialKey == SpecialKey.Space) append(" ")
    }

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        // Long-press backspace deletes a word in the stock callback; for a password
        // there are no words to speak of, so treat it as a single delete.
        if (key == SpecialKey.Backspace) backspace()
    }

    // Nothing to do on press/cancel/long-press of a character key: text is only
    // committed on release, matching the stock behaviour.
    override fun onKeyPressed(code: Int) = Unit
    override fun onSpecialKeyPressed(key: SpecialKey) = Unit
    override fun onKeyLongPressed(code: Int) = Unit
    override fun onKeyCancelled(code: Int) = Unit

    /** Predictive text would leak the password into a suggestion strip. Ignored. */
    override fun onSubmitWord(word: CharSequence) = Unit
}

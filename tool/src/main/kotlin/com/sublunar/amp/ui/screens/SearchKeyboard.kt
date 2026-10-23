package com.sublunar.amp.ui.screens

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextRange
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
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
    LightEmbeddedLp3Keyboard(viewModel = rememberEditingKeyboard(state, onReturn))
}

/**
 * What the keys do to the text: the search query, and every field typed in
 * [TextEntryScreen]. Return ends the typing rather than inserting a break.
 *
 * A port of the SDK's `TextInputKeyboardCallback`, which is `internal`.
 * **If the SDK's editing behaviour changes, this will not follow**: the other
 * way was to make that class public, which means patching the SDK.
 */
internal class EditingKeyboardCallback(
    private val state: TextFieldState,
    private val onReturn: () -> Unit,
    /** A tick as each key goes down, as the SDK's callback gives. */
    private val onHaptic: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = onHaptic()

    override fun onSpecialKeyPressed(key: SpecialKey) {
        onHaptic()
        if (key == SpecialKey.Space) insert(" ")
    }

    override fun onKeyReleased(code: Int) = insertCodePoint(code)

    override fun onKeyRepeated(code: Int) = insertCodePoint(code)

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            // Surrogate-aware, or deleting an emoji leaves half of one behind.
            SpecialKey.Backspace -> deleteBefore(surrogateAwareCount())
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insert(" ")
    }

    override fun onKeyLongPressed(code: Int) = Unit

    /** Holding backspace takes the whole word, as it does in the SDK's editor. */
    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key != SpecialKey.Backspace) return
        val before = state.text.subSequence(0, state.selection.min)
        val trimmed = before.trimEnd()
        val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
        deleteBefore(before.length - if (lastSpace >= 0) lastSpace + 1 else 0)
    }

    override fun onSubmitWord(word: CharSequence) = insert(word.toString())

    private fun surrogateAwareCount(): Int {
        val before = state.text.subSequence(0, state.selection.min)
        if (before.isEmpty()) return 0
        return if (Character.isLowSurrogate(before[before.length - 1])) 2 else 1
    }

    private fun insertCodePoint(code: Int) = insert(buildString { appendCodePoint(code) })

    private fun insert(text: String) {
        state.edit {
            val start = selection.min
            replace(start, selection.max, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBefore(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}

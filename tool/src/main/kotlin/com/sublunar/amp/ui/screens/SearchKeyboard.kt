package com.sublunar.amp.ui.screens

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The LP3's own keyboard, against the bottom of the search page.
 *
 * The SDK hosts this inside [com.thelightphone.sdk.ui.LightTextInputEditor],
 * which is a whole screen: the text sits at the top with nothing below it, which
 * is no use to a search that has to show its results narrowing as you type. The
 * keyboard itself is a composable though, and the view model that drives it is
 * public — so the page keeps its own layout and simply places the keyboard last.
 *
 * Nothing in the SDK is patched or worked around to do this. The one thing not
 * reused is `TextInputKeyboardCallback`, which is `internal`, so
 * [SearchKeyboardCallback] below implements the same public interface instead.
 */
@Composable
fun SearchKeyboard(state: TextFieldState, onReturn: () -> Unit) {
    val callback = remember(state) { SearchKeyboardCallback(state, onReturn) }
    val options = remember { MutableStateFlow(defaultKeyboardOptions()) }
    // Held in remember rather than through viewModel(): the tool doesn't carry
    // lifecycle-viewmodel-compose, and this is a state holder for a view that
    // lives exactly as long as the typing does.
    val keyboard = remember(callback) {
        EnQwertyLp3KeyboardViewModel<Unit>(
            callback,
            keyboardOptionsFlow = options,
            optionsForLayout = { LayoutOptions(!it.isRootLayout) },
        )
    }
    LightEmbeddedLp3Keyboard(viewModel = keyboard)
}

/**
 * What the keys do to the query.
 *
 * A port of the SDK's own `TextInputKeyboardCallback`, which is `internal` and so
 * cannot be reused from a tool. Single-line throughout — a search is one line —
 * so Return ends the typing rather than inserting a break.
 *
 * **If the SDK's editing behaviour changes, this will not follow.** It is a copy
 * of a private thing, which is the one real cost of hosting the keyboard here;
 * the alternative was to make that class public, which means patching the SDK.
 */
private class SearchKeyboardCallback(
    private val state: TextFieldState,
    private val onReturn: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = Unit

    override fun onSpecialKeyPressed(key: SpecialKey) {
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

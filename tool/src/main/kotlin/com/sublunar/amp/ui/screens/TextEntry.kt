package com.sublunar.amp.ui.screens

import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.textentry.LightTextEditResult

/** How a field is typed: which the keyboard capitalises, and which it hides. */
enum class TextKind {
    /** A name people read: playlists, sources. Sentences start with a capital. */
    NAME,

    /** Typed exactly as it is: addresses, usernames, tokens, searches. */
    EXACT,

    /** Shown as dots, never capitalised. */
    PASSWORD,
}

/**
 * Lets the user type [title] full screen in LightOS's keyboard app (the SDK's
 * own editor on a phone without one), and hands [onResult] what was
 * submitted. Backing out hands back nothing.
 */
fun SimpleLightScreen<*>.enterText(
    title: String,
    initial: String,
    kind: TextKind,
    onResult: (String) -> Unit,
) {
    editText(
        title = title,
        text = initial,
        singleLine = true,
        autoCapitalize = kind == TextKind.NAME,
        password = kind == TextKind.PASSWORD,
    ) { result ->
        if (result is LightTextEditResult.Submitted) onResult(result.text)
    }
}

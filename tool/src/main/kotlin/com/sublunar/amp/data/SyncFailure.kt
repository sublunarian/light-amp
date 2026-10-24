package com.sublunar.amp.data

/**
 * Whether a failure was the server turning us away rather than not being there:
 * an unreachable server leaves only the downloads to show, a refused login
 * leaves the cached library fine and the credentials stale.
 *
 * Subsonic says so in its own error codes, with no status — see
 * ServerException.refusedLogin. Anything else is read from the message.
 */
internal fun isAuthFailure(e: Exception): Boolean {
    if ((e as? ServerException)?.refusedLogin == true) return true
    val code = STATUS_IN_MESSAGE.find(e.message.orEmpty())?.value?.toIntOrNull()
    return code == 401 || code == 403
}

private val STATUS_IN_MESSAGE = Regex("""\b(\d{3})\b""")

/**
 * A sync failure in words the person holding the phone can act on, short enough
 * for the one row that shows it. A 5xx says the server is down, not the app or
 * the login.
 */
internal fun syncErrorMessage(e: Exception): String {
    // Only the status the exception carries: a timeout's message names its URL,
    // and a `Container-Size=500` in it is not a 500.
    val code = (e as? ServerException)?.status
    if ((e as? ServerException)?.refusedLogin == true) return LOGIN_REFUSED
    if (code == null && e is java.io.IOException) return "Couldn't reach the server"
    if (code == null) return e.message?.takeIf { it.length < 60 } ?: "Couldn't reach the server"
    return serverDownWords(code) ?: when (code) {
        401, 403 -> LOGIN_REFUSED
        404 -> "That library isn't on the server any more"
        else -> "The server said $code"
    }
}

private const val LOGIN_REFUSED = "The server refused the login. Sign in again in Sources."

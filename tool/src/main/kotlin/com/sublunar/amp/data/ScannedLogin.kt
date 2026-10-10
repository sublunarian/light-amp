package com.sublunar.amp.data

/**
 * A username and password read out of a QR code.
 *
 * The code is made by the user from any QR generator, so the format is the
 * plainest one anyone could type: the username on the first line and the
 * password on the second. Two words on a single line are taken the same way,
 * since some generators fold a line break into a space. Anything else — one
 * word, three lines — is not guessed at: a wrong guess would put an address in
 * the username field or half a password in the password one, and the login
 * would fail with nothing to say why.
 */
data class ScannedLogin(val username: String, val password: String) {
    companion object {
        fun parse(text: String): ScannedLogin? {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val parts = when (lines.size) {
                2 -> lines
                1 -> lines.single().split(Regex("""\s+""")).takeIf { it.size == 2 }
                else -> null
            } ?: return null
            return ScannedLogin(username = parts[0], password = parts[1])
        }
    }
}

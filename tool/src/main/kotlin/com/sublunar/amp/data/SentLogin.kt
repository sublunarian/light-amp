package com.sublunar.amp.data

/** A login typed on a computer and sent through the Tool Manager. Never printed: see [toString]. */
class SentLogin(val username: String, val password: String) {
    override fun toString(): String = "SentLogin(…)"
}

package com.sublunar.amp.data

import java.net.URI

/**
 * Field tracing for "playlists won't load off Wi-Fi" (field notes, 2026-10-14/15):
 * a downloaded playlist opens empty on cellular, while the Artists tab plays —
 * even streams — and the player shows "Waiting for server".
 *
 * Everything goes out under one tag, so the whole story reads in order with
 *
 *     adb logcat -s AmpTrace AmpNet AmpSync AmpProxy
 *
 * Info level, so it survives in a release build's logcat. Tokens and
 * passwords never appear: a server is named by its host alone.
 */
object Trace {
    const val TAG = "AmpTrace"
    private const val SELF = "com.sublunar.amp.data.Trace"

    fun i(area: String, text: String) {
        android.util.Log.i(TAG, "$area: $text")
    }

    fun w(area: String, text: String, error: Throwable? = null) {
        android.util.Log.w(TAG, "$area: $text${error?.let { " — ${it::class.simpleName}: ${it.message}" }.orEmpty()}")
    }

    /**
     * A server URL's host and port, marked when it can only be reached from a
     * home network — the first thing to rule out when something works on
     * Wi-Fi and not on cellular.
     */
    fun host(url: String): String {
        if (url.isBlank()) return "(no address)"
        val uri = runCatching { URI(url) }.getOrNull() ?: return "(unparsable address)"
        val host = uri.host ?: return "(no host)"
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "${uri.scheme}://$host$port${reachNote(host)?.let { " [$it]" }.orEmpty()}"
    }

    private fun reachNote(host: String): String? {
        val h = host.lowercase()
        if (h == "localhost" || h.startsWith("127.")) return "loopback"
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || h.endsWith(".internal")) {
            return "LAN name"
        }
        if (h.endsWith(".ts.net")) return "Tailscale"
        if (h.endsWith(".plex.direct")) {
            // 192-168-1-5.<hash>.plex.direct resolves to the address it spells.
            val spelled = h.substringBefore('.').replace('-', '.')
            return reachNote(spelled)?.let { "plex.direct → $it" } ?: "plex.direct"
        }
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return null
        val (a, b) = parts
        return when {
            a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) -> "LAN only"
            a == 169 && b == 254 -> "link-local"
            a == 100 && b in 64..127 -> "CGNAT/Tailscale"
            else -> null
        }
    }

    /**
     * Where a call came from: the first of amp's own frames that isn't this
     * file or one of [skip] — each a "Class.method" prefix, as printed.
     */
    fun caller(vararg skip: String): String =
        Throwable().stackTrace.asSequence()
            .filter { it.className.startsWith("com.sublunar.") && it.className != SELF }
            .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            .firstOrNull { frame -> skip.none { frame.startsWith(it) } }
            ?: "?"
}

package com.sublunar.amp.log

/**
 * The last pass over every log line before it goes anywhere: logcat, the
 * phone's own log, a saved copy.
 *
 * A net under the rules in tool/docs/LOGGING.md, not a replacement for them.
 * Lines are meant to hold nothing these patterns look for; this catches the one
 * that slipped. It can't know a bare word is a host name ("navidrome", "music")
 * or that an id is an id, so what a line says is still decided where it's
 * written.
 *
 * Everything a pattern takes becomes a placeholder in angle brackets, so a
 * reader can see something was there.
 */
object LogScrubber {
    /** A line longer than this is cut: nothing worth reading needs more, and a runaway string can't fill the log. */
    const val MAX_CHARS = 400

    // Control characters, Unicode line and paragraph separators, and bidi
    // controls: any of them could break one line into two, or reorder text
    // so that it reads as something else.
    private val controls = Regex("""[\p{Cc}\p{Cf}  ]""")

    // JSON writes "https:\/\/host"; undone first so the URL rule sees a URL.
    private val jsonSlash = Regex("""\\/""")

    // Any scheme://…: hosts, paths, queries and logins all live in one, and
    // none of it belongs in a line.
    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>\]\[)(]*""")

    // A query's secrets, should a fragment of one survive without its scheme.
    private val querySecret = Regex(
        """(?i)([?&;](?:u|t|s|p|salt|token|apikey|api_key|access_token|x-plex-token|x-emby-token|x-mediabrowser-token)=)[^&#\s"']*""",
    )

    // A header carrying a login, written as "name: value", "name=value" or as
    // a Kotlin Pair "(name, value)".
    private val secretHeader = Regex(
        """(?i)\b(authorization|proxy-authorization|cookie|set-cookie|x-plex-token|x-emby-token|""" +
            """x-emby-authorization|x-mediabrowser-token|remote-user|remote-email|cf-access-token|""" +
            """cf-access-client-id|cf-access-client-secret|cf_authorization)(\s*[:=,]\s*)(?:(?:basic|bearer|mediabrowser)\s+)?\S+""",
    )
    private val bearer = Regex("""(?i)\b(basic|bearer|mediabrowser)\s+[A-Za-z0-9+/=._~-]{8,}""")

    // A path on the phone runs to the end of the line: a song's file name has
    // spaces in it, so no pattern can tell where it stops.
    private val phonePath = Regex("""(?:file:|m3u:)?/(?:storage|sdcard|data|mnt)/.*""")

    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    // Plex's per-server names carry the address and the server's id.
    private val plexDirect = Regex("""(?i)\b[\w-]+\.[0-9a-f]{32}\.plex\.direct\b""")

    // host:port, and names on the private suffixes home networks use. A stack
    // frame's "Foo.kt:12" is not a host.
    private val hostPort = Regex("""(?i)\b(?:[a-z0-9-]+\.)+(?!kt:|java:)[a-z][a-z0-9-]*:\d{2,5}\b""")
    private val localHost = Regex("""(?i)\b(?:[a-z0-9-]+\.)+(?:lan|local|home|internal|localdomain|home\.arpa|ts\.net)\b""")

    // Loopback stays: it says the stream proxy, and identifies nothing.
    private val ipv4 = Regex(
        """\b(?!127\.)(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)\b""",
    )
    private val mac = Regex("""(?i)\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}\b""")

    // Candidates only; [isIpv6] decides. A time like 1:02:33 is hex too, so an
    // address needs "::" or five colons.
    private val ipv6Candidate = Regex("""(?i)(?<![0-9a-z:.])[0-9a-f:]*:[0-9a-f:]*:[0-9a-f:]*(?:%[0-9a-z]+)?(?![0-9a-z:])""")

    private val uuid = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
    private val longHex = Regex("""(?i)\b[0-9a-f]{16,}\b""")

    // A run of 20 or more letters and digits with both cases and a digit:
    // Plex tokens, Navidrome ids. A class name has no digit; a media3 error
    // name has no lower case, and a synthetic class's "$$ExternalSyntheticLambda0" follows a "$".
    private val token = Regex("""(?<![$.\w])(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Z])(?=[A-Za-z0-9_-]*[a-z])[A-Za-z0-9_-]{20,}\b""")

    fun scrub(text: String): String {
        var s = controls.replace(text, " ")
        s = jsonSlash.replace(s, "/")
        s = url.replace(s, "<url>")
        s = querySecret.replace(s) { "${it.groupValues[1]}…" }
        s = secretHeader.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}…" }
        s = bearer.replace(s) { "${it.groupValues[1]} …" }
        s = phonePath.replace(s, "<path>")
        s = email.replace(s, "<email>")
        s = plexDirect.replace(s, "<host>")
        s = hostPort.replace(s, "<host>")
        s = localHost.replace(s, "<host>")
        s = ipv4.replace(s, "<ip>")
        s = mac.replace(s, "<mac>")
        s = ipv6Candidate.replace(s) { if (isIpv6(it.value)) "<ip>" else it.value }
        s = uuid.replace(s, "<id>")
        s = longHex.replace(s, "<id>")
        s = token.replace(s, "<token>")
        return if (s.length > MAX_CHARS) s.take(MAX_CHARS - 1) + "…" else s
    }

    private fun isIpv6(candidate: String): Boolean {
        val address = candidate.substringBefore('%')
        if (!address.any { it.isLetterOrDigit() }) return false
        return "::" in address || address.count { it == ':' } >= 5
    }
}

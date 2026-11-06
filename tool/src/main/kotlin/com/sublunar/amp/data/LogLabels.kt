package com.sublunar.amp.data

import com.sublunar.amp.source.ServerAddress
import java.net.URI

/**
 * A server address as a log line may name it: where it leads (lan, wan or
 * loopback) and how (http or https), never the host. Judged by its spelling,
 * as ServerAddress judges one; a plex.direct name spells its address in its
 * first label.
 */
internal fun routeOf(url: String?): String {
    val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return "unknown"
    val host = uri.host?.lowercase() ?: return "unknown"
    val spelled = if (host.endsWith(".plex.direct")) host.substringBefore('.').replace('-', '.') else host
    val where = when {
        spelled == "localhost" || spelled.startsWith("127.") || spelled.removeSurrounding("[", "]") == "::1" -> "loopback"
        ServerAddress.isLocal(spelled) -> "lan"
        else -> "wan"
    }
    return "$where ${uri.scheme?.lowercase() ?: "unknown"}"
}

/** [routeOf], with the address's rank among [ranked] (best first), for a server that keeps several. */
internal fun routeOf(url: String, ranked: List<String>): String =
    routeOf(url) + ranked.indexOf(url).let { if (it >= 0) " #$it" else "" }

/**
 * What a request to a music server was for, as a log line may name it:
 * stream, download, universal, part, transcode or cover. Never the path
 * itself, which holds ids, and can hold a reverse proxy's secret prefix.
 */
internal fun endpointKind(path: String?): String {
    val p = path?.lowercase() ?: return "unknown"
    val last = p.trimEnd('/').substringAfterLast('/').substringBefore('.')
    return when {
        "/transcode/" in p -> "transcode"
        "/library/parts/" in p -> "part"
        last == "universal" -> "universal"
        last == "download" -> "download"
        last == "stream" -> "stream"
        last == "getcoverart" || "/images/" in p || "/thumb" in p -> "cover"
        else -> "other"
    }
}

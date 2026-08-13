package com.sublunar.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * Linking a Plex account, and finding the servers on it.
 *
 * The PIN flow rather than a password: the app asks plex.tv for a four-character
 * code, shows it, and the user types it at **plex.tv/link** on whatever device
 * they already have to hand. Nothing is typed on the phone, which on this
 * keyboard is the difference between a minute and a fight — and the app never
 * sees a password.
 *
 * The token that comes back is also what lists the account's servers, so the
 * user never types a server address either: [resources] returns each one with
 * its local, remote and relay URIs, and [reachable] picks the first that
 * answers.
 */
object PlexAccount {

    private const val PLEX_TV = "https://plex.tv"

    private val http = HttpClient(OkHttp) { expectSuccess = false }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * Ask for a code. Null when plex.tv can't be reached.
     *
     * Deliberately *not* `strong=true`: that returns a long random string, which
     * is right for a machine-to-machine link and wrong here. The short code is
     * the one plex.tv/link accepts, and the one a person can read off a phone
     * and type somewhere else.
     */
    suspend fun requestPin(): PlexPin? = runCatching {
        val response = http.post("$PLEX_TV/api/v2/pins") { plexHeaders() }
        json.decodeFromString<PlexPin>(response.bodyAsText())
    }.getOrNull()

    /**
     * Poll until the code is claimed, or give up.
     *
     * Plex expires a PIN after fifteen minutes; this stops well short of that,
     * because a code left on screen that long has been abandoned.
     */
    suspend fun awaitToken(pin: PlexPin, onTick: (Int) -> Unit = {}): String? {
        repeat(POLL_ATTEMPTS) { attempt ->
            delay(POLL_INTERVAL_MS)
            onTick(attempt)
            val token = runCatching {
                val response = http.get("$PLEX_TV/api/v2/pins/${pin.id}") { plexHeaders() }
                json.decodeFromString<PlexPin>(response.bodyAsText()).authToken
            }.getOrNull()
            if (!token.isNullOrBlank()) return token
        }
        return null
    }

    /** Every server on the account that provides a library. */
    suspend fun resources(token: String): List<PlexResource> = runCatching {
        val response = http.get("$PLEX_TV/api/v2/resources?includeHttps=1&includeRelay=1") {
            plexHeaders()
            header("X-Plex-Token", token)
        }
        json.decodeFromString<List<PlexResource>>(response.bodyAsText())
            .filter { it.provides.contains("server") }
    }.getOrDefault(emptyList())

    /**
     * The first connection that answers, local ones first.
     *
     * A server usually advertises several addresses — a LAN one, a public one
     * and a relay. The LAN address is the fastest when you're at home and dead
     * when you're not, so they're tried in that order rather than guessed at.
     */
    /** Every advertised address, best first — local, then direct, then relay. */
    fun candidates(resource: PlexResource): List<String> =
        resource.connections
            .sortedWith(compareBy({ !it.local }, { it.relay }))
            .map { it.uri.trimEnd('/') }
            .distinct()

    /**
     * The first advertised address that answers.
     *
     * Local first because it is faster and avoids Plex's relay — but see
     * [MusicSource.connections]: the rest are kept so this can be redone when
     * the network changes underneath a source.
     */
    suspend fun reachable(resource: PlexResource, token: String): String? {
        val ordered = resource.connections.sortedWith(
            compareBy({ !it.local }, { it.relay }),
        )
        for (connection in ordered) {
            val ok = runCatching {
                val response = http.get(connection.uri.trimEnd('/') + "/identity") {
                    plexHeaders()
                    header("X-Plex-Token", resource.accessToken ?: token)
                }
                response.status.value in 200..299
            }.getOrDefault(false)
            if (ok) return connection.uri.trimEnd('/')
        }
        return null
    }

    /** The first of [uris] that answers, for re-resolving a stored source. */
    suspend fun firstReachable(uris: List<String>, token: String): String? {
        for (uri in uris) {
            val ok = runCatching {
                http.get(uri.trimEnd('/') + "/identity") {
                    plexHeaders()
                    header("X-Plex-Token", token)
                }.status.value in 200..299
            }.getOrDefault(false)
            if (ok) return uri.trimEnd('/')
        }
        return null
    }

    private fun io.ktor.client.request.HttpRequestBuilder.plexHeaders() {
        header("Accept", "application/json")
        PlexClient.plexIdentity().forEach { (k, v) -> header(k, v) }
    }

    private const val POLL_INTERVAL_MS = 2_000L
    private const val POLL_ATTEMPTS = 90
}

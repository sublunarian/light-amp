package com.sublunar.amp.source.plex

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.sublunar.amp.data.NetworkGate
import com.sublunar.amp.source.serverJson

/**
 * Linking a Plex account, and finding the servers on it.
 *
 * The PIN flow rather than a password: plex.tv hands out a four-character
 * code, the user types it at plex.tv/link on any other device, and the token
 * that comes back lists the account's servers with every address they
 * advertise. Neither a password nor a server address is typed on the phone.
 */
object PlexAccount {

    private const val PLEX_TV = "https://plex.tv"

    private val http = NetworkGate.httpClient { expectSuccess = false }

    /**
     * Ask for a code. Null when plex.tv can't be reached.
     *
     * Not `strong=true`: that returns a long random string, and plex.tv/link
     * accepts only the short code a person can read off a phone.
     *
     * [clientIdentifier] is the link's own (see [PlexClient.newClientIdentifier]).
     * The token is issued to it, and plex.tv only answers [awaitToken] for a
     * code asked for under the same one.
     */
    suspend fun requestPin(clientIdentifier: String): PlexPin? = runCatching {
        val response = http.post("$PLEX_TV/api/v2/pins") { plexHeaders(clientIdentifier) }
        serverJson.decodeFromString<PlexPin>(response.bodyAsText())
    }.getOrNull()

    /**
     * Poll until the code is claimed, or give up. Plex expires a PIN after
     * fifteen minutes, and this stops well short of that.
     */
    suspend fun awaitToken(pin: PlexPin, clientIdentifier: String): String? {
        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val token = runCatching {
                val response = http.get("$PLEX_TV/api/v2/pins/${pin.id}") { plexHeaders(clientIdentifier) }
                serverJson.decodeFromString<PlexPin>(response.bodyAsText()).authToken
            }.getOrNull()
            if (!token.isNullOrBlank()) return token
        }
        return null
    }

    /** Every server on the account that provides a library. */
    suspend fun resources(token: String, clientIdentifier: String): List<PlexResource> = runCatching {
        val response = http.get("$PLEX_TV/api/v2/resources?includeHttps=1&includeRelay=1") {
            plexHeaders(clientIdentifier)
            header("X-Plex-Token", token)
        }
        serverJson.decodeFromString<List<PlexResource>>(response.bodyAsText())
            .filter { it.provides.contains("server") }
    }.getOrDefault(emptyList())

    /**
     * Every advertised address, best first: local, then direct, then relay.
     * All are kept on the source (see MusicSource.connections) so the choice
     * can be redone when the network changes underneath it.
     */
    fun candidates(resource: PlexResource): List<String> =
        resource.connections
            .sortedWith(compareBy({ !it.local }, { it.relay }))
            .map { it.uri.trimEnd('/') }
            .distinct()

    /** The best-ranked of [resource]'s addresses that answers. */
    suspend fun reachable(resource: PlexResource, token: String, clientIdentifier: String): String? =
        firstReachable(candidates(resource), resource.accessToken ?: token, clientIdentifier)

    /**
     * The best-ranked of [uris] that answers.
     *
     * From any one place some of a server's addresses lead nowhere, and tried
     * in turn each of those has to time out before the next is asked. So all
     * are probed at once with a short limit, the ranking still deciding which
     * wins, and only when none answers that fast (a slow link) do they get a
     * patient pass in turn.
     */
    suspend fun firstReachable(uris: List<String>, token: String, clientIdentifier: String): String? {
        val quick = coroutineScope {
            val probes = uris.map { uri ->
                async { withTimeoutOrNull(QUICK_PROBE_MS) { answers(uri, token, clientIdentifier) } == true }
            }
            val winner = uris.indices.firstOrNull { probes[it].await() }
            probes.forEach { it.cancel() }
            winner
        }
        if (quick != null) return uris[quick].trimEnd('/')
        return uris.firstOrNull { answers(it, token, clientIdentifier) }?.trimEnd('/')
    }

    private suspend fun answers(uri: String, token: String, clientIdentifier: String): Boolean = runCatching {
        http.get(uri.trimEnd('/') + "/identity") {
            plexHeaders(clientIdentifier)
            header("X-Plex-Token", token)
        }.status.isSuccess()
    }.getOrDefault(false)

    private fun io.ktor.client.request.HttpRequestBuilder.plexHeaders(clientIdentifier: String) {
        header("Accept", "application/json")
        PlexClient.plexIdentity(clientIdentifier = clientIdentifier).forEach { (k, v) -> header(k, v) }
    }

    private const val POLL_INTERVAL_MS = 2_000L
    /** Long enough for a relay to answer; short enough that a dead LAN address isn't waited out. */
    private const val QUICK_PROBE_MS = 3_000L
    private const val POLL_ATTEMPTS = 90
}

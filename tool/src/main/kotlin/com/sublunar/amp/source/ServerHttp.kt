package com.sublunar.amp.source

import com.sublunar.amp.data.NetworkGate
import io.ktor.client.HttpClient
import java.net.URLEncoder
import kotlinx.serialization.json.Json

/**
 * The two HTTP clients a server's client talks through, both behind
 * NetworkGate's wall, and both leaving a failed status for the caller to read.
 *
 * [quick] is for the calls a person is waiting on — the reachability ping and
 * the stream decision — with a request queue of its own: behind a launch
 * sync's hundreds of requests on [bulk], a ping that waited its turn read as
 * the server gone. Built on first use.
 */
class ServerHttp {
    val bulk: HttpClient = NetworkGate.httpClient { expectSuccess = false }

    private val quickClient = lazy { NetworkGate.httpClient(isolated = true) { expectSuccess = false } }
    val quick: HttpClient get() = quickClient.value

    fun close() {
        bulk.close()
        if (quickClient.isInitialized()) quick.close()
    }
}

/**
 * How a server's JSON is read: fields this app doesn't know are skipped, a
 * null where a default stands takes the default, and loose JSON still reads.
 */
internal val serverJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

/** [path] on the server at [base], with [params] as its query. */
internal fun serverUrl(base: String, path: String, params: List<Pair<String, String>> = emptyList()): String {
    val query = queryOf(params)
    return base.trimEnd('/') + path + if (query.isEmpty()) "" else "?$query"
}

/** [params] as a query string, each value percent-encoded. */
internal fun queryOf(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (key, value) -> "$key=${encoded(value)}" }

internal fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")

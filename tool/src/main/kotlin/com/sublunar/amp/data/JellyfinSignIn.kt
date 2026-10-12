package com.sublunar.amp.data

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A signed-in Jellyfin session, and a client already holding it. */
data class JellyfinSession(
    val client: JellyfinClient,
    val token: String,
    val userId: String,
    val serverName: String,
    /** Where the server was found, which a redirect may have moved from the address given — http:// to https://, say. */
    val baseUrl: String,
)

/** How far a sign-in got, so the form can say which half was wrong — the address or the account. */
sealed interface JellyfinSignInResult {
    data class SignedIn(val session: JellyfinSession) : JellyfinSignInResult

    /** Nothing answered: the name, the network, the certificate, the port. */
    data class Unreached(val error: Throwable) : JellyfinSignInResult

    /** Something answered, but not a Jellyfin server — a proxy's page, another app on that port. */
    data object NotJellyfin : JellyfinSignInResult

    /** Something answered for the server but couldn't serve: a gateway with nothing behind it, or Jellyfin still starting. */
    data class Unavailable(val status: Int) : JellyfinSignInResult

    /** Jellyfin, found at [baseUrl], answered and turned the username and password down. */
    data class Refused(val baseUrl: String) : JellyfinSignInResult

    /** Jellyfin, found at [baseUrl], answered, then the sign-in itself failed: [status] when it gave one, null when it went quiet. */
    data class Failed(val status: Int?, val baseUrl: String) : JellyfinSignInResult
}

/**
 * Signing in to a Jellyfin server, which is the one thing [JellyfinClient]
 * cannot do for itself: it needs a token to make any request, and getting one
 * is the request that has no token.
 */
object JellyfinSignIn {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * Exchange an account for an access token.
     *
     * The server's public info is asked first. Jellyfin answers it to anyone,
     * so it settles whether the address is right before the account is in
     * question — and it carries the server's name for the Sources page.
     *
     * The identifying header goes on the sign-in too — Jellyfin refuses the
     * login without one, because the session it is about to create has to be
     * attributed to some client.
     */
    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
        product: String? = null,
        /** Who the phone signs in as — a fresh [JellyfinClient.newDeviceId] unless re-signing a source in. */
        deviceId: String,
    ): JellyfinSignInResult {
        val http = NetworkGate.httpClient { expectSuccess = false }
        try {
            var root = baseUrl.trimEnd('/')
            val info = try {
                val response = http.get("$root$PUBLIC_INFO") { header("Accept", "application/json") }
                val status = response.status.value
                if (status in 500..599) return JellyfinSignInResult.Unavailable(status)
                if (!response.status.isSuccess()) return JellyfinSignInResult.NotJellyfin
                // The client follows a redirect for this GET but not for the
                // sign-in's POST, so the sign-in goes where this one landed —
                // or a server that moves http:// to https:// would be found
                // here and then fail to sign in at the address it moved from.
                // Only on the same host: the password goes nowhere the user
                // didn't name.
                val landed = response.call.request.url
                if (landed.host.equals(Url(root).host, ignoreCase = true)) {
                    landed.toString().substringBefore('?')
                        .takeIf { it.endsWith(PUBLIC_INFO) }
                        ?.let { root = it.removeSuffix(PUBLIC_INFO) }
                }
                runCatching { json.decodeFromString<JellyfinPublicInfo>(response.bodyAsText()) }.getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return JellyfinSignInResult.Unreached(e)
            }
            if (info == null || info.id.isBlank()) return JellyfinSignInResult.NotJellyfin

            val payload: JsonObject = buildJsonObject {
                put("Username", username)
                // Jellyfin's field is "Pw"; "Password" is the old SHA-1 one and
                // a server reading that finds an empty password and refuses.
                put("Pw", password)
            }
            val result = try {
                val response = http.post("$root/Users/AuthenticateByName") {
                    header("Authorization", JellyfinClient.authorization(product, deviceId = deviceId))
                    header("Accept", "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(JsonObject.serializer(), payload))
                }
                when (val status = response.status.value) {
                    401, 403 -> return JellyfinSignInResult.Refused(root)
                    !in 200..299 -> return JellyfinSignInResult.Failed(status, root)
                }
                runCatching { json.decodeFromString<JellyfinAuthResult>(response.bodyAsText()) }.getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result == null || result.accessToken.isBlank() || result.user.id.isBlank()) {
                return JellyfinSignInResult.Failed(null, root)
            }

            return JellyfinSignInResult.SignedIn(
                JellyfinSession(
                    client = JellyfinClient(root, result.accessToken, result.user.id, product, deviceId),
                    token = result.accessToken,
                    userId = result.user.id,
                    serverName = info.serverName,
                    baseUrl = root,
                ),
            )
        } finally {
            http.close()
        }
    }

    private const val PUBLIC_INFO = "/System/Info/Public"
}

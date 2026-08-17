package com.sublunar.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
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
)

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
     * The identifying header goes on unauthenticated too — Jellyfin refuses the
     * login itself without one, because the session it is about to create has
     * to be attributed to some client. Null on anything that isn't a clean
     * sign-in: a wrong password, an address with no Jellyfin behind it, a server
     * that isn't answering.
     */
    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
        product: String? = null,
    ): JellyfinSession? {
        val http = HttpClient(OkHttp) { expectSuccess = false }
        try {
            val root = baseUrl.trimEnd('/')
            val payload: JsonObject = buildJsonObject {
                put("Username", username)
                // Jellyfin's field is "Pw"; "Password" is the old SHA-1 one and
                // a server reading that finds an empty password and refuses.
                put("Pw", password)
            }
            val response = http.post("$root/Users/AuthenticateByName") {
                header("Authorization", JellyfinClient.authorization(product))
                header("Accept", "application/json")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(JsonObject.serializer(), payload))
            }
            if (response.status.value !in 200..299) return null
            val result = runCatching {
                json.decodeFromString<JellyfinAuthResult>(response.bodyAsText())
            }.getOrNull() ?: return null
            if (result.accessToken.isBlank() || result.user.id.isBlank()) return null

            // The server's own name, for the Sources page. Public, so a failure
            // here is cosmetic and must not cost an otherwise good sign-in.
            val serverName = runCatching {
                val info = http.get("$root/System/Info/Public") { header("Accept", "application/json") }
                json.decodeFromString<JellyfinPublicInfo>(info.bodyAsText()).serverName
            }.getOrDefault("")

            return JellyfinSession(
                client = JellyfinClient(root, result.accessToken, result.user.id, product),
                token = result.accessToken,
                userId = result.user.id,
                serverName = serverName,
            )
        } catch (_: Exception) {
            return null
        } finally {
            http.close()
        }
    }
}

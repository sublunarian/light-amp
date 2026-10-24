package com.sublunar.amp.source.jellyfin

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.NetworkGate
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.SourceLibrary
import com.sublunar.amp.data.newSourceId
import com.sublunar.amp.source.PendingServer
import com.sublunar.amp.source.SignInOutcome
import com.sublunar.amp.source.serverJson
import com.sublunar.amp.source.unavailableReason
import com.sublunar.amp.source.unreachedReason
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Signing in to a Jellyfin server, which is the one thing [JellyfinClient]
 * cannot do for itself: it needs a token to make any request, and getting one
 * is the request that has no token.
 */
object JellyfinSignIn {

    /**
     * Everything about a Jellyfin server that has to be settled before it
     * becomes a source: that it answers, who we are on it, what it calls
     * itself, and what music libraries that account can see. Better found here
     * than by a sync that fails after the source is saved, and said in a way
     * that tells a wrong address from a wrong account.
     *
     * The server's public info is asked first. Jellyfin answers it to anyone,
     * so it settles whether the address is right before the account is in
     * question — and it carries the server's name for the Sources page.
     *
     * The identifying header goes on the sign-in too — Jellyfin refuses the
     * login without one, because the session it is about to create has to be
     * attributed to some client.
     */
    suspend fun signIn(
        uri: String,
        username: String,
        password: String,
        /** Who the phone signs in as — a fresh [JellyfinClient.newDeviceId] unless re-signing a source in. */
        deviceId: String,
    ): SignInOutcome {
        val http = NetworkGate.httpClient { expectSuccess = false }
        try {
            var root = uri.trimEnd('/')
            val info = try {
                val response = http.get("$root$PUBLIC_INFO") { header("Accept", "application/json") }
                val status = response.status.value
                if (status in 500..599) return SignInOutcome.Missed(unavailableReason(status), answered = true)
                if (!response.status.isSuccess()) return notJellyfin
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
                runCatching { serverJson.decodeFromString<JellyfinPublicInfo>(response.bodyAsText()) }.getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SignInOutcome.Missed(unreachedReason(e), answered = false)
            }
            if (info == null || info.id.isBlank()) return notJellyfin

            val payload: JsonObject = buildJsonObject {
                put("Username", username)
                // Jellyfin's field is "Pw"; "Password" is the old SHA-1 one and
                // a server reading that finds an empty password and refuses.
                put("Pw", password)
            }
            val result = try {
                val response = http.post("$root/Users/AuthenticateByName") {
                    header("Authorization", JellyfinClient.authorization(deviceId = deviceId))
                    header("Accept", "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(serverJson.encodeToString(JsonObject.serializer(), payload))
                }
                when (val status = response.status.value) {
                    401, 403 -> return SignInOutcome.Settled(root, "Jellyfin didn't accept that username and password.")
                    !in 200..299 -> return SignInOutcome.Settled(root, signInFailed(status))
                }
                runCatching { serverJson.decodeFromString<JellyfinAuthResult>(response.bodyAsText()) }.getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result == null || result.accessToken.isBlank() || result.user.id.isBlank()) {
                return SignInOutcome.Settled(root, signInFailed(null))
            }
            val client = JellyfinClient(root, result.accessToken, result.user.id, deviceId = deviceId)
            try {
                val sections = runCatching { client.getMusicFolders() }.getOrNull()
                    ?: return SignInOutcome.Settled(root, "Signed in, but the server didn't list its libraries. Try again.")
                val source = MusicSource(
                    id = newSourceId(),
                    kind = SourceKind.JELLYFIN,
                    // The server's own name where it gave one, so the Sources page
                    // reads as the server rather than as its IP address.
                    name = info.serverName.ifBlank { MusicSource.nameFor(root) },
                    baseUrl = root,
                    username = username,
                    token = result.accessToken,
                    userId = result.user.id,
                    deviceId = deviceId,
                    // Remembered on the source so the Sources page can offer the same
                    // switch a Navidrome's music folders get, without waiting for a
                    // sync to discover what we already know here.
                    libraries = sections.map { SourceLibrary(it.id, it.name) },
                )
                return SignInOutcome.Found(PendingServer(source, sections))
            } finally {
                client.close()
            }
        } finally {
            http.close()
        }
    }

    /** Something answered, but not a Jellyfin server — a proxy's page, another app on that port. */
    private val notJellyfin = SignInOutcome.Missed(
        "That address answered, but it isn't a Jellyfin server. Check the address and port.",
        answered = true,
    )

    /** Jellyfin answered, then the sign-in itself failed: [status] when it gave one, null when it went quiet. */
    private fun signInFailed(status: Int?): String = when (status) {
        null -> "The sign-in didn't finish. Try again."
        // Followed for the address check but not for the sign-in: a redirect to
        // another host, which isn't followed with a password.
        in 300..399 -> "That address sends the sign-in somewhere else. Type the address it leads to."
        else -> "Jellyfin couldn't sign that account in (error $status). Try again."
    }

    private const val PUBLIC_INFO = "/System/Info/Public"
}

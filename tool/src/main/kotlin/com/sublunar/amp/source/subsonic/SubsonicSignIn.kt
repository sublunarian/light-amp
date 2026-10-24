package com.sublunar.amp.source.subsonic

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.SourceLibrary
import com.sublunar.amp.data.newSourceId
import com.sublunar.amp.source.PendingServer
import com.sublunar.amp.source.SignInOutcome
import com.sublunar.amp.source.causes
import com.sublunar.amp.source.unreachedReason
import kotlinx.serialization.SerializationException

/**
 * Whether [uri] is a Subsonic server that takes this login, and if so a new
 * source for it named [name], with the libraries the login check read.
 *
 * The ping settles the address and a read that needs a login settles the
 * account: Bandcamp answers a ping "ok" whatever login is sent. Only an answer
 * about the account refuses it; a hiccup is reported as one, so a right
 * password is never blamed.
 */
internal suspend fun inspectSubsonicServer(
    uri: String,
    username: String,
    password: String,
    name: String,
): SignInOutcome {
    val client = SubsonicClient(SubsonicConfig(uri, username, password))
    try {
        runCatching { client.ping() }.exceptionOrNull()?.let { error ->
            // A ping refused in Subsonic's own words has found the server, and
            // that is the answer: trying http:// next could only bury it under
            // a connection error.
            val subsonic = error.subsonicAnswer()
            return if (subsonic != null && (subsonic.code != null || subsonic.envelope)) {
                SignInOutcome.Settled(uri, explain(error))
            } else {
                SignInOutcome.Missed(explain(error), answered = false)
            }
        }
        val folders = runCatching { client.getMusicFolders() }
        val refused = folders.exceptionOrNull()
        when (loginVerdict(refused)) {
            LoginVerdict.ACCEPTED, LoginVerdict.UNCHECKABLE -> Unit
            LoginVerdict.REFUSED -> return SignInOutcome.Settled(uri, refusedLogin(refused!!))
            LoginVerdict.UNREACHED -> return SignInOutcome.Settled(
                uri,
                "That address answered, then stopped answering before the account could be checked. Try Save again.",
            )
        }
        val sections = folders.getOrNull().orEmpty()
        val source = MusicSource(
            id = newSourceId(),
            kind = SourceKind.SUBSONIC,
            name = name,
            baseUrl = uri,
            username = username,
            password = password,
            // With two or more, the user picks which to show before the first
            // sync — see finishAddingSource.
            libraries = sections.map { SourceLibrary(it.id, it.name) },
        )
        return SignInOutcome.Found(PendingServer(source, sections))
    } finally {
        client.close()
    }
}

/** What the folders call said about the account, once the ping has settled the address. */
internal enum class LoginVerdict { ACCEPTED, REFUSED, UNCHECKABLE, UNREACHED }

/**
 * Whether a failed folders call is the account being refused or something
 * else getting in the way.
 *
 * Refused: a Subsonic error from the server itself (it carries a code), or
 * HTTP 401, 403 or 500 — a bare 500 being how Bandcamp says "not this login".
 * Uncheckable: the server has no such call, or answered in a shape this client
 * can't read (it answered, so the account got in). Everything else — a
 * timeout, a 502, a 500 that is a gateway's page rather than the server's own,
 * a web page where JSON should be — is the network or something in front of
 * the server, and says nothing about the password.
 */
internal fun loginVerdict(error: Throwable?): LoginVerdict {
    if (error == null) return LoginVerdict.ACCEPTED
    val server = error.subsonicAnswer() ?: return LoginVerdict.UNREACHED
    return when {
        server.endpointMissing -> LoginVerdict.UNCHECKABLE
        server.code != null -> LoginVerdict.REFUSED
        server.envelope -> LoginVerdict.UNCHECKABLE
        server.status == 401 || server.status == 403 -> LoginVerdict.REFUSED
        server.status == 500 && !server.notJson -> LoginVerdict.REFUSED
        else -> LoginVerdict.UNREACHED
    }
}

/**
 * The address answered and the account did not.
 *
 * A server that says why — Subsonic's own "Wrong username or password" — is
 * quoted. One that doesn't, and Bandcamp doesn't (a bare HTTP 500), gets a
 * sentence that claims only what is known: the address is fine and the login
 * is what was refused. Naming the password outright would be a guess.
 */
internal fun refusedLogin(error: Throwable): String {
    val said = error.subsonicAnswer()?.takeIf { it.code != null }?.message
        ?.takeIf { it.isNotBlank() && it != SUBSONIC_REQUEST_FAILED }
    return said ?: "That address answered, but not for this account. Check the username and password."
}

/**
 * What went wrong, in words that point at a fix. A raw parse error is true and
 * useless; the usual cause is a reverse proxy serving its own page on HTTPS
 * while the server sits behind plain HTTP.
 */
internal fun explain(error: Throwable?): String {
    if (error == null) return "Couldn't connect."
    val chain = error.causes().toList()
    // Before the pass-through below, whose "Unexpected response from server."
    // would hide the one problem the user can fix. Only an answer that wasn't
    // JSON at all: JSON in another shape is the server talking, at an address
    // that is fine. A gateway's error page (502, 503, 504) keeps its HTTP
    // message for the same reason.
    val page = chain.filterIsInstance<SubsonicException>()
        .any { it.notJson && (it.status == null || it.status == 404) } ||
        (chain.none { it is SubsonicException } && chain.any { it is SerializationException })
    if (page) {
        return "That address answered with a web page, not a music server. Check the " +
            "address. If the server uses plain HTTP, type http:// in front of it."
    }
    // Already a sentence meant for a person, such as "Wrong username or password".
    error.subsonicAnswer()?.let {
        return it.message ?: "Couldn't connect."
    }
    return unreachedReason(error)
}

/** The Subsonic answer inside this exception, however deep the HTTP client wrapped it. */
private fun Throwable.subsonicAnswer(): SubsonicException? =
    causes().filterIsInstance<SubsonicException>().firstOrNull()

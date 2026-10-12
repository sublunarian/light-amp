package com.sublunar.amp.ui.screens

import com.sublunar.amp.data.ServerAddress
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/** What came of trying a server address on a sign-in form. */
sealed interface SignInOutcome {
    /** The server, signed in, with its music libraries. */
    data class Found(val server: PendingServer) : SignInOutcome

    /**
     * The server answered at [uri] and that settled it — the account was turned
     * down, say. No other spelling of the address is tried after this one.
     */
    data class Settled(val uri: String, val reason: String) : SignInOutcome

    /** Nothing at this spelling turned out to be the server; another spelling may yet. */
    data class Missed(val reason: String, val answered: Boolean) : SignInOutcome
}

/**
 * Try each spelling of a typed address — see [ServerAddress] — until one of
 * them is the server.
 *
 * Found and Settled end the search: the server has answered, and trying
 * http:// after it could only bury that answer under a connection error from a
 * spelling that was never right. When every spelling misses, the reason
 * reported is the one that got furthest — something answering beats nothing
 * answering — and, between equals, the last.
 */
suspend fun firstAnsweringSpelling(
    typed: String,
    attempt: suspend (uri: String) -> SignInOutcome,
): SignInOutcome {
    val spellings = ServerAddress.candidates(typed)
    var best: SignInOutcome.Missed? = null
    for (uri in spellings) {
        when (val outcome = attempt(uri)) {
            is SignInOutcome.Missed -> if (best == null || outcome.answered || !best.answered) best = outcome
            else -> return outcome
        }
    }
    return best!!
}

/** Something answered for the server but couldn't serve: a gateway with nothing behind it, or a server still starting. */
fun unavailableReason(status: Int): String =
    "That address answered, but the server behind it isn't available (error $status). " +
        "Check it's running, then try again."

/**
 * Why nothing answered, in words that point at a fix.
 *
 * Causes are walked because Ktor wraps: the interesting exception is rarely
 * the outermost one.
 */
fun unreachedReason(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    return when {
        chain.any { it is UnknownHostException } ->
            "Couldn't find that address. Check the spelling, and that this phone is " +
                "on the same network."
        chain.any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException } ->
            "The HTTPS certificate wasn't accepted. A self-signed certificate needs " +
                "http:// instead."
        // The rest is a port that doesn't speak https:// at all — a plain
        // HTTP server answering the handshake with text — not a bad certificate.
        chain.any { it is SSLException } ->
            "That address didn't answer over https://. If the server uses plain HTTP, " +
                "type http:// in front of it."
        chain.any { it is ConnectException || it is SocketTimeoutException } ->
            "Nothing answered at that address. Check the server is running and the " +
                "port is right."
        else -> error.message ?: "Couldn't connect."
    }
}

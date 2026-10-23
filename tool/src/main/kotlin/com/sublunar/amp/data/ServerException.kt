package com.sublunar.amp.data

/**
 * A failure a server answered with, whichever protocol it speaks.
 *
 * One base so the readers of a failure — [writeResultOf], [syncErrorMessage],
 * [answeredBy] — can ask for what they need without naming every kind of
 * server. A protocol with words of its own for a failure says so by
 * overriding, and one without leaves the defaults and lets the status decide.
 *
 * [status] is the HTTP status when the server gave one — see Reachability,
 * which reads a 5xx as "not there".
 */
abstract class ServerException(
    message: String,
    val status: Int?,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** What this protocol's own error says a failed write was, read before [status]. Null leaves it to the status. */
    open fun writeResult(): WriteResult? = null

    /** A refusal's words when there is no status, and the server's own message is missing or too long to show. */
    open val refusalWords: String get() = "Couldn't save"

    /**
     * Whether the server turned the login away in its protocol's own words
     * rather than with a 401 or 403 — see isAuthFailure, for which the
     * difference is the library staying as it is.
     */
    open val refusedLogin: Boolean get() = false

    /**
     * Whether the server itself said this, rather than something in front of
     * it answering in its place — a captive portal's login page, a proxy's
     * error page — where the server's own answer should have been.
     */
    open val fromServer: Boolean get() = true
}

/**
 * What came of a write: a like, a rating, a playlist edit, a play.
 *
 * Two kinds of failure, because they want opposite things done. A server that
 * never answered may well take the same write later, so it is kept and sent
 * again; a server that answered no will say no again, and a write kept for it
 * only stands in the way of everything queued behind it.
 */
sealed interface WriteResult {
    data object Saved : WriteResult

    /** Never answered: worth sending again later. */
    data class Unreachable(val reason: String) : WriteResult

    /** The server answered no: sending it again gets the same answer. */
    data class Refused(val reason: String) : WriteResult
}

/** Refused before anything was sent — no call for it on this server, or nothing to aim it at. */
class RefusedWrite(reason: String = CANT_DO_THAT) : Exception(reason)

internal const val UNREACHABLE = "Couldn't reach the server"
internal const val CANT_DO_THAT = "This server can't do that"
internal const val NO_SUCH_ARTIST = "This artist isn't on the server"

/** A server's own words are shown when they fit on a line; past that they are a log entry. */
private const val SHOWN_MESSAGE_MAX = 60

/**
 * Which of the two a failed write was — see [WriteResult].
 *
 * The same split [Reachability] draws between a server that answered and one
 * that didn't, with one difference: a write that can never land must not count
 * as one that might, so anything not recognised is a refusal. Read as "try
 * again later", a bug that always throws would hold the offline queue shut for
 * good.
 *
 * A 401 is deliberately not called a login problem: Jellyfin answers 401 to a
 * delete the account isn't allowed to make, from a login that works.
 */
internal fun writeResultOf(e: Exception): WriteResult {
    if (e is RefusedWrite) return WriteResult.Refused(e.message ?: CANT_DO_THAT)
    if (e is ServerException) e.writeResult()?.let { return it }
    if (e is java.io.IOException || e is kotlinx.serialization.SerializationException) {
        return WriteResult.Unreachable(UNREACHABLE)
    }
    val status = (e as? ServerException)?.status
    serverDownWords(status)?.let { return WriteResult.Unreachable(it) }
    return when (status) {
        401, 403 -> WriteResult.Refused("The server doesn't allow that")
        404 -> WriteResult.Refused("The server doesn't have that")
        in 400..499 -> WriteResult.Refused("The server said $status")
        else -> WriteResult.Refused(
            e.message?.takeIf { it.isNotBlank() && it.length < SHOWN_MESSAGE_MAX }
                ?: (e as? ServerException)?.refusalWords ?: "Couldn't save",
        )
    }
}

/** Runs one write and says how it went. Cancellation is not a failure, and goes straight through. */
internal suspend fun attemptWrite(block: suspend () -> Unit): WriteResult =
    try {
        block()
        WriteResult.Saved
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        writeResultOf(e)
    }

/**
 * A 5xx in words that say the server is down, not the app or the login — for
 * a failed write and a failed sync alike. Null for any other status.
 */
internal fun serverDownWords(status: Int?): String? = when (status) {
    // Cloudflare's own codes: it answered, the server behind it didn't.
    in 520..529 -> "Your server isn't responding ($status)"
    in 500..599 -> "The server had an error ($status)"
    else -> null
}

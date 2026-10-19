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
}

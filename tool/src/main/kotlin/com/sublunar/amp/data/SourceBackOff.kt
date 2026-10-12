package com.sublunar.amp.data

import java.util.concurrent.ConcurrentHashMap

/**
 * When to try each source again after its server stopped answering — not
 * *whether* to.
 *
 * This used to be a boolean latched on the first failed transfer and
 * cleared only by a successful library sync. A single blip therefore parked
 * downloads indefinitely: the queue sat on "Waiting for the server" on
 * perfectly good wifi, and nothing about downloading could get it going
 * again, because only syncing could clear the flag. A deadline retries by
 * itself and needs nobody's permission.
 *
 * Per source, because a server that is down is one server: its tracks wait,
 * everyone else's are picked instead — see Downloader.eligible.
 *
 * What it does say, after [PARK_AFTER_FAILURES] failures in a row and never
 * on one, is that trying has stopped being worth it — and the Downloader
 * stops, until something says the server is back: see Downloader.parkings.
 *
 * The time is always passed in, never read here, so the tests can be the
 * clock.
 */
class SourceBackOff {
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Int>()

    fun waiting(sourceId: String, now: Long): Boolean = now < (retryAt[sourceId] ?: 0L)

    /** A failed try. True when the source has now failed long enough to park — see [PARK_AFTER_FAILURES]. */
    fun fail(sourceId: String, now: Long): Boolean {
        val count = (failures[sourceId] ?: 0) + 1
        failures[sourceId] = count
        retryAt[sourceId] = now + waitAfter(count)
        return count >= PARK_AFTER_FAILURES
    }

    /** A transfer that worked proves the server is there, whatever else said. */
    fun clear(sourceId: String) {
        failures.remove(sourceId)
        retryAt.remove(sourceId)
    }

    fun clearAll() {
        failures.clear()
        retryAt.clear()
    }

    companion object {
        /** First wait after a failed transfer; doubles up to [MAX_MS]. */
        const val BASE_MS = 2_000L
        const val MAX_MS = 60_000L

        /**
         * Ten tries at the cap: at least 602 s after the first failure, the
         * same ten minutes a parked player waits before leaving it to
         * something else to wake it. A server restarting is back well
         * inside that.
         */
        const val PARK_AFTER_FAILURES = 15

        /** Backs off to a minute, so a server that is really down isn't hammered. */
        fun waitAfter(failures: Int): Long =
            (BASE_MS * (1L shl (failures - 1).coerceAtMost(6))).coerceAtMost(MAX_MS)
    }
}

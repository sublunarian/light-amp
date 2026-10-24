package com.sublunar.amp.data

import java.util.concurrent.ConcurrentHashMap

/**
 * When to try each source again after its server stopped answering — not
 * *whether* to: a deadline retries by itself, where a latched flag waited for
 * something else to clear it.
 *
 * Per source, because a server that is down is one server: its tracks wait and
 * everyone else's are picked instead — see Downloader.eligible. After
 * [PARK_AFTER_FAILURES] failures in a row, and never on one, the Downloader
 * stops trying until something says the server is back: see Downloader.Hold.Parked.
 *
 * The time is always passed in, never read here, so the tests can be the clock.
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
        private const val BASE_MS = 2_000L
        private const val MAX_MS = 60_000L

        /**
         * At least 602 s after the first failure: the same ten minutes a parked
         * player waits, and well past a server restarting.
         */
        const val PARK_AFTER_FAILURES = 15

        /** Backs off to a minute, so a server that is really down isn't hammered. */
        fun waitAfter(failures: Int): Long =
            (BASE_MS * (1L shl (failures - 1).coerceAtMost(6))).coerceAtMost(MAX_MS)
    }
}

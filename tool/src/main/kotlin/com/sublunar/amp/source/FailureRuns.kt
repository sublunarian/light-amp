package com.sublunar.amp.source

import com.sublunar.amp.log.Log
import com.sublunar.amp.log.SystemClocks
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * Failures that repeat, logged when a run of them starts and when it ends
 * rather than each time: a heartbeat sent every ten seconds to a server that
 * refuses it would otherwise be a line every ten seconds.
 *
 * A run ends after [SUCCESSES_TO_END] successes in a row, or on a success a
 * minute after its last failure, so a link that fails every other request is
 * one run, not two lines a blip.
 *
 * Info lines, not warnings: only info lines are held to the log's per-tag limit.
 */
class FailureRuns(private val tag: String) {
    private class Run(var failures: Int, var successes: Int, var lastFailureMs: Long)

    private val runs = ConcurrentHashMap<String, Run>()

    /** [what] failed, for [why]. Only the first of a run is logged. */
    fun failed(what: String, why: String) {
        val now = SystemClocks.elapsedMs()
        var first = false
        runs.compute(what) { _, run ->
            (run ?: Run(0, 0, now).also { first = true }).apply {
                failures++
                successes = 0
                lastFailureMs = now
            }
        }
        if (first) Log.i(tag, "$what failed: $why")
    }

    /** [what] worked. Ends a run of failures, if there was one and it is over. */
    fun worked(what: String) {
        if (!runs.containsKey(what)) return
        val now = SystemClocks.elapsedMs()
        var ended = 0
        runs.computeIfPresent(what) { _, run ->
            run.successes++
            if (run.successes >= SUCCESSES_TO_END || now - run.lastFailureMs > QUIET_TO_END_MS) {
                ended = run.failures
                null
            } else {
                run
            }
        }
        if (ended > 0) Log.i(tag, "$what working again after $ended failure(s)")
    }

    private companion object {
        const val SUCCESSES_TO_END = 3
        const val QUIET_TO_END_MS = 60_000L
    }
}

/** [Result.onFailure], leaving out a cancellation: a request given up on didn't fail. */
inline fun <T> Result<T>.onRealFailure(action: (Throwable) -> Unit): Result<T> =
    onFailure { if (it !is CancellationException) action(it) }

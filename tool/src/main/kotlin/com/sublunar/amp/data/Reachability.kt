package com.sublunar.amp.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether the browsed server is answering — found out by asking it, cheaply,
 * at the moments the answer can have changed.
 *
 * It used to be inferred: one stream dying without an answer, or one failed
 * sync, marked the server gone, and only a *successful sync* marked it back.
 * Nothing started a sync when the network returned, so a hiccup during a
 * Wi-Fi-to-cellular handover left the library narrowed to downloads, playback
 * pinned to files and downloads held, in every data mode, until the next
 * launch or the half-hourly job. That was the delay after switching networks.
 *
 * Now a failure is a *suspicion*, settled at once by one ping (a single small
 * authenticated request), and every network change asks again. While the
 * server stays silent the ping is retried a few times with a growing gap and
 * then left alone until something else happens — a network change, a press, a
 * sync — so a server that is simply away costs nothing to keep waiting for.
 */
class Reachability(
    private val scope: CoroutineScope,
    private val client: () -> MusicServer?,
    /** Whether the rules allow speaking to the server at all — see LinkRules. */
    private val mayAsk: () -> Boolean,
) {
    private val _reachable = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = _reachable

    private val pinging = Mutex()
    private var retry: Job? = null

    /** Something just proved it either way: a sync that finished, a request that was answered. */
    fun report(reachable: Boolean) {
        if (reachable) retry?.cancel()
        _reachable.value = reachable
    }

    /** A new source: nothing is known about it, so assume it is there and find out. */
    fun reset() {
        retry?.cancel()
        _reachable.value = true
        check(delayMs = 0L)
    }

    /**
     * The connection changed, or something failed without an answer: ask now.
     * Debounced a little, because a handover is several events in a row and
     * the first of them often arrives before the new link can carry anything.
     */
    fun check(delayMs: Long = SETTLE_MS) {
        retry?.cancel()
        log("check in ${delayMs}ms (was ${if (_reachable.value) "reachable" else "gone"})")
        retry = scope.launch {
            delay(delayMs)
            var attempt = 0
            while (true) {
                val verdict = verify()
                // Null: the rules forbid asking (Wi-Fi Only on cellular, or no
                // link). Not an answer about the server — leave what is known
                // alone; the next network change asks again.
                if (verdict == null || verdict) return@launch
                val wait = RETRY_MS.getOrNull(attempt++) ?: return@launch
                delay(wait)
            }
        }
    }

    /**
     * Ask once, now. True/false is the server's answer; null means the rules
     * did not allow the question. The state is updated either way.
     */
    suspend fun verify(): Boolean? = pinging.withLock {
        if (!mayAsk()) {
            // The rules forbid asking — Wi-Fi Only on cellular, or no link. A
            // verdict reached on some earlier link says nothing about the next
            // one, so it is dropped: "not known to be gone" is the honest state,
            // and the rules already keep anything from being sent. Without this
            // a "gone" learnt on a dying Wi-Fi survived the switch to a mode
            // that may use cellular, with nothing left to question it.
            _reachable.value = true
            return null
        }
        val server = client() ?: return null
        val askedAtMs = System.nanoTime() / 1_000_000
        var why = "answered"
        val answered: Boolean? = try {
            // Only *this* timeout is an answer ("never came back"). A caller
            // that stops waiting sooner cancels this coroutine with a timeout
            // exception of its own, and that must pass straight through: caught
            // here as "no", a caller's impatience marked a slow server as gone
            // — the very thing the caller's short wait exists to avoid.
            withTimeoutOrNull(PING_TIMEOUT_MS) { server.ping(); true } ?: false.also { why = "no answer in ${PING_TIMEOUT_MS}ms" }
        } catch (e: NetworkGateClosedException) {
            why = "rule closed"
            // The wall said no between the check above and the request. Not
            // an answer about the server.
            null
        } catch (e: IOException) {
            // No route, no name, no TLS, no reply: nobody there — unless the
            // wall shut every socket while this was out, which is our doing
            // and not the server's.
            why = "${e::class.simpleName}: ${e.message}"
            if (NetworkGate.slammedSince(askedAtMs)) null.also { why = "our own slam" } else false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            why = "${e::class.simpleName}: ${e.message}"
            answeredBy(e)
        }
        log("ping → ${answered ?: "no verdict"} in ${System.nanoTime() / 1_000_000 - askedAtMs}ms ($why)")
        if (answered != null) _reachable.value = answered
        answered
    }

    private fun log(text: String) = android.util.Log.i("AmpNet", "reachability: $text")

    /**
     * Whether an answer that wasn't the expected one still came from the
     * *server*. A refused login or a bad request did: the server is there and
     * the rest is someone else's problem. A 5xx usually didn't — it is a
     * reverse proxy saying the thing behind it is down — and neither did a
     * body that won't parse, which is a captive portal's login page more often
     * than it is the server.
     */
    private fun answeredBy(e: Exception): Boolean {
        val status = when (e) {
            is SubsonicException -> e.status
            is PlexException -> e.status
            is JellyfinException -> e.status
            else -> return e !is kotlinx.serialization.SerializationException
        }
        return status == null || status !in 500..599
    }

    private companion object {
        const val SETTLE_MS = 400L
        const val PING_TIMEOUT_MS = 6_000L
        /** Then silence, until something changes. About two minutes in all. */
        val RETRY_MS = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    }
}

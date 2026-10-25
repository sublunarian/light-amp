package com.sublunar.amp.data

import android.util.Log
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
 * A failure is a *suspicion*, settled at once by one ping (a single small
 * authenticated request), and every network change asks again; nothing waits
 * for a sync to prove the server back. While the server stays silent the ping
 * is retried a few times with a growing gap and then left alone until
 * something else happens — a network change, a press, a sync — so a server that
 * is simply away costs nothing to keep waiting for.
 */
class Reachability(
    private val scope: CoroutineScope,
    private val client: () -> MusicServer?,
    /** Whether the rules allow speaking to the server at all — see LinkRules. */
    private val mayAsk: () -> Boolean,
    /**
     * The server's other addresses, where it has any — see [SourceRoute]. A miss is tried
     * against them before it counts, and an answer is followed by a look at
     * the ones ranked above the address in use.
     */
    private val route: SourceRoute? = null,
) {
    private val _reachable = MutableStateFlow(true)
    val reachable: StateFlow<Boolean> = _reachable

    private val pinging = Mutex()
    private var retry: Job? = null

    /**
     * Pings in a row that a server last known to be there failed to answer.
     *
     * One is not enough to call it gone. On cellular the first request after a
     * switch or a wake pays for the radio coming up, the name and the TLS
     * handshake, and regularly runs past [PING_TIMEOUT_MS] for a server that
     * answers the next ping in three seconds. Read as "gone", that one slow
     * ping narrowed the library to downloads and parked streams for as long as
     * the retry took — the flicker that made Low Data feel unpredictable.
     */
    private var misses = 0

    /** Something just proved it either way: a sync that finished, a request that was answered. */
    fun report(reachable: Boolean) {
        if (reachable) {
            retry?.cancel()
            misses = 0
        }
        if (_reachable.value != reachable) log("reported ${if (reachable) "reachable" else "gone"} by ${Trace.caller("Reachability", "App.reportServerReachable")}")
        _reachable.value = reachable
    }

    /** A new source: nothing is known about it, so assume it is there and find out. */
    fun reset() {
        retry?.cancel()
        misses = 0
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
        log("check in ${delayMs}ms (was ${if (_reachable.value) "reachable" else "gone"}) from ${Trace.caller("Reachability", "App.reportServerReachable")}")
        retry = scope.launch {
            delay(delayMs)
            var attempt = 0
            while (true) {
                val verdict = verify()
                // Null: the rules forbid asking (Wi-Fi Only on cellular, or no
                // link). Not an answer about the server — leave what is known
                // alone; the next network change asks again.
                if (verdict == null) return@launch
                if (verdict) {
                    // Answering, but maybe on the public address it moved to
                    // while away. A check follows every network change, which
                    // is the moment the LAN one can have come back.
                    try {
                        route?.upgrade()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("moving back failed: ${e::class.simpleName}: ${e.message}")
                    }
                    return@launch
                }
                val wait = RETRY_MS.getOrNull(attempt++)
                    ?: run {
                        log("gave up after $attempt pings; waiting for a network change or a press")
                        return@launch
                    }
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
            // verdict reached on an earlier link says nothing about the next
            // one, so it is dropped: kept, a "gone" learnt on a dying Wi-Fi
            // would survive the switch to a mode that may use cellular, with
            // nothing left to question it.
            if (!_reachable.value) log("rules forbid asking — dropping the old \"gone\"")
            misses = 0
            _reachable.value = true
            return null
        }
        val server = client() ?: return null.also { log("no client to ask") }
        val ping = askServer(server)
        log("ping → ${ping.answered ?: "no verdict"} in ${ping.tookMs}ms (${ping.why})")
        var answered = ping.answered
        // Silent at this address isn't silent at every address: a LAN one
        // never answers on cellular. Another that answered is the server
        // answering — the source is saved onto it, which rebuilds the client.
        if (answered == false && route != null) {
            val moved = try {
                route.recover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("rerouting failed: ${e::class.simpleName}: ${e.message}")
                false
            }
            if (moved) answered = true
        }
        when (answered) {
            true -> {
                misses = 0
                _reachable.value = true
            }
            // The verdict is still returned as it was — a caller deciding
            // what to do with one stalled track should hear "no" — but the
            // state every screen reads waits for a second miss. The retry in
            // [check] is what asks again.
            false -> if (_reachable.value && ++misses < MISSES_TO_GONE) {
                log("first miss, holding \"reachable\" until the retry")
            } else {
                _reachable.value = false
            }
            null -> {}
        }
        answered
    }

    private fun log(text: String) = Log.i("AmpNet", "reachability: $text")

    private companion object {
        const val SETTLE_MS = 400L
        /** See [misses]. */
        const val MISSES_TO_GONE = 2
        /** Then silence, until something changes. About two minutes in all. */
        val RETRY_MS = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
    }
}

/** What one [askServer] came to: the server's answer, or null for none; [why] and [tookMs] are for the log. */
internal class Ping(val answered: Boolean?, val why: String, val tookMs: Long)

/**
 * Ping [server] once and judge what came back — for the browsed server
 * ([Reachability]) and for a parked download server alike. Null is no verdict
 * about the server: the wall said no, or shut the socket itself.
 */
internal suspend fun askServer(server: MusicServer): Ping {
    val askedAtMs = monotonicMs()
    var why = "answered"
    val answered: Boolean? = try {
        // Only *this* timeout is an answer ("never came back"). A caller
        // that stops waiting sooner cancels this with a timeout of its own,
        // which must pass straight through: caught here as "no", a caller's
        // impatience would mark a slow server as gone.
        withTimeoutOrNull(PING_TIMEOUT_MS) { server.ping(); true } ?: false.also { why = "no answer in ${PING_TIMEOUT_MS}ms" }
    } catch (e: NetworkGateClosedException) {
        why = "rule closed"
        // The wall said no between the caller's check and the request. Not
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
    return Ping(answered, why, monotonicMs() - askedAtMs)
}

private const val PING_TIMEOUT_MS = 6_000L

/**
 * Whether an answer that wasn't the expected one still came from the
 * *server*. A refused login or a bad request did: the server is there and
 * the rest is someone else's problem. A 5xx usually didn't — it is a
 * reverse proxy saying the thing behind it is down — and neither did a
 * body that won't parse, or a page where the server's JSON should be, which
 * is a captive portal's login page more often than it is the server.
 */
internal fun answeredBy(e: Exception): Boolean {
    if (e !is ServerException) return e !is kotlinx.serialization.SerializationException
    if (!e.fromServer) return false
    if (e.refusedLogin) return true
    val status = e.status
    return status == null || status !in 500..599
}

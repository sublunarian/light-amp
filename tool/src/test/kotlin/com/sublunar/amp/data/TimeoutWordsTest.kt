package com.sublunar.amp.data

import com.sublunar.amp.data.NetworkGate.withoutRequestInTimeouts
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A server that never answers, so a real read timeout comes back through
 * ktor's OkHttp engine exactly as it does on the phone.
 */
class TimeoutWordsTest {

    private val silent = ServerSocket(0)
    private val held = mutableListOf<java.net.Socket>()

    init {
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val socket = silent.accept()
                    synchronized(held) { held += socket }
                }
            }
        }
    }

    @AfterTest
    fun close() {
        synchronized(held) { held.forEach { runCatching { it.close() } } }
        silent.close()
    }

    private val url = "http://127.0.0.1:${silent.localPort}/rest/ping.view?u=sam&t=0a1b2c3d&s=salty"

    private fun failure(cleaned: Boolean): Throwable {
        val client = HttpClient(OkHttp) {
            expectSuccess = false
            engine { config { readTimeout(300, TimeUnit.MILLISECONDS) } }
            if (cleaned) withoutRequestInTimeouts()
        }
        return client.use { runCatching { runBlocking { it.get(url) } }.exceptionOrNull() }
            ?: error("the silent server answered")
    }

    private fun Throwable.allMessages(): String =
        generateSequence(this) { it.cause }.take(6).joinToString(" | ") { it.toString() }

    @Test
    fun `ktor's own timeout names the whole request, login and all`() {
        assertTrue("t=0a1b2c3d" in failure(cleaned = false).allMessages(), "the test no longer shows the leak it guards against")
    }

    @Test
    fun `the same timeout through Amp's clients names no request`() {
        val e = failure(cleaned = true)
        val words = e.allMessages()
        assertTrue(e is java.io.IOException, "still an IOException, as callers expect: $words")
        listOf("u=sam", "0a1b2c3d", "salty", "ping.view").forEach { assertFalse(it in words, "$it in: $words") }
    }
}

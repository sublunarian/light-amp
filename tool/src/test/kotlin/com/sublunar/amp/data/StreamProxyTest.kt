package com.sublunar.amp.data

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient

/**
 * The proxy end to end, the way the player uses it: an `HttpsURLConnection`
 * to 127.0.0.1, trusting only what [StreamProxy] itself installed, against a
 * plain HTTP "music server" on another local port.
 */
class StreamProxyTest {
    private val payload = Random(7).nextBytes(300_000)
    private lateinit var origin: ServerSocket
    private lateinit var scope: CoroutineScope
    private lateinit var proxy: StreamProxy
    @Volatile private var gateOpen = true

    @BeforeTest
    fun setUp() {
        origin = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { serveOrigin() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = OkHttpClient()
        proxy = StreamProxy(gateOpen = { gateOpen }, upstream = { client }, log = ::println)
        proxy.start(scope)
        val deadline = System.currentTimeMillis() + 10_000
        while (proxy.state.value is StreamProxy.State.Starting && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @AfterTest
    fun tearDown() {
        proxy.stop()
        scope.cancel()
        origin.close()
    }

    private fun originUrl(path: String) = "http://127.0.0.1:${origin.localPort}$path"

    private fun fetch(url: String, range: String? = null): Pair<Int, ByteArray> {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) connection.setRequestProperty("Range", range)
        return try {
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            code to (stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun comesUpAndProvesItself() {
        assertIs<StreamProxy.State.On>(proxy.state.value, "state was ${proxy.state.value}")
    }

    @Test
    fun certificateNamesLoopbackOnly() {
        val cert = LoopbackCert.create().certificate
        val names = cert.subjectAlternativeNames.map { it[1].toString() }.toSet()
        assertEquals(setOf("127.0.0.1", "localhost"), names)
        cert.checkValidity()
    }

    @Test
    fun wholeFileWithItsLength() {
        val (code, body) = fetch(proxy.wrap(originUrl("/file")))
        assertEquals(200, code)
        assertContentEquals(payload, body)
    }

    @Test
    fun rangeIsPassedBothWays() {
        val (code, body) = fetch(proxy.wrap(originUrl("/file")), range = "bytes=1000-")
        assertEquals(206, code)
        assertContentEquals(payload.copyOfRange(1000, payload.size), body)
    }

    @Test
    fun unknownLengthArrivesWhole() {
        val (code, body) = fetch(proxy.wrap(originUrl("/chunked")))
        assertEquals(200, code)
        assertContentEquals(payload, body)
    }

    @Test
    fun aBreakHalfwayIsPickedUpWithoutThePlayerNoticing() {
        // The origin promises the whole file and hangs up a third of the way
        // in — a Wi-Fi-to-cellular handover, as the proxy sees it. The player's
        // single request must still receive every byte, in order.
        val (code, body) = fetch(proxy.wrap(originUrl("/flaky")))
        assertEquals(200, code)
        assertContentEquals(payload, body)
        assertTrue(flakyHits >= 2, "expected a second, ranged request to the origin")
    }

    @Test
    fun aBreakInATranscodeIsSplicedWhenTheBytesMatch() {
        // No length, no ranges — a live transcode. The origin hangs up a third
        // of the way in, and answers the pickup from the top with the same
        // bytes. The player's single request must still receive every byte.
        val (code, body) = fetch(proxy.wrap(originUrl("/flakytranscode")))
        assertEquals(200, code)
        assertContentEquals(payload, body)
        assertTrue(flakyTranscodeHits >= 2, "expected a second request to the origin")
    }

    @Test
    fun aBreakInATranscodeIsNotSplicedWhenTheBytesDiffer() {
        // Same, but the second encode comes out different: the door must not
        // splice it in. The player sees a broken stream, as it would have.
        val failed = runCatching { fetch(proxy.wrap(originUrl("/flakytranscode-changed"))) }
        val ok = failed.getOrNull()
        assertTrue(ok == null || !ok.second.contentEquals(payload), "a different stream was spliced in")
    }

    @Test
    fun aPickupThatFailsIsTriedAgain() {
        // The origin hangs up mid-file, then slams the door on the first
        // pickup attempt — the new link isn't ready yet — and only answers the
        // second. The player still gets every byte.
        val (code, body) = fetch(proxy.wrap(originUrl("/flakytwice")))
        assertEquals(200, code)
        assertContentEquals(payload, body)
        assertTrue(flakyTwiceHits >= 3, "expected the pickup to be tried again (hits=$flakyTwiceHits)")
    }

    @Test
    fun aNewRequestForTheSameTrackRetiresTheOldStream() {
        // A seek: the player asks again for the same track while the old
        // stream is still trickling in. The old upstream must be let go
        // *before* the new one is asked for — on Plex the two would otherwise
        // kill each other's transcode in turn — and the new one arrives whole.
        val oldStream = proxy.wrap(originUrl("/slow"), group = "track-1")
        val newStream = proxy.wrap(originUrl("/file"), group = "track-1")
        var oldResult: Result<Pair<Int, ByteArray>>? = null
        val old = thread { oldResult = runCatching { fetch(oldStream) } }
        val deadline = System.currentTimeMillis() + 5_000
        while (slowHits == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(200) // the old stream is mid-flight now

        val (code, body) = fetch(newStream)
        assertEquals(200, code)
        assertContentEquals(payload, body)

        old.join(10_000)
        val finished = oldResult
        assertTrue(finished != null, "the old stream was never let go")
        val delivered = finished.getOrNull()?.second
        assertTrue(delivered == null || !delivered.contentEquals(payload), "the old stream ran to its end")
        // The origin learns of the close on a later write, a few of which the
        // kernel may still accept: give it a moment.
        val noticeBy = System.currentTimeMillis() + 5_000
        while (!slowClosedEarly && System.currentTimeMillis() < noticeBy) Thread.sleep(20)
        assertTrue(slowClosedEarly, "the origin never saw the old upstream close")
    }

    @Test
    fun differentTracksDoNotDisturbEachOther() {
        val a = proxy.wrap(originUrl("/chunked"), group = "track-a")
        val b = proxy.wrap(originUrl("/file"), group = "track-b")
        var first: Pair<Int, ByteArray>? = null
        val t = thread { first = fetch(a) }
        val second = fetch(b)
        t.join(10_000)
        assertContentEquals(payload, first?.second)
        assertContentEquals(payload, second.second)
    }

    @Test
    fun serverStatusIsTheServers() {
        assertEquals(404, fetch(proxy.wrap(originUrl("/gone"))).first)
    }

    @Test
    fun closedRuleIsRefusedWithoutAskingTheServer() {
        gateOpen = false
        val before = originHits
        assertEquals(StreamProxy.GATE_STATUS, fetch(proxy.wrap(originUrl("/file"))).first)
        assertEquals(before, originHits, "the origin must not be contacted while the rule is closed")
    }

    @Test
    fun addressesAreStableAndUnguessable() {
        val a = proxy.wrap(originUrl("/file"))
        assertEquals(a, proxy.wrap(originUrl("/file")))
        assertTrue(a.startsWith("https://127.0.0.1:"))
        val port = (proxy.state.value as StreamProxy.State.On).port
        assertEquals(404, fetch("https://127.0.0.1:$port/s/${"0".repeat(32)}").first)
    }

    // --- A very small music server -------------------------------------------

    @Volatile private var originHits = 0
    @Volatile private var flakyHits = 0
    @Volatile private var flakyTranscodeHits = 0
    @Volatile private var flakyTwiceHits = 0
    @Volatile private var slowHits = 0
    @Volatile private var slowClosedEarly = false
    @Volatile private var flakyChangedHits = 0

    private fun serveOrigin() {
        while (!origin.isClosed) {
            val socket = try { origin.accept() } catch (_: Exception) { return }
            thread(isDaemon = true) {
                socket.use { s ->
                    originHits++
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val requestLine = reader.readLine() ?: return@use
                    var range: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                    }
                    val out = s.getOutputStream()
                    fun head(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
                    when (requestLine.split(' ')[1]) {
                        "/file" -> {
                            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                            if (from == null) {
                                head("HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nAccept-Ranges: bytes\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                                out.write(payload)
                            } else {
                                head("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/flac\r\nAccept-Ranges: bytes\r\nContent-Range: bytes $from-${payload.size - 1}/${payload.size}\r\nContent-Length: ${payload.size - from}\r\nConnection: close\r\n\r\n")
                                out.write(payload, from, payload.size - from)
                            }
                        }
                        "/flaky" -> {
                            flakyHits++
                            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                            if (from == null) {
                                head("HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nAccept-Ranges: bytes\r\nETag: \"v1\"\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                                out.write(payload, 0, 100_000)
                                out.flush()
                                // Hang up mid-body: short of the promised length.
                            } else {
                                head("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/flac\r\nAccept-Ranges: bytes\r\nETag: \"v1\"\r\nContent-Range: bytes $from-${payload.size - 1}/${payload.size}\r\nContent-Length: ${payload.size - from}\r\nConnection: close\r\n\r\n")
                                out.write(payload, from, payload.size - from)
                            }
                        }
                        "/flakytranscode", "/flakytranscode-changed" -> {
                            val changed = requestLine.contains("-changed")
                            val hit = if (changed) ++flakyChangedHits else ++flakyTranscodeHits
                            // Ranges ignored, no length: every request is the whole
                            // encode from the top. The second encode of the
                            // "changed" file differs from the first byte on.
                            val bytes = if (changed && hit > 1) payload.map { (it + 1).toByte() }.toByteArray() else payload
                            head("HTTP/1.1 200 OK\r\nContent-Type: audio/ogg\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                            val stopAt = if (hit == 1) 100_000 else bytes.size
                            var at = 0
                            while (at < stopAt) {
                                val n = minOf(7_777, stopAt - at)
                                head("${n.toString(16)}\r\n"); out.write(bytes, at, n); head("\r\n")
                                at += n
                            }
                            if (hit == 1) out.flush() else head("0\r\n\r\n") // first time: hang up mid-stream
                        }
                        "/flakytwice" -> {
                            val hit = ++flakyTwiceHits
                            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                            when {
                                hit == 1 -> {
                                    head("HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nAccept-Ranges: bytes\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                                    out.write(payload, 0, 100_000)
                                    out.flush()
                                }
                                hit == 2 -> Unit // no answer at all: the socket just closes
                                from != null -> {
                                    head("HTTP/1.1 206 Partial Content\r\nContent-Type: audio/flac\r\nAccept-Ranges: bytes\r\nContent-Range: bytes $from-${payload.size - 1}/${payload.size}\r\nContent-Length: ${payload.size - from}\r\nConnection: close\r\n\r\n")
                                    out.write(payload, from, payload.size - from)
                                }
                                else -> {
                                    head("HTTP/1.1 200 OK\r\nContent-Type: audio/flac\r\nAccept-Ranges: bytes\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n")
                                    out.write(payload)
                                }
                            }
                        }
                        "/slow" -> {
                            slowHits++
                            head("HTTP/1.1 200 OK\r\nContent-Type: audio/ogg\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                            var at = 0
                            try {
                                while (at < payload.size) {
                                    val n = minOf(2_000, payload.size - at)
                                    head("${n.toString(16)}\r\n"); out.write(payload, at, n); head("\r\n")
                                    out.flush()
                                    at += n
                                    Thread.sleep(40) // about six seconds for the whole thing
                                }
                                head("0\r\n\r\n")
                            } catch (_: Exception) {
                                slowClosedEarly = true // the proxy hung up on us mid-stream
                            }
                        }
                        "/chunked" -> {
                            head("HTTP/1.1 200 OK\r\nContent-Type: audio/ogg\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                            var at = 0
                            while (at < payload.size) {
                                val n = minOf(7_777, payload.size - at)
                                head("${n.toString(16)}\r\n"); out.write(payload, at, n); head("\r\n")
                                at += n
                            }
                            head("0\r\n\r\n")
                        }
                        else -> head("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    }
                    out.flush()
                }
            }
        }
    }
}

package com.sublunar.amp.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A door the platform player streams through, so that the player never talks
 * to the music server itself.
 *
 * **Why.** The player fetches with its own HTTP stack, outside NetworkGate: it
 * opens the next track ahead of time and reconnects a dropped stream at once,
 * on whatever network the phone is then using, and after the user backs out of
 * the tool it goes on playing its queue with no tool code left to stop it. The
 * SDK gives a tool no say in any of that (see tool/docs/SDK-GAPS.md). So the
 * player is handed addresses on this phone instead — `https://127.0.0.1:port/…`
 * — and what answers there is this class, which fetches the real bytes through
 * the gated client like everything else in the app. The rule is asked when
 * each request arrives and all the while it is served, under the rules in
 * force *at that moment*; the player's queue never has to be edited because a
 * network or a data mode changed. It lives in the app's process scope, not the
 * activity's, so it is still standing when the screens are gone.
 *
 * **Why TLS.** A store build gets only the manifest Light's plugin generates,
 * and that permits no cleartext HTTP — not even to this phone. So the door
 * speaks TLS, with an identity made at launch ([LoopbackCert]) and trusted by
 * exactly one addition to the process's default trust for `HttpsURLConnection`
 * (which is what the player uses, and OkHttp does not): every other
 * certificate is judged by the platform's own trust manager, untouched.
 *
 * **Fail-safe.** None of this is assumed to work. At start the proxy fetches
 * from itself the way the player will; only if that succeeds does [wrap] hand
 * out loopback addresses. Otherwise the player gets the server's own URL, as
 * it always has, and [state] says why.
 */
class StreamProxy(
    private val gateOpen: () -> Boolean,
    private val upstream: () -> OkHttpClient,
    private val log: (String) -> Unit,
    /**
     * How many bytes to hold between the server and the player — see [Pipe].
     * Asked once per stream; a metered link gets less, because what is read
     * ahead of a track the user then skips is data spent on nothing.
     */
    private val readAheadBytes: () -> Int = { READ_AHEAD_BYTES },
) {
    sealed interface State {
        data object Starting : State
        data class On(val port: Int) : State
        data class Off(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state

    private var server: ServerSocket? = null
    private val tokens = TokenTable()

    /**
     * The address to give the player for [url]: through this door when it is
     * open, else [url] itself.
     *
     * [group] names what the stream is *of* — the track. The player only ever
     * has one live load per track, so a new request in a group means the
     * previous one has been abandoned, whether or not its socket has closed
     * yet: a seek, a rebuild, a retry. The door keeps one upstream per group
     * and retires the old one first — see [stream]. It matters on Plex, which
     * allows one transcode per playback session and kills the previous holder
     * when the next request arrives: left alive, the old feeder took that kill
     * for a broken link and picked its stream up again, which killed the new
     * one, whose feeder did the same — two streams of one song trading resets
     * for twenty seconds after every seek.
     */
    fun wrap(url: String, group: String? = null): String {
        if (url.isEmpty()) return url
        val on = _state.value as? State.On ?: return url
        return "https://$HOST:${on.port}$STREAM_PATH${tokens.tokenFor(url, group)}"
    }

    /** Bring the door up and prove it, off the calling thread. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (server != null) return
        scope.launch(Dispatchers.IO) {
            try {
                val identity = LoopbackCert.create()
                trustForThisProcess(identity.certificate)
                val socket = serverContext(identity).serverSocketFactory
                    .createServerSocket(0, BACKLOG, InetAddress.getByName(HOST))
                server = socket
                scope.launch(Dispatchers.IO) { acceptLoop(scope, socket) }
                selfTest(socket.localPort)
                _state.value = State.On(socket.localPort)
                log("proxy on, port ${socket.localPort}")
            } catch (e: Exception) {
                runCatching { server?.close() }
                server = null
                _state.value = State.Off(e.message ?: e.toString())
                log("proxy off: $e")
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        _state.value = State.Off("stopped")
    }

    // --- TLS -----------------------------------------------------------------

    private fun serverContext(identity: LoopbackCert): SSLContext {
        val password = CharArray(0)
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("amp", identity.keyPair.private, password, arrayOf(identity.certificate))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keys, password) }
        return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, SecureRandom()) }
    }

    /**
     * Add [ours] to what `HttpsURLConnection` trusts in this process — and
     * nothing else. Every other chain goes to the platform's own trust
     * manager, the one that enforces the network security config, exactly as
     * before; this only answers first for the one certificate made a moment
     * ago in this process. The hostname check is left entirely alone: the
     * certificate names 127.0.0.1, so the standard verifier passes it there
     * and nowhere else.
     */
    private fun trustForThisProcess(ours: X509Certificate) {
        val platform = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(PlusOne(platform, ours)), SecureRandom())
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
    }

    private class PlusOne(
        private val platform: X509TrustManager,
        private val ours: X509Certificate,
    ) : X509ExtendedTrustManager() {
        private val extended = platform as? X509ExtendedTrustManager
        private val oursEncoded = ours.encoded

        private fun isOurs(chain: Array<out X509Certificate>?): Boolean =
            chain != null && chain.size == 1 && chain[0].encoded.contentEquals(oursEncoded)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (!isOurs(chain)) platform.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            if (isOurs(chain)) return
            if (extended != null) extended.checkServerTrusted(chain, authType, socket)
            else platform.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            if (isOurs(chain)) return
            if (extended != null) extended.checkServerTrusted(chain, authType, engine)
            else platform.checkServerTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            platform.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            if (extended != null) extended.checkClientTrusted(chain, authType, socket)
            else platform.checkClientTrusted(chain, authType)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            if (extended != null) extended.checkClientTrusted(chain, authType, engine)
            else platform.checkClientTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
    }

    /** Fetch from ourselves the way the player will. Throws unless the answer is exactly right. */
    private fun selfTest(port: Int) {
        val connection = URL("https://$HOST:$port$SELF_TEST_PATH").openConnection() as HttpsURLConnection
        connection.connectTimeout = SELF_TEST_TIMEOUT_MS
        connection.readTimeout = SELF_TEST_TIMEOUT_MS
        try {
            val code = connection.responseCode
            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            if (code != 200 || body != SELF_TEST_BODY) throw IOException("self-test answered $code \"$body\"")
        } finally {
            connection.disconnect()
        }
    }

    // --- Serving -------------------------------------------------------------

    private fun acceptLoop(scope: CoroutineScope, socket: ServerSocket) {
        while (scope.isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                // Closed by stop(): nothing to say. Anything else — out of file
                // descriptors, say — and the door is shut while still handing
                // out its address: every track would hang on a port nobody
                // answers. Say so, and wrap() goes back to the server's URLs.
                if (!socket.isClosed) {
                    runCatching { socket.close() }
                    server = null
                    _state.value = State.Off("accept failed: ${e.message}")
                    log("proxy off: accept failed: $e")
                }
                break
            }
            scope.launch(Dispatchers.IO) {
                try {
                    client.soTimeout = CLIENT_READ_TIMEOUT_MS
                    serve(client)
                } catch (_: Exception) {
                    // A player that hung up, a handshake that failed: nothing
                    // to tell anyone. The socket is closed below either way.
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val request = lines.first().split(' ')
        if (request.size < 2) return respond(output, 400, "Bad Request")
        val method = request[0]
        val path = request[1]
        if (method != "GET" && method != "HEAD") return respond(output, 405, "Method Not Allowed")
        if (path == SELF_TEST_PATH) return respond(output, 200, "OK", SELF_TEST_BODY)
        if (!path.startsWith(STREAM_PATH)) return respond(output, 404, "Not Found")
        val target = tokens.targetFor(path.removePrefix(STREAM_PATH))
            ?: return respond(output, 404, "Not Found")
        val range = lines.drop(1).firstOrNull { it.startsWith("Range:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        stream(target.url, target.group, range, headOnly = method == "HEAD", output)
    }

    /**
     * Fetch [url] through the wall and hand it on, byte for byte.
     *
     * What the player is told is what the server said: the status, the type,
     * the length, the ranges — its seeking and its duration arithmetic depend
     * on all of them. Two answers are this door's own. The rule being closed
     * is [GATE_STATUS], at once and without touching the network, which the
     * playback controller reads as "waiting", not as a failure of the server.
     * And a server that cannot be reached is no answer at all: the connection
     * is dropped, which the player reports as the network error it is.
     *
     * Between the server and the player sits a [Pipe] of read-ahead. It is
     * there for the moment the link changes under a stream: the [Feeder]
     * fetches the rest on the new link — a range request where the server
     * honours ranges, otherwise the whole thing again with what was already
     * delivered skipped and checked byte for byte — while the player goes on
     * being fed from the pipe, and never learns that anything happened. That
     * matters most for a transcode, whose length the player does not know:
     * shown a broken connection it would wait for its buffer to run dry and
     * then start the song again from the top.
     */
    private fun stream(url: String, group: String?, range: String?, headOnly: Boolean, output: OutputStream) {
        if (!gateOpen()) return respond(output, GATE_STATUS, "Waiting")
        // Before anything is asked of the server: whatever was still feeding
        // this track is finished. Its upstream is closed *first*, so the
        // request below is the only claim on the server's session for it.
        if (group != null && !headOnly) {
            live.remove(group)?.let {
                log("a newer request for the same track — retiring the old stream")
                it.cancel()
            }
        }
        val firstCall = request(url, range)
        val first: Response = firstCall.execute() // an IOException here drops the connection — see above
        // The player only ever asks for the body; a HEAD, or a server's "no",
        // gets the status and headers and nothing else — and the upstream
        // body, which was opened all the same, is let go at once.
        if (headOnly || !first.isSuccessful) {
            first.use {
                val head = StringBuilder("HTTP/1.1 ${first.code} Proxied\r\n")
                for (name in PASSED_ON) first.header(name)?.let { head.append("$name: ${it.printable()}\r\n") }
                head.append("Content-Length: 0\r\nConnection: close\r\n\r\n")
                output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
            return
        }
        val length = first.body.contentLength()
        val head = StringBuilder("HTTP/1.1 ${first.code} Proxied\r\n")
        for (name in PASSED_ON) first.header(name)?.let { head.append("$name: ${it.printable()}\r\n") }
        if (length >= 0) head.append("Content-Length: $length\r\n") else head.append("Transfer-Encoding: chunked\r\n")
        head.append("Connection: close\r\n\r\n")
        try {
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        } catch (e: IOException) {
            first.close()
            throw e
        }

        val pipe = Pipe(readAheadBytes())
        val feeder = Feeder(url, firstCall, first, pipe)
        if (group != null) live[group] = feeder
        val feeding = Thread(feeder::run, "amp-proxy-feed").apply {
            isDaemon = true
            // A plain thread that lets an exception escape takes the whole
            // process with it — which it did, once, on an interrupt that a
            // blocking queue turned into a thrown InterruptedException. run()
            // catches everything now; this is the net under the net.
            setUncaughtExceptionHandler { _, e -> log("feeder died: $e") }
            start()
        }
        try {
            while (true) {
                val chunk = pipe.take() ?: break
                try {
                    if (length < 0) output.write("${chunk.size.toString(16)}\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.write(chunk)
                    if (length < 0) output.write(CRLF)
                } catch (e: IOException) {
                    // The player hung up — a skip, a seek, a stop. Let go of the server.
                    throw e
                }
            }
            if (pipe.failed) throw IOException("upstream lost")
            // A clean end. Without the last chunk the player reads an unknown-
            // length stream that simply stopped as an error, which a broken one
            // relies on.
            if (length < 0) output.write(LAST_CHUNK)
            output.flush()
        } finally {
            feeder.cancel()
            if (group != null) live.remove(group, feeder)
            feeding.join(FEEDER_JOIN_MS)
        }
    }

    /** The one feeder allowed per group — see [wrap]. */
    private val live = ConcurrentHashMap<String, Feeder>()

    /**
     * Reads the stream from the server into the [Pipe], and carries on across
     * a break in the link.
     *
     * A broken read is answered with a range request for the rest, from the
     * byte after the last one delivered. A server that honours it answers 206
     * from exactly there, and the stream continues with nothing to check. One
     * that doesn't — every live transcode — answers 200 from the top, and the
     * bytes already delivered are read again and thrown away, on two
     * conditions: the first kilobytes must match what was delivered the first
     * time, and so must the last. An encoder is deterministic or it isn't, and
     * the head says which before much has been spent; the tail proves the
     * splice lands where it should. Either failing, the feeder gives up and
     * the player is left to its own recovery, which is what it had before.
     */
    private inner class Feeder(
        private val url: String,
        firstCall: Call,
        private val first: Response,
        private val pipe: Pipe,
    ) : Runnable {
        @Volatile private var cancelled = false
        @Volatile private var call: Call = firstCall
        @Volatile private var thread: Thread? = null

        /**
         * The player has gone: close the socket under whatever read is in
         * progress, and wake a sleep. Nothing here runs a moment longer than
         * it must — a pickup abandoned while it was skipping the delivered
         * bytes again would otherwise finish that re-download for nobody.
         */
        fun cancel() {
            cancelled = true
            call.cancel()
            thread?.interrupt()
        }

        override fun run() {
            thread = Thread.currentThread()
            var response = first
            // Where in the whole entity this stream began, for picking up after a break.
            val startedAt = first.header("Content-Range")?.let(::rangeStart) ?: 0L
            val total = entityLength(first)
            // A weak ETag may not be sent as If-Range (RFC 7233), and a server
            // compares strongly anyway: it would only ever buy a 200 and a full
            // re-download where Last-Modified would have resumed.
            val validator = first.header("ETag")?.takeUnless { it.startsWith("W/") } ?: first.header("Last-Modified")
            var produced = 0L
            var pickups = 0
            val headOfStream = ByteArrayOutputStream()
            val tailOfStream = TailRing(OVERLAP_BYTES)
            val buffer = ByteArray(COPY_BYTES)
            var clean = false
            try {
                var body: InputStream = response.body.byteStream()
                while (!cancelled) {
                    val n = try {
                        body.read(buffer)
                    } catch (e: IOException) {
                        if (cancelled) return
                        val at = startedAt + produced
                        runCatching { response.close() }
                        // Each attempt is one pickup spent, whether it is refused
                        // or fails on its own — the link that just changed is
                        // often not usable for the first second, and the first
                        // attempt after a break is the one most likely to throw.
                        var joined: InputStream? = null
                        while (joined == null) {
                            val why = when {
                                pickups >= MAX_PICKUPS -> "too many breaks"
                                !gateOpen() -> "rule closed"
                                total >= 0 && at >= total -> "at the end"
                                else -> null
                            }
                            if (why != null) {
                                log("upstream broke at $at, not picked up: $why")
                                return
                            }
                            try {
                                Thread.sleep(PICKUP_WAIT_MS[pickups])
                            } catch (_: InterruptedException) {
                                return
                            }
                            pickups++
                            if (cancelled) return
                            try {
                                val next = request(url, "bytes=$at-", ifRange = validator)
                                call = next
                                if (cancelled) {
                                    next.cancel()
                                    return
                                }
                                response = next.execute()
                                val stream = response.body.byteStream()
                                val ok = when (response.code) {
                                    206 -> response.header("Content-Range")?.let(::rangeStart) == at
                                    200 -> (total < 0 || entityLength(response) == total) &&
                                        skipVerified(stream, at, startedAt, headOfStream.toByteArray(), tailOfStream) { cancelled }
                                    else -> false
                                }
                                if (cancelled) return
                                if (ok) {
                                    log("upstream broke at $at, picked up (#$pickups, ${response.code})")
                                    joined = stream
                                } else {
                                    log("upstream broke at $at, pickup #$pickups refused (${response.code})")
                                    // Refused is final: the same server will say the
                                    // same thing again. Only a failure is retried.
                                    runCatching { response.close() }
                                    return
                                }
                            } catch (e: IOException) {
                                if (cancelled) return
                                log("upstream broke at $at, pickup #$pickups failed: $e")
                                runCatching { response.close() }
                            }
                        }
                        body = joined
                        continue
                    }
                    if (n < 0) break
                    if (headOfStream.size() < OVERLAP_BYTES) {
                        headOfStream.write(buffer, 0, minOf(n, OVERLAP_BYTES - headOfStream.size()))
                    }
                    tailOfStream.add(buffer, 0, n)
                    produced += n
                    if (!pipe.put(buffer.copyOf(n)) { cancelled }) return
                }
                clean = !cancelled
            } catch (e: InterruptedException) {
                // cancel() interrupts this thread on purpose — a sleep, or a
                // wait for room in the pipe — and that is the end of the job,
                // not an error.
            } catch (e: Throwable) {
                if (!cancelled) log("upstream failed: $e")
            } finally {
                runCatching { response.close() }
                pipe.end(clean)
            }
        }
    }

    /**
     * Read and discard [skip] bytes of a fresh 200 response, checking that
     * bytes [headAt, headAt + head.size) equal [head] and the last bytes equal
     * [tail]. False on a mismatch or on running out early — in which case the
     * stream is not the one that was being delivered, and must not be joined.
     */
    private fun skipVerified(
        stream: InputStream,
        skip: Long,
        headAt: Long,
        head: ByteArray,
        tail: TailRing,
        cancelled: () -> Boolean,
    ): Boolean {
        val buffer = ByteArray(COPY_BYTES)
        val seen = TailRing(OVERLAP_BYTES)
        var done = 0L
        while (done < skip) {
            if (cancelled()) return false
            val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), skip - done).toInt())
            if (n < 0) return false
            // The part of this read that overlaps the remembered head, if any.
            val from = maxOf(done, headAt)
            val to = minOf(done + n, headAt + head.size)
            if (from < to) {
                for (i in from until to) {
                    if (buffer[(i - done).toInt()] != head[(i - headAt).toInt()]) return false
                }
            }
            seen.add(buffer, 0, n)
            done += n
        }
        return seen.matches(tail)
    }

    /** The length of the whole entity: Content-Length, or the total in a 206's Content-Range. -1 if unknown. */
    private fun entityLength(response: Response): Long {
        if (response.code == 206) {
            return response.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
        }
        return response.body.contentLength()
    }

    /**
     * Bounded read-ahead between the feeder and the player, in chunks.
     *
     * The player reads in bursts up to its own buffer target and then stops;
     * writes to it then block, the pipe fills, and the feeder blocks in turn —
     * so the server is never read faster than the player will take, plus this
     * much. The slack is what the feeder spends on picking up after a break.
     */
    private class Pipe(capacityBytes: Int) {
        private val chunks = ArrayBlockingQueue<ByteArray>(maxOf(2, capacityBytes / COPY_BYTES))
        @Volatile var failed = false
            private set

        /** False if [cancelled] came true, or the thread was interrupted, while there was no room. */
        fun put(chunk: ByteArray, cancelled: () -> Boolean): Boolean {
            try {
                while (!chunks.offer(chunk, POLL_MS, TimeUnit.MILLISECONDS)) if (cancelled()) return false
            } catch (_: InterruptedException) {
                return false
            }
            return true
        }

        /**
         * The end of the stream, which the writer must always hear about — it
         * is blocked in [take] until it does.
         *
         * A clean end waits its turn behind the data still in the pipe. Any
         * other end is an abandonment: what is buffered is for a stream nobody
         * wants any more, so it is dropped and the marker goes to the front,
         * without waiting and whatever the thread's interrupt flag says. That
         * flag is exactly how a feeder gets here when it is retired, and an
         * interruptible offer would throw before enqueuing anything — leaving
         * the writer, its thread and its socket waiting for ever.
         */
        fun end(clean: Boolean) {
            failed = !clean
            if (clean) {
                try {
                    chunks.put(END)
                    return
                } catch (_: InterruptedException) {
                    failed = true
                }
            }
            Thread.interrupted() // clear it: the offer below must not throw
            chunks.clear()
            chunks.offer(END)
        }

        /** The next chunk, or null at the end — check [failed] to know which end. */
        fun take(): ByteArray? = chunks.take().takeUnless { it === END }
    }

    /** The last [capacity] bytes seen, in order. */
    private class TailRing(private val capacity: Int) {
        private val ring = ByteArray(capacity)
        private var next = 0
        private var filled = 0

        fun add(bytes: ByteArray, offset: Int, length: Int) {
            for (i in offset until offset + length) {
                ring[next] = bytes[i]
                next = (next + 1) % capacity
                if (filled < capacity) filled++
            }
        }

        fun snapshot(): ByteArray {
            val out = ByteArray(filled)
            val start = if (filled < capacity) 0 else next
            for (i in 0 until filled) out[i] = ring[(start + i) % capacity]
            return out
        }

        /** Whether the two remember the same bytes, over as much as both hold. */
        fun matches(other: TailRing): Boolean {
            val a = snapshot()
            val b = other.snapshot()
            val n = minOf(a.size, b.size)
            return a.copyOfRange(a.size - n, a.size).contentEquals(b.copyOfRange(b.size - n, b.size))
        }
    }

    private fun request(url: String, range: String?, ifRange: String? = null): Call {
        val builder = Request.Builder().url(url)
            // As the player itself asks: compressed audio would lose its length.
            .header("Accept-Encoding", "identity")
        if (range != null) builder.header("Range", range)
        if (range != null && ifRange != null) builder.header("If-Range", ifRange)
        return upstream().newCall(builder.build())
    }

    private fun respond(output: OutputStream, code: Int, reason: String, body: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        output.write(
            ("HTTP/1.1 $code $reason\r\nContent-Type: text/plain\r\nContent-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        output.write(bytes)
        output.flush()
    }

    /** The request line and headers, or null if the peer sent something else. */
    private fun readHead(input: InputStream): String? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (head.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = if (b == END_OF_HEAD[matched].toInt()) matched + 1 else if (b == '\r'.code) 1 else 0
            if (matched == END_OF_HEAD.size) return head.toString(Charsets.ISO_8859_1.name())
        }
        return null
    }

    private fun String.printable(): String = filter { it >= ' ' && it != '\u007f' }

    private fun rangeStart(contentRange: String): Long? =
        contentRange.substringAfter("bytes ", "").substringBefore('-').trim().toLongOrNull()

    /** Where a token leads, and what it is a stream of. */
    private class Target(val url: String, val group: String?)

    /** Unguessable names for upstream URLs, stable per URL so an unchanged queue stays unchanged. */
    private class TokenTable {
        private val byUrl = object : LinkedHashMap<String, String>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                val drop = size > MAX_TOKENS
                if (drop && eldest != null) byToken.remove(eldest.value)
                return drop
            }
        }
        private val byToken = HashMap<String, Target>()
        private val random = SecureRandom()

        @Synchronized
        fun tokenFor(url: String, group: String?): String = byUrl.getOrPut(url) {
            ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
                .also { byToken[it] = Target(url, group) }
        }

        @Synchronized
        fun targetFor(token: String): Target? = byToken[token]?.also { byUrl[it.url] } // touch: still in use
    }

    companion object {
        /** What the player is told when the rule is closed. Unassigned in HTTP, so it can mean only this. */
        const val GATE_STATUS = 470

        private const val HOST = "127.0.0.1"
        private const val STREAM_PATH = "/s/"
        private const val SELF_TEST_PATH = "/selftest"
        private const val SELF_TEST_BODY = "amp"
        private const val SELF_TEST_TIMEOUT_MS = 4_000
        private const val CLIENT_READ_TIMEOUT_MS = 15_000
        private const val BACKLOG = 16
        private const val COPY_BYTES = 64 * 1024
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val MAX_TOKENS = 4_000
        private const val MAX_PICKUPS = 3
        private val PICKUP_WAIT_MS = longArrayOf(400L, 1_200L, 3_000L)
        /** How much of the stream's start and end to remember for checking a splice. */
        private const val OVERLAP_BYTES = 16 * 1024
        /** Read-ahead on an unmetered link; App hands in less for a metered one. */
        const val READ_AHEAD_BYTES = 1024 * 1024
        const val READ_AHEAD_METERED_BYTES = 512 * 1024
        private const val POLL_MS = 200L
        private const val FEEDER_JOIN_MS = 2_000L
        private val END = ByteArray(0)
        private val PASSED_ON = listOf("Content-Type", "Content-Range", "Accept-Ranges")
        private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
        private val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        private val END_OF_HEAD = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}

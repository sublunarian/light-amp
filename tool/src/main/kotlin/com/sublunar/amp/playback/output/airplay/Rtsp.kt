package com.sublunar.amp.playback.output.airplay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The control half of AirPlay 1: RTSP over one TCP connection, a request
 * and its answer at a time. The sound goes beside it, over UDP (see
 * [RaopSession]).
 */
internal class Rtsp(
    private val socket: Socket,
    private val userAgent: String,
    /** Sent with every request: names this sender's remote control (see [DacpRemote]), never as anything it isn't. */
    val clientInstance: String,
) {
    private var input: InputStream = BufferedInputStream(socket.getInputStream())
    private var output: OutputStream = socket.getOutputStream()
    private var cseq = 0

    /** The session id the receiver gave at SETUP, sent with everything after. */
    @Volatile
    var session: String? = null

    /** What a speaker sends back with each call to the remote, so a call is known to be about this session. */
    val activeRemote: String = (randomInt().toLong() and 0xffffffffL).toString()

    /** Requests given up on while waiting, whose late answers are still to come. */
    private var abandoned = 0

    val localAddress: String get() = socket.localAddress.hostAddress.orEmpty()

    val isClosed: Boolean get() = socket.isClosed

    /** One request and its response. Throws on a broken connection or a reply that isn't RTSP. */
    @Synchronized
    fun request(
        method: String,
        uri: String,
        headers: List<Pair<String, String>> = emptyList(),
        contentType: String? = null,
        body: ByteArray? = null,
    ): RtspResponse {
        cseq++
        val all = ArrayList<Pair<String, String>>()
        all += "CSeq" to cseq.toString()
        all += headers
        all += "User-Agent" to userAgent
        all += "Client-Instance" to clientInstance
        all += "DACP-ID" to clientInstance
        all += "Active-Remote" to activeRemote
        session?.let { all += "Session" to it }
        val text = requestText(method, uri, all, contentType, body?.size ?: 0)
        output.write(text.toByteArray(Charsets.UTF_8))
        if (body != null) output.write(body)
        output.flush()
        // The late answer to a request given up on is passed over, so it
        // can't be taken for this one's. Only then: some speakers don't
        // number their answers as they should, and are taken at their word.
        while (true) {
            val reply = try {
                readResponse(input)
            } catch (e: java.net.SocketTimeoutException) {
                abandoned++
                throw e
            }
            val said = reply.headers["cseq"]?.trim()?.toIntOrNull()
            if (abandoned > 0 && said != null && said < cseq) {
                abandoned--
                continue
            }
            return reply
        }
    }



    /**
     * A plain HTTP request on the same connection, as AirPlay 2 pairs over:
     * only the headers given, the client name and the length.
     */
    @Synchronized
    fun http(path: String, headers: List<Pair<String, String>>, contentType: String, body: ByteArray): RtspResponse {
        val all = headers + ("User-Agent" to userAgent)
        output.write(requestText("POST", path, all, contentType, body.size, "HTTP/1.1").toByteArray(Charsets.UTF_8))
        output.write(body)
        output.flush()
        return readResponse(input)
    }

    /**
     * Everything from here on goes encrypted both ways, as AirPlay 2 does
     * once pair-verify has given the keys.
     */
    @Synchronized
    fun encrypt(outKey: ByteArray, inKey: ByteArray) {
        output = HapOutputStream(socket.getOutputStream(), outKey)
        input = BufferedInputStream(HapInputStream(socket.getInputStream(), inKey))
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun connect(host: String, port: Int, userAgent: String, dacpId: String, timeoutMs: Int): Rtsp {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            return Rtsp(socket, userAgent, dacpId)
        }

        internal fun requestText(
            method: String,
            uri: String,
            headers: List<Pair<String, String>>,
            contentType: String?,
            contentLength: Int,
            protocol: String = "RTSP/1.0",
        ): String = buildString {
            append(method).append(' ').append(uri).append(' ').append(protocol).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            if (contentLength > 0) append("Content-Length: ").append(contentLength).append("\r\n")
            append("\r\n")
        }

        /** A status line, headers (names lower-cased), and a body as long as Content-Length says. */
        internal fun readResponse(input: InputStream): RtspResponse {
            val message = readMessage(input)
            val parts = message.firstLine.split(' ', limit = 3)
            if (parts.size < 2 || !(parts[0].startsWith("RTSP/") || parts[0].startsWith("HTTP/"))) throw IOException("not an RTSP reply")
            val code = parts[1].toIntOrNull() ?: throw IOException("not an RTSP status")
            return RtspResponse(code, parts.getOrElse(2) { "" }, message.headers, message.body)
        }

        /** Any RTSP or HTTP message, request or reply: its first line, headers (names lower-cased) and body. */
        internal fun readMessage(input: InputStream): Message {
            val first = readLine(input) ?: throw IOException("connection closed")
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("connection closed")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length.coerceIn(0, MAX_BODY))
            var read = 0
            while (read < body.size) {
                val n = input.read(body, read, body.size - read)
                if (n < 0) throw IOException("connection closed")
                read += n
            }
            return Message(first, headers, body)
        }

        internal class Message(val firstLine: String, val headers: Map<String, String>, val body: ByteArray)

        private fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.UTF_8.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
                if (line.size() > MAX_LINE) throw IOException("an RTSP line too long")
            }
            return line.toString(Charsets.UTF_8.name())
        }

        private const val MAX_LINE = 8192
        private const val MAX_BODY = 1 shl 20
    }
}

internal class RtspResponse(
    val status: Int,
    val reason: String,
    /** Names lower-cased. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val ok: Boolean get() = status in 200..299
}

private val random = java.security.SecureRandom()

internal fun randomInt(): Int = random.nextInt()

internal fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }

/**
 * AirPlay 2's encrypted framing: up to 1024 bytes at a time, each block
 * sent as its length (2 bytes, little-endian) and the block sealed with
 * ChaCha20-Poly1305 under a counting nonce, the length as associated data.
 * Written out on [flush], so a request goes as one run of blocks.
 */
internal class HapOutputStream(private val out: OutputStream, private val key: ByteArray) : OutputStream() {
    private val pending = ByteArrayOutputStream()
    private var counter = 0L

    override fun write(b: Int) = pending.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) = pending.write(b, off, len)

    override fun flush() {
        val plain = pending.toByteArray()
        pending.reset()
        var at = 0
        while (at < plain.size) {
            val size = minOf(HAP_BLOCK, plain.size - at)
            val length = byteArrayOf(size.toByte(), (size shr 8).toByte())
            val sealed = HapCrypto.seal(key, HapCrypto.counterNonce(counter++), plain.copyOfRange(at, at + size), length)
            out.write(length)
            out.write(sealed)
            at += size
        }
        out.flush()
    }

    override fun close() = out.close()
}

/** The reading half of [HapOutputStream]'s framing. */
internal class HapInputStream(private val input: InputStream, private val key: ByteArray) : InputStream() {
    private var block = ByteArray(0)
    private var at = 0
    private var counter = 0L

    override fun read(): Int {
        if (!fill()) return -1
        return block[at++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, block.size - at)
        System.arraycopy(block, at, b, off, n)
        at += n
        return n
    }

    private fun fill(): Boolean {
        while (at >= block.size) {
            val length = ByteArray(2)
            if (!readFully(length)) return false
            val size = (length[0].toInt() and 0xff) or ((length[1].toInt() and 0xff) shl 8)
            if (size > HAP_BLOCK) throw IOException("an encrypted block too long")
            val sealed = ByteArray(size + 16)
            if (!readFully(sealed)) throw IOException("connection closed")
            block = HapCrypto.open(key, HapCrypto.counterNonce(counter++), sealed, length)
            at = 0
        }
        return true
    }

    private fun readFully(into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw IOException("connection closed")
            }
            read += n
        }
        return true
    }
}

private const val HAP_BLOCK = 1024

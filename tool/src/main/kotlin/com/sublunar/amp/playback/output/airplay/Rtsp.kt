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
    private val input: InputStream = BufferedInputStream(socket.getInputStream())
    private val output: OutputStream = socket.getOutputStream()
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
        ): String = buildString {
            append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            if (contentType != null) append("Content-Type: ").append(contentType).append("\r\n")
            if (contentLength > 0) append("Content-Length: ").append(contentLength).append("\r\n")
            append("\r\n")
        }

        /** A status line, headers (names lower-cased), and a body as long as Content-Length says. */
        internal fun readResponse(input: InputStream): RtspResponse {
            val status = readLine(input) ?: throw IOException("connection closed")
            val parts = status.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("RTSP/")) throw IOException("not an RTSP reply")
            val code = parts[1].toIntOrNull() ?: throw IOException("not an RTSP status")
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
            return RtspResponse(code, parts.getOrElse(2) { "" }, headers, body)
        }

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

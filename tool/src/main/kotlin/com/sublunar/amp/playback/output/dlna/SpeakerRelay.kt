package com.sublunar.amp.playback.output.dlna

import com.sublunar.amp.log.logName
import com.sublunar.amp.log.loggableWord
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The door a DLNA speaker fetches its music through, so it is never given the
 * server's own address. That address has the login in it, and UPnP is plain
 * text: anyone on the network could read it going by, or ask the speaker for it
 * afterwards. The speaker gets `http://phone:port/c/<random>` instead, and the
 * login stays on this phone.
 *
 * **Who gets in.** Only the speaker being cast to, by its address, and only for
 * addresses made for it. Everything is forgotten when the cast ends, and the
 * door closes.
 *
 * **Not the player's door.** StreamProxy looks after one well-behaved client:
 * it retires a track's older request and picks a broken stream up again.
 * A speaker opens several connections to one song at once, to read its end
 * while it plays the start, and Plex ends a transcode when a second request
 * asks for the same session, where a pickup would start the two fighting. So
 * this one changes nothing: what the speaker asks is what the server is asked,
 * and when the server breaks off, so does the stream, as it would have direct.
 */
internal class SpeakerRelay(
    private val upstream: () -> OkHttpClient,
    private val allowed: () -> Boolean,
    private val log: (String) -> Unit,
    private val localAddressFacing: (InetAddress) -> InetAddress? = ::routeTo,
    /** Whether this app shut every connection at or after a moment (monotonic ms) — see NetworkGate.slam. */
    private val shutSince: (Long) -> Boolean = { false },
) {
    private class Connection(val socket: Socket) {
        @Volatile var call: Call? = null

        @Volatile var cut = false

        fun cutOff() {
            cut = true
            call?.cancel()
            runCatching { socket.close() }
        }
    }

    private var door: ServerSocket? = null
    private var speakerHost: String? = null
    private var speaker: InetAddress? = null
    private var saidStranger = false
    private val connections = HashSet<Connection>()
    private val random = SecureRandom()
    private val urls = HashMap<String, String>()

    // The same address for the same stream: a cast knows a stream by its address.
    private val tokens = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            val drop = size > MAX_ROUTES
            if (drop && eldest != null) urls.remove(eldest.value)
            return drop
        }
    }

    /**
     * The address [host] is to fetch [url] from, or null when no door can be
     * opened: then there is no cast, and the server's address is never the
     * answer instead. One speaker at a time, so a new one replaces the last.
     */
    fun address(url: String, host: String): String? {
        val target = runCatching { InetAddress.getByName(host) }.getOrNull()
        val local = target?.let(localAddressFacing)
        if (target == null || local == null) {
            log("relay: no route to the speaker")
            return null
        }
        var stale = emptyList<Connection>()
        val address = synchronized(this) {
            if (host != speakerHost || target != speaker) stale = forget()
            speakerHost = host
            speaker = target
            openDoor(local)?.let { open ->
                val token = tokens.getOrPut(url) { newToken().also { urls[it] = url } }
                "http://${literal(local)}:${open.localPort}$PATH$token"
            }
        }
        cutOff(stale)
        return address
    }

    /** The cast to [host] is over: its addresses stop working, its streams stop, and the door closes. */
    fun release(host: String) {
        val open = synchronized(this) {
            if (host != speakerHost) return
            speakerHost = null
            speaker = null
            forget()
        }
        cutOff(open)
    }

    private fun forget(): List<Connection> {
        tokens.clear()
        urls.clear()
        saidStranger = false
        runCatching { door?.close() }
        door = null
        return connections.toList().also { connections.clear() }
    }

    // On a thread of its own: this can be asked on the main thread, and closing
    // a socket is the network's business.
    private fun cutOff(open: List<Connection>) {
        if (open.isNotEmpty()) thread(isDaemon = true, name = "amp-relay-cut") { open.forEach(Connection::cutOff) }
    }

    private fun newToken(): String =
        ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun openDoor(local: InetAddress): ServerSocket? {
        door?.takeIf { !it.isClosed && it.inetAddress == local }?.let { return it }
        runCatching { door?.close() }
        door = null
        return try {
            ServerSocket(0, BACKLOG, local).also { socket ->
                door = socket
                thread(isDaemon = true, name = "amp-relay") { acceptOn(socket) }
            }
        } catch (e: IOException) {
            log("relay: can't open: ${e.logName()}")
            null
        }
    }

    private fun acceptOn(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                // Closed here, or the network went from under it: the next
                // address asked for opens another.
                runCatching { socket.close() }
                return
            }
            val connection = admit(client)
            if (connection == null) {
                runCatching { client.close() }
            } else {
                thread(isDaemon = true, name = "amp-relay-stream") { serve(connection) }
            }
        }
    }

    private fun admit(client: Socket): Connection? = synchronized(this) {
        if (client.inetAddress != speaker) {
            if (!saidStranger) log("relay: turned away a device that isn't the speaker")
            saidStranger = true
            return null
        }
        if (connections.size >= MAX_CONNECTIONS) return null
        Connection(client).also(connections::add)
    }

    private fun serve(connection: Connection) {
        val client = connection.socket
        try {
            client.soTimeout = HEAD_TIMEOUT_MS
            client.tcpNoDelay = true
            val head = readHead(client.getInputStream().buffered()) ?: return
            val output = BufferedOutputStream(client.getOutputStream(), COPY_BYTES)
            val request = speakerRequest(head) ?: return reply(output, 400, "Bad Request")
            answer(request, connection, output)
        } catch (_: Exception) {
            // The speaker hung up, as it does on every skip and seek.
        } finally {
            synchronized(this) { connections.remove(connection) }
            runCatching { client.close() }
        }
    }

    private fun answer(request: SpeakerRequest, connection: Connection, output: OutputStream) {
        if (request.method != "GET" && request.method != "HEAD") return reply(output, 405, "Method Not Allowed")
        val url = synchronized(this) {
            // Read from [tokens] too, which marks the stream as still in use.
            request.token?.let(urls::get)?.also { tokens[it] }
        } ?: return reply(output, 404, "Not Found")
        if (!allowed()) return reply(output, 503, "Service Unavailable")
        val call = upstream().newCall(upstreamRequest(url, request))
        connection.call = call
        if (connection.cut) return call.cancel()
        val response = try {
            call.execute()
        } catch (e: IOException) {
            log("relay: server unreachable: ${e.logName()}")
            return reply(output, 502, "Bad Gateway")
        }
        response.use { relay(it, request, connection, output) }
    }

    private fun relay(response: Response, request: SpeakerRequest, connection: Connection, output: OutputStream) {
        val headOnly = request.method == "HEAD"
        // A server's refusal goes on as its status alone: its words may quote the request.
        if (headOnly || !response.isSuccessful) {
            val length = if (response.isSuccessful) response.header("Content-Length") else "0"
            output.write(responseHead(response, length?.let { "Content-Length: $it\r\n" }.orEmpty()))
            output.flush()
            if (!response.isSuccessful) log("relay: server answered ${response.code}")
            return
        }
        val length = response.body.contentLength()
        // HTTP/1.0 has no chunks: there the end of a stream of unknown length is the connection closing.
        val chunked = length < 0 && !request.http10
        val framing = when {
            length >= 0 -> "Content-Length: $length\r\n"
            chunked -> "Transfer-Encoding: chunked\r\n"
            else -> ""
        }
        output.write(responseHead(response, framing))
        output.flush()
        val startedNs = System.nanoTime()
        val startedMs = startedNs / 1_000_000
        var sent = 0L
        // Which side ended a stream is the one thing a speaker that stops early can't say.
        val end = try {
            sent = copy(response.body.byteStream(), output, chunked) { sent = it }
            "the server's end"
        } catch (e: SpeakerGone) {
            if (connection.cut) "the cast ending" else "the speaker hanging up"
        } catch (e: IOException) {
            when {
                connection.cut -> "the cast ending"
                // Amp's own network wall closes every socket when it reads the link as forbidden.
                shutSince(startedMs) -> "Amp shutting every connection (${e.logName()})"
                else -> "the server breaking off (${e.logName()}${e.systemPhrase()})"
            }
        }
        val framed = when {
            length >= 0 -> "sized"
            chunked -> "chunked"
            else -> "unframed"
        }
        log(
            "relay: ${response.code} $framed${if (request.range != null) ", ranged" else ""} ended by $end " +
                "after ${(System.nanoTime() - startedNs) / 1_000_000_000} s, ${sent / 1024} KB",
        )
    }

    // A socket's own words are a fixed phrase from the system ("Connection reset",
    // "Software caused connection abort") that tells who ended it; never an address.
    private fun IOException.systemPhrase(): String =
        if (this is SocketException) ": ${loggableWord(message)}" else ""

    /** A write to the speaker failed: it hung up, or was cut off here. */
    private class SpeakerGone : IOException()

    /** [body] to [output] until the server ends it; the bytes sent, reported to [progress] as they go. */
    private fun copy(body: InputStream, output: OutputStream, chunked: Boolean, progress: (Long) -> Unit): Long {
        val buffer = ByteArray(COPY_BYTES)
        var sent = 0L
        fun write(action: () -> Unit) = try {
            action()
        } catch (_: IOException) {
            throw SpeakerGone()
        }
        while (true) {
            val read = body.read(buffer)
            if (read < 0) break
            write {
                if (chunked) output.write("${read.toString(16)}\r\n".toByteArray(Charsets.ISO_8859_1))
                output.write(buffer, 0, read)
                if (chunked) output.write(CRLF)
                output.flush()
            }
            sent += read
            progress(sent)
        }
        write {
            if (chunked) output.write(LAST_CHUNK)
            output.flush()
        }
        return sent
    }

    private fun upstreamRequest(url: String, request: SpeakerRequest): Request =
        Request.Builder().url(url)
            // Compressed audio would lose its length.
            .header("Accept-Encoding", "identity")
            .apply {
                request.range?.let { header("Range", it) }
                // A server lists its players by this, as it did with the speaker asking.
                request.userAgent?.let { header("User-Agent", it) }
                if (request.method == "HEAD") head()
            }
            .build()

    private fun responseHead(response: Response, framing: String): ByteArray {
        val head = StringBuilder("HTTP/1.1 ${response.code} ${reason(response.code)}\r\n")
        for (name in PASSED_ON) response.header(name)?.let { head.append("$name: ${it.printable()}\r\n") }
        return head.append(framing).append("Connection: close\r\n\r\n").toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun reply(output: OutputStream, code: Int, reason: String) {
        output.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun reason(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        else -> "Relayed"
    }

    private fun String.printable(): String = filter { it in ' '..'~' }

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

    private fun literal(address: InetAddress): String {
        val text = address.hostAddress.orEmpty().substringBefore('%')
        return if (':' in text) "[$text]" else text
    }

    private companion object {
        const val PATH = "/c/"
        const val TOKEN_BYTES = 16
        const val BACKLOG = 8

        /** A song, the next one, and what a speaker opens beside them to read their ends. */
        const val MAX_CONNECTIONS = 8

        /** A queue's worth of streams, the oldest forgotten first. */
        const val MAX_ROUTES = 256
        const val HEAD_TIMEOUT_MS = 10_000
        const val MAX_HEAD_BYTES = 8 * 1024
        const val COPY_BYTES = 64 * 1024
        val PASSED_ON = listOf("Content-Type", "Content-Range", "Accept-Ranges")
        val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
        val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val END_OF_HEAD = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    }
}

/** What a speaker asked for. [token] is null for a path that isn't one of the relay's. */
internal class SpeakerRequest(
    val method: String,
    val token: String?,
    val http10: Boolean,
    val range: String?,
    val userAgent: String?,
)

/** The request in [head], or null when it isn't one. A query is ignored: a restart adds one to make a new address. */
internal fun speakerRequest(head: String): SpeakerRequest? {
    val lines = head.split("\r\n")
    val parts = lines.first().split(' ')
    if (parts.size != 3) return null
    fun header(name: String) = lines.drop(1)
        .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()
        ?.takeIf { value -> value.isNotEmpty() && value.all { it in ' '..'~' } }
    // Some embedded clients send the whole address on the request line.
    val at = parts[1].indexOf("/c/")
    return SpeakerRequest(
        method = parts[0],
        token = if (at < 0) null else parts[1].substring(at + 3).substringBefore('?'),
        http10 = parts[2] == "HTTP/1.0",
        range = header("Range"),
        userAgent = header("User-Agent"),
    )
}

/** This phone's address on the way to [speaker]. Connecting a UDP socket picks the route and sends nothing. */
private fun routeTo(speaker: InetAddress): InetAddress? = runCatching {
    DatagramSocket().use {
        it.connect(speaker, DISCARD_PORT)
        it.localAddress
    }
}.getOrNull()?.takeUnless { it.isAnyLocalAddress }

private const val DISCARD_PORT = 9

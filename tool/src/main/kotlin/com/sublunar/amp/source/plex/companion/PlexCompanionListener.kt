package com.sublunar.amp.source.plex.companion

import com.sublunar.amp.data.NetworkGate
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The other half of a Companion subscription: a small HTTP server a player
 * pushes its timeline to, which answers 200 and hands the body on. Plex's own
 * model is push: a subscribed player sends its state as it changes, where a
 * poll blocks the player's single connection and hears nothing between beats.
 *
 * Polling stays the fallback in [PlexCompanionOutput], for a player that won't
 * subscribe or a network that won't route back here.
 */
class PlexCompanionListener(private val onTimeline: (String) -> Unit) {

    private val server = AtomicReference<ServerSocket?>(null)
    private var job: Job? = null

    /** This phone's address on the LAN, for the player to answer at. */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    /** Open the port and start accepting. Idempotent; returns the port. */
    fun start(scope: CoroutineScope): Int? {
        server.get()?.let { return it.localPort }
        // A listening socket binds every interface, the cellular one included,
        // so none is opened with the wall closed — see NetworkGate.
        if (!NetworkGate.isOpen()) return null
        val opened = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        server.set(opened)
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !opened.isClosed) {
                val socket = runCatching { opened.accept() }.getOrNull() ?: break
                // Closed since the port was opened: hang up unread, unanswered.
                if (NetworkGate.isOpen()) runCatching { serve(socket) }
                runCatching { socket.close() }
            }
            // Let go of a port nobody is answering on any more, so the next
            // start opens a new one rather than handing this one out.
            runCatching { opened.close() }
            server.compareAndSet(opened, null)
        }
        return opened.localPort
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { server.getAndSet(null)?.close() }
    }

    /**
     * Read one request, answer 200, and pass the timeline on.
     *
     * A player sends its state as the request body, or as the query of a GET:
     * both are read the same way, since all this looks for is the XML.
     */
    private fun serve(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val text = readHttpRequest(BufferedInputStream(socket.getInputStream()))
        // Answer first: a player that doesn't hear 200 promptly drops the subscription.
        socket.getOutputStream().apply {
            write(RESPONSE.toByteArray())
            flush()
        }
        val body = text.substringAfter("\r\n\r\n", missingDelimiterValue = "")
        val payload = if (body.contains("<MediaContainer")) body else text
        if (payload.contains("<Timeline")) onTimeline(payload)
    }

    private companion object {
        const val READ_TIMEOUT_MS = 2_000
        const val RESPONSE = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }
}

/**
 * One HTTP request off [input]: the head up to the blank line that ends it,
 * then as many bytes of body as its `Content-Length` says. Never to the end
 * of the stream: a player keeps its side open for the answer, so that read
 * would wait out the timeout and the push would go unanswered.
 */
internal fun readHttpRequest(input: InputStream): String {
    val head = ByteArrayOutputStream()
    var lastFour = 0
    while (lastFour != HEAD_END && head.size() < MAX_HEAD_BYTES) {
        val b = input.read()
        if (b < 0) break
        head.write(b)
        lastFour = (lastFour shl 8) or b
    }
    val headText = head.toString(Charsets.UTF_8.name())
    val length = CONTENT_LENGTH.find(headText)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtMost(MAX_BODY_BYTES) ?: 0
    val body = ByteArray(length)
    var read = 0
    while (read < length) {
        val n = input.read(body, read, length - read)
        if (n < 0) break
        read += n
    }
    return headText + String(body, 0, read, Charsets.UTF_8)
}

/** `\r\n\r\n` as the last four bytes read. */
private const val HEAD_END = 0x0D0A0D0A
private const val MAX_HEAD_BYTES = 16 * 1024
private const val MAX_BODY_BYTES = 256 * 1024
private val CONTENT_LENGTH = Regex("""(?im)^content-length:\s*(\d+)""")

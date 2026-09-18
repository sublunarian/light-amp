package com.sublunar.amp.data

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The other half of a Companion subscription: somewhere for a player to push
 * its timeline to.
 *
 * Plex's own model is push, not poll — a controller subscribes and the player
 * sends its state as it changes. That needs the controller to be reachable,
 * which is all this is: a small HTTP server that accepts a timeline, answers
 * 200, and hands the body on. It replaces asking a player the same question
 * every second, which is chatty, blocks the player's single connection while
 * it happens, and tells us nothing between beats.
 *
 * Polling remains the fallback in [com.sublunar.amp.playback.PlaybackController]:
 * a player that won't subscribe, or a network that won't route back here, must
 * still work.
 */
class PlexCompanionListener(private val onTimeline: (String) -> Unit) {

    private var server: ServerSocket? = null
    private var job: Job? = null

    /** The port a player should push to, or null when it couldn't be opened. */
    val port: Int? get() = server?.localPort

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
        if (server != null) return port
        // A listening socket binds every interface, the cellular one included.
        // With the wall closed there is no cast to steer, so there is nothing
        // to listen for — see NetworkGate.
        if (!NetworkGate.isOpen()) return null
        val opened = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        server = opened
        job = scope.launch(Dispatchers.IO) {
            while (isActive && !opened.isClosed) {
                val socket = runCatching { opened.accept() }.getOrNull() ?: break
                // Closed since the port was opened: hang up unread, unanswered.
                if (NetworkGate.isOpen()) runCatching { serve(socket) }
                runCatching { socket.close() }
            }
        }
        return opened.localPort
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { server?.close() }
        server = null
    }

    /**
     * Read one request, answer 200, and pass the timeline on.
     *
     * A player sends its state as the request body, or as the query of a GET
     * when it has nothing to post — both are read the same way, since all this
     * looks for is the XML.
     */
    private fun serve(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val text = socket.getInputStream().readBytes().decodeToString()
        // Answer first: a player that doesn't hear 200 promptly gives up on
        // the subscription, and parsing costs time it isn't waiting for.
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

package com.sublunar.amp.playback.output.airplay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** What a speaker's own controls asked Amp to do. */
internal sealed interface DacpCommand {
    /** The speaker's level moved — its knob, its remote — to [db], AirPlay's −30 to 0 (−144 is silence). */
    data class SpeakerVolume(val db: Double) : DacpCommand
    data object Play : DacpCommand
    data object Pause : DacpCommand
    data object PlayPause : DacpCommand
    data object Next : DacpCommand
    data object Previous : DacpCommand
    data class VolumeStep(val up: Boolean) : DacpCommand
}

/**
 * Amp's remote control, as AirPlay 1 senders offer one (DACP): a speaker's
 * knob and remote buttons reach Amp, so its fader and play state follow.
 * Every request Amp sends a speaker carries [id] and the session's
 * Active-Remote; the speaker finds this server by the name [DacpAnnouncer]
 * announces and calls it with that Active-Remote, which [accepts] checks.
 *
 * Only a bonus: speakers differ in what they send, and many send nothing,
 * so nothing in Amp waits for it. One seen (Denon AVR-X1500H) reports its
 * level when the whole number on its display changes, and its play/pause
 * key as `discrete-pause`, for pausing and resuming alike.
 */
internal class DacpRemote(
    private val log: (String) -> Unit,
    private val accepts: (activeRemote: String?) -> Boolean,
    private val volumeNow: () -> Float,
    /** With the Active-Remote the call carried, which names the session (and so the speaker). */
    private val onCommand: (activeRemote: String, DacpCommand) -> Unit,
) {
    val id: String = DacpRecords.newId()

    private var server: ServerSocket? = null
    private var announcer: DacpAnnouncer? = null
    private var announcedOn: Inet4Address? = null

    /** Serve and announce on the network [local] is on; nothing new if already there. */
    @Synchronized
    fun start(local: InetAddress) {
        if (local !is Inet4Address) return
        if (announcedOn == local && server?.isClosed == false) return
        stop()
        val listening = listen(InetAddress.getByName("0.0.0.0")) ?: return
        announcer = DacpAnnouncer.start(id, listening.localPort, local, log)
        announcedOn = local
        log("remote: listening on ${listening.localPort}${if (announcer == null) ", not announced" else ""}")
    }

    @Synchronized
    fun stop() {
        announcer?.stop()
        announcer = null
        announcedOn = null
        server?.let { runCatching { it.close() } }
        server = null
    }

    /** Serve on [at]; the usual port if it's free, any other if not, since the announcement names it. */
    internal fun listen(at: InetAddress): ServerSocket? {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            try {
                socket.bind(InetSocketAddress(at, PORT))
            } catch (e: IOException) {
                socket.bind(InetSocketAddress(at, 0))
            }
        } catch (e: IOException) {
            runCatching { socket.close() }
            log("remote: can't listen (${e::class.simpleName})")
            return null
        }
        server = socket
        Thread({ accept(socket) }, "AmpAirPlay-dacp").apply { isDaemon = true }.start()
        return socket
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val s = try {
                server.accept()
            } catch (e: IOException) {
                if (server.isClosed) return
                continue
            }
            Thread({ serve(s) }, "AmpAirPlay-dacp-conn").apply { isDaemon = true }.start()
        }
    }

    /** One connection: a speaker may send several requests on it, one at a time. */
    private fun serve(s: Socket) {
        try {
            s.use { socket ->
                socket.soTimeout = IDLE_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (true) {
                    val request = readRequest(input) ?: return
                    val activeRemote = request.headers["active-remote"]
                    val allowed = activeRemote != null && accepts(activeRemote)
                    val target = request.target
                    output.write(if (allowed) reply(target) else FORBIDDEN)
                    output.flush()
                    if (allowed) parse(target)?.let { command ->
                        log("remote: $command")
                        onCommand(activeRemote!!, command)
                    }
                    if (request.headers["connection"].equals("close", ignoreCase = true)) return
                }
            }
        } catch (e: SocketTimeoutException) {
            // Idle: the speaker opens another when it next has something to say.
        } catch (e: Exception) {
            log("remote: connection ended (${e::class.simpleName})")
        }
    }

    private fun reply(target: String): ByteArray {
        if (target.startsWith("$CTRL/getproperty") && target.contains("dmcp.volume")) {
            val body = dmap("cmgt", dmapInt("mstt", 200) + dmapInt("cmvo", Math.round(volumeNow() * 100f)))
            return head("200 OK", body.size) + body
        }
        return head("204 No Content", 0)
    }

    internal class Request(val target: String, val headers: Map<String, String>)

    companion object {
        const val PORT = 3689
        private const val CTRL = "/ctrl-int/1"
        private const val IDLE_MS = 60_000
        private const val MAX_LINE = 8192

        private val FORBIDDEN = head("403 Forbidden", 0)

        private fun head(status: String, length: Int): ByteArray =
            ("HTTP/1.1 $status\r\nDAAP-Server: Amp\r\nContent-Type: application/x-dmap-tagged\r\nContent-Length: $length\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1)

        /** What a request path asks for, or null for anything Amp doesn't act on. */
        internal fun parse(target: String): DacpCommand? {
            val path = target.substringBefore('?')
            if (!path.startsWith("$CTRL/")) return null
            val query = target.substringAfter('?', "")
            return when (path.removePrefix("$CTRL/")) {
                "setproperty" -> query.split('&').firstNotNullOfOrNull { part ->
                    val (k, v) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    if (k == "dmcp.device-volume") v.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { DacpCommand.SpeakerVolume(it) } else null
                }
                "play" -> DacpCommand.Play
                "pause", "stop" -> DacpCommand.Pause
                // Speakers send discrete-pause for their one play/pause key, to resume as well.
                "playpause", "discrete-pause" -> DacpCommand.PlayPause
                "nextitem" -> DacpCommand.Next
                "previtem" -> DacpCommand.Previous
                "volumeup" -> DacpCommand.VolumeStep(up = true)
                "volumedown" -> DacpCommand.VolumeStep(up = false)
                else -> null
            }
        }

        internal fun readRequest(input: InputStream): Request? {
            var line = readLine(input) ?: return null
            while (line.isEmpty()) line = readLine(input) ?: return null
            val target = line.split(' ').getOrElse(1) { "" }
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(input) ?: return null
                if (h.isEmpty()) break
                val c = h.indexOf(':')
                if (c > 0) headers[h.substring(0, c).trim().lowercase()] = h.substring(c + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            repeat(length.coerceIn(0, MAX_LINE)) { if (input.read() < 0) return null }
            return Request(target, headers)
        }

        private fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) line.write(b)
                if (line.size() > MAX_LINE) throw IOException("line too long")
            }
            return line.toString(Charsets.ISO_8859_1.name())
        }

        internal fun dmap(tag: String, value: ByteArray): ByteArray = tag.toByteArray(Charsets.US_ASCII) + int32(value.size) + value

        private fun dmapInt(tag: String, v: Int): ByteArray = dmap(tag, int32(v))

        private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }
}

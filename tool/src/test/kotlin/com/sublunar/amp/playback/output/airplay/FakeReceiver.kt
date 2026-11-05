package com.sublunar.amp.playback.output.airplay

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An AirPlay 1 receiver on the loopback, for tests: it answers RTSP as a
 * speaker would, asks the sender's time the way shairport-sync does, and
 * keeps everything it's sent. No sound anywhere.
 */
internal class FakeReceiver(
    /** Refuse ANNOUNCE with 403 until an auth-setup has come, as an AirPort Express on 7.8 does. */
    private val wantsAuthSetup: Boolean = false,
    /** Answer OPTIONS with 501, as some receivers do. */
    private val noOptions: Boolean = false,
) : Closeable {
    class Request(val method: String, val uri: String, val headers: Map<String, String>, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    class Audio(val seq: Int, val rtp: Long, val marker: Boolean, val payload: ByteArray, val atNanos: Long)

    class Sync(val first: Boolean, val rtpLessLatency: Long, val ntp: Long, val rtp: Long)

    val requests = CopyOnWriteArrayList<Request>()
    val audio = CopyOnWriteArrayList<Audio>()
    val syncs = CopyOnWriteArrayList<Sync>()
    val timingReplies = CopyOnWriteArrayList<ByteArray>()
    val resends = CopyOnWriteArrayList<ByteArray>()

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val audioSocket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val controlSocket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    private val timingSocket = DatagramSocket(0, InetAddress.getLoopbackAddress())

    @Volatile
    private var closed = false

    @Volatile
    private var senderTimingPort = 0

    @Volatile
    private var senderControlPort = 0

    @Volatile
    var authSetupSeen = false
        private set

    val port: Int get() = server.localPort

    val receiver: AirPlayReceiver
        get() = AirPlayReceiver(
            id = "0a0b0c0d0e0f",
            name = "Test Speaker",
            address = "127.0.0.1",
            port = port,
            txt = mapOf("et" to "0,4", "cn" to "0,1", "md" to "0,1,2", "tp" to "UDP"),
        )

    private val threads = listOf(
        Thread { serve() },
        Thread { listenAudio() },
        Thread { listenControl() },
        Thread { askTime() },
    ).onEach {
        it.isDaemon = true
        it.start()
    }

    private fun serve() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            Thread { talk(socket) }.apply { isDaemon = true }.start()
        }
    }

    private fun talk(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(it.getInputStream())
            val output = it.getOutputStream()
            while (!closed) {
                val request = read(input) ?: return
                requests += request
                val cseq = request.headers["cseq"].orEmpty()
                val extra = StringBuilder()
                var status = "200 OK"
                when (request.method) {
                    "POST" -> if (request.uri == "/auth-setup") authSetupSeen = true
                    "ANNOUNCE" -> if (wantsAuthSetup && !authSetupSeen) status = "403 Forbidden"
                    "OPTIONS" -> if (noOptions) status = "501 Not Implemented"
                    "SETUP" -> {
                        val transport = request.headers["transport"].orEmpty()
                        senderControlPort = Regex("control_port=(\\d+)").find(transport)!!.groupValues[1].toInt()
                        senderTimingPort = Regex("timing_port=(\\d+)").find(transport)!!.groupValues[1].toInt()
                        extra.append("Session: 1\r\n")
                        extra.append(
                            "Transport: RTP/AVP/UDP;unicast;mode=record;server_port=${audioSocket.localPort};" +
                                "control_port=${controlSocket.localPort};timing_port=${timingSocket.localPort}\r\n",
                        )
                    }
                    "RECORD" -> extra.append("Audio-Latency: 11025\r\n")
                }
                output.write("RTSP/1.0 $status\r\nCSeq: $cseq\r\n$extra\r\n".toByteArray())
                output.flush()
            }
        }
    }

    private fun read(input: InputStream): Request? {
        val first = line(input) ?: return null
        val parts = first.split(' ')
        val headers = HashMap<String, String>()
        while (true) {
            val l = line(input) ?: return null
            if (l.isEmpty()) break
            val colon = l.indexOf(':')
            headers[l.substring(0, colon).lowercase()] = l.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = input.read(body, at, length - at)
            if (n < 0) return null
            at += n
        }
        return Request(parts[0], parts[1], headers, body)
    }

    private fun line(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun listenAudio() {
        val buffer = ByteArray(4096)
        audioSocket.soTimeout = 200
        while (!closed) {
            val p = DatagramPacket(buffer, buffer.size)
            try {
                audioSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            val b = p.data
            audio += Audio(
                seq = ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff),
                rtp = u32(b, 4),
                marker = b[1].toInt() and 0x80 != 0,
                payload = b.copyOfRange(12, p.length),
                atNanos = System.nanoTime(),
            )
        }
    }

    private fun listenControl() {
        val buffer = ByteArray(4096)
        controlSocket.soTimeout = 200
        while (!closed) {
            val p = DatagramPacket(buffer, buffer.size)
            try {
                controlSocket.receive(p)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            val b = p.data.copyOf(p.length)
            when (b[1].toInt() and 0xff) {
                0xd4 -> syncs += Sync(b[0].toInt() and 0xff == 0x90, u32(b, 4), u64(b, 8), u32(b, 16))
                0xd6 -> resends += b
            }
        }
    }

    /** Ask the sender's time as shairport-sync does: four times 300 ms apart, then every second here. */
    private fun askTime() {
        var n = 0
        val buffer = ByteArray(128)
        timingSocket.soTimeout = 300
        while (!closed) {
            val port = senderTimingPort
            if (port != 0) {
                val request = ByteArray(32)
                request[0] = 0x80.toByte()
                request[1] = 0xd2.toByte()
                request[3] = 7
                // A transmit time the sender must give back as its origin.
                for (i in 0 until 8) request[24 + i] = (0x10 + n + i).toByte()
                timingSocket.send(DatagramPacket(request, 32, InetAddress.getLoopbackAddress(), port))
                try {
                    val p = DatagramPacket(buffer, buffer.size)
                    timingSocket.receive(p)
                    timingReplies += p.data.copyOf(p.length)
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                    return
                }
                n++
            }
            Thread.sleep(if (n < 4) 300 else 1000)
        }
    }

    /** Ask for [count] packets from [seq] again, as a speaker that lost them would. */
    fun askResend(seq: Int, count: Int) {
        val r = ByteArray(8)
        r[0] = 0x80.toByte()
        r[1] = 0xd5.toByte()
        r[3] = 1
        r[4] = (seq ushr 8).toByte()
        r[5] = seq.toByte()
        r[6] = (count ushr 8).toByte()
        r[7] = count.toByte()
        controlSocket.send(DatagramPacket(r, 8, InetAddress.getLoopbackAddress(), senderControlPort))
    }

    /** Everything heard, in sequence order, decoded back to interleaved stereo samples. */
    fun pcm(): ShortArray {
        val decoder = AlacDecoder(AlacConfig(352, 16, 40, 10, 14, 2, 255, 44_100))
        val out = IntArray(352 * 2)
        val ordered = audio.sortedBy { it.seq }
        val pcm = ShortArray(ordered.size * 352 * 2)
        var at = 0
        for (a in ordered) {
            val frames = decoder.decode(a.payload, 0, a.payload.size, out)
            for (i in 0 until frames * 2) pcm[at++] = out[i].toShort()
        }
        return pcm.copyOf(at)
    }

    fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        audioSocket.close()
        controlSocket.close()
        timingSocket.close()
        threads.forEach { it.join(1000) }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)
}

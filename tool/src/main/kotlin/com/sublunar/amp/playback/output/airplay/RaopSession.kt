package com.sublunar.amp.playback.output.airplay

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * One AirPlay 1 session with one speaker: RTSP to set it up and steer it,
 * and three UDP sockets beside — the sound itself, a control channel for
 * sync and for the speaker's requests to send lost packets again, and a
 * timing channel the speaker uses to learn this phone's clock.
 *
 * Plain AirPlay 1 as AirPort Express and iTunes first spoke it, without its
 * optional encryption: Apple Lossless at 44.1 kHz, 352 samples a packet, the
 * speaker playing each packet [latencyFrames] after the moment it was due to
 * be sent. Built from the protocol's public descriptions (the open-source
 * senders and receivers that implement it) and no key of Apple's.
 *
 * Every RTSP request blocks for its answer, so none is made on Main.
 */
internal class RaopSession private constructor(
    val receiver: AirPlayReceiver,
    private val rtsp: Rtsp,
    private val url: String,
    private val log: (String) -> Unit,
) : Closeable {

    private val remote: InetAddress = InetAddress.getByName(receiver.address)
    private val audio = DatagramSocket()
    private val control = DatagramSocket()
    private val timing = DatagramSocket()

    private var serverPort = 0
    private var controlPort = 0

    /**
     * The latency the speaker is told, in frames at 44.1 kHz: how far ahead
     * of its sound the packets run. It plays a packet this plus
     * [RECEIVER_OFFSET_FRAMES] after the moment it was due to be sent.
     */
    var latencyFrames = DEFAULT_LATENCY_FRAMES
        private set

    /** How long after its due moment a packet is heard, in frames. */
    val heardAfterFrames: Int get() = latencyFrames + RECEIVER_OFFSET_FRAMES

    val ssrc: Int = randomInt()

    /** The sequence number and RTP time the next packet sent will carry. */
    @Volatile
    var nextSeq: Int = randomInt() and 0xffff
        private set

    @Volatile
    var nextRtp: Long = randomInt().toLong() and MASK32
        private set

    private val history = arrayOfNulls<ByteArray>(HISTORY)
    private val historySeq = IntArray(HISTORY) { -1 }

    @Volatile
    private var closed = false

    /** When the last RTSP request was made, for the keep-alive. */
    @Volatile
    var lastRequestAt: Long = System.nanoTime()
        private set

    /** When the speaker last asked for the time: it stops asking once it has let the session go. */
    @Volatile
    var lastTimingRequestAt: Long = System.nanoTime()
        private set

    /** What came and went, for the log: time asked, resends asked and sent, packets sent. */
    @Volatile
    var timingAsks = 0
        private set

    @Volatile
    var resendAsks = 0
        private set

    @Volatile
    var resent = 0
        private set

    @Volatile
    var packetsSent = 0L
        private set

    /** Whether it has asked at all: one that never does can't be judged by its silence. */
    @Volatile
    var asksTime = false
        private set

    /**
     * Sound has gone out since RECORD or the last FLUSH: the speaker may
     * still hold some, which a new start must flush first.
     */
    @Volatile
    var unflushed = false
        private set

    private val threads = ArrayList<Thread>()

    // --- Setting up ----------------------------------------------------------

    private fun setUp(authSetup: Boolean) {
        options()
        if (authSetup) authSetup()
        expect("ANNOUNCE", rtsp.request("ANNOUNCE", url, contentType = "application/sdp", body = sdp().toByteArray()))
        startListening()
        val setup = rtsp.request(
            "SETUP",
            url,
            headers = listOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                    "control_port=${control.localPort};timing_port=${timing.localPort}",
            ),
        )
        expect("SETUP", setup)
        rtsp.session = setup.headers["session"]?.substringBefore(';')?.trim()
            ?: throw RaopRefused("SETUP", 0, "no session")
        val transport = parseTransport(setup.headers["transport"].orEmpty())
        serverPort = transport["server_port"]?.toIntOrNull()?.takeIf { it in PORTS }
            ?: throw RaopRefused("SETUP", 0, "no server port")
        controlPort = (transport["control_port"]?.toIntOrNull() ?: serverPort + 1).takeIf { it in PORTS }
            ?: throw RaopRefused("SETUP", 0, "no control port")

        avoidZeroRtp()
        val record = rtsp.request(
            "RECORD",
            url,
            headers = listOf("Range" to "npt=0-", "RTP-Info" to "seq=$nextSeq;rtptime=$nextRtp"),
        )
        expect("RECORD", record)
        // A speaker that needs longer than the usual two seconds says so.
        record.headers["audio-latency"]?.trim()?.toIntOrNull()?.let { said ->
            if (said > latencyFrames) latencyFrames = said
        }
        touch()
    }

    private fun options() {
        val reply = rtsp.request("OPTIONS", "*")
        // A 401 is a password; anything else that isn't OK, a speaker that
        // won't take this sender — except 501, which some answer to OPTIONS
        // alone and which proves the speaker is there.
        if (reply.status != NOT_IMPLEMENTED) expect("OPTIONS", reply)
    }

    /**
     * The opening message of the MFi handshake some speakers (AirPort
     * Express on its last firmware) wait for before they take a stream: a
     * flag for "no encryption" and a public key — any 32 bytes; the speaker
     * answers with its own and its certificate, which a sender that doesn't
     * check them can let go unread. Nothing of Apple's is in it, and nothing
     * is unlocked by it.
     */
    private fun authSetup() {
        val body = byteArrayOf(0x01) + randomBytes(32)
        expect("auth-setup", rtsp.request("POST", "/auth-setup", contentType = "application/octet-stream", body = body))
    }

    private fun sdp(): String {
        val local = rtsp.localAddress
        val id = url.substringAfterLast('/')
        return "v=0\r\n" +
            "o=Amp $id 0 IN IP4 $local\r\n" +
            "s=Amp\r\n" +
            "c=IN IP4 ${receiver.address}\r\n" +
            "t=0 0\r\n" +
            "m=audio 0 RTP/AVP 96\r\n" +
            "a=rtpmap:96 AppleLossless\r\n" +
            // Frame length, version, bit depth, Rice pb/mb/kb, channels,
            // max run, max frame bytes (0: unbounded), average bit rate, rate.
            "a=fmtp:96 ${AlacFrames.FRAMES_PER_PACKET} 0 16 40 10 14 2 255 0 0 $AIRPLAY_RATE\r\n"
    }

    private fun expect(step: String, reply: RtspResponse) {
        if (!reply.ok) throw RaopRefused(step, reply.status, reply.reason)
    }

    // --- The UDP side --------------------------------------------------------

    private fun startListening() {
        timing.soTimeout = RECEIVE_SLICE_MS
        control.soTimeout = RECEIVE_SLICE_MS
        threads += Thread({ answerTimeRequests() }, "AmpAirPlay-timing").apply {
            isDaemon = true
            start()
        }
        threads += Thread({ answerResendRequests() }, "AmpAirPlay-control").apply {
            isDaemon = true
            start()
        }
    }

    /** The speaker asks this phone's time every few seconds, and is told. */
    private fun answerTimeRequests() {
        val buffer = ByteArray(128)
        val reply = ByteArray(32)
        while (!closed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                timing.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (closed) return
                continue
            }
            val received = NtpClock.now()
            if (packet.length < 32 || (buffer[1].toInt() and 0x7f) != TIMING_REQUEST) continue
            lastTimingRequestAt = System.nanoTime()
            asksTime = true
            timingAsks++
            reply[0] = 0x80.toByte()
            reply[1] = (0x80 or TIMING_REPLY).toByte()
            reply[2] = 0
            reply[3] = 7
            for (i in 4 until 8) reply[i] = 0
            // Their send time back as the origin, then ours received and sent.
            System.arraycopy(buffer, 24, reply, 8, 8)
            putLong(reply, 16, received)
            putLong(reply, 24, NtpClock.now())
            runCatching { timing.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) }
        }
    }

    /** The speaker asks for packets it missed; those still held go again. */
    private fun answerResendRequests() {
        val buffer = ByteArray(128)
        while (!closed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                control.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (closed) return
                continue
            }
            if (packet.length < 8 || (buffer[1].toInt() and 0x7f) != RESEND_REQUEST) continue
            val first = u16(buffer, 4)
            val count = u16(buffer, 6).coerceAtMost(HISTORY)
            resendAsks++
            for (i in 0 until count) {
                val seq = (first + i) and 0xffff
                val slot = seq and (HISTORY - 1)
                val original = synchronized(history) { if (historySeq[slot] == seq) history[slot] else null } ?: continue
                val resend = ByteArray(4 + original.size)
                resend[0] = 0x80.toByte()
                resend[1] = (0x80 or RESEND_REPLY).toByte()
                resend[2] = 0
                resend[3] = 1
                System.arraycopy(original, 0, resend, 4, original.size)
                if (runCatching { control.send(DatagramPacket(resend, resend.size, remote, controlPort)) }.isSuccess) resent++
            }
        }
    }

    /**
     * Send one packet of audio: [frame] holds an ALAC frame of [length]
     * bytes. [first] marks the first packet after a start, a flush or a
     * resync. Returns the RTP time it carried.
     */
    fun sendAudio(frame: ByteArray, length: Int, first: Boolean): Long {
        val seq = nextSeq
        val rtp = nextRtp
        val packet = ByteArray(RTP_HEADER + length)
        packet[0] = 0x80.toByte()
        packet[1] = (if (first) 0x80 or AUDIO else AUDIO).toByte()
        putU16(packet, 2, seq)
        putU32(packet, 4, rtp)
        putU32(packet, 8, ssrc.toLong() and MASK32)
        System.arraycopy(frame, 0, packet, RTP_HEADER, length)
        synchronized(history) {
            val slot = seq and (HISTORY - 1)
            history[slot] = packet
            historySeq[slot] = seq
        }
        nextSeq = (seq + 1) and 0xffff
        nextRtp = (rtp + AlacFrames.FRAMES_PER_PACKET) and MASK32
        unflushed = true
        audio.send(DatagramPacket(packet, packet.size, remote, serverPort))
        packetsSent++
        return rtp
    }

    /**
     * Tell the speaker which RTP time is due when: [rtp] is the packet due to
     * be sent at [dueNanos] (System.nanoTime), so `rtp - latency` is the one
     * to be heard then. Taken from the schedule rather than the clock at the
     * moment of sending, so a late send can't move the speaker's timing.
     * Once a second while playing, and first (flagged) before a stream starts.
     */
    fun sendSync(rtp: Long, dueNanos: Long, first: Boolean, latency: Int = latencyFrames) {
        val packet = ByteArray(20)
        packet[0] = (if (first) 0x90 else 0x80).toByte()
        packet[1] = (0x80 or SYNC).toByte()
        packet[2] = 0
        packet[3] = 7
        putU32(packet, 4, (rtp - latency) and MASK32)
        putLong(packet, 8, NtpClock.at(dueNanos))
        putU32(packet, 16, rtp and MASK32)
        control.send(DatagramPacket(packet, packet.size, remote, controlPort))
    }

    // --- Steering ------------------------------------------------------------

    /** Drop everything buffered: what is sent next, from [nextSeq] and [nextRtp], starts afresh. */
    fun flush() {
        avoidZeroRtp()
        request("FLUSH", headers = listOf("RTP-Info" to "seq=$nextSeq;rtptime=$nextRtp"))
        unflushed = false
    }

    // An RTP time of 0 reads as "flush everything" to shairport-sync, so a
    // start or a flush never lands on it.
    private fun avoidZeroRtp() {
        if (nextRtp == 0L) nextRtp = AlacFrames.FRAMES_PER_PACKET.toLong()
    }

    /** [db] from −30 to 0, or [MUTE_DB]. */
    fun setVolume(db: Double) {
        volumeSentAt = System.nanoTime()
        request("SET_PARAMETER", "text/parameters", "volume: ${"%.6f".format(Locale.ROOT, db)}\r\n".toByteArray())
    }

    /** When a level was last sent: a speaker may report it back, which is no news. */
    @Volatile
    var volumeSentAt: Long = 0L
        private set

    /** What the speaker's calls to Amp's remote carry for this session. */
    val activeRemote: String get() = rtsp.activeRemote

    /** This phone's address on the speaker's network. */
    val localAddress: InetAddress get() = InetAddress.getByName(rtsp.localAddress)

    fun setMetadata(title: String, artist: String?, album: String?, rtp: Long) {
        val body = Dmap.item(title, artist, album)
        request("SET_PARAMETER", "application/x-dmap-tagged", body, listOf("RTP-Info" to "rtptime=$rtp"))
    }

    fun setArtwork(jpeg: ByteArray, rtp: Long) {
        request("SET_PARAMETER", "image/jpeg", jpeg, listOf("RTP-Info" to "rtptime=$rtp"))
    }

    /** Where in the track the speaker's progress bar is: RTP times of its start, now and its end. */
    fun setProgress(start: Long, now: Long, end: Long) {
        val text = "progress: ${start and MASK32}/${now and MASK32}/${end and MASK32}\r\n"
        request("SET_PARAMETER", "text/parameters", text.toByteArray())
    }

    /** A request that keeps an idle session open, and finds out whether it still is. */
    fun keepAlive() {
        val reply = rtsp.request("OPTIONS", "*")
        touch()
        if (!reply.ok && reply.status != NOT_IMPLEMENTED) throw RaopRefused("OPTIONS", reply.status, reply.reason)
    }

    private fun request(
        method: String,
        contentType: String? = null,
        body: ByteArray? = null,
        headers: List<Pair<String, String>> = emptyList(),
    ) {
        val reply = rtsp.request(method, url, headers, contentType, body)
        touch()
        if (!reply.ok) throw RaopRefused(method, reply.status, reply.reason)
    }

    private fun touch() {
        lastRequestAt = System.nanoTime()
    }

    /** Say goodbye, briefly, then let everything go. */
    fun teardown() {
        if (closed) return
        runCatching { rtsp.request("TEARDOWN", url) }
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        rtsp.close()
        audio.close()
        control.close()
        timing.close()
    }

    val isOpen: Boolean get() = !closed && !rtsp.isClosed

    companion object {
        /** Two seconds, the speaker's buffer: what iTunes asked for, and what the speakers expect. */
        const val DEFAULT_LATENCY_FRAMES = 88_200

        /**
         * What AirPlay speakers add to the latency they're told: a quarter
         * second of their own (shairport-sync adds it for senders like this
         * one, and the open-source senders reckon with it), so a packet is
         * heard 2.25 s after it's due.
         */
        const val RECEIVER_OFFSET_FRAMES = 11_025
        const val MUTE_DB = -144.0

        private const val AUDIO = 0x60
        private const val SYNC = 0x54
        private const val TIMING_REQUEST = 0x52
        private const val TIMING_REPLY = 0x53
        private const val RESEND_REQUEST = 0x55
        private const val RESEND_REPLY = 0x56
        private const val RTP_HEADER = 12
        private const val NOT_IMPLEMENTED = 501
        private val PORTS = 1..65535
        private const val MASK32 = 0xffffffffL

        /** About eight seconds of packets kept for resending. */
        private const val HISTORY = 1024
        private const val RECEIVE_SLICE_MS = 500
        private const val CONNECT_TIMEOUT_MS = 5_000

        /**
         * Open a session with [receiver], up to RECORD, ready for sound.
         * Throws [RaopRefused] when the speaker says no, IOException when it
         * can't be reached.
         */
        fun open(
            receiver: AirPlayReceiver,
            userAgent: String,
            authSetup: Boolean,
            log: (String) -> Unit,
            dacpId: String = DacpRecords.newId(),
        ): RaopSession {
            val rtsp = Rtsp.connect(receiver.address, receiver.port, userAgent, dacpId, CONNECT_TIMEOUT_MS)
            val url = "rtsp://${rtsp.localAddress}/${randomInt().toLong() and MASK32}"
            val session = try {
                RaopSession(receiver, rtsp, url, log)
            } catch (e: SocketException) {
                rtsp.close()
                throw e
            }
            try {
                session.setUp(authSetup)
            } catch (e: Exception) {
                session.close()
                throw e
            }
            return session
        }

        /** `a=b;c=d` pairs of a Transport header, names lower-cased. */
        internal fun parseTransport(header: String): Map<String, String> =
            header.split(';').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq).trim().lowercase() to part.substring(eq + 1).trim()
            }.toMap()

        private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

        private fun putU16(b: ByteArray, at: Int, v: Int) {
            b[at] = (v ushr 8).toByte()
            b[at + 1] = v.toByte()
        }

        private fun putU32(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
        }

        private fun putLong(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
        }
    }
}

/** The speaker said no to [step], with RTSP [status] (0: an answer that made no sense). */
internal class RaopRefused(val step: String, val status: Int, reason: String) :
    IOException("$step refused: $status $reason")

/**
 * NTP time, as AirPlay's clocks speak it: seconds since 1900 in the high 32
 * bits, the fraction in the low. Read off the monotonic clock from a fixed
 * moment, so it never jumps when the wall clock is set.
 */
internal object NtpClock {
    private val baseNanos = System.nanoTime()
    private val baseUnixNanos = System.currentTimeMillis() * 1_000_000L

    /** Seconds from 1900 to 1970. */
    private const val EPOCH_OFFSET = 2_208_988_800L

    fun now(): Long = at(System.nanoTime())

    fun at(nanoTime: Long): Long {
        val unix = baseUnixNanos + (nanoTime - baseNanos)
        val seconds = unix / 1_000_000_000L + EPOCH_OFFSET
        val fraction = ((unix % 1_000_000_000L) shl 32) / 1_000_000_000L
        return (seconds shl 32) or (fraction and 0xffffffffL)
    }
}

/** The DMAP a speaker's display reads: one item with its title, artist and album. */
internal object Dmap {
    fun item(title: String, artist: String?, album: String?): ByteArray {
        val inner = java.io.ByteArrayOutputStream()
        fun tag(code: String, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            inner.write(code.toByteArray(Charsets.US_ASCII))
            inner.write(int32(bytes.size))
            inner.write(bytes)
        }
        tag("minm", title)
        artist?.let { tag("asar", it) }
        album?.let { tag("asal", it) }
        val content = inner.toByteArray()
        return "mlit".toByteArray(Charsets.US_ASCII) + int32(content.size) + content
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}

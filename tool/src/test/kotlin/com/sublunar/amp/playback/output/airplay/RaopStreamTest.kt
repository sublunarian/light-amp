package com.sublunar.amp.playback.output.airplay

import com.sublunar.amp.data.Track
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin

/**
 * The session, the feed and the streamer together, against [FakeReceiver]:
 * what goes over the wire, in what order, at what pace, and that what the
 * speaker would decode is exactly what was played.
 */
class RaopStreamTest {
    private val receiver = FakeReceiver()
    private val logs = mutableListOf<String>()
    private val log: (String) -> Unit = { synchronized(logs) { logs += it } }

    @After
    fun close() = receiver.close()

    @Test
    fun `a session is set up in order, unencrypted, with the stream announced as CD-quality ALAC`() {
        val session = RaopSession.open(receiver.receiver, "Amp/test", authSetup = false, log = log, dacpId = "1A2B3C4D5E6F7081")
        session.setVolume(-21.0)
        session.teardown()
        // Every request names Amp's remote and this session, so a speaker can call back about it.
        assertTrue(receiver.requests.all { it.headers["dacp-id"] == "1A2B3C4D5E6F7081" && it.headers["active-remote"] == session.activeRemote })

        val methods = receiver.requests.map { it.method }
        assertEquals(listOf("OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "SET_PARAMETER", "TEARDOWN"), methods)
        val sdp = receiver.requests[1].text
        assertTrue(sdp, sdp.contains("a=rtpmap:96 AppleLossless\r\n"))
        assertTrue(sdp, sdp.contains("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100\r\n"))
        assertTrue("no key in an unencrypted stream", !sdp.contains("rsaaeskey") && !sdp.contains("aesiv"))
        assertEquals("application/sdp", receiver.requests[1].headers["content-type"])
        assertTrue(receiver.requests[2].headers["transport"]!!.contains(Regex("control_port=\\d+;timing_port=\\d+")))
        assertEquals("1", receiver.requests[3].headers["session"])
        assertEquals("volume: -21.000000\r\n", receiver.requests[4].text)
        // The sender names itself, as itself.
        assertTrue(receiver.requests.all { it.headers["user-agent"] == "Amp/test" })
        assertTrue(sdp, sdp.contains("o=Amp ") && sdp.contains("s=Amp\r\n") && !sdp.contains("iTunes"))
        assertTrue(receiver.requests.all { it.headers["client-instance"]?.length == 16 })
    }

    @Test
    fun `a speaker that wants auth-setup turns the stream away until it has it`() {
        val picky = FakeReceiver(wantsAuthSetup = true)
        try {
            val refused = runCatching { RaopSession.open(picky.receiver, "Amp/test", authSetup = false, log = log) }
            assertEquals(403, (refused.exceptionOrNull() as RaopRefused).status)
            RaopSession.open(picky.receiver, "Amp/test", authSetup = true, log = log).teardown()
            val post = picky.requests.first { it.method == "POST" }
            assertEquals("/auth-setup", post.uri)
            assertEquals(33, post.body.size)
            assertEquals(1, post.body[0].toInt())
        } finally {
            picky.close()
        }
    }

    @Test
    fun `a speaker that answers OPTIONS with 501 is still played to and kept alive`() {
        val terse = FakeReceiver(noOptions = true)
        try {
            val session = RaopSession.open(terse.receiver, "Amp/test", authSetup = false, log = log)
            session.keepAlive()
            session.teardown()
            assertEquals(listOf("OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "OPTIONS", "TEARDOWN"), terse.requests.map { it.method })
        } finally {
            terse.close()
        }
    }

    @Test
    fun `the session knows when the speaker may still hold sound`() {
        val (session, streamer) = start(listOf(tone(44_100, frames = 44_100, hz = 400.0)))
        assertTrue(!session.unflushed || receiver.audio.isNotEmpty())
        assertTrue(receiver.waitFor(3_000) { receiver.audio.size >= 5 })
        streamer.stop()
        assertTrue(session.unflushed)
        session.flush()
        assertTrue(!session.unflushed)
        session.teardown()
    }

    @Test
    fun `a track that gives no sound ends the run, even under repeat-one`() {
        val session = RaopSession.open(receiver.receiver, "Amp/test", authSetup = false, log = log)
        var opened = 0
        var couldNot = false
        val feed = Feed(
            open = { _, _ ->
                opened++
                pcmOf(Tone(44_100, ShortArray(0)))
            },
            // Repeat-one: the same row follows itself.
            following = { segment -> segment.row to track(segment.row) },
            gainFor = { 1f },
            log = log,
            first = 0 to track(0),
            fromMs = 0,
        )
        val streamer = Streamer(
            listOf(session),
            feed,
            object : StreamEvents by NoEvents {
                override fun couldNotStart() {
                    couldNot = true
                }
            },
            log,
        )
        streamer.start()
        assertTrue(receiver.waitFor(2_000) { couldNot })
        assertEquals(1, opened)
        assertEquals(Feed.End.UNREADABLE, feed.ended)
        assertEquals("t0", feed.brokenAt?.second?.id)
        session.teardown()
    }

    @Test
    fun `the track after is fetched ahead, but never the playing one again`() {
        val prefetched = mutableListOf<String>()
        val tones = listOf(tone(44_100, frames = 4_000, hz = 440.0), tone(44_100, frames = 4_000, hz = 660.0))
        fun feed(following: (Segment) -> Pair<Int, Track>?) = Feed(
            open = { t, _ -> pcmOf(tones[t.id.removePrefix("t").toInt()]) },
            following = following,
            gainFor = { 1f },
            log = log,
            first = 0 to track(0),
            fromMs = 0,
            prefetch = { synchronized(prefetched) { prefetched += it.id } },
        )
        val next = feed { s -> (s.row + 1).takeIf { it < 2 }?.let { it to track(it) } }
        next.start()
        assertTrue(receiver.waitFor(2_000) { next.ended != null })
        assertEquals(listOf("t1"), synchronized(prefetched) { prefetched.toList() })

        prefetched.clear()
        val repeatOne = feed { s -> s.row to track(s.row) }
        repeatOne.start()
        assertTrue(receiver.waitFor(1_000) { repeatOne.segments.size >= 3 })
        repeatOne.stop()
        assertTrue(synchronized(prefetched) { prefetched.isEmpty() })
    }

    @Test
    fun `CD audio arrives bit for bit, paced in real time, first packet marked and synced`() {
        val source = tone(44_100, frames = 22_050, hz = 1_000.0)
        val (session, streamer) = start(listOf(source))
        assertTrue(receiver.waitFor(3_000) { receiver.audio.size >= 63 })
        streamer.stop()

        val record = receiver.requests.first { it.method == "RECORD" }.headers["rtp-info"]!!
        val first = receiver.audio.minByOrNull { it.seq }!!
        assertEquals("seq=${first.seq};rtptime=${first.rtp}", record)
        assertTrue(first.marker)
        assertTrue(receiver.audio.filter { it !== first }.none { it.marker })

        // The first sync comes before any sound, and names the first packet.
        val sync = receiver.syncs.first()
        assertTrue(sync.first)
        assertEquals(first.rtp, sync.rtp)
        assertEquals((first.rtp - session.latencyFrames) and 0xffffffffL, sync.rtpLessLatency)

        // 22 050 frames is 62.6 packets: 63 sent, the last padded with silence.
        val heard = receiver.pcm()
        assertEquals(63 * 352 * 2, heard.size)
        assertArrayEquals(source.samples, heard.copyOf(source.samples.size))
        assertTrue(heard.copyOfRange(source.samples.size, heard.size).all { it.toInt() == 0 })

        // Half a second of sound took half a second to send.
        val spread = (receiver.audio.maxOf { it.atNanos } - receiver.audio.minOf { it.atNanos }) / 1_000_000
        assertTrue("sent over $spread ms", spread in 400..650)
        session.teardown()
    }

    @Test
    fun `two tracks run into each other without a gap`() {
        val a = tone(44_100, frames = 10_000, hz = 440.0)
        val b = tone(44_100, frames = 10_000, hz = 660.0)
        val (session, streamer) = start(listOf(a, b))
        assertTrue(receiver.waitFor(3_000) { receiver.audio.size >= 57 })
        streamer.stop()
        val heard = receiver.pcm()
        assertArrayEquals(a.samples + b.samples, heard.copyOf(a.samples.size + b.samples.size))
        session.teardown()
    }

    @Test
    fun `48 kHz is brought to 44_1 and keeps its length`() {
        val source = tone(48_000, frames = 24_000, hz = 1_000.0)
        val (session, streamer) = start(listOf(source))
        // Half a second at 44.1 kHz: 22 050 frames, 63 packets.
        assertTrue(receiver.waitFor(3_000) { receiver.audio.size >= 63 })
        Thread.sleep(100)
        streamer.stop()
        assertEquals(63, receiver.audio.size)
        assertTrue(logs.any { it.contains("resampled") })
        session.teardown()
    }

    @Test
    fun `lost packets are sent again from the history`() {
        val (session, streamer) = start(listOf(tone(44_100, frames = 44_100, hz = 500.0)))
        assertTrue(receiver.waitFor(3_000) { receiver.audio.size >= 20 })
        val lost = receiver.audio.minByOrNull { it.seq }!!
        receiver.askResend(lost.seq, 2)
        assertTrue(receiver.waitFor(2_000) { receiver.resends.size >= 2 })
        streamer.stop()
        val resend = receiver.resends.first()
        assertEquals(0x80, resend[0].toInt() and 0xff)
        assertEquals(0xd6, resend[1].toInt() and 0xff)
        // The original packet whole after the four-byte header.
        val innerSeq = ((resend[6].toInt() and 0xff) shl 8) or (resend[7].toInt() and 0xff)
        assertEquals(lost.seq, innerSeq)
        assertArrayEquals(lost.payload, resend.copyOfRange(16, resend.size))
        session.teardown()
    }

    @Test
    fun `the speaker's time requests are answered with its own time as the origin`() {
        val session = RaopSession.open(receiver.receiver, "Amp/test", authSetup = false, log = log)
        assertTrue(receiver.waitFor(2_000) { receiver.timingReplies.size >= 2 })
        val reply = receiver.timingReplies.first()
        assertEquals(32, reply.size)
        assertEquals(0x80, reply[0].toInt() and 0xff)
        assertEquals(0xd3, reply[1].toInt() and 0xff)
        // Request n carried 0x10 + n onwards as its send time; each reply gives back its own.
        val n = (reply[8].toInt() and 0xff) - 0x10
        assertArrayEquals(ByteArray(8) { (0x10 + n + it).toByte() }, reply.copyOfRange(8, 16))
        // Received no later than sent, both this side of 1900 + 126 years.
        val received = java.nio.ByteBuffer.wrap(reply, 16, 8).long
        val sent = java.nio.ByteBuffer.wrap(reply, 24, 8).long
        assertTrue(sent >= received)
        assertTrue((received ushr 32) > 3_900_000_000L)
        session.teardown()
    }

    @Test
    fun `what the speaker hears is reckoned 2_25 s behind what was sent`() {
        val (session, streamer) = start(listOf(tone(44_100, frames = 441_000, hz = 300.0)))
        assertTrue(receiver.waitFor(3_000) { receiver.audio.size >= 10 })
        val heard = streamer.heardNow()
        assertNotNull(heard)
        assertTrue("not sounding yet", !heard!!.sounding)
        streamer.stop()
        assertEquals(99_225, session.heardAfterFrames)
        session.teardown()
    }

    @Test
    fun `a flush names the next packet, and the stream after it starts marked`() {
        val (session, streamer) = start(listOf(tone(44_100, frames = 441_000, hz = 300.0)))
        assertTrue(receiver.waitFor(3_000) { receiver.audio.size >= 10 })
        streamer.stop()
        val nextSeq = session.nextSeq
        val nextRtp = session.nextRtp
        session.flush()
        val flush = receiver.requests.last { it.method == "FLUSH" }
        assertEquals("seq=$nextSeq;rtptime=$nextRtp", flush.headers["rtp-info"])

        val sentBefore = receiver.audio.size
        val again = Streamer(listOf(session), feedOf(listOf(tone(44_100, frames = 44_100, hz = 300.0))), NoEvents, log)
        again.start()
        assertTrue(receiver.waitFor(3_000) { receiver.audio.size >= sentBefore + 5 })
        again.stop()
        val resumed = receiver.audio.drop(sentBefore).minByOrNull { it.seq }!!
        assertEquals(nextSeq, resumed.seq)
        assertEquals(nextRtp, resumed.rtp)
        assertTrue(resumed.marker)
        assertTrue(receiver.syncs.count { it.first } >= 2)
        session.teardown()
    }

    @Test
    fun `an unreadable first track is said so, and nothing is sent`() {
        val session = RaopSession.open(receiver.receiver, "Amp/test", authSetup = false, log = log)
        var couldNot = false
        val feed = Feed(
            open = { _, _ -> throw IOException("gone") },
            following = { null },
            gainFor = { 1f },
            log = log,
            first = 0 to track(0),
            fromMs = 0,
        )
        val streamer = Streamer(
            listOf(session),
            feed,
            object : StreamEvents by NoEvents {
                override fun couldNotStart() {
                    couldNot = true
                }
            },
            log,
        )
        streamer.start()
        assertTrue(receiver.waitFor(2_000) { couldNot })
        assertTrue(receiver.audio.isEmpty())
        session.teardown()
    }

    // --- Helpers -------------------------------------------------------------

    private class Tone(val rate: Int, val samples: ShortArray)

    private fun tone(rate: Int, frames: Int, hz: Double): Tone =
        Tone(rate, ShortArray(frames * 2) { (8_000 * sin(2 * PI * hz * (it / 2) / rate) + if (it % 2 == 1) 1 else 0).toInt().toShort() })

    private fun track(i: Int) = Track(
        id = "t$i",
        title = "Track $i",
        artist = "Artist",
        album = "Album",
        albumArtist = "Artist",
        albumId = null,
        coverArtId = null,
        durationMs = 1_000L,
        trackNumber = i + 1,
        discNumber = 1,
        year = null,
        playCount = 0,
        lastPlayedMs = 0L,
    )

    private fun feedOf(tones: List<Tone>): Feed = Feed(
        open = { track, _ -> pcmOf(tones[track.id.removePrefix("t").toInt()]) },
        following = { segment -> (segment.row + 1).takeIf { it < tones.size }?.let { it to track(it) } },
        gainFor = { 1f },
        log = log,
        first = 0 to track(0),
        fromMs = 0,
    )

    private fun start(tones: List<Tone>): Pair<RaopSession, Streamer> {
        val session = RaopSession.open(receiver.receiver, "Amp/test", authSetup = false, log = log)
        val streamer = Streamer(listOf(session), feedOf(tones), NoEvents, log)
        streamer.start()
        return session to streamer
    }

    private fun pcmOf(tone: Tone): PcmSource = object : PcmSource {
        private var at = 0
        override val sampleRate = tone.rate
        override val channels = 2
        override val order = ChannelOrder.ANDROID
        override val startMs = 0L

        override fun next(block: PcmBlock): Boolean {
            val frames = minOf(1_000, tone.samples.size / 2 - at)
            if (frames <= 0) return false
            val out = block.shorts(frames * 2)
            System.arraycopy(tone.samples, at * 2, out, 0, frames * 2)
            block.frames = frames
            at += frames
            return true
        }

        override fun close() {}
    }

    private object NoEvents : StreamEvents {
        override fun heard(heard: Heard) {}
        override fun finished(end: Feed.End, last: Heard?) {}
        override fun couldNotStart() {}
        override fun lost(cause: IOException) {}
    }
}

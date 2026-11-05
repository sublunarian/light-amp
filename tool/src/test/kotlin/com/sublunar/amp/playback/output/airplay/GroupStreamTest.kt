package com.sublunar.amp.playback.output.airplay

import com.sublunar.amp.data.Track
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Several speakers from one stream: the same sound at the same moment at each, one joining partway. */
class GroupStreamTest {
    private val first = FakeReceiver()
    private val second = FakeReceiver()
    private val log: (String) -> Unit = {}

    @After
    fun close() {
        first.close()
        second.close()
    }

    @Test
    fun `a speaker that joins partway is told the schedule first, and plays each packet when the other does`() {
        val a = RaopSession.open(first.receiver, "Amp/test", authSetup = false, log = log)
        val b = RaopSession.open(second.receiver, "Amp/test", authSetup = false, log = log)
        val streamer = Streamer(listOf(a), feed(), NoEvents, log)
        streamer.start()
        assertTrue(first.waitFor(3_000) { first.audio.size >= 40 })
        streamer.join(b)
        assertTrue(second.waitFor(3_000) { second.audio.size >= 40 })
        streamer.stop()

        // Its first packet is marked, and a flagged sync came before it.
        assertTrue(second.audio.first().marker)
        assertTrue(second.syncs.first().first)
        assertEquals(listOf(a, b), streamer.sessions)

        // The same sound at each, heard at the same moment by each one's own sync.
        var compared = 0
        for (heardAtB in second.audio.drop(5).take(20)) {
            val twin = first.audio.filter { it.payload.contentEquals(heardAtB.payload) }
                .minByOrNull { abs(it.atNanos - heardAtB.atNanos) } ?: continue
            val atA = playsAt(first.syncs.last(), twin.rtp)
            val atB = playsAt(second.syncs.last(), heardAtB.rtp)
            assertEquals("packet ${heardAtB.seq}", atA, atB, 0.002)
            compared++
        }
        assertTrue("compared $compared", compared >= 10)
        a.teardown()
        b.teardown()
    }

    @Test
    fun `a speaker that leaves gets nothing more, and the other plays on`() {
        val a = RaopSession.open(first.receiver, "Amp/test", authSetup = false, log = log)
        val b = RaopSession.open(second.receiver, "Amp/test", authSetup = false, log = log)
        val streamer = Streamer(listOf(a, b), feed(), NoEvents, log)
        streamer.start()
        assertTrue(second.waitFor(3_000) { second.audio.size >= 20 })
        streamer.leave(b)
        Thread.sleep(50)
        val atLeave = second.audio.size
        val aAtLeave = first.audio.size
        assertTrue(first.waitFor(3_000) { first.audio.size >= aAtLeave + 30 })
        streamer.stop()
        assertEquals(atLeave, second.audio.size)
        assertEquals(listOf(a), streamer.sessions)
        a.teardown()
        b.teardown()
    }

    /** When the speaker plays RTP time [rtp], by [sync]: its NTP moment for `rtpLessLatency`, on from there. */
    private fun playsAt(sync: FakeReceiver.Sync, rtp: Long): Double {
        val ntp = (sync.ntp ushr 32) + (sync.ntp and 0xffffffffL) / 4_294_967_296.0
        val frames = ((rtp - sync.rtpLessLatency) and 0xffffffffL).let { if (it > 0x7fffffffL) it - 0x100000000L else it }
        return ntp + frames / 44_100.0
    }

    private fun feed(): Feed = Feed(
        open = { _, _ -> tone() },
        following = { null },
        gainFor = { 1f },
        log = log,
        first = 0 to track(),
        fromMs = 0,
    )

    // A rising sweep, so no two packets of the run sound the same.
    private fun tone(): PcmSource = object : PcmSource {
        private var at = 0
        override val sampleRate = 44_100
        override val channels = 2
        override val order = ChannelOrder.ANDROID
        override val startMs = 0L

        override fun next(block: PcmBlock): Boolean {
            val frames = minOf(1_000, 441_000 - at)
            if (frames <= 0) return false
            val out = block.shorts(frames * 2)
            for (i in 0 until frames) {
                val t = (at + i) / 44_100.0
                val v = (8_000 * sin(2 * PI * (200 + 40 * t) * t)).toInt().toShort()
                out[2 * i] = v
                out[2 * i + 1] = v
            }
            block.frames = frames
            at += frames
            return true
        }

        override fun close() {}
    }

    private fun track() = Track(
        id = "t0",
        title = "Track",
        artist = "Artist",
        album = "Album",
        albumArtist = "Artist",
        albumId = null,
        coverArtId = null,
        durationMs = 10_000L,
        trackNumber = 1,
        discNumber = 1,
        year = null,
        playCount = 0,
        lastPlayedMs = 0L,
    )

    private object NoEvents : StreamEvents {
        override fun heard(heard: Heard) {}
        override fun finished(end: Feed.End, last: Heard?) {}
        override fun couldNotStart() {}
        override fun lost(cause: IOException) {}
    }
}

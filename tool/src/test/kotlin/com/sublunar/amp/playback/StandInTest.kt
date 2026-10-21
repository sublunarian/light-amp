package com.sublunar.amp.playback

import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class StandInWatchTest {

    private val length = 245_016L
    private val quiet = StandInWatch.QUIET_MS

    /** Amp put it in playing at [at], at time 0, and its echo has come back. */
    private fun playing(at: Long = 60_000L) = StandInWatch().apply {
        expect(at, playing = true, nowMs = 0L)
        read(at, playing = false, nowMs = 100L, lengthMs = length)
        read(at, playing = true, nowMs = 200L, lengthMs = length)
    }

    /** Where a stand-in put in playing at [at] at time 0.2 s has reached by [now]. */
    private fun along(at: Long, now: Long) = at + now - 200L

    @Test
    fun `Amp's own moves read as nothing`() {
        val w = playing()
        val now = quiet + 500L
        assertTrue(w.read(along(60_000L, now), playing = true, nowMs = now, lengthMs = length).isEmpty())
        assertTrue(w.wanted)
    }

    @Test
    fun `a pause from outside is a press`() {
        val w = playing()
        val now = quiet + 500L
        w.read(along(60_000L, now), playing = true, nowMs = now, lengthMs = length)
        assertEquals(
            listOf(StandInPress.Pause),
            w.read(along(60_000L, now + 250L), playing = false, nowMs = now + 250L, lengthMs = length),
        )
        assertFalse(w.wanted)
    }

    @Test
    fun `a play long after a pause is a press`() {
        val w = StandInWatch()
        w.expect(30_000L, playing = false, nowMs = 0L)
        w.read(30_000L, playing = false, nowMs = 100L, lengthMs = length)
        assertEquals(
            listOf(StandInPress.Play),
            w.read(30_000L, playing = true, nowMs = 300_000L, lengthMs = length),
        )
        assertTrue(w.wanted)
    }

    @Test
    fun `a stop from outside pauses the output`() {
        val w = playing()
        val now = quiet + 500L
        val at = along(60_000L, now)
        w.read(at, playing = true, nowMs = now, lengthMs = length)
        assertEquals(listOf(StandInPress.Pause), w.read(at + 100L, playing = false, nowMs = now + 100L, lengthMs = length))
    }

    @Test
    fun `running out before the output is not a pause`() {
        val w = playing(at = length - 3_000L)
        val now = quiet + 500L
        w.read(length - 1_000L, playing = true, nowMs = now, lengthMs = length)
        assertTrue(w.read(length, playing = false, nowMs = now + 1_000L, lengthMs = length).isEmpty())
        assertTrue(w.wanted)
    }

    @Test
    fun `skipping ahead is a seek`() {
        val w = playing()
        val now = quiet + 500L
        val at = along(60_000L, now)
        w.read(at, playing = true, nowMs = now, lengthMs = length)
        assertEquals(
            listOf(StandInPress.SeekTo(at + 250L + 15_000L)),
            w.read(at + 250L + 15_000L, playing = true, nowMs = now + 250L, lengthMs = length),
        )
    }

    @Test
    fun `skipping back is a seek`() {
        val w = playing()
        val now = quiet + 500L
        val at = along(60_000L, now)
        w.read(at, playing = true, nowMs = now, lengthMs = length)
        assertEquals(
            listOf(StandInPress.SeekTo(at - 15_000L)),
            w.read(at - 15_000L, playing = true, nowMs = now + 250L, lengthMs = length),
        )
    }

    @Test
    fun `a seek while paused is a press`() {
        val w = StandInWatch()
        w.expect(30_000L, playing = false, nowMs = 0L)
        w.read(30_000L, playing = false, nowMs = 100L, lengthMs = length)
        assertEquals(
            listOf(StandInPress.SeekTo(45_000L)),
            w.read(45_000L, playing = false, nowMs = 60_000L, lengthMs = length),
        )
    }

    @Test
    fun `a stall is not a seek`() {
        val w = playing()
        val now = quiet + 500L
        val at = along(60_000L, now)
        w.read(at, playing = true, nowMs = now, lengthMs = length)
        assertTrue(w.read(at, playing = true, nowMs = now + 5_000L, lengthMs = length).isEmpty())
    }

    @Test
    fun `a stand-in that never starts can't pause the output`() {
        val w = StandInWatch()
        w.expect(60_000L, playing = true, nowMs = 0L)
        w.read(60_000L, playing = false, nowMs = 100L, lengthMs = length)
        assertTrue(w.read(60_000L, playing = false, nowMs = quiet + 500L, lengthMs = length).isEmpty())
        assertTrue(w.wanted)
    }

    @Test
    fun `coming back round from the end is not a seek`() {
        val w = playing(at = length - 2_000L)
        val now = quiet + 500L
        w.read(length - 500L, playing = true, nowMs = now, lengthMs = length)
        assertTrue(w.read(200L, playing = true, nowMs = now + 750L, lengthMs = length).isEmpty())
    }

    @Test
    fun `a jump inside the quiet window is Amp's own`() {
        val w = StandInWatch()
        w.expect(0L, playing = true, nowMs = 0L)
        assertTrue(w.read(120_000L, playing = true, nowMs = 500L, lengthMs = length).isEmpty())
        val now = quiet + 500L
        assertTrue(w.read(120_000L + now - 500L, playing = true, nowMs = now, lengthMs = length).isEmpty())
    }
}

class SilenceTest {

    private val dir = createTempDirectory("silence").toFile()

    @Test
    fun `a file is whole frames of one silent MPEG-1 Layer III frame`() {
        val file = Silence.fileFor(dir, 245_000L)
        val bytes = file.readBytes()
        val frames = (245_000L + Silence.FRAME_MS - 1) / Silence.FRAME_MS
        assertEquals(frames * 96, bytes.size.toLong())
        val header = listOf(0xFF, 0xFB, 0x14, 0xC0)
        for (start in listOf(0, 96, bytes.size - 96)) {
            assertEquals(header, (0..3).map { bytes[start + it].toInt() and 0xFF })
            assertTrue((4 until 96).all { bytes[start + it] == 0.toByte() })
        }
    }

    @Test
    fun `it is never shorter than asked`() {
        assertEquals(24L, Silence.lengthOf(1L))
        assertEquals(245_016L, Silence.lengthOf(245_000L))
        assertEquals(240_000L, Silence.lengthOf(240_000L))
    }

    @Test
    fun `a length asked for again is the same file`() {
        val first = Silence.fileFor(dir, 180_000L)
        first.setLastModified(1_000L)
        val again = Silence.fileFor(dir, 180_000L)
        assertEquals(first, again)
        assertEquals(1_000L, again.lastModified())
    }

    @Test
    fun `pruning keeps only the file in use`() {
        val keep = Silence.fileFor(dir, 120_000L)
        val old = Silence.fileFor(dir, 200_000L)
        val writing = dir.resolve("silence-1.mp3.part").apply { writeText("") }
        Silence.prune(dir, keep)
        assertTrue(keep.exists())
        assertFalse(old.exists())
        assertTrue(writing.exists())
    }
}

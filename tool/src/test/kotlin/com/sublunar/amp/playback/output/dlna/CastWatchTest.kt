package com.sublunar.amp.playback.output.dlna

import com.sublunar.amp.playback.output.dlna.CastWatch.Companion.HANDOFF_POLLS_TO_RESTART
import com.sublunar.amp.playback.output.dlna.CastWatch.Companion.STALLED_POLLS_TO_RESTART
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

/**
 * What a cast's poll does after each reading. A wrong answer cuts off a song
 * part-way, restarts one that was playing, or leaves the speaker silent, so
 * the answers are pinned.
 */
class CastWatchTest {

    private val duration = 200_000L

    private fun CastWatch.reads(state: DlnaState, times: Int = 1) = repeat(times) { stall.observe(state) }

    @Test
    fun `a track that played and then stops twice near its end has ended`() {
        val watch = CastWatch().apply { settled = true; heardMs = 195_000L }
        watch.reads(DlnaState.STOPPED)
        assertEquals(CastStep.Wait, watch.next(duration, 0), "one stop can be a re-buffer")
        watch.reads(DlnaState.STOPPED)
        assertEquals(CastStep.Advance, watch.next(duration, 0))
    }

    @Test
    fun `a stop part-way through is a pause where it was last heard`() {
        val watch = CastWatch().apply { settled = true; heardMs = 77_000L }
        watch.reads(DlnaState.STOPPED, 2)
        val step = assertIs<CastStep.PauseThere>(watch.next(duration, 0))
        assertEquals(77_000L, step.atMs)
    }

    @Test
    fun `a stop with nothing heard is the end`() {
        val watch = CastWatch(playedBefore = true)
        watch.reads(DlnaState.STOPPED, 2)
        assertEquals(CastStep.Advance, watch.next(duration, 0))
    }

    @Test
    fun `going quiet never ends or restarts a track that was playing`() {
        val watch = CastWatch().apply { settled = true; heardMs = 77_000L }
        watch.reads(DlnaState.UNKNOWN, 20)
        assertEquals(CastStep.Wait, watch.next(duration, 0))
    }

    @Test
    fun `paused part-way, nothing ends until it plays again`() {
        val watch = CastWatch().apply { settled = true; heardMs = 77_000L; stoppedThere = true }
        watch.reads(DlnaState.STOPPED, 10)
        assertEquals(CastStep.Wait, watch.next(duration, 0))
    }

    @Test
    fun `a track that never plays is restarted, sooner when it is turned away`() {
        val quiet = CastWatch()
        quiet.reads(DlnaState.UNKNOWN, STALLED_POLLS_TO_RESTART - 1)
        assertEquals(CastStep.Wait, quiet.next(duration, 0))
        quiet.reads(DlnaState.UNKNOWN)
        assertEquals(CastStep.Restart, quiet.next(duration, 0))

        val refused = CastWatch()
        refused.reads(DlnaState.STOPPED, 3)
        assertEquals(CastStep.Restart, refused.next(duration, 0))
    }

    @Test
    fun `a track restarted twice is passed over, counted per track`() {
        val watch = CastWatch()
        repeat(2) {
            watch.reads(DlnaState.STOPPED, STALLED_POLLS_TO_RESTART)
            assertEquals(CastStep.Restart, watch.next(duration, 4))
            watch.restarting(4)
        }
        watch.reads(DlnaState.STOPPED, STALLED_POLLS_TO_RESTART)
        assertEquals(CastStep.PassOver, watch.next(duration, 4))
        assertEquals(CastStep.Restart, watch.next(duration, 5), "another track gets its own tries")

        watch.passedOver()
        assertEquals(null, watch.restartedIndex)
        assertEquals(0, watch.restartsHere)
    }

    @Test
    fun `a hand-off that sits stopped is restarted after one more poll`() {
        val dead = CastWatch().apply { settled = true; heardMs = 199_000L }
        dead.handedOff(positionMs = 199_000L, playing = false)
        assertEquals(HANDOFF_POLLS_TO_RESTART, dead.restartAfter)
        assertEquals(null, dead.heardMs)
        assertEquals(199_000L, dead.lastPositionMs)
        dead.reads(DlnaState.STOPPED, HANDOFF_POLLS_TO_RESTART)
        assertEquals(CastStep.Restart, dead.next(duration, 1))

        val gapless = CastWatch().apply { settled = true }
        gapless.handedOff(positionMs = 0L, playing = true)
        assertEquals(STALLED_POLLS_TO_RESTART, gapless.restartAfter)
    }

    @Test
    fun `after an advance the next track starts unproven`() {
        val watch = CastWatch(playedBefore = true).apply { settled = true; heardMs = 199_000L }
        watch.restarting(0)
        watch.reads(DlnaState.STOPPED, 2)
        watch.advanced()

        assertEquals(false, watch.settled)
        assertEquals(false, watch.played)
        assertEquals(null, watch.heardMs)
        assertEquals(null, watch.restartedIndex)
        assertEquals(0, watch.stall.stopped)
    }
}

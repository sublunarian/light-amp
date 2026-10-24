package com.sublunar.amp.playback.output.dlna

/** What a cast's poll does after a reading — see [CastWatch.next]. */
internal sealed interface CastStep {
    /** Nothing to do this time. */
    data object Wait : CastStep

    /** Stopped part-way through, last heard at [atMs]: a pause, not the end. */
    class PauseThere(val atMs: Long) : CastStep

    /** The track ended: on to the next one. */
    data object Advance : CastStep

    /** The track never started: started by hand again. */
    data object Restart : CastStep

    /** Restarted and still not playing: passed over. */
    data object PassOver : CastStep
}

/**
 * What a cast's poll has seen of the track the renderer is on, and so what it
 * does next. Reset as the track changes — see DlnaOutput.pollRenderer.
 */
internal class CastWatch(playedBefore: Boolean = false) {
    /**
     * Seen to play: the renderer said PLAYING, or its clock moved while it
     * wasn't stopped. Until then only a reading near where the track started is
     * believed: for a poll or two a renderer reports the new URI with the old clock.
     */
    var settled = false

    /**
     * Whether a stop can only mean the track ended, as for a track pushed again
     * for a seek: it was playing a moment ago. Otherwise a stop before any poll
     * saw PLAYING reads as never started, and restarts the track.
     */
    var played = playedBefore

    /** What the renderer said last time. A position that hasn't moved is not evidence that anything is playing. */
    var lastPositionMs: Long? = null

    /** Where the track was last heard playing, for telling a stop part-way through from its end — see [stoppedPartWay]. */
    var heardMs: Long? = null

    /** Stopped part-way and shown paused there, until it plays again. */
    var stoppedThere = false

    /**
     * The queue index started by hand, and how often, so a track that refuses
     * to play is asked again a couple of times and then passed over rather
     * than restarted for ever.
     */
    var restartedIndex: Int? = null
    var restartsHere = 0

    /**
     * How patient to be with a track that hasn't started. A stream being opened
     * from scratch deserves a few seconds; one the renderer has *already* taken
     * from the queue and stopped on does not — see [handedOff].
     */
    var restartAfter = STALLED_POLLS_TO_RESTART

    /**
     * A renderer reports STOPPED for a moment while it re-buffers as well as at
     * the end of a track, and the two are indistinguishable from one poll. Two
     * in a row is: the stream really has ended. Readings that went unanswered
     * are counted separately — see [CastStallCounter].
     */
    val stall = CastStallCounter()

    /** What to do now, with the track [durationMs] long at queue [index]. */
    fun next(durationMs: Long, index: Int): CastStep {
        val heard = heardMs
        val stoppedAfterPlaying = (settled || played) && stall.stopped >= STOPPED_POLLS_TO_ADVANCE
        val neverPlayed = !settled && !played
        return when {
            // Paused part-way (below): nothing ends, restarts or is handed on
            // until it plays again, or the stale clock would read the cued
            // track as over and the next song would start under a pause.
            stoppedThere -> CastStep.Wait
            // Stopped part-way through: some remotes pause by STOP, so it is a
            // pause, not the end.
            stoppedAfterPlaying && heard != null && stoppedPartWay(heard, durationMs) -> CastStep.PauseThere(heard)
            // Played, then stopped: the track ended. Only readings the renderer
            // gave count: going quiet never ends a track that was playing.
            stoppedAfterPlaying -> CastStep.Advance
            // Never played: a renderer can take the queued track, transition to
            // it and then sit stopped for ever, so it is started by hand rather
            // than stepped over. One that stopped answering counts too: this
            // decision can afford to be wrong. Three STOPPED readings in a row is
            // a stream turned away (one still being fetched says TRANSITIONING).
            // Twice per track: a song turned away while the stream it replaced
            // was still closing plays on a second try.
            neverPlayed &&
                (stall.notPlaying >= restartAfter || stall.stopped >= TURNED_AWAY_POLLS) &&
                (restartedIndex != index || restartsHere < RESTARTS_BEFORE_PASSING_OVER) -> CastStep.Restart
            // Asked again and it still won't play: something is wrong with this
            // track rather than with the hand-off, and sitting in silence is
            // worse than going on.
            neverPlayed && stall.notPlaying >= restartAfter && restartedIndex == index -> CastStep.PassOver
            else -> CastStep.Wait
        }
    }

    /**
     * The renderer crossed into the track offered next, but hasn't started it:
     * for a second or two it reports STOPPED and the finished track's position,
     * which reads as this track ending. So it gets a fresh start's protection.
     */
    fun handedOff(positionMs: Long, playing: Boolean) {
        stall.reset()
        settled = false
        played = false
        heardMs = null
        stoppedThere = false
        // Seeded with the reading just seen, not cleared: cleared, the next
        // identical stale reading looks like a change and re-arms the very
        // flag this is putting down.
        lastPositionMs = positionMs
        // A working hand-off is already PLAYING here, the gapless case. A dead
        // one sits stopped on the finished track's position and never recovers
        // on its own: one more poll, not the seconds a cold stream needs.
        restartAfter = if (playing) STALLED_POLLS_TO_RESTART else HANDOFF_POLLS_TO_RESTART
    }

    /** The track ended and the queue moved on. */
    fun advanced() {
        settled = false
        played = false
        heardMs = null
        stall.reset()
        restartedIndex = null
        restartAfter = STALLED_POLLS_TO_RESTART
    }

    /** The track at [index] is being started by hand again. */
    fun restarting(index: Int) {
        if (restartedIndex != index) restartsHere = 0
        restartedIndex = index
        restartsHere++
        stall.reset()
        restartAfter = STALLED_POLLS_TO_RESTART
    }

    /** A track that wouldn't play was passed over. */
    fun passedOver() {
        stall.reset()
        restartedIndex = null
        restartsHere = 0
        restartAfter = STALLED_POLLS_TO_RESTART
    }

    internal companion object {
        /** Consecutive STOPPED readings that mean the track really ended. */
        const val STOPPED_POLLS_TO_ADVANCE = 2

        /** Readings of STOPPED in a row, with nothing opening, that say a pushed track was turned away. */
        const val TURNED_AWAY_POLLS = 3

        /**
         * Polls a track that has never played is given before it is started by
         * hand. Longer than the advance threshold: a renderer opening a fresh
         * stream is stopped for a few seconds while it loads.
         */
        const val STALLED_POLLS_TO_RESTART = 6

        /**
         * The same, for a track the renderer took from the queue and stopped on:
         * it had the whole previous track to load, so that hand-off has failed.
         */
        const val HANDOFF_POLLS_TO_RESTART = 2

        /** Fresh restarts of a track the renderer won't start, before it is passed over. */
        const val RESTARTS_BEFORE_PASSING_OVER = 2
    }
}

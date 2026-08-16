package com.sublunar.amp.playback

import com.thelightphone.sdk.cast.DlnaState

/**
 * How long a cast renderer has gone without being seen to play.
 *
 * Two counts, because the two decisions they feed have opposite costs.
 *
 * [stopped] counts only readings where the renderer actually said STOPPED. It
 * decides whether a track that *was* playing has ended, and a false positive
 * there cuts off a track the listener is in the middle of — so a reading that
 * merely went unanswered must not count towards it.
 *
 * [notPlaying] also counts readings that came back UNKNOWN, which is what a
 * failed SOAP call looks like. It decides whether a track that has *never*
 * played needs starting by hand, and a false positive there costs nothing:
 * there was no audio to interrupt. Counting only STOPPED for that was a bug —
 * a renderer that is dead *and* slow to answer flaps between STOPPED and
 * UNKNOWN, every UNKNOWN put the count back to zero, the threshold was never
 * reached, and the cast sat in silence indefinitely.
 *
 * TRANSITIONING clears both. It is the renderer saying it is busy opening a
 * stream, which is the one state that deserves more patience rather than less.
 */
internal class CastStallCounter {

    /** Consecutive readings of STOPPED, and nothing else. */
    var stopped = 0
        private set

    /** Consecutive readings that were not the renderer playing. */
    var notPlaying = 0
        private set

    fun observe(state: DlnaState) {
        when (state) {
            DlnaState.PLAYING, DlnaState.PAUSED, DlnaState.TRANSITIONING -> reset()
            DlnaState.STOPPED -> {
                stopped++
                notPlaying++
            }
            // Not "it is stopped" — "it did not answer". Only the count that can
            // afford to be wrong is allowed to read anything into that.
            DlnaState.UNKNOWN -> {
                stopped = 0
                notPlaying++
            }
        }
    }

    fun reset() {
        stopped = 0
        notPlaying = 0
    }
}

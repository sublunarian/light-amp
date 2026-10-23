package com.sublunar.amp.playback

import com.sublunar.amp.data.TimelineState

/** One timeline report, as it goes to the server — see MusicServer.reportTimeline. */
internal data class TimelineReport(
    val sessionId: String,
    val trackId: String,
    val state: TimelineState,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * The session the server was last told is open, and what to tell it as the
 * song changes and as playback ends.
 *
 * Kept as the id and the song that actually went out, never worked out again
 * later: an id rebuilt from the controller's state may be one the server
 * never saw. A tap in another list minted a new id before the old song was
 * stopped, its "stopped" went out under that id and matched nothing, and the
 * old song stayed on Plex's dashboard long enough for the next one to be
 * refused, with one remote stream allowed per user (#20).
 *
 * A different open session is stopped before the new one is announced. The
 * same session on another song is not stopped: that is the session changing
 * song, which is how Plex keeps one entry for a whole listening — see
 * MusicServer.oneSessionPerPlayer.
 *
 * [emit] queues one report to be sent, in order, and says whether it was
 * queued. One held back (by the data mode, or with no server) changes
 * nothing here, so the next report that goes closes the right session. Its
 * second argument is false for a repeat of what is already open (a
 * heartbeat), true for anything that changes what the server shows.
 *
 * Synchronized, with [emit] called inside the lock: reports come from Main,
 * closes from a source switch on Default too, and the order they are decided
 * in has to be the order they leave in.
 */
internal class SessionHandOver(private val emit: (report: TimelineReport, news: Boolean) -> Boolean) {

    private var open: TimelineReport? = null

    /** The last report that left a session open — playing or paused — or null when none is. */
    val openSession: TimelineReport?
        @Synchronized get() = open

    /** Tell the server [next], stopping first any other session that is open. */
    @Synchronized
    fun report(next: TimelineReport) {
        val prev = open
        if (prev != null && prev.sessionId != next.sessionId) {
            if (!emit(prev.stoppedAtStart(), true)) return
            open = null
        }
        val news = prev == null ||
            prev.sessionId != next.sessionId ||
            prev.trackId != next.trackId ||
            prev.state != next.state
        if (!emit(next, news)) return
        open = next.takeIf { it.state != TimelineState.STOPPED }
    }

    /**
     * Playback is now on [trackId], whose session is [sessionId]. [live]: the
     * song is playing, or about to. Says nothing unless that changes what the
     * server should show.
     *
     * Announced at the song's own length, and from its start unless the
     * caller knows better ([positionMs], as a reopen onto a song part-way
     * through does): a song changing is a song beginning, and the position
     * the controller held a moment ago may still be the last song's, which
     * Plex refuses as longer than this one.
     */
    @Synchronized
    fun songIs(sessionId: String, trackId: String, live: Boolean, durationMs: Long, positionMs: Long = 0L) {
        val prev = open
        if (prev != null && prev.sessionId == sessionId && prev.trackId == trackId) return
        // Nothing open and nothing playing: a queue cued and waiting, as a
        // restore leaves it. Opening a session for a song nobody listens to
        // would put a phone in a pocket on the dashboard, holding a stream.
        if (prev == null && !live) return
        report(
            TimelineReport(
                sessionId = sessionId,
                trackId = trackId,
                state = if (live) TimelineState.PLAYING else TimelineState.PAUSED,
                positionMs = positionMs.coerceIn(0L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE),
                durationMs = durationMs,
            ),
        )
    }

    /**
     * Playback has ended: stop whatever session is open. [trackId] is the song
     * playback was on, and [positionMs] and [durationMs] are where it got to
     * in it; a session open on any other song is stopped from its start, at
     * the length it was announced with, since nothing here knows how far it
     * got.
     */
    @Synchronized
    fun close(trackId: String? = null, positionMs: Long = 0L, durationMs: Long = 0L) {
        val prev = open ?: return
        val stop = if (prev.trackId == trackId) {
            prev.copy(
                state = TimelineState.STOPPED,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.takeIf { it > 0L } ?: prev.durationMs,
            )
        } else {
            prev.stoppedAtStart()
        }
        if (emit(stop, true)) open = null
    }

    /**
     * Stopped at 0, as a resume point for a song left behind would be a
     * guess, and with the length it was announced at, which Plex measures
     * the time against. Jellyfin reads only the position.
     */
    private fun TimelineReport.stoppedAtStart(): TimelineReport =
        copy(state = TimelineState.STOPPED, positionMs = 0L)
}

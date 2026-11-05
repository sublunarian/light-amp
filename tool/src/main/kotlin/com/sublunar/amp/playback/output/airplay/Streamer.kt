package com.sublunar.amp.playback.output.airplay

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * Where a run of playing has got to, as the speaker hears it: the queue row
 * and how far into its track. [sounding] once the first packet has played.
 */
internal data class Heard(val segment: Segment, val positionMs: Long, val sounding: Boolean)

/** What a [Streamer] tells whoever runs it. Called on the streamer's thread. */
internal interface StreamEvents {
    /** About every quarter second, where the speaker is. */
    fun heard(heard: Heard)

    /** The feed ran dry for good, and the speaker has played the last of it. */
    fun finished(end: Feed.End, last: Heard?)

    /** Couldn't start: the first track wouldn't read. */
    fun couldNotStart()

    /** The network or the speaker went away under the stream: the last speaker, or the only one. */
    fun lost(cause: IOException)

    /** One speaker of several went away; the others play on. */
    fun leftGroup(session: RaopSession, cause: IOException) {}
}

/**
 * The real-time half: packets from a [Feed] to one [RaopSession] or several,
 * each at its moment, 44 100 frames a second by this phone's clock, with a
 * sync once a second so every speaker knows which of them is due when.
 *
 * Several speakers play in step because they hear one schedule: every packet
 * goes to all of them at once and each is told the same moment for it, in its
 * own RTP numbers (each session counts from its own random start). One that
 * joins partway is told where that schedule stands before its first packet,
 * and is heard ~2.25 s later; the others play on untouched.
 *
 * Packet k of a run goes out at t0 + k·352/44100 s and is heard
 * [RaopSession.latencyFrames] later. A stall longer than the speaker's
 * buffer — the network gone quiet, the phone busy — can't be made up by
 * sending faster, so the stream starts its clock again from where it is
 * (the speaker hears a gap, never sound out of time).
 */
internal class Streamer(
    first: List<RaopSession>,
    private val feed: Feed,
    private val events: StreamEvents,
    private val log: (String) -> Unit,
) {
    @Volatile
    private var stopping = false
    private val thread = Thread({ run() }, "AmpAirPlay-send").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY
    }

    /** A speaker the packets go to, and where its RTP numbers stand against the schedule. */
    private class Leg(val session: RaopSession) {
        /** The RTP time its packet [anchorK] carried. */
        @Volatile
        var anchorRtp = 0L

        @Volatile
        var anchorK = 0L

        /** Not yet told the current schedule: its next packet is its first. */
        @Volatile
        var fresh = true
    }

    private val legs = CopyOnWriteArrayList(first.map(::Leg))

    /** One latency for every speaker, so all are told the same moment for a packet. */
    private val latencyFrames = first.maxOf { it.latencyFrames }
    private val heardAfterFrames = latencyFrames + RaopSession.RECEIVER_OFFSET_FRAMES

    // The current anchor: packet 0 of it due at [t0], being run frame [anchorFrame].
    @Volatile
    private var t0 = 0L

    @Volatile
    private var anchorFrame = 0L

    /** Packets sent under the current anchor. */
    @Volatile
    private var k = 0L

    @Volatile
    private var started = false
    private var framesSent = 0L

    /** The furthest run frame the speaker was ever reckoned to be at: what it hears never goes back. */
    @Volatile
    private var furthestHeard = -1L

    fun start() {
        feed.start()
        thread.start()
    }

    /** The speakers being sent to. */
    val sessions: List<RaopSession> get() = legs.map { it.session }

    /** Send to [session] too, from the next packet, in step with the rest. */
    fun join(session: RaopSession) {
        if (legs.any { it.session === session }) return
        if (session.latencyFrames > latencyFrames) log("${session.receiver.name} asks more latency than the group plays at")
        legs += Leg(session)
    }

    /** Stop sending to [session]; the others carry on. */
    fun leave(session: RaopSession) {
        legs.removeAll { it.session === session }
    }

    /** Stop sending, and where the speaker had got to. Waits for the thread, briefly. */
    fun stop(): Heard? {
        stopping = true
        feed.stop()
        thread.interrupt()
        runCatching { thread.join(STOP_WAIT_MS) }
        return heardNow()
    }

    /** Where the speaker is now, or null before anything has been laid. */
    fun heardNow(): Heard? {
        // A copy taken at once: the feed may drop a segment that gave nothing.
        val segments = ArrayList(feed.segments)
        if (segments.isEmpty()) return null
        if (!started) return Heard(segments.first(), segments.first().startMs, sounding = false)
        val frame = playingFrame(System.nanoTime())
        val sounding = frame >= 0
        val at = frame.coerceAtLeast(0).coerceAtLeast(furthestHeard).coerceAtMost(framesSent)
        val segment = segments.lastOrNull { it.firstFrame <= at } ?: segments.first()
        val ms = segment.startMs + (at - segment.firstFrame) * 1000 / AIRPLAY_RATE
        return Heard(segment, ms, sounding)
    }

    /** The RTP time run frame [frame] carries at [session], or null before it has had a packet of this schedule. */
    fun rtpOf(session: RaopSession, frame: Long): Long? {
        val leg = legs.firstOrNull { it.session === session }?.takeIf { !it.fresh } ?: return null
        return (leg.anchorRtp + (frame - anchorFrame - leg.anchorK * AlacFrames.FRAMES_PER_PACKET)) and 0xffffffffL
    }

    /** The RTP time [session] is hearing now. */
    fun rtpNow(session: RaopSession): Long? = rtpOf(session, playingFrame(System.nanoTime()))

    /** The run frame the speakers are playing at [now]: negative before the first is heard. */
    private fun playingFrame(now: Long): Long =
        anchorFrame + (now - t0) * AIRPLAY_RATE / 1_000_000_000L - heardAfterFrames

    private fun run() {
        try {
            if (!prebuffer()) return
            anchor(System.nanoTime() + START_LEAD_NS)
            var lastSync = System.nanoTime()
            var lastHeard = 0L
            while (!stopping) {
                val due = dueOf(k)
                val now = System.nanoTime()
                if (now < due) {
                    LockSupport.parkNanos(minOf(due - now, MAX_PARK_NS))
                    continue
                }
                val packet = feed.packets.peek()
                if (packet == null) {
                    val end = feed.ended
                    if (end != null && feed.packets.isEmpty()) {
                        finish(end)
                        return
                    }
                    // Starved: wait for the feed. Once sound is back, a wait
                    // that ran too long starts the clock again, below.
                    LockSupport.parkNanos(STARVED_PARK_NS)
                    continue
                }
                // Too late to reach the speaker in time: with sound in hand,
                // the clock starts again from now rather than from the past.
                if (now - due > resyncAfterNs()) {
                    log("stalled ${(now - due) / 1_000_000} ms → the clock starts again")
                    furthestHeard = maxOf(furthestHeard, playingFrame(now).coerceAtMost(framesSent))
                    anchor(now + START_LEAD_NS)
                    continue
                }
                feed.packets.poll()
                for (leg in legs) send(leg, packet, due)
                k++
                framesSent += AlacFrames.FRAMES_PER_PACKET
                if (now - lastSync >= SYNC_EVERY_NS) {
                    // The next packet's place in the schedule.
                    for (leg in legs) {
                        val rtp = (leg.anchorRtp + (k - leg.anchorK) * AlacFrames.FRAMES_PER_PACKET) and 0xffffffffL
                        runCatching { leg.session.sendSync(rtp, dueOf(k), first = false, latencyFrames) }
                    }
                    lastSync = now
                }
                if (now - lastHeard >= HEARD_EVERY_NS) {
                    heardNow()?.let { events.heard(it) }
                    lastHeard = now
                }
            }
        } catch (e: IOException) {
            if (!stopping) events.lost(e)
        } catch (_: InterruptedException) {
            // Stopped.
        } catch (e: RuntimeException) {
            // Anything else ends the stream as lost rather than the app.
            if (!stopping) events.lost(IOException(e))
        }
    }

    /**
     * One packet to one speaker. One new to this schedule learns where it
     * stands first. A speaker that can't be sent to leaves the group; the
     * last one ends the stream.
     */
    private fun send(leg: Leg, packet: ByteArray, due: Long) {
        try {
            val first = leg.fresh
            if (first) {
                leg.anchorRtp = leg.session.nextRtp
                leg.anchorK = k
                leg.session.sendSync(leg.anchorRtp, due, first = true, latencyFrames)
                leg.fresh = false
            }
            leg.session.sendAudio(packet, packet.size, first)
        } catch (e: IOException) {
            if (legs.size <= 1) throw e
            legs.remove(leg)
            events.leftGroup(leg.session, e)
        }
    }

    /**
     * Wait for the feed to have a little laid by, so the first packets don't
     * starve. False when it never will: stopped, or nothing could be read.
     */
    private fun prebuffer(): Boolean {
        val deadline = System.nanoTime() + PREBUFFER_LIMIT_NS
        while (!stopping) {
            if (feed.bufferedFrames >= PREBUFFER_FRAMES) return true
            val end = feed.ended
            if (end == Feed.End.UNREADABLE) {
                events.couldNotStart()
                return false
            }
            if (end != null) return feed.packets.isNotEmpty() || run { finish(end); false }
            if (System.nanoTime() > deadline && feed.packets.isNotEmpty()) return true
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
        return false
    }

    /** When packet [k] of the current clock is due to go. */
    private fun dueOf(k: Long): Long = t0 + k * AlacFrames.FRAMES_PER_PACKET * 1_000_000_000L / AIRPLAY_RATE

    private fun anchor(at: Long) {
        t0 = at
        anchorFrame = framesSent
        k = 0
        for (leg in legs) leg.fresh = true
        started = true
    }

    /** Everything is sent: wait until the speaker has played it, then say so. */
    private fun finish(end: Feed.End) {
        val lastPlaysAt = t0 + ((framesSent - anchorFrame) + heardAfterFrames) * 1_000_000_000L / AIRPLAY_RATE
        while (!stopping) {
            val left = lastPlaysAt - System.nanoTime()
            if (left <= 0) break
            heardNow()?.let { events.heard(it) }
            LockSupport.parkNanos(minOf(left, HEARD_EVERY_NS))
        }
        if (!stopping) events.finished(end, heardNow())
    }

    /** A stall can be made up as long as what follows still reaches the speaker before it is due. */
    private fun resyncAfterNs(): Long = latencyFrames * 1_000_000_000L / AIRPLAY_RATE * 3 / 4

    private companion object {
        const val SYNC_EVERY_NS = 1_000_000_000L
        const val HEARD_EVERY_NS = 250_000_000L
        const val MAX_PARK_NS = 20_000_000L
        const val STARVED_PARK_NS = 2_000_000L
        const val START_LEAD_NS = 30_000_000L

        /** A quarter second laid by before the first packet goes. */
        const val PREBUFFER_FRAMES = AIRPLAY_RATE / 4

        /** A slow start (a transcode warming up) plays what it has after this. */
        val PREBUFFER_LIMIT_NS = TimeUnit.SECONDS.toNanos(20)
        const val STOP_WAIT_MS = 1_000L
    }
}

package com.sublunar.amp.playback

import android.util.Log
import com.sublunar.amp.App
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.PendingAction
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.UNREACHABLE
import com.sublunar.amp.data.WriteResult
import com.sublunar.amp.data.attemptWrite
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * What the server is told about playback: the timeline (the session a
 * dashboard shows), now-playing, the plays that count, and the queue for
 * another client to pick up. It reads the controller's state and never
 * writes it; the controller says what the state alone doesn't show: a press,
 * a seek, an end.
 */
internal class ServerReports(
    private val scope: CoroutineScope,
    private val serverClient: StateFlow<MusicServer?>,
    /**
     * Whether the server may be spoken to at all — see App.metadataAllowed.
     * It gates the small talk (timelines, now-playing, scrobbles), not what
     * may be streamed.
     */
    private val metadataAllowed: () -> Boolean,
    private val queue: StateFlow<List<Track>>,
    private val index: StateFlow<Int>,
    private val positionMs: StateFlow<Long>,
    private val durationMs: StateFlow<Long>,
    private val isPlaying: StateFlow<Boolean>,
    private val buffering: StateFlow<Boolean>,
    /** The output with the sound reports its own playback (a Companion player), so nothing is said for it here. */
    private val reportsElsewhere: () -> Boolean,
    /** Whether the song playback is on is playing or about to — see PlaybackController.playbackWanted. Main only. */
    private val playbackWanted: () -> Boolean,
    /** Whether a handle is connected, so that what the service plays can be seen. Main only. */
    private val handleReady: () -> Boolean,
) {
    /**
     * The id the server knows playback by, on timeline reports and on the
     * streams they report on — see [MusicServer.streamUrl]. The server says
     * which of two policies ([MusicServer.oneSessionPerPlayer]):
     *
     * - One session for the whole listening, changing song as it goes, as
     *   Plex wants and Plexamp does: this id, minted once per process and again
     *   by [newListening]. An id per song put a dashboard entry per song, and
     *   with one remote stream allowed per user the next song was refused (503)
     *   while the last one was still listed (#20).
     * - One per song, as Jellyfin keeps: [playbackSession] plus the song's id,
     *   new with each queue. Derived rather than stored, because the whole
     *   queue becomes audio items at once and each item's URL has to agree
     *   with the timeline however far ahead it was built.
     *
     * A "stopped" never works these out again: it goes out under the id that
     * was announced — see [sessions].
     */
    @Volatile
    private var listeningSession: String = newSessionId()

    private val _playbackSession = MutableStateFlow(newSessionId())

    /** The queue's part of a per-song id; a flow, so a new queue of the same songs is still seen — see [follow]. */
    val playbackSession: StateFlow<String> = _playbackSession

    /**
     * The timeline reports on their way to the server, sent one at a time in
     * the order they were decided, so a song's "stopped" can't overtake the
     * next one's "playing".
     */
    private val timelineOut = Channel<TimelineSend>(Channel.UNLIMITED)

    private sealed interface TimelineSend {
        /** [report], to the server that was current when it was decided. */
        class Report(val client: MusicServer, val report: TimelineReport) : TimelineSend

        /** Everything queued before this has been sent — see [flush]. */
        class Flush(val done: CompletableDeferred<Unit>) : TimelineSend
    }

    /**
     * The session the server was last told is open, kept as it was announced
     * — see [SessionHandOver]. Every report goes through it, so a change of
     * song or an end to playback stops exactly the session said before it.
     */
    private val sessions = SessionHandOver(::queueTimeline)

    /**
     * The plays begun so far, one per run of a song from its start — see
     * [songStarted]. Now-playing and the submission are each sent once per
     * play, so a song heard again counts again: under repeat-one, queued
     * twice, or tapped again in the same queue.
     */
    private var play = 0

    /** Where the current play is — see [startsAPlay]. */
    private var playAt: PlayAt? = null

    /** The last play announced as playing now — see [announceNowPlaying]. */
    private var announcedPlay = -1

    /** The last play submitted — see [maybeSubmitPlay]. */
    private var countedPlay = -1

    /** The play and position last heard — see [heard]. */
    private var lastHeard: Pair<Int, Long>? = null

    /** The sources a refused play has been said for this run — see [maybeSubmitPlay]. */
    private val playRefusalSaid: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Identity of the queue last handed to the server — see [pushQueue]. */
    private var lastSavedQueueKey: Int? = null

    init {
        // Sends the timeline reports, in order — see [timelineOut].
        scope.launch {
            for (send in timelineOut) {
                when (send) {
                    is TimelineSend.Report -> runCatching {
                        val r = send.report
                        send.client.reportTimeline(r.sessionId, r.trackId, r.state, r.positionMs, r.durationMs)
                    }
                    is TimelineSend.Flush -> send.done.complete(Unit)
                }
            }
        }
    }

    /** Start following playback for the server, on [bound]: the plays that count, the heartbeat and the song. */
    fun follow(bound: Job) {
        // All three on Main, where every change of queue and song is made and
        // songStarted is called: a report never reads a new queue's session
        // against the old queue's song, and plays are counted on one thread.
        val onMain = bound + Dispatchers.Main
        scope.launch(onMain) { positionMs.collect { heard(it) } }
        // The heartbeat: Plex drops a session nothing pings for a while. Only
        // while playing, and only with a handle connected: without one nothing
        // here sees what the service plays, and a heartbeat would go on
        // reporting a frozen song for as long as the process lived. Leaving
        // closes the session instead, and the reopen opens it again.
        scope.launch(onMain) {
            while (true) {
                delay(TIMELINE_INTERVAL_MS)
                if (isPlaying.value && handleReady()) timeline(TimelineState.PLAYING)
            }
        }
        // The one place a change of song reaches the server, however it came
        // about: a tap, next, gapless, a skip, a restore, a removal, the speaker
        // moving on. The queue's session is watched too, so the same song tapped
        // in a new list is the new session it is where the server keeps one per
        // song. Not immediate: it runs after whatever set the queue and the
        // index, and never reads a new queue under the old index.
        scope.launch(onMain) {
            combine(queue, index, playbackSession) { _, _, _ -> }
                .collect { followCurrentSong() }
        }
    }

    // --- Sessions ------------------------------------------------------------

    /**
     * A new queue: a new session where the server keeps one per song, so the
     * same track an hour later isn't the old session still going. Where it
     * keeps one per listening (Plex), that one carries on.
     */
    fun newQueue() {
        _playbackSession.value = newSessionId()
    }

    /**
     * What plays next is a listening of its own, under a new id where the
     * server keeps one per listening. Minted once the queue is gone, so
     * nothing reads it against the old songs.
     */
    fun newListening() {
        listeningSession = newSessionId()
    }

    /** The id the server knows this song's playback by, which every timeline report about it carries. */
    private fun sessionIdFor(trackId: String): String =
        if (serverClient.value?.oneSessionPerPlayer == true) listeningSession else "${_playbackSession.value}:$trackId"

    /**
     * The id a stream of this song carries. On a server with one session per
     * player, the listening session's own id, but only on a stream the
     * loopback door decides first ([decidedFirst]; see
     * [TrackSources.playerUrl]): Plex refuses a transcode under a session it
     * tracks unless a decision for that very song came first. Anything else
     * that fetches on its own (a speaker, the player while the door is down)
     * gets an id of its own that no timeline names. That also keeps apart two
     * transcodes in flight at once, as a speaker's next song is: Plex keeps
     * one per id and tears the other down.
     */
    fun streamSessionIdFor(trackId: String, decidedFirst: Boolean): String =
        if (serverClient.value?.oneSessionPerPlayer == true && !decidedFirst) {
            "$listeningSession:$trackId"
        } else {
            sessionIdFor(trackId)
        }

    // --- The timeline --------------------------------------------------------

    /**
     * Tell the server this session is playing or paused, through [sessions],
     * so a session open under another id is stopped first. Queued, not
     * awaited: a dropped heartbeat only leaves the dashboard stale until the
     * next one.
     */
    fun timeline(state: TimelineState, positionMs: Long = this.positionMs.value) {
        // A far end that reports itself (a Companion player) would have every
        // play counted twice.
        if (reportsElsewhere()) return
        val track = queue.value.getOrNull(index.value) ?: return
        // A phone file has nothing on the server to report on.
        if (PhoneFiles.isPhoneFile(track.id)) return
        sessions.report(
            TimelineReport(sessionIdFor(track.id), track.id, state, positionMs, durationMs.value),
        )
    }

    /**
     * A seek moved the place in the song, here or wherever the sound is: the
     * server hears it now, not at the next heartbeat, as a server that keeps
     * the place (Navidrome) would read a jump ahead as that much listened.
     * Only for a session already open: a cued queue that never played opens
     * none.
     */
    fun seeked(ms: Long) {
        if (sessions.openSession == null) return
        val live = isPlaying.value || buffering.value
        // Within the song: Plex refuses a time past its length.
        val at = ms.coerceIn(0L, durationMs.value.takeIf { it > 0L } ?: Long.MAX_VALUE)
        timeline(if (live) TimelineState.PLAYING else TimelineState.PAUSED, positionMs = at)
    }

    /**
     * Bring the server's session up to the song playback is on now — see the
     * follower in [follow]. Says nothing unless what the server shows has to
     * change. Main only: the queue, the index and the player's intent are
     * read together. [atMs] is where in the song playback is, when that is
     * the song's own reading rather than the last song's. A change of song
     * also starts a play — see [songStarted].
     */
    fun followCurrentSong(atMs: Long = 0L) {
        val q = queue.value
        val i = index.value
        val track = q.getOrNull(i)
        when {
            track == null -> playAt = null
            startsAPlay(playAt, q, i, track) -> songStarted()
            else -> playAt = PlayAt(q, i, track)
        }
        if (reportsElsewhere()) return
        if (track == null || PhoneFiles.isPhoneFile(track.id)) {
            // Nothing of the server's plays any more: an emptied queue, or the
            // last song taken out of it.
            sessions.close()
            return
        }
        sessions.songIs(
            sessionIdFor(track.id),
            track.id,
            live = playbackWanted(),
            positionMs = atMs,
            durationMs = track.durationMs,
        )
    }

    /**
     * Playback has ended: stop whatever session is open — see
     * [SessionHandOver.close] for [trackId], [positionMs] and [durationMs].
     */
    fun close(trackId: String? = null, positionMs: Long = 0L, durationMs: Long = 0L) {
        sessions.close(trackId, positionMs, durationMs)
    }

    /**
     * Queue [report] for the server that is current now, the one it is about,
     * even if a source switch swaps the client before it goes. Called by
     * [sessions] as it decides; false when it can't go, and the session is
     * then left as it was.
     */
    private fun queueTimeline(report: TimelineReport, news: Boolean): Boolean {
        // Not where the mode forbids talking to the server. A heartbeat
        // replayed later would be worse than none, and a stop held back isn't
        // lost: the session stays open in [sessions], and the next report that
        // may go stops it. The play itself is kept — see maybeSubmitPlay.
        if (!metadataAllowed()) return false
        val client = serverClient.value ?: return false
        if (news) {
            Log.i(
                "AmpSession",
                "${report.state.name.lowercase()} ${report.trackId} under ${report.sessionId}",
            )
        }
        return timelineOut.trySend(TimelineSend.Report(client, report)).isSuccess
    }

    /** Wait until every timeline report queued so far has been sent, or has failed. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        if (timelineOut.trySend(TimelineSend.Flush(done)).isSuccess) done.await()
    }

    // --- Now-playing and plays -----------------------------------------------

    /**
     * The current song starts a play, from its beginning: a new queue, a
     * restart, a row tapped, a song that ended into itself. It is announced,
     * and counts again once enough of it is heard. A change of song is seen
     * by [followCurrentSong], which calls this.
     */
    fun songStarted() {
        play++
        val q = queue.value
        val i = index.value
        playAt = q.getOrNull(i)?.let { PlayAt(q, i, it) }
        announceNowPlaying()
    }

    /**
     * A new position for the song playing. A song that ended into its own
     * start (repeat-one, or a speaker repeating it) never changes the index,
     * so this is where it is seen: the last reading near the end and this
     * one near the start, in the same play.
     */
    private fun heard(positionMs: Long) {
        val before = lastHeard
        lastHeard = play to positionMs
        if (before != null && before.first == play && endedIntoItself(before.second, positionMs, durationMs.value)) {
            songStarted()
        }
        maybeSubmitPlay(positionMs)
    }

    /**
     * Say what is playing, the moment it starts — see [MusicServer.scrobble].
     * Never kept for a retry: "playing this now" replayed an hour later is
     * false, where a play is true whenever it arrives.
     */
    fun announceNowPlaying() {
        // A far end that reports itself tells the server.
        if (reportsElsewhere()) return
        val track = queue.value.getOrNull(index.value) ?: return
        // A phone file: no server to tell.
        if (PhoneFiles.isPhoneFile(track.id)) return
        if (announcedPlay == play) return
        if (!metadataAllowed()) return
        announcedPlay = play
        val client = serverClient.value ?: return
        scope.launch { runCatching { client.scrobble(track.id, submission = false) } }
    }

    /**
     * How much of a track has to be heard before the play counts: half of it
     * or four minutes, whichever comes first (Last.fm's rule, which the server
     * applies to whatever it forwards). Four minutes for a stream that never
     * declared its length.
     */
    private fun submitAfterMs(durationMs: Long): Long =
        if (durationMs <= 0L) SUBMIT_AFTER_MS else minOf(durationMs / 2, SUBMIT_AFTER_MS)

    /** Submit the play once enough of the track has been heard — see [submitAfterMs]. */
    private fun maybeSubmitPlay(positionMs: Long) {
        val track = queue.value.getOrNull(index.value) ?: return
        if (PhoneFiles.isPhoneFile(track.id)) return
        if (countedPlay == play) return
        if (positionMs < submitAfterMs(durationMs.value)) return
        countedPlay = play
        // Stamped now, sent whenever: a play made offline still belongs at the
        // time it happened.
        val at = System.currentTimeMillis()
        val client = serverClient.value
        // The source the play was heard on, the only one it is ever sent to.
        val source = App.source.value.id
        scope.launch {
            // Local first, so the library's order follows the listening whether
            // or not the server ever hears of it.
            runCatching { App.library.markPlayed(track.id) }
            // A play is durable as a heartbeat isn't: where the mode forbids the
            // bytes it is queued, as for an unreachable server, not dropped. A
            // gate that can't be read errs towards the queue.
            val allowed = runCatching { metadataAllowed() }.getOrDefault(false)
            val result = if (allowed && client != null) {
                attemptWrite { client.scrobble(track.id, at) }
            } else {
                WriteResult.Unreachable(UNREACHABLE)
            }
            when (result) {
                WriteResult.Saved -> Unit
                // Guarded: a play can land before App has finished wiring itself up.
                is WriteResult.Unreachable -> runCatching {
                    App.pending.add(
                        PendingAction(PendingAction.Kind.SCROBBLE, track.id, atMs = at, sourceId = source),
                    )
                }
                // Not kept, as the server would refuse it every time. Said once
                // per source and run: a server that refuses one play tends to
                // refuse them all.
                is WriteResult.Refused -> {
                    Log.w("AmpSync", "play of ${track.id} refused: ${result.reason}")
                    if (playRefusalSaid.add(source)) runCatching { App.tell("Couldn't save\n${result.reason}") }
                }
            }
        }
    }

    // --- The queue on the server ---------------------------------------------

    /**
     * Hand the queue to the server, so another client can pick it up. Only
     * when the queue or its track changed, unlike the local snapshot, as this
     * is a round trip; the position riding along is from the change of track,
     * and exact when leaving forces a last push.
     */
    suspend fun pushQueue(tracks: List<Track>, force: Boolean) {
        val client = serverClient.value ?: return
        val at = index.value.coerceAtLeast(0)
        val currentId = tracks.getOrNull(at)?.id
        // A window from the playing track on: Subsonic takes one URL parameter
        // per id, and a long queue would be a request the server, or a proxy
        // in front of it, refuses silently. What is ahead is what another
        // device can use.
        val ids = if (tracks.size <= MAX_SAVED_QUEUE) {
            tracks.map { it.id }
        } else {
            tracks.subList(at, minOf(at + MAX_SAVED_QUEUE, tracks.size)).map { it.id }
        }
        val key = 31 * ids.hashCode() + currentId.hashCode()
        if (!force && key == lastSavedQueueKey) return
        lastSavedQueueKey = key
        runCatching { client.savePlayQueue(ids, currentId, positionMs.value) }
    }

    private companion object {
        fun newSessionId(): String = UUID.randomUUID().toString()

        /** How often a live session is re-announced; Plexamp does about this. */
        const val TIMELINE_INTERVAL_MS = 10_000L

        /** The most that has to be heard for a play to count — see submitAfterMs. */
        const val SUBMIT_AFTER_MS = 4 * 60 * 1000L

        /**
         * How many of the queue's tracks the server is told about: a few
         * kilobytes of ids in a URL, under the eight that proxies commonly cut
         * off, and far enough ahead for another device to resume.
         */
        const val MAX_SAVED_QUEUE = 100
    }
}

/** Where a play is: its queue, its place there and its track. */
internal class PlayAt(val queue: List<Track>, val index: Int, val track: Track)

/**
 * Whether playback on [track], at [index] of [queue], is a new play after
 * [last]. The same track where an edit moved it is the same play; another
 * track, or playback moving along the same list (a song queued twice in a
 * row), is a new one.
 */
internal fun startsAPlay(last: PlayAt?, queue: List<Track>, index: Int, track: Track): Boolean =
    last == null || track !== last.track || (queue === last.queue && index != last.index)

/** How near its end, then its start, a song is read when it has ended into itself — see [endedIntoItself]. */
private const val WRAP_SLACK_MS = 3_000L

/**
 * Whether a song read at [beforeMs] and then at [nowMs] ended into its own
 * start: from near its end, [lengthMs], to near its beginning. Unknowable
 * without a length.
 */
internal fun endedIntoItself(beforeMs: Long, nowMs: Long, lengthMs: Long): Boolean =
    lengthMs > 0L && beforeMs >= lengthMs - WRAP_SLACK_MS && nowMs < WRAP_SLACK_MS

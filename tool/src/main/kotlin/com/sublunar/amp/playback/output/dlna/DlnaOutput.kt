package com.sublunar.amp.playback.output.dlna

import android.os.SystemClock
import android.util.Log
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.Track
import com.sublunar.amp.playback.output.Disconnect
import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget
import com.sublunar.amp.playback.output.PREVIOUS_RESTARTS_AFTER_MS
import com.sublunar.amp.playback.output.Refound
import com.sublunar.amp.playback.output.VolumeControl
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Playing to a DLNA renderer. A renderer holds one URI, and optionally the
 * next, so this side drives: the queue and the index stay the controller's, a
 * poll once a second mirrors the renderer's clock back into them, and the
 * queue advances from here as each track ends, honouring repeat. The phone
 * reports the playback to the server, as for its own player.
 */
internal class DlnaOutput(private val host: OutputHost) : Output {
    override val family = DlnaFamily

    override val logTag = "AmpCast"

    // A renderer tells the server nothing: the phone reports for it.
    override val reportsItself = false

    override val needsLan = true

    // The fader drives the renderer's own RenderingControl.
    override val volumeControl: StateFlow<VolumeControl> = MutableStateFlow(VolumeControl.Ours)

    /** The stream the renderer was given for the queue's current track. */
    private class CastStream(
        val url: String,
        /**
         * How far into the track the stream starts. A UPnP Seek is a
         * byte-range request, which a live transcode can't answer: the
         * renderer refuses it and plays from the top. So a stream that must
         * start part-way asks the server for it with a timeOffset, and the
         * readouts shift back.
         */
        val offsetMs: Long,
    )

    /** What the renderer is playing, or null when nothing of the queue is on it. */
    private var stream: CastStream? = null

    /** Where the current track was started from — see pollRenderer. */
    private var startedAtMs = 0L

    /** A seek sent to the renderer in place, and when. */
    private class PendingSeek(val targetMs: Long, val sentAt: Long)

    /**
     * The seek in place in flight, until a reading near its target arrives,
     * or [CAST_SEEK_WAIT_MS] goes by — see pollRenderer.
     */
    private var pendingSeek: PendingSeek? = null

    /**
     * Bumped by every seek, push and change of track, so the check a seek
     * leaves behind can't act on whatever replaced it — see [verifyCastSeek].
     * A previous pressed soon after a seek must not read as that seek missing.
     */
    private var seekGeneration = 0

    /**
     * The renderer's next slot as this side knows it: what was offered for
     * it, for which song and queue slot, and whether the renderer said it took
     * it. Filled near a track's end — see [armNextIfDue].
     */
    private class NextOffer {
        /**
         * The queue index armed for, or offered and refused. Arming is tried at
         * every poll near a track's end, so without this a renderer lacking
         * SetNextAVTransportURI would be asked every second.
         */
        var armedFor: Int? = null

        /**
         * The track offered, by id. A queue edited since may have moved it or
         * taken it out, and the slot it was offered for then names some other
         * song — see [offeredNextIndex].
         */
        var trackId: String? = null

        /**
         * The address offered, set before the reply: a renderer can take the
         * address and lose the answer (straight after a seek, say), and its
         * hand-off must still be seen. A lost reply is not a refusal.
         */
        var offered: String? = null

        /**
         * The address the renderer said it took. Set when the reply comes, even
         * one that comes after the offer was forgotten: the renderer may hold
         * it all the same, and a hand-off into it is still followed.
         */
        var taken: String? = null

        /** The address a hand-off shows up as: GetPositionInfo reporting it is the only evidence. */
        val url: String? get() = taken ?: offered

        fun offer(index: Int, id: String, address: String) {
            armedFor = index
            taken = null
            offered = address
            trackId = id
        }

        fun forget() {
            taken = null
            offered = null
            trackId = null
            armedFor = null
        }
    }

    private val nextOffer = NextOffer()

    /**
     * The renderer holds nothing to resume (the queue ran out there, say). Play
     * pushes the queue's track from where the clock stands, since a bare resume
     * would replay the last song, and a seek only moves the clock until then.
     */
    @Volatile
    private var waitingForPlay = false

    /** The queue index the renderer was on when the link took it away — see [findAgain]. */
    private var lostAtIndex: Int? = null

    private var job: Job? = null

    // "rendererId|format" -> stream format actually sent + its MIME type.
    // Concurrent: a source switch clears it from wherever stop was called,
    // while a cast job on its way out may still be writing it.
    private val formats = ConcurrentHashMap<String, Pair<StreamFormat, String>>()

    /**
     * Whether a track's stream can be seeked, by track and format: mp3 asked of
     * a flac original is a live transcode, of an mp3 one the file itself, and
     * only the file seeks. Only settled answers are kept, so a failed probe is
     * asked again. Concurrent, as [formats] is.
     */
    private val seekableStreams = ConcurrentHashMap<String, Boolean>()

    /** Whatever an earlier seek left to check is moot: a seek, a push or a change of track has replaced it. */
    private fun dropSeekChecks() {
        seekGeneration++
    }

    /** The renderer this is steering, or null once it has been let go. */
    private fun steered(): DlnaRenderer? = (host.steering(this) as? DlnaTarget)?.renderer

    private fun log(message: String) {
        Log.i(logTag, message)
    }

    /** Renderers on the network, after a few seconds of listening. None off Wi-Fi: see DlnaCast.allowsNetwork. */
    override suspend fun find(): List<OutputTarget> = DlnaCast.discover().map(::DlnaTarget)

    /**
     * Move playback to the renderer: hand it the current track's stream URL
     * at the position we'd reached, and start polling it for progress.
     */
    override fun connect(target: OutputTarget, fromMs: Long, play: Boolean) {
        val renderer = (target as DlnaTarget).renderer
        job?.cancel()
        job = host.scope.launch {
            undoHush(renderer)
            // The fader takes the renderer's level, and the phone's own follows,
            // so the rocker nudges from where the speaker is.
            DlnaCast.volume(renderer)?.let {
                host.showVolume(it)
                withContext(Dispatchers.Main.immediate) { host.setPhoneVolume(it) }
            }
            stopIfBusy(renderer)
            when {
                // Paused here: chosen, and nothing is sent until a play, which
                // pushes the song from the phone's place — see waitingForPlay.
                !play && host.queue.value.getOrNull(host.index.value) != null -> {
                    forgetCast()
                    waitingForPlay = true
                }
                startCastTrack(renderer, fromMs) -> pollRenderer(renderer)
                // Nothing queued yet: chosen, it waits for the queue.
                host.queue.value.getOrNull(host.index.value) == null -> Unit
                // Nothing played there: back to the phone, paused where the cast
                // began, rather than naming a speaker that stays silent.
                else -> {
                    log("couldn't start on ${renderer.name} → back to the phone")
                    host.dropped(this@DlnaOutput, target)
                }
            }
        }
    }

    /**
     * Stop steering the renderer, for [why]: told to stop (and waited for,
     * briefly, when the app is being left), or with the link gone just let go,
     * keeping what it was given to know it by when the link is back — see
     * [findAgain].
     */
    override fun disconnect(target: OutputTarget, why: Disconnect) {
        val renderer = (target as DlnaTarget).renderer
        job?.cancel()
        job = null
        dropSeekChecks()
        pendingSeek = null
        when (why) {
            Disconnect.LinkLost -> lostAtIndex = host.index.value.takeIf { it >= 0 }
            Disconnect.Leaving -> {
                forgetCast()
                // A renderer keeps playing the URI it was handed after the app
                // leaves, so it is told to stop, bounded so an unreachable one
                // can't hang teardown. A muted start cut short gives the sound
                // back here too: its own ending may not run before the process goes.
                runBlocking {
                    withTimeoutOrNull(CAST_STOP_TIMEOUT_MS) {
                        DlnaCast.stop(renderer)
                        undoHush(renderer)
                    }
                }
            }
            else -> {
                forgetCast()
                host.scope.launch { DlnaCast.stop(renderer) }
            }
        }
    }

    /**
     * The queue went (a source switch) and the renderer stays the output:
     * stopped, keeping nothing of the old queue, down to the formats the old
     * server delivered. The next queue plays on it.
     */
    override fun stopPlayback() {
        val renderer = steered() ?: return
        job?.cancel()
        job = null
        dropSeekChecks()
        forgetCast()
        waitingForPlay = true
        formats.clear()
        seekableStreams.clear()
        log("queue gone — ${renderer.name} stopped, still the output")
        host.scope.launch { DlnaCast.stop(renderer) }
    }

    /** Nothing of the queue is on the renderer any more, or nothing on it is followed from here. */
    private fun forgetCast() {
        stream = null
        nextOffer.forget()
        pendingSeek = null
    }

    /**
     * The renderer the link took away, found again: on the network under the
     * same id, with what it is doing read and nothing sent to it. Still on the
     * track it was given, or on the one offered after it, it is this queue's,
     * wherever it has got to. Stopped, it holds nothing to resume, and is
     * taken back idle. Playing anything else, it is someone else's now, and
     * left alone.
     */
    override suspend fun findAgain(target: OutputTarget): Refound? {
        val gone = (target as DlnaTarget).renderer
        val renderer = rediscover(gone.id) ?: return null
        // A muted start the link cut off gives its sound back now, whether or
        // not the speaker is followed from here.
        undoHush(renderer)
        val current = stream
        val next = nextOffer.url
        repeat(REFOUND_READS) { attempt ->
            if (attempt > 0) delay(CAST_POLL_MS)
            val position = DlnaCast.position(renderer)
            val state = DlnaCast.state(renderer)
            if (position == null || state == DlnaState.UNKNOWN) return@repeat
            val uri = position.trackUri
            log("${renderer.name} found again: $state on ${DlnaCast.loggable(uri)} at ${position.positionMs}")
            val found = DlnaTarget(renderer)
            val onCurrent = DlnaCast.sameUri(uri, current?.url)
            val onNext = !onCurrent && DlnaCast.sameUri(uri, next)
            // Someone else's sound: left alone.
            if (!onCurrent && !onNext && state != DlnaState.STOPPED) return null
            // Ours, still going: stopped, as an iPhone stops a speaker it finds
            // again, so a play from here starts the song afresh rather than ask
            // for a second stream of a song the server is still sending.
            if (state != DlnaState.STOPPED) {
                log("${renderer.name} still playing what it was sent → stopped")
                DlnaCast.stop(renderer)
            }
            // Where it got to: on the song offered next if it crossed into it
            // (wherever an edit moved that one since), else its own. A renderer
            // that clears its clock on a stop says 0, and the phone's place stands.
            val index = when {
                onCurrent -> lostAtIndex
                onNext -> offeredNextIndex()
                else -> null
            }
            val at = position.positionMs + if (onCurrent) current?.offsetMs ?: 0L else 0L
            return if (index != null && (state != DlnaState.STOPPED || position.positionMs > 0L)) {
                Refound(found, index, at)
            } else {
                Refound(found)
            }
        }
        return null
    }

    /**
     * [id] on the network again, freshly described — a renderer that went
     * away may come back at another address or port. A few rounds: just after
     * a link comes up, the first search can go unanswered.
     */
    private suspend fun rediscover(id: String): DlnaRenderer? {
        repeat(REDISCOVER_ROUNDS) { round ->
            if (round > 0) delay(REDISCOVER_PAUSE_MS)
            DlnaCast.discover().firstOrNull { it.id == id }?.let { return it }
            log("search ${round + 1} of $REDISCOVER_ROUNDS: not there")
        }
        return null
    }

    override fun rejoin(found: Refound) {
        val renderer = (found.target as DlnaTarget).renderer
        job?.cancel()
        dropSeekChecks()
        // Stopped, or holding nothing of ours: a play sends the song afresh
        // from where it had got to — see waitingForPlay.
        waitingForPlay = true
        forgetCast()
        job = host.scope.launch {
            undoHush(renderer)
            // The fader is the renderer's again, as when a cast begins.
            DlnaCast.volume(renderer)?.let {
                host.showVolume(it)
                withContext(Dispatchers.Main.immediate) { host.setPhoneVolume(it) }
            }
        }
    }

    /**
     * Stop whatever [renderer] is playing before it is sent anything (this
     * app's song, left playing when the app closed, say): a busy renderer
     * turns a second stream away. Waits briefly for it to say it has stopped.
     */
    private suspend fun stopIfBusy(renderer: DlnaRenderer) {
        val state = DlnaCast.state(renderer)
        if (state != DlnaState.PLAYING && state != DlnaState.PAUSED && state != DlnaState.TRANSITIONING) return
        log("${renderer.name} busy ($state) → stopped first")
        DlnaCast.stop(renderer)
        val until = SystemClock.elapsedRealtime() + CAST_STOP_TIMEOUT_MS * 2
        while (SystemClock.elapsedRealtime() < until) {
            delay(MUTED_STEP_MS)
            if (DlnaCast.state(renderer) == DlnaState.STOPPED) return
        }
    }

    override fun play() {
        val renderer = steered() ?: return
        pressed(playing = true)
        if (waitingForPlay) {
            job?.cancel()
            job = host.scope.launch {
                if (startOrStop(renderer, host.positionMs.value)) pollRenderer(renderer)
            }
            return
        }
        host.scope.launch { DlnaCast.resume(renderer) }
    }

    override fun pause() {
        val renderer = steered() ?: return
        pressed(playing = false)
        host.scope.launch { DlnaCast.pause(renderer) }
    }

    /**
     * A play or pause pressed here, and when. Until the renderer has acted on
     * it, a poll still reading the old state is not news (it would show playing
     * for a second after a pause, and tell the server so).
     */
    @Volatile
    private var pressedPlaying: Boolean? = null

    @Volatile
    private var pressedAt = 0L

    private fun pressed(playing: Boolean) {
        pressedAt = SystemClock.elapsedRealtime()
        pressedPlaying = playing
    }

    /** Whether a reading of [playing] is only the renderer not having caught up with a press yet. */
    private fun behindPress(playing: Boolean): Boolean {
        val pressed = pressedPlaying ?: return false
        if (pressed == playing || SystemClock.elapsedRealtime() - pressedAt > PRESS_SETTLE_MS) {
            pressedPlaying = null
            return false
        }
        return true
    }

    override fun seekTo(ms: Long) {
        val renderer = steered() ?: return
        dropSeekChecks()
        if (waitingForPlay) {
            // Nothing to seek in — see waitingForPlay. Play starts there.
            host.showPosition(ms.coerceIn(0L, host.durationMs.value.takeIf { it > 0L } ?: Long.MAX_VALUE))
            return
        }
        // A target in the last second, or past it, leaves nothing to hear: the
        // next track. Anything earlier plays, and a stop straight after it reads
        // as the track ending — see pollRenderer's playedBefore.
        val total = host.durationMs.value
        if (total > 0L && total - ms <= CAST_SEEK_END_SLACK_MS) {
            log("seek $ms of $total is the end → next")
            job?.cancel()
            job = host.scope.launch {
                if (advanceCast(renderer)) pollRenderer(renderer) else DlnaCast.stop(renderer)
            }
            return
        }
        host.showPosition(ms)
        // In place when the renderer can, pushed again when it can't — see
        // seekCast. A stop after this is the track's end, not a failed start.
        job?.cancel()
        job = host.scope.launch {
            if (seekCast(renderer, ms)) {
                // Now, not at the poll's first round: a seek into the last few
                // seconds would have run out by then with nothing to follow it.
                armNextIfDue(renderer)
                pollRenderer(renderer, playedBefore = true)
            }
        }
    }

    // Only within the queue, as the controller's own jumpTo has it: a next
    // at the end of the queue doesn't wrap.
    override fun next() {
        val index = host.index.value + 1
        if (index in host.queue.value.indices) jumpTo(index)
    }

    override fun previous() {
        if (host.positionMs.value > PREVIOUS_RESTARTS_AFTER_MS) {
            seekTo(0)
        } else {
            val index = host.index.value - 1
            if (index in host.queue.value.indices) jumpTo(index)
        }
    }

    override fun jumpTo(index: Int) {
        val renderer = steered() ?: return
        dropSeekChecks()
        host.showIndex(index)
        host.showPosition(0)
        job?.cancel()
        job = host.scope.launch {
            if (startOrStop(renderer)) pollRenderer(renderer)
        }
    }

    // The renderer's own level over UPnP RenderingControl, and the phone's
    // nudged to match, so the rocker starts from where the speaker is.
    override fun setVolume(level: Float) {
        faderMoved = level
        host.showVolume(level)
        val renderer = steered()
        if (renderer != null) {
            host.scope.launch { DlnaCast.setVolume(renderer, level) }
        }
        host.setPhoneVolume(level)
    }

    override suspend fun followDeviceVolume(level: Float) {
        val renderer = steered() ?: return
        faderMoved = level
        DlnaCast.setVolume(renderer, level)
    }

    override fun newQueue() {
        val renderer = steered() ?: return
        dropSeekChecks()
        job?.cancel()
        job = host.scope.launch {
            if (startOrStop(renderer)) pollRenderer(renderer)
        }
    }

    override suspend fun upcomingChanged() {
        val renderer = steered() ?: return
        // Whatever the renderer was armed with is stale.
        nextOffer.armedFor = null
        armNextIfDue(renderer)
    }

    /**
     * Seek within the track the renderer is playing: a Seek when the stream can
     * be seeked (the file itself, served in ranges), one call and the track
     * carries on. Otherwise the track is pushed again from the position: a
     * stream that started part-way (the renderer seeks only within what it
     * holds), a transcode, or a renderer that refuses the Seek.
     */
    private suspend fun seekCast(renderer: DlnaRenderer, ms: Long): Boolean {
        val track = host.queue.value.getOrNull(host.index.value) ?: return false
        val current = stream
        val wanted = if (ms > 1_000L) ms else 0L
        if (current != null && current.offsetMs == 0L) {
            val (format, _) = castFormatFor(renderer, track)
            if (seekable(track, format) == true) {
                if (DlnaCast.seek(renderer, wanted)) {
                    log("seek in place → $wanted")
                    host.showPosition(wanted)
                    startedAtMs = wanted
                    pendingSeek = PendingSeek(wanted, SystemClock.elapsedRealtime())
                    if (wanted > 0L) verifyCastSeek(renderer, wanted, current.url)
                    return true
                }
                log("in-place seek refused → push")
            }
        }
        return startOrStop(renderer, wanted)
    }

    /**
     * A moment after a seek, check the renderer really went there, and if not,
     * ask the server for the track from that position instead. A renderer may
     * accept a Seek on a chunked transcode it can't honour and grind through
     * the encode in silence; GetPositionInfo says where it really is.
     *
     * Not into the last few seconds: the track ends before the check, the clock
     * resets, and "landed at 0" would restart the track just as it ended.
     */
    private fun verifyCastSeek(renderer: DlnaRenderer, wanted: Long, url: String) {
        val nearEnd = host.durationMs.value > 0 &&
            host.durationMs.value - wanted <= CAST_SEEK_VERIFY_MS + CAST_SEEK_TOLERANCE_MS
        if (nearEnd) return
        // This seek's own: the seek, or the push it came with, has already
        // moved the generation on from any check before it.
        val generation = seekGeneration
        val seekedIndex = host.index.value
        host.scope.launch {
            delay(CAST_SEEK_VERIFY_MS)
            if (generation != seekGeneration) return@launch
            if (steered()?.id != renderer.id) return@launch
            // Moved on — the renderer finished it, or a skip did — so the
            // seek has nothing left to be measured against.
            if (host.index.value != seekedIndex || stream?.url != url) return@launch
            val info = DlnaCast.position(renderer) ?: return@launch
            if (info.trackUri != null && !DlnaCast.sameUri(info.trackUri, url)) return@launch
            if (DlnaCast.state(renderer) == DlnaState.STOPPED) return@launch
            if (info.positionMs >= wanted - CAST_SEEK_TOLERANCE_MS) return@launch
            // Asked again on Main, where seeks and skips swap the cast job: the
            // two reads can take seconds, and a previous pressed meanwhile
            // must not have its job cancelled and the old position pushed back.
            withContext(Dispatchers.Main.immediate) {
                if (generation != seekGeneration || steered()?.id != renderer.id) return@withContext
                if (host.index.value != seekedIndex || stream?.url != url) return@withContext
                // A seek taken before play and then not acted on: the renderer
                // started from the top. It starts muted from here on, and this
                // track again that way — the stream itself seeks fine, and a
                // server that isn't transcoding it would ignore an offset.
                val muted = url == seekedBeforePlay && seeksBeforePlay[renderer.id] != false
                if (muted) seeksBeforePlay[renderer.id] = false
                log("seek missed: at ${info.positionMs} wanted $wanted → ${if (muted) "muted start" else "server"}")
                job?.cancel()
                job = host.scope.launch {
                    if (startOrStop(renderer, wanted, askServer = !muted)) {
                        pollRenderer(renderer, playedBefore = true)
                    }
                }
            }
        }
    }

    /**
     * Push the queue's current track to the renderer, from [seekToMs] into it,
     * with where it starts settled before its first sound.
     *
     * A stream served in ranges (anything untranscoded) the renderer seeks
     * itself: loaded, seeked, then played — see [playFrom]. One that isn't
     * can't be seeked by anyone, so the server is asked for it from the
     * position as a `timeOffset`; [askServer] says that is already decided.
     * Which a track's stream is gets measured per track, not inferred from the
     * format: mp3 asked of an mp3 file is the file, and the server then ignores
     * timeOffset — see DlnaCast.supportsRanges.
     */
    private suspend fun startCastTrack(
        renderer: DlnaRenderer,
        seekToMs: Long = 0L,
        askServer: Boolean = false,
        fresh: Boolean = false,
    ): Boolean {
        dropSeekChecks()
        val track = host.queue.value.getOrNull(host.index.value) ?: return false
        val (format, mime) = castFormatFor(renderer, track)
        // Anything under a second is where the track starts anyway, and a
        // timeOffset of 0 is a re-encode for nothing.
        val wanted = if (seekToMs > 1_000L) seekToMs else 0L
        val offset = when {
            askServer -> wanted
            wanted == 0L -> 0L
            else -> if (seekable(track, format) == false) wanted else 0L
        }
        val url = castUrl(track, format, offset)?.let { if (fresh) freshAddress(it) else it } ?: return false
        val started = if (offset == 0L && wanted > 0L) {
            when (playFrom(renderer, track, url, mime, wanted)) {
                Start.PLAYING -> true
                Start.FAILED -> false
                // The renderer won't seek this stream after all: the server's
                // stream from the position, as for one that can't be seeked.
                Start.UNSEEKABLE -> return startCastTrack(renderer, wanted, askServer = true, fresh = fresh)
            }
        } else {
            DlnaCast.play(renderer, url, track, mime)
        }
        if (!started) return false
        // A start cancelled while its unmute was out still comes back PLAYING
        // (the unmute is never cut short). The rest belongs to what replaced it.
        coroutineContext.ensureActive()
        waitingForPlay = false
        log("pushed idx=${host.index.value} wanted=$wanted offset=$offset ${format.id}")
        host.showPlaying(true)
        host.showDuration(track.durationMs)
        host.showPosition(wanted)
        startedAtMs = wanted
        // Whether it really went there is checked a moment later — see
        // verifyCastSeek.
        if (offset == 0L && wanted > 0L) verifyCastSeek(renderer, wanted, url)
        host.announceNowPlaying()
        stream = CastStream(url, offset)
        pendingSeek = null
        // SetAVTransportURI leaves the next slot empty, and it stays that way
        // until this track is nearly over — see armNextIfDue.
        nextOffer.forget()
        return true
    }

    /**
     * [startCastTrack], and when it couldn't start the track: the renderer is
     * told to stop — what it may still be playing is no longer the song the
     * screen names — and the controller hears so, showing it paused there with
     * nothing starting. A press of play asks again — see [waitingForPlay].
     */
    private suspend fun startOrStop(
        renderer: DlnaRenderer,
        seekToMs: Long = 0L,
        askServer: Boolean = false,
        fresh: Boolean = false,
    ): Boolean {
        if (startCastTrack(renderer, seekToMs, askServer, fresh)) return true
        if (steered()?.id != renderer.id) return false
        log("couldn't start idx=${host.index.value} → paused")
        forgetCast()
        waitingForPlay = true
        DlnaCast.stop(renderer)
        host.couldNotPlay(this)
        return false
    }

    /** How a start at a position went — see [playFrom]. */
    private enum class Start { PLAYING, UNSEEKABLE, FAILED }

    /**
     * Start [url] at [wantedMs] with nothing of it heard from anywhere else.
     *
     * Loaded, seeked while still stopped, then played: the renderer's first
     * sound is the position. A renderer that won't take a seek before it plays
     * is muted instead: played, seeked, and heard once its clock says it got
     * there, or a few seconds have gone. One with nothing to mute it with
     * plays, then seeks, and is heard from the top for a moment.
     */
    private suspend fun playFrom(
        renderer: DlnaRenderer,
        track: Track,
        url: String,
        mime: String,
        wantedMs: Long,
    ): Start {
        seekedBeforePlay = null
        if (!DlnaCast.setUri(renderer, url, track, mime)) return Start.FAILED
        if (seeksBeforePlay[renderer.id] != false) {
            if (DlnaCast.seek(renderer, wantedMs)) {
                seekedBeforePlay = url
                return if (DlnaCast.resume(renderer)) Start.PLAYING else Start.FAILED
            }
            seeksBeforePlay[renderer.id] = false
            log("seek before play refused → muted starts on ${renderer.name}")
        }
        // Seen through even by a start cancelled meanwhile: the renderer is
        // muted whether or not the answer is waited for.
        val hush = withContext(NonCancellable) { hush(renderer) }
        if (hush == null) {
            log("nothing to mute ${renderer.name} with → play, then seek")
            if (!DlnaCast.resume(renderer)) return Start.FAILED
            if (DlnaCast.seek(renderer, wantedMs)) return Start.PLAYING
            log("renderer refused seek → server")
            return Start.UNSEEKABLE
        }
        try {
            if (!DlnaCast.resume(renderer)) return Start.FAILED
            if (!seekOncePlaying(renderer, wantedMs)) {
                log("renderer refused seek → server")
                // Stopped while it still can't be heard: the server's stream
                // takes over from the position.
                DlnaCast.stop(renderer)
                return Start.UNSEEKABLE
            }
            val there = clockReaches(renderer, wantedMs)
            log("muted start ${if (there) "reached $wantedMs" else "not seen there yet"} → sound")
            return Start.PLAYING
        } finally {
            // However this ends — a refusal, a failure, a skip cancelling it —
            // the renderer is never left silent by a start.
            withContext(NonCancellable) { unhush(renderer, hush) }
        }
    }

    /** Seek [renderer] once it takes one — a renderer still opening a stream may refuse. False if it never does in a few seconds. */
    private suspend fun seekOncePlaying(renderer: DlnaRenderer, wantedMs: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + MUTED_SEEK_WINDOW_MS
        while (true) {
            if (DlnaCast.seek(renderer, wantedMs)) return true
            if (SystemClock.elapsedRealtime() >= until) return false
            delay(MUTED_STEP_MS)
        }
    }

    /** Whether [renderer]'s clock reaches [wantedMs] within a few seconds: how long a muted start stays muted. */
    private suspend fun clockReaches(renderer: DlnaRenderer, wantedMs: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + MUTED_LISTEN_MS
        while (SystemClock.elapsedRealtime() < until) {
            delay(MUTED_STEP_MS)
            val at = DlnaCast.position(renderer)?.positionMs ?: continue
            if (at >= wantedMs - MUTED_ARRIVAL_SLACK_MS && at <= wantedMs + CAST_START_WINDOW_MS) return true
        }
        return false
    }

    /**
     * Whether a seek before play lands on a renderer, by its id: unknown until
     * tried, and false once one refused it, or took it and played from the
     * top anyway — see [playFrom] and [verifyCastSeek].
     */
    private val seeksBeforePlay = ConcurrentHashMap<String, Boolean>()

    /** The stream last started by a seek before play, which [verifyCastSeek] judges that way of starting by. */
    private var seekedBeforePlay: String? = null

    /** What a muted start did to a renderer's sound, to be undone. */
    private sealed interface Hush {
        /** Muted already, by whoever did it: left as it is. */
        data object Silent : Hush

        /** Muted here, with the renderer's own switch. */
        data object Muted : Hush

        /** No switch to use: the volume taken to 0 from [level], the renderer's own 0–100. */
        class Lowered(val level: Int) : Hush
    }

    /**
     * The renderers a muted start has silenced, by id, and how — until their
     * sound is given back. Given back by the start itself, whatever ends it,
     * and where that couldn't be sent — the link went — at the next chance: a
     * poll, a connect, the speaker found again, the app being left.
     */
    private val hushed = ConcurrentHashMap<String, Hush>()

    /** The level the fader asked for during a muted start, which a lowered volume goes back to instead of the old one. */
    @Volatile
    private var faderMoved: Float? = null

    /** Silence [renderer] for a muted start — its mute, else its volume — or null when it has neither. */
    private suspend fun hush(renderer: DlnaRenderer): Hush? {
        faderMoved = null
        // Silenced by an earlier start that couldn't give it back: ours, not
        // its owner's, and still to be undone.
        hushed[renderer.id]?.let { return it }
        if (DlnaCast.muted(renderer) == true) return Hush.Silent
        // Written down before each call goes out. One that is cut off still
        // silences the renderer, and only this record gives the sound back.
        hushed[renderer.id] = Hush.Muted
        if (DlnaCast.setMute(renderer, true)) return Hush.Muted
        hushed.remove(renderer.id)
        val level = DlnaCast.rawVolume(renderer) ?: return null
        if (level == 0) return Hush.Silent
        val lowered = Hush.Lowered(level)
        hushed[renderer.id] = lowered
        if (DlnaCast.setRawVolume(renderer, 0)) return lowered
        hushed.remove(renderer.id)
        return null
    }

    /** Give back the sound [hush] took from [renderer]. Kept in [hushed] for the next chance when it can't be sent. */
    private suspend fun unhush(renderer: DlnaRenderer, hush: Hush) {
        val undone = when (hush) {
            Hush.Silent -> true
            Hush.Muted -> DlnaCast.setMute(renderer, false)
            is Hush.Lowered -> DlnaCast.setRawVolume(
                renderer,
                faderMoved?.let { DlnaCast.fractionToVolume(it) } ?: hush.level,
            )
        }
        if (undone) {
            hushed.remove(renderer.id, hush)
            return
        }
        hushed[renderer.id] = hush
        log("couldn't give ${renderer.name} its sound back — again at the next chance")
    }

    /** Give [renderer] back any sound a muted start still holds. */
    private suspend fun undoHush(renderer: DlnaRenderer) {
        val hush = hushed[renderer.id] ?: return
        unhush(renderer, hush)
    }

    /**
     * The address the renderer fetches [track] in [format] from, [offsetMs]
     * into it.
     *
     * No estimated length: a server estimates from duration x the cap bitrate,
     * an ABR transcode comes in under it, and a renderer stops where the
     * declared length says, seconds early. Chunked plays to the end, and the
     * shown duration comes from the DIDL metadata anyway.
     *
     * A session id of its own: the renderer fetches for itself, with no door
     * to decide it first, and during a hand-off both tracks' transcodes are in
     * flight at once, where Plex tears down whichever shares an identifier
     * with the other — see OutputHost.ownStreamSession.
     */
    private fun castUrl(track: Track, format: StreamFormat, offsetMs: Long = 0L): String? =
        host.streamUrl(
            track,
            format,
            estimateContentLength = false,
            timeOffsetSeconds = (offsetMs / 1000).toInt(),
            sessionId = host.ownStreamSession(track.id),
        )

    /**
     * Whether [track]'s stream in [format] can be seeked: remembered, else
     * asked of the server and remembered.
     *
     * **Not** with the URL the renderer plays: a second request with the same
     * session id has Plex tear the first down, stopping the music. Without one
     * the probe gets a transcode of its own, closed at once, and the answer is
     * the same: whether the server transcodes depends on the file and format.
     */
    private suspend fun seekable(track: Track, format: StreamFormat): Boolean? {
        val key = "${track.id}|${format.id}"
        seekableStreams[key]?.let { return it }
        // Known without a fetch, which would take the very transcode the
        // speaker is about to ask for — see MusicServer.knownStream.
        host.knownStream(track, format)?.let {
            seekableStreams[key] = it.ranges
            return it.ranges
        }
        val probeUrl = host.streamUrl(
            track,
            format,
            estimateContentLength = false,
        ) ?: return null
        // The probe goes to the music server, not the speaker, on a stack
        // outside NetworkGate — DlnaCast.allowsNetwork stands in front of it.
        return DlnaCast.supportsRanges(probeUrl)?.also { seekableStreams[key] = it }
    }

    /**
     * Hand over the following track once the current one is nearly finished.
     * A renderer opens the next stream the moment it is given it and plays from
     * that connection however much later, and a stream left undrained for
     * minutes goes stale: the hand-off arrives STOPPED, or cuts out part-way.
     */
    private suspend fun armNextIfDue(renderer: DlnaRenderer) {
        val total = host.durationMs.value
        // A track of unknown length (zero) still gets one: early risks a stale
        // stream, never is a gap on every track.
        if (total > 0L && total - host.positionMs.value > CAST_PREQUEUE_LEAD_MS) return
        queueNextOnRenderer(renderer)
    }

    /**
     * Hand the renderer the following track so it can cross into it seamlessly.
     *
     * The address goes in [nextOffer] so the poll can recognise the hand-off,
     * offered before the reply and taken once the renderer says so. Call
     * through [armNextIfDue]: a stream handed over early goes stale.
     */
    private suspend fun queueNextOnRenderer(renderer: DlnaRenderer) {
        val after = nextIndex()
        val track = after?.let { host.queue.value.getOrNull(it) }
        if (after == null || track == null) {
            // Offered before the queue lost its next — repeat switched off on
            // the last song, say. Left in the renderer, it would cross into it
            // under the old song's name.
            dropNext(renderer)
            return
        }
        // Offered once per track. Asking again neither opens a new stream nor
        // changes the renderer's mind, and each attempt nulls the very field the
        // hand-off is recognised by until the SOAP call comes back.
        if (nextOffer.armedFor == after) return
        val (format, mime) = castFormatFor(renderer, track)
        // Its own session id, not the current track's — see castUrl.
        val url = castUrl(track, format) ?: return
        if (url == nextOffer.taken) return
        nextOffer.offer(after, track.id, url)
        val queued = DlnaCast.setNext(renderer, url, track, mime)
        // Renderers without it fault, and the stop-and-restart path carries the
        // queue. An offer never answered is still looked for — see NextOffer.offered.
        if (queued) nextOffer.taken = url
    }

    /** Restarts so far, so each one is an address of its own — see [freshAddress]. */
    private var restarts = 0

    /**
     * [url] with a throwaway parameter, so a restart is a new address: a
     * renderer won't fetch an address it already holds again. Servers pass
     * over a parameter they don't know.
     */
    private fun freshAddress(url: String): String =
        url + (if ('?' in url) "&" else "?") + "ampTry=" + (++restarts)

    /** Forget what was offered as the next track, on the renderer as well. */
    private suspend fun dropNext(renderer: DlnaRenderer) {
        val offered = nextOffer.url
        nextOffer.forget()
        // A renderer without the call faults, which changes nothing.
        if (offered != null) DlnaCast.clearNext(renderer)
    }

    /** The index the queue would move to next, or null at the end of it. */
    private fun nextIndex(): Int? = nextCastIndex(host.index.value, host.queue.value.size, host.repeatMode.value)

    /**
     * Where the track offered as next sits in the queue now: the slot it was
     * offered for while that still holds it, else wherever an edit moved it.
     * Null when it was never offered, or the queue no longer has it.
     */
    private fun offeredNextIndex(): Int? {
        val id = nextOffer.trackId ?: return null
        val queue = host.queue.value
        nextOffer.armedFor?.takeIf { queue.getOrNull(it)?.id == id }?.let { return it }
        return queue.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }

    /**
     * Format to cast in: the user's chosen streaming quality (never an upgrade),
     * as long as the renderer can decode what the *server* will send.
     *  - The format name doesn't fix the container (a "flac" transcode can
     *    arrive as Ogg-FLAC), so the MIME type is probed from the server.
     *  - An unsupported format doesn't error, the renderer just stalls, so it
     *    falls back to MP3 when the sink list doesn't cover that MIME.
     * If the device won't list its formats, the user's choice wins.
     */
    private suspend fun castFormatFor(
        renderer: DlnaRenderer,
        track: Track,
    ): Pair<StreamFormat, String> {
        val desired = host.streamFormat()
        val key = "${renderer.id}|${desired.id}"
        formats[key]?.let { return it }

        val sink = runCatching { DlnaCast.sinkFormats(renderer) }.getOrDefault(emptySet())
        // Same as seekable: this asks the music server; DlnaCast's own
        // switch stands in front of it. Not where the server already says.
        val mime = host.knownStream(track, desired)?.mime
            ?: host.streamUrl(track, desired)?.let { DlnaCast.probeMime(it) }
            ?: assumedMime(desired)
        val choice = if (sinkAccepts(sink, mime)) {
            desired to mime
        } else {
            StreamFormat.MP3 to "audio/mpeg"
        }
        formats[key] = choice
        return choice
    }

    /**
     * Mirror the renderer's clock into the UI and advance the queue when a track
     * finishes: a renderer holds one URI, so the hand-off is driven from here.
     */
    private suspend fun pollRenderer(renderer: DlnaRenderer, playedBefore: Boolean = false) {
        var settled = false
        // Whether a stop can only mean the track ended, as for a track pushed
        // again for a seek: it was playing a moment ago. Otherwise a stop before
        // any poll saw PLAYING reads as never started, and restarts the track.
        var played = playedBefore
        // What the renderer said last time. A position that hasn't moved is not
        // evidence that anything is playing — see below.
        var lastPositionMs = -1L
        // The queue index we have tried to start by hand, and how often, so a
        // track that refuses to play is asked again a couple of times and
        // then passed over rather than restarted for ever.
        var startedIndex = -1
        var restartsHere = 0
        // How patient to be with a track that hasn't started. A stream being
        // opened from scratch deserves a few seconds; one the renderer has
        // *already* taken from the queue and stopped on does not — see the
        // hand-off below.
        var restartAfter = STALLED_POLLS_TO_RESTART
        // A renderer reports STOPPED for a moment while it re-buffers as well as
        // at the end of a track, and the two are indistinguishable from one poll.
        // Two in a row is: the stream really has ended. Readings that went
        // unanswered are counted separately — see CastStallCounter.
        val stall = CastStallCounter()
        // Where the track was last heard playing, for telling a stop part-way
        // through from its end — see stoppedPartWay.
        var heardMs = -1L
        // Stopped part-way and shown paused there, until it plays again.
        var stoppedThere = false
        while (coroutineContext.isActive && steered()?.id == renderer.id) {
            delay(CAST_POLL_MS)
            undoHush(renderer)
            val position = DlnaCast.position(renderer)
            val state = DlnaCast.state(renderer)
            // This reading is of the track we pushed or followed.
            var oursNow = false
            if (position != null) {
                val reported = position.trackUri
                // The renderer crossed into the track we queued: follow it here
                // rather than driving it, so nothing interrupts the audio. It has
                // to be a *different* URI from the current one, since repeat-one
                // queues the same track. Offered counts as much as confirmed —
                // see NextOffer.offered.
                val next = nextOffer.url
                val moved = next != null &&
                    !DlnaCast.sameUri(next, stream?.url) &&
                    DlnaCast.sameUri(reported, next)
                if (moved) {
                    dropSeekChecks()
                    (offeredNextIndex() ?: nextIndex())?.let { host.showIndex(it) }
                    // The queued track was handed over whole, from its start.
                    stream = CastStream(next, offsetMs = 0L)
                    startedAtMs = 0L
                    pendingSeek = null
                    host.showDuration(
                        host.queue.value.getOrNull(host.index.value)?.durationMs
                            ?: host.durationMs.value,
                    )
                    host.announceNowPlaying()
                    // The slot the renderer just consumed is empty again, and is
                    // left that way until this track is nearly over.
                    nextOffer.forget()
                    stall.reset()
                    // The renderer has taken the queued track but not started it:
                    // for a second or two it reports STOPPED and the finished
                    // track's position, which reads as this track ending. So the
                    // new track gets a fresh start's protection, and the rest of
                    // this poll, which is about the old one, is skipped.
                    settled = false
                    played = false
                    heardMs = -1L
                    stoppedThere = false
                    // Seeded with the reading we just saw, not cleared: cleared,
                    // the next identical stale reading looks like a change and
                    // re-arms the very flag this is putting down.
                    lastPositionMs = position.positionMs
                    host.showPosition(0L)
                    // A working hand-off is already PLAYING here, the gapless case.
                    // A dead one sits stopped on the finished track's position and
                    // never recovers on its own: one more poll, not the seconds a
                    // cold stream needs.
                    restartAfter = if (state == DlnaState.PLAYING) {
                        STALLED_POLLS_TO_RESTART
                    } else {
                        HANDOFF_POLLS_TO_RESTART
                    }
                    continue
                }
                // Only readings of *our* track: between streams a renderer goes
                // on reporting the last one's frozen position, sometimes for seconds.
                val current = stream
                val ours = reported == null || DlnaCast.sameUri(reported, current?.url)
                oursNow = ours
                if (ours) {
                    // Until this track has settled, only a reading near where it
                    // started is believed: for a poll or two a renderer reports
                    // the new URI with the *old* clock.
                    val offset = current?.offsetMs ?: 0L
                    val reading = position.positionMs + offset
                    val pending = pendingSeek
                    if (pending != null) {
                        // A seek is in flight, and the renderer reports the old
                        // clock for a poll or two while it reopens the stream:
                        // nothing is believed until a reading near the target
                        // (the seek's own check re-pushes if none comes). Not for
                        // ever: a seek into the last seconds under repeat-one can
                        // wrap to 0:00 before any reading near it.
                        if (abs(reading - pending.targetMs) <= CAST_START_WINDOW_MS) {
                            pendingSeek = null
                            host.showPosition(reading)
                            if (state != DlnaState.STOPPED) heardMs = reading
                        } else if (SystemClock.elapsedRealtime() - pending.sentAt > CAST_SEEK_WAIT_MS) {
                            log("seek to ${pending.targetMs} never seen — following the renderer at $reading")
                            pendingSeek = null
                            host.showPosition(reading)
                            if (state != DlnaState.STOPPED) heardMs = reading
                        }
                    } else if (settled || abs(reading - startedAtMs) <= CAST_START_WINDOW_MS) {
                        host.showPosition(reading)
                        if (state != DlnaState.STOPPED) heardMs = reading
                    }
                    // A stream that started part-way in reports a short duration:
                    // the track's own is the honest number.
                    if (offset == 0L && position.durationMs > 0) {
                        host.showDuration(position.durationMs)
                    }
                    // Started means the renderer saying PLAYING (below), or a
                    // position that has *moved* while not stopped. A stopped
                    // renderer's position is never evidence: through a hand-off
                    // it holds the finished track's last one.
                    if (state != DlnaState.STOPPED &&
                        position.positionMs > 0 &&
                        position.positionMs != lastPositionMs
                    ) {
                        settled = true
                    }
                    lastPositionMs = position.positionMs
                }
            }
            stall.observe(state)
            when (state) {
                DlnaState.PLAYING -> {
                    settled = true
                    if (oursNow && stoppedThere) {
                        // Played again from its own remote: followed from
                        // wherever it plays, nothing left to push from here.
                        stoppedThere = false
                        waitingForPlay = false
                    }
                    if (!behindPress(playing = true)) host.showPlaying(true)
                    // Sound, by its own say, once any seek has landed: the
                    // start is over now, not a second or two later when its
                    // clock is seen to move.
                    if (oursNow && pendingSeek == null) host.showSounding()
                }
                DlnaState.PAUSED -> if (!behindPress(playing = false)) host.showPaused()
                else -> Unit
            }
            when {
                // Paused part-way (below): nothing ends, restarts or is handed on
                // until it plays again, or the stale clock would read the cued
                // track as over and the next song would start under a pause.
                stoppedThere -> Unit
                // Stopped part-way through: some remotes pause by STOP, so it is
                // a pause, not the end. Shown paused where last heard; a play
                // here pushes the track again from there — see waitingForPlay —
                // and one on its remote is followed.
                (settled || played) && stall.stopped >= STOPPED_POLLS_TO_ADVANCE &&
                    stoppedPartWay(heardMs, host.durationMs.value) -> {
                    log("stopped at $heardMs of ${host.durationMs.value} → paused there")
                    stoppedThere = true
                    waitingForPlay = true
                    host.showPosition(heardMs)
                    host.showPaused()
                    dropNext(renderer)
                    // Put in again from there, stopped, so its own play and
                    // a resume from here both go on from where it stopped.
                    if (cueAt(renderer, heardMs)) waitingForPlay = false
                    // Until it plays the cued stream it reports the old one's
                    // clock, which added to the new offset is the stop point
                    // twice over: only a reading near the cue is believed.
                    settled = false
                }
                // Played, then stopped: the track ended. Only readings the renderer
                // gave count: going quiet never ends a track that was playing.
                (settled || played) && stall.stopped >= STOPPED_POLLS_TO_ADVANCE -> {
                    log("ended idx=${host.index.value} → advance")
                    if (!advanceCast(renderer)) return
                    settled = false
                    played = false
                    heardMs = -1L
                    stall.reset()
                    startedIndex = -1
                    restartAfter = STALLED_POLLS_TO_RESTART
                }
                // Never played: a renderer can take the queued track, transition
                // to it and then sit stopped for ever, so it is started by hand
                // rather than stepped over. One that stopped answering counts
                // too: this decision can afford to be wrong. Three STOPPED
                // readings in a row is a stream turned away (one still being
                // fetched says TRANSITIONING). Twice per track: a song turned
                // away while the stream it replaced was still closing plays on
                // a second try.
                !settled && !played &&
                    (stall.notPlaying >= restartAfter || stall.stopped >= TURNED_AWAY_POLLS) &&
                    (startedIndex != host.index.value || restartsHere < RESTARTS_BEFORE_PASSING_OVER) -> {
                    log("never started idx=${host.index.value} → restart")
                    if (startedIndex != host.index.value) restartsHere = 0
                    startedIndex = host.index.value
                    restartsHere++
                    stall.reset()
                    restartAfter = STALLED_POLLS_TO_RESTART
                    if (!startOrStop(renderer, host.positionMs.value, fresh = true)) return
                }
                // Asked again and it still won't play: something is wrong with
                // this track rather than with the hand-off, and sitting in
                // silence is worse than going on.
                !settled && !played &&
                    stall.notPlaying >= restartAfter &&
                    startedIndex == host.index.value -> {
                    log("still not playing idx=${host.index.value} → pass over")
                    if (!advanceCast(renderer)) return
                    stall.reset()
                    startedIndex = -1
                    restartsHere = 0
                    restartAfter = STALLED_POLLS_TO_RESTART
                }
            }
            // Near the end, the next track is handed over — see armNextIfDue.
            if (!stoppedThere) armNextIfDue(renderer)
        }
    }

    /**
     * Point [renderer] at the current track from [atMs], stopped: what a play
     * from its own remote, or a resume from here, then starts from.
     *
     * A remote that pauses by stopping plays again from the start of the
     * address held, which for a stream the server began at a seek is the old
     * seek point. So the stream goes in again from [atMs]: seeked by the
     * renderer where it can, otherwise begun there by the server. False when
     * nothing was put in, and a play here pushes the track again — see
     * waitingForPlay.
     */
    private suspend fun cueAt(renderer: DlnaRenderer, atMs: Long): Boolean {
        val track = host.queue.value.getOrNull(host.index.value) ?: return false
        val (format, mime) = castFormatFor(renderer, track)
        val byRenderer = seekable(track, format) == true && seeksBeforePlay[renderer.id] != false
        val offset = if (byRenderer) 0L else atMs / 1000 * 1000
        val url = castUrl(track, format, offset) ?: return false
        if (!DlnaCast.setUri(renderer, url, track, mime)) return false
        // Held now, whatever happens next: its readings are this track's, and
        // a play from its own remote is followed from wherever it starts.
        stream = CastStream(url, offset)
        pendingSeek = null
        val there = !byRenderer || DlnaCast.seek(renderer, atMs)
        startedAtMs = if (byRenderer && there) atMs else offset
        log(
            "cued idx=${host.index.value} at $atMs " +
                "${if (!byRenderer) "from the server" else if (there) "seeked" else "but not seeked"}, stopped",
        )
        return there
    }

    /** Move to the next queue entry on the renderer; false when the queue ends. */
    private suspend fun advanceCast(renderer: DlnaRenderer): Boolean {
        val next = nextIndex()?.takeIf { it in host.queue.value.indices }
        if (next == null) {
            log("queue finished")
            // Nothing left to resume — see waitingForPlay. The controller winds
            // back to the top, as it does for the phone.
            waitingForPlay = true
            forgetCast()
            host.queueFinished(this)
            return false
        }
        host.showIndex(next)
        log("advance → idx=$next")
        return startOrStop(renderer)
    }

    private companion object {
        /** Long enough for a renderer that can seek to have done so. */
        const val CAST_SEEK_VERIFY_MS = 4_000L

        /**
         * How long a seek in place is waited for before the renderer's clock is
         * believed again, wherever it is — see pollRenderer.
         */
        const val CAST_SEEK_WAIT_MS = 5_000L

        /**
         * Readings of STOPPED in a row, with nothing opening, that say a pushed
         * track was turned away — see pollRenderer.
         */
        const val TURNED_AWAY_POLLS = 3

        /** A cast seek this close to the end, or past it, is the next track: nothing is left to hear. */
        const val CAST_SEEK_END_SLACK_MS = 1_000L
        const val CAST_SEEK_TOLERANCE_MS = 15_000L

        /** How far a just-started track's first readings may stray from its start before being disbelieved. */
        const val CAST_START_WINDOW_MS = 15_000L

        const val CAST_POLL_MS = 1_000L

        /** How long a renderer gets to act on a play or pause before what it reports counts again. */
        const val PRESS_SETTLE_MS = 3_000L

        /** Consecutive STOPPED readings that mean the track really ended. */
        const val STOPPED_POLLS_TO_ADVANCE = 2

        /**
         * Polls a track that has never played is given before it is started by
         * hand — see pollRenderer. Longer than the advance threshold: a renderer
         * opening a fresh stream is stopped for a few seconds while it loads.
         */
        const val STALLED_POLLS_TO_RESTART = 6

        /**
         * The same, for a track the renderer took from the queue and stopped on:
         * it had the whole previous track to load, so that hand-off has failed.
         */
        const val HANDOFF_POLLS_TO_RESTART = 2

        /**
         * How close to the end of the current track the next one is handed over:
         * long enough for the renderer to open and buffer the stream, short
         * enough that it can't go stale first — see [armNextIfDue].
         */
        const val CAST_PREQUEUE_LEAD_MS = 15_000L

        /** How long leaving waits for the renderer to take its stop — see disconnect. */
        const val CAST_STOP_TIMEOUT_MS = 1_500L

        /** How long a muted start keeps offering its seek to a renderer still opening the stream. */
        const val MUTED_SEEK_WINDOW_MS = 3_000L

        /** How long a muted start waits to see the clock at its position before it is heard anyway. */
        const val MUTED_LISTEN_MS = 4_000L

        /** Between one ask and the next during a muted start. */
        const val MUTED_STEP_MS = 300L

        /** The renderer's clock counts whole seconds, and lands on the second it was sent to. */
        const val MUTED_ARRIVAL_SLACK_MS = 2_000L

        /** Searches for a renderer the link took away, once it is back, and the wait between them. */
        const val REDISCOVER_ROUNDS = 4
        const val REDISCOVER_PAUSE_MS = 3_000L

        /** How many tries a renderer found again gets to say what it is doing. */
        const val REFOUND_READS = 3

        /** Fresh restarts of a track the renderer won't start, before it is passed over. */
        const val RESTARTS_BEFORE_PASSING_OVER = 2
    }
}

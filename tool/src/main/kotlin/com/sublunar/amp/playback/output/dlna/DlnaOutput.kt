package com.sublunar.amp.playback.output.dlna

import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.Track
import com.sublunar.amp.playback.output.Disconnect
import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget
import com.sublunar.amp.playback.output.PREVIOUS_RESTARTS_AFTER_MS
import com.sublunar.amp.playback.output.Refound
import com.sublunar.amp.playback.output.VolumeControl
import android.os.SystemClock
import kotlin.coroutines.coroutineContext
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
 * Playing to a DLNA renderer.
 *
 * A renderer holds one URI, and optionally the next, so this side drives:
 * the queue and the index stay the controller's, a poll once a second
 * mirrors the renderer's clock back into them, and the queue is advanced from
 * here as each track ends, honouring repeat. The phone reports the playback
 * to the server, as it does for its own player.
 */
internal class DlnaOutput(private val host: OutputHost) : Output {
    override val family = DlnaFamily

    override val logTag = "AmpCast"

    // A renderer plays what it is handed and tells the server nothing:
    // the phone reports for it.
    override val reportsItself = false

    override val needsLan = true

    // The fader drives the renderer's own RenderingControl.
    override val volumeControl: StateFlow<VolumeControl> = MutableStateFlow(VolumeControl.Ours)

    /**
     * How far into the track the renderer's stream starts — the trick the
     * phone's own player plays with a server seek.
     *
     * A UPnP Seek is a byte-range request, and a Navidrome transcode is a live
     * ffmpeg pipe with no seek table — so handing the Denon a seek right after
     * SetAVTransportURI got the request refused and the track played from the
     * top, which is why casting mid-song sometimes "restarted" it. The offset
     * goes into the URL instead, and the readouts are shifted back.
     */
    private var castOffsetMs = 0L

    /** Where the current cast track was started from — see pollRenderer. */
    private var castStartedAtMs = 0L

    /**
     * Target of a seek sent to the renderer in place, until a reading near it
     * arrives; -1 when none is in flight. See pollRenderer.
     */
    private var castSeekPending = -1L

    /** When [castSeekPending] was sent — a wait for it is given up after [CAST_SEEK_WAIT_MS]. */
    private var castSeekPendingAt = 0L

    private var castJob: Job? = null

    // "rendererId|format" -> stream format actually sent + its MIME type.
    // Concurrent: a source switch clears it from wherever stop was called,
    // while a cast job on its way out may still be writing it.
    private val castFormats = java.util.concurrent.ConcurrentHashMap<String, Pair<StreamFormat, String>>()

    /**
     * Whether a track's stream can be seeked, keyed by track and format.
     *
     * Per track rather than per format: asking a server for mp3 gets a live
     * transcode for a flac original and the untouched file for an mp3 one, and
     * only the second of those can be seeked. Only settled answers are kept, so
     * a probe that failed is asked again rather than remembered as a no.
     * Concurrent, as [castFormats] is.
     */
    private val castSeekable = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** What the renderer is playing, and what it has been given to play next. */
    private var castCurrentUrl: String? = null
    private var castNextUrl: String? = null

    /**
     * The address last offered as the next track, whether or not the
     * renderer's reply said it was taken — see [queueNextOnRenderer]. A reply
     * lost on its way back is not a renderer that refused.
     */
    private var castNextOffered: String? = null

    /** The last address the renderer reported that was neither its track nor the one offered after it — logged once each. */
    private var strangeUri: String? = null

    /**
     * The renderer holds nothing to resume: the queue ran out there. Play
     * starts the queue's track where the clock stands — a bare resume played
     * the last song again, under a clock that went on from its end — and a
     * seek only moves the clock until then, as it does on a paused phone.
     */
    @Volatile
    private var waitingForPlay = false

    /**
     * The queue index the renderer's next slot has been armed for — or offered
     * and refused.
     *
     * Arming is attempted from the poll loop now rather than once per track, so
     * without this a renderer that doesn't implement SetNextAVTransportURI would
     * be asked again every second for the whole lead window.
     */
    private var castNextArmedFor: Int? = null

    /**
     * The track the offered next address is for, by id. A queue edited since
     * may have moved it or taken it out, and the slot it was offered for then
     * names some other song — see [offeredNextIndex].
     */
    private var castNextTrackId: String? = null

    /**
     * Bumped by every seek, every push and every change of track, so the
     * check a seek leaves behind can't act on whatever replaced it — see
     * [verifyCastSeek]. Bumped only by the seeks that left a check, as it
     * was, a previous pressed a few seconds after a seek let that seek's check
     * find the track back at 0:00, call it a miss, and push the old position
     * back from the server.
     */
    private var seekGeneration = 0

    /** Whatever an earlier seek left to check is moot: a seek, a push or a change of track has replaced it. */
    private fun dropSeekChecks() {
        seekGeneration++
    }

    /** The renderer this is steering, or null once it has been let go. */
    private fun steered(): DlnaRenderer? = (host.steering(this) as? DlnaTarget)?.renderer

    /** Find renderers on the network. Blocking on the network for a few seconds. */
    override suspend fun find(): List<OutputTarget> =
        // Only on Wi-Fi — see LinkRules.mayUseLan. DlnaCast asks the same
        // question itself before it sends anything; asking here as well means
        // the search returns at once instead of listening to nothing for
        // three seconds.
        if (host.rules().mayUseLan) DlnaCast.discover().map(::DlnaTarget) else emptyList()

    /**
     * Move playback to the renderer: hand it the current track's stream URL
     * at the position we'd reached, and start polling it for progress.
     */
    override fun connect(target: OutputTarget, fromMs: Long) {
        val renderer = (target as DlnaTarget).renderer
        castJob?.cancel()
        castJob = host.scope.launch {
            undoHush(renderer)
            // Adopt the renderer's current level so the fader isn't lying — and
            // move the device's own to match, so the first press of the rocker
            // nudges from where the speaker is rather than jumping.
            DlnaCast.volume(renderer)?.let {
                host.showVolume(it)
                withContext(Dispatchers.Main.immediate) { host.setPhoneVolume(it) }
            }
            when {
                startCastTrack(renderer, fromMs) -> pollRenderer(renderer)
                // Nothing queued yet: chosen, it waits for the queue.
                host.queue.value.getOrNull(host.index.value) == null -> Unit
                // Nothing played there, and the phone sits paused where the
                // cast began — it says so, rather than naming a speaker that
                // stays silent.
                else -> {
                    android.util.Log.i("AmpCast", "couldn't start on ${renderer.name} → back to the phone")
                    host.dropped(this@DlnaOutput, target)
                }
            }
        }
    }

    /**
     * Stop steering the renderer, for [why]: told to stop — and waited for,
     * briefly, when the app is being left — or, with the link gone, simply
     * let go of, since there is nobody to hear it. What it was given is kept
     * then, to know it by when the link is back — see [findAgain].
     */
    override fun disconnect(target: OutputTarget, why: Disconnect) {
        val renderer = (target as DlnaTarget).renderer
        castJob?.cancel()
        castJob = null
        dropSeekChecks()
        castSeekPending = -1L
        when (why) {
            Disconnect.LinkLost -> lostAtIndex = host.index.value
            Disconnect.Leaving -> {
                forgetCast()
                // A renderer keeps playing whatever URI it was handed, so leaving
                // without telling it to stop strands audio on the speaker while
                // the app shows "This device". Bounded so teardown can't hang on
                // an unreachable device. A muted start cut short gives the sound
                // back here as well as in its own ending, which may not run
                // before the process goes.
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
     * The queue went — a source switch — and the renderer stays the output:
     * stopped, with nothing of the old queue kept, down to how the server
     * being left delivers its formats, which the next one may answer in
     * another container. The next queue plays on it.
     */
    override fun stopPlayback() {
        val renderer = steered() ?: return
        castJob?.cancel()
        castJob = null
        dropSeekChecks()
        forgetCast()
        waitingForPlay = true
        castFormats.clear()
        castSeekable.clear()
        android.util.Log.i("AmpCast", "queue gone — ${renderer.name} stopped, still the output")
        host.scope.launch { DlnaCast.stop(renderer) }
    }

    /** Nothing of the queue is on the renderer any more, or nothing on it is followed from here. */
    private fun forgetCast() {
        castCurrentUrl = null
        castNextUrl = null
        castNextOffered = null
        castNextTrackId = null
        castNextArmedFor = null
        castOffsetMs = 0L
        castSeekPending = -1L
    }

    /** The queue index the renderer was on when the link took it away — see [findAgain]. */
    private var lostAtIndex = -1

    /** The address [findAgain] found the renderer playing, for [rejoin] to follow. */
    private var refoundOn: String? = null

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
        // Reachable again: a muted start the link cut off gives its sound back
        // now, whether or not the speaker is followed from here.
        undoHush(renderer)
        val current = castCurrentUrl
        val next = castNextUrl ?: castNextOffered
        repeat(REFOUND_READS) { attempt ->
            if (attempt > 0) delay(CAST_POLL_MS)
            val position = DlnaCast.position(renderer)
            val state = DlnaCast.state(renderer)
            if (position == null || state == DlnaState.UNKNOWN) return@repeat
            val uri = position.trackUri
            android.util.Log.i(
                "AmpCast",
                "${renderer.name} found again: $state on ${DlnaCast.loggable(uri)} at ${position.positionMs}",
            )
            val found = DlnaTarget(renderer)
            val playing = state != DlnaState.PAUSED
            return when {
                // Stopped there, on its own song — the remote's pause is a stop
                // on the Denon — and still saying where: shown paused at that
                // place. A renderer that clears its clock on a stop says 0, and
                // the phone's own place stands.
                state == DlnaState.STOPPED &&
                    current != null &&
                    DlnaCast.sameUri(uri, current) &&
                    position.positionMs > 0L ->
                    Refound(found, lostAtIndex, position.positionMs + castOffsetMs, playing = false)
                state == DlnaState.STOPPED -> Refound(found)
                current != null && DlnaCast.sameUri(uri, current) -> {
                    refoundOn = current
                    Refound(found, lostAtIndex, position.positionMs + castOffsetMs, playing)
                }
                next != null && DlnaCast.sameUri(uri, next) -> {
                    // Wherever that song sits now: an edit while the link was
                    // down can have moved it, or taken it out, and then the
                    // speaker is playing something the queue no longer has.
                    val after = offeredNextIndex() ?: return null
                    refoundOn = next
                    Refound(found, after, position.positionMs, playing)
                }
                else -> null
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
            android.util.Log.i("AmpCast", "search ${round + 1} of $REDISCOVER_ROUNDS: not there")
        }
        return null
    }

    override fun rejoin(found: Refound) {
        val renderer = (found.target as DlnaTarget).renderer
        val on = refoundOn
        refoundOn = null
        castJob?.cancel()
        dropSeekChecks()
        val following = found.index >= 0 && on != null
        // Holding nothing to resume, or playing this queue — see waitingForPlay.
        waitingForPlay = !following
        if (!following) {
            forgetCast()
        } else if (on != castCurrentUrl) {
            // It crossed into the track offered after it while nobody watched,
            // and that one was handed over whole.
            castCurrentUrl = on
            castOffsetMs = 0L
            castNextUrl = null
            castNextOffered = null
            castNextTrackId = null
            castNextArmedFor = null
        } else {
            // Still on its own song. What it holds after it was armed for the
            // queue as it stood when the link went, and is offered again for
            // the queue as it is now, once due.
            castNextArmedFor = null
        }
        castStartedAtMs = found.positionMs
        castSeekPending = -1L
        castJob = host.scope.launch {
            undoHush(renderer)
            // The fader is the renderer's again, as when a cast begins.
            DlnaCast.volume(renderer)?.let {
                host.showVolume(it)
                withContext(Dispatchers.Main.immediate) { host.setPhoneVolume(it) }
            }
            if (!following) return@launch
            host.announceNowPlaying()
            // It was playing all along: a stop from here on is the end of the track.
            pollRenderer(renderer, playedBefore = true)
        }
    }

    override fun play() {
        val renderer = steered() ?: return
        pressed(playing = true)
        if (waitingForPlay) {
            castJob?.cancel()
            castJob = host.scope.launch {
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
     * A play or pause pressed here, and when. Until the renderer has acted
     * on it, a poll still reading the old state is the old state, not news:
     * read as news, it showed playing again for a second after a pause, and
     * told the server so.
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
        // Only the very end is the next track: a target in the last second, or
        // past it, leaves nothing to hear. Anything before that is played, the
        // last seconds too. A stop straight after the seek reads as the track
        // ending, not as one that never started — see pollRenderer's
        // playedBefore — and the next track is armed at once, so they hand
        // over the way any track's last seconds do.
        val total = host.durationMs.value
        if (total > 0L && total - ms <= CAST_SEEK_END_SLACK_MS) {
            android.util.Log.i("AmpCast", "seek $ms of $total is the end → next")
            castJob?.cancel()
            castJob = host.scope.launch {
                if (advanceCast(renderer)) pollRenderer(renderer) else DlnaCast.stop(renderer)
            }
            return
        }
        host.showPosition(ms)
        // In place when the renderer can, pushed again when it can't — see
        // seekCast. The track was playing a moment ago, so a stop after
        // this is the end of it, not a failure to start.
        castJob?.cancel()
        castJob = host.scope.launch {
            if (seekCast(renderer, ms)) {
                // Not left to the poll's first round, a second or more away:
                // a seek into the last few seconds would have run out by then,
                // with nothing handed over to follow it.
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
        castJob?.cancel()
        castJob = host.scope.launch {
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
        castJob?.cancel()
        castJob = host.scope.launch {
            if (startOrStop(renderer)) pollRenderer(renderer)
        }
    }

    override suspend fun upcomingChanged() {
        val renderer = steered() ?: return
        // What plays next has changed, so whatever the renderer
        // was armed with is stale and it gets to be reconsidered.
        castNextArmedFor = null
        armNextIfDue(renderer)
    }

    /**
     * Seek within the track the renderer is playing.
     *
     * The renderer already has the stream. When that stream can be seeked — the
     * file itself, served in ranges — a Seek is all it takes: one call, about a
     * second, and the track carries on from the new position. Pushing the track
     * again first, as every cast seek did, had the renderer open the stream
     * afresh and play a burst of it from the top before seeking anyway. The
     * push stays for the cases that need it: a stream that starts part-way
     * (the renderer can seek only within what it holds), a transcode (which
     * nobody can seek, so it is asked for again from the position), and a
     * renderer that refuses the Seek.
     */
    private suspend fun seekCast(renderer: DlnaRenderer, ms: Long): Boolean {
        val track = host.queue.value.getOrNull(host.index.value) ?: return false
        val url = castCurrentUrl
        val wanted = if (ms > 1_000L) ms else 0L
        if (url != null && castOffsetMs == 0L) {
            val (format, _) = castFormatFor(renderer, track)
            val seekKey = "${track.id}|${format.id}"
            val seekable = castSeekable[seekKey] ?: probeSeekable(track, format, seekKey)
            if (seekable == true) {
                if (DlnaCast.seek(renderer, wanted)) {
                    android.util.Log.i("AmpCast", "seek in place → $wanted")
                    host.showPosition(wanted)
                    castStartedAtMs = wanted
                    castSeekPending = wanted
                    castSeekPendingAt = SystemClock.elapsedRealtime()
                    if (wanted > 0L) verifyCastSeek(renderer, wanted, url)
                    return true
                }
                android.util.Log.i("AmpCast", "in-place seek refused → push")
            }
        }
        return startOrStop(renderer, wanted)
    }

    /**
     * A moment after a seek, check the renderer really went there; if not,
     * ask the server for the track from that position instead.
     *
     * Refusing outright is the easy case. The hard one is a renderer that
     * accepts a Seek it can't honour: a live transcode arrives chunked with no
     * length, so there is nothing to jump to and it grinds forward through the
     * encode instead — minutes of silence on a long track. Unlike the local
     * player, GetPositionInfo reports where the renderer really is, so this is
     * measurable.
     *
     * Seeking into the last few seconds has nothing to verify: the track ends
     * before the check, the renderer's clock resets, and "landed at 0" read as
     * a failed seek — which restarted the track from the top, through the
     * server, just as it was about to end.
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
            if (host.index.value != seekedIndex || castCurrentUrl != url) return@launch
            val info = DlnaCast.position(renderer) ?: return@launch
            if (info.trackUri != null && !DlnaCast.sameUri(info.trackUri, url)) return@launch
            if (DlnaCast.state(renderer) == DlnaState.STOPPED) return@launch
            if (info.positionMs >= wanted - CAST_SEEK_TOLERANCE_MS) return@launch
            // Asked again on Main, where seeks and skips swap the cast job: the
            // two reads can take seconds, and a previous pressed meanwhile
            // must not have its job cancelled and the old position pushed back.
            withContext(Dispatchers.Main.immediate) {
                if (generation != seekGeneration || steered()?.id != renderer.id) return@withContext
                if (host.index.value != seekedIndex || castCurrentUrl != url) return@withContext
                // A seek taken before play and then not acted on: the renderer
                // started from the top. It starts muted from here on, and this
                // track again that way — the stream itself seeks fine, and a
                // server that isn't transcoding it would ignore an offset.
                val muted = url == seekedBeforePlay && seeksBeforePlay[renderer.id] != false
                if (muted) seeksBeforePlay[renderer.id] = false
                android.util.Log.i(
                    "AmpCast",
                    "seek missed: at ${info.positionMs} wanted $wanted → ${if (muted) "muted start" else "server"}",
                )
                castJob?.cancel()
                castJob = host.scope.launch {
                    if (startOrStop(renderer, wanted, askServer = !muted)) {
                        pollRenderer(renderer, playedBefore = true)
                    }
                }
            }
        }
    }

    /**
     * Push the queue's current track to the renderer, from [seekToMs] into it.
     *
     * Where it starts is settled before the renderer makes a sound. Played
     * first and seeked after, as it was, a cast that began mid-song played a
     * moment from the top, went quiet, and played again from the position.
     *
     * Seeking is the renderer's job where it can do it. Any response with a
     * length and byte ranges — anything a server hands over untranscoded — it
     * can seek by itself, instantly and without re-fetching: such a stream is
     * loaded, seeked and only then played — see [playFrom]. One the server
     * won't serve in ranges cannot be seeked by anyone, so it is asked for from
     * the position, as a `timeOffset`, and [askServer] says that is already
     * decided. Which one a track's stream is gets measured, per track, before
     * anything is pushed: the same format is a transcode for one file and the
     * file itself for another — see DlnaCast.supportsRanges.
     *
     * Inferring it from the format was wrong in the one case that matters:
     * asking for mp3 when the file is already mp3 gets the file untouched, and
     * the server then ignores timeOffset because it isn't transcoding. The
     * track restarted from the top while the readout claimed the seek landed.
     */
    private suspend fun startCastTrack(
        renderer: DlnaRenderer,
        seekToMs: Long = 0L,
        askServer: Boolean = false,
        fresh: Boolean = false,
    ): Boolean {
        dropSeekChecks()
        val track = host.queue.value.getOrNull(host.index.value) ?: return false
        // The server hears of the song from the index having moved, as it does
        // for the phone's own player — see followCurrentSong.
        val (format, mime) = castFormatFor(renderer, track)
        // Anything under a second is where the track starts anyway, and a
        // timeOffset of 0 is a re-encode for nothing.
        val wanted = if (seekToMs > 1_000L) seekToMs else 0L
        val offset = when {
            askServer -> wanted
            wanted == 0L -> 0L
            else -> {
                val seekKey = "${track.id}|${format.id}"
                val seekable = castSeekable[seekKey] ?: probeSeekable(track, format, seekKey)
                android.util.Log.i("AmpCast", "seekable=$seekable")
                if (seekable == false) wanted else 0L
            }
        }
        // No estimated length for a renderer. Navidrome derives the estimate from
        // duration x the *cap* bitrate, but its ffmpeg output is ABR and comes in
        // well under: a 100s track declared 3,091,660 bytes and delivered
        // 2,817,871. A UPnP renderer takes that declaration for the extent of the
        // stream and stops at the length it implies — about 23 seconds early on
        // that track. Chunked is honest: it plays to the end of the audio, and the
        // duration it displays comes from the DIDL metadata either way.
        val url = host.streamUrl(
            track,
            format,
            estimateContentLength = false,
            timeOffsetSeconds = (offset / 1000).toInt(),
            // The renderer fetches for itself, with no door to decide it
            // first — see OutputHost.ownStreamSession.
            sessionId = host.ownStreamSession(track.id),
        )?.let { if (fresh) freshAddress(it) else it } ?: return false
        val started = if (offset == 0L && wanted > 0L) {
            when (playFrom(renderer, track, url, mime, wanted)) {
                Start.PLAYING -> true
                Start.FAILED -> false
                // The renderer won't seek this stream after all: the server's
                // stream from the position, as for one that can't be seeked.
                Start.UNSEEKABLE -> return startCastTrack(renderer, wanted, askServer = true, fresh = fresh)
            }
        } else {
            DlnaCast.play(
                renderer = renderer,
                url = url,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                mimeType = mime,
            )
        }
        if (!started) return false
        // A start cancelled while its unmute was out still comes back PLAYING —
        // the unmute is never cut short. The rest belongs to whatever replaced it.
        coroutineContext.ensureActive()
        waitingForPlay = false
        android.util.Log.i("AmpCast", "pushed idx=${host.index.value} wanted=$wanted offset=$offset ${format.id}")
        host.showPlaying(true)
        host.showDuration(track.durationMs)
        castOffsetMs = offset
        host.showPosition(wanted)
        castStartedAtMs = wanted
        // Whether it really went there is checked a moment later — see
        // verifyCastSeek.
        if (offset == 0L && wanted > 0L) verifyCastSeek(renderer, wanted, url)
        host.announceNowPlaying()
        castCurrentUrl = url
        castSeekPending = -1L
        // SetAVTransportURI leaves the next slot empty, and it stays that way
        // until this track is nearly over — see armNextIfDue.
        castNextUrl = null
        castNextOffered = null
        castNextTrackId = null
        castNextArmedFor = null
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
        android.util.Log.i("AmpCast", "couldn't start idx=${host.index.value} → paused")
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
     * is muted instead — played, seeked, and heard only once its clock says it
     * got there, or a few seconds have gone. One with nothing to mute it with
     * is the last case left, and takes the old order and its moment from the
     * top.
     */
    private suspend fun playFrom(
        renderer: DlnaRenderer,
        track: Track,
        url: String,
        mime: String,
        wantedMs: Long,
    ): Start {
        seekedBeforePlay = null
        if (!DlnaCast.setUri(renderer, url, track.title, track.artist, track.album, track.durationMs, mime)) {
            return Start.FAILED
        }
        if (seeksBeforePlay[renderer.id] != false) {
            if (DlnaCast.seek(renderer, wantedMs)) {
                seekedBeforePlay = url
                return if (DlnaCast.resume(renderer)) Start.PLAYING else Start.FAILED
            }
            seeksBeforePlay[renderer.id] = false
            android.util.Log.i("AmpCast", "seek before play refused → muted starts on ${renderer.name}")
        }
        // Seen through even by a start cancelled meanwhile: the renderer is
        // muted whether or not the answer is waited for.
        val hush = withContext(NonCancellable) { hush(renderer) }
        if (hush == null) {
            android.util.Log.i("AmpCast", "nothing to mute ${renderer.name} with → play, then seek")
            if (!DlnaCast.resume(renderer)) return Start.FAILED
            if (DlnaCast.seek(renderer, wantedMs)) return Start.PLAYING
            android.util.Log.i("AmpCast", "renderer refused seek → server")
            return Start.UNSEEKABLE
        }
        try {
            if (!DlnaCast.resume(renderer)) return Start.FAILED
            if (!seekOncePlaying(renderer, wantedMs)) {
                android.util.Log.i("AmpCast", "renderer refused seek → server")
                // Stopped while it still can't be heard: the server's stream
                // takes over from the position.
                DlnaCast.stop(renderer)
                return Start.UNSEEKABLE
            }
            val there = clockReaches(renderer, wantedMs)
            android.util.Log.i("AmpCast", "muted start ${if (there) "reached $wantedMs" else "not seen there yet"} → sound")
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
    private val seeksBeforePlay = mutableMapOf<String, Boolean>()

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
    private val hushed = java.util.concurrent.ConcurrentHashMap<String, Hush>()

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
        android.util.Log.i("AmpCast", "couldn't give ${renderer.name} its sound back — again at the next chance")
    }

    /** Give [renderer] back any sound a muted start still holds. */
    private suspend fun undoHush(renderer: DlnaRenderer) {
        val hush = hushed[renderer.id] ?: return
        unhush(renderer, hush)
    }

    /**
     * Ask the server whether this track's stream can be seeked, and remember it.
     *
     * Deliberately **not** the URL the renderer is playing. That one carries the
     * session identifier, and a second request bearing the same one has Plex
     * tear the first down — it would answer the question by stopping the music.
     * Without an identifier the probe gets a transcode of its own, which it
     * closes immediately, and the answer is the same either way: whether the
     * server transcodes depends on the file and the format, not on who asked.
     */
    private suspend fun probeSeekable(
        track: Track,
        format: StreamFormat,
        key: String,
    ): Boolean? {
        val probeUrl = host.streamUrl(
            track,
            format,
            estimateContentLength = false,
        ) ?: return null
        // The probe goes to the music server, not the speaker, on a stack
        // outside NetworkGate — DlnaCast.allowsNetwork stands in front of it.
        return DlnaCast.supportsRanges(probeUrl)?.also { castSeekable[key] = it }
    }

    /**
     * Hand over the following track once the current one is nearly finished.
     *
     * The renderer opens the queued track's HTTP stream the instant it is given
     * one — the server logs the request a second after SetNextAVTransportURI,
     * not when the track eventually starts — and then plays from that same
     * connection however much later. Handed over at the start of a five-minute
     * track, the stream sits open and undrained for five minutes with the
     * server's transcoder writing into it, and by the time the renderer wants it
     * the connection has rotted: either it yields nothing, and the hand-off
     * arrives STOPPED, or it yields what was buffered and cuts out part-way
     * through. Both were seen on the Denon, minutes apart, from this one cause.
     */
    private suspend fun armNextIfDue(renderer: DlnaRenderer) {
        val total = host.durationMs.value
        // A track of unknown length (zero) still gets its hand-off: arming early
        // is this bug, but never arming at all is a gap on every single track.
        if (total > 0L && total - host.positionMs.value > CAST_PREQUEUE_LEAD_MS) return
        queueNextOnRenderer(renderer)
    }

    /**
     * Hand the renderer the following track so it can cross into it seamlessly.
     *
     * Records the URL under [castNextUrl] so the poller can recognise the
     * hand-off when it happens: the renderer switches on its own, and the only
     * evidence is that GetPositionInfo starts reporting the other URI. Under
     * [castNextOffered] too, before the reply: a renderer can take the address
     * and lose the answer — straight after a seek, say — and recorded only on
     * a reply, a hand-off it then made went unseen, the clock frozen on the
     * last track for as long as the new one played.
     *
     * Call through [armNextIfDue] rather than directly — handing a renderer the
     * next stream too early is what this whole path had to be rebuilt around.
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
        if (castNextArmedFor == after) return
        val (format, mime) = castFormatFor(renderer, track)
        // Its own id, not the current track's: both transcodes are in flight
        // at once during the hand-off, and Plex tears down whichever one
        // shares an identifier with the other — see OutputHost.ownStreamSession.
        val url = host.streamUrl(
            track,
            format,
            estimateContentLength = false,
            sessionId = host.ownStreamSession(track.id),
        ) ?: return
        if (url == castNextUrl) return
        castNextArmedFor = after
        castNextUrl = null
        castNextOffered = url
        castNextTrackId = track.id
        val queued = DlnaCast.setNext(
            renderer = renderer,
            url = url,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            mimeType = mime,
        )
        // AmpCast: temporary — chasing a hand-off the poll never followed
        // (2026-10-20). With the failed call's own line from DlnaCast, this
        // says whether the renderer was told and whether it answered.
        android.util.Log.i("AmpCast", "next idx=$after offered: ${if (queued) "taken" else "no answer"}")
        // Renderers that don't implement it fault; the stop-and-restart path then
        // carries the queue as it did before. An offer it never answered is
        // still looked for — see castNextOffered.
        if (queued) castNextUrl = url
    }

    /** Restarts so far, so each one is an address of its own — see [freshAddress]. */
    private var restarts = 0

    /**
     * [url] with a throwaway parameter, so a restart is a new address. The
     * Denon sat stopped on the first Plex song after a source switch through
     * every resend of it, and played the moment it was handed another song:
     * an address it already holds is no reason, to it, to fetch again.
     * Servers pass over a parameter they don't know.
     */
    private fun freshAddress(url: String): String =
        url + (if ('?' in url) "&" else "?") + "ampTry=" + (++restarts)

    /** Forget what was offered as the next track, on the renderer as well. */
    private suspend fun dropNext(renderer: DlnaRenderer) {
        val offered = castNextUrl ?: castNextOffered
        castNextUrl = null
        castNextOffered = null
        castNextTrackId = null
        castNextArmedFor = null
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
        val id = castNextTrackId ?: return null
        val queue = host.queue.value
        castNextArmedFor?.takeIf { queue.getOrNull(it)?.id == id }?.let { return it }
        return queue.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }

    /**
     * Format to cast in: the user's chosen streaming quality, honoured as long as
     * the renderer can actually decode what the *server* will send.
     *
     * Deliberately not an upgrade — choosing MP3 in settings means MP3 goes to the
     * speaker too. Two things make this trickier than it looks, and both are
     * load-bearing:
     *  - the format name in the URL doesn't fix the container (a "flac" transcode
     *    can arrive as Ogg-FLAC), so the MIME type is probed from the server;
     *  - an unsupported format doesn't error, the renderer just stalls, so we fall
     *    back to MP3 whenever the device's sink list doesn't cover that MIME.
     * If the device won't list its formats, the user's choice wins.
     */
    private suspend fun castFormatFor(
        renderer: DlnaRenderer,
        track: Track,
    ): Pair<StreamFormat, String> {
        val desired = host.streamFormat()
        val key = "${renderer.id}|${desired.id}"
        castFormats[key]?.let { return it }

        val probeUrl = host.streamUrl(track, desired)
        val sink = runCatching { DlnaCast.sinkFormats(renderer) }.getOrDefault(emptySet())
        // Same as probeSeekable: this asks the music server; DlnaCast's own
        // switch stands in front of it.
        val mime = probeUrl?.let { DlnaCast.probeMime(it) }
            ?: assumedMime(desired)
        val choice = if (sinkAccepts(sink, mime)) {
            desired to mime
        } else {
            StreamFormat.MP3 to "audio/mpeg"
        }
        castFormats[key] = choice
        return choice
    }

    /**
     * Mirror the renderer's clock into the UI and advance the queue when a track
     * finishes — a renderer only ever holds one URI, so gapless hand-off has to
     * be driven from here.
     */
    private suspend fun pollRenderer(renderer: DlnaRenderer, playedBefore: Boolean = false) {
        var settled = false
        // Whether a stop can only mean the track ended. A track pushed again
        // for a seek was playing a moment ago; it has nothing to prove, and a
        // stop after the seek is the end of it — or the seek killed it, which
        // going on from is also the right answer. Without this the stop came
        // before any poll had seen PLAYING, counted as a track that never
        // started, and restarted it from the top after a six-second wait.
        var played = playedBefore
        // What the renderer said last time. A position that hasn't moved is not
        // evidence that anything is playing — see below.
        var lastPositionMs = -1L
        // The queue index we have already tried to start by hand, so a track
        // that refuses to play is asked once and then passed over rather than
        // restarted for ever.
        var startedIndex = -1
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
        // AmpCast: temporary — the first polls after a start, chasing a renderer
        // that kept the old song's address for twenty seconds after being
        // handed a new one, after a source switch (2026-10-20).
        var startPolls = 0
        while (coroutineContext.isActive && steered()?.id == renderer.id) {
            delay(CAST_POLL_MS)
            undoHush(renderer)
            val position = DlnaCast.position(renderer)
            val state = DlnaCast.state(renderer)
            if (!settled && startPolls < START_POLLS_LOGGED) {
                startPolls++
                android.util.Log.i(
                    "AmpCast",
                    "start poll $startPolls idx=${host.index.value}: $state (${DlnaCast.lastTransportStatus}) on " +
                        "${DlnaCast.loggable(position?.trackUri)} at ${position?.positionMs}, " +
                        "holds ${DlnaCast.loggable(DlnaCast.mediaUri(renderer))}",
                )
            }
            // This reading is of the track we pushed or followed.
            var oursNow = false
            if (position != null) {
                val reported = position.trackUri
                // The renderer crossed into the track we queued: follow it here
                // rather than driving it, so nothing interrupts the audio.
                // Repeat-one queues the track that's already playing, so a URI
                // match on its own proves nothing — it has to be a *different*
                // URI from the one we started. Offered counts as much as
                // confirmed — see castNextOffered.
                val next = castNextUrl ?: castNextOffered
                val moved = next != null &&
                    !DlnaCast.sameUri(next, castCurrentUrl) &&
                    DlnaCast.sameUri(reported, next)
                if (moved) {
                    dropSeekChecks()
                    (offeredNextIndex() ?: nextIndex())?.let { host.showIndex(it) }
                    castCurrentUrl = next
                    // The queued track was handed over whole, from its start.
                    castOffsetMs = 0L
                    castStartedAtMs = 0L
                    castSeekPending = -1L
                    host.showDuration(
                        host.queue.value.getOrNull(host.index.value)?.durationMs
                            ?: host.durationMs.value,
                    )
                    host.announceNowPlaying()
                    // The slot the renderer just consumed is empty again, and is
                    // left that way until this track is nearly over.
                    castNextUrl = null
                    castNextOffered = null
                    castNextTrackId = null
                    castNextArmedFor = null
                    stall.reset()
                    // The renderer has *taken* the queued track but not started
                    // it: for a second or two it reports STOPPED while it opens
                    // the stream, and reports the finished track's position
                    // while doing so. Both of those read as "this track just
                    // ended" — which is what skipped the track that had only
                    // just begun. Unsettling it puts the new track back under
                    // the same protection a track gets when it first starts,
                    // and the rest of this poll is about the old one, so there
                    // is nothing here worth reading.
                    settled = false
                    played = false
                    heardMs = -1L
                    stoppedThere = false
                    // Seeded with the reading we just saw, not cleared: cleared,
                    // the next identical stale reading looks like a change and
                    // re-arms the very flag this is putting down.
                    lastPositionMs = position.positionMs
                    host.showPosition(0L)
                    // A hand-off that worked has the renderer already PLAYING the
                    // new stream at this very poll, its position back at zero —
                    // that is the gapless case, and nothing here should touch it.
                    // A dead one has it stopped, still holding the finished
                    // track's position, and it has never once recovered on its
                    // own. So give that one a single further poll to prove
                    // otherwise rather than the seconds a cold stream needs.
                    restartAfter = if (state == DlnaState.PLAYING) {
                        STALLED_POLLS_TO_RESTART
                    } else {
                        HANDOFF_POLLS_TO_RESTART
                    }
                    continue
                }
                // Only what the renderer says about *our* track. Between one
                // stream and the next it goes on reporting the last one — a
                // frozen position, sometimes for seconds — and reading that as
                // ours put the finished track's time on the new track's bar:
                // eighteen minutes into a thirty-seven second song, when the
                // offset we had seeked to was added on top.
                val ours = reported == null || DlnaCast.sameUri(reported, castCurrentUrl)
                oursNow = ours
                if (!ours && reported != strangeUri) {
                    // AmpCast: temporary — see queueNextOnRenderer.
                    strangeUri = reported
                    android.util.Log.i("AmpCast", "renderer reports ${DlnaCast.loggable(reported)}, neither its track nor the next")
                }
                if (ours) {
                    // Until this track has settled, only a reading near where
                    // it was started is believed: a renderer reports the new
                    // URI with the *old* clock for a poll or two while it opens
                    // the stream, and the finished track's 0:42 was flashing
                    // onto a track that hadn't begun.
                    val reading = position.positionMs + castOffsetMs
                    val pending = castSeekPending
                    if (pending >= 0L) {
                        // A seek is in flight. The renderer goes on reporting
                        // the old clock for a poll or two while it reopens the
                        // stream at the new position, and shown, that is the
                        // bar jumping back to where it was and forward again.
                        // Nothing is believed until a reading near the target;
                        // if none ever comes, the seek's own check re-pushes.
                        //
                        // Not for ever. A seek into the last seconds under
                        // repeat-one wrapped back to 0:00 before any reading
                        // came, no reading was ever near the target, and the
                        // clock and "Buffering…" stood still until another
                        // song was started.
                        if (kotlin.math.abs(reading - pending) <= CAST_START_WINDOW_MS) {
                            castSeekPending = -1L
                            host.showPosition(reading)
                            if (state != DlnaState.STOPPED) heardMs = reading
                        } else if (SystemClock.elapsedRealtime() - castSeekPendingAt > CAST_SEEK_WAIT_MS) {
                            android.util.Log.i("AmpCast", "seek to $pending never seen — following the renderer at $reading")
                            castSeekPending = -1L
                            host.showPosition(reading)
                            if (state != DlnaState.STOPPED) heardMs = reading
                        }
                    } else if (settled || kotlin.math.abs(reading - castStartedAtMs) <= CAST_START_WINDOW_MS) {
                        host.showPosition(reading)
                        if (state != DlnaState.STOPPED) heardMs = reading
                    }
                    // The renderer only knows about the piece of the track it was
                    // given, so a stream that started part-way in reports a short
                    // duration. The track's own is the honest number.
                    if (castOffsetMs == 0L && position.durationMs > 0) {
                        host.showDuration(position.durationMs)
                    }
                    // What counts as "this track has started": the renderer
                    // saying PLAYING (below), or a position that has *moved*
                    // while it is not stopped. A stopped renderer's position is
                    // never evidence of playing, whatever number it holds —
                    // through a hand-off it keeps reporting the finished track's
                    // last position, and reading that as a start is what let the
                    // next track's loading silence count as that track ending.
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
                    if (oursNow && castSeekPending < 0L) host.showSounding()
                }
                DlnaState.PAUSED -> if (!behindPress(playing = false)) host.showPaused()
                else -> Unit
            }
            when {
                // Played, then stopped: the track ended. Move on. Only readings
                // the renderer actually gave us count here — going quiet must
                // never cost a track that was playing perfectly well.
                // Paused there, below: nothing ends, restarts or is handed on
                // until it plays again. Let run, the stale clock read the cued
                // track as over, the next song was offered, the renderer took
                // it, and the restart below started it under a pause.
                stoppedThere -> Unit
                // Stopped part-way through instead: the Denon's own remote
                // pauses that way. Read as the track's end, it skipped to the
                // next song. Shown paused where it was heard last; a play here
                // pushes the track again from there — see waitingForPlay — and
                // one on its remote is followed.
                (settled || played) && stall.stopped >= STOPPED_POLLS_TO_ADVANCE &&
                    stoppedPartWay(heardMs, host.durationMs.value) -> {
                    android.util.Log.i("AmpCast", "stopped at $heardMs of ${host.durationMs.value} → paused there")
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
                (settled || played) && stall.stopped >= STOPPED_POLLS_TO_ADVANCE -> {
                    android.util.Log.i("AmpCast", "ended idx=${host.index.value} → advance")
                    if (!advanceCast(renderer)) return
                    settled = false
                    played = false
                    heardMs = -1L
                    stall.reset()
                    startedIndex = -1
                    restartAfter = STALLED_POLLS_TO_RESTART
                }
                // Never played at all. This renderer takes the track we queue
                // ahead of time, transitions to it — and then sits there
                // stopped, for ever. It is why tracks appeared to be skipped:
                // the miscounted stops that followed made us advance *past* the
                // track it had just loaded, so what was really a dead hand-off
                // sounded like a jump. Start it ourselves rather than stepping
                // over it. A renderer that has stopped answering counts too:
                // nothing is playing either way, and this is the decision that
                // can afford to be wrong.
                // Stopped, not opening anything, three readings running is a
                // renderer that turned the stream away rather than one still
                // fetching it — which says TRANSITIONING.
                !settled && !played &&
                    (stall.notPlaying >= restartAfter || stall.stopped >= TURNED_AWAY_POLLS) &&
                    startedIndex != host.index.value -> {
                    android.util.Log.i("AmpCast", "never started idx=${host.index.value} → restart")
                    startedIndex = host.index.value
                    stall.reset()
                    restartAfter = STALLED_POLLS_TO_RESTART
                    if (!startOrStop(renderer, fresh = true)) return
                }
                // Asked again and it still won't play: something is wrong with
                // this track rather than with the hand-off, and sitting in
                // silence is worse than going on.
                !settled && !played &&
                    stall.notPlaying >= restartAfter &&
                    startedIndex == host.index.value -> {
                    android.util.Log.i("AmpCast", "still not playing idx=${host.index.value} → pass over")
                    if (!advanceCast(renderer)) return
                    stall.reset()
                    startedIndex = -1
                    restartAfter = STALLED_POLLS_TO_RESTART
                }
            }
            // Late enough in the track that the renderer can be handed the next
            // one without the stream going stale before it is wanted.
            if (!stoppedThere) armNextIfDue(renderer)
        }
    }

    /**
     * Point [renderer] at the current track from [atMs], stopped: what a play
     * from its own remote, or a resume from here, then starts from.
     *
     * The Denon's remote pauses by stopping, and its play opens the track's
     * address again from the start of what that address holds: for a stream
     * the server began at a seek, the old seek point rather than where it
     * stopped. So the stream goes in again from there — seeked by the renderer
     * where it can seek this stream before playing, otherwise begun there by
     * the server. False when nothing was put in, and a play here pushes the
     * track again — see waitingForPlay.
     */
    private suspend fun cueAt(renderer: DlnaRenderer, atMs: Long): Boolean {
        val track = host.queue.value.getOrNull(host.index.value) ?: return false
        val (format, mime) = castFormatFor(renderer, track)
        val seekKey = "${track.id}|${format.id}"
        val seekable = castSeekable[seekKey] ?: probeSeekable(track, format, seekKey)
        val byRenderer = seekable == true && seeksBeforePlay[renderer.id] != false
        val offset = if (byRenderer) 0L else atMs / 1000 * 1000
        val url = host.streamUrl(
            track,
            format,
            estimateContentLength = false,
            timeOffsetSeconds = (offset / 1000).toInt(),
            sessionId = host.ownStreamSession(track.id),
        ) ?: return false
        if (!DlnaCast.setUri(renderer, url, track.title, track.artist, track.album, track.durationMs, mime)) return false
        // Held now, whatever happens next: its readings are this track's, and
        // a play from its own remote is followed from wherever it starts.
        castCurrentUrl = url
        castOffsetMs = offset
        castSeekPending = -1L
        val there = !byRenderer || DlnaCast.seek(renderer, atMs)
        castStartedAtMs = if (byRenderer && there) atMs else offset
        android.util.Log.i(
            "AmpCast",
            "cued idx=${host.index.value} at $atMs " +
                "${if (!byRenderer) "from the server" else if (there) "seeked" else "but not seeked"}, stopped",
        )
        return there
    }

    /** Move to the next queue entry on the renderer; false when the queue ends. */
    private suspend fun advanceCast(renderer: DlnaRenderer): Boolean {
        val next = when (host.repeatMode.value) {
            RepeatMode.TRACK -> host.index.value
            RepeatMode.QUEUE -> (host.index.value + 1) % host.queue.value.size.coerceAtLeast(1)
            RepeatMode.OFF -> host.index.value + 1
        }
        if (next !in host.queue.value.indices) {
            android.util.Log.i("AmpCast", "queue finished")
            // Stopped at the end of the last song, with nothing to resume —
            // see waitingForPlay. The controller winds back to the top, as it
            // does for the phone.
            waitingForPlay = true
            forgetCast()
            host.queueFinished(this)
            return false
        }
        host.showIndex(next)
        android.util.Log.i("AmpCast", "advance → idx=$next")
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

        /** AmpCast: temporary — how many polls after a start are logged. */
        const val START_POLLS_LOGGED = 12

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
         * How long a track that has never played is given before we start it
         * ourselves — see the STOPPED branch in pollRenderer.
         *
         * Longer than the advance threshold on purpose: a renderer opening a
         * fresh stream is legitimately stopped for a few seconds (about three on
         * the Denon), and this must not fire while it is merely loading.
         */
        const val STALLED_POLLS_TO_RESTART = 6

        /**
         * The same, for a track the renderer took from the queue and stopped on.
         *
         * Nothing is being loaded there — it had the whole of the previous track
         * to prepare — so a stop at that moment is a hand-off that has already
         * failed, not one still getting ready.
         */
        const val HANDOFF_POLLS_TO_RESTART = 2

        /**
         * How close to the end of the current track the next one is handed over.
         *
         * Long enough for the renderer to open the stream and buffer it — the
         * Denon takes about three seconds from a cold start — and short enough
         * that the connection has no time to go stale while it waits. See
         * [armNextIfDue] for why a stale one is the whole problem.
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
    }
}

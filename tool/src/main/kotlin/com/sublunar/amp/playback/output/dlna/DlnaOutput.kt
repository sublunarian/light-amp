package com.sublunar.amp.playback.output.dlna

import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.Track
import com.sublunar.amp.playback.output.Disconnect
import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget
import com.sublunar.amp.playback.output.PREVIOUS_RESTARTS_AFTER_MS
import com.sublunar.amp.playback.output.VolumeControl
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    override fun handBackPlays(wasPlaying: Boolean) = true

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

    private var castJob: Job? = null

    // "rendererId|format" -> stream format actually sent + its MIME type.
    private val castFormats = mutableMapOf<String, Pair<StreamFormat, String>>()

    /**
     * Whether a track's stream can be seeked, keyed by track and format.
     *
     * Per track rather than per format: asking a server for mp3 gets a live
     * transcode for a flac original and the untouched file for an mp3 one, and
     * only the second of those can be seeked. Only settled answers are kept, so
     * a probe that failed is asked again rather than remembered as a no.
     */
    private val castSeekable = mutableMapOf<String, Boolean>()

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
     * The queue index the renderer's next slot has been armed for — or offered
     * and refused.
     *
     * Arming is attempted from the poll loop now rather than once per track, so
     * without this a renderer that doesn't implement SetNextAVTransportURI would
     * be asked again every second for the whole lead window.
     */
    private var castNextArmedFor: Int? = null

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
            // Adopt the renderer's current level so the fader isn't lying — and
            // move the device's own to match, so the first press of the rocker
            // nudges from where the speaker is rather than jumping.
            DlnaCast.volume(renderer)?.let {
                host.showVolume(it)
                withContext(Dispatchers.Main.immediate) { host.setPhoneVolume(it) }
            }
            if (startCastTrack(renderer, fromMs)) pollRenderer(renderer)
        }
    }

    /**
     * Stop steering the renderer, for [why]: told to stop — and waited for,
     * briefly, when the app is being left — or, with the link gone, simply
     * forgotten, since there is nobody to hear it.
     */
    override fun disconnect(target: OutputTarget, why: Disconnect) {
        val renderer = (target as DlnaTarget).renderer
        castJob?.cancel()
        castJob = null
        dropSeekChecks()
        if (why == Disconnect.Leaving) {
            // A renderer keeps playing whatever URI it was handed, so leaving without
            // telling it to stop strands audio on the speaker while the app shows
            // "This device". Bounded so teardown can't hang on an unreachable device.
            runBlocking { withTimeoutOrNull(CAST_STOP_TIMEOUT_MS) { DlnaCast.stop(renderer) } }
            return
        }
        if (why == Disconnect.Stopped) {
            host.scope.launch { DlnaCast.stop(renderer) }
            return
        }
        castOffsetMs = 0L
        castSeekPending = -1L
        if (why != Disconnect.LinkLost) host.scope.launch { DlnaCast.stop(renderer) }
    }

    override fun play() {
        val renderer = steered() ?: return
        host.scope.launch { DlnaCast.resume(renderer) }
    }

    override fun pause() {
        val renderer = steered() ?: return
        host.scope.launch { DlnaCast.pause(renderer) }
    }

    override fun seekTo(ms: Long) {
        val renderer = steered() ?: return
        dropSeekChecks()
        // The last moments of a track are not worth a seek. Seeking means
        // pushing the stream again, probing it and seeking it — seconds —
        // after which the renderer stops almost at once, and a stop before
        // any poll had seen it playing read as a track that never started:
        // a six-second wait, then the track again from the top. Dragging to
        // the end means the next track, and that is what Next does.
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
            if (seekCast(renderer, ms)) pollRenderer(renderer, playedBefore = true)
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
            if (startCastTrack(renderer)) pollRenderer(renderer)
        }
    }

    // The renderer's own level over UPnP RenderingControl, and the phone's
    // nudged to match, so the rocker starts from where the speaker is.
    override fun setVolume(level: Float) {
        host.showVolume(level)
        val renderer = steered()
        if (renderer != null) {
            host.scope.launch { DlnaCast.setVolume(renderer, level) }
        }
        host.setPhoneVolume(level)
    }

    override suspend fun followDeviceVolume(level: Float) {
        val renderer = steered() ?: return
        DlnaCast.setVolume(renderer, level)
    }

    override fun newQueue() {
        val renderer = steered() ?: return
        dropSeekChecks()
        castJob?.cancel()
        castJob = host.scope.launch {
            if (startCastTrack(renderer)) pollRenderer(renderer)
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
                    if (wanted > 0L) verifyCastSeek(renderer, wanted, url)
                    return true
                }
                android.util.Log.i("AmpCast", "in-place seek refused → push")
            }
        }
        return startCastTrack(renderer, wanted)
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
            android.util.Log.i("AmpCast", "seek missed: at ${info.positionMs} wanted $wanted → server")
            castJob?.cancel()
            castJob = host.scope.launch {
                if (startCastTrack(renderer, wanted, askServer = true)) {
                    pollRenderer(renderer, playedBefore = true)
                }
            }
        }
    }

    /**
     * Push the queue's current track to the renderer, optionally seeking in.
     *
     * Seeking is the renderer's job first. It has the stream, and any response
     * with a length and byte ranges — which includes anything a server hands
     * over untranscoded — it can seek by itself, instantly and without
     * re-fetching. Only when it refuses is the seek handed to the server as a
     * `timeOffset`, and [askServer] says that has already been tried.
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
    ): Boolean {
        dropSeekChecks()
        val track = host.queue.value.getOrNull(host.index.value) ?: return false
        // The server hears of the song from the index having moved, as it does
        // for the phone's own player — see followCurrentSong.
        val (format, mime) = castFormatFor(renderer, track)
        // Anything under a second is where the track starts anyway, and a
        // timeOffset of 0 is a re-encode for nothing.
        val wanted = if (seekToMs > 1_000L) seekToMs else 0L
        val offset = if (askServer) wanted else 0L
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
        ) ?: return false
        val started = DlnaCast.play(
            renderer = renderer,
            url = url,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            mimeType = mime,
        )
        if (!started) return false
        android.util.Log.i("AmpCast", "pushed idx=${host.index.value} wanted=$wanted offset=$offset ${format.id}")
        host.showPlaying(true)
        host.showDuration(track.durationMs)
        castOffsetMs = offset
        host.showPosition(wanted)
        castStartedAtMs = wanted
        if (offset == 0L && wanted > 0L) {
            // A stream the server won't serve in ranges cannot be seeked by
            // anyone, so asking the renderer buys nothing but the four seconds
            // it takes to notice — which the listener spends hearing the wrong
            // part of the track. Ask the stream instead, and when it says no,
            // go straight to the server. Measured per track, because the same
            // format is a transcode for one file and the file itself for
            // another; see DlnaCast.supportsRanges.
            val seekKey = "${track.id}|${format.id}"
            val seekable = castSeekable[seekKey] ?: probeSeekable(track, format, seekKey)
            android.util.Log.i("AmpCast", "seekable=$seekable")
            if (seekable == false) {
                return startCastTrack(renderer, wanted, askServer = true)
            }
            // Whether it then went there is checked a moment later — see
            // verifyCastSeek.
            if (!DlnaCast.seek(renderer, wanted)) {
                android.util.Log.i("AmpCast", "renderer refused seek → server")
                return startCastTrack(renderer, wanted, askServer = true)
            }
            verifyCastSeek(renderer, wanted, url)
        }
        host.announceNowPlaying()
        castCurrentUrl = url
        castSeekPending = -1L
        // SetAVTransportURI leaves the next slot empty, and it stays that way
        // until this track is nearly over — see armNextIfDue.
        castNextUrl = null
        castNextOffered = null
        castNextArmedFor = null
        return true
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
            castNextUrl = null
            castNextArmedFor = null
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

    /** The index the queue would move to next, or null at the end of it. */
    private fun nextIndex(): Int? = nextCastIndex(host.index.value, host.queue.value.size, host.repeatMode.value)

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
        while (coroutineContext.isActive && steered()?.id == renderer.id) {
            delay(CAST_POLL_MS)
            val position = DlnaCast.position(renderer)
            val state = DlnaCast.state(renderer)
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
                    nextIndex()?.let { host.showIndex(it) }
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
                        if (kotlin.math.abs(reading - pending) <= CAST_START_WINDOW_MS) {
                            castSeekPending = -1L
                            host.showPosition(reading)
                        }
                    } else if (settled || kotlin.math.abs(reading - castStartedAtMs) <= CAST_START_WINDOW_MS) {
                        host.showPosition(reading)
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
                    host.showPlaying(true)
                }
                DlnaState.PAUSED -> host.showPlaying(false)
                else -> Unit
            }
            when {
                // Played, then stopped: the track ended. Move on. Only readings
                // the renderer actually gave us count here — going quiet must
                // never cost a track that was playing perfectly well.
                (settled || played) && stall.stopped >= STOPPED_POLLS_TO_ADVANCE -> {
                    android.util.Log.i("AmpCast", "ended idx=${host.index.value} → advance")
                    if (!advanceCast(renderer)) return
                    settled = false
                    played = false
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
                !settled && !played &&
                    stall.notPlaying >= restartAfter &&
                    startedIndex != host.index.value -> {
                    android.util.Log.i("AmpCast", "never started idx=${host.index.value} → restart")
                    startedIndex = host.index.value
                    stall.reset()
                    restartAfter = STALLED_POLLS_TO_RESTART
                    if (!startCastTrack(renderer)) return
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
            armNextIfDue(renderer)
        }
    }

    /** Move to the next queue entry on the renderer; false when the queue ends. */
    private suspend fun advanceCast(renderer: DlnaRenderer): Boolean {
        val next = when (host.repeatMode.value) {
            RepeatMode.TRACK -> host.index.value
            RepeatMode.QUEUE -> (host.index.value + 1) % host.queue.value.size.coerceAtLeast(1)
            RepeatMode.OFF -> host.index.value + 1
        }
        if (next !in host.queue.value.indices) {
            host.showPlaying(false)
            // The speaker ran out of queue: the listening is over.
            host.listeningEnded()
            return false
        }
        host.showIndex(next)
        android.util.Log.i("AmpCast", "advance → idx=$next")
        return startCastTrack(renderer)
    }

    private companion object {
        /** Long enough for a renderer that can seek to have done so. */
        const val CAST_SEEK_VERIFY_MS = 4_000L

        /** A cast seek this close to the end is the next track, not a seek. */
        const val CAST_SEEK_END_SLACK_MS = 3_000L
        const val CAST_SEEK_TOLERANCE_MS = 15_000L

        /** How far a just-started track's first readings may stray from its start before being disbelieved. */
        const val CAST_START_WINDOW_MS = 15_000L

        const val CAST_POLL_MS = 1_000L

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
    }
}

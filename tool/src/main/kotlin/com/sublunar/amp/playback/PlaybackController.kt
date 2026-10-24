package com.sublunar.amp.playback

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.sublunar.amp.App
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.Connectivity
import com.sublunar.amp.data.DownloadStore
import com.sublunar.amp.data.KnownStream
import com.sublunar.amp.data.LinkRules
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.NetworkGate
import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.SavedQueue
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.StreamProxy
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.playback.output.ActiveOutput
import com.sublunar.amp.playback.output.Disconnect
import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputControls
import com.sublunar.amp.playback.output.OutputFamilies
import com.sublunar.amp.playback.output.OutputFamily
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget
import com.sublunar.amp.playback.output.Outputs
import com.sublunar.amp.playback.output.PREVIOUS_RESTARTS_AFTER_MS
import com.sublunar.amp.playback.output.QueueEdit
import com.sublunar.amp.playback.output.Refound
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerAvailability
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightPlayerSpec
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's one playback engine. It drives the detached [LightAudioPlayer]
 * (the SDK's own service, which plays on with the screens gone), owns the
 * queue and every readout the screens show, and steers an [Output] instead
 * while the sound is elsewhere. Queue edits use the player's incremental ops,
 * so changing what plays next never interrupts the current track. What the
 * server is told lives in [ServerReports], where each track plays from in
 * [TrackSources], and the pure queue arithmetic in QueueMath.kt.
 *
 * Two rules hold throughout. The player's looper is Main and it throws on
 * any other thread, so every player call from a coroutine is made on Main,
 * mostly through Dispatchers.Main.immediate. And a released handle throws
 * too, so a deferred block re-reads [player] on Main instead of using a
 * handle it captured: a new activity may have replaced it in between.
 */
class PlaybackController(
    private val settings: AppSettings,
    private val serverClient: StateFlow<MusicServer?>,
    /** Unused; goes when App stops passing it. */
    daoProvider: () -> LibraryDao,
    downloads: DownloadStore,
    private val scope: CoroutineScope,
    /**
     * Whether the server may be spoken to at all — see App.metadataAllowed.
     * It gates the small talk (timelines, now-playing, scrobbles), not what
     * may be streamed, which the mode and the source's formats decide.
     */
    private val metadataAllowed: () -> Boolean,
    /** Where the stand-in keeps its silence — see [StandIn]. */
    private val standInDir: File,
) {
    // --- What the screens read -----------------------------------------------

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    /**
     * What the queue was started as, where that has a name ("Judith Radio"),
     * for saving it as a playlist. Null for a queue that is just some songs.
     * Set and cleared with the queue; adding to the queue keeps it.
     */
    private val _queueName = MutableStateFlow<String?>(null)
    val queueName: StateFlow<String?> = _queueName

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index

    val currentTrack: StateFlow<Track?> =
        combine(_queue, _index) { q, i -> q.getOrNull(i) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /**
     * A stream was asked to start and hasn't made a sound yet. The screen says
     * "Buffering…" instead of showing a track that looks paused, which invites
     * a second tap that tears the half-buffered stream down. Background work
     * (sync, playlists, downloads) steps aside meanwhile — see
     * App.yieldToPlayback. Cleared by sound, a failure, a pause, or
     * [BUFFERING_LIMIT_MS], so nothing waits on it for ever.
     */
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume

    /**
     * Loudness normalisation on or off, fed from settings by App. While on, the
     * playing track's [Track.gainDb] becomes the player's own volume — see the
     * gain collector in [collectHandle].
     */
    val replayGain = MutableStateFlow(true)

    /**
     * Parked: the current track can't play now, because it needs the server
     * and may not have it (the mode, no link, a server not answering) or
     * because it won't play at all. The value is the reason in the screen's
     * words, null when not parked. The player sits in its error state until
     * [resumeAfterWait] prepares it again.
     *
     * Parking is the last resort: playback that arrives at such a track by
     * itself skips ahead and leaves it in the queue — see [blockedAtCurrent].
     * Only a track the user asked for, or a queue with nothing playable left,
     * parks.
     */
    private val _waitingFor = MutableStateFlow<String?>(null)
    val waitingFor: StateFlow<String?> = _waitingFor

    private val _active = MutableStateFlow<ActiveOutput?>(null)

    /** The output that has the sound, or null while it comes out of this phone. */
    val active: StateFlow<ActiveOutput?> = _active

    /**
     * True once [bind] has attached the audio stack. The restore needs a
     * player and gets one try, and boot launches it before binding, so it
     * waits on this.
     */
    private val _bound = MutableStateFlow(false)
    val bound: StateFlow<Boolean> = _bound

    // --- Handle state --------------------------------------------------------

    /**
     * The handle on the detached service. Replaced on Main, by [attach], and
     * read from Default as well: by the restore, the rules and downloads
     * collectors, and a source switch's stop.
     */
    @Volatile
    private var player: LightAudioPlayer? = null

    /** The activity [player] was made from — see [attach] and [release]. */
    private var handleOwner: LightAudio? = null

    /**
     * The ReplayGain factor last given to the service, seeded onto each new
     * handle. A handle starts at unity and hands its gain to the service when
     * it connects, so without the seed a reopen on an attenuated track jumped
     * to full level.
     */
    private var appliedGain = 1f

    /**
     * The collectors [attachHandle] started on one handle's flows, and its wait
     * to connect. A released handle's flows just stop, so these are cancelled
     * with the handle — in [release], [handOver] and [handleLost] — rather than
     * left to pile up, one set per reopen.
     */
    private var handleJob: Job? = null

    /**
     * Everything else [bind] started: the collectors of this controller's and
     * the app's state, and the loops. They outlive the handle on purpose, as
     * the service plays on with the screens gone. The next bind replaces them;
     * a handover leaves them be, as none of them holds the handle.
     */
    private var bindJob: Job? = null

    // --- Start, park and skip state ------------------------------------------

    /** Whether anything has played since the queue last ended — see [rewindIfQueueFinished]. */
    private var hasPlayed = false

    /**
     * The queue position the user last asked for by hand: a tap on a row, a
     * new queue, next or previous. Playback reaching a position by itself is
     * not that, and the difference decides between parking and skipping.
     */
    private var pickedIndex: Int = -1

    /**
     * Where the last automatic skip landed. If that track is refused too, the
     * rules aren't what the skip believed, and skipping again would march
     * through the queue and lose the user's place: one skip, then a second
     * refusal in a row parks. Cleared once something plays.
     */
    private var lastSkipTarget: Int = -1

    /** The queue as it was when shuffle was switched on, to go back to when it's switched off. */
    private var preShuffleOrder: List<Track>? = null

    /** The source each row of the player's queue was last built from, so a no-op rebuild stays a no-op. */
    private var queuedSources: List<LightAudioSource> = emptyList()

    /** Where the position stood when a start was first seen buffering, and when — see [noteProgress]. */
    private var bufferingProbe: Pair<Long, Long>? = null

    /** When the start now buffering began, so [BUFFERING_LIMIT_MS] counts from then. */
    private var bufferingSinceMs = 0L

    /** Bumped per seek, so the check an earlier one left can't say "Buffering…" for this one. */
    private var seekBufferingGeneration = 0

    /** Bumped by anything that supersedes a pending stream decision — see [withStreamDecision]. */
    private var decisionGeneration = 0

    /** The decision [withStreamDecision] is still waiting on, or 0 — see [release]. */
    private var awaitedDecision = 0

    // --- Seek state ----------------------------------------------------------

    /**
     * How far into the track the current stream starts, when the server was
     * asked to seek. Zero for files and for a stream from the top.
     */
    @Volatile
    private var streamOffsetMs = 0L

    /**
     * The track whose queue slot holds a server-seeked remainder instead of
     * the whole song. The remainder really is in the player's queue, so a
     * repeat wrap or a tap on the row would play the tail again: [landedOn]
     * puts the whole song back once playback leaves the slot. A repeat-one
     * wrap never comes back to a remainder — see [restartCopyAt].
     */
    @Volatile
    private var offsetItemTrackId: String? = null

    /**
     * Where the player holds the whole song, queued straight after a
     * server-seeked remainder while repeat-one is on, or -1.
     *
     * Repeat-one would replay the remainder, the tail from the seek point. So
     * the remainder is followed by the song itself, with the player's repeat
     * off: it ends gaplessly into the song's start, and [finishRestart] then
     * drops the remainder and hands repeat back to the player.
     *
     * While set, the player's queue is one item longer than [_queue], so
     * anything that addresses the player by position calls [settleRestartCopy]
     * first. Written on Main; the collectors read it to tell the copy's start
     * from a new track.
     */
    @Volatile
    private var restartCopyAt = -1

    /**
     * Tracks whose native seek missed, which seek through the server from then
     * on. Whether a stream seeks natively can't be known in advance (a raw
     * stream still needs the server to answer byte ranges), so
     * [verifyNativeSeek] measures it, and a track pays for a wrong guess once.
     */
    private val seekNeedsReload = mutableSetOf<String>()

    /** Bumped per seek, so a slow verify can't act on a stale target. */
    private var seekGeneration = 0

    // --- Server reports and track sources ------------------------------------

    /** What the server is told about playback — see [ServerReports]. */
    private val reports = ServerReports(
        scope = scope,
        serverClient = serverClient,
        metadataAllowed = metadataAllowed,
        queue = _queue,
        index = _index,
        positionMs = _positionMs,
        durationMs = _durationMs,
        isPlaying = _isPlaying,
        buffering = _buffering,
        reportsElsewhere = { remote?.reportsItself == true },
        playbackWanted = ::playbackWanted,
        handleReady = { player?.availability?.value == LightAudioPlayerAvailability.Ready },
    )

    /** Where each track plays from, and what has failed — see [TrackSources]. */
    private val sources = TrackSources(serverClient, downloads, reports::streamSessionIdFor)

    // --- Output state --------------------------------------------------------

    /**
     * The phone's player while an output has the sound: silence, named and
     * timed like the output's song, so the media session says what the output
     * is doing — see [StandIn]. A press that reaches it goes where the sound
     * is.
     */
    private val standIn = StandIn(
        scope = scope,
        dir = standInDir,
        queue = _queue,
        index = _index,
        isPlaying = _isPlaying,
        positionMs = _positionMs,
    ) { press ->
        // Only while there is somewhere to send it: one arriving as the sound
        // comes back is the stand-in being put away.
        if (!isCasting) return@StandIn
        when (press) {
            StandInPress.Play -> play()
            StandInPress.Pause -> pause()
            is StandInPress.SeekTo -> seekTo(press.positionMs)
        }
    }

    /** Each family's output, made when first asked for and kept for the process. Main only. */
    private val outputs = mutableMapOf<String, Output>()

    /** When an output last reported where it is — see [carriedOn]. */
    @Volatile
    private var positionReportedAtMs = 0L

    /** What the phone was waiting for when it began casting, and on which song — see [castTo]. */
    @Volatile
    private var waitBeforeCast: Pair<String, Int>? = null

    /**
     * Since when a speaker the phone owns has sat paused, by the clock that
     * runs on through sleep; 0 while it plays or starts, or there is none —
     * see [forgetPausedSpeaker].
     */
    @Volatile
    private var speakerPausedSinceMs = 0L

    /** Lets a paused speaker go on time, while the phone is awake to run it. */
    private var forgetJob: Job? = null

    /** An output the link took away, to be taken back when it returns — see [rejoinLost]. */
    @Volatile
    private var lost: Lost? = null

    /** The search for [lost], while one runs. */
    @Volatile
    private var rejoinJob: Job? = null

    // --- Derived state -------------------------------------------------------

    /** True while the sound goes somewhere else instead of this phone. */
    private val isCasting: Boolean get() = _active.value != null

    private val parked: Boolean get() = _waitingFor.value != null

    /** Parked because of the track, not the link: the link changing is no reason to try it again. */
    private val parkedOnTheTrackItself: Boolean
        get() = _waitingFor.value.let { it == CANT_PLAY_FILE || it == CANT_PLAY_FORMAT || it == CANT_PLAY_THIS }

    /**
     * The player, when it holds the queue. Not while it plays the stand-in,
     * which must never be handed a real song: an edit then changes the queue
     * and the output only, and the queue goes back into the player with the
     * sound — see [takeOverHere].
     */
    private val queuePlayer: LightAudioPlayer? get() = player?.takeUnless { standIn.active }

    /**
     * The player's readings are the controller's: not while the sound is
     * elsewhere, or while the player still holds the stand-in.
     */
    private val playerSpeaks: Boolean get() = !isCasting && !standIn.active

    /**
     * Where the controls go while the sound is elsewhere, or null for this
     * phone. Every control asks this first and falls through to the phone's
     * own code when it is null.
     */
    private val remote: OutputControls? get() = _active.value?.output

    init {
        // Times how long a speaker the phone owns has sat paused — see [forgetPausedSpeaker].
        scope.launch {
            combine(_active, _isPlaying, _buffering) { a, playing, starting ->
                a != null && a.family in Outputs.phone && !playing && !starting
            }.distinctUntilChanged().collect { paused ->
                forgetJob?.cancel()
                if (!paused) {
                    speakerPausedSinceMs = 0L
                    return@collect
                }
                speakerPausedSinceMs = SystemClock.elapsedRealtime()
                forgetJob = launch {
                    delay(FORGET_PAUSED_SPEAKER_MS)
                    withContext(Dispatchers.Main.immediate) { forgetPausedSpeaker() }
                }
            }
        }
    }

    // --- The handle's life ---------------------------------------------------

    /**
     * Attach the audio stack from the activity that is starting: [bind] the
     * first time, and again after a back-out released the handle.
     *
     * A handle still held means the activity that made it is finishing or
     * gone. The activity is single-task, so a second boot is Android having
     * destroyed the first while the process lived on, or a configuration
     * change. That handle is stranded: its controller still names the dead
     * activity, and its eventual release is the crash [LateUnbindGuard]
     * absorbs. It is swapped for one made from the new activity — see
     * [handOver]. The same activity booting twice is left alone.
     */
    fun attach(audio: LightAudio) {
        val old = player
        when {
            old == null -> bind(audio)
            audio == handleOwner -> Unit
            else -> handOver(old, audio)
        }
    }

    /** Attach the audio stack with no handle held ([attach] decides), and start the loops that outlive handles. */
    private fun bind(audio: LightAudio) {
        // A reopen: the last bind's collectors would otherwise run beside these,
        // with two heartbeats and two chances at every scrobble.
        bindJob?.cancel()
        val bound = SupervisorJob(scope.coroutineContext[Job])
        bindJob = bound
        // What the screen carried across a reopen: the re-cue in attachHandle
        // puts it back if the service came back empty.
        openHandle(audio, _queue.value, _index.value, _positionMs.value)
        // Anything that changes what plays next has to reach an output holding
        // a URI chosen earlier. Debounced: one edit moves several of these.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch(bound) {
            combine(_queue, _index, _repeatMode) { _, _, _ -> Unit }
                .debounce(UPCOMING_DEBOUNCE_MS)
                .collect { remote?.upcomingChanged() }
        }
        // The connectivity callback misses the default moving from a weak Wi-Fi
        // to cellular while both stay up, which decides the next track's format
        // and whether a parked one may resume. So while sound plays or is waited
        // for, the system is asked every few seconds (two binder reads); idle,
        // nothing runs.
        scope.launch(bound) {
            combine(_isPlaying, _waitingFor) { playing, waiting -> playing to (waiting != null) }
                .distinctUntilChanged()
                .collectLatest { (playing, waiting) ->
                    var parkedForMs = 0L
                    while (playing || (waiting && parkedForMs < PARKED_POLL_LIMIT_MS)) {
                        delay(LINK_POLL_MS)
                        if (!playing) parkedForMs += LINK_POLL_MS
                        Connectivity.refresh()
                    }
                }
        }
        // The door coming up or going down moves every stream's address, and a
        // track parked for want of it can be cued again.
        scope.launch(bound) {
            App.streamProxy.state.collect { state ->
                reresolveQueue()
                if (state is StreamProxy.State.On) cueIfParked()
            }
        }
        // Nothing waits on "buffering" for ever. Counted from the start, not from
        // when this collector saw it: a reopen replaces it mid-start.
        scope.launch(bound) {
            _buffering.collectLatest { on ->
                if (on) {
                    val elapsed = SystemClock.elapsedRealtime() - bufferingSinceMs
                    delay(BUFFERING_LIMIT_MS - elapsed)
                    _buffering.value = false
                }
            }
        }
        // Everything the rules can change (the link, its price, the mode, the
        // server), heard in the same moment the library and the queue view hear
        // it — see LinkRules.
        scope.launch(bound) {
            App.rules.collect { rules ->
                // Nothing learnt while streaming wasn't allowed outlives that.
                // Cleared here, not only when the server answers again:
                // "reachable" never changes while a ping keeps succeeding.
                if (rules.mayStream) sources.forgetFailures()
                reresolveQueue()
                val out = remote
                if (!rules.mayUseLan) {
                    // A search for a lost speaker waits for the network to come
                    // back again — see rejoinLost.
                    rejoinJob?.cancel()
                    if (out != null && out.needsLan) {
                        // Off Wi-Fi the speaker is out of reach: the cast is let
                        // go and shown paused where it was. Nothing is sent, as
                        // nobody would hear it, and nothing starts on the phone:
                        // leaving the house is not a press of play. With Wi-Fi
                        // back it is taken back.
                        Log.i(out.logTag, "Wi-Fi gone — cast let go, to be taken back")
                        withContext(Dispatchers.Main.immediate) {
                            if (remote?.needsLan == true) dropOutput(Disconnect.LinkLost)
                        }
                    }
                } else if (lost != null) {
                    rejoinLost()
                }
                if (rules.mayStream) {
                    cueIfParked()
                } else {
                    // Still parked, maybe for another reason now, so the words
                    // stay true; only a wait about the link is reworded. On Main
                    // with every other writer, so this can't re-park a player
                    // un-parked in between.
                    withContext(Dispatchers.Main.immediate) {
                        if (parked && !parkedOnTheTrackItself) {
                            _waitingFor.value = rules.waitingFor ?: _waitingFor.value
                        }
                    }
                }
            }
        }
        // The library's read of the active source's downloads, shared.
        scope.launch(bound) {
            App.library.downloadFiles.collect { rows ->
                sources.downloadsChanged(rows)
                reresolveQueue()
            }
        }
        // A snapshot every few seconds survives a kill without writing to
        // DataStore at every position tick.
        scope.launch(bound) {
            while (true) {
                delay(SAVE_STATE_INTERVAL_MS)
                persistState()
            }
        }
        reports.follow(bound)
        _bound.value = true
    }

    /**
     * Swap the stranded handle for one made from the new activity, and change
     * nothing else. Main only: it runs inside the new activity's onCreate.
     *
     * Not a release and a bind: [release] is leaving (it ends a cast's
     * steering, closes the server's session, drops a pending start), and a new
     * activity means none of that. The loops keep running and nothing is
     * played, paused or seeked. [decisionGeneration] is left alone too: a press
     * still waiting on the server starts whatever [player] is when the answer
     * comes, so it lands on the new handle.
     *
     * The old handle goes first: the SDK makes one at a time and won't make the
     * next until the last is closed, which its release does before returning.
     * The new one follows in the same turn on Main, so the service is never
     * left unbound: the new controller binds at once, while the old one's
     * unbind is only posted (that unbind is what throws when the old activity
     * is gone). [player] goes from the old handle straight to the new one,
     * never through null: the restore and the re-resolution read it from
     * Default, and a restore that saw none would give up for good.
     */
    private fun handOver(old: LightAudioPlayer, audio: LightAudio) {
        settleRestartCopy()
        Log.i(
            "AmpLife",
            "new activity: handle moved over " +
                "(playing=${_isPlaying.value} idx=${_index.value} casting=$isCasting)",
        )
        val carried = _queue.value
        val carriedIndex = _index.value
        val carriedPositionMs = _positionMs.value
        handleJob?.cancel()
        handleJob = null
        standIn.unbind()
        old.release()
        openHandle(audio, carried, carriedIndex, carriedPositionMs)
    }

    /**
     * Make [audio]'s handle on the service, seeded with the gain the service
     * last had, and attach it with what the screen shows as it is made — see
     * [attachHandle]. Main only. A failure is thrown on: the activity doesn't
     * start without its player. Left behind is no handle, not a released one
     * that throws at the next press.
     */
    private fun openHandle(audio: LightAudio, carried: List<Track>, carriedIndex: Int, carriedPositionMs: Long) {
        val p = try {
            // Detached: playback lives in the SDK's service and survives the
            // screen and the activity going away.
            audio.newPlayer(LightPlayerSpec(playback = LightAudioPlayback.Detached))
        } catch (e: Throwable) {
            player = null
            handleOwner = null
            throw e
        }
        p.playbackGain = appliedGain
        player = p
        handleOwner = audio
        attachHandle(p, carried, carriedIndex, carriedPositionMs)
    }

    /**
     * Everything that lives on one handle: the volume follower and, once the
     * handle has connected, the re-cue or the landing and the collectors of
     * its flows. Run for each new handle, by [openHandle], and cancelled
     * with it through [handleJob].
     *
     * Until the handle connects its flows hold placeholders (nothing playing,
     * 0:00, no track); the SDK fills them from the service before it calls the
     * handle ready. Read earlier, they flashed 0:00 on every reopen and turned
     * a press of pause into a resume, so they are read only after
     * [LightAudioPlayer.awaitReady]. Until then the screen keeps what it had
     * when the handle was made: [carried], [carriedIndex], [carriedPositionMs].
     */
    private fun attachHandle(
        p: LightAudioPlayer,
        carried: List<Track>,
        carriedIndex: Int,
        carriedPositionMs: Long,
    ) {
        val handle = SupervisorJob(scope.coroutineContext[Job])
        handleJob = handle
        // The fader follows the system media volume, and a change to it while
        // casting goes on to the output. The first reading is the phone's level,
        // not a press: a handle arriving mid-cast mustn't put it over the
        // output's own level, or send it there. Started at once: this level is
        // the system's, not a placeholder, and held back a cold start's fader
        // sat at full until the service answered.
        scope.launch(handle) {
            var first = true
            p.deviceVolume.collect { level ->
                if (first) {
                    first = false
                    if (isCasting) return@collect
                }
                _volume.value = level
                remote?.followDeviceVolume(level)
            }
        }
        scope.launch(handle + Dispatchers.Main.immediate) {
            if (!p.awaitReady()) {
                // Amp's own releases cancel this first, so this is the SDK
                // giving up on a connection that failed.
                if (player === p) handleLost()
                return@launch
            }
            if (player !== p) return@launch
            // A new activity mid-cast: the stand-in goes back in on this handle.
            standIn.bind(p)
            // An error the service was already in, raised while no handle
            // listened, is only noted: it can be any age, and handled as a live
            // failure it would skip ahead and start the next track the moment
            // the app was opened. The error collector acts from here on.
            p.error.value?.let {
                Log.i(
                    "AmpNet",
                    "connected onto a player error ${it.diagnostic} at idx=${it.itemIndex} — left as it is",
                )
            }
            // A service that stopped itself after its idle limit comes back
            // empty under a queue that still shows, and play then does nothing;
            // restoreState runs once per process and can't help. So the queue
            // goes back in, cued and paused, as a cold start restores it — only
            // in that case, see needsRecue. Before the collectors start, so they
            // read the queue back in place rather than the empty player.
            val recue = carriedIndex in carried.indices && needsRecue(
                carriedOver = _queue.value === carried && _index.value == carriedIndex,
                playerIndex = p.currentMediaItemIndex.value,
                playerPlaying = p.isPlaying.value,
                casting = isCasting,
                parked = parked,
            )
            if (recue) {
                Log.i("AmpNet", "reopened onto an empty player — cueing idx=$carriedIndex again")
                pickedIndex = carriedIndex
                rebuildQueueAt(p, carried, carriedIndex, carriedPositionMs)
                // A new service player has repeat off, whatever the screen shows.
                setRepeat(_repeatMode.value)
                p.pause()
            }
            // A service that played on while no handle listened is on another
            // track, part-way through. Landed on before the collectors start, so
            // the index collector can't zero the position behind the position
            // collector, and the duration collector measures the right track
            // with no offset left over. Not while parked: the service then sits
            // on the failed track, and the screen shows where the user moved
            // since. Read once, as a resume could change it half-way.
            val heldByPark = parked
            val connectedOn = p.currentMediaItemIndex.value
            connectLanding(
                playerIndex = connectedOn,
                playerPositionMs = p.positionMs.value,
                shownIndex = _index.value,
                pickedIndex = pickedIndex,
                recued = recue,
                casting = isCasting,
                parked = heldByPark,
            )?.let { startMs -> landedOn(p, connectedOn, startMs) }
            collectHandle(p, handle, heldByPark)
            // The service can be seen again: a back-out closed the server's
            // session while it played on, and this opens it again on what is
            // actually playing, where it has got to, to be stopped later.
            reports.followCurrentSong(atMs = if (isCasting) _positionMs.value else p.positionMs.value + streamOffsetMs)
            // Re-resolutions asked for while the handle connected were turned
            // away, by reresolveQueue's wait for Ready or by a handover replacing
            // the handle they read; this pass sends them. A re-cue has just built
            // the queue from fresh answers.
            if (!recue) reresolveQueue()
        }
    }

    /**
     * The collectors of a connected handle's flows, on [handle] — see
     * [attachHandle] for why not before. [heldByPark]: it connected while
     * parked, so its first track and position are the failed track's, which
     * the screen deliberately left. That first reading of each is skipped and
     * everything after it followed, so a skip that reaches the service
     * directly, or the resume's own rebuild, still moves the screen.
     */
    private fun collectHandle(p: LightAudioPlayer, handle: Job, heldByPark: Boolean) {
        // A failure is the error going from none to one. The flow repeats an
        // error when the queue moves under it, and a retry clears it first, so a
        // second failure counts again. One there at connect was noted in
        // attachHandle.
        scope.launch(handle + Dispatchers.Main.immediate) {
            var last = p.error.value
            p.error.collect { error ->
                if (error != null && last == null) playerFailed(p, error)
                last = error
            }
        }
        // While casting, the output is the truth for these readings; the paused
        // local player would report 0:00 and not-playing over it.
        scope.launch(handle) {
            p.isPlaying.collect { playing ->
                if (!playerSpeaks) return@collect
                _isPlaying.value = playing
                if (playing) {
                    heardSound()
                } else if (hasPlayed) {
                    rewindIfQueueFinished(p)
                }
            }
        }
        // A server-seeked stream starts at 0:00 of a shorter file, so the
        // readouts are shifted back into the track's own timeline. Not on the
        // restart copy, which is the song from its start.
        scope.launch(handle) {
            var snapshot = heldByPark
            p.positionMs.collect {
                if (snapshot) {
                    snapshot = false
                    return@collect
                }
                if (playerSpeaks) {
                    _positionMs.value = it + if (onRestartCopy(p)) 0L else streamOffsetMs
                    noteProgress(it)
                }
            }
        }
        scope.launch(handle) {
            p.durationMs.collect { reported ->
                if (!playerSpeaks) return@collect
                // The library's length, real before a byte has arrived.
                val current = _queue.value.getOrNull(_index.value)
                val known = current?.durationMs ?: 0L
                val fromFile = current?.id?.let(sources::hasDownload) == true
                // Never on the restart copy: read as an ignored offset below, it
                // sent the copy to the seek point and repeat-one played the tail
                // twice.
                val offset = if (onRestartCopy(p)) 0L else streamOffsetMs
                // The server ignored the seek: a seeked stream's length is the
                // remainder, and this one is the whole track. Navidrome honours
                // timeOffset only while transcoding, so asked for the format the
                // file already is, it serves the file whole from the top while
                // the readouts claim the seek worked. That file has a seek table,
                // so the player finishes the seek itself.
                if (offset > 0L &&
                    known > 0L &&
                    reported >= known - IGNORED_OFFSET_SLACK_MS
                ) {
                    val target = offset
                    streamOffsetMs = 0L
                    // Whichever handle holds the service by then: the offset has
                    // already left the readouts, so the seek mustn't be dropped.
                    scope.launch(Dispatchers.Main.immediate) { player?.seekTo(target) }
                    _durationMs.value = reported
                    return@collect
                }
                _durationMs.value = when {
                    // A transcode arrives chunked, with no length to report;
                    // Plex streams every format but the original that way.
                    reported <= 0L -> known
                    // A seeked stream's own length is the remainder.
                    offset > 0 -> known.takeIf { it > 0L } ?: (reported + offset)
                    // A download's length is the server's to state. A VBR mp3
                    // transcoded to disk has no Xing header, and ExoPlayer's
                    // guess from the first frame's bitrate can be far off.
                    fromFile && known > 0L -> known
                    else -> reported
                }
            }
        }
        // ReplayGain: the playing track's gain becomes the player's volume,
        // attenuate-only (clamped at unity), so nothing is boosted into
        // clipping. While casting the stand-in is item 0 and its silence gets
        // the first track's gain, which is harmless.
        //
        // No index is no answer. A handle that hasn't connected, or an empty
        // player, reads -1, and unity for it would overwrite the gain the
        // handle was seeded with.
        scope.launch(handle) {
            combine(p.currentMediaItemIndex, _queue, replayGain) { idx, q, on ->
                if (idx < 0) return@combine null
                // The restart copy is the song in the slot before it.
                val slot = if (restartCopyAt in 0..idx) idx - 1 else idx
                val db = if (on) q.getOrNull(slot)?.gainDb else null
                db?.let { 10.0.pow(it / 20.0).toFloat().coerceAtMost(1f) } ?: 1f
            }.filterNotNull().distinctUntilChanged().collect { factor ->
                withContext(Dispatchers.Main.immediate) {
                    // A replaced handle's number mustn't be carried onto the next.
                    if (player !== p) return@withContext
                    p.playbackGain = factor
                    appliedGain = factor
                }
            }
        }
        scope.launch(handle) {
            var snapshot = heldByPark
            p.currentMediaItemIndex.collect { idx ->
                if (!playerSpeaks) return@collect
                // Each change is a track starting from its beginning. The one
                // found at connect, attachHandle has already landed on (or,
                // parked, deliberately not).
                if (snapshot) {
                    snapshot = false
                } else if (restartCopyAt >= 0) {
                    // The remainder ending into the copy is the song starting
                    // over, not a new track. Anywhere else the queue goes back
                    // in step first, and a later slot is one less on screen.
                    val slot: Int? = withContext(Dispatchers.Main.immediate) {
                        val copy = restartCopyAt
                        when {
                            player !== p || copy < 0 -> idx
                            idx == copy -> {
                                finishRestart(p, copy)
                                null
                            }
                            idx == copy - 1 -> idx
                            else -> {
                                settleRestartCopy()
                                if (idx > copy) idx - 1 else idx
                            }
                        }
                    }
                    if (slot == null) return@collect
                    if (slot >= 0 && slot != _index.value) landedOn(p, slot, startMs = 0L)
                } else if (idx >= 0 && idx != _index.value) {
                    landedOn(p, idx, startMs = 0L)
                }
            }
        }
    }

    /**
     * The player is on [idx], not the track the screen shows: a track change,
     * or, from [attachHandle], a service that moved on while no handle
     * listened. [startMs] is where that leaves playback: 0 for a change as it
     * happens, the service's own reading for the one found at connect.
     */
    private fun landedOn(p: LightAudioPlayer, idx: Int, startMs: Long) {
        // Gapless: sound never stopped, so no new "playing" will clear this.
        if (p.isPlaying.value) _buffering.value = false
        // Landed somewhere other than where the user pointed: that request
        // mustn't decide a later refusal.
        if (idx != pickedIndex) pickedIndex = -1
        _index.value = idx
        // A new track starts from its beginning, offset and position both. A
        // position left over from the last track went out under the new one's
        // id, and Plex refused it as longer than the song (400) or kept it as a
        // resume point. The paths that start a track part-way (a restore, a
        // server seek) set _index before the player moves, so never get here —
        // see rebuildQueueAt. The track found at connect brings its own
        // position, read with its index from the same snapshot: zeroed, a
        // paused reopen showed and saved 0:00 for good.
        streamOffsetMs = 0
        _positionMs.value = startMs
        // Leaving a server-seeked remainder: the whole song goes back in its
        // slot, so a later visit plays the track and not the tail.
        offsetItemTrackId?.let { staleId ->
            offsetItemTrackId = null
            val q = _queue.value
            if (q.getOrNull(idx)?.id == staleId) {
                // Arrived onto a stale remainder (a wrap over a slot never put
                // back): rebuild it live.
                scope.launch(Dispatchers.Main.immediate) { seekTo(0) }
            } else {
                val stubIdx = q.indexOfFirst { it.id == staleId }
                val original = q.getOrNull(stubIdx)
                if (stubIdx >= 0 && original != null) {
                    scope.launch(Dispatchers.Main.immediate) {
                        // A handle that replaced this one steers the same queue.
                        val cur = player ?: return@launch
                        cur.replaceMediaItem(stubIdx, sources.item(original))
                    }
                }
            }
        }
        seedDuration(_queue.value.getOrNull(idx))
        // The server hears of the new song through ServerReports.followCurrentSong.
    }

    /**
     * The SDK released the handle before it connected. Kept in [player], it
     * threw at the next press ("has been released") and took the process
     * down. Dropped, the controls do nothing until a new activity, or a
     * back-out and reopen, makes a new one.
     */
    private fun handleLost() {
        Log.w("AmpLife", "player handle released before it connected — dropped")
        handleJob?.cancel()
        handleJob = null
        standIn.unbind()
        player = null
        handleOwner = null
    }

    /**
     * The boot screen of [owner] is backing out: the app is being left. One
     * boot screen per activity, so [owner] names the activity.
     */
    fun release(owner: LightAudio) {
        // An older activity's last screen, after a newer one took the player
        // over: the handle, and the leaving, are the newer one's.
        if (handleOwner != null && owner != handleOwner) {
            Log.i("AmpLife", "an old screen closed; the current player is left alone")
            return
        }
        // A backed-out service plays on with nobody to hand repeat-one back to
        // it, so the restart copy goes now.
        settleRestartCopy()
        // The output stops being steered from here (a speaker is told to stop,
        // a Plex player is left playing — see each output's disconnect), and
        // one the link took away isn't taken back after leaving.
        forgetLost()
        dropOutput(Disconnect.Leaving)
        // The service plays on detached, and a reopen must find the queue in
        // it, paused where the speaker had got to, not the stand-in's silence.
        if (standIn.active) {
            standIn.stop()
            val cur = player
            val tracks = _queue.value
            if (cur != null && tracks.isNotEmpty()) {
                rebuildQueueAt(cur, tracks, _index.value.coerceIn(0, tracks.lastIndex), _positionMs.value)
                cur.repeatMode = _repeatMode.value.forPlayer()
                cur.pause()
            }
        }
        // The session closes now, though detached audio may play on: from here
        // nothing can see what it plays, and the reopen opens it again on
        // whatever plays by then. The stop (after anything said before it) and
        // a last snapshot are waited for together, so a slow server can't spend
        // the snapshot's time, and bounded: the process may not outlive this by
        // much.
        reports.close()
        runBlocking {
            withTimeoutOrNull(SAVE_STATE_TIMEOUT_MS) {
                val stopped = launch { reports.flush() }
                persistState(final = true)
                stopped.join()
            }
        }
        // A start still waiting on the server's decision is dropped: leaving
        // outranks it, and it would start whatever handle a reopen has made by
        // then. (A handover lets it through.) Buffering goes off only if that
        // start was its reason.
        if (awaitedDecision != 0 && awaitedDecision == decisionGeneration) _buffering.value = false
        decisionGeneration++
        // The handle's own collectors go with it; the rest stay — see bindJob.
        handleJob?.cancel()
        handleJob = null
        // At once, on Main, whatever state the handle is in: the SDK cancels a
        // connection in flight itself, and media3 unbinds only once.
        player?.release()
        player = null
        handleOwner = null
    }

    // --- The saved queue -----------------------------------------------------

    /** Write the queue, index and position so the next launch can pick them up. */
    private suspend fun persistState(final: Boolean = false) {
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        settings.saveQueue(tracks.map { it.id }, _index.value, _positionMs.value)
        reports.pushQueue(tracks, force = final)
    }

    /**
     * Reload the queue saved by a previous run, cued and paused. Tracks deleted
     * since drop out, and the index follows the saved track among those left.
     * [library] is read only after the waits, so none of them holds a list a
     * write has since replaced.
     */
    suspend fun restoreState(library: () -> List<Track>) {
        if (player == null || _queue.value.isNotEmpty()) return
        // The downloads first: decided before the database answers, a
        // downloaded track would be queued as a stream, which offline never
        // plays. Bounded, so a database that never answers can't cost the
        // restore.
        withTimeoutOrNull(DOWNLOADS_READY_TIMEOUT_MS) {
            App.library.downloadsLoaded.first { it }
        }
        // And the loopback door, for the same reason: built a moment early, the
        // current track's address would be the server's own.
        withTimeoutOrNull(PROXY_READY_TIMEOUT_MS) {
            App.streamProxy.state.first { it !is StreamProxy.State.Starting }
        }
        val localIds = settings.savedQueueIds.first()
        val saved = if (localIds.isNotEmpty()) {
            SavedQueue(
                localIds,
                localIds.getOrNull(settings.savedQueueIndex.first()),
                settings.savedPositionMs.first(),
            )
        } else {
            // Nothing saved here (a fresh install, a cleared cache): the server
            // may hold what this account last played elsewhere. Never asked
            // over a queue this phone has.
            serverClient.value?.let { runCatching { it.getPlayQueue() }.getOrNull() }
        } ?: return
        val byId = library().associateBy { it.id }
        val tracks = saved.trackIds.mapNotNull { byId[it] }
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == saved.currentId }.takeIf { it >= 0 } ?: 0

        val position = saved.positionMs
        withContext(Dispatchers.Main.immediate) {
            val p = player ?: return@withContext
            _queue.value = tracks
            preShuffleOrder = null
            // The track the user left on: if it can't be had now it waits here
            // rather than being skipped. The position goes in with the rebuild,
            // as a seek this early would clamp to 0.
            pickedIndex = index
            rebuildQueueAt(p, tracks, index, position)
            // Cued, not played: opening the app doesn't start the music.
            p.pause()
        }
    }

    // --- Controls ------------------------------------------------------------

    /** Replace the queue with [tracks] and start at [startIndex]; see [queueName]. */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0, name: String? = null) {
        if (tracks.isEmpty()) return
        // A tap on the row that is still starting is left alone: a rebuild
        // throws away what has buffered and asks for a new stream (on Plex a new
        // transcode), so each impatient tap on a slow link put the sound further
        // off.
        if (_buffering.value && !_shuffle.value) {
            val playing = _queue.value
            val at = _index.value
            // The same list, not just the same song: every list of five hundred
            // or more is the same length once capped, so the name and the rows
            // either side are compared too.
            if (name == _queueName.value &&
                tracks.getOrNull(startIndex)?.id == playing.getOrNull(at)?.id &&
                tracks.getOrNull(startIndex + 1)?.id == playing.getOrNull(at + 1)?.id &&
                tracks.getOrNull(startIndex - 1)?.id == playing.getOrNull(at - 1)?.id &&
                playing.size == minOf(tracks.size, MAX_QUEUE)
            ) {
                Log.i("AmpNet", "tap on a row that is already starting — left alone")
                return
            }
        }
        _queueName.value = name
        reports.newQueue()
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        if (_shuffle.value) {
            // Shuffled as positions, the tapped one first, so the window has an
            // order to go back to when shuffle is switched off: its songs as the
            // list had them. Set after setQueue, which clears the last queue's.
            val picked = shuffledFrom(tracks.size, start)
            setQueue(picked.map { tracks[it] }, 0)
            preShuffleOrder = picked.sorted().map { tracks[it] }
        } else {
            val (window, index) = capped(tracks, start)
            setQueue(window, index)
        }
    }

    fun togglePlayPause() {
        forgetPausedSpeaker()
        val playing = when {
            remote != null -> _isPlaying.value
            player == null -> return
            // Parked: a play on the player as it stands is silence — see [play].
            parked -> false
            // "Playing" includes a start that hasn't sounded yet: a press while a
            // stream buffers means stop trying, not try again.
            else -> _isPlaying.value || _buffering.value
        }
        if (playing) pause() else play()
    }

    private fun play() {
        forgetPausedSpeaker()
        remote?.let { out ->
            _isPlaying.value = true
            // A resume carries on a stream the far end has open. Where nothing
            // has played yet (the queue ran out there, or holds nothing), play
            // is a start, and says so.
            if (!hasPlayed) markStartingElsewhere()
            out.play()
            reports.timeline(TimelineState.PLAYING)
            return
        }
        // Parked: the player is in its error state, and playing it is silence.
        // It is prepared again where it stood or, if the rules still say no,
        // left parked with the screen saying so.
        if (parked) {
            // A press is also a reason to ask the server again, if that is what
            // is waited for (the answer arrives through App.rules), and to try
            // the door again if that is what is closed.
            if (!App.rules.value.serverReachable) App.reachability.check(delayMs = 0L)
            if (App.streamProxy.state.value is StreamProxy.State.Off) App.streamProxy.start(App.scope)
            resumeAfterWait(play = true)
            return
        }
        startWhenDecided(_index.value)
        reports.timeline(TimelineState.PLAYING)
    }

    private fun pause() {
        remote?.let { out ->
            _isPlaying.value = false
            cancelPendingStart()
            out.pause()
            reports.timeline(TimelineState.PAUSED)
            return
        }
        cancelPendingStart()
        player?.pause()
        reports.timeline(TimelineState.PAUSED)
    }

    /**
     * Seek by whichever route the source allows. A file seeks natively and
     * exactly. A transcoded stream can't: it is a live encode with no seek
     * table, so the player could only guess a byte offset and read forward to
     * it, pulling megabytes before a sound and landing roughly. So the server
     * seeks instead: the same track asked for with a time offset, which starts
     * the encode there, while [streamOffsetMs] shifts the readouts back into
     * the track's own timeline.
     */
    fun seekTo(ms: Long) {
        remote?.let { out ->
            // As on the phone, only a seek in a track that is playing waits.
            if (_isPlaying.value) markStartingElsewhere()
            out.seekTo(ms)
            reports.seeked(ms)
            return
        }
        val p = player ?: return
        settleRestartCopy()
        val track = _queue.value.getOrNull(_index.value)
        if (track == null || !needsServerSeek(track)) {
            p.seekTo(ms)
            bufferAfterSeek(p)
            // Trusted, then checked — see verifyNativeSeek.
            if (track != null) verifyNativeSeek(p, track, ms)
            reports.seeked(ms)
            return
        }
        serverSeek(p, track, ms)
        reports.seeked(ms)
    }

    fun next() {
        remote?.let { out ->
            // As on the phone: only a track that is playing waits, and only
            // where there is a next one to go to.
            if (_isPlaying.value && _index.value + 1 in _queue.value.indices) markStartingElsewhere()
            out.next()
            return
        }
        settleRestartCopy()
        if (parked) {
            stepWhileWaiting(+1)
            return
        }
        pickedIndex = wrapped(_index.value + 1)
        // Only if it is meant to be making sound: next on a paused player
        // starts nothing, and would be left "buffering".
        if (player?.playWhenReady == true) markStarting(pickedIndex)
        player?.skipToNext()
    }

    /** Restart the current track if past the intro, otherwise go to the previous one. */
    fun previous() {
        remote?.let { out ->
            // Back to the start, or to the track before where there is one: the
            // rule every output's previous keeps.
            val restarts = _positionMs.value > PREVIOUS_RESTARTS_AFTER_MS
            val moves = restarts || _index.value - 1 in _queue.value.indices
            if (_isPlaying.value && moves) markStartingElsewhere()
            out.previous()
            // Back to the start is a seek to it, and said as one, and the song
            // starting over.
            if (restarts) {
                reports.seeked(0L)
                reports.songStarted()
            }
            return
        }
        settleRestartCopy()
        if (parked) {
            stepWhileWaiting(if (_positionMs.value > PREVIOUS_RESTARTS_AFTER_MS) 0 else -1)
            return
        }
        pickedIndex = if (_positionMs.value > PREVIOUS_RESTARTS_AFTER_MS) _index.value else wrapped(_index.value - 1)
        val p = player ?: return
        if (_positionMs.value > PREVIOUS_RESTARTS_AFTER_MS) {
            reports.songStarted()
            // The controller's seek, not the player's: a server-seeked stream
            // begins mid-track, so the player's 0 is the offset, and every press
            // would restart it without ever reaching the previous track.
            seekTo(0)
        } else {
            p.skipToPrevious()
        }
    }

    /** Jump to a queue position: a tap on a queue row. */
    fun jumpTo(index: Int) {
        if (index !in _queue.value.indices) return
        remote?.let { out ->
            // A row tapped plays, wherever the sound is.
            markStartingElsewhere()
            out.jumpTo(index)
            return
        }
        val p = player ?: return
        settleRestartCopy()
        if (parked) {
            // A parked player is in its error state, where a seek makes no
            // sound: prepare it on the row asked for.
            goWhileParked(index)
            return
        }
        pickedIndex = index
        p.seekToDefaultPosition(index)
        _index.value = index
        // From its start, the playing row too.
        reports.songStarted()
        if (cannotStreamNow(index)) {
            blockedAtCurrent()
            return
        }
        markStarting(index)
        p.play()
    }

    /**
     * Stop playback and empty the queue.
     *
     * A speaker the phone offers stays the output, quiet, and the next queue
     * plays on it: switching sources is a choice of music, not of where it
     * plays. A source's own players go with their source, and so does any
     * output when [keepOutput] is false, as what follows has no server to
     * give a speaker an address from.
     */
    fun stop(keepOutput: Boolean = true) {
        cancelPendingStart()
        // A speaker the link took away belonged to the queue going now.
        forgetLost()
        // The open session ends where playback got to, under the id it was
        // announced as.
        val stopping = _queue.value.getOrNull(_index.value)
        reports.close(stopping?.id, _positionMs.value, _durationMs.value)
        // The output is told to stop, and the queue it was given goes with this one.
        val kept = _active.value?.takeIf { keepOutput && it.family in Outputs.phone }
        if (kept != null) {
            kept.output.stopPlayback()
            _isPlaying.value = false
        } else {
            dropOutput(Disconnect.Stopped)
        }
        // The queue goes now, on the caller's thread: plain state writes need no
        // looper. A source switch calls this from Default and swaps the client
        // and the database straight after, and a queue still there was
        // re-resolved against the new server, one source's ids sent to another.
        _queue.value = emptyList()
        preShuffleOrder = null
        _queueName.value = null
        _index.value = -1
        _waitingFor.value = null
        pickedIndex = -1
        queuedSources = emptyList()
        streamOffsetMs = 0
        offsetItemTrackId = null
        _positionMs.value = 0
        _durationMs.value = 0
        reports.newListening()
        // The player's own calls on Main, immediate so a caller already there
        // stays synchronous. Not dropped when the caller is elsewhere: the
        // service would play the old source's queue under an empty screen.
        scope.launch(Dispatchers.Main.immediate) {
            val cur = player ?: return@launch
            settleRestartCopy()
            // A speaker kept for the next queue: the player is still its
            // stand-in, which empties itself as the queue goes.
            if (_active.value != null && standIn.active) return@launch
            standIn.stop()
            // The SDK's stop() clears playWhenReady too, so a new queue waits
            // for its stream decision before making a sound.
            cur.stop()
            cur.setMediaQueue(emptyList())
        }
    }

    // --- Starting, parking and skipping --------------------------------------

    /** Sound is coming out: the start is over, whatever asked for it. */
    private fun heardSound() {
        hasPlayed = true
        // The pick has been honoured: from here on, whatever happens to this
        // track happened to playback, not to a request — see blockedAtCurrent.
        pickedIndex = -1
        lastSkipTarget = -1
        _buffering.value = false
    }

    /**
     * A start whose "playing" is never reported would leave "Buffering…" and a
     * play button over music that plainly plays. A position that moves is
     * sound coming out, whatever the flag says: a second of the track in a
     * second of time, so a seek's jump doesn't count. Fed by the player and by
     * an output's reports.
     */
    private fun noteProgress(positionMs: Long) {
        if (!_buffering.value) {
            bufferingProbe = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        val seen = bufferingProbe
        if (seen == null || positionMs < seen.first) {
            bufferingProbe = positionMs to now
            return
        }
        if (positionMs - seen.first >= PROGRESS_PROOF_MS && now - seen.second >= PROGRESS_PROOF_MS) {
            bufferingProbe = null
            _isPlaying.value = true
            heardSound()
        }
    }

    /** Mark a start at [index]: buffering only if that row is a stream. */
    private fun markStarting(index: Int) {
        _buffering.value = queuedSources.getOrNull(index) is LightAudioSource.UrlSource
        if (_buffering.value) bufferingSinceMs = SystemClock.elapsedRealtime()
    }

    /**
     * The output with the sound was asked to start, seek or change track: the
     * same "Buffering…" as a start on the phone, until the far end's clock
     * moves ([noteProgress], fed by its reports, measured from its first
     * reading after this), or a pause, a failure or the limit ends it. Every
     * far end fetches a stream, so there is no file to tell apart.
     */
    private fun markStartingElsewhere() {
        bufferingProbe = null
        bufferingSinceMs = SystemClock.elapsedRealtime()
        _buffering.value = true
    }

    /**
     * A seek that has to fetch says "Buffering…" too, until sound comes back
     * (see [heardSound]). Only once the wait outlasts [SEEK_BUFFERING_GRACE_MS],
     * so a seek into what is already buffered doesn't flash it, and never for
     * a file, which has nothing to fetch.
     */
    private fun bufferAfterSeek(p: LightAudioPlayer) {
        if (!p.playWhenReady) return
        if (queuedSources.getOrNull(_index.value) !is LightAudioSource.UrlSource) return
        val generation = ++seekBufferingGeneration
        scope.launch(Dispatchers.Main.immediate) {
            delay(SEEK_BUFFERING_GRACE_MS)
            if (generation != seekBufferingGeneration || player !== p || isCasting) return@launch
            if (p.isPlaying.value || !p.playWhenReady) return@launch
            bufferingSinceMs = SystemClock.elapsedRealtime()
            _buffering.value = true
        }
    }

    /**
     * Whatever start was pending is off: a pause, a stop, a cast taking over.
     * A play deferred behind the server's decision mustn't land after one of
     * those (under a cast it would be a second song from the phone's own
     * speaker), and nothing should go on waiting for it.
     */
    private fun cancelPendingStart() {
        decisionGeneration++
        _buffering.value = false
    }

    /**
     * Run [start] once the server has agreed to serve the current track: Plex
     * answers a stream started with no decision behind it with a 400. Only
     * for a stream, as a file has nobody to ask. Bounded, and it starts anyway
     * on a timeout or a refusal: a server that won't decide may still serve,
     * and a silent player is the worse failure.
     */
    private fun withStreamDecision(start: () -> Unit) {
        // From the queue itself: currentTrack is derived, and is still the last
        // track just after a new queue is set.
        val track = _queue.value.getOrNull(_index.value)
        val source = queuedSources.getOrNull(_index.value)
        val client = serverClient.value
        val generation = ++decisionGeneration
        if (track == null || client == null || source !is LightAudioSource.UrlSource) {
            start()
            return
        }
        awaitedDecision = generation
        // Under the id the queued stream carries. Where the door decides each
        // stream itself (see playerUrl) this is asked twice, harmlessly.
        val format = sources.effectiveFormat()
        val sid = reports.streamSessionIdFor(track.id, sources.decidesAtDoor(client, track, format))
        scope.launch {
            withTimeoutOrNull(STREAM_DECISION_TIMEOUT_MS) {
                client.prepareStream(track.id, format, sid)
            }
            withContext(Dispatchers.Main.immediate) {
                if (awaitedDecision == generation) awaitedDecision = 0
                // Overtaken while the server decided (a pause, a stop, another
                // track, the handle released), which outranks this press.
                if (generation == decisionGeneration) start()
            }
        }
    }

    /**
     * Start the track at [index], the current one: the server's decision
     * first, then the sound, on whichever handle holds the service when the
     * answer comes — see [withStreamDecision].
     */
    private fun startWhenDecided(index: Int) {
        markStarting(index)
        withStreamDecision { player?.play() }
    }

    /** Play [p] from [index] if [play], marked as a start, otherwise leave it paused. Main only. */
    private fun playOrCue(p: LightAudioPlayer, index: Int, play: Boolean) {
        if (play) {
            markStarting(index)
            p.play()
        } else {
            p.pause()
        }
    }

    /**
     * The player failed on the current item. Main only. The player says what
     * kind of failure it was; what the server said, if anything, only the
     * door saw — see StreamProxy.lastOutcome.
     */
    private fun playerFailed(p: LightAudioPlayer, error: LightAudioError) {
        // The stand-in's silence, not the music: nothing to park or blame.
        if (standIn.active) {
            Log.w("AmpStandIn", "the silence failed: ${error.diagnostic}")
            return
        }
        _buffering.value = false
        settleRestartCopy()
        val source = queuedSources.getOrNull(_index.value)
        val fromFile = source is LightAudioSource.FileSource
        val outcome = _queue.value.getOrNull(_index.value)?.let { App.streamProxy.lastOutcome(it.id) }
        val status = (outcome as? StreamProxy.Outcome.Answered)?.status
        Log.i(
            "AmpNet",
            "player error ${error.diagnostic}${status?.let { " (HTTP $it)" }.orEmpty()} " +
                "at idx=${_index.value} (${if (fromFile) "file" else "stream"})",
        )
        val failure = playFailure(
            fromFile = fromFile,
            networkError = error.kind == LightAudioErrorKind.Source,
            badHttpStatus = error.diagnostic == BAD_HTTP_STATUS,
            status = status,
            doorRefused = outcome == StreamProxy.Outcome.Refused ||
                !App.streamProxy.isOn ||
                (source as? LightAudioSource.UrlSource)?.url == StreamProxy.UNAVAILABLE_URL,
        )
        when (failure) {
            // Neither retried nor blamed on the link or the server. It says what
            // it is, and playback moves on if it was moving.
            PlayFailure.FILE -> blockedAtCurrent(because = CANT_PLAY_FILE)
            PlayFailure.FORMAT -> blockedAtCurrent(because = CANT_PLAY_FORMAT)
            // The server isn't unreachable, it is unasked, so there is nothing
            // to fall back to or latch.
            PlayFailure.UNASKED -> blockedAtCurrent()
            // Rebuilt on the server's other address for the same file. Only a
            // failure there as well comes down to the fallback.
            PlayFailure.SERVER_ERROR -> if (!playOriginalElsewhere(p)) fallBackOffline(serverAnswered = true)
            // A bad HTTP status proves the server is there: it isn't taken as
            // unreachable, which would put the whole library on downloads only
            // over one bad URL.
            PlayFailure.BAD_STATUS -> fallBackOffline(serverAnswered = true)
            PlayFailure.NO_ANSWER -> fallBackOffline(serverAnswered = false)
        }
    }

    /**
     * The current track can't be had. Main only.
     *
     * If the user asked for this track, stay on it and say why: they chose
     * it. If playback arrived here by itself and was meant to be making sound,
     * carry on with the next track that can play and leave this one in the
     * queue, to play once the rules allow. With nothing playable left, or a
     * player that was paused, park: a failure is never a reason to start
     * making noise.
     *
     * Decided from [App.rulesNow], not the flow, which is a few hops behind
     * the refusal.
     */
    private fun blockedAtCurrent(because: String? = null) {
        _buffering.value = false
        val p = player ?: return
        // The output plays, not this player: its failures move nothing.
        if (isCasting) return
        val tracks = _queue.value
        val at = _index.value
        val rules = App.rulesNow()
        // Rules that allow streaming and a stream that still failed mean the
        // server isn't delivering: never "Wi-Fi", which would send the user
        // looking for the wrong thing.
        val reason = because ?: rules.waitingFor
            ?: if (App.streamProxy.isOn) LinkRules.WAITING_FOR_SERVER else STREAM_UNAVAILABLE
        val wanted = p.playWhenReady
        val next = when {
            !wanted || at == pickedIndex || at == lastSkipTarget -> null
            else -> nextPlayableAfter(at, rules)
        }
        Log.i(
            "AmpNet",
            "blocked at idx=$at picked=$pickedIndex wanted=$wanted → ${next ?: "park ($reason)"}",
        )
        if (next == null) {
            _waitingFor.value = reason
            _isPlaying.value = false
            // The server was told this song started (play() says so before
            // any sound), and nothing will start it now: it is told it
            // stopped, or its dashboard goes on showing it. Whatever session
            // is open, as announced: after a tap onto this song that can still
            // be the last song's.
            reports.close()
            return
        }
        _waitingFor.value = null
        lastSkipTarget = next
        rebuildQueueAt(p, tracks, next, 0L)
        markStarting(next)
        p.play()
    }

    /** The next queue position after [from] whose track can play; wraps only under repeat-queue. */
    private fun nextPlayableAfter(from: Int, rules: LinkRules): Int? {
        val tracks = _queue.value
        val ahead = (from + 1..tracks.lastIndex).firstOrNull { sources.isPlayable(tracks[it], rules) }
        if (ahead != null || _repeatMode.value != RepeatMode.QUEUE) return ahead
        return (0 until from).firstOrNull { sources.isPlayable(tracks[it], rules) }
    }

    /**
     * Prepare the parked player again where it stood, and play if asked. Main
     * only. The rules are asked first, so a press while they still say no
     * costs no rebuild and no error, and the screen goes on saying what it
     * said.
     */
    private fun resumeAfterWait(play: Boolean) {
        val p = player ?: return
        val tracks = _queue.value
        val index = _index.value
        if (index !in tracks.indices) {
            _waitingFor.value = null
            return
        }
        val track = tracks[index]
        val rules = App.rulesNow()
        if (!sources.isPlayable(track, rules) || (!sources.hasLocalCopy(track) && !NetworkGate.isOpen())) {
            // Still not allowed: say why as of now, and leave the player alone.
            _waitingFor.value = rules.waitingFor ?: _waitingFor.value
            return
        }
        // On the phone, but the stream would be preferred and can't be had:
        // play the copy, or source() hands out the URL again and the track
        // parks with its file on disk.
        if (!rules.mayStream && sources.hasDownload(track.id)) sources.preferDownload(track.id)
        _waitingFor.value = null
        // Asked for, by a press or by the rules opening on the track the user
        // waited on: refused after all, it parks again rather than skipping.
        pickedIndex = index
        rebuildQueueAt(p, tracks, index, _positionMs.value)
        playOrCue(p, index, play)
    }

    /**
     * The rules opened on a parked track: prepare it where it stood, paused.
     * Cued, not started, as Wi-Fi coming back is not a press of play; one
     * press then plays, where a parked player answers a press with silence.
     */
    private suspend fun cueIfParked() {
        if (!parked || isCasting || parkedOnTheTrackItself) return
        withContext(Dispatchers.Main.immediate) { resumeAfterWait(play = false) }
    }

    /**
     * Next or previous while parked: move the marker and try the neighbour as
     * play would, so a download plays and a stream parks again on the new row.
     * Repeat's wrap is honoured; off the end, nothing.
     */
    private fun stepWhileWaiting(delta: Int) {
        val target = if (delta == 0) _index.value else wrapped(_index.value + delta)
        if (target !in _queue.value.indices) return
        goWhileParked(target)
    }

    /** [index] as the player will take it: wrapped under repeat-queue, otherwise as given. */
    private fun wrapped(index: Int): Int =
        wrappedIndex(index, _queue.value.size, repeatQueue = _repeatMode.value == RepeatMode.QUEUE)

    /** Move to [target] by hand while parked: a file plays, a stream parks there instead. */
    private fun goWhileParked(target: Int) {
        pickedIndex = target
        _index.value = target
        _positionMs.value = 0L
        resumeAfterWait(play = true)
    }

    /** The row at [index] is a stream, and the rules, read now, or a closed door forbid streaming. */
    private fun cannotStreamNow(index: Int): Boolean =
        queuedSources.getOrNull(index) is LightAudioSource.UrlSource &&
            (!NetworkGate.isOpen() || !App.rulesNow().mayStream || !App.streamProxy.isOn)

    /**
     * The server failed the original stream of the song the player is on:
     * move the song to the other address its server has for the same file,
     * for good, and carry on from where it stood. Main only. False leaves the
     * failure to the usual path.
     *
     * Plex answers its direct-play address with a 500 for files whose
     * analysis never finished, and serves the identical file as a download,
     * with the same length and ranges, so seeking and the door work as
     * before. Once per song: one that fails there too (a shared account kept
     * from downloading) goes the usual way, and the format never changes here.
     *
     * The index is the player's, not [_index]: a song that fails as the
     * player moves onto it fails before the index collector has caught up.
     */
    private fun playOriginalElsewhere(p: LightAudioPlayer): Boolean {
        if (isCasting) return false
        settleRestartCopy()
        val index = p.currentMediaItemIndex.value
        val track = _queue.value.getOrNull(index) ?: return false
        if (queuedSources.getOrNull(index) !is LightAudioSource.UrlSource) return false
        if (!sources.moveOriginal(track)) return false
        Log.i(
            "AmpNet",
            "server failed the original at idx=$index — playing it from its other address from now on",
        )
        // Arrived on it by itself and not yet heard of here: landed on as the
        // index collector would, so it is announced and timed from its start.
        if (index != _index.value) landedOn(p, index, startMs = 0L)
        val wanted = p.playWhenReady
        rebuildQueueAt(p, _queue.value, index, _positionMs.value)
        if (wanted) startWhenDecided(index) else p.pause()
        return true
    }

    /**
     * A stream died: carry on from the downloaded copy, at the same second, so
     * that walking out of Wi-Fi sounds like nothing happened. With no copy,
     * the server is asked whether it is still there before the track is given
     * up on.
     *
     * [TrackSources.forceOffline] latches when the server didn't answer, as
     * the next track would otherwise resolve to a URL and fail the same way.
     */
    private fun fallBackOffline(serverAnswered: Boolean) {
        val p = player ?: return
        val track = _queue.value.getOrNull(_index.value) ?: return
        val resumeAt = _positionMs.value
        if (!sources.hasDownload(track.id)) {
            // Nothing local to fall back to. A bad status is the server's own
            // answer about this track, and stands. Silence is what a Wi-Fi to
            // cellular handover sounds like from here, so the server is asked,
            // once: answering, with rules that allow streaming, the track picks
            // up where it stopped; otherwise it waits like any other.
            if (isCasting) return
            if (serverAnswered) {
                // Said, and playback moves on if it was moving, rather than a
                // player left in its error state under a screen that looks ready.
                blockedAtCurrent(because = CANT_PLAY_THIS)
                return
            }
            if (!sources.firstRetryOf(track.id)) {
                // Picked up once and died again: it waits, and playback moves
                // on if it can.
                blockedAtCurrent()
                return
            }
            scope.launch {
                // Bounded: the ping may queue behind one already out on the
                // dead link while the player sits silent. Three answers: yes,
                // no, and nothing yet (out of time, or not allowed to ask),
                // which on a slow link is the common one and isn't a no.
                val verdict = withTimeoutOrNull(VERIFY_WAIT_MS) { App.reachability.verify() }
                val there = verdict != false
                // One ping's "no" is a suspicion: keep asking in the background,
                // so a change reaches this player through the rules, no press
                // needed.
                if (verdict != true) App.reachability.check()
                withContext(Dispatchers.Main.immediate) {
                    // Moved on, paused, cast or recovered meanwhile: any of those
                    // outranks this.
                    if (player !== p) return@withContext
                    if (_queue.value.getOrNull(_index.value)?.id != track.id) return@withContext
                    if (isCasting || p.isPlaying.value) return@withContext
                    if (there && App.rulesNow().mayStream) {
                        val at = _positionMs.value
                        Log.i("AmpNet", "stream died, server answers — resuming at $at")
                        val wanted = p.playWhenReady
                        rebuildQueueAt(p, _queue.value, _index.value, at)
                        playOrCue(p, _index.value, wanted)
                    } else {
                        blockedAtCurrent()
                    }
                }
            }
            return
        }
        // Once per track, so a file that won't decode can't spin here.
        if (!sources.firstRetryOf(track.id)) return
        sources.preferDownload(track.id)
        // Only a server that never answered pins everything to local files,
        // and only until it is asked and answers.
        if (!serverAnswered) {
            sources.forceOffline = true
            scope.launch {
                if (withTimeoutOrNull(VERIFY_WAIT_MS) { App.reachability.verify() } == true) {
                    sources.forceOffline = false
                    reresolveQueue()
                } else {
                    // Keep asking; the rules collector lifts the pin when the
                    // server answers.
                    App.reachability.check()
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            // Read before the rebuild: a stream can die while paused (the player
            // buffers regardless), which is no reason to start the copy playing.
            val wanted = p.playWhenReady
            // A rebuild like any other, so what the controller holds for the row
            // says file, as what plays now is.
            rebuildQueueAt(p, _queue.value, _index.value, resumeAt)
            playOrCue(p, _index.value, wanted)
        }
    }

    /**
     * The queue ran out: back to its first track, at its start, paused. The
     * one way a queue ends, on the phone and wherever else the sound is (see
     * OutputHost.queueFinished): left on the last song at its end, the next
     * play replayed that song rather than the record.
     *
     * The listening is over, so its session is too, said before anything
     * moves: the first song, cued in its place, is a stream the server may be
     * asked for before it hears this.
     */
    private fun queueFinished() {
        reports.close()
        _isPlaying.value = false
        _buffering.value = false
        _index.value = 0
        _positionMs.value = 0L
        seedDuration(_queue.value.firstOrNull())
        hasPlayed = false
    }

    /**
     * A queue that ran out on this phone goes back to its first track — see
     * [queueFinished]. Under repeat the player loops and never ends.
     *
     * The SDK doesn't surface STATE_ENDED, so this reads the evidence Media3
     * would: stopped on the last item at its end, with little enough slack
     * that a pause a second from the end isn't taken for it. And [hasPlayed]:
     * a restore can cue the last track at its end, paused, with nothing
     * played, and rewinding that would lose the track the app was reopened to
     * go on with.
     */
    private fun rewindIfQueueFinished(p: LightAudioPlayer) {
        if (!playerSpeaks) return
        if (!hasPlayed) return
        if (_repeatMode.value != RepeatMode.OFF) return
        val last = _queue.value.lastIndex
        if (last < 0 || _index.value != last) return
        // The player's duration when it has one. A chunked transcode may never
        // report any, so then the library's, in the track's own timeline as the
        // readouts are.
        val reported = p.durationMs.value
        val endReached = if (reported > 0L) {
            p.positionMs.value >= reported - QUEUE_END_SLACK_MS
        } else {
            val known = _durationMs.value
            known > 0L && _positionMs.value >= known - QUEUE_END_SLACK_MS
        }
        if (!endReached) return
        // The state is set here, not in the Main block below, so a second
        // emission can't queue a second rewind, and the session closes before
        // the player moves.
        queueFinished()
        scope.launch(Dispatchers.Main.immediate) {
            // A handle that replaced this one steers the same service.
            val cur = player ?: return@launch
            // Paused first: a seek out of the ended state with playWhenReady
            // still set starts the queue over instead of waiting there.
            cur.pause()
            cur.seekToDefaultPosition(0)
        }
    }

    /** The library's length for [track], shown at once: a chunked stream may never report one. */
    private fun seedDuration(track: Track?) {
        track?.durationMs?.takeIf { it > 0L }?.let { _durationMs.value = it }
    }

    // --- Seeking -------------------------------------------------------------

    /** Whether the player can't be trusted to seek within this track's stream, so the server must. */
    private fun needsServerSeek(track: Track, askDoor: Boolean = true): Boolean {
        if (sources.source(track) is LightAudioSource.FileSource) return false
        if (track.id in seekNeedsReload) return true
        // A queue being rebuilt is about to fetch afresh: what the door saw was
        // the stream it held before.
        if (!askDoor) return sources.effectiveFormat() != StreamFormat.RAW
        // Ask the door what arrived. Asked for mp3 and given it with a length,
        // the server sent the file untouched, with byte ranges, and ignores
        // timeOffset as it isn't transcoding. "Not RAW, so a transcode" would
        // pick server-seeking exactly where only native seeking works, and
        // fail silently: the audio restarts and the clock claims the seek
        // landed.
        val answered = App.streamProxy.lastOutcome(track.id) as? StreamProxy.Outcome.Answered
        if (answered != null && answered.status in 200..299 && answered.lengthKnown) return false
        // Nothing fetched yet to ask about: go by the request.
        return sources.effectiveFormat() != StreamFormat.RAW
    }

    /**
     * Check where a native seek landed, and seek through the server if it
     * missed. The player first answers with the position it was told; a
     * moment later it reflects the stream, and one that ignored the seek is
     * back near the beginning.
     */
    private fun verifyNativeSeek(
        p: LightAudioPlayer,
        track: Track,
        target: Long,
        awaitStream: Boolean = false,
    ) {
        val generation = ++seekGeneration
        scope.launch {
            // A queue just built answers with the asked position until its
            // stream declares itself, so the check would pass a seek about to be
            // dropped: wait for that, within reason.
            if (awaitStream) {
                withTimeoutOrNull(REBUILD_VERIFY_TIMEOUT_MS) { p.durationMs.first { it > 0L } }
            }
            delay(SEEK_VERIFY_MS)
            if (generation != seekGeneration) return@launch
            if (player !== p) return@launch
            if (_active.value != null) return@launch
            if (_queue.value.getOrNull(_index.value)?.id != track.id) return@launch
            val landed = p.positionMs.value + streamOffsetMs
            if (abs(landed - target) <= SEEK_TOLERANCE_MS) return@launch
            seekNeedsReload += track.id
            serverSeek(p, track, target)
        }
    }

    /**
     * Seek by swapping the playing item for a stream that starts at [ms] —
     * see [seekTo]. Only that item is replaced, so what plays next is
     * undisturbed.
     */
    private fun serverSeek(p: LightAudioPlayer, track: Track, ms: Long) {
        if (serverClient.value == null) return
        val target = ms.coerceAtLeast(0L)
        // No estimated length, as for every stream (see TrackSources.playerUrl):
        // here it would also have ExoPlayer take the transcode as
        // range-seekable and probe its end, which Navidrome answers with 416.
        val url = sources.playerUrl(track, timeOffsetSeconds = (target / 1000).toInt())
        streamOffsetMs = target
        offsetItemTrackId = track.id.takeIf { target > 0L }
        _positionMs.value = target
        val item = track.toAudioItem(LightAudioSource.UrlSource(url))
        scope.launch(Dispatchers.Main.immediate) {
            if (player !== p) return@launch
            // The slot is about to be addressed by position — see restartCopyAt.
            settleRestartCopy()
            // Intent, not the momentary state: isPlaying is false during a
            // rebuffer, and a seek then left the new stream paused.
            val wasPlaying = p.playWhenReady
            val at = _index.value
            if (at < 0) return@launch
            // Repeat-one is lifted for the swap. With the playing item replaced,
            // Media3 looks for what plays after it, under repeat-one that same
            // item, now gone, and jumps to the first track of the queue.
            val repeatOne = p.repeatMode == Player.REPEAT_MODE_ONE
            if (repeatOne) p.repeatMode = Player.REPEAT_MODE_OFF
            // Replaced in place, not removed and re-added: that moves the
            // player's index away and back, which the index collector reads as
            // a new track, clearing streamOffsetMs.
            p.replaceMediaItem(at, item)
            // Played from its own beginning, which is already the target: Media3
            // keeps the old position for an item that looks the same, which
            // would apply the offset twice. The index is the one the player is
            // on, so nothing reads it as a track change.
            p.seekToDefaultPosition(at)
            // A remainder under repeat-one has the whole song queued after it
            // — see restartCopyAt.
            if (repeatOne && target > 0L) {
                armRestartCopy(p, at, track)
            } else if (repeatOne) {
                p.repeatMode = Player.REPEAT_MODE_ONE
            }
            if (wasPlaying) p.play()
            bufferAfterSeek(p)
        }
    }

    /** Main only. Queue the whole song after the remainder at [at], with the player's repeat off — see [restartCopyAt]. */
    private fun armRestartCopy(p: LightAudioPlayer, at: Int, track: Track) {
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.addMediaItem(at + 1, sources.item(track))
        restartCopyAt = at + 1
    }

    /**
     * Main only. The player's queue back in step with the screen's: the copy
     * goes, unless the remainder has already ended into it, and then the
     * remainder goes and the copy is the song from here on.
     */
    private fun settleRestartCopy() {
        val copy = restartCopyAt
        if (copy < 0) return
        val p = player
        if (p == null) {
            restartCopyAt = -1
            return
        }
        if (p.currentMediaItemIndex.value == copy) {
            finishRestart(p, copy)
            return
        }
        restartCopyAt = -1
        p.removeMediaItem(copy)
        if (_repeatMode.value == RepeatMode.TRACK) p.repeatMode = Player.REPEAT_MODE_ONE
    }

    /** Main only. The remainder ended into the copy: the copy takes its slot, and repeat-one is the player's again. */
    private fun finishRestart(p: LightAudioPlayer, copy: Int) {
        // The offset first: a collector that finds the copy no longer marked
        // must already find no offset to apply to it.
        streamOffsetMs = 0L
        restartCopyAt = -1
        offsetItemTrackId = null
        _positionMs.value = 0L
        seedDuration(_queue.value.getOrNull(_index.value))
        p.removeMediaItem(copy - 1)
        if (_repeatMode.value == RepeatMode.TRACK) p.repeatMode = Player.REPEAT_MODE_ONE
        bufferAfterSeek(p)
    }

    /**
     * Main only. Repeat-one over a remainder with no copy behind it (after an
     * edit put the queue back in step, or repeat-one switched on after the
     * seek) gets one — see [restartCopyAt].
     */
    private fun armIfRemainder() {
        if (restartCopyAt >= 0 || !playerSpeaks || parked) return
        if (_repeatMode.value != RepeatMode.TRACK) return
        val p = player ?: return
        val at = _index.value
        val track = _queue.value.getOrNull(at) ?: return
        if (streamOffsetMs <= 0L || offsetItemTrackId != track.id) return
        if (p.currentMediaItemIndex.value != at) return
        armRestartCopy(p, at, track)
    }

    /** [armIfRemainder] once what is running on Main has finished, so a run of edits doesn't arm in the middle of itself. */
    private fun rearmSoon() {
        scope.launch(Dispatchers.Main) { armIfRemainder() }
    }

    /** [p] is on the restart copy, not yet settled: the song from its start, the remainder's offset over. */
    private fun onRestartCopy(p: LightAudioPlayer): Boolean =
        restartCopyAt >= 0 && p.currentMediaItemIndex.value == restartCopyAt

    // --- Building the player's queue -----------------------------------------

    /** Make [tracks] the queue and start it at [startIndex], here or wherever the sound is. */
    private fun setQueue(tracks: List<Track>, startIndex: Int) {
        val p = player ?: return
        settleRestartCopy()
        _queue.value = tracks
        // The order shuffle would go back to is the last queue's, not this one's.
        preShuffleOrder = null
        // Into the player unless it holds the stand-in: the queue goes back in
        // with the sound — see takeOverHere. Each source is resolved once and
        // handed on, not again per item, on Main.
        val resolved = tracks.map(sources::source)
        queuedSources = resolved
        queuePlayer?.setMediaQueue(itemsFrom(tracks, resolved), startIndex)
        _index.value = startIndex
        reports.songStarted()
        // A new queue plays from the start of whatever it opens on. A leftover
        // offset would be added to a clock starting at 0:00, and the index
        // collector won't clear it when the new queue opens on the same index.
        streamOffsetMs = 0
        offsetItemTrackId = null
        sources.forgetStreamFailures()
        // A new queue is prepared afresh: a park on the old one is over, and
        // where it starts is where the user asked it to.
        _waitingFor.value = null
        pickedIndex = startIndex
        seedDuration(tracks.getOrNull(startIndex))
        // A new queue goes where the sound already goes, from the top. Without
        // this it started here while a Companion player carried on with the
        // queue it still had: two songs at once.
        remote?.let { out ->
            queuePlayer?.pause()
            markStartingElsewhere()
            out.newQueue()
            return
        }
        // Known before asking: the player would only find out at the door,
        // whose refusal it retries for a few seconds first.
        if (cannotStreamNow(startIndex)) {
            blockedAtCurrent()
            return
        }
        startWhenDecided(startIndex)
    }

    /**
     * Rebuild the player's queue on [index], cued at [positionMs]. Main only.
     *
     * The position goes in with the queue, never as a seek after it: a seek
     * into an item the player hasn't opened clamps to its still unknown
     * duration, landing on 0. And a start position only lands on a stream the
     * player can seek; a chunked transcode opens from 0:00 regardless. Those
     * streams are built to start at the position, through the server as
     * [seekTo] does, with [streamOffsetMs] shifting the readouts. A stream the
     * player can seek takes the cheap route, checked as a native seek is.
     */
    private fun rebuildQueueAt(p: LightAudioPlayer, tracks: List<Track>, index: Int, positionMs: Long) {
        settleRestartCopy()
        val track = tracks[index]
        val at = positionMs.coerceAtLeast(0L)
        // Each source resolved once, for the memo and the items alike.
        val resolved = tracks.map(sources::source)
        queuedSources = resolved
        // Set before the player moves: the index collector would read the move
        // as a new track and clear the offset set below. That is also why the
        // duration is seeded here.
        _index.value = index
        _positionMs.value = at
        seedDuration(track)
        val client = serverClient.value
        if (at > 1_000L && client != null && needsServerSeek(track, askDoor = false)) {
            // No estimated length — see serverSeek.
            val url = sources.playerUrl(track, timeOffsetSeconds = (at / 1000).toInt())
            streamOffsetMs = at
            offsetItemTrackId = track.id
            p.setMediaQueue(
                tracks.mapIndexed { i, it ->
                    it.toAudioItem(if (i == index) LightAudioSource.UrlSource(url) else resolved[i])
                },
                index,
            )
        } else {
            streamOffsetMs = 0
            offsetItemTrackId = null
            p.setMediaQueueAt(itemsFrom(tracks, resolved), index, at)
            if (at > 0L) verifyNativeSeek(p, track, at, awaitStream = true)
        }
        rearmSoon()
    }

    /**
     * Rebuild the queue's sources when "file or stream?" may have changed.
     * The answer ([TrackSources.source]) hangs on things that move while a queue plays:
     * Wi-Fi going, a download finishing, the data mode. A queue built on Wi-Fi
     * went on asking the network for tracks by then on disk, which offline
     * means a socket timeout for every one.
     *
     * The playing item is left alone: replacing it would restart the track
     * under the listener. Everything around it is corrected.
     */
    private fun reresolveQueue() {
        val p = player ?: return
        if (!playerSpeaks) return
        // Not until the handle has connected: before then there is no knowing
        // whether the service kept this queue or came back empty, and on an
        // empty one these edits would become the queue. attachHandle asks
        // again once connected.
        if (p.availability.value != LightAudioPlayerAvailability.Ready) return
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        val current = _index.value.coerceIn(0, tracks.lastIndex)
        // Nothing to do unless an answer moved: this runs on every finished
        // download, and most change nothing.
        val rebuilt = tracks.map(sources::source)
        val held = queuedSources
        if (rebuilt == held) return
        queuedSources = rebuilt
        scope.launch(Dispatchers.Main.immediate) {
            if (player !== p) {
                // The handle changed on the way (a handover, a back-out, the SDK
                // dropping one that never connected), so none of this reached
                // the service. The memo goes back to what the service still
                // holds, unless something wrote it since, and the ask is
                // repeated: a connected handle gets the edits now, one still
                // connecting in attachHandle's pass. Left as it was, that pass
                // found a match and sent nothing, and a track downloaded
                // meanwhile stayed a stream.
                if (queuedSources === rebuilt) queuedSources = held
                reresolveQueue()
                return@launch
            }
            settleRestartCopy()
            if (current > 0) {
                p.replaceRange(0, current, itemsFrom(tracks.subList(0, current), rebuilt.subList(0, current)))
            }
            if (current + 1 <= tracks.lastIndex) {
                p.replaceRange(
                    current + 1,
                    tracks.size,
                    itemsFrom(tracks.subList(current + 1, tracks.size), rebuilt.subList(current + 1, tracks.size)),
                )
            }
            rearmSoon()
        }
    }

    // --- Editing the queue ---------------------------------------------------

    fun addToQueue(tracks: List<Track>) {
        player ?: return
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        settleRestartCopy()
        _queue.value = _queue.value + tracks
        queuePlayer?.addItems(tracks.map(sources::item))
        remote?.queueEdited(QueueEdit.Appended(tracks.map { it.id }))
        rearmSoon()
    }

    /**
     * Put these tracks straight after the one playing. A track already in the
     * queue is moved rather than copied: "play next" on something queued for
     * later means bring it forward, not play it twice.
     */
    fun playNext(tracks: List<Track>) {
        pickedIndex = -1 // indices are about to move under it
        player ?: return
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        settleRestartCopy()
        // Each goes after the one before it, so an album played next keeps its
        // order.
        var slot = _index.value + 1
        val added = mutableListOf<String>()
        for (track in tracks) {
            val from = _queue.value.indexOfFirst { it.id == track.id }
            if (from < 0) {
                val at = slot.coerceIn(0, _queue.value.size)
                _queue.value = _queue.value.toMutableList().apply { add(at, track) }
                queuePlayer?.addMediaItem(at, sources.item(track))
                added += track.id
                slot = at + 1
                continue
            }
            if (from == _index.value) continue
            // Destinations are read after the removal, so a track pulled from in
            // front of the slot lands one place earlier than one from behind it.
            val to = if (from < slot) slot - 1 else slot
            moveInQueue(from, to)
            slot = to + 1
        }
        // One edit for the lot, in order, so an output keeps the album's order too.
        if (added.isNotEmpty()) remote?.queueEdited(QueueEdit.PlayNext(added))
        rearmSoon()
    }

    /**
     * Keep what is playing and replace everything after it: a radio started
     * from the playing song becomes what follows. The queue's name changes
     * with its contents — see [queueName]. One range replace, not an edit per
     * track, each of which would rebuild the session queue.
     */
    fun replaceUpcoming(tracks: List<Track>, name: String? = null) {
        player ?: return
        settleRestartCopy()
        val q = _queue.value
        val cur = _index.value
        if (cur !in q.indices) {
            playQueue(tracks, 0, name)
            return
        }
        val droppedIds = q.drop(cur + 1).map { it.id }
        queuePlayer?.replaceRange(cur + 1, q.size, tracks.map(sources::item))
        _queue.value = q.take(cur + 1) + tracks
        _queueName.value = name
        remote?.queueEdited(QueueEdit.UpcomingReplaced(droppedIds, tracks.map { it.id }))
        rearmSoon()
    }

    fun removeFromQueue(index: Int) {
        editQueue { q ->
            if (index !in q.indices) return@editQueue null
            RowEdit(
                queue = q.toMutableList().apply { removeAt(index) },
                told = QueueEdit.Removed(listOf(q[index].id)),
            ) { it.removeMediaItem(index) }
        }
    }

    /**
     * Drop several tracks at once: removed from the player back to front, so
     * no removal shifts the ones still to go, and the surviving list worked
     * out here rather than read back from the player mid-edit.
     */
    fun removeFromQueue(ids: Set<String>) {
        editQueue { q ->
            val targets = q.indices.filter { q[it].id in ids }
            if (targets.isEmpty()) return@editQueue null
            val gone = targets.toSet()
            RowEdit(
                queue = q.filterIndexed { i, _ -> i !in gone },
                told = QueueEdit.Removed(targets.map { q[it].id }),
            ) { held -> targets.sortedDescending().forEach { held.removeMediaItem(it) } }
        }
    }

    private fun moveInQueue(from: Int, to: Int) {
        editQueue { q ->
            if (from !in q.indices || to !in q.indices || from == to) return@editQueue null
            val newQueue = q.toMutableList().apply { add(to, removeAt(from)) }
            // An output is told what the track now follows: nothing at the front.
            RowEdit(
                queue = newQueue,
                told = QueueEdit.Moved(listOf(q[from].id), newQueue.getOrNull(to - 1)?.id),
            ) { it.moveMediaItem(from, to) }
        }
    }

    /**
     * Move several tracks (by index) as a block to [insertAt] among the rest,
     * applied to the player one move at a time, as it has no batch move.
     */
    fun moveGroupInQueue(fromIndices: Set<Int>, insertAt: Int) {
        editQueue { q ->
            val (newQueue, landsAt) = movedAsBlock(q, fromIndices, insertAt) ?: return@editQueue null
            // An output is told what the block's first track now follows:
            // nothing at the front.
            val movedIds = newQueue.subList(landsAt, landsAt + fromIndices.size).map { it.id }
            RowEdit(
                queue = newQueue,
                told = QueueEdit.Moved(movedIds, newQueue.getOrNull(landsAt - 1)?.id),
            ) { held -> stepwiseMoves(q, newQueue).forEach { (from, to) -> held.moveMediaItem(from, to) } }
        }
    }

    /** Rows removed or moved: the new queue, what an output is told, and the same change made to the player. */
    private class RowEdit(
        val queue: List<Track>,
        val told: QueueEdit,
        val onPlayer: (LightAudioPlayer) -> Unit,
    )

    /**
     * Remove or move rows. [edit] works out the change from the queue as it
     * is, or null when there is nothing to do. The player is changed only
     * while it holds the queue, and the playing track stays the one playing —
     * see [indexAfterEdit].
     */
    private fun editQueue(edit: (List<Track>) -> RowEdit?) {
        pickedIndex = -1 // indices are about to move under it
        player ?: return
        settleRestartCopy()
        val q = _queue.value
        val change = edit(q) ?: return
        val playingId = q.getOrNull(_index.value)?.id
        queuePlayer?.let(change.onPlayer)
        _queue.value = change.queue
        _index.value = indexAfterEdit(change.queue, playingId)
        remote?.queueEdited(change.told)
        rearmSoon()
    }

    /**
     * Where the playing track sits after a queue edit. The player's index
     * answers while it is the one playing. While the sound is elsewhere it
     * sits on whatever it held when casting began, and reading it started the
     * wrong song, so the track is found by id, which survives a reorder.
     */
    private fun indexAfterEdit(newQueue: List<Track>, playingId: String?): Int {
        if (newQueue.isEmpty()) return -1
        if (!playerSpeaks) {
            val byId = newQueue.indexOfFirst { it.id == playingId }
            return if (byId >= 0) byId else _index.value.coerceIn(0, newQueue.lastIndex)
        }
        return (player?.currentMediaItemIndex?.value ?: _index.value).coerceIn(0, newQueue.lastIndex)
    }

    /**
     * Shuffle or unshuffle the upcoming part of the queue only: history and
     * the playing track stay put, so the playing track is never re-prepared.
     * Switching off puts the upcoming tracks back in their earlier order.
     */
    fun toggleShuffle() {
        pickedIndex = -1 // indices are about to move under it
        settleRestartCopy()
        val on = !_shuffle.value
        _shuffle.value = on
        val q = _queue.value
        val current = _index.value
        if (q.isEmpty() || current < 0) {
            preShuffleOrder = if (on) q.takeIf { it.isNotEmpty() }?.toList() else null
            return
        }
        val head = q.take(current + 1)
        val tail = q.drop(current + 1)
        val newTail = if (on) {
            preShuffleOrder = q.toList()
            tail.shuffled()
        } else {
            val original = preShuffleOrder
            preShuffleOrder = null
            if (original == null) tail else restoreOrder(tail, original)
        }
        if (tail.isEmpty()) return
        _queue.value = head + newTail
        // One replace rather than a move per track: a few hundred moves are a
        // timeline update each, which locks Main and, with artwork, exhausts
        // the heap.
        queuePlayer?.replaceRange(head.size, q.size, newTail.map(sources::item))
        remote?.queueEdited(QueueEdit.Reordered)
        rearmSoon()
    }

    // --- Repeat and volume ---------------------------------------------------

    fun cycleRepeat() {
        setRepeat(
            when (_repeatMode.value) {
                RepeatMode.OFF -> RepeatMode.QUEUE
                RepeatMode.QUEUE -> RepeatMode.TRACK
                RepeatMode.TRACK -> RepeatMode.OFF
            }
        )
    }

    private fun setRepeat(mode: RepeatMode) {
        _repeatMode.value = mode
        // A copy queued for repeat-one goes before the mode changes under it,
        // and repeat-one over a remainder gets one — see restartCopyAt.
        settleRestartCopy()
        queuePlayer?.repeatMode = mode.forPlayer()
        if (mode == RepeatMode.TRACK) rearmSoon()
        remote?.repeatChanged(mode)
    }

    private fun RepeatMode.forPlayer(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
        RepeatMode.QUEUE -> Player.REPEAT_MODE_ALL
    }

    /**
     * A hardware volume key; true when it was handled here. While casting each
     * press steps the output's volume (see [setVolume]); otherwise the key is
     * left to the system.
     */
    fun handleVolumeKey(keyCode: Int): Boolean {
        if (!isCasting) return false
        val step = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_KEY_STEP
            KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_KEY_STEP
            else -> return false
        }
        setVolume(_volume.value + step)
        return true
    }

    /** Set the volume of whatever has the sound: the output, or the phone's media stream. */
    fun setVolume(fraction: Float) {
        val v = fraction.coerceIn(0f, 1f)
        remote?.let { out ->
            out.setVolume(v)
            return
        }
        _volume.value = v
        player?.setSystemVolume(v)
    }

    // --- Where the sound goes ------------------------------------------------

    /** Every family the sound can go to from here: the active source's own, and the phone's. */
    fun outputFamilies() = OutputFamilies(App.source.value.provider.outputFamilies, Outputs.phone)

    /** Every [OutputTarget] [family] can play to right now. Does its own waiting off Main. */
    suspend fun find(family: OutputFamily): List<OutputTarget> = outputOf(family).find()

    /** Whether [family] is reached over the local network only, so finds nothing off Wi-Fi. Main only. */
    fun needsLan(family: OutputFamily): Boolean = outputOf(family).needsLan

    private fun outputOf(family: OutputFamily): Output = outputs.getOrPut(family.id) { family.create(host) }

    /**
     * Move playback to [target]: stop this device and hand the queue over, at
     * the current track and the position it has reached. False, with nothing
     * changed, when it can't be had right now: off Wi-Fi, or with nothing to
     * steer it with.
     */
    fun castTo(family: OutputFamily, target: OutputTarget): Boolean {
        val next = outputOf(family)
        // Playing or starting goes on there, paused stays paused: read before
        // anything below pauses the phone.
        val play = _isPlaying.value || _buffering.value
        // No Wi-Fi, no speaker: one found a minute ago is out of reach now, and
        // playback mustn't leave the phone for it.
        if (next.needsLan && !App.rulesNow().mayUseLan) return false
        if (!next.canSteer()) return false
        forgetLost()
        settleRestartCopy()
        cancelPendingStart()
        // A far end that reports its own playback does so as a session of its
        // own, and the phone's ends: left open, nothing would report on it and
        // it would sit on the dashboard beside the far end's until it timed out.
        if (next.reportsItself) reports.close()
        // One route at a time: whatever has the sound hands over quietly, a
        // second speaker of the same kind included.
        val current = _active.value
        if (current != null && (current.output !== next || current.target.id != target.id)) {
            dropOutput(Disconnect.Switch)
        }
        // Already the stand-in when this is a switch, which follows the output
        // by itself.
        queuePlayer?.pause()
        // The phone's wait for the network was about the phone's player, and
        // would stand where "Buffering…" is said; a hand-back finds out again.
        // Kept for a cast that never gets going — see OutputHost.dropped.
        waitBeforeCast = _waitingFor.value?.let { it to _index.value }
        _waitingFor.value = null
        _active.value = ActiveOutput(family, target, next)
        standIn.start(player)
        // A start, where there is something to start: a speaker chosen with
        // nothing queued waits for the queue, and one chosen paused for a play.
        if (play && _queue.value.getOrNull(_index.value) != null) markStartingElsewhere()
        next.connect(target, fromMs = _positionMs.value, play = play)
        return true
    }

    /** Bring the sound back to this phone, from wherever the output had got to. */
    fun playHere() {
        // This device, chosen: an output the link took away is not to be taken
        // back when it returns.
        forgetLost()
        dropOutput(Disconnect.HandBack)
    }

    /**
     * Let go of a speaker the phone has left paused for ten minutes, as an
     * iPhone lets go of an AirPlay speaker and Spotify of a Connect device:
     * the next play, the next morning's too, is on the phone. The speaker is
     * stopped and the queue comes back here, paused where it was.
     *
     * Asked of the clock, not left to the timer alone, which a sleeping phone
     * or an app frozen in the background doesn't run: a play, and a look at
     * the Output page, ask first. A source's own players are left alone, as
     * they hold the queue themselves. Main only.
     */
    fun forgetPausedSpeaker() {
        val since = speakerPausedSinceMs
        if (since == 0L) return
        val a = _active.value ?: return
        if (a.family !in Outputs.phone) return
        val pausedFor = SystemClock.elapsedRealtime() - since
        if (pausedFor < FORGET_PAUSED_SPEAKER_MS) return
        Log.i(a.output.logTag, "paused ${pausedFor / 60_000} min on ${a.target.name} → back to the phone")
        playHere()
    }

    /**
     * Stop sending the sound to whatever has it, for [why]: the output is told
     * as its family needs (see Output.disconnect). Then, unless playback
     * stopped or the app is being left, the fader goes back to the phone's
     * level and the queue is picked up here where the output had got to:
     * playing on a hand-back if it was playing, paused otherwise.
     */
    private fun dropOutput(why: Disconnect) {
        // A start still under way there counts, as it does on the phone.
        val wasPlaying = _isPlaying.value || _buffering.value
        // Where the far end has got to now, not at its last report a second or
        // so ago, or the phone picks up a little behind the speaker.
        val at = carriedOn(
            reportedMs = _positionMs.value,
            sinceReportMs = SystemClock.elapsedRealtime() - positionReportedAtMs,
            playing = isCasting && _isPlaying.value && !_buffering.value,
            durationMs = _durationMs.value,
        )
        val a = _active.value ?: return
        // Let go before telling it, so nothing still running reads it as
        // steering on the way out: the requeue collector would arm one more
        // track on a renderer being stopped.
        if (!_active.compareAndSet(a, null)) return
        val out = a.output
        out.disconnect(a.target, why)
        if (why == Disconnect.Stopped || why == Disconnect.Leaving) return
        // Kept, to be taken back when the link is — see rejoinLost.
        if (why == Disconnect.LinkLost) {
            lost = Lost(
                active = a,
                session = reports.playbackSession.value,
                sourceId = App.source.value.id,
                index = _index.value,
                positionMs = at,
                atMs = SystemClock.elapsedRealtime(),
            )
        }
        faderBackToPhone()
        when (why) {
            // The phone carries on as the far end was, playing or paused.
            Disconnect.HandBack -> takeOverHere(out.logTag, "hand-back", at, play = wasPlaying)
            // Shown paused where the speaker was, not "playing" over a silent
            // phone on the song the cast began with.
            Disconnect.LinkLost -> takeOverHere(out.logTag, "Wi-Fi gone", at, play = false)
            else -> Unit
        }
    }

    /**
     * The sound is this phone's again: the queue rebuilt here at its track and
     * [at], playing if [play], otherwise paused and shown paused at once.
     * [why] is for the log.
     *
     * The queue moved on while the output had it (a renderer's poll walks
     * _index as tracks finish there), but this player sat on what it held
     * when casting began. A seek in place landed inside that old track, and a
     * seek into the right one clamped to 0:00, so the queue is rebuilt at the
     * index and position in one call — see [rebuildQueueAt]. Reached from
     * polls on Default as well.
     */
    private fun takeOverHere(logTag: String, why: String, at: Long, play: Boolean) {
        if (!play) markStopped()
        scope.launch(Dispatchers.Main.immediate) {
            // Before the queue goes back: nothing done to the player from here
            // is a press, and its readings are the controller's again.
            standIn.stop()
            val cur = player ?: return@launch
            val tracks = _queue.value
            if (tracks.isEmpty()) return@launch
            val index = _index.value.coerceIn(0, tracks.lastIndex)
            Log.i(logTag, "$why: on the phone at idx=$index at=$at ${if (play) "playing" else "paused"}")
            rebuildQueueAt(cur, tracks, index, at)
            // The stand-in played with repeat off; the queue repeats as shown.
            cur.repeatMode = _repeatMode.value.forPlayer()
            playOrCue(cur, index, play)
        }
    }

    /** Mark nothing playing and nothing starting; whether anything was. */
    private fun markStopped(): Boolean {
        val was = _isPlaying.value || _buffering.value
        _isPlaying.value = false
        _buffering.value = false
        return was
    }

    /** [output] on [target] no longer has the sound, if it still did; null when it didn't. */
    private fun letGo(output: Output, target: OutputTarget): ActiveOutput? {
        val a = _active.value ?: return null
        if (a.output !== output || a.target !== target) return null
        if (!_active.compareAndSet(a, null)) return null
        return a
    }

    /** The fader shows the phone's own level again. */
    private fun faderBackToPhone() {
        player?.let { _volume.value = it.deviceVolume.value }
    }

    /**
     * An output the link took away, and where the phone stood when it went —
     * see [rejoinLost]. The speaker plays on at home, and is taken back when
     * Wi-Fi is, unless the phone has moved on since.
     */
    private class Lost(
        val active: ActiveOutput,
        val session: String,
        val sourceId: String,
        val index: Int,
        val positionMs: Long,
        val atMs: Long,
    )

    /** Whatever the link took away is not to be taken back: another choice was made here. */
    private fun forgetLost() {
        lost = null
        rejoinJob?.cancel()
    }

    /**
     * Wi-Fi is back and an output it took away is remembered: look for it,
     * and take it back as an iPhone does. Whatever of this queue it still
     * plays is stopped at once, so the server never has two streams of one
     * song going (the speaker turned the second away), and it is chosen again
     * paused where it got to; a play sends the song afresh. Only while nothing
     * here would be undone by it (see [movedOn]), and only for
     * [LOST_OUTPUT_KEPT_MS]: given up quietly then, or when it can't be found
     * or is busy with someone else's sound.
     */
    private fun rejoinLost() {
        val gone = lost ?: return
        if (rejoinJob?.isActive == true) return
        val out = gone.active.output
        val name = gone.active.target.name
        rejoinJob = scope.launch {
            if (SystemClock.elapsedRealtime() - gone.atMs > LOST_OUTPUT_KEPT_MS) {
                Log.i(out.logTag, "Wi-Fi back, but $name went too long ago — left")
                if (lost === gone) lost = null
                return@launch
            }
            Log.i(out.logTag, "Wi-Fi back — looking for $name")
            // Bounded. A link that goes again meanwhile cancels this and keeps
            // the memory for the next time it comes back.
            val found = withTimeoutOrNull(REJOIN_SEARCH_MS) { out.findAgain(gone.active.target) }
            withContext(Dispatchers.Main.immediate) {
                if (lost !== gone) return@withContext
                lost = null
                when {
                    found == null -> Log.i(out.logTag, "$name not found, or playing something else — left")
                    movedOn(gone) -> Log.i(out.logTag, "moved on here since — $name stopped, not taken back")
                    else -> rejoin(gone, found)
                }
            }
        }
    }

    /**
     * Whether anything happened on the phone since [gone] was lost that taking
     * it back would undo: another output, source, queue, song or place in it,
     * or the phone making sound. Main only.
     */
    private fun movedOn(gone: Lost): Boolean =
        _active.value != null ||
            App.source.value.id != gone.sourceId ||
            reports.playbackSession.value != gone.session ||
            _index.value != gone.index ||
            abs(_positionMs.value - gone.positionMs) > REJOIN_POSITION_SLACK_MS ||
            _isPlaying.value || _buffering.value || player?.playWhenReady == true

    /** Make [gone]'s output the one with the sound again, paused where [found] says it got to. Main only. */
    private fun rejoin(gone: Lost, found: Refound) {
        val out = gone.active.output
        Log.i(
            out.logTag,
            "took ${found.target.name} back, paused " +
                if (found.index >= 0) "at idx=${found.index} ${found.positionMs}" else "where the phone was",
        )
        settleRestartCopy()
        cancelPendingStart()
        player?.pause()
        // The phone's wait for the network was about the phone's player, and
        // the sound is elsewhere again.
        _waitingFor.value = null
        _active.value = ActiveOutput(gone.active.family, found.target, out)
        // Before the song, so the change of song is announced as what it is —
        // see ServerReports.followCurrentSong.
        _isPlaying.value = false
        if (found.index in _queue.value.indices) {
            _index.value = found.index
            _positionMs.value = found.positionMs
            seedDuration(_queue.value[found.index])
        }
        out.rejoin(found)
        standIn.start(player)
    }

    /** What every output is given of this controller — see [OutputHost]. */
    private val host = object : OutputHost {
        override val scope: CoroutineScope get() = this@PlaybackController.scope
        override val queue: StateFlow<List<Track>> get() = _queue
        override val index: StateFlow<Int> get() = _index
        override val isPlaying: StateFlow<Boolean> get() = _isPlaying
        override val positionMs: StateFlow<Long> get() = _positionMs
        override val durationMs: StateFlow<Long> get() = _durationMs
        override val repeatMode: StateFlow<RepeatMode> get() = _repeatMode
        override val shuffle: StateFlow<Boolean> get() = _shuffle

        override fun steering(output: Output): OutputTarget? =
            _active.value?.takeIf { it.output === output }?.target

        override fun showPlaying(playing: Boolean) {
            val resumed = playing && !_isPlaying.value
            _isPlaying.value = playing
            // Resumed by the far end's own remote: the server hears it as it
            // would a play pressed here, which reports for itself.
            if (resumed) reports.timeline(TimelineState.PLAYING)
        }

        override fun showSounding() {
            if (!_buffering.value) return
            bufferingProbe = null
            _isPlaying.value = true
            heardSound()
        }

        override fun showPaused() {
            // Not pressed here, so not reported yet.
            if (markStopped()) reports.timeline(TimelineState.PAUSED)
        }

        // The far end's clock moving is its sound, which ends "Buffering…" as
        // the phone's own does.
        override fun showPosition(ms: Long) {
            _positionMs.value = ms
            positionReportedAtMs = SystemClock.elapsedRealtime()
            noteProgress(ms)
        }

        override fun showDuration(ms: Long) {
            _durationMs.value = ms
        }

        override fun showIndex(index: Int) {
            _index.value = index
        }

        override fun showRepeat(mode: RepeatMode) {
            _repeatMode.value = mode
        }

        override fun showShuffle(on: Boolean) {
            _shuffle.value = on
        }

        override fun showVolume(level: Float) {
            _volume.value = level
        }

        override fun client(): MusicServer? = serverClient.value

        override fun rules(): LinkRules = App.rulesNow()

        override fun mayTalkToServer(): Boolean = metadataAllowed()

        override fun streamFormat(): StreamFormat = sources.effectiveFormat()

        // The server's own address, never the player's: the door is on this
        // phone's loopback, where a speaker can't reach it.
        override fun streamUrl(
            track: Track,
            format: StreamFormat,
            timeOffsetSeconds: Int,
            estimateContentLength: Boolean,
            sessionId: String?,
        ): String? = sources.serverUrl(track, format, timeOffsetSeconds, estimateContentLength, sessionId)

        override fun knownStream(track: Track, format: StreamFormat): KnownStream? =
            serverClient.value?.knownStream(track, format)

        override fun ownStreamSession(trackId: String): String = reports.streamSessionIdFor(trackId, decidedFirst = false)

        override fun announceNowPlaying() {
            reports.announceNowPlaying()
        }

        override fun queueFinished(output: Output) {
            if (steering(output) == null) return
            this@PlaybackController.queueFinished()
        }

        override fun setPhoneVolume(level: Float) {
            player?.setSystemVolume(level)
        }

        override fun dropped(output: Output, target: OutputTarget) {
            val a = letGo(output, target) ?: return
            // Paused where the cast began, with nothing starting any more, and
            // the fader the phone's again.
            val was = markStopped()
            faderBackToPhone()
            // A far end that reports itself had its own session; this one was
            // the phone's, which the server would go on showing as playing.
            if (was && !a.output.reportsItself) reports.timeline(TimelineState.PAUSED)
            // Nothing played there, so what the phone was waiting for before the
            // cast still holds, on the same song.
            waitBeforeCast?.let { (why, at) -> if (_index.value == at) _waitingFor.value = why }
            waitBeforeCast = null
            // The stand-in may already be in the player.
            takeOverHere(output.logTag, "couldn't cast", _positionMs.value, play = false)
        }

        override fun couldNotPlay(output: Output) {
            if (steering(output) == null) return
            if (markStopped()) reports.timeline(TimelineState.PAUSED)
        }

        override fun endedOnItsOwn(output: Output, target: OutputTarget) {
            letGo(output, target) ?: return
            faderBackToPhone()
            takeOverHere(output.logTag, "stopped there", _positionMs.value, play = false)
        }
    }

    // --- What the server is told ---------------------------------------------

    /**
     * Whether the song playback is on is playing or about to, which is what a
     * change of song is announced as. The player's own state, not
     * [isPlaying], which lags it and is stale for a moment on a reopen. Main
     * only, for [LightAudioPlayer.playWhenReady].
     */
    private fun playbackWanted(): Boolean {
        if (remote != null) return _isPlaying.value
        val p = player ?: return false
        if (p.availability.value != LightAudioPlayerAvailability.Ready || parked) return false
        return p.isPlaying.value || p.playWhenReady || _buffering.value
    }

    companion object {
        // Why a track is parked, in the screen's words.

        /** The server refused the track: a bad status, not a bad link. */
        private const val CANT_PLAY_THIS = "Server can't play this track"

        /** A file on the phone that wouldn't open or decode. */
        private const val CANT_PLAY_FILE = "Can't play this file"

        /** A stream that arrived and wouldn't decode. */
        private const val CANT_PLAY_FORMAT = "Can't play this format"

        /** The loopback door the player streams through isn't up — see StreamProxy. */
        private const val STREAM_UNAVAILABLE = "Can't stream right now"

        /** The player's name for "the server answered with an error status". */
        private val BAD_HTTP_STATUS = PlaybackException.getErrorCodeName(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        // Starting and parking.

        /** How often the link is re-read while playing or parked — see bind. */
        private const val LINK_POLL_MS = 3_000L

        /** How long a parked player keeps re-reading the link: one in a pocket shouldn't ask all afternoon. */
        private const val PARKED_POLL_LIMIT_MS = 10 * 60 * 1000L

        /** How much of a track has to pass, in as much time, to count as playing — see noteProgress. */
        private const val PROGRESS_PROOF_MS = 1_000L

        private const val BUFFERING_LIMIT_MS = 45_000L

        /** How long a dead stream waits on the reachability ping before taking it as a no. */
        private const val VERIFY_WAIT_MS = 2_500L

        /** How long Plex gets to settle a transcode decision; short, as it sits between the tap and the sound. */
        private const val STREAM_DECISION_TIMEOUT_MS = 2_000L

        /** How near the end still counts as having reached it. */
        private const val QUEUE_END_SLACK_MS = 1_000L

        // Seeking.

        /**
         * How near the whole track a seeked stream's length can be and still
         * mean the offset was ignored. Generous: a transcode's length is its
         * own estimate.
         */
        private const val IGNORED_OFFSET_SLACK_MS = 3_000L

        /** Long enough for the player to be reporting the stream, not the request. */
        private const val SEEK_VERIFY_MS = 900L

        /** How long a rebuilt queue gets to open its stream before the seek is judged. */
        private const val REBUILD_VERIFY_TIMEOUT_MS = 8_000L

        /** How far a native seek may land from the target before it counts as a miss. */
        private const val SEEK_TOLERANCE_MS = 5_000L

        /** How long a seek may wait for sound before the screen says it is buffering. */
        private const val SEEK_BUFFERING_GRACE_MS = 300L

        // The queue.

        /** Settling time before an output hears that what plays next has changed. */
        private const val UPCOMING_DEBOUNCE_MS = 400L

        /** How long a restore waits to learn what is downloaded, and whether the door is up. */
        private const val DOWNLOADS_READY_TIMEOUT_MS = 4_000L
        private const val PROXY_READY_TIMEOUT_MS = 3_000L

        /** How often the queue snapshot is written, and how long leaving waits for the last one. */
        private const val SAVE_STATE_INTERVAL_MS = 5_000L
        private const val SAVE_STATE_TIMEOUT_MS = 1_000L

        // Outputs.

        /** One press of the rocker, as a fraction of full scale. */
        private const val VOLUME_KEY_STEP = 0.05f

        private const val FORGET_PAUSED_SPEAKER_MS = 10 * 60 * 1000L

        /** How long an output the link took away is kept: a walk out of range, not an afternoon out. */
        private const val LOST_OUTPUT_KEPT_MS = 10 * 60 * 1000L

        /** How long the search for a lost output may take once the link is back. */
        private const val REJOIN_SEARCH_MS = 30_000L

        /** How far the phone's place may have drifted since and still be where the output was lost. */
        private const val REJOIN_POSITION_SLACK_MS = 3_000L
    }
}

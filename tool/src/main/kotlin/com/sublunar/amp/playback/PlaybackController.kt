package com.sublunar.amp.playback

import androidx.media3.common.Player
import com.sublunar.amp.data.NetworkGate
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.Connectivity
import com.sublunar.amp.data.DownloadStore
import com.sublunar.amp.data.PhoneFiles
import com.sublunar.amp.data.RepeatMode
import android.view.KeyEvent
import androidx.media3.common.PlaybackException
import com.sublunar.amp.App
import com.sublunar.amp.data.PendingAction
import com.sublunar.amp.data.UNREACHABLE
import com.sublunar.amp.data.WriteResult
import com.sublunar.amp.data.attemptWrite
import com.sublunar.amp.data.SavedQueue
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.MusicServer
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
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.qualityRank
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioPlayerAvailability
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightPlayerSpec
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.channels.Channel
import com.sublunar.amp.data.LinkRules
import com.sublunar.amp.data.StreamProxy

/**
 * The single, app-scoped playback engine. Wraps one [LightAudioPlayer] (created
 * from the activity, reused across every screen). All queue mutations route
 * through here; edits use the player's incremental ops so adding or reordering
 * upcoming tracks never interrupts the current one.
 *
 * Playback is foreground-only until LightOS ships background audio; this class
 * needs no change when it does.
 */
class PlaybackController(
    private val settings: AppSettings,
    private val serverClient: StateFlow<MusicServer?>,
    /** Resolved per call: the active source's database, see App.dao. */
    private val daoProvider: () -> LibraryDao,
    private val downloads: DownloadStore,
    private val scope: CoroutineScope,
    /**
     * Whether the server may be spoken to at all — see App.metadataAllowed.
     *
     * Playback itself is not gated by this: what may be *streamed* is decided
     * by the mode and the source's own cellular format. This governs the small
     * talk that goes with it — timelines, now-playing, scrobbles — which used
     * to carry on over cellular in a mode whose whole promise is that it won't.
     */
    private val metadataAllowed: () -> Boolean = { true },
) {
    /**
     * The handle on the detached service. Replaced on Main — by [attach], when
     * a new activity takes over — and read from Default as well: by the
     * restore, the rules and downloads collectors, and a source switch's stop.
     */
    @Volatile
    private var player: LightAudioPlayer? = null

    /** The activity [player] was made from — see [attach] and [release]. */
    private var handleOwner: LightAudio? = null

    /**
     * The ReplayGain factor last given to the playing service, carried onto
     * each new handle before it connects. A handle starts at unity, and the SDK
     * hands its gain to the service the moment it connects: without the seed,
     * every reopen on an attenuated track jumped to full level until the
     * track's own gain came back round.
     */
    private var appliedGain = 1f

    /**
     * Whether anything has actually played since the queue last reached its end.
     *
     * Tells a finished queue apart from one that was merely cued up — see
     * [rewindIfQueueFinished].
     */
    private var hasPlayed = false

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    /**
     * What the queue was started *as*, where that has a name — "Judith Radio"
     * — for anything that wants to keep it, like saving it as a playlist. Null
     * for a queue that is just some songs. Set with the queue and cleared with
     * it; adding to the queue leaves it, since the radio is still what it was.
     */
    private val _queueName = MutableStateFlow<String?>(null)
    val queueName: StateFlow<String?> = _queueName

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index

    /**
     * Loudness normalisation on/off — fed from settings by App. While on, the
     * playing track's server-measured gain ([Track.gainDb]) becomes the
     * player's own volume; see the collector in [collectHandle].
     */
    val replayGain = MutableStateFlow(true)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

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

    val currentTrack: StateFlow<Track?> =
        combine(_queue, _index) { q, i -> q.getOrNull(i) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Mirrored from [Connectivity.unmetered]; source selection is on the main thread. */
    @Volatile
    private var unmetered = false
    private var lastScrobbledId: String? = null
    private var nowPlayingId: String? = null
    /** The sources a refused play has been said for, this run — see [maybeSubmitPlay]. */
    private val playRefusalSaid: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Identity of the queue last handed to the server — see pushQueueToServer. */
    private var lastSavedQueueKey: Int? = null

    /**
     * Identifies playback to the server — on the [reportTimeline] calls, and
     * on the streams they report on — distinct from any download in flight;
     * see the comment on [MusicServer.streamUrl].
     *
     * Two policies, and the server says which ([MusicServer.oneSessionPerPlayer]).
     * Plex wants one session for the whole listening, changing song as it
     * goes, which is what Plexamp does and what its dashboard shows as one
     * entry: [listeningSession], minted once per process and again after
     * [stop], never per queue or per song. An id per song put a new entry on
     * the dashboard for every song, and with "remote streams allowed per user"
     * at one, the next song was refused (503) while the last one's session was
     * still listed (issue #20).
     *
     * Jellyfin keeps one per song, so its id is derived from the queue and the
     * song: [playbackSession] changes with each new queue, so playing the same
     * song again is a new session rather than a continuation of the old one.
     * Derived rather than stored per song because the whole queue is turned
     * into audio items at once, each carrying the id that existed at that
     * moment, and the URL and the timeline have to agree however far ahead the
     * queue was built.
     *
     * Whichever it is, a "stopped" is never worked out from these again: it
     * goes out under the id that was announced — see [sessions].
     */
    @Volatile
    private var listeningSession: String = newSessionId()

    /** The queue's part of a per-song id — a flow, so a new queue of the same songs is still seen; see [bind]. */
    private val playbackSession = MutableStateFlow(newSessionId())

    /** The id the server knows this song's playback by, which every timeline report about it carries. */
    private fun sessionIdFor(trackId: String): String =
        if (serverClient.value?.oneSessionPerPlayer == true) listeningSession else "${playbackSession.value}:$trackId"

    /**
     * The id a stream of this song carries.
     *
     * On a server with one session per player, the listening session's own
     * id — but only on a stream the loopback door decides first
     * ([decidedFirst]; see [playerUrl]). Plex refuses a transcode under a
     * session it is tracking unless a decision for that very song came first,
     * and the session is tracked from its first timeline report. Anything else
     * that fetches on its own — a speaker, or the player while the door is
     * down — gets an id of its own, which no timeline names, so Plex holds it
     * to nothing: the way every stream worked before. It also keeps two
     * transcodes apart where both are in flight at once, as a speaker's next
     * song is while the current one is still being read: Plex keeps one per
     * id and tears the other down.
     */
    private fun streamSessionIdFor(trackId: String, decidedFirst: Boolean): String =
        if (serverClient.value?.oneSessionPerPlayer == true && !decidedFirst) {
            "$listeningSession:$trackId"
        } else {
            sessionIdFor(trackId)
        }

    /**
     * Whether the door will ask the server's decision for this stream right
     * before fetching it — see [playerUrl].
     */
    private fun decidesAtDoor(client: MusicServer, track: Track, format: StreamFormat): Boolean =
        client.oneSessionPerPlayer && App.streamProxy.isOn && client.streamNeedsDecision(track, format)

    /**
     * The timeline reports on their way to the server, sent one at a time in
     * the order they were decided. They used to be a launch apiece, so a song's
     * "stopped" and the next one's "playing" could arrive either way round.
     */
    private val timelineOut = Channel<TimelineSend>(Channel.UNLIMITED)

    private sealed interface TimelineSend {
        /** [report], to the server that was current when it was decided. */
        class Report(val client: MusicServer, val report: TimelineReport) : TimelineSend

        /** Everything queued before this has been sent — see [timelineSent]. */
        class Flush(val done: CompletableDeferred<Unit>) : TimelineSend
    }

    init {
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

    /**
     * The session the server was last told is open, kept as it was announced —
     * see [SessionHandOver]. Every report goes through it, so a change of song
     * or an end to playback stops exactly the session that was said before it.
     */
    private val sessions = SessionHandOver(::queueTimeline)

    // Local files for downloaded tracks, refreshed as downloads land so queue
    // items can be built synchronously.
    private var localFiles: Map<String, Pair<java.io.File, StreamFormat>> = emptyMap()

    private val dao: LibraryDao get() = daoProvider()

    /**
     * How far into the track the current *stream* starts, when the server was
     * asked to seek for us. Zero for files and for a stream played from the top.
     */
    @Volatile
    private var streamOffsetMs = 0L

    /**
     * The track whose queue slot currently holds a server-seeked *remainder*
     * instead of the whole song. The replacement is real: it lives in the
     * player's queue, so it would be what plays on every later visit — a
     * repeat wrap or a tap on the row would play the tail the last seek left
     * behind. The index collector puts the original item back the moment
     * playback leaves the slot. The one return path with no index change, a
     * repeat-one wrap, never comes back to a remainder: under repeat-one the
     * whole song is queued after it — see [restartCopyAt].
     */
    @Volatile
    private var offsetItemTrackId: String? = null

    /**
     * Where the player holds the whole song, queued straight after a
     * server-seeked remainder while repeat-one is on — or -1.
     *
     * Repeat-one replays the item it is on, and on a remainder that is the
     * tail from the seek point: rebuilt only once Media3 says it wrapped, a
     * moment of the wrong part had already played. So under repeat-one the
     * remainder is followed by the song itself and the player's own repeat is
     * off: the remainder ends into the song's start, as gapless as any change
     * of track, and [finishRestart] then drops the remainder and hands repeat
     * back to the player.
     *
     * While set, the player's queue is one item longer than [_queue] — the copy,
     * just after the playing slot — so anything that addresses the player by
     * position calls [settleRestartCopy] first. Main only, but read by the
     * collectors to tell the copy's start from a new track.
     */
    @Volatile
    private var restartCopyAt = -1

    /** Latched by [fallBackOffline]; cleared when the server answers again. */
    @Volatile
    private var forceOffline = false
    private var retriedTrackId: String? = null

    /**
     * Parked: the current track needs the server and may not have it — the
     * mode's rule, no link, or a server that isn't answering. The value is the
     * reason in the screen's words (see LinkRules.waitingFor), null when not
     * parked. The player sits in its error state until [resumeAfterWait]
     * prepares it again: when the rules allow, cued where it stood; on a press
     * before that, left parked, which is the screen keeping its word.
     *
     * Parking is the last resort. Playback that *arrived* at such a track on
     * its own skips ahead to the next one that can play and leaves this one in
     * the queue — see [blockedAtCurrent]. Only a track the user asked for, or a
     * queue with nothing playable left in it, parks.
     */
    private val _waitingFor = MutableStateFlow<String?>(null)
    val waitingFor: StateFlow<String?> = _waitingFor
    private val parked: Boolean get() = _waitingFor.value != null

    /** Parked because of the track, not the link: the link changing is no reason to try it again. */
    private val parkedOnTheTrackItself: Boolean
        get() = _waitingFor.value.let { it == CANT_PLAY_FILE || it == CANT_PLAY_FORMAT || it == CANT_PLAY_THIS }

    /**
     * The queue position the user last asked for by hand — a tap on a row, a
     * new queue, next or previous. Playback reaching a position by itself is
     * not that, and the difference decides between parking and skipping.
     */
    private var pickedIndex: Int = -1

    /**
     * A stream has been asked to start and hasn't made a sound yet.
     *
     * Two readers. The screen, which otherwise shows a track sitting there
     * looking paused for as long as a slow link takes — and a player that
     * looks like it ignored the tap gets tapped again, which used to tear the
     * half-buffered stream down and start it over, so that on a weak link it
     * never did start. And the background work (sync, playlist fetching,
     * downloads), which steps aside while this is true — see
     * App.yieldToPlayback. Cleared by sound, by failure, by a pause, and by a
     * timeout, so nothing waits on it for ever.
     */
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering

    /** Sound is coming out: the start is over, whatever asked for it. */
    private fun heardSound() {
        hasPlayed = true
        // The pick has been honoured. From here on, whatever happens to this
        // track happened to playback, not to a request — see blockedAtCurrent.
        pickedIndex = -1
        lastSkipTarget = -1
        _buffering.value = false
    }

    /** Where the position stood when a start was first seen buffering, and when — see [noteProgress]. */
    private var bufferingProbe: Pair<Long, Long>? = null

    /**
     * The player's own "playing" can go stale — see [stop] — and a start that
     * is never reported leaves "Buffering…" and a play button over music that
     * is plainly playing. A position that moves is sound coming out, whatever
     * the flag says: a second of the track in a second of time, so a seek's
     * jump doesn't count.
     */
    private fun noteProgress(positionMs: Long) {
        if (!_buffering.value) {
            bufferingProbe = null
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
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

    /** Mark a start at [index] — buffering only if that row is a stream. */
    private fun starting(index: Int) {
        _buffering.value = queuedSources.getOrNull(index) is LightAudioSource.UrlSource
        if (_buffering.value) bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
    }

    /** When the start now buffering began, so its limit counts from then — see [bind]. */
    private var bufferingSinceMs = 0L

    /** Bumped per seek, so the check an earlier one left can't say "Buffering…" for this one. */
    private var seekBufferingGeneration = 0

    /**
     * A seek that has to fetch before it can play on says so, as a start does:
     * "Buffering…" where the artist is, until sound comes back — see
     * [heardSound], which clears it the same way. Only once the wait outlasts
     * [SEEK_BUFFERING_GRACE_MS], so a seek into what is already buffered doesn't
     * flash it, and never for a file on the phone, which has nothing to fetch.
     */
    private fun bufferAfterSeek(p: LightAudioPlayer) {
        if (!p.playWhenReady) return
        if (queuedSources.getOrNull(_index.value) !is LightAudioSource.UrlSource) return
        val generation = ++seekBufferingGeneration
        scope.launch(Dispatchers.Main.immediate) {
            delay(SEEK_BUFFERING_GRACE_MS)
            if (generation != seekBufferingGeneration || player !== p || isCasting) return@launch
            if (p.isPlaying.value || !p.playWhenReady) return@launch
            bufferingSinceMs = android.os.SystemClock.elapsedRealtime()
            _buffering.value = true
        }
    }

    /** Bumped by anything that supersedes a pending stream decision — see [withStreamDecision]. */
    private var decisionGeneration = 0

    /** The decision [withStreamDecision] is still waiting on, or 0 — see [release]. */
    private var awaitedDecision = 0

    /**
     * Whatever start was pending is off: a pause, a stop, a cast taking over.
     * A play() deferred behind the server's decision must not land after one
     * of those — under a cast it would be a second song out of the phone's
     * own speaker — and nothing should go on waiting for a stream that is no
     * longer being started.
     */
    private fun supersedeStart() {
        decisionGeneration++
        _buffering.value = false
    }

    /**
     * Where the last automatic skip landed. If *that* track is refused too,
     * the rules are not what the skip believed — a link that moved with no
     * callback yet, a server that answered and then didn't — and skipping
     * again would march through the queue, rebuilding it at every row, and
     * lose the user's place. One skip; a second refusal in a row parks.
     * Cleared once something actually plays.
     */
    private var lastSkipTarget: Int = -1

    /**
     * Tracks whose stream failed, which play from disk instead.
     *
     * Narrower than [forceOffline], and the right size for a server that is
     * plainly there but wouldn't serve *this* song: one track goes to its
     * downloaded copy and everything else still streams.
     */
    private val streamFailed = mutableSetOf<String>()

    /**
     * Songs whose original the server failed on its usual address, which are
     * fetched from the other address it has for the same file — see
     * [playOriginalElsewhere]. By source as well as id, since two servers can
     * each have a song of the same id. Kept for as long as the process: a file
     * the server wouldn't serve the usual way on one queue won't on the next.
     */
    private val originalElsewhere = mutableSetOf<Pair<String, String>>()

    /** This song's entry in [originalElsewhere]: the source playing it, and its id there. */
    private fun Track.originalKey(): Pair<String, String> = App.source.value.id to id

    // Queue order captured when shuffle was switched on, so switching it back off
    // can restore it.
    private var preShuffleOrder: List<Track>? = null

    // --- Where the sound goes ------------------------------------------------

    private val _active = MutableStateFlow<ActiveOutput?>(null)

    /** The output that has the sound, or null while it comes out of this phone. */
    val active: StateFlow<ActiveOutput?> = _active

    /** True while audio is going somewhere else instead of this device. */
    val isCasting: Boolean get() = _active.value != null

    /**
     * Where the controls go while the sound is elsewhere, or null for this
     * phone — see [OutputControls]. Every control asks this once and falls
     * through to the phone's own code when it is null.
     */
    private val remote: OutputControls? get() = _active.value?.output

    /** Each family's output, made the first time it is asked for and kept: one per family per process. Main only. */
    private val outputs = mutableMapOf<String, Output>()

    private fun outputOf(family: OutputFamily): Output = outputs.getOrPut(family.id) { family.create(host) }

    /**
     * True once [bind] has attached the audio stack.
     *
     * Restoring the last queue needs a player, and does nothing without one —
     * see [restoreState], which has no second chance. Boot launches that restore
     * on another thread and only then binds, so the two really are in a race,
     * and this is how the restore waits for its turn.
     */
    private val _bound = MutableStateFlow(false)
    val bound: StateFlow<Boolean> = _bound

    /**
     * The collectors [attachHandle] started on the player handle's own flows,
     * and its wait for the handle to connect, which starts most of them.
     * A released handle's flows simply stop, so without this they would sit on
     * it for the life of the process, one more set with every reopen. They
     * are cancelled with the handle, in [release], [handOver] and [handleLost].
     */
    private var handleJob: Job? = null

    /**
     * Everything else [bind] started: the collectors of this controller's own
     * state and the app's, and the loops. These outlive the handle on purpose
     * — the detached service goes on playing with the screens gone, and the
     * snapshot, the link poll and the buffering limit go on with it. The
     * heartbeat stays too, but says nothing until a handle is connected
     * again: without one there is nothing true to say. The next bind replaces
     * them rather than adding a second set beside them. A handover leaves
     * them be: none of them holds the handle.
     */
    private var bindJob: Job? = null

    /**
     * A finished queue goes back to its first track and stays stopped.
     *
     * ExoPlayer leaves a queue that has run out parked on the last song at its
     * very end, so the next press of play replays that song rather than the
     * record — and the player sits showing the last track as if it were about to
     * start. Winding back to the top says what actually happened: this is over,
     * and here is where it begins again.
     *
     * Repeat is not this function's business: with TRACK or QUEUE the player
     * loops and never reaches an end to notice.
     *
     * The SDK does not surface `STATE_ENDED` — see LightAudioPlayer, which keeps
     * it to itself — so this reads the same evidence Media3 would: playback
     * stopped, on the last item, with the position at its duration. The slack is
     * small enough that pausing by hand a second from the end is not mistaken
     * for the queue running out.
     *
     * [hasPlayed] is the other half of that inference and the important half: a
     * queue is only finished if it ran. Restoring cues the last track up and
     * pauses (see [restoreState]), which is a stop at the end of the last item
     * without a note having been played — and rewinding *that* would throw away
     * the track the app was reopened to continue.
     */
    private fun rewindIfQueueFinished(p: LightAudioPlayer) {
        if (isCasting) return
        if (!hasPlayed) return
        if (_repeatMode.value != RepeatMode.OFF) return
        val last = _queue.value.lastIndex
        if (last < 0 || _index.value != last) return
        // The player's own duration when it has one — but a chunked transcode
        // may never report any (it stays 0 for the whole track), which silently
        // disabled this for streamed queues: the queue stopped at the end of
        // the last track and just sat there. The library's number is the
        // fallback, compared in the track's own timeline the way the readouts
        // are.
        val reported = p.durationMs.value
        val endReached = if (reported > 0L) {
            p.positionMs.value >= reported - QUEUE_END_SLACK_MS
        } else {
            val known = _durationMs.value
            known > 0L && _positionMs.value >= known - QUEUE_END_SLACK_MS
        }
        if (!endReached) return
        // Paused first: a seek out of the ended state with playWhenReady still
        // true starts the queue over from the top instead of waiting there.
        //
        // On Main, because this is reached from the isPlaying collector, which
        // runs on Default: ExoPlayer's looper is Main and it throws rather than
        // tolerating anything else — every other player call in this class
        // places itself for that reason, and this one didn't, so reaching the
        // end of the last track killed the app. The rest is set here rather
        // than inside, so a second emission arriving before that block runs
        // can't queue a second rewind behind it.
        //
        // The listening is over, so its session is too. Nothing used to say
        // so, and the last song sat on Plex's dashboard as playing until it
        // timed out. Said before the player moves: the first song, cued in its
        // place, is a stream the server may be asked for before it hears this.
        sessions.close()
        scope.launch(Dispatchers.Main.immediate) {
            // Released while this was on its way: a gone handle throws. One
            // that replaced it steers the same service, which still has to be
            // told what the state below already says.
            val cur = player ?: return@launch
            cur.pause()
            cur.seekToDefaultPosition(0)
        }
        _index.value = 0
        _positionMs.value = 0L
        hasPlayed = false
    }

    /**
     * Attach the audio stack from the activity that is starting: [bind] the
     * first time, and again after a back-out released the handle.
     *
     * A handle still held means the activity that made it is finishing or
     * already gone. The activity is single-task and the launcher brings a live
     * one forward, so a second boot is Android having destroyed the first
     * while the process lived on, or relaunching it for a configuration change.
     * Either way that handle is stranded: Android drops the dead activity's
     * binding, nothing tells the SDK, and the controller inside still names
     * that activity, so its eventual release is the crash LateUnbindGuard
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

    /**
     * Swap the stranded handle for one made from the new activity, and change
     * nothing else. Main only: it runs inside the new activity's onCreate.
     *
     * Not a release followed by a bind. [release] is leaving: it ends a Plex
     * cast's steering, stops a DLNA renderer, closes the server's session and
     * drops a start still waiting on the server, and a new activity means none
     * of that. The bound loops keep running, and no play, pause or seek is
     * sent, so a playing service goes on playing and a paused one stays where
     * it was. [decisionGeneration] is left alone too: a press still waiting on
     * the server starts whatever [player] is by the time the answer comes, so
     * it lands on the new handle and waits there for it to connect.
     *
     * The old handle goes first, because the SDK allows one at a time and
     * won't make the next until the last is closed, which its release does
     * before returning. Then the new one, in the same turn on Main: its
     * controller binds the service at once, where the old one's unbind is only
     * posted, so the service is never left with nothing bound to it. That
     * unbind is the one that throws when the old activity is gone — straight
     * after this, or up to half a minute later if media3 still had commands in
     * flight.
     *
     * [player] goes from the old handle straight to the new one, never through
     * null: the restore and the queue's re-resolution read it from Default,
     * and a restore that saw none would give up for good.
     */
    private fun handOver(old: LightAudioPlayer, audio: LightAudio) {
        settleRestartCopy()
        android.util.Log.i(
            "AmpLife",
            "new activity: handle moved over " +
                "(playing=${_isPlaying.value} idx=${_index.value} casting=$isCasting)",
        )
        // What the screen shows as the handle changes, as bind reads it: the
        // re-cue in attachHandle puts it back if the service turns out to have
        // stopped, and only if nothing touched the queue while the new handle
        // connected.
        val carried = _queue.value
        val carriedIndex = _index.value
        val carriedPositionMs = _positionMs.value
        handleJob?.cancel()
        handleJob = null
        old.release()
        val p = try {
            audio.newPlayer(LightPlayerSpec(playback = LightAudioPlayback.Detached))
        } catch (e: Throwable) {
            // Thrown on, as a first bind's failure is: the activity doesn't
            // start without its player. What is left behind is no handle,
            // rather than a released one that throws at the next press.
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
     * Everything that lives on one handle: its callbacks, the collectors of its
     * own flows, and the re-cue or the landing once it connects. Run for each
     * new handle — by [bind], and by [handOver] when a new activity takes the
     * player over — and cancelled with it through [handleJob].
     *
     * The handle's flows are not read until it has connected. Until then they
     * hold placeholders — nothing playing, at 0:00, on no track — and the
     * collectors used to start at once and copy those over what the screen
     * carried: every reopen flashed 0:00, the button read paused, so a press
     * in that moment resumed instead of pausing, and on the last second of the
     * last track the "paused" reading queued a rewind that ran on the live
     * service when it connected. The SDK fills the flows from the service's
     * own state before it calls the handle ready, so collectors started after
     * that read the truth from their first value, and until then the screen
     * goes on showing what it had. [carried], [carriedIndex] and
     * [carriedPositionMs] are what it had when the handle was made.
     */
    private fun attachHandle(
        p: LightAudioPlayer,
        carried: List<Track>,
        carriedIndex: Int,
        carriedPositionMs: Long,
    ) {
        val handle = SupervisorJob(scope.coroutineContext[Job])
        handleJob = handle
        // Mirror the system media volume so the fader reflects it (and the
        // hardware volume keys, which drive the same stream).
        // The hardware rocker moves Android's own media volume, which while
        // casting controls a player that isn't making the sound. Forwarding it to
        // the renderer makes the buttons act on whatever is actually playing —
        // without intercepting the keys, which the tool sandbox can't do.
        //
        // The first reading is only the phone's level as it stands, not a
        // press. Under a cast the fader shows the speaker's or the Plex
        // player's own level, so a handle arriving mid-cast — a new activity
        // taking over — must not put the phone's over it, or send it to the
        // speaker. Only changes after that are forwarded.
        //
        // Started at once, unlike the collectors that wait for the connection:
        // this level is the system's, read when the handle is made, not a
        // placeholder for the service's. Held back, a cold start's fader would
        // sit at full until the service answered.
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
                // Released before it ever connected. Amp's own releases — a
                // back-out, a handover — cancel this first, so this is the SDK
                // giving up on a connection that failed.
                if (player === p) handleLost()
                return@launch
            }
            if (player !== p) return@launch
            // An error the service's player was already sitting in — raised
            // while no handle was listening, after a back-out or in a
            // handover's gap — is noted and left as it was: the error
            // collector acts on failures from here on. It can be
            // any age, and a player that failed while playing still wants to
            // play, so handled as a live failure it would skip ahead and start
            // the next track the moment the app was opened, which a reopen must
            // never do. The player shows as paused, as it always has here.
            p.error.value?.let {
                android.util.Log.i(
                    "AmpNet",
                    "connected onto a player error ${it.diagnostic} at idx=${it.itemIndex} — left as it is",
                )
            }
            // A reopen onto a service that stopped itself while the screens were
            // gone — paused, with no handle, for its idle limit — finds a player
            // holding nothing under a queue that still shows, and play then does
            // nothing at all. restoreState can't help: it runs once per process,
            // and this process never ended. So the queue goes back in where it was
            // left, cued and paused, the way a cold start restores it. Only in that
            // case: a service that kept its queue is where the truth is, and a
            // queue touched since, a cast or a park each prepare the player their
            // own way. On Main, where the player lives. A handover lands here as
            // well, when Android stopped the service while the old handle sat
            // stranded.
            //
            // Decided before the collectors start, so they find the queue back in
            // place and read its track and position rather than the empty
            // player's — the re-cue no longer passes through 0:00 either.
            val recue = carriedIndex in carried.indices && needsRecue(
                carriedOver = _queue.value === carried && _index.value == carriedIndex,
                playerIndex = p.currentMediaItemIndex.value,
                playerPlaying = p.isPlaying.value,
                casting = isCasting,
                parked = parked,
            )
            if (recue) {
                android.util.Log.i("AmpNet", "reopened onto an empty player — cueing idx=$carriedIndex again")
                pickedIndex = carriedIndex
                rebuildQueueAt(p, carried, carriedIndex, carriedPositionMs)
                // The service's player is a new one, so repeat is off in it
                // whatever the screen shows. Not casting, so this is the player only.
                setRepeat(_repeatMode.value)
                p.pause()
            }
            // A service that moved on while no handle was listening — played on
            // through the album, then paused — is on another track than the
            // screen carried, and part-way through it. Landed on here, before
            // the collectors start, so the index collector finds nothing to do
            // and can't zero the position behind the position collector's back,
            // and the duration collector's first reading is measured against
            // the right track with no offset left over from the last one.
            //
            // Not while parked. The service then sits on the track that failed,
            // at the point it failed, and the screen is where the user moved the
            // marker since — a step or a seek while waiting moves only that, and
            // resuming cues what it shows. Read once: a resume during the
            // collectors' start would otherwise change the answer half-way.
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
            // Now the service can be seen again, the server hears where it is.
            // A back-out closed the session while the service went on playing
            // (see release); a reopen onto that music opens it again, on the
            // song actually playing and where it has got to in it, so that it
            // is there to be stopped later.
            followCurrentSong(atMs = p.positionMs.value + streamOffsetMs)
            // A download finishing, the rules or the proxy moving while the
            // handle connected each asked for the sources to be looked at again,
            // and were turned away one of two ways: by reresolveQueue's own wait
            // for the connection, or, when the ask read the old handle just
            // before a handover replaced it, on reaching Main, where its edits
            // were dropped, the memo put back to what the service holds, and
            // the ask repeated into that same wait. Either way this pass is what
            // sends them. A re-cue has just built the queue from fresh answers
            // and needs nothing more.
            if (!recue) reresolveQueue()
        }
    }

    /**
     * The handle was released before it ever connected: the SDK gives up on a
     * connection that fails and releases the handle itself. Kept in [player],
     * it threw at the next press ("has been released"), on Main, taking the
     * process down. Dropped instead, the controls do nothing until a new
     * activity, or a back-out and reopen, makes a new one.
     */
    private fun handleLost() {
        android.util.Log.w("AmpLife", "player handle released before it connected — dropped")
        handleJob?.cancel()
        handleJob = null
        player = null
        handleOwner = null
    }

    /**
     * The collectors of a connected handle's own flows, on [handle] — see
     * [attachHandle] for why not before.
     *
     * [heldByPark]: the handle connected while playback was parked, so the
     * first track and position it reports are the failed track's, which the
     * screen's marker deliberately left — see the landing in [attachHandle].
     * That first reading of each is passed over and everything after it is
     * followed, so a skip that reaches the session directly, or the resume's
     * own rebuild, still moves the screen.
     */
    private fun collectHandle(p: LightAudioPlayer, handle: Job, heldByPark: Boolean) {
        // A failure is the player going from no error to one. The flow also
        // sends an error again when the queue moves under it, which is the
        // same failure, and a retry clears it first, so a second failure
        // counts again. The one already there at connect was dealt with in
        // attachHandle.
        scope.launch(handle + Dispatchers.Main.immediate) {
            var last = p.error.value
            p.error.collect { error ->
                if (error != null && last == null) playerFailed(p, error)
                last = error
            }
        }
        // While casting, the renderer is the source of truth for these — the local
        // player sits paused and would otherwise report position 0 / not-playing
        // straight over the cast state.
        scope.launch(handle) {
            p.isPlaying.collect { playing ->
                if (isCasting) return@collect
                _isPlaying.value = playing
                if (playing) {
                    heardSound()
                } else if (hasPlayed) {
                    rewindIfQueueFinished(p)
                }
            }
        }
        // A server-seeked stream starts at 0:00 of a shorter file, so both
        // readouts are shifted back into the track's own timeline.
        scope.launch(handle) {
            var snapshot = heldByPark
            p.positionMs.collect {
                if (snapshot) {
                    snapshot = false
                    return@collect
                }
                if (!isCasting) {
                    // Into the restart copy and not yet settled: the copy is the
                    // song from its start, and the remainder's offset is over.
                    val intoCopy = restartCopyAt >= 0 && p.currentMediaItemIndex.value == restartCopyAt
                    _positionMs.value = it + if (intoCopy) 0L else streamOffsetMs
                    noteProgress(it)
                }
            }
        }
        scope.launch(handle) {
            p.durationMs.collect { reported ->
                if (isCasting) return@collect
                // What the library says the track runs to, which is a real number
                // even before a single byte has arrived.
                val current = _queue.value.getOrNull(_index.value)
                val known = current?.durationMs ?: 0L
                // Playing off the disk, rather than off the server.
                val fromFile = current?.id?.let { localFiles.containsKey(it) } == true
                // The server took the seek request and ignored it.
                //
                // Navidrome only honours timeOffset while it is actually
                // transcoding. Ask it for a format the file already is — mp3
                // for an mp3 — and it serves the file whole and drops the
                // offset, so the audio restarts from the beginning while the
                // readouts, shifted by streamOffsetMs, insist the seek worked.
                //
                // A seeked stream's own duration is the *remainder*; this one's
                // is the whole track, which is how the two are told apart. The
                // file is a real file with a seek table, so the player can
                // finish the job itself.
                //
                // Not the restart copy, which is the whole song on purpose and
                // starts at its beginning: the remainder's offset is over the
                // moment it begins — see restartCopyAt. Read as an ignored
                // offset, it sent the copy to the seek point, and repeat-one
                // played the tail a second time.
                val intoCopy = restartCopyAt >= 0 && p.currentMediaItemIndex.value == restartCopyAt
                val offset = if (intoCopy) 0L else streamOffsetMs
                if (offset > 0L &&
                    known > 0L &&
                    reported >= known - IGNORED_OFFSET_SLACK_MS
                ) {
                    val target = offset
                    streamOffsetMs = 0L
                    // Whichever handle holds the service by then: the offset
                    // has already gone from the readouts, so the seek can't be.
                    scope.launch(Dispatchers.Main.immediate) { player?.seekTo(target) }
                    _durationMs.value = reported
                    return@collect
                }
                _durationMs.value = when {
                    // A transcode arrives chunked, with no length to derive a
                    // duration from — Plex streams every format but the original
                    // that way. The player reports nothing, and without this the
                    // bar has a position and no end to measure it against.
                    reported <= 0L -> known
                    // A server-seeked stream is a shorter file starting at 0:00,
                    // so its own duration is the remainder, not the track's.
                    offset > 0 -> known.takeIf { it > 0L } ?: (reported + offset)
                    // A downloaded file's length is the server's to state, not
                    // the player's to guess. Plex transcodes mp3 as a stream, so
                    // what lands on disk has no Xing header and ExoPlayer falls
                    // back to size ÷ the first frame's bitrate — which on a
                    // variable-rate encode is wrong by however quiet that frame
                    // happened to be. "I Want You" is 3:07 and was reported as
                    // 22:57: a 235 kbps file read as though it were 32. The
                    // library's number came from the server, which has the whole
                    // file and no reason to guess.
                    fromFile && known > 0L -> known
                    else -> reported
                }
                // AmpDur: temporary — chasing a downloaded track whose position
                // ran past its end with no sound (2026-09-02).
                android.util.Log.i(
                    "AmpDur",
                    "duration reported=$reported known=$known off=$streamOffsetMs " +
                        "file=$fromFile -> ${_durationMs.value}",
                )
            }
        }
        // ReplayGain: the playing track's measured gain becomes the player's
        // own volume. Attenuate-only (clamped at unity) — loud masters come
        // down toward the reference and quiet ones play untouched, so no
        // boost can ever clip. Sources that measure nothing leave it at 1.
        // Not applied while casting: the renderer plays its own copy.
        //
        // No index is no answer. A handle reads -1 until it connects, and when
        // this ran from the start, turning that into unity overwrote the gain
        // the handle was seeded with — the SDK hands it to the playing service
        // at connect, so the track jumped to full level for a moment. It waits
        // for the connection now, but an empty player reads -1 too, and has no
        // track whose gain could be the right one. Nothing is said until there
        // is a track.
        scope.launch(handle) {
            combine(p.currentMediaItemIndex, _queue, replayGain) { idx, q, on ->
                if (idx < 0) return@combine null
                // The restart copy is the song in the slot before it.
                val slot = if (restartCopyAt in 0..idx) idx - 1 else idx
                val db = if (on) q.getOrNull(slot)?.gainDb else null
                db?.let { 10.0.pow(it / 20.0).toFloat().coerceAtMost(1f) } ?: 1f
            }.filterNotNull().distinctUntilChanged().collect { factor ->
                withContext(Dispatchers.Main.immediate) {
                    // A handle that has since been replaced is not the one the
                    // service listens to, and its number must not be the one
                    // carried onto the next.
                    if (player !== p) return@withContext
                    p.playbackGain = factor
                    appliedGain = factor
                }
            }
        }
        scope.launch(handle) {
            var snapshot = heldByPark
            p.currentMediaItemIndex.collect { idx ->
                if (isCasting) return@collect
                // Every change seen here is a track starting from its own
                // beginning. The one a handle finds at connect, which may be
                // part-way through, attachHandle has already landed on — or,
                // parked, deliberately not.
                if (snapshot) {
                    snapshot = false
                } else if (restartCopyAt >= 0) {
                    // The remainder ending into the whole song queued after it
                    // is the song starting over, not a new track — see
                    // restartCopyAt. Anywhere else, the queue goes back in step
                    // first, and a later slot is one less on the screen.
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
                announceNowPlaying()
            }
        }
    }

    /**
     * The player is on [idx], which is not the track the screen shows: a
     * track change, or, from [attachHandle], a service that moved on while no
     * handle was listening. [startMs] is where in the track that leaves
     * playback: 0 for a change as it happens, the service's own reading for
     * the one found at connect.
     */
    private fun landedOn(p: LightAudioPlayer, idx: Int, startMs: Long) {
        // AmpDur: temporary — see the duration collector. What the player
        // landed on, against what the library says it should be.
        val landed = _queue.value.getOrNull(idx)
        android.util.Log.i(
            "AmpDur",
            "track pIdx=$idx uiIdx=${_index.value} id=${landed?.id} " +
                "\"${landed?.title}\" libDur=${landed?.durationMs} " +
                "pDur=${p.durationMs.value} off=$streamOffsetMs at=$startMs " +
                "local=${landed?.id?.let { localFiles[it] }?.let { (f, fmt) ->
                    "${f.name} ${f.length()}B ${fmt.id}"
                } ?: "stream"}",
        )
        // Gapless into the next track: sound never stopped, so there is no new
        // "playing" for the isPlaying collector to clear this on.
        if (p.isPlaying.value) _buffering.value = false
        // Landed somewhere other than where the user pointed: that request is
        // history, and must not decide a later refusal.
        if (idx != pickedIndex) pickedIndex = -1
        _index.value = idx
        // A new track is a fresh stream from its own beginning — the position
        // as much as the offset. Left standing, the position stayed the
        // *outgoing* track's until the player emitted its first reading for
        // this one, and everything that read it in between got that: the
        // readout showed 5:23 against a 3:52 track, and the timeline sent the
        // same number under the new track's id and duration. Plex
        // answered 400 "Time value greater than duration" where the new track
        // was shorter, and where it was longer took the number and wrote it in
        // as a resume point.
        //
        // Safe to clear here: the paths that legitimately start a track
        // part-way — a restored queue, a server seek — set _index before the
        // player moves, which is what keeps them out of this block at all. See
        // rebuildQueueAt.
        //
        // The track found at connect is the one exception, and it comes with
        // its own position: the SDK fills the index and the position from the
        // same snapshot of the service before it calls the handle ready, so
        // the two describe the same track. Zeroed instead, a reopen onto a
        // service that had moved on and then paused showed 0:00 for good — a
        // paused position never changes, so it is never said again — and that
        // 0 was saved, restored after a process death, and read by previous()
        // as the start of the track.
        streamOffsetMs = 0
        _positionMs.value = startMs
        // Leaving a server-seeked remainder is the moment to put the whole song
        // back in its slot, so any later visit — a repeat-all wrap, a tap on
        // the queue row — plays the track and not the tail the last seek left
        // behind.
        offsetItemTrackId?.let { staleId ->
            offsetItemTrackId = null
            val q = _queue.value
            if (q.getOrNull(idx)?.id == staleId) {
                // Arrived *onto* a stale remainder (a wrap over a slot that
                // never got restored): rebuild it live.
                scope.launch(Dispatchers.Main.immediate) { seekTo(0) }
            } else {
                val stubIdx = q.indexOfFirst { it.id == staleId }
                val original = q.getOrNull(stubIdx)
                if (stubIdx >= 0 && original != null) {
                    scope.launch(Dispatchers.Main.immediate) {
                        // A handle that replaced this one steers the same
                        // queue, where the stub still is.
                        val cur = player ?: return@launch
                        cur.replaceMediaItem(stubIdx, original.toAudioItem())
                    }
                }
            }
        }
        // Seeded from the library rather than waited for: a chunked stream may
        // never report a duration at all, and the player won't emit again to
        // give the duration collector a second go.
        _queue.value.getOrNull(idx)?.durationMs
            ?.takeIf { it > 0L }
            ?.let { _durationMs.value = it }
        // The server hears of the new song from the index having moved — see
        // followCurrentSong, which every change of song reaches the same way.
    }

    /** Attach the audio stack from the current activity, with no handle held — [attach] decides. */
    private fun bind(audio: LightAudio) {
        // A reopen. The last bind's collectors are still running, and left
        // there they would run beside this one's — two heartbeats, two
        // snapshots, two chances at every scrobble. The fresh set below picks
        // up everything they watched from where it stands now.
        bindJob?.cancel()
        val bound = SupervisorJob(scope.coroutineContext[Job])
        bindJob = bound
        // What this controller carried across a reopen: the re-cue in
        // attachHandle puts it back if the service came back empty, and only
        // if nothing touched the queue while the handle connected.
        val carried = _queue.value
        val carriedIndex = _index.value
        val carriedPositionMs = _positionMs.value
        // Detached: playback lives in the SDK's own service, so it survives the
        // screen going off — and the activity going away — officially.
        val p = audio.newPlayer(LightPlayerSpec(playback = LightAudioPlayback.Detached))
        p.playbackGain = appliedGain
        player = p
        handleOwner = audio
        attachHandle(p, carried, carriedIndex, carriedPositionMs)
        // Anything that changes what plays next — an edit, a jump, a repeat mode —
        // has to reach an output that is holding a URI we chose earlier.
        // Debounced because a single edit moves several of these at once.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch(bound) {
            combine(_queue, _index, _repeatMode) { _, _, _ -> Unit }
                .debounce(UPCOMING_DEBOUNCE_MS)
                .collect { remote?.upcomingChanged() }
        }
        // The server answering again is the signal to stop pinning playback to
        // local files; the next track resolves normally.
        scope.launch(bound) {
            App.serverReachable.collect { reachable ->
                if (reachable) {
                    forceOffline = false
                    retriedTrackId = null
                    streamFailed.clear()
                }
            }
        }
        // The connectivity callback hears networks come and go, but not the
        // phone moving its default from a weak Wi-Fi to cellular while both
        // stay up — and that is the change that decides which format the next
        // track is asked for, and whether a parked one may resume. So while
        // sound is coming out, or being waited for, the system is asked
        // directly every few seconds: two binder reads, on a CPU that is
        // awake for the music anyway. Idle, nothing runs.
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
        // Nothing may wait on "buffering" for ever: a start that produced
        // neither sound nor an error in this long is not holding anyone back.
        scope.launch(bound) {
            _buffering.collectLatest { on ->
                if (on) {
                    // Counted from the start, not from when this collector
                    // first saw it: a reopen replaces the collector mid-start,
                    // and a fresh 45 s from there would hold everything longer.
                    val elapsed = android.os.SystemClock.elapsedRealtime() - bufferingSinceMs
                    delay(BUFFERING_LIMIT_MS - elapsed)
                    _buffering.value = false
                }
            }
        }
        // One collector for everything the rules can change — the link, its
        // price, the mode, the server — so playback hears of any of them in
        // the same moment the library and the queue view do. See LinkRules.
        scope.launch(bound) {
            App.rules.collect { rules ->
                unmetered = rules.unmetered
                if (rules.mayStream) {
                    // Streaming is allowed again, so nothing learnt while it
                    // wasn't should outlive that: the pin to local files, the
                    // tracks whose stream failed, the one-retry marker. These
                    // used to wait for "reachable" to go false and come back —
                    // which a ping that simply keeps succeeding never does.
                    forceOffline = false
                    retriedTrackId = null
                    streamFailed.clear()
                }
                reresolveQueue()
                val out = remote
                if (!rules.mayUseLan && out != null && out.needsLan) {
                    // Off Wi-Fi the speaker is out of reach: the cast is over
                    // here, whatever the speaker goes on doing at home. Nothing
                    // is sent to say so — there is nobody to hear it — and
                    // nothing starts playing on the phone: leaving the house
                    // is not a press of play.
                    android.util.Log.i(out.logTag, "Wi-Fi gone — cast dropped")
                    withContext(Dispatchers.Main.immediate) {
                        if (remote?.needsLan == true) dropOutput(Disconnect.LinkLost)
                    }
                }
                if (rules.mayStream) {
                    cueIfParked()
                } else {
                    // Still parked, possibly for a different reason now: keep
                    // the words on the screen true. On Main, where every other
                    // writer of this is — a check-then-set here could re-park a
                    // player the user un-parked in between.
                    withContext(Dispatchers.Main.immediate) {
                        // Only a wait that is *about* the link is reworded by
                        // it; a file that won't decode stays what it says.
                        if (parked && !parkedOnTheTrackItself) {
                            _waitingFor.value = rules.waitingFor ?: _waitingFor.value
                        }
                    }
                }
            }
        }
        // Shared with the library rather than queried again here — one read of
        // the downloads table feeds both. It also fixes a subtler thing: this
        // used to resolve `dao` once, at bind, and so stayed subscribed to
        // whichever database was current at boot. After a source switch the map
        // still described the *previous* source's downloads, whose track ids
        // match nothing in the new one, so downloaded audio quietly stopped
        // being used at all.
        scope.launch(bound) {
            App.library.downloadFiles.collect { rows ->
                localFiles = rows.mapNotNull { row ->
                    downloads.existing(App.source.value.id, row.fileName)?.let { file ->
                        row.trackId to (file to StreamFormat.fromId(row.format))
                    }
                }.toMap()
                reresolveQueue()
            }
        }

        // A play is submitted part-way through rather than at the start, so the
        // position is what decides when — see maybeSubmitPlay.
        scope.launch(bound) { _positionMs.collect { maybeSubmitPlay(it) } }
        // Snapshot the queue periodically rather than on every position tick: the
        // point is to survive a kill, and a few seconds of lost progress is a fair
        // trade against writing to DataStore once a second forever.
        scope.launch(bound) {
            while (true) {
                delay(SAVE_STATE_INTERVAL_MS)
                persistState()
            }
        }

        // Plex tears a session out of `/status/sessions` if nothing pings it
        // for a while — the same idea as a UPnP lease. Only while something is
        // actually playing: a paused session was already told once and doesn't
        // need repeating, and an idle queue has no session at all.
        //
        // And only while a handle is connected to the service. Without one —
        // a back-out released it, and detached audio goes on in the service —
        // nothing here can see what is playing: the track and the position are
        // frozen at the moment of leaving, and "playing" stays true after the
        // service has moved on or run out. The heartbeat used to go on saying
        // so every ten seconds for as long as the process lived, keeping a song
        // on Plex's dashboard, and a shared user's one stream taken, long after
        // the music had stopped. release closes the session instead, and the
        // reopen opens it again on what the service is really playing.
        //
        // On Main, where every change of queue and song is made, so a report
        // never reads a new queue's session against the old queue's song.
        scope.launch(bound) {
            while (true) {
                delay(TIMELINE_INTERVAL_MS)
                withContext(Dispatchers.Main.immediate) {
                    if (_isPlaying.value && player?.availability?.value == LightAudioPlayerAvailability.Ready) {
                        reportTimeline(TimelineState.PLAYING)
                    }
                }
            }
        }
        // The one place a change of song reaches the server, whichever way it
        // came about: a tap in another list, next or previous, gapless, a row
        // of the queue, a skip past a song that failed, a restored queue, the
        // playing song removed, the speaker moving on. Most of those set the
        // index themselves and never pass through landedOn, which is the only
        // place that used to say anything, so the song they left was never
        // stopped. The new queue's id is part of what is watched, so tapping
        // the same song in a new list is seen as the new session it is where
        // the server keeps one per song.
        //
        // On Main and not immediate: the collector runs after whatever set the
        // queue and the index has finished, and reads them as they stand then
        // — never a new queue under the old index.
        scope.launch(bound + Dispatchers.Main) {
            combine(_queue, _index, playbackSession) { _, _, _ -> }
                .collect { followCurrentSong() }
        }
        _bound.value = true
    }

    /** Write the queue, index and position so the next launch can pick them up. */
    private suspend fun persistState(final: Boolean = false) {
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        settings.saveQueue(tracks.map { it.id }, _index.value, _positionMs.value)
        pushQueueToServer(tracks, force = final)
    }

    /**
     * Hand the queue to the server, so another client can pick it up where this
     * one left it.
     *
     * Sent only when the queue or the track in it has actually changed, unlike
     * the local snapshot beside it: this is a network round trip, not a write to
     * a file on the phone. So the position that rides along is the one from the
     * moment the track changed — close enough to resume from, and exact when it
     * matters, because leaving forces one last push.
     */
    private suspend fun pushQueueToServer(tracks: List<Track>, force: Boolean) {
        val client = serverClient.value ?: return
        val at = _index.value.coerceAtLeast(0)
        val currentId = tracks.getOrNull(at)?.id
        // Trimmed to a window starting at whatever is playing. Subsonic takes
        // the ids one URL parameter each, so Play All over a large library would
        // be a request tens of thousands of characters long — refused by the
        // server, or by anything proxying it, and silently at that. What is
        // ahead of you is the part another device can use anyway.
        val ids = if (tracks.size <= MAX_SAVED_QUEUE) {
            tracks.map { it.id }
        } else {
            tracks.subList(at, minOf(at + MAX_SAVED_QUEUE, tracks.size)).map { it.id }
        }
        val key = 31 * ids.hashCode() + currentId.hashCode()
        if (!force && key == lastSavedQueueKey) return
        lastSavedQueueKey = key
        runCatching { client.savePlayQueue(ids, currentId, _positionMs.value) }
    }

    /**
     * Reload the queue saved by a previous run, ready but **paused**.
     *
     * Resolving ids against the cached library means a track deleted server-side
     * simply drops out. The index is remapped onto whatever survived so the right
     * song is still cued rather than an arbitrary neighbour.
     *
     * [library] is asked for only once the waits below are over, so none of
     * them holds a list a write may have replaced in the meantime.
     */
    suspend fun restoreState(library: () -> List<Track>) {
        if (player == null || _queue.value.isNotEmpty()) return
        // Wait for the downloads to be known before deciding anything.
        //
        // Every track resolves to a file or a URL exactly once, when the queue
        // is built, and the answer is then fixed for as long as that queue
        // lives. Deciding before the database has answered means deciding with
        // no downloads on record, so a fully downloaded library restores as a
        // queue of streams — which on a bad connection stalls for a socket
        // timeout per track, and offline never plays at all.
        //
        // Bounded: a database that never answers must not cost the restore.
        withTimeoutOrNull(DOWNLOADS_READY_TIMEOUT_MS) {
            App.library.downloadsLoaded.first { it }
        }
        // And for the loopback door to say whether it is up, for the same
        // reason: the current track's address is fixed when the queue is built,
        // and built a moment too early it would be the server's own. Bounded;
        // the self-test takes a fraction of a second.
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
            // Nothing of our own to restore — a fresh install, or a cleared
            // cache. The server may still be holding what this account was
            // listening to somewhere else, which is the point of it keeping a
            // queue at all. Only consulted when this phone has nothing: a queue
            // the user can see here is never replaced by one they can't.
            serverClient.value?.let { runCatching { it.getPlayQueue() }.getOrNull() }
        } ?: return
        val byId = library().associateBy { it.id }
        val tracks = saved.trackIds.mapNotNull { byId[it] }
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == saved.currentId }.takeIf { it >= 0 } ?: 0

        val position = saved.positionMs
        // ExoPlayer's application looper is Main and it enforces that: touching the
        // player from this coroutine's Default dispatcher throws "Player is accessed
        // on the wrong thread" and takes the app down on launch. Every other caller
        // reaches setQueue from a composable, i.e. already on Main.
        withContext(Dispatchers.Main.immediate) {
            // Read here rather than before the hop: a new activity can take the
            // player over in between, and the handle it replaced is released.
            val p = player ?: return@withContext
            _queue.value = tracks
            preShuffleOrder = null
            lastScrobbledId = null
            nowPlayingId = null
            // Start position goes in at prepare time: seekTo() clamps to the
            // duration, which is still unknown this early, so it would land on 0.
            // The track the user was on when they left. If it can't be had
            // right now it waits here, where they left it — it is not skipped.
            pickedIndex = index
            rebuildQueueAt(p, tracks, index, position)
            // Deliberately not p.play(): restoring should cue the track up, not
            // start making noise on its own the moment the app is opened.
            p.pause()
        }
    }

    /**
     * The boot screen of [owner] is backing out: the app is being left. One
     * boot screen per activity, each with its own SealedLightActivity, so
     * [owner] names the activity.
     */
    fun release(owner: LightAudio) {
        // An older activity's last screen going, after a newer one took the
        // player over: the handle is the newer one's, and so is the leaving.
        if (handleOwner != null && owner != handleOwner) {
            android.util.Log.i("AmpLife", "an old screen closed; the current player is left alone")
            return
        }
        // A backed-out service plays on without anyone to hand repeat-one back
        // to it, so the copy goes now — see restartCopyAt.
        settleRestartCopy()
        // Whatever the sound went to stops being steered from here: a speaker
        // is told to stop, a Plex player is left playing — see each output's
        // disconnect.
        dropOutput(Disconnect.Leaving)
        // One last snapshot before the player goes: an orderly exit should not
        // lose the few seconds since the last tick. The session is closed out in
        // the same breath — stop() does it when the queue is emptied, but simply
        // leaving never went through stop(), so the server was left showing this
        // phone as playing until the session's lease ran out.
        //
        // Closed even though detached audio may play on in the service: from
        // here nothing can see what it plays, and a session left open would be
        // reported on by nobody — or by a heartbeat frozen on this song; see
        // bind. The reopen opens it again on whatever is playing by then. The
        // stop goes out under the session it was announced as, after anything
        // said before it, and is waited for: the process may not outlive this
        // by much. Waited for beside the snapshot rather than before it, so a
        // slow server can't spend the snapshot's time.
        sessions.close()
        runBlocking {
            withTimeoutOrNull(SAVE_STATE_TIMEOUT_MS) {
                val stopped = launch { timelineSent() }
                persistState(final = true)
                stopped.join()
            }
        }
        // A start still waiting on the server's decision is dropped: leaving
        // outranks it, and it would otherwise start whatever handle a reopen
        // has made by the time the answer comes. The track stays cued in the
        // service for the next press. (A handover, which is not leaving, lets
        // it through — see handOver.) Buffering goes off only if that start
        // was the reason for it; a stream already starting in the service
        // still holds the rest back.
        if (awaitedDecision != 0 && awaitedDecision == decisionGeneration) _buffering.value = false
        decisionGeneration++
        // The handle's own collectors go with it. The rest stay: see bindJob.
        handleJob?.cancel()
        handleJob = null
        // Straight away, on Main, whatever state the handle is in: the SDK
        // cancels a connection still in flight itself, and media3 1.10.1 has
        // exactly one unbind, guarded and nulled in place, so it cannot unbind
        // twice. (A deferred release was tried on 2026-09-22 against exactly
        // that imagined double unbind; the bytecode rules it out, and the
        // deferral only ran the release off the main thread.)
        player?.release()
        player = null
        handleOwner = null
    }

    /** Wait until every timeline report queued so far has been sent, or has failed. */
    private suspend fun timelineSent() {
        val done = CompletableDeferred<Unit>()
        if (timelineOut.trySend(TimelineSend.Flush(done)).isSuccess) done.await()
    }

    // --- Playback controls ---------------------------------------------------

    /** Replace the queue with [tracks] and start at [startIndex]; see [queueName]. */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0, name: String? = null) {
        if (tracks.isEmpty()) return
        // The same row of the same list, while it is still starting: leave it
        // alone. Rebuilding here throws away what has buffered and asks the
        // server for a new stream — on Plex a new transcode — so each
        // impatient tap on a slow link put the first sound further off.
        if (_buffering.value && !_shuffle.value) {
            val playing = _queue.value
            val at = _index.value
            // The same *list*, not just the same song: every list of five
            // hundred or more is the same length once capped, so the name and
            // the rows either side are compared too.
            if (name == _queueName.value &&
                tracks.getOrNull(startIndex)?.id == playing.getOrNull(at)?.id &&
                tracks.getOrNull(startIndex + 1)?.id == playing.getOrNull(at + 1)?.id &&
                tracks.getOrNull(startIndex - 1)?.id == playing.getOrNull(at - 1)?.id &&
                playing.size == minOf(tracks.size, MAX_QUEUE)
            ) {
                android.util.Log.i("AmpNet", "tap on a row that is already starting — left alone")
                return
            }
        }
        _queueName.value = name
        // A new queue is a new session where the server keeps one per song,
        // so the ids derived from it are new too — otherwise playing the same
        // track again an hour later would report itself as the same session
        // still going. Where the server keeps one for the whole listening
        // (Plex), that one carries on: see listeningSession.
        playbackSession.value = newSessionId()
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        if (_shuffle.value) {
            // Shuffled as positions, the tapped one first, so the window also
            // has an order to go back to when shuffle is turned off: its songs
            // as the list had them. setQueue drops the last queue's.
            val rest = IntArray(tracks.size - 1) { if (it < start) it else it + 1 }.apply { shuffle() }
            val picked = listOf(start) + rest.take(MAX_QUEUE - 1)
            setQueue(picked.map { tracks[it] }, 0)
            preShuffleOrder = picked.sorted().map { tracks[it] }
        } else {
            val (window, index) = capped(tracks, start)
            setQueue(window, index)
        }
    }

    /**
     * Trim a queue to something the player can actually hold.
     *
     * Media3 mirrors the whole timeline into a legacy MediaSession queue for
     * Bluetooth clients — and loads artwork for every entry while doing it. Handing
     * it eight thousand tracks ran the process out of memory in
     * `MediaSessionLegacyStub.updateQueue`, and building that many stream URLs
     * blocked the tap that started it for seconds.
     *
     * The window is centred on the track being played, keeping a little history
     * behind it, so "play the 3,000th song" still works — it just doesn't carry
     * the other seven thousand along.
     *
     * A copy either way, never the caller's list or a view of it. Play from the
     * Songs tab hands over the whole sorted library, and a sublist kept as the
     * queue kept that list — and every song in it — alive after the library
     * had moved on to its next read: two libraries in memory where one fits.
     */
    private fun capped(tracks: List<Track>, startIndex: Int): Pair<List<Track>, Int> {
        if (tracks.size <= MAX_QUEUE) return tracks.toList() to startIndex
        val first = (startIndex - QUEUE_LOOKBACK).coerceIn(0, tracks.size - MAX_QUEUE)
        return tracks.subList(first, first + MAX_QUEUE).toList() to (startIndex - first)
    }

    fun togglePlayPause() {
        remote?.let { out ->
            val wasPlaying = _isPlaying.value
            _isPlaying.value = !wasPlaying
            if (wasPlaying) out.pause() else out.play()
            reportTimeline(if (wasPlaying) TimelineState.PAUSED else TimelineState.PLAYING)
            return
        }
        val p = player ?: return
        // Parked: a play on the player as it stands is silence — see [play].
        if (parked) {
            play()
            return
        }
        // "Playing" includes a start that hasn't sounded yet: the button shows
        // play while a stream buffers, and a press then means stop trying, not
        // try again — which is what it used to do.
        val wasPlaying = _isPlaying.value || _buffering.value
        // Resuming needs the server's decision first, exactly as [play] does —
        // this is the button that actually starts a restored queue. The start
        // goes to whichever handle holds the service when the answer comes: a
        // new activity may have taken it over meanwhile, and the old handle
        // is released and throws.
        if (wasPlaying) {
            supersedeStart()
            p.pause()
        } else {
            starting(_index.value)
            withStreamDecision { player?.play() }
        }
        reportTimeline(if (wasPlaying) TimelineState.PAUSED else TimelineState.PLAYING)
    }

    fun play() {
        remote?.let { out ->
            _isPlaying.value = true
            out.play()
            reportTimeline(TimelineState.PLAYING)
            return
        }
        // Parked by the rule: a play on the player as it stands is silence
        // (it is in its error state). Prepare it again where it stood, or,
        // if the rule still says no, leave it parked and let the screen say so.
        if (parked) {
            // A press is also a reason to ask the server again, if it is the
            // server being waited for: the answer arrives through App.rules.
            if (!App.rules.value.serverReachable) App.reachability.check(delayMs = 0L)
            // And to try the door again, if it is the door that is closed.
            if (App.streamProxy.state.value is StreamProxy.State.Off) App.streamProxy.start(App.scope)
            resumeAfterWait(play = true)
            return
        }
        // The decision first, then the sound — see MusicServer.prepareStream.
        // Starting the player before it is settled is what left a restored
        // queue dead: the stream went out with no decision behind it and came
        // back 400, which then took the whole library offline.
        starting(_index.value)
        withStreamDecision { player?.play() }
        reportTimeline(TimelineState.PLAYING)
    }

    /**
     * The player failed on the current item. Main only.
     *
     * The player says what kind of failure it was. What the server said, if
     * anything, only the door saw — see StreamProxy.lastOutcome.
     */
    private fun playerFailed(p: LightAudioPlayer, error: LightAudioError) {
        _buffering.value = false
        settleRestartCopy()
        val source = queuedSources.getOrNull(_index.value)
        val fromFile = source is LightAudioSource.FileSource
        val network = error.kind == LightAudioErrorKind.Source
        val outcome = _queue.value.getOrNull(_index.value)?.let { App.streamProxy.lastOutcome(it.id) }
        val status = (outcome as? StreamProxy.Outcome.Answered)?.status
        android.util.Log.i(
            "AmpNet",
            "player error ${error.diagnostic}${status?.let { " (HTTP $it)" }.orEmpty()} " +
                "at idx=${_index.value} (${if (fromFile) "file" else "stream"})",
        )
        val serverAnswered = error.diagnostic == BAD_HTTP_STATUS
        if (fromFile || !network) {
            // Not a matter for the network at all: a file on the phone, or
            // audio that arrived and would not decode. Nothing about the
            // link or the server can change it, so it is neither retried
            // nor blamed on them — a local file once sat here saying
            // "Waiting for server". It says what it is, and playback moves
            // on if it was moving.
            blockedAtCurrent(because = if (fromFile) CANT_PLAY_FILE else CANT_PLAY_FORMAT)
        } else if (
            outcome == StreamProxy.Outcome.Refused ||
            !App.streamProxy.isOn ||
            (source as? LightAudioSource.UrlSource)?.url == StreamProxy.UNAVAILABLE_URL
        ) {
            // Not the server's failure and not the network's: the app's own
            // rule at the door, or no door to stream through yet. Nothing to
            // fall back to and nothing to latch — the server is not
            // unreachable, it is unasked.
            blockedAtCurrent()
        } else if (serverAnswered && status in 500..599 && playOriginalElsewhere(p)) {
            // Rebuilt on the other address the server has for the same
            // file. Only a failure there as well comes down to the path
            // below, which says so like any other refusal.
        } else {
            // A bad HTTP status is proof the server is *there*: it answered, it
            // just didn't like the request. Treating that as "unreachable" takes
            // the whole library down to downloads-only over one bad URL, and it
            // stays that way until something else proves otherwise.
            fallBackOffline(serverAnswered = serverAnswered)
        }
    }

    /** Whether [track] can play under the rules as they stand — for the screens. */
    fun isPlayable(track: Track): Boolean = isPlayable(track, App.rules.value)

    private fun isPlayable(track: Track, rules: LinkRules): Boolean =
        PhoneFiles.isPhoneFile(track.id) || localFiles.containsKey(track.id) ||
            (rules.mayStream && App.streamProxy.isOn)

    /**
     * The current track needs the server and can't have it. Main only.
     *
     * If the user asked for this track, stay on it and say why — they chose
     * it, and jumping elsewhere would be answering a different question. If
     * playback arrived here by itself *and was meant to be making sound*,
     * carry on with the next track that can play and leave this one where it
     * is: the queue is the same queue, it just has rows that have to wait, and
     * they play again the moment the rules allow. With nothing playable left,
     * or a player that was paused — a restored queue, a track the user had
     * stopped — park: a failure is never a reason to start making noise.
     *
     * Decided from [App.rulesNow], not the flow: this runs in the same breath
     * as the refusal, and the flow is a few hops behind it.
     */
    private fun blockedAtCurrent(because: String? = null) {
        _buffering.value = false
        val p = player ?: return
        // The renderer is playing, not this player, which only sits loaded so
        // that coming back is instant. Its failures move nothing.
        if (isCasting) return
        val tracks = _queue.value
        val at = _index.value
        val rules = App.rulesNow()
        // If the rules say streaming is fine and it still failed, it is the
        // server that isn't delivering — never "Wi-Fi", which would send the
        // user looking for the wrong thing.
        val reason = because ?: rules.waitingFor
            ?: if (App.streamProxy.isOn) LinkRules.WAITING_FOR_SERVER else STREAM_UNAVAILABLE
        val wanted = p.playWhenReady
        val next = when {
            !wanted || at == pickedIndex || at == lastSkipTarget -> null
            else -> nextPlayableAfter(at, rules)
        }
        android.util.Log.i(
            "AmpNet",
            "blocked at idx=$at picked=$pickedIndex wanted=$wanted → ${next ?: "park ($reason)"}",
        )
        if (next == null) {
            _waitingFor.value = reason
            _isPlaying.value = false
            // The server was told this song started — play() says so before
            // there is any sound — and nothing it can do will start it now,
            // so it is told it stopped. Otherwise its dashboard went on
            // listing the phone as playing a song that never played. Whatever
            // session is open, as it was announced: after a tap onto this song
            // that can still be the last song's.
            sessions.close()
            return
        }
        _waitingFor.value = null
        lastSkipTarget = next
        rebuildQueueAt(p, tracks, next, 0L)
        starting(next)
        p.play()
    }

    /** The next queue position after [from] whose track can play; wraps only under repeat-queue. */
    private fun nextPlayableAfter(from: Int, rules: LinkRules): Int? {
        val tracks = _queue.value
        val ahead = (from + 1..tracks.lastIndex).firstOrNull { isPlayable(tracks[it], rules) }
        if (ahead != null || _repeatMode.value != RepeatMode.QUEUE) return ahead
        return (0 until from).firstOrNull { isPlayable(tracks[it], rules) }
    }

    /**
     * Prepare the parked player again where it stood, and play if asked.
     * Main only, like [rebuildQueueAt].
     *
     * Asked of the rule first, not because the player wouldn't ask — it
     * would, at its connection, and refuse the same way — but so that a press
     * while the link is still metered costs no rebuild, no seek check and no
     * error, and the screen simply goes on saying what it said.
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
        if (!isPlayable(track, rules) || (!localFiles.containsKey(track.id) && !PhoneFiles.isPhoneFile(track.id) && !NetworkGate.isOpen())) {
            // Still not allowed. Say why as of now — the reason can have
            // changed since the park — and leave the player alone.
            _waitingFor.value = rules.waitingFor ?: _waitingFor.value
            return
        }
        // On the phone, but the stream would have been preferred and may not
        // be had: play the copy. Without this source() hands out the URL
        // again and the track parks with its file sitting on the disk.
        if (!rules.mayStream && localFiles.containsKey(track.id)) streamFailed += track.id
        _waitingFor.value = null
        // Whatever is prepared here was asked for — by a press, or by the
        // rules opening on the track the user was waiting on. If it is
        // refused after all, it parks again rather than skipping away.
        pickedIndex = index
        rebuildQueueAt(p, tracks, index, _positionMs.value)
        if (play) {
            starting(index)
            p.play()
        } else {
            p.pause()
        }
    }

    /**
     * The rule opened — Wi-Fi is back, or the mode changed — and playback is
     * parked on it: prepare the player where it stood, paused. Cued rather
     * than started, for the reason [restoreState] gives — the phone coming
     * back into Wi-Fi is not a press of play. One press then plays, from the
     * screen or a headset button, where a parked player answers a press with
     * silence.
     */
    private suspend fun cueIfParked() {
        if (!parked || isCasting || parkedOnTheTrackItself) return
        withContext(Dispatchers.Main.immediate) { resumeAfterWait(play = false) }
    }

    /**
     * Next/previous while parked: move the marker and try the neighbour the
     * way play would — a downloaded track plays, a stream is refused again
     * and the screen says so on the new row. Repeat's wrap is honoured, as a
     * cast's advance honours it; off the end, nothing.
     */
    private fun stepWhileWaiting(delta: Int) {
        val size = _queue.value.size
        if (size == 0) return
        val target = when {
            delta == 0 -> _index.value
            _repeatMode.value == RepeatMode.QUEUE -> ((_index.value + delta) % size + size) % size
            else -> _index.value + delta
        }
        if (target !in 0 until size) return
        goWhileParked(target)
    }

    /** [index] as the player will take it: wrapped under repeat-queue, otherwise as given. */
    private fun wrapped(index: Int): Int {
        val size = _queue.value.size
        return if (size > 0 && _repeatMode.value == RepeatMode.QUEUE) ((index % size) + size) % size else index
    }

    /** Move to [target] by hand while parked: a file plays, a stream parks there instead. */
    private fun goWhileParked(target: Int) {
        pickedIndex = target
        _index.value = target
        _positionMs.value = 0L
        resumeAfterWait(play = true)
    }

    /**
     * Run [start] once the server has agreed to serve the current track.
     *
     * Only for a streamed track: a file on the phone has no server to ask, and
     * making it wait would put a round trip in front of playing something that
     * is already here. Bounded, and it starts anyway on a timeout or a refusal —
     * a server that won't decide may still serve, and a silent player is the
     * worse failure.
     */
    private fun withStreamDecision(start: () -> Unit) {
        // Read from the queue itself: [currentTrack] is a derived flow and is
        // still the *previous* track in the instant after a new queue is set.
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
        // stream itself (see playerUrl) this is asked twice, which is harmless:
        // the same decision for the same song.
        val format = effectiveFormat()
        val sid = streamSessionIdFor(track.id, decidesAtDoor(client, track, format))
        scope.launch {
            withTimeoutOrNull(STREAM_DECISION_TIMEOUT_MS) {
                client.prepareStream(track.id, format, sid)
            }
            withContext(Dispatchers.Main.immediate) {
                if (awaitedDecision == generation) awaitedDecision = 0
                // Overtaken while the server was deciding — a pause, a stop,
                // another track, the handle released. That outranks this press.
                if (generation == decisionGeneration) start()
            }
        }
    }

    fun pause() {
        remote?.let { out ->
            _isPlaying.value = false
            out.pause()
            reportTimeline(TimelineState.PAUSED)
            return
        }
        supersedeStart()
        player?.pause()
        reportTimeline(TimelineState.PAUSED)
    }

    /**
     * Seek, by whichever route the source allows.
     *
     * A downloaded file seeks natively and exactly. A *transcoded* stream can't:
     * the server is piping a live ffmpeg encode with no seek table, so the
     * player can only guess a byte offset from an estimated length and then read
     * forward to reach it — which at 320 kbps means pulling megabytes before a
     * sound comes out, and landing somewhere approximate when it does.
     *
     * So the seek is handed to the server instead: the same track requested with
     * `timeOffset`, which starts the encode at that second. The stream then runs
     * from 0:00, and [streamOffsetMs] shifts the readouts back into the track's
     * own timeline.
     */
    fun seekTo(ms: Long) {
        remote?.let { out ->
            out.seekTo(ms)
            return
        }
        val p = player ?: return
        settleRestartCopy()
        val track = _queue.value.getOrNull(_index.value)
        if (track == null || !needsServerSeek(track)) {
            p.seekTo(ms)
            bufferAfterSeek(p)
            // Trust it, then check — see [verifyNativeSeek].
            if (track != null) verifyNativeSeek(p, track, ms)
            return
        }
        serverSeek(p, track, ms)
    }

    /**
     * Tracks whose native seek was tried and didn't land.
     *
     * Whether a stream can be seeked in the player is not something we can work
     * out in advance. "It's the original file, so it has a seek table" is true
     * of the file and says nothing about what arrives: a raw stream still needs
     * the server to answer byte ranges, and when it doesn't, the player reports
     * the position it was asked for while the audio plays on from the top.
     *
     * So it's measured instead of predicted — and remembered, so a track only
     * pays for the wrong guess once.
     */
    private val seekNeedsReload = mutableSetOf<String>()

    /** Bumped per seek, so a slow verify can't act on a stale target. */
    private var seekGeneration = 0

    /** True when the player can't be trusted to seek within this stream. */
    private fun needsServerSeek(track: Track, askDoor: Boolean = true): Boolean {
        if (track.source() is LightAudioSource.FileSource) return false
        if (track.id in seekNeedsReload) return true
        // A queue being rebuilt is about to fetch afresh: what the door saw
        // was the stream it held before.
        if (!askDoor) return effectiveFormat() != StreamFormat.RAW
        // Ask the door, which saw what actually arrived. Asking for mp3 and
        // getting mp3 means the server sent the file untouched — with a length
        // and byte ranges — and it then ignores timeOffset, because it is not
        // transcoding and has nothing to offset. Inferring "not RAW, therefore a
        // transcode" picks server-seeking for exactly the streams where only
        // native seeking works, and the failure is silent: the audio restarts
        // and the clock claims the seek landed.
        val answered = App.streamProxy.lastOutcome(track.id) as? StreamProxy.Outcome.Answered
        if (answered != null && answered.status in 200..299 && answered.lengthKnown) return false
        // Nothing fetched yet to ask about — fall back to the request we made.
        return effectiveFormat() != StreamFormat.RAW
    }

    /**
     * Check where a native seek actually landed, and reload if it missed.
     *
     * The player answers with the position it was told to go to, so its own
     * reading is worthless immediately. A moment later it reflects the stream,
     * and a stream that ignored the seek is back near the beginning.
     */
    private fun verifyNativeSeek(
        p: LightAudioPlayer,
        track: Track,
        target: Long,
        awaitStream: Boolean = false,
    ) {
        val generation = ++seekGeneration
        scope.launch {
            // A queue just built has no stream to report on yet, and until it
            // has one the player answers with the position it was asked for —
            // the check would pass on a seek about to be dropped. Wait for the
            // stream to declare itself, within reason.
            if (awaitStream) {
                withTimeoutOrNull(REBUILD_VERIFY_TIMEOUT_MS) { p.durationMs.first { it > 0L } }
            }
            delay(SEEK_VERIFY_MS)
            if (generation != seekGeneration) return@launch
            if (player !== p) return@launch
            if (_active.value != null) return@launch
            if (_queue.value.getOrNull(_index.value)?.id != track.id) return@launch
            val landed = p.positionMs.value + streamOffsetMs
            if (kotlin.math.abs(landed - target) <= SEEK_TOLERANCE_MS) return@launch
            android.util.Log.i("AmpSeek", "missed: wanted $target, landed $landed — reloading")
            seekNeedsReload += track.id
            serverSeek(p, track, target)
        }
    }

    private fun serverSeek(p: LightAudioPlayer, track: Track, ms: Long) {
        if (serverClient.value == null) return
        val target = ms.coerceAtLeast(0L)
        // With no estimated length, as every stream the player is handed —
        // see playerUrl. It matters even more here: an estimated length makes
        // ExoPlayer believe the transcode is bounded and range-seekable, and
        // its end-of-file probe then asks for bytes the stream doesn't have —
        // Navidrome answers 416 and playback errors. That was the restored
        // queue "acting weird", and every repeat wrap of an offset item dying.
        val url = playerUrl(track, effectiveFormat(), timeOffsetSeconds = (target / 1000).toInt())
        streamOffsetMs = target
        offsetItemTrackId = track.id.takeIf { target > 0L }
        // Only the playing item is replaced; the queue either side of it is
        // untouched, so a seek doesn't disturb what plays next.
        _positionMs.value = target
        val item = LightAudioItem(
            source = LightAudioSource.UrlSource(url),
            metadata = LightMediaMetadata(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
            ),
        )
        scope.launch(Dispatchers.Main.immediate) {
            // Released while the URL was being built: a gone handle throws.
            if (player !== p) return@launch
            // The slot is about to be addressed by position — see restartCopyAt.
            settleRestartCopy()
            // Intent, not the instantaneous state: isPlaying is false during a
            // rebuffer, and a seek issued in that window read it as "paused"
            // and left the replacement stream parked.
            val wasPlaying = p.playWhenReady
            val at = _index.value
            if (at < 0) return@launch
            // AmpSeek: temporary — chasing the repeat-one wrap of a server-seeked
            // remainder (2026-10-20). See restartCopyAt.
            android.util.Log.i(
                "AmpSeek",
                "server seek idx=$at to=$target repeat=${p.repeatMode} playing=$wasPlaying",
            )
            // Repeat-one is lifted for the swap. Taking the playing item out has
            // Media3 look for what plays after it, and under repeat-one that is
            // the same item, which is gone — so it gave up and went to the first
            // track of the queue, on the phone's side and the service's alike: a
            // seek in the third song of an album jumped to the first. With it off,
            // what follows is the replacement, put in just after the item it takes
            // over from.
            val repeatOne = p.repeatMode == Player.REPEAT_MODE_ONE
            if (repeatOne) p.repeatMode = Player.REPEAT_MODE_OFF
            // Replaced in place rather than removed and re-added. Taking the
            // playing item out moves the player's idea of the current index —
            // twice, once each way — and the collector above reads that as a new
            // track and clears streamOffsetMs. The clock then counts up from zero
            // while the audio plays from where it was asked to.
            p.replaceMediaItem(at, item)
            // The replacement is a stream that already starts at the target, so
            // it has to play from its own beginning: Media3 keeps the old
            // position when the item it replaces looks like the same one, which
            // would land us at the offset twice over. This sets the index the
            // player is already on, so nothing reads it as a track change.
            p.seekToDefaultPosition(at)
            // A remainder under repeat-one has the whole song queued after it,
            // and the player's repeat stays off for it — see restartCopyAt.
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
        p.addMediaItem(at + 1, track.toAudioItem())
        restartCopyAt = at + 1
        // AmpSeek: temporary — see serverSeek.
        android.util.Log.i("AmpSeek", "restart copy queued after idx=$at")
    }

    /**
     * Main only. The player's queue back in step with the screen's: the copy
     * goes — unless the remainder has already ended into it, and then the
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
        _queue.value.getOrNull(_index.value)?.durationMs?.takeIf { it > 0L }?.let { _durationMs.value = it }
        p.removeMediaItem(copy - 1)
        if (_repeatMode.value == RepeatMode.TRACK) p.repeatMode = Player.REPEAT_MODE_ONE
        // AmpSeek: temporary — see serverSeek.
        android.util.Log.i("AmpSeek", "restart copy took over at idx=${_index.value}")
        bufferAfterSeek(p)
    }

    /**
     * Main only. Repeat-one over a remainder with no copy behind it yet — after
     * an edit put the queue back in step, or repeat-one switched on after the
     * seek — gets one: see [restartCopyAt].
     */
    private fun armIfRemainder() {
        if (restartCopyAt >= 0 || isCasting || parked) return
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

    /** Stop playback and empty the queue. */
    fun stop() {
        supersedeStart()
        // The session that is open ends here, at the point playback reached —
        // under the id it was announced as, whatever has moved since.
        val stopping = _queue.value.getOrNull(_index.value)
        sessions.close(stopping?.id, _positionMs.value, _durationMs.value)
        // Whatever the sound went to is told to stop, and the queue it was
        // given goes with this one.
        dropOutput(Disconnect.Stopped)
        // The queue goes now, on whatever thread called: these are plain state
        // writes and need no looper, where the player's own calls do.
        //
        // Both inside the Main dispatch below, as they were, stop() returned
        // having merely *scheduled* the clear — and a source switch, which calls
        // this from a collector on Default, carried on to swap the client and
        // the database while the old queue was still there to be read. What read
        // it was reresolveQueue, rebuilding those tracks' URLs against the
        // server that had just arrived: Navidrome ids on Plex's transcoder,
        // refused four times over as the player retried. A queue belongs to the
        // source it was built from, and must not outlive the decision to leave.
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
        // And whatever plays next is a listening of its own, under a new id
        // where the server keeps one for the whole listening. Minted once the
        // queue is gone, so nothing reads the new id against the old songs.
        listeningSession = newSessionId()
        // Same rule as restoreState: ExoPlayer's looper is Main and it throws
        // rather than tolerating anything else. Unlike the rest of this class,
        // stop() is not only reached from a composable — a source switch calls it
        // from a collector on Default — so it has to place itself. `immediate`
        // means a caller already on Main is unaffected and still synchronous.
        //
        // The handle is read on Main, not before the hop: a new activity may
        // take the player over in between, and the old handle is released and
        // throws. Not dropped either — the service would go on playing the old
        // source's queue under an empty one on the screen.
        scope.launch(Dispatchers.Main.immediate) {
            val cur = player ?: return@launch
            settleRestartCopy()
            // Paused first. A stop alone leaves the player wanting to play,
            // and through the SDK's media controller it then never reports
            // that it stopped: its "playing" stays true, so the next song's
            // start is no change, nothing hears it, and the screen sat on
            // "Buffering…" with a play button over the music. A pause is
            // reported. It also means a new queue waits for its stream
            // decision before making a sound, as every start is meant to.
            cur.pause()
            cur.stop()
            cur.setMediaQueue(emptyList())
        }
    }

    fun next() {
        remote?.let { out ->
            out.next()
            return
        }
        settleRestartCopy()
        if (parked) {
            stepWhileWaiting(+1)
            return
        }
        pickedIndex = wrapped(_index.value + 1)
        // Only if it is meant to be making sound: next on a paused player moves
        // the marker and starts nothing, and would be left "buffering".
        if (player?.playWhenReady == true) starting(pickedIndex)
        player?.skipToNext()
    }

    /** Restart the current track if we're past the intro, otherwise go to the previous one. */
    fun previous() {
        remote?.let { out ->
            out.previous()
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
            // The controller's seek, not the player's. A restored or
            // server-seeked stream is a shorter file that begins mid-track, so
            // the player's 0 is that offset: the clock snapped back to the
            // offset instead of 0:00, and since that reads as >3s, every later
            // press restarted again — Previous could never reach the previous
            // track. seekTo(0) rebuilds such a stream at the track's real
            // beginning and clears the offset.
            seekTo(0)
        } else {
            p.skipToPrevious()
        }
    }

    /** Jump to an arbitrary queue position (tapping a queue row). */
    fun jumpTo(index: Int) {
        if (index !in _queue.value.indices) return
        remote?.let { out ->
            out.jumpTo(index)
            return
        }
        val p = player ?: return
        settleRestartCopy()
        if (parked) {
            // A parked player is in its error state: a seek moves the marker
            // and makes no sound. Prepare it on the row that was asked for.
            goWhileParked(index)
            return
        }
        pickedIndex = index
        p.seekToDefaultPosition(index)
        _index.value = index
        if (cannotStreamNow(index)) {
            blockedAtCurrent()
            return
        }
        starting(index)
        p.play()
    }

    // --- Queue editing (seamless) --------------------------------------------

    fun addToQueue(tracks: List<Track>) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        settleRestartCopy()
        _queue.value = _queue.value + tracks
        p.addItems(tracks.map { it.toAudioItem() })
        remote?.queueEdited(QueueEdit.Appended(tracks.map { it.id }))
        rearmSoon()
    }

    /**
     * Put these tracks immediately after the one playing.
     *
     * A track already in the queue is *moved* rather than copied: "play next" on
     * something queued for later means bring it forward, and inserting a second
     * copy would leave it to play twice.
     */
    fun playNext(tracks: List<Track>) {
        pickedIndex = -1 // indices are about to move under it
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        settleRestartCopy()
        // Where the next of them goes: straight after the playing track for
        // the first, then after the one before it, so an album played next
        // plays in its own order. Each put straight after the playing track,
        // as it once was, turned the album round.
        var slot = _index.value + 1
        val added = mutableListOf<String>()
        for (track in tracks) {
            val from = _queue.value.indexOfFirst { it.id == track.id }
            if (from < 0) {
                val at = slot.coerceIn(0, _queue.value.size)
                _queue.value = _queue.value.toMutableList().apply { add(at, track) }
                p.addMediaItem(at, track.toAudioItem())
                added += track.id
                slot = at + 1
                continue
            }
            if (from == _index.value) continue
            // Destinations are read after the removal, so a track pulled from
            // in front of the slot lands one place earlier than one pulled from
            // behind it.
            val to = if (from < slot) slot - 1 else slot
            moveInQueue(from, to)
            slot = to + 1
        }
        // One edit for the lot, in order: one each, every one "after the
        // playing track", turned the album round on an output too.
        if (added.isNotEmpty()) remote?.queueEdited(QueueEdit.PlayNext(added))
        rearmSoon()
    }

    /**
     * Keep what is playing, replace everything after it.
     *
     * For a radio started from the song already playing: the song carries on
     * untouched and the radio becomes what follows, in place of whatever was
     * queued. The queue's name changes with its contents — see [queueName].
     * One range replace rather than an edit per track: each edit is a queue
     * update that rebuilds the session queue, and a long queue has hundreds.
     */
    fun replaceUpcoming(tracks: List<Track>, name: String? = null) {
        val p = player ?: return
        settleRestartCopy()
        val q = _queue.value
        val cur = _index.value
        if (cur !in q.indices) {
            playQueue(tracks, 0, name)
            return
        }
        val droppedIds = q.drop(cur + 1).map { it.id }
        p.replaceRange(cur + 1, q.size, tracks.map { it.toAudioItem() })
        _queue.value = q.take(cur + 1) + tracks
        _queueName.value = name
        remote?.queueEdited(QueueEdit.UpcomingReplaced(droppedIds, tracks.map { it.id }))
        rearmSoon()
    }

    fun removeFromQueue(index: Int) {
        pickedIndex = -1 // indices are about to move under it
        val p = player ?: return
        settleRestartCopy()
        val q = _queue.value
        if (index !in q.indices) return
        val playingId = q.getOrNull(_index.value)?.id
        val goneId = q[index].id
        p.removeMediaItem(index)
        val newQueue = q.toMutableList().apply { removeAt(index) }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        remote?.queueEdited(QueueEdit.Removed(listOf(goneId)))
        rearmSoon()
    }

    /**
     * Drop several tracks from the queue at once.
     *
     * Removed back to front so each removal can't shift the indices of the ones
     * still to go, and the surviving list is computed here rather than read back
     * from the player — a snapshot taken mid-edit is how rows end up playing the
     * wrong track.
     */
    fun removeFromQueue(ids: Set<String>) {
        pickedIndex = -1 // indices are about to move under it
        val p = player ?: return
        settleRestartCopy()
        if (ids.isEmpty()) return
        val q = _queue.value
        val targets = q.indices.filter { q[it].id in ids }
        if (targets.isEmpty()) return
        val playingId = q.getOrNull(_index.value)?.id
        targets.sortedDescending().forEach { p.removeMediaItem(it) }
        val gone = targets.toSet()
        val goneIds = targets.map { q[it].id }
        val newQueue = q.filterIndexed { i, _ -> i !in gone }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        remote?.queueEdited(QueueEdit.Removed(goneIds))
        rearmSoon()
    }

    fun moveInQueue(from: Int, to: Int) {
        pickedIndex = -1 // indices are about to move under it
        val p = player ?: return
        settleRestartCopy()
        val q = _queue.value
        if (from !in q.indices || to !in q.indices || from == to) return
        val playingId = q.getOrNull(_index.value)?.id
        val movedId = q[from].id
        p.moveMediaItem(from, to)
        val newQueue = q.toMutableList().apply { add(to, removeAt(from)) }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        // An output is told what the track now follows — nothing when it
        // lands at the front.
        val afterId = newQueue.getOrNull(to - 1)?.id
        remote?.queueEdited(QueueEdit.Moved(listOf(movedId), afterId))
        rearmSoon()
    }

    /**
     * Move several tracks (by index) as a block to position [insertAt] among the rest of
     * the queue. Reconciled against the player one single-item move at a time, since it
     * has no batch API.
     */
    fun moveGroupInQueue(fromIndices: Set<Int>, insertAt: Int) {
        pickedIndex = -1 // indices are about to move under it
        val p = player ?: return
        settleRestartCopy()
        if (fromIndices.isEmpty()) return
        val q = _queue.value
        if (fromIndices.any { it !in q.indices }) return
        val playingId = q.getOrNull(_index.value)?.id
        val moving = q.filterIndexed { i, _ -> i in fromIndices }
        if (moving.size == q.size) return
        val remaining = q.filterIndexed { i, _ -> i !in fromIndices }
        val landsAt = insertAt.coerceIn(0, remaining.size)
        val newQueue = remaining.toMutableList().apply { addAll(landsAt, moving) }
        if (newQueue == q) return
        val working = q.toMutableList()
        newQueue.forEachIndexed { target, track ->
            val current = working.indexOfFirst { it === track }
            if (current != target) {
                p.moveMediaItem(current, target)
                working.add(target, working.removeAt(current))
            }
        }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        // An output is told what the block's first track now follows —
        // nothing when it lands at the front.
        val anchorId = newQueue.getOrNull(landsAt - 1)?.id
        remote?.queueEdited(QueueEdit.Moved(moving.map { it.id }, anchorId))
        rearmSoon()
    }

    /**
     * Where the playing track sits after a queue edit.
     *
     * The local player's index answers when it is the one playing. While the
     * sound is elsewhere that player sits paused on whatever it held when
     * casting began, so its index is stale — reading it is what made an edit
     * start the wrong song. A track's identity survives a reorder; an index
     * does not.
     */
    private fun indexAfterEdit(newQueue: List<Track>, playingId: String?): Int {
        if (newQueue.isEmpty()) return -1
        if (isCasting) {
            val byId = newQueue.indexOfFirst { it.id == playingId }
            return if (byId >= 0) byId else _index.value.coerceIn(0, newQueue.lastIndex)
        }
        return (player?.currentMediaItemIndex?.value ?: _index.value).coerceIn(0, newQueue.lastIndex)
    }

    // --- Modes ---------------------------------------------------------------

    fun cycleRepeat() {
        setRepeat(
            when (_repeatMode.value) {
                RepeatMode.OFF -> RepeatMode.QUEUE
                RepeatMode.QUEUE -> RepeatMode.TRACK
                RepeatMode.TRACK -> RepeatMode.OFF
            }
        )
    }

    fun setRepeat(mode: RepeatMode) {
        _repeatMode.value = mode
        // A copy queued for repeat-one goes before the mode changes under it,
        // and repeat-one over a remainder gets one — see restartCopyAt.
        settleRestartCopy()
        player?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
            RepeatMode.QUEUE -> Player.REPEAT_MODE_ALL
        }
        if (mode == RepeatMode.TRACK) rearmSoon()
        remote?.repeatChanged(mode)
    }

    /**
     * Move the fader. While casting this drives the *renderer's* volume over UPnP
     * RenderingControl; otherwise it's the phone's media stream — the same one the
     * hardware keys use.
     */
    /**
     * Handle a hardware volume key, returning true when it was ours to handle.
     *
     * The keys are deliberately allowed to fall through to the system so the
     * active MediaSession moves playback volume — but while casting there is no
     * local playback, so Android moves the *ring* stream and the speaker never
     * hears about it. Catching them here is the only way the rocker can reach a
     * renderer; everything else is left to the system exactly as before.
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

    /**
     * Set the output's volume, whichever output that is.
     *
     * Casting also nudges the device's own level to match, so the rocker starts
     * from where the renderer actually is rather than jumping on the first press.
     */
    fun setVolume(fraction: Float) {
        val v = fraction.coerceIn(0f, 1f)
        remote?.let { out ->
            out.setVolume(v)
            return
        }
        _volume.value = v
        player?.setSystemVolume(v)
    }

    /**
     * Toggles shuffle by reordering only the *upcoming* part of the live queue.
     * History and the current track stay put and the reorder is applied with
     * incremental move ops, so the playing track is never re-prepared (rebuilding
     * the queue with setMediaQueue would restart it). Turning shuffle off puts the
     * upcoming tracks back into their pre-shuffle order.
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
        // One replace rather than a move per track: settling a few hundred
        // positions one at a time is a timeline update each, which locks the main
        // thread and (with artwork) exhausts the heap.
        player?.replaceRange(head.size, q.size, newTail.map { it.toAudioItem() })
        remote?.queueEdited(QueueEdit.Reordered)
        rearmSoon()
    }

    // --- Where the sound goes ------------------------------------------------

    /** Every family the sound can go to from here: the active source's own, and the phone's. */
    fun outputFamilies() = OutputFamilies(App.source.value.provider.outputFamilies, Outputs.phone)

    /** Every [OutputTarget] [family] can play to right now. Does its own waiting off Main. */
    suspend fun find(family: OutputFamily): List<OutputTarget> = outputOf(family).find()

    /**
     * Move playback to [target]: stop this device and hand the queue over,
     * at the current track and the position it has reached.
     */
    fun castTo(family: OutputFamily, target: OutputTarget) {
        val next = outputOf(family)
        // No Wi-Fi, no speaker: one found a minute ago is out of reach now,
        // and playback must not be taken off this device for it.
        if (next.needsLan && !App.rulesNow().mayUseLan) return
        settleRestartCopy()
        supersedeStart()
        if (!next.canSteer()) return
        // A far end that reports its own playback does so as a session of its
        // own, and this phone's ends. Left open, nothing would report on it
        // again and it would sit on the dashboard beside the player's — two
        // streams for one listener — until it timed out.
        if (next.reportsItself) sessions.close()
        // One route at a time — whatever has the sound hands over quietly. The
        // same family is only pointed somewhere else.
        val current = remote
        if (current != null && current !== next) dropOutput(Disconnect.Switch)
        player?.pause()
        _active.value = ActiveOutput(family, target, next)
        next.connect(target, fromMs = _positionMs.value)
    }

    /** Bring the sound back to this phone, from wherever the output had reached. */
    fun playHere() = dropOutput(Disconnect.HandBack)

    /**
     * Stop sending the sound to whatever has it, for [why]: the output is
     * told as its family needs — see Output.disconnect. Then, unless
     * playback stopped or the app is being left, the fader goes back to the
     * phone's own level, and a hand-back picks the queue up here from where
     * the output had reached.
     */
    private fun dropOutput(why: Disconnect) {
        val at = _positionMs.value
        val wasPlaying = _isPlaying.value
        val a = _active.value ?: return
        // Let go before telling it, so nothing still running reads it as
        // steering on the way out — the requeue collector would otherwise
        // arm one more track on a renderer being stopped.
        if (!_active.compareAndSet(a, null)) return
        val out = a.output
        out.disconnect(a.target, why)
        if (why == Disconnect.Stopped || why == Disconnect.Leaving) return
        val p = player ?: return
        _volume.value = p.deviceVolume.value
        if (why != Disconnect.HandBack) return
        val play = out.handBackPlays(wasPlaying)
        // The queue moved on while the output had it — a renderer's poll
        // walks _index as tracks finish there, and a Plex player's reports
        // move it too — but this player sat paused on whatever it held when
        // casting began. Seeking it in place picked the cast's position inside
        // the *old* track: coming back landed where you were before casting,
        // not where the speaker left off. Index and position go in one seek;
        // see LightAudioPlayer. Rebuilt at that index and position in one call
        // rather than seeked: a paused player has no duration yet for an item
        // it never opened, so any plain seek into it clamps to 0 — which is
        // why the right song came back from the top. Same reason restoreState
        // and the stream fallback rebuild instead of seeking — and a stream
        // the player can't seek at all goes through the server; see
        // rebuildQueueAt, which is where the second restart-from-the-top came
        // from. The handle is read on Main, as in stop().
        scope.launch(Dispatchers.Main.immediate) {
            val cur = player ?: return@launch
            val tracks = _queue.value
            if (tracks.isEmpty()) return@launch
            val index = _index.value.coerceIn(0, tracks.lastIndex)
            android.util.Log.i(out.logTag, "hand-back idx=$index at=$at playing=$wasPlaying")
            rebuildQueueAt(cur, tracks, index, at)
            if (play) cur.play() else cur.pause()
        }
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
            _isPlaying.value = playing
        }

        override fun showPosition(ms: Long) {
            _positionMs.value = ms
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

        override fun streamFormat(): StreamFormat = effectiveFormat()

        // The server's own address, never playerUrl's: the door that wraps is
        // on this phone's loopback, where a speaker can't reach it.
        override fun streamUrl(
            track: Track,
            format: StreamFormat,
            timeOffsetSeconds: Int,
            estimateContentLength: Boolean,
            sessionId: String?,
        ): String? = serverClient.value?.playableUrl(track, format, timeOffsetSeconds, estimateContentLength, sessionId)

        override fun ownStreamSession(trackId: String): String = streamSessionIdFor(trackId, decidedFirst = false)

        override fun announceNowPlaying() {
            this@PlaybackController.announceNowPlaying()
        }

        override fun listeningEnded() {
            sessions.close()
        }

        override fun setPhoneVolume(level: Float) {
            player?.setSystemVolume(level)
        }

        override fun dropped(output: Output, target: OutputTarget) {
            val a = _active.value ?: return
            if (a.output === output && a.target === target) _active.compareAndSet(a, null)
        }

        override fun endedOnItsOwn(output: Output, target: OutputTarget) {
            val a = _active.value ?: return
            if (a.output !== output || a.target !== target) return
            if (!_active.compareAndSet(a, null)) return
            _isPlaying.value = false
            val p = player ?: return
            _volume.value = p.deviceVolume.value
            // Reached from a poll on Default, so the handle is read again
            // once on Main: a new activity may have taken the player over in
            // between, and the one it replaced is released and throws.
            scope.launch(Dispatchers.Main.immediate) {
                val cur = player ?: return@launch
                val tracks = _queue.value
                if (tracks.isEmpty()) return@launch
                rebuildQueueAt(cur, tracks, _index.value.coerceIn(0, tracks.lastIndex), _positionMs.value)
                cur.pause()
            }
        }
    }

    // --- internals -----------------------------------------------------------

    /**
     * Rebuild the queue's sources when the answer to "file or stream?" may have
     * changed.
     *
     * That question is answered per track by [source], and until now it was
     * answered exactly once — when the queue was built — and then frozen into
     * it. Everything the answer depends on can move while that same queue is
     * still playing: Wi-Fi goes away, a download finishes, the data mode
     * changes. A queue built on Wi-Fi therefore went on asking the network for
     * tracks that were by then sitting on the disk, which offline means stalling
     * for a socket timeout on every one of them, and looks from the outside like
     * an app that has simply stopped working.
     *
     * The playing item is deliberately left alone. Replacing it would restart
     * the track under the listener, which is a worse thing to do than letting
     * one track finish on the source it began with — everything after it is
     * corrected, which is where the stalls were.
     */
    private fun reresolveQueue() {
        val p = player ?: return
        if (isCasting) return
        // Not until the handle has connected. Before then there is no knowing
        // whether the service kept this queue or came back empty after its
        // idle limit, and edits sent ahead land on whichever it is — on an
        // empty one they become the queue. attachHandle asks again once
        // connected.
        if (p.availability.value != LightAudioPlayerAvailability.Ready) return
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        val current = _index.value.coerceIn(0, tracks.lastIndex)
        // Nothing to say unless something actually moves — this runs on every
        // download that completes, and most of them change no answers at all.
        val rebuilt = tracks.map { it.source() }
        val held = queuedSources
        if (rebuilt == held) return
        queuedSources = rebuilt
        // ExoPlayer's looper is Main and it enforces that — see restoreState.
        scope.launch(Dispatchers.Main.immediate) {
            if (player !== p) {
                // The handle changed on the way here — a new activity took the
                // player over, a back-out let it go, or the SDK dropped one that
                // never connected — so none of this reached the service. Left
                // as it was, the memo said it had, and the pass attachHandle
                // makes once the next handle connects found a match and sent
                // nothing: a track downloaded meanwhile stayed queued as a
                // stream, and under Wi-Fi Only on cellular the gate refused it
                // with the file on disk. So the memo goes back to what the
                // service still holds, which is also what a playback error
                // reads it as, unless a new queue or another rebuild has
                // written it since. Then the ask is repeated: a handle already
                // connected gets the edits now, and with none, or one still
                // connecting, they wait for that pass, which now finds the
                // difference.
                if (queuedSources === rebuilt) queuedSources = held
                reresolveQueue()
                return@launch
            }
            settleRestartCopy()
            if (current > 0) {
                p.replaceRange(0, current, tracks.subList(0, current).map { it.toAudioItem() })
            }
            if (current + 1 <= tracks.lastIndex) {
                p.replaceRange(
                    current + 1,
                    tracks.size,
                    tracks.subList(current + 1, tracks.size).map { it.toAudioItem() },
                )
            }
            rearmSoon()
        }
    }

    /** What the queue was last built from, so a no-op rebuild stays a no-op. */
    private var queuedSources: List<LightAudioSource> = emptyList()

    private fun setQueue(tracks: List<Track>, startIndex: Int) {
        val p = player ?: return
        settleRestartCopy()
        _queue.value = tracks
        // The order shuffle would go back to is the last queue's, not this one's.
        preShuffleOrder = null
        lastScrobbledId = null
        // Keep the local player loaded even while casting, so switching back to
        // this device resumes instantly instead of re-preparing the queue.
        // Resolved once and handed on: toAudioItem() would resolve each of up
        // to five hundred tracks a second time, on the main thread.
        val sources = tracks.map { it.source() }
        queuedSources = sources
        p.setMediaQueue(tracks.mapIndexed { i, track -> track.toAudioItem(sources[i]) }, startIndex)
        _index.value = startIndex
        // A new queue plays from the start of whatever it opens on. Any offset
        // still set belongs to a stream that was seeked before this one, and
        // would be added to the clock of a track starting at 0:00 — which is how
        // a song opens at 0:49 with the audio at the very beginning. The index
        // collector can't be relied on to clear it: it only fires when the index
        // *changes*, and a new queue often starts on the same one.
        streamOffsetMs = 0
        // Every slot of a new queue is a full item; nothing is left to restore.
        offsetItemTrackId = null
        // Picking something new to play is reason enough to try the server again.
        streamFailed.clear()
        // A new queue is prepared afresh, so a park on the old one is over —
        // and where it starts is where the user asked it to.
        _waitingFor.value = null
        pickedIndex = startIndex
        // Right from the first frame, rather than whenever the stream gets round
        // to declaring a length — see the durationMs collector in ensurePlayer.
        tracks.getOrNull(startIndex)?.durationMs
            ?.takeIf { it > 0L }
            ?.let { _durationMs.value = it }
        // A new queue goes wherever the sound is already going. Without this it
        // started here while the Companion player carried on with the queue it
        // still owned — two songs at once, and the Output page still naming the
        // TV. From the top, not the old queue's position.
        remote?.let { out ->
            p.pause()
            out.newQueue()
            return
        }
        // The decision first, then the sound — the same order [play] keeps,
        // and for the same reason: a Plex stream started with no decision
        // behind it comes back 400. A file has nothing to decide and starts
        // at once.
        if (cannotStreamNow(startIndex)) {
            // Known before asking: the player would only find out through the
            // loopback door, whose refusal it retries for a few seconds first.
            blockedAtCurrent()
            return
        }
        starting(startIndex)
        // The handle current when the answer comes, as in togglePlayPause.
        withStreamDecision { player?.play() }
    }

    /** The row at [index] is a stream, and the rules, read now, or a closed door forbid streaming. */
    private fun cannotStreamNow(index: Int): Boolean =
        queuedSources.getOrNull(index) is LightAudioSource.UrlSource &&
            (!NetworkGate.isOpen() || !App.rulesNow().mayStream || !App.streamProxy.isOn)

    /** Sort [tracks] back into the relative order they had in [original]. */
    private fun restoreOrder(tracks: List<Track>, original: List<Track>): List<Track> {
        val rank = original.withIndex().associate { (i, t) -> t.id to i }
        // Tracks queued after shuffling have no pre-shuffle rank; keep them last.
        return tracks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    /**
     * The original stream of the song the player is on failed on the server's
     * side: move the song to the other address its server has for the same
     * file, for good, and carry on from where it stood. Main only, like
     * [rebuildQueueAt]. False leaves the failure to the usual path.
     *
     * Plex answers its direct-play address with a 500 for files whose media
     * analysis never finished, and serves the identical file asked for as a
     * download — same length, same ranges — so seeking and the loopback
     * door's pickups go on working as they did. Once per song: one that fails
     * on the other address as well — a shared account the owner keeps from
     * downloading is refused there — goes down the usual path, and nothing
     * here ever changes the format.
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
        if (effectiveFormat() != StreamFormat.RAW || track.originalKey() in originalElsewhere) return false
        if (serverClient.value?.alternateOriginalUrl(track) == null) return false
        originalElsewhere += track.originalKey()
        android.util.Log.i(
            "AmpNet",
            "server failed the original at idx=$index — playing it from its other address from now on",
        )
        // Arrived on it by itself and not yet heard of here: landed on the
        // way the index collector would have, so the song is announced and
        // timed as the one playing, from its start.
        if (index != _index.value) landedOn(p, index, startMs = 0L)
        val wanted = p.playWhenReady
        rebuildQueueAt(p, _queue.value, index, _positionMs.value)
        if (wanted) {
            starting(index)
            withStreamDecision { player?.play() }
        } else {
            p.pause()
        }
        return true
    }

    /**
     * A stream died — carry on from the downloaded copy, at the same second.
     *
     * Losing the network mid-track otherwise just stops the music: the player
     * reports an error, playback ends, and the user is left tapping play on a URL
     * that will keep failing. Where the track is downloaded, this rebuilds the
     * queue against local files and resumes at the position that was reached, so
     * walking out of Wi-Fi sounds like nothing happened.
     *
     * [forceOffline] latches until the server answers again, because the very
     * next track would otherwise be resolved to a URL and fail in the same way.
     */
    private fun fallBackOffline(serverAnswered: Boolean) {
        val p = player ?: return
        val track = _queue.value.getOrNull(_index.value) ?: return
        val resumeAt = _positionMs.value
        if (!localFiles.containsKey(track.id)) {
            // Nothing local to fall back to. A bad status is the server's own
            // answer about this one track, and stands. Silence is different:
            // it is what a Wi-Fi-to-cellular handover sounds like from here,
            // and the player's own retries gave up on the network it started
            // on. So ask the server, once: if it answers and the rules still
            // allow streaming, pick the track up where it stopped; if not,
            // this track has to wait like any other that needs the server.
            if (isCasting) return
            if (serverAnswered) {
                // The server answered and the answer was no — for this track.
                // It used to end here, silently: a player in its error state
                // under a screen that looked ready, where play did nothing and
                // only a new tap recovered. Now it is said, and playback moves
                // on if it was moving.
                blockedAtCurrent(because = CANT_PLAY_THIS)
                return
            }
            if (retriedTrackId == track.id) {
                // Already picked up once and it died again. Not silently: this
                // track waits like any other that can't be had, and playback
                // moves on if it can.
                blockedAtCurrent()
                return
            }
            retriedTrackId = track.id
            scope.launch {
                // Bounded here, because the ping itself may be queued behind
                // one already out on the link that just died, and the player
                // is sitting silent meanwhile. No answer in time counts as no.
                // Three answers, not two. Yes; no; and *nothing yet* — the
                // wait ran out, or the rules forbade asking. On a slow link
                // nothing-yet is the common one, and reading it as "no" parked
                // a track whose server was perfectly fine, just slow to say so.
                val verdict = withTimeoutOrNull(VERIFY_WAIT_MS) { App.reachability.verify() }
                val there = verdict != false
                // A "no" from one ping is a suspicion, not a verdict: keep
                // asking in the background, so the answer changing reaches the
                // rules — and through them this player — without a press.
                if (verdict != true) App.reachability.check()
                withContext(Dispatchers.Main.immediate) {
                    // The user may have moved on, paused, started a cast or
                    // got the sound back by other means while the question
                    // was out. Any of those outranks this recovery.
                    if (player !== p) return@withContext
                    if (_queue.value.getOrNull(_index.value)?.id != track.id) return@withContext
                    if (isCasting || p.isPlaying.value) return@withContext
                    if (there && App.rulesNow().mayStream) {
                        val at = _positionMs.value
                        android.util.Log.i("AmpNet", "stream died, server answers — resuming at $at")
                        val wanted = p.playWhenReady
                        rebuildQueueAt(p, _queue.value, _index.value, at)
                        if (wanted) {
                            starting(_index.value)
                            p.play()
                        } else {
                            p.pause()
                        }
                    } else {
                        blockedAtCurrent()
                    }
                }
            }
            return
        }
        // One attempt per track, so a file that is itself unplayable (a codec the
        // device can't decode) can't spin here.
        if (retriedTrackId == track.id) return
        retriedTrackId = track.id
        streamFailed += track.id
        // Only a server that never answered is grounds for pinning *everything*
        // to local files — and only until it is asked and answers. The pin
        // keeps the music going from the disk right now; the question below
        // decides whether it stays. It used to stay until the next sync.
        if (!serverAnswered) {
            forceOffline = true
            scope.launch {
                if (withTimeoutOrNull(VERIFY_WAIT_MS) { App.reachability.verify() } == true) {
                    forceOffline = false
                    reresolveQueue()
                } else {
                    // Keep asking; the rules collector lifts the pin when the
                    // server answers.
                    App.reachability.check()
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            // Read before the rebuild: a stream can die while *paused* — the
            // player buffers regardless — and that is no reason to start
            // making sound from the copy on the disk.
            val wanted = p.playWhenReady
            p.setMediaQueueAt(_queue.value.map { it.toAudioItem() }, _index.value, resumeAt)
            if (wanted) p.play() else p.pause()
        }
    }

    /**
     * Tell the server this session is playing or paused — see
     * [MusicServer.reportTimeline]. Through [sessions], so a session that is
     * open under another id is stopped first; queued, not awaited: a dropped
     * heartbeat just means the dashboard is stale until the next one, not a
     * broken stream.
     */
    private fun reportTimeline(state: TimelineState) {
        // A Companion player reports its own timeline to the server — that is
        // its scrobble, and this phone adding another would double every play.
        if (remote?.reportsItself == true) return
        val track = _queue.value.getOrNull(_index.value) ?: return
        // Nothing playing on the server's behalf to report on.
        if (PhoneFiles.isPhoneFile(track.id)) return
        sessions.report(
            TimelineReport(sessionIdFor(track.id), track.id, state, _positionMs.value, _durationMs.value),
        )
    }

    /**
     * Bring the server's session up to the song playback is on now: the one
     * place a change of song reaches the server — see the collector in
     * [bind]. Says nothing unless what the server shows has to change. Main
     * only: the queue, the index and the player's own intent are read
     * together. [atMs] is where in the song playback is, when that is known
     * to be the song's own reading rather than the last one's.
     */
    private fun followCurrentSong(atMs: Long = 0L) {
        if (remote?.reportsItself == true) return
        val track = _queue.value.getOrNull(_index.value)
        if (track == null || PhoneFiles.isPhoneFile(track.id)) {
            // Nothing of the server's is playing any more: an emptied queue,
            // the last song taken out of it.
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
     * Whether the song playback is on is playing, or about to: what a change
     * of song is announced as. The player's own state rather than
     * [isPlaying], which lags it, and is stale for a moment on a reopen. Main
     * only, for [LightAudioPlayer.playWhenReady].
     */
    private fun playbackWanted(): Boolean {
        if (remote != null) return _isPlaying.value
        val p = player ?: return false
        if (p.availability.value != LightAudioPlayerAvailability.Ready || parked) return false
        return p.isPlaying.value || p.playWhenReady || _buffering.value
    }

    /**
     * Queue [report] for the server that is current now — the one it is
     * about, even if a source switch swaps the client before it is sent. For
     * [sessions], which calls this as it decides; false when it can't go, and
     * the session is then left as it was.
     */
    private fun queueTimeline(report: TimelineReport, news: Boolean): Boolean {
        // Wi-Fi Only on a metered link means don't. A heartbeat is worth no
        // cellular data and nothing is lost by skipping it: it says where
        // playback is *now*, so a stale one replayed later would be worse than
        // none. A stop held back here is not lost either: the session stays
        // open in [sessions], and the next report that may go stops it. The
        // play itself is kept — see maybeSubmitPlay.
        if (!metadataAllowed()) return false
        val client = serverClient.value ?: return false
        if (news) {
            android.util.Log.i(
                "AmpSession",
                "${report.state.name.lowercase()} ${report.trackId} under ${report.sessionId}",
            )
        }
        return timelineOut.trySend(TimelineSend.Report(client, report)).isSuccess
    }

    /**
     * Say what is playing, the moment it starts.
     *
     * Not the same statement as the play itself — see [MusicServer.scrobble].
     * Nothing is kept for a retry: a now-playing notice replayed an hour later
     * is simply false, where a play is still true whenever it arrives.
     */
    private fun announceNowPlaying() {
        // The Companion player is the one playing; the server hears it from them.
        if (remote?.reportsItself == true) return
        val track = _queue.value.getOrNull(_index.value) ?: return
        // Nothing to tell, and nowhere to keep a play count: a local file's
        // history would be invented by this app and restorable by nothing.
        if (PhoneFiles.isPhoneFile(track.id)) return
        if (track.id == nowPlayingId) return
        // Same as the timeline: "playing this now" is only true now.
        if (!metadataAllowed()) return
        nowPlayingId = track.id
        val client = serverClient.value ?: return
        scope.launch { runCatching { client.scrobble(track.id, submission = false) } }
    }

    /**
     * How much of a track has to be heard before the play counts.
     *
     * Half of it, or four minutes, whichever comes first — the rule Last.fm
     * applies at the far end of whatever the server forwards to. A track whose
     * length nothing has reported yet falls back to the four minutes; that only
     * happens on a stream that never declares one.
     */
    private fun submitAfterMs(durationMs: Long): Long =
        if (durationMs <= 0L) SUBMIT_AFTER_MS else minOf(durationMs / 2, SUBMIT_AFTER_MS)

    /**
     * Submit the play, once enough of the track has actually been heard.
     *
     * This used to fire the instant a track started, which meant a queue skimmed
     * through in ten seconds logged a play for every track in it — in the local
     * counts that drive Plays sorting, and on whatever the server scrobbles to.
     */
    private fun maybeSubmitPlay(positionMs: Long) {
        val track = _queue.value.getOrNull(_index.value) ?: return
        if (PhoneFiles.isPhoneFile(track.id)) return
        if (track.id == lastScrobbledId) return
        if (positionMs < submitAfterMs(_durationMs.value)) return
        lastScrobbledId = track.id
        // Stamped now, sent whenever: a play that happened offline still belongs
        // at the time it happened once the server hears about it.
        val at = System.currentTimeMillis()
        val client = serverClient.value
        // The source the play was heard on, so it is only ever sent there.
        val source = App.source.value.id
        scope.launch {
            // Local first, so the library's ordering moves with the listening
            // whether or not the server ever hears about this play.
            runCatching { App.library.markPlayed(track.id) }
            // A play is durable in a way a heartbeat isn't, so where the mode
            // forbids the bytes it is queued rather than dropped — the same
            // road an unreachable server sends it down, and PendingActions
            // already stamps it with when it happened. Erring towards the queue
            // if the gate can't be read: waiting costs nothing, and a play that
            // was never sent and never queued is gone.
            val allowed = runCatching { metadataAllowed() }.getOrDefault(false)
            val result = if (allowed && client != null) {
                attemptWrite { client.scrobble(track.id, at) }
            } else {
                WriteResult.Unreachable(UNREACHABLE)
            }
            when (result) {
                WriteResult.Saved -> Unit
                // Guarded: a play can land before App has finished wiring itself up,
                // and a missed scrobble is not worth a crash.
                is WriteResult.Unreachable -> runCatching {
                    App.pending.add(
                        PendingAction(PendingAction.Kind.SCROBBLE, track.id, atMs = at, sourceId = source),
                    )
                }
                // Not kept: the server would say no again every time. Said once
                // per source and run — a server that refuses one play tends to
                // refuse them all, and a notice at every track would be noise.
                is WriteResult.Refused -> {
                    android.util.Log.w("AmpSync", "play of ${track.id} refused: ${result.reason}")
                    if (playRefusalSaid.add(source)) runCatching { App.tell("Couldn't save\n${result.reason}") }
                }
            }
        }
    }

    /**
     * The format to actually request.
     *
     * Low Data is about *cellular* data, not quality in general — on Wi-Fi there's
     * nothing to save, so the user's chosen format applies as normal.
     *
     * Off Wi-Fi it clamps to Opus rather than MP3: at 192 kbps Opus is 24 KB/s
     * against MP3 320's 40 KB/s, so it saves about 40% of the bytes *and* sounds
     * better. MP3 was only ever the compatibility-safe choice, and this device
     * decodes Opus fine.
     */
    private fun effectiveFormat(): StreamFormat {
        // From the source rather than the client: the format is a property of
        // which server you are on, and not every backend carries a config object.
        //
        // The connection picks between two stated choices rather than a data
        // mode quietly capping one. Low Data used to drop anything heavier than
        // Opus off Wi-Fi, which meant asking for lossless on cellular and being
        // given something else without being told — the substitution this app
        // has been getting rid of everywhere else. Whoever wants Opus on
        // cellular can now say so, and be believed.
        val source = App.source.value
        return if (unmetered) source.wifiFormat else source.cellularFormat
    }

    /**
     * [MusicServer.streamUrl], except for a song moved to the other address
     * its server has for the original — see [playOriginalElsewhere]. Every
     * URL this controller plays, casts or probes is made here, so a moved
     * song stays moved however it is reached next: a seek, a rebuild, a
     * speaker.
     */
    private fun MusicServer.playableUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = true,
        sessionId: String? = null,
    ): String {
        val moved = format == StreamFormat.RAW && track.originalKey() in originalElsewhere
        return (if (moved) alternateOriginalUrl(track) else null)
            ?: streamUrl(track, format, timeOffsetSeconds, estimateContentLength, sessionId)
    }

    /**
     * Where to play a track from.
     *
     * Downloads win by default — that's the point of having them. A download is
     * abandoned only when the user's chosen streaming format is genuinely better
     * than the copy on disk, *and* the bytes are free: an unmetered connection,
     * whatever the mode. Even Make it Hurt — which spends cellular data on
     * everything else — plays a downloaded track from its file: a copy on the
     * phone beats paying for the same song again.
     */
    private fun Track.source(): LightAudioSource {
        // A track from the phone's own library is the file, always — there is no
        // stream to prefer and no server to ask.
        PhoneFiles.fileOf(id)?.let { return LightAudioSource.FileSource(it) }
        val local = localFiles[id]
        if (local != null) {
            val (file, downloadedFormat) = local
            val streamed = effectiveFormat()
            val preferStream = !forceOffline && id !in streamFailed && unmetered &&
                streamed.qualityRank > downloadedFormat.qualityRank
            if (!preferStream) return LightAudioSource.FileSource(file)
        }
        return LightAudioSource.UrlSource(playerUrl(this, effectiveFormat()))
    }

    /**
     * The address the player is handed for [track] in [format]: through the
     * loopback door when it is up — see StreamProxy — so the player never
     * holds the server's address, only this phone's.
     *
     * Where the server keeps one session per player, the door also asks the
     * server's decision for this very song right before it fetches it, under
     * that session's id. Plex holds a session it is tracking to a decision per
     * song, and the player fetches ahead and on its own — the next song while
     * this one plays, a skip, a retry — so nothing in front of it can know
     * when to ask. Without the door, the stream has an id of its own instead;
     * see [streamSessionIdFor].
     */
    private fun playerUrl(track: Track, format: StreamFormat, timeOffsetSeconds: Int = 0): String {
        val client = serverClient.value ?: return ""
        val decideFirst = decidesAtDoor(client, track, format)
        val sid = streamSessionIdFor(track.id, decideFirst)
        val url = client.playableUrl(
            track,
            format,
            timeOffsetSeconds = timeOffsetSeconds,
            // No estimated Content-Length, which is what made a long track take
            // minutes to start.
            //
            // To declare a length for a transcode the server has to know the
            // size, and knowing the size means finishing the encode — so the
            // wait grew with the track. Measured: 29 minutes of audio took 67.9s
            // to make a sound, with downloads held off entirely and the link
            // running at 5 MB/s. That is ~26x realtime, this server's opus rate.
            // Without the estimate the same 124-minute track starts in 605ms.
            //
            // The estimate was here for byte-seeking inside a transcoded stream,
            // but seekTo doesn't byte-seek — it asks the server for the same
            // track at a timeOffset. So nothing here needed it.
            //
            // Only Subsonic reads the flag; Jellyfin and Plex ignore it. Passing
            // it here covers every source that ever might, and costs the other
            // two nothing.
            estimateContentLength = false,
            sessionId = sid,
        )
        // Asked of whichever client is current when the fetch happens: one
        // rebuilt for the same source since — a new address, a renewed token —
        // is the one that can still be heard.
        val decision: (suspend () -> Unit)? =
            if (decideFirst) ({ serverClient.value?.prepareStream(track.id, format, sid) }) else null
        return App.streamProxy.wrap(url, group = track.id, prepare = decision)
    }

    /**
     * Rebuild the player's queue on [index], cued at [positionMs]. Main only.
     *
     * A start position handed to the player at prepare time only lands on a
     * stream it can seek. A transcode arrives chunked with nothing to seek by,
     * and ExoPlayer quietly opens it from 0:00 instead — which is how coming
     * back from the speaker, and reopening the app, began the track over. Those
     * streams go through the server the way [seekTo] does: the item is built
     * on a stream that already starts at the position, and [streamOffsetMs]
     * shifts the readouts. A stream the player can seek keeps the cheap route,
     * checked afterwards the way a native seek is.
     */
    private fun rebuildQueueAt(p: LightAudioPlayer, tracks: List<Track>, index: Int, positionMs: Long) {
        settleRestartCopy()
        val track = tracks[index]
        val at = positionMs.coerceAtLeast(0L)
        queuedSources = tracks.map { it.source() }
        // Set before the player moves: the index collector reads a change as a
        // new track and would clear the offset this is about to set.
        _index.value = index
        _positionMs.value = at
        // Seeded from the library the way the index collector seeds a track
        // change — which this path deliberately suppresses by pre-setting
        // _index. A chunked stream may never report a duration at all, and its
        // 0 never re-emits, so a restored queue was left with a position and
        // no end: a full bar and a total of 0:00.
        track.durationMs.takeIf { it > 0L }?.let { _durationMs.value = it }
        val client = serverClient.value
        if (at > 1_000L && client != null && needsServerSeek(track, askDoor = false)) {
            android.util.Log.i("AmpCast", "rebuild idx=$index at=$at via server offset")
            // See serverSeek: an estimated length invites range probes the
            // transcode can't answer (416), which errored the restore.
            val url = playerUrl(track, effectiveFormat(), timeOffsetSeconds = (at / 1000).toInt())
            streamOffsetMs = at
            offsetItemTrackId = track.id
            p.setMediaQueue(
                tracks.mapIndexed { i, it ->
                    if (i == index) it.toAudioItem(LightAudioSource.UrlSource(url)) else it.toAudioItem()
                },
                index,
            )
        } else {
            android.util.Log.i("AmpCast", "rebuild idx=$index at=$at natively")
            streamOffsetMs = 0
            offsetItemTrackId = null
            p.setMediaQueueAt(tracks.map { it.toAudioItem() }, index, at)
            if (at > 0L) verifyNativeSeek(p, track, at, awaitStream = true)
        }
        rearmSoon()
    }

    private fun Track.toAudioItem(from: LightAudioSource = source()): LightAudioItem = LightAudioItem(
        source = from,
        metadata = LightMediaMetadata(
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
        ),
    )

    companion object {
        private fun newSessionId(): String = UUID.randomUUID().toString()

        /** Shown when the server itself refused the track — a bad status, not a bad link. */
        private const val CANT_PLAY_THIS = "Server can't play this track"

        /** A file on the phone that would not open or decode. */
        private const val CANT_PLAY_FILE = "Can't play this file"

        /** A stream that arrived and would not decode. */
        private const val CANT_PLAY_FORMAT = "Can't play this format"

        /** Streaming waits on the door the player streams through, which isn't up — see StreamProxy. */
        private const val STREAM_UNAVAILABLE = "Can't stream right now"

        /** The player's name for "the server answered with an error status". */
        private val BAD_HTTP_STATUS = PlaybackException.getErrorCodeName(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

        /** How often the link is re-read while playing or parked — see bind. */
        private const val LINK_POLL_MS = 3_000L

        /**
         * How long a *parked* player keeps re-reading the link before leaving
         * it to the callback, a press, or the app coming back to the front. A
         * phone parked on "Waiting for Wi-Fi" in a pocket should not be asking
         * the system anything all afternoon.
         */
        private const val PARKED_POLL_LIMIT_MS = 10 * 60 * 1000L

        /** How much of a track has to pass, in as much time, to count as playing — see noteProgress. */
        private const val PROGRESS_PROOF_MS = 1_000L

        /** The longest a stream start is treated as "in progress" — see [buffering]. */
        private const val BUFFERING_LIMIT_MS = 45_000L

        /** How long a dead stream waits on the reachability ping before treating it as a no. */
        private const val VERIFY_WAIT_MS = 2_500L
        /**
         * How close a seeked stream's duration has to be to the whole track
         * before we conclude the server ignored the offset. Generous, because a
         * transcode's duration is its own estimate.
         */
        private const val IGNORED_OFFSET_SLACK_MS = 3_000L

        /** How near the end still counts as having reached it. */
        private const val QUEUE_END_SLACK_MS = 1_000L

        /** Long enough for the player to be reporting the stream, not the request. */
        /**
         * How long to wait for Plex to settle a transcode decision before
         * starting anyway. Short: this sits between the tap and the sound.
         */
        private const val STREAM_DECISION_TIMEOUT_MS = 2_000L

        /** Long enough for the player to be reporting the stream, not the request. */
        private const val SEEK_VERIFY_MS = 900L

        /** How long a rebuilt queue gets to open its stream before the seek is judged. */
        private const val REBUILD_VERIFY_TIMEOUT_MS = 8_000L

        /** How far a native seek may land from the target before it counts as a miss. */
        private const val SEEK_TOLERANCE_MS = 5_000L

        /** How long a seek may wait for sound before the screen says it is buffering — see bufferAfterSeek. */
        private const val SEEK_BUFFERING_GRACE_MS = 300L

        /**
         * The most tracks the player is ever given at once, and how many already
         * played are kept behind the starting point.
         */
        private const val MAX_QUEUE = 500
        private const val QUEUE_LOOKBACK = 50

        /** One press of the rocker, as a fraction of full scale. */
        private const val VOLUME_KEY_STEP = 0.05f

        /** Settling time before an output hears that what plays next has changed. */
        private const val UPCOMING_DEBOUNCE_MS = 400L

        /** How often the queue snapshot is written while playing. */
        /** How long a restore waits to learn what is downloaded before deciding. */
        private const val DOWNLOADS_READY_TIMEOUT_MS = 4_000L
        private const val PROXY_READY_TIMEOUT_MS = 3_000L

        private const val SAVE_STATE_INTERVAL_MS = 5_000L
        private const val SAVE_STATE_TIMEOUT_MS = 1_000L

        /** How often a live session is re-announced; PlexAmp uses roughly this. */
        private const val TIMELINE_INTERVAL_MS = 10_000L
        /**
         * The far end of "enough of it was heard to count as a play".
         *
         * Half the track or this, whichever comes first — Last.fm's rule, and
         * the one the server will apply to whatever it forwards.
         */
        private const val SUBMIT_AFTER_MS = 4 * 60 * 1000L

        /**
         * How many tracks of the queue the server is told about.
         *
         * An id is around forty characters once it is a URL parameter, so this is
         * a few kilobytes of request — under the eight that proxies commonly cut
         * off, and far enough ahead that resuming on another device has somewhere
         * to go.
         */
        private const val MAX_SAVED_QUEUE = 100
    }
}

/**
 * Whether a reopen has to put the queue back into the player — see the
 * re-cue in [PlaybackController.attachHandle], once the handle has connected.
 *
 * Only for a handle that connected holding nothing and making no sound, under
 * the very queue and track the controller carried across. Silence is asked
 * for as well as emptiness: a service making sound has a queue, and cueing
 * over it would stop the music the user came back to.
 */
internal fun needsRecue(
    carriedOver: Boolean,
    playerIndex: Int,
    playerPlaying: Boolean,
    casting: Boolean,
    parked: Boolean,
): Boolean = carriedOver && playerIndex == NO_MEDIA_ITEM && !playerPlaying && !casting && !parked

/**
 * Where in its track a handle that has just connected leaves playback, when
 * the service is on another track than the screen shows — see the landing in
 * [PlaybackController.attachHandle]. Null when there is nothing to land on:
 * the same track, an empty player, a queue just cued back in, a cast, whose
 * renderer is the one that says where playback is, or a park, where the
 * service sits on the track that failed and the screen's marker is the one
 * the user moved.
 *
 * The service's own position, which the SDK read from the same snapshot as
 * its index. Except on the track a next or previous pressed while the handle
 * connected went to ([pickedIndex]): that press reached the service after the
 * snapshot, so the track starts from the top and the position is still the
 * last one's.
 */
internal fun connectLanding(
    playerIndex: Int,
    playerPositionMs: Long,
    shownIndex: Int,
    pickedIndex: Int,
    recued: Boolean,
    casting: Boolean,
    parked: Boolean,
): Long? = when {
    recued || casting || parked -> null
    playerIndex < 0 || playerIndex == shownIndex -> null
    playerIndex == pickedIndex -> 0L
    else -> playerPositionMs
}

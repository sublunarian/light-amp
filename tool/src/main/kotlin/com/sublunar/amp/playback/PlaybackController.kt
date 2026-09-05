package com.sublunar.amp.playback

import androidx.media3.common.Player
import com.thelightphone.sdk.cast.DlnaCast
import com.thelightphone.sdk.cast.DlnaRenderer
import com.thelightphone.sdk.cast.DlnaState
import com.thelightphone.sdk.audio.LightAudioNetworkBlockedException
import com.sublunar.amp.data.NetworkGate
import com.sublunar.amp.data.AppSettings
import com.sublunar.amp.data.Connectivity
import com.sublunar.amp.data.DownloadStore
import com.sublunar.amp.data.LocalLibrary
import com.sublunar.amp.data.RepeatMode
import android.view.KeyEvent
import androidx.media3.common.PlaybackException
import com.sublunar.amp.App
import com.sublunar.amp.data.PendingAction
import com.sublunar.amp.data.SavedQueue
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.PlexClient
import com.sublunar.amp.data.PlexCompanionListener
import com.sublunar.amp.data.PlexGdm
import com.sublunar.amp.data.PlexPlayerTimeline
import com.sublunar.amp.data.plexTimelineFrom
import com.sublunar.amp.data.PlexPlayer
import com.sublunar.amp.data.PlexQueue
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.db.LibraryDao
import com.sublunar.amp.data.qualityRank
import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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
    private var player: LightAudioPlayer? = null

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
     * player's own volume; see the collector in [bind].
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
    /** Identity of the queue last handed to the server — see pushQueueToServer. */
    private var lastSavedQueueKey: Int? = null

    /**
     * Identifies playback to the server, distinct from any other stream (or
     * download) in flight — see the comment on [MusicServer.streamUrl]. Carried
     * on both the stream URL and the [reportTimeline] calls that go with it, so
     * Plex's dashboard has one session to show rather than none.
     *
     * One id **per track**, derived rather than minted, because of when stream
     * URLs are built: the whole queue is turned into audio items at once, each
     * carrying the id that existed at that moment. A single field reassigned on
     * every track change therefore ended up reporting a session that no stream
     * was carrying — and gave every queued track the same identifier, which is
     * the one thing Plex must not see twice. It keeps one transcode per
     * identifier and tears down the previous holder, so the next track being
     * prepared while the current one plays could kill the stream that is
     * playing. Deriving it from the track keeps the URL and the timeline in
     * agreement however far ahead the queue was built.
     *
     * [playbackSession] changes with each new queue, so playing the same track
     * again is a new session rather than a continuation of the old one.
     */
    private var playbackSession: String = UUID.randomUUID().toString()

    private fun sessionIdFor(trackId: String): String = "$playbackSession:$trackId"

    /** The track whose session is currently open, so it can be closed out by id. */
    private var sessionTrackId: String? = null

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
     * playback leaves the slot; [LightAudioPlayer.onItemRepeated] handles the
     * one return path with no index change.
     */
    @Volatile
    private var offsetItemTrackId: String? = null

    /**
     * The same trick for the renderer: how far into the track its stream starts.
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

    /** Latched by [fallBackOffline]; cleared when the server answers again. */
    @Volatile
    private var forceOffline = false
    private var retriedTrackId: String? = null

    /**
     * Parked by the rule: the player refused its own connection because the
     * bytes would have cost cellular data in Wi-Fi Only — see the SDK's
     * LightAudioNetworkPolicy, wired to App.networkAllowed. The one failure a
     * stream can raise that is neither the server's nor the network's doing,
     * so the one the screen names. The player sits in its error state until
     * [resumeAfterWait] prepares it again: when the link is free again, cued
     * where it stood; on a press before that, left parked, which is the
     * screen keeping its word.
     */
    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi

    /**
     * Tracks whose stream failed, which play from disk instead.
     *
     * Narrower than [forceOffline], and the right size for a server that is
     * plainly there but wouldn't serve *this* song: one track goes to its
     * downloaded copy and everything else still streams.
     */
    private val streamFailed = mutableSetOf<String>()

    // Queue order captured when shuffle was switched on, so switching it back off
    // can restore it.
    private var preShuffleOrder: List<Track>? = null

    // --- DLNA casting (TEMPORARY — removed before submitting to Light) --------

    private val _castRenderer = MutableStateFlow<DlnaRenderer?>(null)
    val castRenderer: StateFlow<DlnaRenderer?> = _castRenderer

    /** True while audio is going somewhere else instead of this device. */
    val isCasting: Boolean get() = _castRenderer.value != null || _plexPlayer.value != null

    private var castJob: Job? = null

    // --- Plex Companion casting (permanent — plain HTTP, all in tool code) ----

    private val _plexPlayer = MutableStateFlow<PlexPlayer?>(null)
    val plexPlayer: StateFlow<PlexPlayer?> = _plexPlayer

    /**
     * Whether the fader can move a Companion player's volume — decided by
     * evidence, not by the player's word.
     *
     * A player that reports a level it doesn't own is the awkward case, and
     * the common one: an Apple TV feeding a receiver over optical says
     * `volume="0"` for ever, takes the command, and changes nothing — the
     * amplifier owns the sound. So [Unknown] is where every player starts,
     * a drag is allowed as a *probe*, and the answer comes from what the
     * player reports afterwards. Once [NotOurs], the fader is disabled rather
     * than left to lie.
     */
    enum class PlexVolume { Unknown, Controllable, NotOurs }

    private val _plexVolume = MutableStateFlow(PlexVolume.Unknown)
    val plexVolume: StateFlow<PlexVolume> = _plexVolume

    /** The level last asked for (0–100) while proving control, if any. */
    private var plexVolumeAsked: Int? = null
    private var plexVolumeProbes = 0

    private var plexJob: Job? = null
    private var plexQueue: PlexQueue? = null
    private var plexPushedIds: List<String> = emptyList()

    /** Companion wants a fresh command id per request, per controller. */
    private var plexCommandId = 0

    /**
     * How far into [_queue] the player has been given, or -1 when it holds the
     * lot.
     *
     * A play queue names every track in one `uri`, and Plex takes that in the
     * *request line* — a 500-track queue is 5 KB of URL and the server answers
     * "Error parsing HTTP request" before reading a byte of it. So a long queue
     * goes over in a window, topped up in place as the player nears the end,
     * which costs nothing audible: appending to a play queue is an edit, not a
     * new queue.
     */
    private var plexWindowEnd = -1

    /** Whether the player is pushing its timeline rather than being asked. */
    @Volatile
    private var plexSubscribed = false

    /** When the last push arrived, so silence can be noticed. */
    @Volatile
    private var plexLastPush = 0L

    /**
     * The little HTTP server a subscription pushes to. Built once and reused:
     * a port opened per cast would be a port leaked per cast.
     */
    private val plexListener = PlexCompanionListener { body ->
        plexLastPush = System.currentTimeMillis()
        plexTimelineFrom(body)?.let { t ->
            if (t.state == "stopped") {
                // The player saying it has stopped is the one push worth
                // acting on rather than mirroring.
                scope.launch(Dispatchers.Main.immediate) { plexCastEnded() }
            } else {
                scope.launch(Dispatchers.Main.immediate) { adoptPlexTimeline(t) }
            }
        }
    }

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
     * The queue index the renderer's next slot has been armed for — or offered
     * and refused.
     *
     * Arming is attempted from the poll loop now rather than once per track, so
     * without this a renderer that doesn't implement SetNextAVTransportURI would
     * be asked again every second for the whole lead window.
     */
    private var castNextArmedFor: Int? = null

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
        scope.launch(Dispatchers.Main.immediate) {
            p.pause()
            p.seekToIndex(0)
        }
        _index.value = 0
        _positionMs.value = 0L
        hasPlayed = false
    }

    /** Attach the audio stack from the current activity. Idempotent per process. */
    fun bind(audio: LightAudio) {
        if (player != null) return
        // Detached: playback lives in the SDK's own service, so it survives the
        // screen going off — and the activity going away — officially.
        val p = audio.newPlayer(LightAudioUsage.Music, LightAudioPlayback.Detached)
        player = p
        p.onItemRepeated = {
            // Repeat-one wrapped the current item. A full track wraps to its
            // own beginning and needs nothing; a server-seeked remainder would
            // loop its last few seconds forever, so it is rebuilt from the
            // track's real start. On Main: the player invokes this on its own
            // thread, which is Main by the looper rule.
            if (streamOffsetMs > 0L) seekTo(0)
        }
        p.onPlaybackError = { error ->
            if (error.refusedByGate()) {
                // Not the server's failure and not the network's: the app's
                // own rule, at the player's own connection. Nothing to fall
                // back to and nothing to latch — the server is not unreachable,
                // it is unasked.
                stallForWifi()
            } else {
                // A bad HTTP status is proof the server is *there*: it answered, it
                // just didn't like the request. Treating that as "unreachable" takes
                // the whole library down to downloads-only over one bad URL, and it
                // stays that way until something else proves otherwise.
                fallBackOffline(
                    serverAnswered = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                )
            }
        }
        // While casting, the renderer is the source of truth for these — the local
        // player sits paused and would otherwise report position 0 / not-playing
        // straight over the cast state.
        scope.launch {
            p.isPlaying.collect { playing ->
                if (isCasting) return@collect
                _isPlaying.value = playing
                if (playing) {
                    hasPlayed = true
                } else if (hasPlayed) {
                    rewindIfQueueFinished(p)
                }
            }
        }
        // A server-seeked stream starts at 0:00 of a shorter file, so both
        // readouts are shifted back into the track's own timeline.
        scope.launch { p.positionMs.collect { if (!isCasting) _positionMs.value = it + streamOffsetMs } }
        scope.launch {
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
                if (streamOffsetMs > 0L &&
                    known > 0L &&
                    reported >= known - IGNORED_OFFSET_SLACK_MS
                ) {
                    val target = streamOffsetMs
                    streamOffsetMs = 0L
                    scope.launch(Dispatchers.Main.immediate) { p.seekTo(target) }
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
                    streamOffsetMs > 0 -> known.takeIf { it > 0L } ?: (reported + streamOffsetMs)
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
        scope.launch {
            combine(p.currentMediaItemIndex, _queue, replayGain) { idx, q, on ->
                val db = if (on) q.getOrNull(idx)?.gainDb else null
                db?.let { 10.0.pow(it / 20.0).toFloat().coerceAtMost(1f) } ?: 1f
            }.distinctUntilChanged().collect { factor ->
                withContext(Dispatchers.Main.immediate) { p.playbackGain = factor }
            }
        }
        scope.launch {
            p.currentMediaItemIndex.collect { idx ->
                if (isCasting) return@collect
                if (idx >= 0 && idx != _index.value) {
                    // AmpDur: temporary — see above. What the player landed on,
                    // against what the library says it should be.
                    val landed = _queue.value.getOrNull(idx)
                    android.util.Log.i(
                        "AmpDur",
                        "track pIdx=$idx uiIdx=${_index.value} id=${landed?.id} " +
                            "\"${landed?.title}\" libDur=${landed?.durationMs} " +
                            "pDur=${p.durationMs.value} off=$streamOffsetMs " +
                            "local=${landed?.id?.let { localFiles[it] }?.let { (f, fmt) ->
                                "${f.name} ${f.length()}B ${fmt.id}"
                            } ?: "stream"}",
                    )
                    _index.value = idx
                    // A new track is a fresh stream from its own beginning —
                    // the position as much as the offset. Left standing, the
                    // position stayed the *outgoing* track's until the player
                    // emitted its first reading for this one, and everything
                    // that read it in between got that: the readout showed
                    // 5:23 against a 3:52 track, and reportTimeline below sent
                    // the same number under the new track's id and duration.
                    // Plex answered 400 "Time value greater than duration"
                    // where the new track was shorter, and where it was longer
                    // took the number and wrote it in as a resume point.
                    //
                    // Safe to clear here: the paths that legitimately start a
                    // track part-way — a restored queue, a server seek — set
                    // _index before the player moves, which is what keeps them
                    // out of this block at all. See rebuildQueueAt.
                    streamOffsetMs = 0
                    _positionMs.value = 0L
                    // Leaving a server-seeked remainder is the moment to put
                    // the whole song back in its slot, so any later visit —
                    // a repeat-all wrap, a tap on the queue row — plays the
                    // track and not the tail the last seek left behind.
                    offsetItemTrackId?.let { staleId ->
                        offsetItemTrackId = null
                        val q = _queue.value
                        if (q.getOrNull(idx)?.id == staleId) {
                            // Arrived *onto* a stale remainder (a wrap over a
                            // slot that never got restored): rebuild it live.
                            scope.launch(Dispatchers.Main.immediate) { seekTo(0) }
                        } else {
                            val stubIdx = q.indexOfFirst { it.id == staleId }
                            val original = q.getOrNull(stubIdx)
                            if (stubIdx >= 0 && original != null) {
                                scope.launch(Dispatchers.Main.immediate) {
                                    p.replaceRange(stubIdx, stubIdx + 1, listOf(original.toAudioItem()))
                                }
                            }
                        }
                    }
                    // A new track is a new session too, or Plex sees the same
                    // one just keep changing what it's playing rather than one
                    // track ending and the next beginning. The one it replaces
                    // is told it's stopped first, so it doesn't just sit in
                    // `/status/sessions` until its lease times out.
                    _queue.value.getOrNull(idx)?.let { openSessionFor(it.id) }
                    // Seeded from the library rather than waited for: a chunked
                    // stream may never report a duration at all, and the player
                    // won't emit again to give the collector above a second go.
                    _queue.value.getOrNull(idx)?.durationMs
                        ?.takeIf { it > 0L }
                        ?.let { _durationMs.value = it }
                    reportTimeline(TimelineState.PLAYING)
                }
                announceNowPlaying()
            }
        }
        // Anything that changes what plays next — an edit, a jump, a repeat mode —
        // has to be pushed to the renderer, which is holding a URI we chose
        // earlier. Debounced because a single edit moves several of these at once.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch {
            combine(_queue, _index, _repeatMode) { _, _, _ -> Unit }
                .debounce(CAST_REQUEUE_DEBOUNCE_MS)
                .collect {
                    _castRenderer.value?.let {
                        // What plays next has changed, so whatever the renderer
                        // was armed with is stale and it gets to be reconsidered.
                        castNextArmedFor = null
                        armNextIfDue(it)
                    }
                }
        }
        // The server answering again is the signal to stop pinning playback to
        // local files; the next track resolves normally.
        scope.launch {
            App.serverReachable.collect { reachable ->
                if (reachable) {
                    forceOffline = false
                    retriedTrackId = null
                    streamFailed.clear()
                }
            }
        }
        scope.launch {
            Connectivity.unmetered.collect {
                unmetered = it
                reresolveQueue()
                if (it) cueIfParked()
            }
        }
        // The rule can also open by the mode changing — Wi-Fi Only switched
        // off while on cellular — with nothing about the connection changing.
        scope.launch { App.dataMode.collect { cueIfParked() } }
        // Shared with the library rather than queried again here — one read of
        // the downloads table feeds both. It also fixes a subtler thing: this
        // used to resolve `dao` once, at bind, and so stayed subscribed to
        // whichever database was current at boot. After a source switch the map
        // still described the *previous* source's downloads, whose track ids
        // match nothing in the new one, so downloaded audio quietly stopped
        // being used at all.
        scope.launch {
            App.library.downloadFiles.collect { rows ->
                localFiles = rows.mapNotNull { row ->
                    downloads.existing(App.source.value.id, row.fileName)?.let { file ->
                        row.trackId to (file to StreamFormat.fromId(row.format))
                    }
                }.toMap()
                reresolveQueue()
            }
        }
        // Mirror the system media volume so the fader reflects it (and the
        // hardware volume keys, which drive the same stream).
        // The hardware rocker moves Android's own media volume, which while
        // casting controls a player that isn't making the sound. Forwarding it to
        // the renderer makes the buttons act on whatever is actually playing —
        // without intercepting the keys, which the tool sandbox can't do.
        scope.launch {
            p.deviceVolume.collect { level ->
                _volume.value = level
                val renderer = _castRenderer.value ?: return@collect
                DlnaCast.setVolume(renderer, level)
            }
        }

        // A play is submitted part-way through rather than at the start, so the
        // position is what decides when — see maybeSubmitPlay.
        scope.launch { _positionMs.collect { maybeSubmitPlay(it) } }
        // Snapshot the queue periodically rather than on every position tick: the
        // point is to survive a kill, and a few seconds of lost progress is a fair
        // trade against writing to DataStore once a second forever.
        scope.launch {
            while (true) {
                delay(SAVE_STATE_INTERVAL_MS)
                persistState()
            }
        }

        // Plex tears a session out of `/status/sessions` if nothing pings it
        // for a while — the same idea as a UPnP lease. Only while something is
        // actually playing: a paused session was already told once and doesn't
        // need repeating, and an idle queue has no session at all.
        scope.launch {
            while (true) {
                delay(TIMELINE_INTERVAL_MS)
                if (_isPlaying.value) reportTimeline(TimelineState.PLAYING)
            }
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
     */
    suspend fun restoreState(library: List<Track>) {
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
        val byId = library.associateBy { it.id }
        val tracks = saved.trackIds.mapNotNull { byId[it] }
        if (tracks.isEmpty()) return
        val index = tracks.indexOfFirst { it.id == saved.currentId }.takeIf { it >= 0 } ?: 0

        val p = player ?: return
        val position = saved.positionMs
        // ExoPlayer's application looper is Main and it enforces that: touching the
        // player from this coroutine's Default dispatcher throws "Player is accessed
        // on the wrong thread" and takes the app down on launch. Every other caller
        // reaches setQueue from a composable, i.e. already on Main.
        withContext(Dispatchers.Main.immediate) {
            _queue.value = tracks
            lastScrobbledId = null
            nowPlayingId = null
            // Start position goes in at prepare time: seekTo() clamps to the
            // duration, which is still unknown this early, so it would land on 0.
            rebuildQueueAt(p, tracks, index, position)
            // Deliberately not p.play(): restoring should cue the track up, not
            // start making noise on its own the moment the app is opened.
            p.pause()
        }
    }

    fun release() {
        // A Companion player is deliberately left playing: it walks its own
        // queue and answers to the server, so closing the app that steered it
        // is no reason to silence the room — that's how every Plex remote
        // behaves. Only the steering stops.
        if (_plexPlayer.value != null) {
            android.util.Log.i("AmpPlex", "leaving the player playing")
            // The subscription goes even though the playback stays: pushes to
            // a port that is about to close would have the player talking to
            // nothing.
            endPlexSubscription(plexClient(), _plexPlayer.value)
            plexJob?.cancel()
            plexJob = null
            _plexPlayer.value = null
            plexQueue = null
        }
        // A renderer keeps playing whatever URI it was handed, so leaving without
        // telling it to stop strands audio on the speaker while the app shows
        // "This device". Bounded so teardown can't hang on an unreachable device.
        _castRenderer.value?.let { renderer ->
            castJob?.cancel()
            castJob = null
            _castRenderer.value = null
            runBlocking { withTimeoutOrNull(CAST_STOP_TIMEOUT_MS) { DlnaCast.stop(renderer) } }
        }
        // One last snapshot before the player goes: an orderly exit should not
        // lose the few seconds since the last tick. The session is closed out in
        // the same breath — stop() does it when the queue is emptied, but simply
        // leaving never went through stop(), so the server was left showing this
        // phone as playing until the session's lease ran out.
        runBlocking {
            withTimeoutOrNull(SAVE_STATE_TIMEOUT_MS) {
                sessionTrackId?.let { reportTimelineStoppedNow(it) }
                persistState(final = true)
            }
        }
        sessionTrackId = null
        player?.release()
        player = null
    }

    /**
     * The blocking form of [reportTimelineStopped], for teardown.
     *
     * The fire-and-forget version launches into [scope], which is being torn
     * down around it — the request would be cancelled before it left.
     */
    private suspend fun reportTimelineStoppedNow(trackId: String) {
        if (LocalLibrary.isLocal(trackId)) return
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        runCatching {
            client.reportTimeline(sessionIdFor(trackId), trackId, TimelineState.STOPPED, 0L, 0L)
        }
    }

    // --- Playback controls ---------------------------------------------------

    /** Replace the queue with [tracks] and start at [startIndex]; see [queueName]. */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0, name: String? = null) {
        if (tracks.isEmpty()) return
        _queueName.value = name
        // A new queue is a new listening session, so the ids derived from it are
        // new too — otherwise playing the same track again an hour later would
        // report itself as the same session still going.
        playbackSession = UUID.randomUUID().toString()
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        if (_shuffle.value) {
            val first = tracks[start]
            val rest = tracks.toMutableList().apply { removeAt(start) }.shuffled()
            setQueue(capped(listOf(first) + rest, 0).first, 0)
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
     */
    private fun capped(tracks: List<Track>, startIndex: Int): Pair<List<Track>, Int> {
        if (tracks.size <= MAX_QUEUE) return tracks to startIndex
        val first = (startIndex - QUEUE_LOOKBACK).coerceIn(0, tracks.size - MAX_QUEUE)
        return tracks.subList(first, first + MAX_QUEUE) to (startIndex - first)
    }

    fun togglePlayPause() {
        _plexPlayer.value?.let { target ->
            val wasPlaying = _isPlaying.value
            _isPlaying.value = !wasPlaying
            scope.launch {
                plexClient()?.companionCommand(target, if (wasPlaying) "pause" else "play", ++plexCommandId)
            }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            val wasPlaying = _isPlaying.value
            _isPlaying.value = !wasPlaying
            scope.launch {
                if (wasPlaying) DlnaCast.pause(renderer) else DlnaCast.resume(renderer)
            }
            reportTimeline(if (wasPlaying) TimelineState.PAUSED else TimelineState.PLAYING)
            return
        }
        val p = player ?: return
        val wasPlaying = _isPlaying.value
        // Resuming needs the server's decision first, exactly as [play] does —
        // this is the button that actually starts a restored queue.
        if (wasPlaying) p.pause() else withStreamDecision { p.play() }
        reportTimeline(if (wasPlaying) TimelineState.PAUSED else TimelineState.PLAYING)
    }

    fun play() {
        _plexPlayer.value?.let { target ->
            _isPlaying.value = true
            scope.launch { plexClient()?.companionCommand(target, "play", ++plexCommandId) }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            _isPlaying.value = true
            scope.launch { DlnaCast.resume(renderer) }
            reportTimeline(TimelineState.PLAYING)
            return
        }
        // Parked by the rule: a play on the player as it stands is silence
        // (it is in its error state). Prepare it again where it stood, or,
        // if the rule still says no, leave it parked and let the screen say so.
        if (_waitingForWifi.value) {
            resumeAfterWait(play = true)
            return
        }
        // The decision first, then the sound — see MusicServer.prepareStream.
        // Starting the player before it is settled is what left a restored
        // queue dead: the stream went out with no decision behind it and came
        // back 400, which then took the whole library offline.
        withStreamDecision { player?.play() }
        reportTimeline(TimelineState.PLAYING)
    }

    /** Whether the player's own connection was refused by the network rule. */
    private fun PlaybackException.refusedByGate(): Boolean =
        generateSequence<Throwable>(this) { it.cause }.any { it is LightAudioNetworkBlockedException }

    private fun stallForWifi() {
        _waitingForWifi.value = true
        _isPlaying.value = false
        android.util.Log.i("AmpNet", "refused by the rule at idx=${_index.value} pos=${_positionMs.value}")
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
            _waitingForWifi.value = false
            return
        }
        if (tracks[index].source() is LightAudioSource.UrlSource && !NetworkGate.isOpen()) return
        _waitingForWifi.value = false
        rebuildQueueAt(p, tracks, index, _positionMs.value)
        if (play) p.play() else p.pause()
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
        if (!_waitingForWifi.value || isCasting) return
        withContext(Dispatchers.Main.immediate) { resumeAfterWait(play = false) }
    }

    /**
     * Next/previous while parked: move the marker and try the neighbour the
     * way play would — a downloaded track plays, a stream is refused again
     * and the screen says so on the new row. Repeat's wrap is honoured the way
     * [advanceCast] honours it; off the end, nothing.
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
        val track = currentTrack.value
        val source = queuedSources.getOrNull(_index.value)
        val client = serverClient.value
        if (track == null || client == null || source !is LightAudioSource.UrlSource) {
            start()
            return
        }
        scope.launch {
            withTimeoutOrNull(STREAM_DECISION_TIMEOUT_MS) {
                client.prepareStream(track.id, effectiveFormat(), sessionIdFor(track.id))
            }
            withContext(Dispatchers.Main.immediate) { start() }
        }
    }

    fun pause() {
        _plexPlayer.value?.let { target ->
            _isPlaying.value = false
            scope.launch { plexClient()?.companionCommand(target, "pause", ++plexCommandId) }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            _isPlaying.value = false
            scope.launch { DlnaCast.pause(renderer) }
            reportTimeline(TimelineState.PAUSED)
            return
        }
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
        _plexPlayer.value?.let { target ->
            _positionMs.value = ms
            scope.launch { plexClient()?.companionSeek(target, ms, ++plexCommandId) }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            // The last moments of a track are not worth a seek. Seeking means
            // pushing the stream again, probing it and seeking it — seconds —
            // after which the renderer stops almost at once, and a stop before
            // any poll had seen it playing read as a track that never started:
            // a six-second wait, then the track again from the top. Dragging to
            // the end means the next track, and that is what Next does.
            val total = _durationMs.value
            if (total > 0L && total - ms <= CAST_SEEK_END_SLACK_MS) {
                android.util.Log.i("AmpCast", "seek $ms of $total is the end → next")
                castJob?.cancel()
                castJob = scope.launch {
                    if (advanceCast(renderer)) pollRenderer(renderer) else DlnaCast.stop(renderer)
                }
                return
            }
            _positionMs.value = ms
            // In place when the renderer can, pushed again when it can't — see
            // seekCast. The track was playing a moment ago, so a stop after
            // this is the end of it, not a failure to start.
            castJob?.cancel()
            castJob = scope.launch {
                if (seekCast(renderer, ms)) pollRenderer(renderer, playedBefore = true)
            }
            return
        }
        val p = player ?: return
        val track = _queue.value.getOrNull(_index.value)
        if (track == null || !needsServerSeek(track)) {
            p.seekTo(ms)
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
    private fun needsServerSeek(track: Track, askPlayer: Boolean = true): Boolean {
        if (track.source() is LightAudioSource.FileSource) return false
        if (track.id in seekNeedsReload) return true
        // A queue being rebuilt has nothing prepared to ask: the player's
        // answer would be about the item it held before.
        if (!askPlayer) return effectiveFormat() != StreamFormat.RAW
        // Ask the player, which knows what actually arrived. Asking for mp3 and
        // getting mp3 means the server sent the file untouched — with a length
        // and byte ranges — and it then ignores timeOffset, because it is not
        // transcoding and has nothing to offset. Inferring "not RAW, therefore a
        // transcode" picks server-seeking for exactly the streams where only
        // native seeking works, and the failure is silent: the audio restarts
        // and the clock claims the seek landed.
        player?.let { if (it.isCurrentItemSeekable) return false }
        // Nothing playing yet to ask — fall back to the request we made.
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
            if (_castRenderer.value != null) return@launch
            if (_queue.value.getOrNull(_index.value)?.id != track.id) return@launch
            val landed = p.positionMs.value + streamOffsetMs
            if (kotlin.math.abs(landed - target) <= SEEK_TOLERANCE_MS) return@launch
            android.util.Log.i("AmpSeek", "missed: wanted $target, landed $landed — reloading")
            seekNeedsReload += track.id
            serverSeek(p, track, target)
        }
    }

    private fun serverSeek(p: LightAudioPlayer, track: Track, ms: Long) {
        val client = serverClient.value ?: return
        val target = ms.coerceAtLeast(0L)
        val url = client.streamUrl(
            track,
            effectiveFormat(),
            timeOffsetSeconds = (target / 1000).toInt(),
            // Same as toAudioItem, and it matters even more here: an estimated
            // length makes ExoPlayer believe the transcode is bounded and
            // range-seekable, and its end-of-file probe then asks for bytes the
            // stream doesn't have — Navidrome answers 416 and playback errors.
            // That was the restored queue "acting weird", and every repeat wrap
            // of an offset item dying.
            estimateContentLength = false,
            sessionId = sessionIdFor(track.id),
        )
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
            // Intent, not the instantaneous state: isPlaying is false during a
            // rebuffer, and a seek issued in that window read it as "paused"
            // and left the replacement stream parked.
            val wasPlaying = p.playWhenReady
            val at = _index.value
            // Replaced in place rather than removed and re-added. Taking the
            // playing item out moves the player's idea of the current index —
            // twice, once each way — and the collector above reads that as a new
            // track and clears streamOffsetMs. The clock then counts up from zero
            // while the audio plays from where it was asked to.
            p.replaceRange(at, at + 1, listOf(item))
            // The replacement is a stream that already starts at the target, so
            // it has to play from its own beginning: Media3 keeps the old
            // position when the item it replaces looks like the same one, which
            // would land us at the offset twice over. This sets the index the
            // player is already on, so nothing reads it as a track change.
            p.seekToIndex(at)
            if (wasPlaying) p.play()
        }
    }

    /** Stop playback and empty the queue. */
    fun stop() {
        reportTimeline(TimelineState.STOPPED)
        sessionTrackId = null
        _plexPlayer.value?.let { target ->
            val client = plexClient()
            plexJob?.cancel()
            plexJob = null
            _plexPlayer.value = null
            plexQueue = null
            plexWindowEnd = -1
            // Close the subscription too, as every other way out of a cast
            // does. Left open, the player goes on pushing its timeline at a
            // socket nobody is reading and then at a dead port, and a player
            // whose requests keep failing does not stay well — see the note on
            // playerLock in PlexClient. Nothing else can clean it up either:
            // the player's own "stopped" push routes to plexCastEnded, which
            // returns immediately once _plexPlayer is null, as it now is.
            endPlexSubscription(client, target)
            scope.launch { client?.companionCommand(target, "stop", ++plexCommandId) }
        }
        _castRenderer.value?.let { renderer ->
            castJob?.cancel()
            castJob = null
            _castRenderer.value = null
            scope.launch { DlnaCast.stop(renderer) }
        }
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
        _queueName.value = null
        _index.value = -1
        _waitingForWifi.value = false
        queuedSources = emptyList()
        streamOffsetMs = 0
        offsetItemTrackId = null
        _positionMs.value = 0
        _durationMs.value = 0
        val p = player ?: return
        // Same rule as restoreState: ExoPlayer's looper is Main and it throws
        // rather than tolerating anything else. Unlike the rest of this class,
        // stop() is not only reached from a composable — a source switch calls it
        // from a collector on Default — so it has to place itself. `immediate`
        // means a caller already on Main is unaffected and still synchronous.
        scope.launch(Dispatchers.Main.immediate) {
            p.stop()
            p.setMediaQueue(emptyList())
        }
    }
    fun skipForward() = player?.skipForward()
    fun skipBack() = player?.skipBack()

    fun next() {
        _plexPlayer.value?.let { target ->
            scope.launch { plexClient()?.companionCommand(target, "skipNext", ++plexCommandId) }
            return
        }
        if (isCasting) {
            jumpTo(_index.value + 1)
            return
        }
        if (_waitingForWifi.value) {
            stepWhileWaiting(+1)
            return
        }
        player?.skipToNext()
    }

    /** Restart the current track if we're past the intro, otherwise go to the previous one. */
    fun previous() {
        _plexPlayer.value?.let { target ->
            scope.launch {
                if (_positionMs.value > PREVIOUS_RESTART_MS) {
                    plexClient()?.companionSeek(target, 0, ++plexCommandId)
                } else {
                    plexClient()?.companionCommand(target, "skipPrevious", ++plexCommandId)
                }
            }
            return
        }
        if (isCasting) {
            if (_positionMs.value > PREVIOUS_RESTART_MS) seekTo(0) else jumpTo(_index.value - 1)
            return
        }
        if (_waitingForWifi.value) {
            stepWhileWaiting(if (_positionMs.value > PREVIOUS_RESTART_MS) 0 else -1)
            return
        }
        val p = player ?: return
        if (_positionMs.value > PREVIOUS_RESTART_MS) {
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
        _plexPlayer.value?.let { target ->
            val track = _queue.value[index]
            _index.value = index
            _positionMs.value = 0
            scope.launch {
                if (plexPushedIds.contains(track.id)) {
                    // Within the queue it already holds — see companionSkipTo.
                    plexClient()?.companionSkipTo(target, track.id, ++plexCommandId)
                    // A player keeps its own window of a play queue, and it
                    // need not hold as much of it as we handed over. A skipTo
                    // to something outside *its* window is accepted and then
                    // quietly not acted on — the player stays put and the
                    // mirror puts the row back, which is exactly what a
                    // distant song refusing to play looks like. So check where
                    // it actually went, and rebuild around the track when it
                    // didn't go there. Same shape as verifyNativeSeek: trust
                    // the command, then confirm it landed.
                    delay(PLEX_SKIP_VERIFY_MS)
                    if (_plexPlayer.value?.id == target.id &&
                        _queue.value.getOrNull(_index.value)?.id != track.id
                    ) {
                        android.util.Log.i("AmpPlex", "skipTo idx=$index didn't land — rebuilding around it")
                        _index.value = index
                        _positionMs.value = 0
                        repushPlexQueue(positionMs = 0L)
                    }
                } else {
                    // Outside the window the player was given, so there is
                    // nothing there to skip to — asking left it where it was
                    // and the poll put the row back. Rebuild the window around
                    // the track instead; a jump is already a change of what is
                    // playing, so starting it is what was asked for.
                    android.util.Log.i("AmpPlex", "jump to idx=$index is outside the window — rebuilding")
                    repushPlexQueue(positionMs = 0L)
                }
            }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            _index.value = index
            _positionMs.value = 0
            castJob?.cancel()
            castJob = scope.launch {
                if (startCastTrack(renderer)) pollRenderer(renderer)
            }
            return
        }
        val p = player ?: return
        p.seekToIndex(index)
        _index.value = index
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
        _queue.value = _queue.value + tracks
        p.addItems(tracks.map { it.toAudioItem() })
        plexQueueEdit { client, queue -> client.addToPlayQueue(queue, tracks.map { it.id }, next = false) }
    }

    /**
     * Put these tracks immediately after the one playing.
     *
     * A track already in the queue is *moved* rather than copied: "play next" on
     * something queued for later means bring it forward, and inserting a second
     * copy would leave it to play twice.
     */
    fun playNext(tracks: List<Track>) {
        val p = player ?: return
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) {
            playQueue(tracks, 0)
            return
        }
        for (track in tracks) {
            val from = _queue.value.indexOfFirst { it.id == track.id }
            val cur = _index.value
            if (from < 0) {
                val at = (cur + 1).coerceIn(0, _queue.value.size)
                _queue.value = _queue.value.toMutableList().apply { add(at, track) }
                p.addItemAt(at, track.toAudioItem())
                plexQueueEdit { client, queue -> client.addToPlayQueue(queue, listOf(track.id), next = true) }
                continue
            }
            if (from == cur) continue
            // Destinations are read after the removal, so a track pulled from
            // behind the cursor lands one place earlier than one pushed forward.
            moveInQueue(from, if (from < cur) cur else cur + 1)
        }
    }

    /**
     * Keep what is playing, replace everything after it.
     *
     * For a radio started from the song already playing: the song carries on
     * untouched and the radio becomes what follows, in place of whatever was
     * queued. The queue's name changes with its contents — see [queueName].
     * Upcoming items are dropped back to front so each removal can't shift the
     * ones still to go, as [removeFromQueue] does.
     */
    fun replaceUpcoming(tracks: List<Track>, name: String? = null) {
        val p = player ?: return
        val q = _queue.value
        val cur = _index.value
        if (cur !in q.indices) {
            playQueue(tracks, 0, name)
            return
        }
        val droppedIds = q.drop(cur + 1).map { it.id }
        for (i in q.lastIndex downTo cur + 1) p.removeItem(i)
        _queue.value = q.take(cur + 1) + tracks
        if (tracks.isNotEmpty()) p.addItems(tracks.map { it.toAudioItem() })
        _queueName.value = name
        // Everything after the playing track goes, then the new set arrives —
        // expressed as edits so the song already playing carries on untouched,
        // which is the whole point of replacing only what is upcoming.
        plexQueueEdit { client, queue ->
            val emptied = droppedIds.fold<String, PlexQueue?>(queue) { acc, id ->
                acc?.let { client.removePlayQueueItem(it, id) }
            }
            if (tracks.isEmpty()) emptied
            else emptied?.let { client.addToPlayQueue(it, tracks.map { t -> t.id }, next = false) }
        }
    }

    fun removeFromQueue(index: Int) {
        val p = player ?: return
        val q = _queue.value
        if (index !in q.indices) return
        val playingId = q.getOrNull(_index.value)?.id
        val goneId = q[index].id
        p.removeItem(index)
        val newQueue = q.toMutableList().apply { removeAt(index) }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        plexQueueEdit { client, queue -> client.removePlayQueueItem(queue, goneId) }
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
        val p = player ?: return
        if (ids.isEmpty()) return
        val q = _queue.value
        val targets = q.indices.filter { q[it].id in ids }
        if (targets.isEmpty()) return
        val playingId = q.getOrNull(_index.value)?.id
        targets.sortedDescending().forEach { p.removeItem(it) }
        val gone = targets.toSet()
        val goneIds = targets.map { q[it].id }
        val newQueue = q.filterIndexed { i, _ -> i !in gone }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        plexQueueEdit { client, queue ->
            // One at a time, each answer carrying the places for the next.
            goneIds.fold<String, PlexQueue?>(queue) { acc, id ->
                acc?.let { client.removePlayQueueItem(it, id) }
            }
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        val p = player ?: return
        val q = _queue.value
        if (from !in q.indices || to !in q.indices || from == to) return
        val playingId = q.getOrNull(_index.value)?.id
        val movedId = q[from].id
        p.moveItem(from, to)
        val newQueue = q.toMutableList().apply { add(to, removeAt(from)) }
        _queue.value = newQueue
        _index.value = indexAfterEdit(newQueue, playingId)
        // Plex places an item *after* another one, so the move is expressed as
        // whoever it now follows — nothing when it lands at the front.
        val afterId = newQueue.getOrNull(to - 1)?.id
        plexQueueEdit { client, queue -> client.movePlayQueueItem(queue, movedId, afterId) }
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
        player?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.TRACK -> Player.REPEAT_MODE_ONE
            RepeatMode.QUEUE -> Player.REPEAT_MODE_ALL
        }
        _plexPlayer.value?.let { target ->
            // Companion's values: 0 off, 1 the track, 2 the whole queue.
            val plexRepeat = when (mode) {
                RepeatMode.OFF -> "0"
                RepeatMode.TRACK -> "1"
                RepeatMode.QUEUE -> "2"
            }
            scope.launch {
                plexClient()?.companionSetParameters(target, listOf("repeat" to plexRepeat), ++plexCommandId)
            }
        }
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
        _plexPlayer.value?.let { target ->
            // A player whose volume isn't its own to move takes no orders —
            // see judgePlexVolume. Until that is settled, a drag is the probe:
            // it goes out, and the next polls say whether it landed. The
            // phone's own level is left alone either way — it has no business
            // changing while the sound is elsewhere.
            if (_plexVolume.value == PlexVolume.NotOurs) return
            val asked = (v * 100).roundToInt()
            plexVolumeAsked = asked
            _volume.value = v
            scope.launch {
                plexClient()?.companionSetParameters(
                    target,
                    listOf("volume" to asked.toString()),
                    ++plexCommandId,
                )
            }
            return
        }
        _volume.value = v
        val renderer = _castRenderer.value
        if (renderer != null) {
            scope.launch { DlnaCast.setVolume(renderer, v) }
        }
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
        val on = !_shuffle.value
        _shuffle.value = on
        val q = _queue.value
        val current = _index.value
        if (q.isEmpty() || current < 0) {
            preShuffleOrder = if (on) q.takeIf { it.isNotEmpty() } else null
            return
        }
        val head = q.take(current + 1)
        val tail = q.drop(current + 1)
        val newTail = if (on) {
            preShuffleOrder = q
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
        // Reordering everything upcoming is the one edit not worth expressing
        // as a move apiece — a shuffled hundred is a hundred round trips. The
        // player gets a fresh queue, and pays a blip for it.
        if (_plexPlayer.value != null) scope.launch { repushPlexQueue() }
    }

    // --- Plex Companion casting ----------------------------------------------
    //
    // The other way sound leaves the phone, and a different animal from DLNA:
    // a Plex player (the Apple TV app, another Plexamp…) is handed a
    // *server-side play queue* and walks it itself — gapless, and skippable
    // from its own remote. So this side mirrors rather than drives: the poll
    // adopts whatever the player reports, including track changes and pauses
    // made on the TV. Commands are relayed through the server the same way the
    // official controllers do it, so nothing here ever connects to the player.
    // Only a Plex source can do this, so only a Plex source shows it.

    private fun plexClient(): PlexClient? = serverClient.value as? PlexClient

    /**
     * Companion players for the active Plex source. Empty on any other source.
     *
     * Three answers to the same question, because no one of them is complete:
     * the server knows the players it discovered itself, plex.tv knows the
     * account's devices — and forgets the ones that sleep — and [PlexGdm] asks
     * the network, which is the only one that can see a player nobody has
     * heard from lately. A device answering GDM is on this Wi-Fi and listening,
     * which is the whole question.
     */
    suspend fun findPlexPlayers(): List<PlexPlayer> {
        val client = plexClient() ?: return emptyList()
        // The server's list and plex.tv are both remote lookups, and on a
        // metered link in Wi-Fi Only they are worse than merely costly: they
        // name players on a network this phone isn't on, so the page offers a
        // living room it cannot reach. GDM is a broadcast to whatever network
        // this is, costs nothing off it, and answers the question that matters.
        val listed = if (metadataAllowed()) client.companionPlayers() else emptyList()
        // GDM is a broadcast to the local network, so off Wi-Fi there is nobody
        // to broadcast to and nothing that could answer — a player reachable
        // from here would have to be on this network, and there is no network.
        // It costs no cellular data either way; what it costs is the wait for
        // its own timeout, on a page that cannot find anything anyway.
        val onNetwork = if (Connectivity.isOnWifi()) PlexGdm.findPlayers() else emptyList()
        val seen = listed.map { it.id }.toMutableSet()
        val extra = onNetwork.filter { seen.add(it.id) }
        android.util.Log.i("AmpPlex", "gdm found ${onNetwork.map { it.name }}, new here ${extra.map { it.name }}")
        return listed + extra
    }

    /**
     * Move playback to [target]: push the queue to the server, point the player
     * at it from the position we'd reached, and start mirroring its reports.
     */
    fun castToPlex(target: PlexPlayer) {
        val client = plexClient() ?: return
        // One route at a time; a DLNA renderer in progress is stopped quietly.
        if (_castRenderer.value != null) stopCasting(resumeLocally = false)
        player?.pause()
        _plexPlayer.value = target
        _plexVolume.value = PlexVolume.Unknown
        plexVolumeAsked = null
        plexVolumeProbes = 0
        val from = _positionMs.value
        plexWindowEnd = -1
        plexJob?.cancel()
        plexJob = scope.launch {
            if (_queue.value.isEmpty()) { _plexPlayer.value = null; return@launch }
            // A device named only by plex.tv is named by an address the account
            // has on file, and an address is not an identity — a lease that
            // moved would have us handing this queue to whatever answers there
            // now. Ask it who it is before it gets any audio.
            if (!target.identityKnown && !client.playerIsWhoWeThink(target)) {
                android.util.Log.i("AmpPlex", "${target.name} isn't who plex.tv said — not casting")
                _plexPlayer.value = null
                return@launch
            }
            // The same push a new queue takes — windowed, so a long queue
            // doesn't arrive as five kilobytes of URL.
            repushPlexQueue(positionMs = from)
            if (plexQueue == null) {
                android.util.Log.i("AmpPlex", "play queue refused — staying on this device")
                _plexPlayer.value = null
                return@launch
            }
            if (_repeatMode.value == RepeatMode.TRACK) {
                client.companionSetParameters(target, listOf("repeat" to "1"), ++plexCommandId)
            }
            subscribeToPlexPlayer(client, target)
            pollPlexPlayer(client, target)
        }
    }

    /** Stop steering [target] and pick playback back up here from where it got to. */
    fun stopPlexCasting(resumeLocally: Boolean = true) {
        val target = _plexPlayer.value ?: return
        val client = plexClient()
        val at = _positionMs.value
        val wasPlaying = _isPlaying.value
        plexJob?.cancel()
        plexJob = null
        _plexPlayer.value = null
        plexQueue = null
        plexWindowEnd = -1
        endPlexSubscription(client, target)
        scope.launch { client?.companionCommand(target, "stop", ++plexCommandId) }
        val p = player ?: return
        _volume.value = p.deviceVolume.value
        if (resumeLocally) {
            // Same rebuild-not-seek hand-back as the DLNA route, and for the
            // same reason — see stopCasting. The player's last reported state
            // decides whether sound starts here or waits.
            scope.launch(Dispatchers.Main.immediate) {
                val tracks = _queue.value
                if (tracks.isEmpty()) return@launch
                val index = _index.value.coerceIn(0, tracks.lastIndex)
                android.util.Log.i("AmpPlex", "hand-back idx=$index at=$at playing=$wasPlaying")
                rebuildQueueAt(p, tracks, index, at)
                if (wasPlaying) p.play() else p.pause()
            }
        }
    }

    /**
     * Mirror the player's timeline until it stops being one.
     *
     * Subscribes first — the protocol's own way round, where the player pushes
     * its state as it changes — and polls only for a player that won't have
     * it, or a network that won't route back to us. A poll while paused is
     * asking a question whose answer isn't changing, so it slows down until
     * something is playing again.
     */
    private suspend fun pollPlexPlayer(client: PlexClient, target: PlexPlayer) {
        var misses = 0
        while (coroutineContext.isActive && _plexPlayer.value?.id == target.id) {
            // Nothing to ask while the player is pushing; the loop stays as the
            // heartbeat that notices it going away.
            if (plexSubscribed) {
                delay(PLEX_SUBSCRIBED_CHECK_MS)
                if (System.currentTimeMillis() - plexLastPush > PLEX_PUSH_SILENCE_MS) {
                    android.util.Log.i("AmpPlex", "no pushes for a while — polling again")
                    plexSubscribed = false
                }
                continue
            }
            val t = client.companionTimeline(target, ++plexCommandId)
            if (t == null || t.state == "stopped") {
                // A player that stops answering, or reports stopped, was ended
                // from its own side — someone pressed stop on the TV, or the
                // app there quit. After a few beats that is the truth, not a
                // blip: keep the phone's place, paused.
                if (++misses >= PLEX_GONE_POLLS) {
                    plexCastEnded()
                    return
                }
            } else {
                misses = 0
                adoptPlexTimeline(t)
                topUpPlexWindow(client, target)
            }
            // A paused player's state isn't going anywhere; ask a fifth as often.
            delay(if (_isPlaying.value) CAST_POLL_MS else PLEX_IDLE_POLL_MS)
        }
    }

    /**
     * Take the player's word for what is happening — from a poll or a push,
     * the same either way.
     *
     * Everything here flows one direction: the player is the one playing, so
     * its state is the truth and Amp's is the copy. Nothing in it is echoed
     * back, or a mode read from the player would be set on the player, which
     * is a loop.
     */
    private fun adoptPlexTimeline(t: PlexPlayerTimeline) {
        _isPlaying.value = t.state == "playing"
        _positionMs.value = t.timeMs
        if (t.durationMs > 0) _durationMs.value = t.durationMs
        judgePlexVolume(t.volume, t.controllable)
        // Repeat can be changed on the player's own remote, and a mode the
        // phone doesn't know about is a phone showing the wrong one.
        t.repeat?.let { reported ->
            val mode = when (reported) {
                1 -> RepeatMode.TRACK
                2 -> RepeatMode.QUEUE
                else -> RepeatMode.OFF
            }
            if (mode != _repeatMode.value) {
                android.util.Log.i("AmpPlex", "player's repeat is $mode")
                _repeatMode.value = mode
            }
        }
        t.shuffle?.let { on -> if (on != _shuffle.value) _shuffle.value = on }
        val rk = t.ratingKey
        if (rk != null && _queue.value.getOrNull(_index.value)?.id != rk) {
            // The player moved on its own — its remote skipped, or a track
            // simply ended. Follow it; don't fight it.
            val idx = _queue.value.indexOfFirst { it.id == rk }
            if (idx >= 0) {
                _index.value = idx
                android.util.Log.i("AmpPlex", "player moved to idx=$idx")
            }
        }
    }

    /**
     * Decide whether the fader owns this player's volume.
     *
     * The player's own word comes first: a timeline lists what it accepts, and
     * one that doesn't name `volume` is saying so plainly — an Apple TV feeding
     * a receiver over optical is passing a full-scale digital stream on and the
     * amplifier does the attenuating. Where a player says nothing, evidence
     * decides: a level that moves on its own or follows where we put it means
     * the fader is real; a level that ignores us for a few polls means it
     * isn't.
     */
    private fun judgePlexVolume(reported: Int?, controllable: String?) {
        if (_plexVolume.value != PlexVolume.NotOurs &&
            controllable != null &&
            !controllable.split(',').any { it.trim().equals("volume", ignoreCase = true) }
        ) {
            plexVolumeNotOurs("player's timeline doesn't list volume")
            return
        }
        if (reported == null) return
        when (_plexVolume.value) {
            PlexVolume.Controllable -> _volume.value = (reported / 100f).coerceIn(0f, 1f)
            PlexVolume.NotOurs -> Unit
            PlexVolume.Unknown -> {
                val asked = plexVolumeAsked
                val followed = asked != null && kotlin.math.abs(reported - asked) <= PLEX_VOLUME_SLACK
                if (reported > 0 || followed) {
                    _plexVolume.value = PlexVolume.Controllable
                    plexVolumeAsked = null
                    _volume.value = (reported / 100f).coerceIn(0f, 1f)
                    android.util.Log.i("AmpPlex", "volume is ours (reported=$reported)")
                } else if (asked != null && ++plexVolumeProbes >= PLEX_VOLUME_PROBES) {
                    plexVolumeNotOurs("asked for $asked, player stayed at $reported")
                }
            }
        }
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

    /**
     * Apply a queue edit to the *live* play queue on the server, then tell the
     * player to re-read it.
     *
     * The queue is the player's own copy, so an edit here has to reach it
     * somehow. Pushing a whole new queue does — and restarts the current track
     * to do it, which is the blip an edit used to make. Editing the queue in
     * place changes what comes next and leaves what is playing alone.
     */
    private fun plexQueueEdit(edit: suspend (PlexClient, PlexQueue) -> PlexQueue?) {
        val target = _plexPlayer.value ?: return
        val client = plexClient() ?: return
        val queue = plexQueue ?: return
        scope.launch {
            val edited = edit(client, queue)
            if (edited == null) {
                android.util.Log.i("AmpPlex", "queue edit refused — falling back to a re-push")
                repushPlexQueue()
                return@launch
            }
            // An edit's own answer carries as much of the queue as Plex felt
            // like returning, and a place we don't know can't be edited next
            // time. Re-read the window we pushed so the following edit has
            // what it needs.
            val updated = client.readPlayQueue(edited, PLEX_WINDOW) ?: edited
            plexQueue = updated
            plexPushedIds = _queue.value.map { it.id }
            client.companionRefreshQueue(target, updated, ++plexCommandId)
        }
    }

    /**
     * Hand the player a whole new queue, cued at [positionMs] of wherever the
     * index now points.
     *
     * How a *new* queue reaches the player, and the last resort for an edit —
     * it restarts the playing track — too broad to express in place, or one
     * the server wouldn't take.
     */
    private suspend fun repushPlexQueue(positionMs: Long = _positionMs.value) {
        val target = _plexPlayer.value ?: return
        val client = plexClient() ?: return
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        val index = _index.value.coerceIn(0, tracks.lastIndex)
        // A window around where playback is, not the whole queue — see
        // [plexWindowEnd]. Enough history behind it that Previous still works
        // on the player's own remote.
        val from = (index - PLEX_WINDOW_BEHIND).coerceAtLeast(0)
        val to = (from + PLEX_WINDOW).coerceAtMost(tracks.size)
        val window = tracks.subList(from, to)
        val queue = client.createPlayQueue(
            window.map { it.id },
            tracks[index].id,
            repeatAll = _repeatMode.value == RepeatMode.QUEUE,
        ) ?: return
        plexQueue = queue
        plexPushedIds = window.map { it.id }
        plexWindowEnd = if (to >= tracks.size) -1 else to - 1
        client.companionPlay(target, queue, tracks[index].id, positionMs, ++plexCommandId, PLEX_WINDOW)
        android.util.Log.i(
            "AmpPlex",
            "pushed ${window.size} of ${tracks.size} at idx=$index from ${positionMs}ms" +
                if (plexWindowEnd >= 0) ", window ends at $plexWindowEnd" else "",
        )
    }

    /**
     * Ask the player to push its state here instead of being asked for it.
     *
     * Best effort by design: a player may refuse, and a network may not route
     * back to this phone at all (a guest VLAN, client isolation). Neither is
     * worth failing a cast over — the poll it falls back to is the same
     * endpoint Amp used before subscriptions existed — but it is worth saying
     * which one is running.
     */
    private suspend fun subscribeToPlexPlayer(client: PlexClient, target: PlexPlayer) {
        val port = plexListener.start(scope)
        if (port == null) {
            android.util.Log.i("AmpPlex", "no port to be pushed to — polling")
            return
        }
        plexLastPush = System.currentTimeMillis()
        plexSubscribed = client.companionSubscribe(target, port, ++plexCommandId)
        android.util.Log.i(
            "AmpPlex",
            if (plexSubscribed) "subscribed; ${target.name} pushes to ${plexListener.localAddress()}:$port"
            else "subscribe declined — polling",
        )
    }

    /**
     * Hand the player the next stretch of a long queue as it nears the end of
     * what it has.
     *
     * An append is an edit of the queue it already holds, so nothing restarts
     * — the difference between this and pushing a fresh window, which would
     * blip every hundred tracks.
     */
    private suspend fun topUpPlexWindow(client: PlexClient, target: PlexPlayer) {
        val end = plexWindowEnd
        if (end < 0) return
        val tracks = _queue.value
        if (end >= tracks.lastIndex) {
            plexWindowEnd = -1
            return
        }
        if (_index.value < end - PLEX_WINDOW_REFILL) return
        val queue = plexQueue ?: return
        val to = (end + 1 + PLEX_WINDOW_CHUNK).coerceAtMost(tracks.size)
        val more = tracks.subList(end + 1, to)
        val updated = client.addToPlayQueue(queue, more.map { it.id }, next = false) ?: return
        plexQueue = updated
        plexPushedIds = plexPushedIds + more.map { it.id }
        plexWindowEnd = if (to >= tracks.size) -1 else to - 1
        client.companionRefreshQueue(target, updated, ++plexCommandId)
        android.util.Log.i("AmpPlex", "topped up ${more.size} more; window now ends at $plexWindowEnd")
    }

    private fun plexVolumeNotOurs(why: String) {
        _plexVolume.value = PlexVolume.NotOurs
        plexVolumeAsked = null
        // Full, not the phone's last level: what leaves here is a digital
        // stream at unity gain, and the far end does the attenuating. A bar
        // showing anything less would be describing a knob nobody is turning.
        _volume.value = 1f
        android.util.Log.i("AmpPlex", "volume isn't ours — $why; fader disabled at unity")
    }

    /** Stop the pushes and close the door behind them. */
    private fun endPlexSubscription(client: PlexClient?, target: PlexPlayer?) {
        val wasSubscribed = plexSubscribed
        plexSubscribed = false
        plexListener.stop()
        if (wasSubscribed && client != null && target != null) {
            scope.launch { client.companionUnsubscribe(target, ++plexCommandId) }
        }
    }

    private fun plexCastEnded() {
        if (_plexPlayer.value == null) return
        android.util.Log.i("AmpPlex", "player gone — keeping the place, paused")
        endPlexSubscription(plexClient(), _plexPlayer.value)
        _plexPlayer.value = null
        plexQueue = null
        _isPlaying.value = false
        val p = player ?: return
        _volume.value = p.deviceVolume.value
        scope.launch(Dispatchers.Main.immediate) {
            val tracks = _queue.value
            if (tracks.isEmpty()) return@launch
            rebuildQueueAt(p, tracks, _index.value.coerceIn(0, tracks.lastIndex), _positionMs.value)
            p.pause()
        }
    }

    // --- DLNA casting --------------------------------------------------------

    /** Find renderers on the network. Blocking on the network for a few seconds. */
    suspend fun findCastDevices(): List<DlnaRenderer> =
        // A LAN broadcast, but the LAN could be a hotspot — metered, and so
        // cellular as far as Wi-Fi Only is concerned. Same wall as every socket.
        if (NetworkGate.isOpen()) DlnaCast.discover() else emptyList()

    /**
     * Move playback to [renderer]: stop this device, hand the current track's
     * stream URL to the renderer at the position we'd reached, and start polling
     * it for progress.
     */
    fun castTo(renderer: DlnaRenderer) {
        // One route at a time — a Companion cast in progress hands over quietly.
        if (_plexPlayer.value != null) stopPlexCasting(resumeLocally = false)
        player?.pause()
        _castRenderer.value = renderer
        val from = _positionMs.value
        castJob?.cancel()
        castJob = scope.launch {
            // Adopt the renderer's current level so the fader isn't lying — and
            // move the device's own to match, so the first press of the rocker
            // nudges from where the speaker is rather than jumping.
            DlnaCast.volume(renderer)?.let {
                _volume.value = it
                withContext(Dispatchers.Main.immediate) { player?.setSystemVolume(it) }
            }
            if (startCastTrack(renderer, from)) pollRenderer(renderer)
        }
    }

    /**
     * Stop casting and (by default) pick playback back up on this device from
     * wherever the renderer had reached.
     */
    fun stopCasting(resumeLocally: Boolean = true) {
        val renderer = _castRenderer.value ?: return
        val at = _positionMs.value
        castJob?.cancel()
        castJob = null
        _castRenderer.value = null
        castOffsetMs = 0L
        castSeekPending = -1L
        scope.launch { DlnaCast.stop(renderer) }
        val p = player ?: return
        _volume.value = p.deviceVolume.value
        if (resumeLocally) {
            // The queue moved on while the renderer had it — advanceCast walks
            // _index as tracks finish there — but this player sat paused on
            // whatever it held when casting began. Seeking it in place picked
            // the cast's position inside the *old* track: coming back landed
            // where you were before casting, not where the speaker left off.
            // Index and position go in one seek; see LightAudioPlayer.
            // Rebuilt at that index and position in one call rather than
            // seeked: a paused player has no duration yet for an item it never
            // opened, so any plain seek into it clamps to 0 — which is why the
            // right song came back from the top. Same reason restoreState and
            // the stream fallback rebuild instead of seeking — and a stream the
            // player can't seek at all goes through the server; see
            // rebuildQueueAt, which is where the second restart-from-the-top
            // came from.
            scope.launch(Dispatchers.Main.immediate) {
                val tracks = _queue.value
                if (tracks.isEmpty()) return@launch
                val index = _index.value.coerceIn(0, tracks.lastIndex)
                android.util.Log.i("AmpCast", "hand-back idx=$index at=$at")
                rebuildQueueAt(p, tracks, index, at)
                p.play()
            }
        }
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
        val track = _queue.value.getOrNull(_index.value) ?: return false
        val url = castCurrentUrl
        val wanted = if (ms > 1_000L) ms else 0L
        if (url != null && castOffsetMs == 0L) {
            val (format, _) = castFormatFor(renderer, track)
            val seekKey = "${track.id}|${format.id}"
            val seekable = castSeekable[seekKey] ?: probeSeekable(track, format, seekKey)
            if (seekable == true) {
                if (DlnaCast.seek(renderer, wanted)) {
                    android.util.Log.i("AmpCast", "seek in place → $wanted")
                    _positionMs.value = wanted
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
        val nearEnd = _durationMs.value > 0 &&
            _durationMs.value - wanted <= CAST_SEEK_VERIFY_MS + CAST_SEEK_TOLERANCE_MS
        if (nearEnd) return
        val generation = ++seekGeneration
        val seekedIndex = _index.value
        scope.launch {
            delay(CAST_SEEK_VERIFY_MS)
            if (generation != seekGeneration) return@launch
            if (_castRenderer.value?.id != renderer.id) return@launch
            // Moved on — the renderer finished it, or a skip did — so the
            // seek has nothing left to be measured against.
            if (_index.value != seekedIndex || castCurrentUrl != url) return@launch
            val info = DlnaCast.position(renderer) ?: return@launch
            if (info.trackUri != null && info.trackUri != url) return@launch
            if (DlnaCast.state(renderer) == DlnaState.STOPPED) return@launch
            if (info.positionMs >= wanted - CAST_SEEK_TOLERANCE_MS) return@launch
            android.util.Log.i("AmpCast", "seek missed: at ${info.positionMs} wanted $wanted → server")
            castJob?.cancel()
            castJob = scope.launch {
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
        val track = _queue.value.getOrNull(_index.value) ?: return false
        // A new track on the renderer is a new session, same as the local
        // player's — see the index collector in bind(). Also covers a seek,
        // which restarts the renderer's stream from scratch the same way a
        // track change does, so the session it's replacing needs closing too.
        openSessionFor(track.id)
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
        val url = serverClient.value?.streamUrl(
            track,
            format,
            estimateContentLength = false,
            timeOffsetSeconds = (offset / 1000).toInt(),
            sessionId = sessionIdFor(track.id),
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
        android.util.Log.i("AmpCast", "pushed idx=${_index.value} wanted=$wanted offset=$offset ${format.id}")
        _isPlaying.value = true
        _durationMs.value = track.durationMs
        castOffsetMs = offset
        _positionMs.value = wanted
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
        announceNowPlaying()
        castCurrentUrl = url
        castSeekPending = -1L
        // SetAVTransportURI leaves the next slot empty, and it stays that way
        // until this track is nearly over — see armNextIfDue.
        castNextUrl = null
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
        val probeUrl = serverClient.value?.streamUrl(
            track,
            format,
            estimateContentLength = false,
        ) ?: return null
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
        val total = _durationMs.value
        // A track of unknown length (zero) still gets its hand-off: arming early
        // is this bug, but never arming at all is a gap on every single track.
        if (total > 0L && total - _positionMs.value > CAST_PREQUEUE_LEAD_MS) return
        queueNextOnRenderer(renderer)
    }

    /**
     * Hand the renderer the following track so it can cross into it seamlessly.
     *
     * Records the URL under [castNextUrl] so the poller can recognise the
     * hand-off when it happens: the renderer switches on its own, and the only
     * evidence is that GetPositionInfo starts reporting the other URI.
     *
     * Call through [armNextIfDue] rather than directly — handing a renderer the
     * next stream too early is what this whole path had to be rebuilt around.
     */
    private suspend fun queueNextOnRenderer(renderer: DlnaRenderer) {
        val after = nextCastIndex()
        val track = after?.let { _queue.value.getOrNull(it) }
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
        // shares an identifier with the other.
        val url = serverClient.value
            ?.streamUrl(track, format, estimateContentLength = false, sessionId = sessionIdFor(track.id))
            ?: return
        if (url == castNextUrl) return
        castNextArmedFor = after
        castNextUrl = null
        val queued = DlnaCast.setNext(
            renderer = renderer,
            url = url,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            mimeType = mime,
        )
        // Renderers that don't implement it fault; the stop-and-restart path then
        // carries the queue as it did before.
        if (queued) castNextUrl = url
    }

    /** The index the queue would move to next, or null at the end of it. */
    private fun nextCastIndex(): Int? {
        val size = _queue.value.size
        if (size == 0) return null
        return when (_repeatMode.value) {
            RepeatMode.TRACK -> _index.value
            RepeatMode.QUEUE -> (_index.value + 1) % size
            RepeatMode.OFF -> (_index.value + 1).takeIf { it < size }
        }
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
        val desired = effectiveFormat()
        val key = "${renderer.id}|${desired.id}"
        castFormats[key]?.let { return it }

        val client = serverClient.value
        val sink = runCatching { DlnaCast.sinkFormats(renderer) }.getOrDefault(emptySet())
        val mime = client?.let { DlnaCast.probeMime(it.streamUrl(track, desired)) }
            ?: assumedMime(desired)
        val choice = if (sinkAccepts(sink, mime)) {
            desired to mime
        } else {
            StreamFormat.MP3 to "audio/mpeg"
        }
        castFormats[key] = choice
        return choice
    }

    private fun assumedMime(format: StreamFormat): String = when (format) {
        StreamFormat.MP3 -> "audio/mpeg"
        StreamFormat.OPUS -> "audio/ogg"
        StreamFormat.FLAC -> "audio/flac"
        // Unknown container up front; the DLNA wildcard lets the device sniff.
        StreamFormat.RAW -> "*"
    }

    /**
     * Whether the renderer's advertised sink covers [mime]. Compared through
     * alias groups because devices and servers spell the same container
     * differently (`audio/mp4` vs `audio/m4a`, `audio/mpeg` vs `audio/mp3`).
     */
    private fun sinkAccepts(sink: Set<String>, mime: String): Boolean {
        if (sink.isEmpty() || mime == "*") return true
        val aliases = when (mime) {
            "audio/mpeg", "audio/mp3" -> setOf("audio/mpeg", "audio/mp3")
            "audio/flac", "audio/x-flac" -> setOf("audio/flac", "audio/x-flac")
            "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" ->
                setOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac")
            "audio/ogg", "audio/opus", "application/ogg" ->
                setOf("audio/ogg", "audio/opus", "application/ogg")
            "audio/wav", "audio/x-wav" -> setOf("audio/wav", "audio/x-wav")
            else -> setOf(mime)
        }
        return sink.any { it.substringBefore(';').trim() in aliases }
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
        while (coroutineContext.isActive && _castRenderer.value?.id == renderer.id) {
            delay(CAST_POLL_MS)
            val position = DlnaCast.position(renderer)
            val state = DlnaCast.state(renderer)
            if (position != null) {
                // The renderer crossed into the track we queued: follow it here
                // rather than driving it, so nothing interrupts the audio.
                // Repeat-one queues the track that's already playing, so a URI
                // match on its own proves nothing — it has to be a *different*
                // URI from the one we started.
                val moved = castNextUrl != null &&
                    castNextUrl != castCurrentUrl &&
                    position.trackUri == castNextUrl
                if (moved) {
                    nextCastIndex()?.let { _index.value = it }
                    castCurrentUrl = castNextUrl
                    // The queued track was handed over whole, from its start.
                    castOffsetMs = 0L
                    castStartedAtMs = 0L
                    castSeekPending = -1L
                    _durationMs.value = _queue.value.getOrNull(_index.value)?.durationMs
                        ?: _durationMs.value
                    announceNowPlaying()
                    // The slot the renderer just consumed is empty again, and is
                    // left that way until this track is nearly over.
                    castNextUrl = null
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
                    _positionMs.value = 0L
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
                val ours = position.trackUri == null || position.trackUri == castCurrentUrl
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
                            _positionMs.value = reading
                        }
                    } else if (settled || kotlin.math.abs(reading - castStartedAtMs) <= CAST_START_WINDOW_MS) {
                        _positionMs.value = reading
                    }
                    // The renderer only knows about the piece of the track it was
                    // given, so a stream that started part-way in reports a short
                    // duration. The track's own is the honest number.
                    if (castOffsetMs == 0L && position.durationMs > 0) {
                        _durationMs.value = position.durationMs
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
                    _isPlaying.value = true
                }
                DlnaState.PAUSED -> _isPlaying.value = false
                else -> Unit
            }
            when {
                // Played, then stopped: the track ended. Move on. Only readings
                // the renderer actually gave us count here — going quiet must
                // never cost a track that was playing perfectly well.
                (settled || played) && stall.stopped >= STOPPED_POLLS_TO_ADVANCE -> {
                    android.util.Log.i("AmpCast", "ended idx=${_index.value} → advance")
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
                    startedIndex != _index.value -> {
                    android.util.Log.i("AmpCast", "never started idx=${_index.value} → restart")
                    startedIndex = _index.value
                    stall.reset()
                    restartAfter = STALLED_POLLS_TO_RESTART
                    if (!startCastTrack(renderer)) return
                }
                // Asked again and it still won't play: something is wrong with
                // this track rather than with the hand-off, and sitting in
                // silence is worse than going on.
                !settled && !played &&
                    stall.notPlaying >= restartAfter &&
                    startedIndex == _index.value -> {
                    android.util.Log.i("AmpCast", "still not playing idx=${_index.value} → pass over")
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
        val tracks = _queue.value
        if (tracks.isEmpty()) return
        val current = _index.value.coerceIn(0, tracks.lastIndex)
        // Nothing to say unless something actually moves — this runs on every
        // download that completes, and most of them change no answers at all.
        val rebuilt = tracks.map { it.source() }
        if (rebuilt == queuedSources) return
        queuedSources = rebuilt
        // ExoPlayer's looper is Main and it enforces that — see restoreState.
        scope.launch(Dispatchers.Main.immediate) {
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
        }
    }

    /** What the queue was last built from, so a no-op rebuild stays a no-op. */
    private var queuedSources: List<LightAudioSource> = emptyList()

    /** Move to the next queue entry on the renderer; false when the queue ends. */
    private suspend fun advanceCast(renderer: DlnaRenderer): Boolean {
        val next = when (_repeatMode.value) {
            RepeatMode.TRACK -> _index.value
            RepeatMode.QUEUE -> (_index.value + 1) % _queue.value.size.coerceAtLeast(1)
            RepeatMode.OFF -> _index.value + 1
        }
        if (next !in _queue.value.indices) {
            _isPlaying.value = false
            return false
        }
        _index.value = next
        android.util.Log.i("AmpCast", "advance → idx=$next")
        return startCastTrack(renderer)
    }

    // --- internals -----------------------------------------------------------

    private fun setQueue(tracks: List<Track>, startIndex: Int) {
        val p = player ?: return
        _queue.value = tracks
        lastScrobbledId = null
        // Keep the local player loaded even while casting, so switching back to
        // this device resumes instantly instead of re-preparing the queue.
        queuedSources = tracks.map { it.source() }
        p.setMediaQueue(tracks.map { it.toAudioItem() }, startIndex)
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
        // A new queue is prepared afresh, so a park on the old one is over.
        _waitingForWifi.value = false
        // Right from the first frame, rather than whenever the stream gets round
        // to declaring a length — see the durationMs collector in ensurePlayer.
        tracks.getOrNull(startIndex)?.durationMs
            ?.takeIf { it > 0L }
            ?.let { _durationMs.value = it }
        // A new queue goes wherever the sound is already going. Without this it
        // started here while the Companion player carried on with the queue it
        // still owned — two songs at once, and the Output page still naming the
        // TV. From the top, not the old queue's position.
        if (_plexPlayer.value != null) {
            p.pause()
            _positionMs.value = 0L
            scope.launch { repushPlexQueue(positionMs = 0L) }
            return
        }
        val renderer = _castRenderer.value
        if (renderer != null) {
            p.pause()
            castJob?.cancel()
            castJob = scope.launch {
                if (startCastTrack(renderer)) pollRenderer(renderer)
            }
            return
        }
        p.play()
    }

    /** Sort [tracks] back into the relative order they had in [original]. */
    private fun restoreOrder(tracks: List<Track>, original: List<Track>): List<Track> {
        val rank = original.withIndex().associate { (i, t) -> t.id to i }
        // Tracks queued after shuffling have no pre-shuffle rank; keep them last.
        return tracks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
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
        if (!serverAnswered) App.reportServerReachable(false)
        // Nothing local to fall back to: let the error stand rather than
        // restarting a stream that just failed.
        if (!localFiles.containsKey(track.id)) return
        // One attempt per track, so a file that is itself unplayable (a codec the
        // device can't decode) can't spin here.
        if (retriedTrackId == track.id) return
        retriedTrackId = track.id
        streamFailed += track.id
        // Only a server that never answered is grounds for pinning *everything*
        // to local files. That latch is released when the server answers again —
        // which never happens if we never called it unreachable.
        if (!serverAnswered) forceOffline = true
        val resumeAt = _positionMs.value
        scope.launch(Dispatchers.Main.immediate) {
            p.setMediaQueueAt(_queue.value.map { it.toAudioItem() }, _index.value, resumeAt)
            p.play()
        }
    }

    /**
     * Tell the server this session is playing, paused or stopped — see
     * [MusicServer.reportTimeline]. Fire-and-forget: a dropped heartbeat just
     * means the dashboard is stale until the next one, not a broken stream.
     */
    private fun reportTimeline(state: TimelineState) {
        // A Companion player reports its own timeline to the server — that is
        // its scrobble, and this phone adding another would double every play.
        if (_plexPlayer.value != null) return
        val track = _queue.value.getOrNull(_index.value) ?: return
        // Nothing playing on the server's behalf to report on.
        if (LocalLibrary.isLocal(track.id)) return
        // Wi-Fi Only on a metered link means don't. A heartbeat is worth no
        // cellular data and nothing is lost by skipping it: it says where
        // playback is *now*, so a stale one replayed later would be worse than
        // none. The play itself is kept — see maybeSubmitPlay.
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        val sid = sessionIdFor(track.id)
        val position = _positionMs.value
        val duration = _durationMs.value
        scope.launch { runCatching { client.reportTimeline(sid, track.id, state, position, duration) } }
    }

    /**
     * Close out the session for a track that has stopped being the one playing.
     *
     * Without it the old session sits in `/status/sessions` until its lease
     * times out on its own, and a track change every few minutes makes them pile
     * up faster than they expire. Takes the track explicitly, because by the
     * time this runs the queue has usually moved on to the next one.
     */
    private fun reportTimelineStopped(oldTrackId: String) {
        if (LocalLibrary.isLocal(oldTrackId)) return
        // Gated here as well as in reportTimeline: these two close a session by
        // calling the client straight out, so the gate on the wrapper never saw
        // them and "state=stopped&time=0" kept arriving over cellular in a mode
        // that forbids it. Every route to the server needs its own answer.
        if (!metadataAllowed()) return
        val client = serverClient.value ?: return
        val sid = sessionIdFor(oldTrackId)
        scope.launch {
            runCatching {
                client.reportTimeline(sid, oldTrackId, TimelineState.STOPPED, 0L, 0L)
            }
        }
    }

    /**
     * Hand the open session over to [newTrackId], stopping the outgoing one.
     *
     * The id itself is derived from the track rather than minted here — see
     * [sessionIdFor] — so this only tracks *which* session is currently open.
     */
    private fun openSessionFor(newTrackId: String) {
        val outgoing = sessionTrackId
        if (outgoing == newTrackId) return
        outgoing?.let { reportTimelineStopped(it) }
        sessionTrackId = newTrackId
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
        if (_plexPlayer.value != null) return
        val track = _queue.value.getOrNull(_index.value) ?: return
        // Nothing to tell, and nowhere to keep a play count: a local file's
        // history would be invented by this app and restorable by nothing.
        if (LocalLibrary.isLocal(track.id)) return
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
        if (LocalLibrary.isLocal(track.id)) return
        if (track.id == lastScrobbledId) return
        if (positionMs < submitAfterMs(_durationMs.value)) return
        lastScrobbledId = track.id
        // Stamped now, sent whenever: a play that happened offline still belongs
        // at the time it happened once the server hears about it.
        val at = System.currentTimeMillis()
        val client = serverClient.value
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
            val sent = allowed && client != null &&
                runCatching { client.scrobble(track.id, at) }.isSuccess
            // Guarded: a play can land before App has finished wiring itself up,
            // and a missed scrobble is not worth a crash.
            if (!sent) {
                runCatching {
                    App.pending.add(PendingAction(PendingAction.Kind.SCROBBLE, track.id, atMs = at))
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
        LocalLibrary.fileOf(id)?.let { return LightAudioSource.FileSource(it) }
        val local = localFiles[id]
        if (local != null) {
            val (file, downloadedFormat) = local
            val streamed = effectiveFormat()
            val preferStream = !forceOffline && id !in streamFailed && unmetered &&
                streamed.qualityRank > downloadedFormat.qualityRank
            if (!preferStream) return LightAudioSource.FileSource(file)
        }
        return LightAudioSource.UrlSource(
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
            serverClient.value?.streamUrl(
                this,
                effectiveFormat(),
                estimateContentLength = false,
                sessionId = sessionIdFor(id),
            ).orEmpty(),
        )
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
        if (at > 1_000L && client != null && needsServerSeek(track, askPlayer = false)) {
            android.util.Log.i("AmpCast", "rebuild idx=$index at=$at via server offset")
            val url = client.streamUrl(
                track,
                effectiveFormat(),
                timeOffsetSeconds = (at / 1000).toInt(),
                // See serverSeek: an estimated length invites range probes the
                // transcode can't answer (416), which errored the restore.
                estimateContentLength = false,
                sessionId = sessionIdFor(track.id),
            )
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
        private const val PREVIOUS_RESTART_MS = 3_000L
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

        /** Long enough for a renderer that can seek to have done so. */
        private const val CAST_SEEK_VERIFY_MS = 4_000L

        /** A cast seek this close to the end is the next track, not a seek. */
        private const val CAST_SEEK_END_SLACK_MS = 3_000L
        private const val CAST_SEEK_TOLERANCE_MS = 15_000L
        /** How far a just-started track's first readings may stray from its start before being disbelieved. */
        const val CAST_START_WINDOW_MS = 15_000L

        private const val CAST_POLL_MS = 1_000L

        /** Consecutive silent/stopped polls before a Companion player counts as gone. */
        private const val PLEX_GONE_POLLS = 5

        /**
         * How much of a long queue a player is given at once, and how much of
         * it sits behind the playing track. Small enough that the queue's uri
         * stays a URL rather than a payload — Plex reads it from the request
         * line — and long enough that a top-up is rare.
         */
        private const val PLEX_WINDOW = 150
        private const val PLEX_WINDOW_BEHIND = 20

        /** Long enough for a player to act on a skip before it is judged. */
        private const val PLEX_SKIP_VERIFY_MS = 2_500L

        /** How often a subscribed cast checks that pushes are still coming. */
        private const val PLEX_SUBSCRIBED_CHECK_MS = 3_000L

        /** Silence from a subscribed player before falling back to polling. */
        private const val PLEX_PUSH_SILENCE_MS = 12_000L

        /** Poll interval while the player is paused — its state isn't moving. */
        private const val PLEX_IDLE_POLL_MS = 5_000L

        /** Tracks left ahead of the player before the window is topped up. */
        private const val PLEX_WINDOW_REFILL = 30

        /** How many more go over in a top-up. */
        private const val PLEX_WINDOW_CHUNK = 80

        /** How near the asked-for level counts as the player having taken it. */
        private const val PLEX_VOLUME_SLACK = 5

        /** Polls a player gets to follow a volume before the fader gives up on it. */
        private const val PLEX_VOLUME_PROBES = 3

        /**
         * The most tracks the player is ever given at once, and how many already
         * played are kept behind the starting point.
         */
        private const val MAX_QUEUE = 500
        private const val QUEUE_LOOKBACK = 50

        /** One press of the rocker, as a fraction of full scale. */
        private const val VOLUME_KEY_STEP = 0.05f

        /** Consecutive STOPPED readings that mean the track really ended. */
        private const val STOPPED_POLLS_TO_ADVANCE = 2

        /**
         * How long a track that has never played is given before we start it
         * ourselves — see the STOPPED branch in pollRenderer.
         *
         * Longer than the advance threshold on purpose: a renderer opening a
         * fresh stream is legitimately stopped for a few seconds (about three on
         * the Denon), and this must not fire while it is merely loading.
         */
        private const val STALLED_POLLS_TO_RESTART = 6

        /**
         * The same, for a track the renderer took from the queue and stopped on.
         *
         * Nothing is being loaded there — it had the whole of the previous track
         * to prepare — so a stop at that moment is a hand-off that has already
         * failed, not one still getting ready.
         */
        private const val HANDOFF_POLLS_TO_RESTART = 2

        /**
         * How close to the end of the current track the next one is handed over.
         *
         * Long enough for the renderer to open the stream and buffer it — the
         * Denon takes about three seconds from a cold start — and short enough
         * that the connection has no time to go stale while it waits. See
         * [armNextIfDue] for why a stale one is the whole problem.
         */
        private const val CAST_PREQUEUE_LEAD_MS = 15_000L

        /** Settling time before re-arming the renderer's next track after an edit. */
        private const val CAST_REQUEUE_DEBOUNCE_MS = 400L
        private const val CAST_STOP_TIMEOUT_MS = 1_500L

        /** How often the queue snapshot is written while playing. */
        /** How long a restore waits to learn what is downloaded before deciding. */
        private const val DOWNLOADS_READY_TIMEOUT_MS = 4_000L

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

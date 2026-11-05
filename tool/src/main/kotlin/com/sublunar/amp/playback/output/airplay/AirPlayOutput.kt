package com.sublunar.amp.playback.output.airplay

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.sublunar.amp.BuildConfig
import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.Track
import com.sublunar.amp.playback.output.Disconnect
import com.sublunar.amp.playback.output.FarEndPress
import com.sublunar.amp.playback.output.GroupMember
import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget
import com.sublunar.amp.playback.output.PhoneSource
import com.sublunar.amp.playback.output.Refound
import com.sublunar.amp.playback.output.VolumeControl
import com.sublunar.amp.playback.output.nextRow
import com.sublunar.amp.playback.output.previousRestarts
import com.sublunar.amp.playback.output.previousRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Playing to AirPlay speakers, as AirPlay 1: the phone decodes the queue and
 * sends the sound itself, Apple Lossless at CD quality. Unlike DLNA, where
 * the speaker fetches the server's stream, the music goes through the phone,
 * so it can be anything the phone plays — its own files, a download, a
 * server the speaker can't reach — and goes on from one track to the next
 * without a gap. The cost is that the phone has to keep at it the whole
 * time; the stand-in (see StandIn) keeps it awake for that.
 *
 * Everything that talks to the speaker runs on one worker thread, in the
 * order it was asked for. A press that replaces or stops what plays counts
 * in [presses]: a start queued before it is dropped, and a stream started
 * before it no longer reports, so the last press decides. The sound itself
 * runs on a [Streamer]'s threads.
 */
internal class AirPlayOutput(private val host: OutputHost) : Output {

    override val volumeControl: StateFlow<VolumeControl> = MutableStateFlow(VolumeControl.Ours)

    // A pause flushes the speaker, so a resume is a start: it opens the
    // track again and waits out the speaker's latency.
    override val resumesAfresh: Boolean get() = true

    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "AmpAirPlay-steer").apply { isDaemon = true }
    }

    // Worker-thread state, unless marked volatile.

    /** The speaker the cast was made to: the one the controller steers, whatever plays with it. */
    private var receiver: AirPlayReceiver? = null

    /** The speakers the sound goes to: the chosen one alone, or a group (which may have let it go). */
    private val members = LinkedHashMap<String, AirPlayReceiver>()

    /** The open session of each member that has one. */
    private val sessions = HashMap<String, RaopSession>()

    /** [members] for other threads to read. */
    @Volatile
    private var memberList: List<AirPlayReceiver> = emptyList()

    private val _group = MutableStateFlow<List<GroupMember>>(emptyList())
    override val group: StateFlow<List<GroupMember>> = _group

    /** The level the phone's own volume was last moved to from here, and when: its echo is no news. */
    @Volatile
    private var phoneSetTo: Pair<Float, Long>? = null

    @Volatile
    private var run: Run? = null

    /** Whether anything has played to the speaker since it was chosen: a failure before that gives it up. */
    @Volatile
    private var playedSinceConnect = false

    /** Speakers that turned a stream away until they were sent auth-setup — see [RaopSession]. */
    private val wantsAuthSetup = HashSet<String>()

    /** The level each speaker was last set to, for when it's chosen again. */
    private val levels = ConcurrentHashMap<String, Float>()

    /** Where the sound had got to when the link went: for [findAgain]. */
    @Volatile
    private var lostAt: Pair<Int, Long>? = null

    /**
     * The track a pause (or a failure) left the sound on. A play that finds
     * another at the queue's place — the paused one was taken out — starts
     * that one from its top, not from where the other had got to.
     */
    @Volatile
    private var pausedOn: String? = null

    private val presses = AtomicInteger()

    // Each speaker's latest level, sent once however many steps a drag made.
    private val wantedDb = ConcurrentHashMap<String, Double>()
    private val volumeQueued = AtomicBoolean(false)

    private val streams = StreamCache { host.workDir(AirPlayFamily.id) }

    /** The speaker's own knob and buttons, reaching back — see [DacpRemote]. */
    private val remote = DacpRemote(
        log = ::log,
        accepts = { said -> remoteFor.containsKey(said) },
        volumeNow = { host.volume.value },
        onCommand = ::onSpeakerCommand,
    )

    /** Each open session's Active-Remote, and whose it is: what a speaker's call says it's about. */
    private val remoteFor = ConcurrentHashMap<String, String>()


    private class Run(val streamer: Streamer, val feed: Feed, val press: Int) {
        val startedAt = System.nanoTime()

        @Volatile
        var shownSegment: Segment? = null

        @Volatile
        var soundingShown = false

        /** The speakers told about [shownSegment]: one that joins after is told on the next report. */
        val described: MutableSet<RaopSession> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    }

    init {
        worker.scheduleWithFixedDelay({ tick() }, TICK_S, TICK_S, TimeUnit.SECONDS)
    }

    private fun log(message: String) {
        Log.i(AirPlayFamily.logTag, message)
    }

    private fun steered(): AirPlayTarget? = host.steering(this) as? AirPlayTarget

    private fun onWorker(task: () -> Unit) {
        worker.execute {
            try {
                task()
            } catch (e: Exception) {
                log("worker: ${e::class.simpleName}")
            }
        }
    }

    /** A press that replaces or stops what plays: what was started before it is now old. */
    private fun press(): Int = presses.incrementAndGet()

    // --- Finding speakers ----------------------------------------------------

    override suspend fun find(): List<OutputTarget> = AirPlayFamily.discover().map(::AirPlayTarget)

    // --- Taking the sound and letting it go ----------------------------------

    override fun connect(target: OutputTarget, fromMs: Long, play: Boolean) {
        val chosen = (target as AirPlayTarget).receiver
        val p = press()
        showLevel(chosen)
        // A start is shown at once, so a press made while the speaker is
        // being reached (a skip) carries it on rather than finding it paused.
        if (play) host.showPlaying(true)
        onWorker {
            endSession(say = true)
            receiver = chosen
            setMembers(listOf(chosen))
            playedSinceConnect = false
            lostAt = null
            pausedOn = if (play) null else host.queue.value.getOrNull(host.index.value)?.id
            log("chose ${chosen.name} (${chosen.txt["am"].orEmpty()})")
            if (play) startAt(host.index.value, fromMs, p)
        }
    }

    /** The fader at the level [r] was last set to, or the cautious start; the phone's own follows, for the rocker. */
    private fun showLevel(r: AirPlayReceiver) {
        val level = levels[r.id] ?: STARTING_LEVEL
        host.showVolume(level)
        phoneSetTo = level to System.nanoTime()
        host.scope.launch(Dispatchers.Main.immediate) { host.setPhoneVolume(level) }
    }

    override fun disconnect(target: OutputTarget, why: Disconnect) {
        val going = (target as AirPlayTarget).receiver
        press()
        when (why) {
            // Nothing can be said to it now: stop sending, keep where it was.
            // The fetches go too, or the door would carry them on over cellular.
            Disconnect.LinkLost -> onWorker {
                val heard = stopRun()
                lostAt = heard?.let { rowOf(it.segment) to it.positionMs } ?: (host.index.value to host.positionMs.value)
                sessions.values.forEach { it.close() }
                sessions.clear()
                remoteFor.clear()
                receiver?.let { setMembers(listOf(it)) }
                remote.stop()
                streams.clear()
            }
            Disconnect.Leaving -> {
                val task = worker.submit {
                    endSession(say = true)
                    remote.stop()
                    streams.clear()
                }
                runCatching { task.get(STOP_WAIT_MS, TimeUnit.MILLISECONDS) }
            }
            else -> onWorker {
                if (receiver?.id == going.id) endSession(say = true)
                remote.stop()
                streams.clear()
            }
        }
    }

    override fun stopPlayback() {
        press()
        onWorker {
            stopRun()
            flushIfNeeded()
            streams.clear()
            pausedOn = null
        }
    }

    override suspend fun findAgain(target: OutputTarget): Refound? {
        val gone = (target as AirPlayTarget).receiver
        val (row, ms) = lostAt ?: return null
        repeat(FIND_AGAIN_ROUNDS) {
            val found = AirPlayFamily.discover().firstOrNull { it.id == gone.id }
            if (found != null) return Refound(AirPlayTarget(found), row, ms)
        }
        return null
    }

    override fun rejoin(found: Refound) {
        val back = (found.target as AirPlayTarget).receiver
        // The fader was the phone's while the link was gone: back to the
        // speaker's level, so the rocker doesn't jump it.
        showLevel(back)
        onWorker {
            receiver = back
            setMembers(listOf(back))
            lostAt = null
            pausedOn = host.queue.value.getOrNull(host.index.value)?.id
            log("found ${back.name} again → waits for play")
        }
    }

    // --- Controls ------------------------------------------------------------

    override fun play() {
        if (steered() == null) return
        val p = presses.get()
        val row = host.index.value
        var ms = host.positionMs.value
        val paused = pausedOn
        if (paused != null && host.queue.value.getOrNull(row)?.id != paused) {
            ms = 0L
            show(row, 0L)
        }
        onWorker { if (run == null) startAt(row, ms, p) }
    }

    override fun pause() {
        if (steered() == null) return
        val p = press()
        onWorker { pauseHere(p) }
    }

    override fun seekTo(ms: Long) {
        if (steered() == null) return
        val total = host.durationMs.value
        // Nothing left to hear: on to what follows, as the end itself would.
        // With nothing after it, the seek plays the last moment and the queue ends.
        if (total > 0L && total - ms <= SEEK_END_SLACK_MS) {
            val following = followingRow(host.index.value)
            if (following != null) {
                jumpOrMove(following)
                return
            }
        }
        val p = press()
        host.showPosition(ms)
        val row = host.index.value
        val playing = host.isPlaying.value
        onWorker {
            if (run != null || playing) {
                startAt(row, ms, p)
            } else {
                // Sought while paused: the play that follows starts there, on this track.
                pausedOn = host.queue.value.getOrNull(row)?.id
            }
        }
    }

    override fun next() {
        val index = host.nextRow()
        if (index in host.queue.value.indices) jumpOrMove(index)
    }

    override fun previous() {
        val index = host.previousRow()
        if (previousRestarts(host.positionMs.value, index, host.queue.value.size)) seekTo(0) else jumpOrMove(index)
    }

    /** A row tapped plays, wherever the sound is. */
    override fun jumpTo(index: Int) {
        if (steered() == null) return
        val p = press()
        show(index, 0L)
        onWorker { startAt(index, 0L, p) }
    }

    /** Next and previous keep to the play state they found, as on the phone. */
    private fun jumpOrMove(index: Int) {
        if (steered() == null) return
        val p = press()
        val playing = host.isPlaying.value
        show(index, 0L)
        onWorker {
            if (run != null || playing) {
                startAt(index, 0L, p)
            } else {
                stopRun()
                pausedOn = null
            }
        }
    }

    private fun show(index: Int, ms: Long) {
        host.showIndex(index)
        host.showPosition(ms)
        host.queue.value.getOrNull(index)?.let { host.showDuration(it.durationMs) }
    }

    /** A new queue, from its start — the position shown is still the last track's. */
    override fun newQueue() {
        if (steered() == null) return
        val p = press()
        val row = host.index.value
        show(row, 0L)
        // Shown as playing at once, as the controller's own play does: a skip
        // made while the speaker is being reached carries the start on.
        host.showPlaying(true)
        onWorker {
            stopRun()
            streams.clear()
            pausedOn = null
            startAt(row, 0L, p)
        }
    }

    /**
     * What follows the playing track may have changed. A feed that has gone
     * on into a track that no longer follows — or ended the queue where songs
     * now follow — starts again from where the speaker is, and a playing
     * track taken out of the queue gives way to what stands in its place.
     */
    override suspend fun upcomingChanged() {
        if (steered() == null) return
        onWorker {
            val current = run ?: return@onWorker
            val heard = current.streamer.heardNow() ?: return@onWorker
            val queue = host.queue.value
            if (queue.none { it.id == heard.segment.track.id }) {
                val index = host.index.value
                log("the playing track left the queue → row $index")
                show(index, 0L)
                startAt(index, 0L, presses.get())
                return@onWorker
            }
            val playingRow = rowOf(heard.segment)
            var row = playingRow
            for (later in current.feed.segments.filter { it.firstFrame > heard.segment.firstFrame }) {
                val expected = followingRow(row)
                if (expected == null || queue.getOrNull(expected)?.id != later.track.id) {
                    log("what follows changed → again from ${heard.positionMs} ms")
                    startAt(playingRow, heard.positionMs, presses.get())
                    return@onWorker
                }
                row = expected
            }
            if (current.feed.ended == Feed.End.QUEUE && followingRow(row) != null) {
                log("songs follow the end now → again from ${heard.positionMs} ms")
                startAt(playingRow, heard.positionMs, presses.get())
            }
        }
    }

    override fun repeatChanged(mode: RepeatMode) {}

    // --- Volume --------------------------------------------------------------

    /** Two speakers or more playing: each has its own fader, and the top one is the loudest's. */
    private val grouped: Boolean get() = memberList.size > 1

    /** The one speaker playing: the chosen one, or the one left when it left a group. */
    private fun soloId(): String? = memberList.singleOrNull()?.id ?: steered()?.receiver?.id

    /** The top fader in a group: the loudest speaker's level. */
    private fun master(): Float = memberList.maxOfOrNull { levels[it.id] ?: STARTING_LEVEL } ?: host.volume.value

    override fun setVolume(level: Float) {
        if (steered() == null) return
        if (grouped) {
            scaleGroup(level)
            showMaster()
            return
        }
        val id = soloId() ?: return
        levels[id] = level
        host.showVolume(level)
        host.setPhoneVolume(level)
        queueVolume(id, level)
    }

    override fun setMemberVolume(targetId: String, level: Float) {
        if (steered() == null || memberList.none { it.id == targetId }) return
        levels[targetId] = level
        rememberShares()
        publishGroup()
        queueVolume(targetId, level)
        showMaster()
    }

    /**
     * Each grouped speaker's level as a share of the loudest's, set by its own
     * fader and kept while the top one moves: brought down to silence and back
     * up, the speakers come back to where they stood against each other.
     */
    private val shares = ConcurrentHashMap<String, Float>()

    /** The shares as the levels stand now; at silence there's nothing to learn, so they're kept. */
    private fun rememberShares() {
        val top = master()
        if (top <= 0f) return
        for (m in memberList) shares[m.id] = (levels[m.id] ?: STARTING_LEVEL) / top
    }

    /**
     * The top fader (or the rocker) moved to [level] in a group: the loudest
     * lands there and each of the others at its share of it.
     */
    private fun scaleGroup(level: Float) {
        if (memberList.any { !shares.containsKey(it.id) }) rememberShares()
        for (m in memberList) {
            val moved = ((shares[m.id] ?: 1f) * level).coerceIn(0f, 1f)
            levels[m.id] = moved
            queueVolume(m.id, moved)
        }
        publishGroup()
    }

    /** The top fader and the phone's own level at the loudest speaker's, so the rocker steps from there. */
    private fun showMaster() {
        val m = master()
        host.showVolume(m)
        phoneSetTo = m to System.nanoTime()
        host.scope.launch(Dispatchers.Main.immediate) { host.setPhoneVolume(m) }
    }

    override suspend fun followDeviceVolume(level: Float) {
        if (steered() == null) return
        // The phone's level moved to match one set from here: shown as it was meant, and not sent on.
        phoneSetTo?.let { (said, at) ->
            if (System.nanoTime() - at < ECHO_NS && abs(level - said) <= PHONE_STEP_SLACK) {
                host.showVolume(said)
                return
            }
        }
        if (grouped) {
            scaleGroup(level)
            return
        }
        val id = soloId() ?: return
        levels[id] = level
        queueVolume(id, level)
    }

    // --- Groups --------------------------------------------------------------

    /** Any AirPlay speaker not playing now — the one the cast was made to as well, after it left. */
    override fun canGroup(target: OutputTarget): Boolean =
        target is AirPlayTarget && steered() != null && memberList.none { it.id == target.id }

    /** Play on [target] as well: at once, in step, if the sound is going; else from the next play. */
    override fun addToGroup(target: OutputTarget) {
        val r = (target as? AirPlayTarget)?.receiver ?: return
        if (steered() == null) return
        onWorker {
            if (members.containsKey(r.id) || steered() == null) return@onWorker
            levels.putIfAbsent(r.id, STARTING_LEVEL)
            setMembers(members.values + r)
            rememberShares()
            showMaster()
            log("${r.name} joins")
            val current = run ?: return@onWorker
            val s = sessions[r.id]?.takeIf { it.isOpen } ?: openSession(r) ?: return@onWorker
            if (run === current && members.containsKey(r.id)) current.streamer.join(s)
        }
    }

    /** Stop playing on [target]; the others carry on. The last one isn't let go here: that's a playHere. */
    override fun removeFromGroup(target: OutputTarget) {
        val id = (target as? AirPlayTarget)?.receiver?.id ?: return
        onWorker {
            if (members.size <= 1 || !members.containsKey(id)) return@onWorker
            log("${members[id]?.name} leaves")
            dropMember(id, say = true)
        }
    }

    /** [id] no longer plays: its session goes, the rest play on. */
    private fun dropMember(id: String, say: Boolean) {
        members.remove(id) ?: return
        setMembers(members.values.toList())
        // The top fader is the one left's own again, or the loudest of those left.
        shares.remove(id)
        if (grouped) {
            rememberShares()
            showMaster()
        } else {
            memberList.singleOrNull()?.let { showLevel(it) }
        }
        val s = sessions.remove(id) ?: return
        remoteFor.remove(s.activeRemote)
        run?.streamer?.leave(s)
        if (say) s.teardown() else s.close()
    }

    private fun setMembers(list: List<AirPlayReceiver>) {
        members.clear()
        for (r in list) members[r.id] = r
        memberList = members.values.toList()
        publishGroup()
    }

    /** Empty while the chosen speaker plays alone; else who plays, so the page can tick them. */
    private fun publishGroup() {
        val alone = memberList.isEmpty() || memberList.map { it.id } == listOfNotNull(receiver?.id)
        _group.value = if (alone) emptyList() else memberList.map { GroupMember(AirPlayTarget(it), levels[it.id] ?: STARTING_LEVEL) }
    }

    // --- The speaker's own controls (see DacpRemote) -------------------------

    private fun onSpeakerCommand(activeRemote: String, command: DacpCommand) {
        val from = remoteFor[activeRemote] ?: return
        when (command) {
            is DacpCommand.SpeakerVolume -> onWorker { followSpeaker(from, command.db) }
            DacpCommand.Play -> host.pressedAtFarEnd(this, FarEndPress.PLAY)
            DacpCommand.Pause -> host.pressedAtFarEnd(this, FarEndPress.PAUSE)
            DacpCommand.PlayPause -> host.pressedAtFarEnd(this, FarEndPress.PLAY_PAUSE)
            DacpCommand.Next -> host.pressedAtFarEnd(this, FarEndPress.NEXT)
            DacpCommand.Previous -> host.pressedAtFarEnd(this, FarEndPress.PREVIOUS)
            is DacpCommand.VolumeStep -> host.scope.launch(Dispatchers.Main.immediate) {
                if (steered() == null) return@launch
                val now = levels[from] ?: host.volume.value
                val moved = (now + if (command.up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0f, 1f)
                if (grouped) setMemberVolume(from, moved) else setVolume(moved)
            }
        }
    }

    /**
     * The speaker's level moved at the speaker: the fader follows, and the
     * phone's level with it for the rocker. Nothing goes back — a level
     * written back can land a step off and snap the speaker's knob back.
     */
    private fun followSpeaker(id: String, db: Double) {
        val s = sessions[id] ?: return
        if (steered() == null) return
        // The speaker telling back the level just sent is no news.
        if (System.nanoTime() - s.volumeSentAt < ECHO_NS) return
        val level = levelFor(db)
        levels[id] = level
        if (grouped) {
            rememberShares()
            publishGroup()
            showMaster()
            return
        }
        phoneSetTo = level to System.nanoTime()
        host.showVolume(level)
        host.scope.launch(Dispatchers.Main.immediate) { host.setPhoneVolume(level) }
    }

    /** At most one volume request waits on the worker, with the latest level: a drag never holds up a press. */
    private fun queueVolume(id: String, level: Float) {
        wantedDb[id] = dbFor(level)
        if (!volumeQueued.compareAndSet(false, true)) return
        onWorker {
            volumeQueued.set(false)
            for ((who, db) in wantedDb.entries.toList()) {
                wantedDb.remove(who, db)
                val s = sessions[who] ?: continue
                ask(s) { s.setVolume(db) }
            }
        }
    }

    // --- Playing (worker thread) ---------------------------------------------

    /**
     * Play the queue from [row] at [ms] for press [press], opening the
     * session first if there is none, or none the speaker still holds.
     */
    private fun startAt(row: Int, ms: Long, press: Int) {
        if (press != presses.get()) return
        stopRun()
        val target = steered() ?: return
        val castId = target.receiver.id
        val track = host.queue.value.getOrNull(row) ?: return
        if (!AirPlayFamily.allowsNetwork()) {
            host.couldNotPlay(this)
            return
        }
        val opened = ArrayList<RaopSession>()
        for (r in members.values.toList()) {
            // A session kept from before is asked whether it's still there: a
            // FLUSH answers, and drops whatever of the last run the speaker holds.
            var s = sessions[r.id]?.takeIf { it.isOpen }
            if (s != null) {
                try {
                    s.flush()
                } catch (e: IOException) {
                    log("${r.name} let the session go (${e::class.simpleName}) → a new one")
                    s.close()
                    sessions.remove(r.id)
                    remoteFor.remove(s.activeRemote)
                    s = null
                }
            }
            (s ?: openSession(r))?.let { opened += it }
            // Superseded while a speaker was being reached: the later press has the say.
            if (press != presses.get() || steered()?.receiver?.id != castId) return
        }
        if (opened.isEmpty()) return
        val feed = Feed(
            open = ::openTrack,
            following = { segment ->
                val queue = host.queue.value
                followingRow(rowOf(segment))?.let { next -> queue.getOrNull(next)?.let { next to it } }
            },
            gainFor = host::gainFor,
            log = ::log,
            first = row to track,
            fromMs = ms,
            prefetch = ::prefetch,
        )
        lateinit var started: Run
        val streamer = Streamer(opened, feed, events { started }, ::log)
        started = Run(streamer, feed, press)
        run = started
        pausedOn = null
        host.showPlaying(true)
        streamer.start()
        log("playing row $row from $ms ms")
    }

    /** Stop sending: what the speaker had got to, or null with nothing running. */
    private fun stopRun(): Heard? {
        val current = run ?: return null
        run = null
        return current.streamer.stop()
    }

    /** Drop what the speaker still holds of a run, if anything went out since the last flush. */
    private fun flushIfNeeded() {
        for (s in sessions.values.toList()) if (s.unflushed) ask(s) { s.flush() }
    }

    private fun pauseHere(press: Int) {
        val heard = stopRun()
        // Whatever was shown since — a start that finished after this press
        // was made — the pause is the last word on playing.
        if (steered() != null) host.showPaused()
        flushIfNeeded()
        heard ?: return
        pausedOn = heard.segment.track.id
        // Where it stopped, unless a press since (a skip, a seek) has shown another place.
        if (press != presses.get()) return
        host.showIndex(rowOf(heard.segment))
        host.showPosition(heard.positionMs)
    }

    private fun openSession(r: AirPlayReceiver): RaopSession? {
        val userAgent = "Amp/${BuildConfig.VERSION_NAME}"
        val opened = try {
            RaopSession.open(r, userAgent, authSetup = r.id in wantsAuthSetup, log = ::log, dacpId = remote.id)
        } catch (e: RaopRefused) {
            // Some speakers turn a stream away until a sender starts the
            // handshake they offer (see RaopSession.authSetup); asked once more.
            if (e.status == FORBIDDEN && r.takesAuthSetup && r.id !in wantsAuthSetup) {
                log("${r.name}: ${e.step} refused (403) → again with auth-setup")
                wantsAuthSetup += r.id
                return openSession(r)
            }
            log("${r.name}: ${e.step} refused (${e.status})")
            // Busy with another sender, after this one had played there (a
            // session the link cut is still held for a while): shown paused,
            // and a later play asks again.
            couldNotOpen(r, busy = e.status == BUSY)
            return null
        } catch (e: IOException) {
            log("${r.name}: couldn't connect (${e::class.simpleName})")
            couldNotOpen(r, busy = false)
            return null
        }
        sessions[r.id] = opened
        remoteFor[opened.activeRemote] = r.id
        runCatching { remote.start(opened.localAddress) }
        log("${r.name}: session open, latency ${opened.latencyFrames} frames")
        // AirPlay 1 can't ask a speaker its level, and on a receiver it is
        // the master volume: the fader's level goes before a note plays, so
        // nothing starts louder than the fader shows.
        val start = levels[r.id] ?: if (r.id == steered()?.receiver?.id) host.volume.value else STARTING_LEVEL
        ask(opened) { opened.setVolume(dbFor(start)) }
        // The speaker hung up straight after RECORD.
        if (sessions[r.id] !== opened) {
            couldNotOpen(r, busy = false)
            return null
        }
        return opened
    }

    /**
     * [r] couldn't be had. In a group it just leaves. Alone: busy with another
     * sender after this one had played there (a session the link cut is still
     * held a while), it's shown paused and a later play asks again; otherwise
     * the cast is given up.
     */
    private fun couldNotOpen(r: AirPlayReceiver, busy: Boolean) {
        if (members.size > 1) {
            log("${r.name} leaves the group")
            dropMember(r.id, say = false)
            return
        }
        if (busy && playedSinceConnect) {
            if (steered() != null) host.couldNotPlay(this)
        } else {
            giveUp(r)
        }
    }

    /** The speaker couldn't be had: before anything played there, the cast is given up; after, it ended there. */
    private fun giveUp(r: AirPlayReceiver) {
        val target = steered() ?: return
        if (target.receiver.id != r.id && !members.containsKey(r.id)) return
        // The controller lets go without a disconnect: the fetches go here, and the remote.
        streams.clear()
        remote.stop()
        if (playedSinceConnect) host.endedOnItsOwn(this, target) else host.dropped(this, target)
    }

    private fun endSession(say: Boolean) {
        stopRun()
        setMembers(emptyList())
        shares.clear()
        val all = sessions.values.toList()
        sessions.clear()
        remoteFor.clear()
        for (s in all) if (say) s.teardown() else s.close()
    }

    /**
     * A request the speaker may turn down — logged, nothing more — or
     * fail to hear at all: the connection is gone, so the session is.
     */
    private fun ask(s: RaopSession, request: () -> Unit) {
        try {
            request()
        } catch (e: RaopRefused) {
            log("${s.receiver.name} refused ${e.step} (${e.status})")
        } catch (e: SocketTimeoutException) {
            log("${s.receiver.name} didn't answer in time")
        } catch (e: IOException) {
            sessionLost(s, e, "a request went unanswered")
        }
    }

    /**
     * The speaker let the session go: closed the connection, stopped asking
     * the time. Playing, the cast ends there — a moment later, so that a
     * link lost at the same time (the likelier cause) is the one that wins,
     * and the speaker is taken back with the link.
     */
    private fun sessionLost(s: RaopSession, cause: IOException, why: String) {
        val id = s.receiver.id
        if (sessions[id] !== s) return
        // Amp's own fixed words where they're the cause ("connection closed"); anything else by its kind only.
        val detail = cause.message?.takeIf { it in OWN_WORDS || it.startsWith(SILENCE_WORDS) } ?: cause::class.simpleName.orEmpty()
        log("${s.receiver.name} let the session go: $why ($detail; ${traffic(s, System.nanoTime())})")
        // One of several: it leaves, the rest play on.
        if (members.size > 1) {
            dropMember(id, say = false)
            return
        }
        sessions.remove(id)
        remoteFor.remove(s.activeRemote)
        s.close()
        val wasRunning = run != null
        stopRun()
        if (!wasRunning) return
        val p = presses.get()
        // Only if nothing has happened since: a press, or a play that opened
        // a session afresh, has taken it on.
        worker.schedule({
            if (presses.get() == p && run == null && sessions.isEmpty()) {
                steered()?.takeIf { it.receiver.id == id || members.containsKey(id) }?.let {
                    streams.clear()
                    remote.stop()
                    host.endedOnItsOwn(this, it)
                }
            }
        }, LET_GO_AFTER_S, TimeUnit.SECONDS)
    }

    /** Every couple of seconds: the session kept alive while idle, and watched while playing. */
    /** One speaker's traffic in a line: what a silence or a drop-out is read against. */
    private fun traffic(s: RaopSession, now: Long): String {
        val asked = if (s.timingAsks == 0) "never" else "${(now - s.lastTimingRequestAt) / 1_000_000} ms ago"
        return "time asked ${s.timingAsks}× (last $asked), resends asked ${s.resendAsks}, packets resent ${s.resent}, sent ${s.packetsSent}"
    }

    private fun tick() {
        for (s in sessions.values.toList()) {
            val now = System.nanoTime()
            val current = run
            // Silence counted from the run's start: a speaker may stop asking while paused.
            val quietFor = if (current != null && s.asksTime) now - maxOf(s.lastTimingRequestAt, current.startedAt) else 0L
            if (quietFor > TIMING_SILENCE_NS) {
                // A speaker in a group can go 12 s without asking and still be playing
                // (seen on both: no packets lost, the music going on). One that answers
                // is still there; one that doesn't, or stays silent a minute, is gone.
                if (quietFor > TIMING_GIVE_UP_NS) {
                    sessionLost(s, IOException("$SILENCE_WORDS ${quietFor / 1_000_000_000} s"), "it stopped asking the time")
                } else if (now - s.lastRequestAt >= PROBE_EVERY_NS) {
                    try {
                        s.keepAlive()
                        log("${s.receiver.name} hasn't asked the time for ${quietFor / 1_000_000_000} s but answers: still playing (${traffic(s, now)})")
                    } catch (e: IOException) {
                        sessionLost(s, e, "it stopped asking the time and didn't answer")
                    }
                }
                continue
            }
            if (now - s.lastRequestAt < KEEP_ALIVE_NS) continue
            ask(s) { s.keepAlive() }
        }
    }

    private fun events(current: () -> Run) = object : StreamEvents {
        override fun heard(heard: Heard) {
            val r = current()
            if (run !== r || r.press != presses.get() || !heard.sounding || steered() == null) return
            if (!r.soundingShown) {
                r.soundingShown = true
                playedSinceConnect = true
                host.showSounding()
            }
            if (r.shownSegment !== heard.segment) {
                r.shownSegment = heard.segment
                r.described.clear()
                val row = rowOf(heard.segment)
                host.showIndex(row)
                host.showDuration(heard.segment.track.durationMs)
                onWorker { describe(r, heard.segment) }
            } else if (r.streamer.sessions.any { it !in r.described }) {
                // One that joined since: told what plays.
                onWorker { describe(r, heard.segment) }
            }
            host.showPosition(heard.positionMs)
        }

        override fun finished(end: Feed.End, last: Heard?) {
            val r = current()
            onWorker {
                if (run !== r) return@onWorker
                run = null
                if (steered() == null) return@onWorker
                when (end) {
                    Feed.End.QUEUE -> {
                        log("queue finished")
                        pausedOn = null
                        host.queueFinished(this@AirPlayOutput)
                    }
                    else -> {
                        // The track that wouldn't play is the one shown, so it's
                        // seen, and a play tries it again.
                        val broken = r.feed.brokenAt
                        if (broken != null) {
                            val (row, track) = broken
                            log("stopped at row $row ($end)")
                            show(rowOfTrack(track, row), 0L)
                            pausedOn = track.id
                        } else if (last != null) {
                            log("stopped partway ($end)")
                            host.showIndex(rowOf(last.segment))
                            host.showPosition(last.positionMs)
                            pausedOn = last.segment.track.id
                        }
                        host.couldNotPlay(this@AirPlayOutput)
                    }
                }
            }
        }

        override fun couldNotStart() {
            val r = current()
            onWorker {
                if (run !== r) return@onWorker
                run = null
                log("couldn't read the track")
                pausedOn = r.feed.brokenAt?.second?.id
                if (steered() != null) host.couldNotPlay(this@AirPlayOutput)
            }
        }

        override fun lost(cause: IOException) {
            val r = current()
            onWorker {
                if (run !== r) return@onWorker
                val s = r.streamer.sessions.firstOrNull() ?: sessions.values.firstOrNull()
                if (s != null) {
                    sessionLost(s, cause, "sending failed")
                } else {
                    stopRun()
                    if (steered() != null) host.couldNotPlay(this@AirPlayOutput)
                }
            }
        }

        override fun leftGroup(session: RaopSession, cause: IOException) {
            onWorker { sessionLost(session, cause, "sending to it failed") }
        }
    }

    /** The speaker's display, as far as it has one: the track's title, its place, its cover. */
    private fun describe(r: Run, segment: Segment) {
        if (run !== r) return
        val track = segment.track
        for (s in r.streamer.sessions) {
            if (s in r.described || sessions[s.receiver.id] !== s) continue
            // Not yet sent a packet of this run: told on the next report.
            val first = r.streamer.rtpOf(s, segment.firstFrame) ?: continue
            r.described += s
            val shown = s.receiver
            val start = first - segment.startMs * AIRPLAY_RATE / 1000
            val end = start + track.durationMs * AIRPLAY_RATE / 1000
            if (shown.showsText) ask(s) { s.setMetadata(track.title, track.artist, track.album, start and MASK32) }
            if (shown.showsProgress && sessions[shown.id] === s) {
                r.streamer.rtpNow(s)?.let { now -> ask(s) { s.setProgress(start, now, end) } }
            }
            // The cover may have to be fetched: away from the worker, so no press waits on it.
            if (shown.showsArtwork) {
                host.scope.launch {
                    val jpeg = host.coverJpeg(track, COVER_PX) ?: return@launch
                    onWorker { if (run === r && sessions[shown.id] === s) ask(s) { s.setArtwork(jpeg, start and MASK32) } }
                }
            }
        }
    }

    // --- Reading tracks (the feed's thread) ----------------------------------

    private fun openTrack(track: Track, fromMs: Long): PcmSource {
        val viaServer = fromMs >= 1000 && onMain { host.seeksAtServer(track) }
        val offsetSeconds = if (viaServer) (fromMs / 1000).toInt() else 0
        val key = "${track.id}@$offsetSeconds"
        val extractor = MediaExtractor()
        try {
            var streamStartMs = 0L
            var fetched = streams.get(key)
            if (fetched != null) {
                extractor.setDataSource(fetched.source())
                streamStartMs = offsetSeconds * 1000L
            } else {
                when (val source = onMain { host.phoneSource(track, offsetSeconds) }) {
                    null -> throw IOException("nothing to read it from")
                    is PhoneSource.File -> extractor.setDataSource(source.file.absolutePath)
                    is PhoneSource.Url -> {
                        fetched = streams.open(key, source.url)
                        extractor.setDataSource(fetched.source())
                        streamStartMs = source.offsetMs
                    }
                }
            }
            // A server that sent the whole file for all that (Navidrome, for one
            // it serves as it is) is sought within like a file.
            if (streamStartMs > 0 && streamIsWholeTrack(extractor, track)) streamStartMs = 0
            val seekUs = ((fromMs - streamStartMs).coerceAtLeast(0)) * 1000
            val decoded = decodedTrack(extractor, seekUs, startMs = fromMs, canSeek = fetched == null || fetched.size >= 0)
            return if (fetched != null) BreakAware(decoded, fetched) else decoded
        } catch (e: Exception) {
            runCatching { extractor.release() }
            throw e as? IOException ?: IOException(e)
        }
    }

    /** Start fetching [track]'s stream from its start, unless it's a file or already coming. */
    private fun prefetch(track: Track) {
        val key = "${track.id}@0"
        if (streams.has(key)) return
        val source = onMain { host.phoneSource(track) } as? PhoneSource.Url ?: return
        streams.open(key, source.url)
    }

    private fun streamIsWholeTrack(extractor: MediaExtractor, track: Track): Boolean {
        if (track.durationMs <= 0) return false
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (!f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) continue
            if (!f.containsKey(MediaFormat.KEY_DURATION)) return false
            return abs(f.getLong(MediaFormat.KEY_DURATION) / 1000 - track.durationMs) < WHOLE_TRACK_SLACK_MS
        }
        return false
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }

    // --- The queue -----------------------------------------------------------

    /** The row that plays after [row] runs out: itself under repeat-one, wrapping under repeat, none past the end. */
    private fun followingRow(row: Int): Int? {
        val size = host.queue.value.size
        if (size == 0) return null
        val next = when (host.repeatMode.value) {
            RepeatMode.TRACK -> row
            RepeatMode.QUEUE -> (row + 1) % size
            else -> row + 1
        }
        return next.takeIf { it in 0 until size }
    }

    /**
     * Where [segment]'s track sits in the queue now: the row it was laid at,
     * unless an edit since has moved it, then the nearest row holding it.
     */
    private fun rowOf(segment: Segment): Int = rowOfTrack(segment.track, segment.row)

    private fun rowOfTrack(track: Track, laidAt: Int): Int {
        val queue = host.queue.value
        if (queue.getOrNull(laidAt)?.id == track.id) return laidAt
        val playing = host.index.value
        return queue.indices.filter { queue[it].id == track.id }.minByOrNull { abs(it - playing) } ?: playing
    }

    private companion object {
        /** The fader for a speaker never set from here: low enough that a receiver can't start loud. */
        const val STARTING_LEVEL = 0.3f

        /** What Rtsp says when the speaker's answer isn't there: safe to log as they are. */
        val OWN_WORDS = setOf("connection closed", "not an RTSP reply", "not an RTSP status", "an RTSP line too long")
        const val SILENCE_WORDS = "none for"

        /** How long a speaker's report of a level just sent, or the phone's of one just reported, counts as an echo. */
        val ECHO_NS = TimeUnit.SECONDS.toNanos(2)

        /** More than a step of the phone's own volume, which the report's level is rounded to. */
        const val PHONE_STEP_SLACK = 0.1f
        const val VOLUME_STEP = 0.05f
        const val TICK_S = 2L
        val KEEP_ALIVE_NS = TimeUnit.SECONDS.toNanos(10)

        /** A speaker asks the time every three seconds or so while it has a session. */
        val TIMING_SILENCE_NS = TimeUnit.SECONDS.toNanos(12)

        /** Silent this long, answering or not, it has let the session go. */
        val TIMING_GIVE_UP_NS = TimeUnit.SECONDS.toNanos(60)

        /** How often a silent speaker is asked whether it's still there. */
        val PROBE_EVERY_NS = TimeUnit.SECONDS.toNanos(4)
        const val LET_GO_AFTER_S = 3L
        const val STOP_WAIT_MS = 1_500L
        const val SEEK_END_SLACK_MS = 1_000L
        const val FIND_AGAIN_ROUNDS = 4
        const val WHOLE_TRACK_SLACK_MS = 3_000L
        const val COVER_PX = 600
        const val MASK32 = 0xffffffffL
        const val FORBIDDEN = 403
        const val BUSY = 453

        /** AirPlay's volume runs from −30 dB to 0, with −144 for silence. */
        fun dbFor(level: Float): Double = if (level <= 0f) RaopSession.MUTE_DB else -30.0 + 30.0 * level.coerceAtMost(1f)

        /** The fader's level for a speaker's [db]: [dbFor] the other way. */
        fun levelFor(db: Double): Float = if (db <= -30.0) 0f else ((db + 30.0) / 30.0).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * The streams fetched lately, kept while they may be read again: the
 * playing track and the one after, and the one before for a step back.
 */
internal class StreamCache(folder: () -> File) {
    private val dir by lazy(folder)
    private val files = LinkedHashMap<String, StreamFile>()

    /** A fetch of [key] that is under way or done, never one that failed. */
    @Synchronized
    fun get(key: String): StreamFile? {
        val file = files.remove(key) ?: return null
        if (file.failed) {
            file.close()
            return null
        }
        files[key] = file
        return file
    }

    @Synchronized
    fun has(key: String): Boolean = files[key]?.failed == false

    @Synchronized
    fun open(key: String, url: String): StreamFile {
        files.remove(key)?.close()
        val file = StreamFile(url, File(dir, "${files.size}-${System.nanoTime()}"))
        files[key] = file
        while (files.size > KEPT) {
            val oldest = files.keys.first()
            files.remove(oldest)?.close()
        }
        return file
    }

    @Synchronized
    fun clear() {
        files.values.forEach { it.close() }
        files.clear()
    }

    private companion object {
        const val KEPT = 3
    }
}

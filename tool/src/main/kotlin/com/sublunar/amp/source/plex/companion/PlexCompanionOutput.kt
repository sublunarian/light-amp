package com.sublunar.amp.source.plex.companion

import com.sublunar.amp.data.Connectivity
import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.playback.output.Disconnect
import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget
import com.sublunar.amp.playback.output.PREVIOUS_RESTARTS_AFTER_MS
import com.sublunar.amp.playback.output.QueueEdit
import com.sublunar.amp.playback.output.VolumeControl
import com.sublunar.amp.source.plex.PlexClient
import com.sublunar.amp.source.plex.PlexPlayer
import com.sublunar.amp.source.plex.PlexPlayerTimeline
import com.sublunar.amp.source.plex.PlexQueue
import com.sublunar.amp.source.plex.plexTimelineFrom
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Playing to a Plex player — the Apple TV app, another Plexamp — over Plex's
 * Companion protocol.
 *
 * A different animal from DLNA: the player is handed a *server-side play
 * queue* and walks it itself — gapless, and skippable from its own remote.
 * So this side mirrors rather than drives: the poll adopts whatever the
 * player reports, including track changes and pauses made on the TV.
 * Commands are relayed through the server the same way the official
 * controllers do it, so nothing here ever connects to the player. Only a
 * Plex source can do this, so only the Plex provider offers it.
 *
 * The calls themselves are PlexClient's: they need its token and its lock.
 */
internal class PlexCompanionOutput(private val host: OutputHost) : Output {
    override val family = PlexCompanionFamily

    override val logTag = "AmpPlex"

    // The player reports its own playback to the server, and this phone
    // adding another would double every play.
    override val reportsItself = true

    override val needsLan = false

    // The player's last reported state decides whether sound starts here
    // or waits.
    override fun handBackPlays(wasPlaying: Boolean) = wasPlaying

    /**
     * Whether the fader can move the player's volume — decided by evidence,
     * not by the player's word.
     *
     * A player that reports a level it doesn't own is the awkward case, and
     * the common one: an Apple TV feeding a receiver over optical says
     * `volume="0"` for ever, takes the command, and changes nothing — the
     * amplifier owns the sound. So [VolumeControl.Unknown] is where every
     * player starts, a drag is allowed as a *probe*, and the answer comes from
     * what the player reports afterwards. Once [VolumeControl.NotOurs], the
     * fader is disabled rather than left to lie.
     */
    private val _volumeControl = MutableStateFlow(VolumeControl.Unknown)
    override val volumeControl: StateFlow<VolumeControl> = _volumeControl

    /** The level last asked for (0–100) while proving control, if any. */
    private var plexVolumeAsked: Int? = null
    private var plexVolumeProbes = 0

    private var plexJob: Job? = null
    private var plexQueue: PlexQueue? = null
    private var plexPushedIds: List<String> = emptyList()

    /** Companion wants a fresh command id per request, per controller. */
    private var plexCommandId = 0

    /**
     * How far into the queue the player has been given, or -1 when it holds
     * the lot.
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
                host.scope.launch(Dispatchers.Main.immediate) { plexCastEnded() }
            } else {
                host.scope.launch(Dispatchers.Main.immediate) { adoptPlexTimeline(t) }
            }
        }
    }

    /** Read per call, never kept: a client rebuilt for the same source — a renewed token — is the one that answers. */
    private fun plexClient(): PlexClient? = host.client() as? PlexClient

    /** The player this is steering, or null once it has been let go. */
    private fun steered(): PlexPlayer? = (host.steering(this) as? PlexCompanionTarget)?.player

    override fun canSteer() = plexClient() != null

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
    override suspend fun find(): List<OutputTarget> {
        val client = plexClient() ?: return emptyList()
        // The server's list and plex.tv are both remote lookups, and on a
        // metered link in Wi-Fi Only they are worse than merely costly: they
        // name players on a network this phone isn't on, so the page offers a
        // living room it cannot reach. GDM is a broadcast to whatever network
        // this is, costs nothing off it, and answers the question that matters.
        val listed = if (host.mayTalkToServer()) client.companionPlayers() else emptyList()
        // GDM is a broadcast to the local network, so off Wi-Fi there is nobody
        // to broadcast to and nothing that could answer — a player reachable
        // from here would have to be on this network, and there is no network.
        // It costs no cellular data either way; what it costs is the wait for
        // its own timeout, on a page that cannot find anything anyway.
        val onNetwork = if (Connectivity.isOnWifi()) PlexGdm.findPlayers() else emptyList()
        val seen = listed.map { it.id }.toMutableSet()
        val extra = onNetwork.filter { seen.add(it.id) }
        android.util.Log.i("AmpPlex", "gdm found ${onNetwork.map { it.name }}, new here ${extra.map { it.name }}")
        return (listed + extra).map(::PlexCompanionTarget)
    }

    /**
     * Move playback to the player: push the queue to the server, point the
     * player at it from [fromMs], and start mirroring its reports.
     */
    override fun connect(target: OutputTarget, fromMs: Long) {
        val player = (target as PlexCompanionTarget).player
        val client = plexClient() ?: run {
            host.dropped(this, target)
            return
        }
        _volumeControl.value = VolumeControl.Unknown
        plexVolumeAsked = null
        plexVolumeProbes = 0
        plexWindowEnd = -1
        plexJob?.cancel()
        plexJob = host.scope.launch {
            if (host.queue.value.isEmpty()) {
                host.dropped(this@PlexCompanionOutput, target)
                return@launch
            }
            // A device named only by plex.tv is named by an address the account
            // has on file, and an address is not an identity — a lease that
            // moved would have us handing this queue to whatever answers there
            // now. Ask it who it is before it gets any audio.
            if (!player.identityKnown && !client.playerIsWhoWeThink(player)) {
                android.util.Log.i("AmpPlex", "${player.name} isn't who plex.tv said — not casting")
                host.dropped(this@PlexCompanionOutput, target)
                return@launch
            }
            // The same push a new queue takes — windowed, so a long queue
            // doesn't arrive as five kilobytes of URL.
            repushPlexQueue(positionMs = fromMs)
            if (plexQueue == null) {
                android.util.Log.i("AmpPlex", "play queue refused — staying on this device")
                host.dropped(this@PlexCompanionOutput, target)
                return@launch
            }
            if (host.repeatMode.value == RepeatMode.TRACK) {
                client.companionSetParameters(player, listOf("repeat" to "1"), ++plexCommandId)
            }
            subscribeToPlexPlayer(client, player)
            pollPlexPlayer(client, player)
        }
    }

    /** Stop steering the player, for [why]: told to stop, or left playing when the app is left. */
    override fun disconnect(target: OutputTarget, why: Disconnect) {
        val player = (target as PlexCompanionTarget).player
        if (why == Disconnect.Leaving) {
            // A Companion player is deliberately left playing: it walks its own
            // queue and answers to the server, so closing the app that steered it
            // is no reason to silence the room — that's how every Plex remote
            // behaves. Only the steering stops.
            android.util.Log.i("AmpPlex", "leaving the player playing")
            // The subscription goes even though the playback stays: pushes to
            // a port that is about to close would have the player talking to
            // nothing.
            endPlexSubscription(plexClient(), player)
            plexJob?.cancel()
            plexJob = null
            plexQueue = null
            return
        }
        val client = plexClient()
        plexJob?.cancel()
        plexJob = null
        plexQueue = null
        plexWindowEnd = -1
        // Close the subscription too, as every other way out of a cast
        // does. Left open, the player goes on pushing its timeline at a
        // socket nobody is reading and then at a dead port, and a player
        // whose requests keep failing does not stay well — see the note on
        // playerLock in PlexClient. Nothing else can clean it up either:
        // the player's own "stopped" push routes to plexCastEnded, which
        // returns immediately once the controller has let go, as it has.
        endPlexSubscription(client, player)
        host.scope.launch { client?.companionCommand(player, "stop", ++plexCommandId) }
    }

    override fun play() {
        val target = steered() ?: return
        host.scope.launch { plexClient()?.companionCommand(target, "play", ++plexCommandId) }
    }

    override fun pause() {
        val target = steered() ?: return
        host.scope.launch { plexClient()?.companionCommand(target, "pause", ++plexCommandId) }
    }

    override fun seekTo(ms: Long) {
        val target = steered() ?: return
        host.showPosition(ms)
        host.scope.launch { plexClient()?.companionSeek(target, ms, ++plexCommandId) }
    }

    override fun next() {
        val target = steered() ?: return
        host.scope.launch { plexClient()?.companionCommand(target, "skipNext", ++plexCommandId) }
    }

    override fun previous() {
        val target = steered() ?: return
        host.scope.launch {
            if (host.positionMs.value > PREVIOUS_RESTARTS_AFTER_MS) {
                plexClient()?.companionSeek(target, 0, ++plexCommandId)
            } else {
                plexClient()?.companionCommand(target, "skipPrevious", ++plexCommandId)
            }
        }
    }

    override fun jumpTo(index: Int) {
        val target = steered() ?: return
        val track = host.queue.value[index]
        host.showIndex(index)
        host.showPosition(0)
        host.scope.launch {
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
                if (steered()?.id == target.id &&
                    host.queue.value.getOrNull(host.index.value)?.id != track.id
                ) {
                    android.util.Log.i("AmpPlex", "skipTo idx=$index didn't land — rebuilding around it")
                    host.showIndex(index)
                    host.showPosition(0)
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
    }

    override fun repeatChanged(mode: RepeatMode) {
        val target = steered() ?: return
        // Companion's values: 0 off, 1 the track, 2 the whole queue.
        val plexRepeat = when (mode) {
            RepeatMode.OFF -> "0"
            RepeatMode.TRACK -> "1"
            RepeatMode.QUEUE -> "2"
        }
        host.scope.launch {
            plexClient()?.companionSetParameters(target, listOf("repeat" to plexRepeat), ++plexCommandId)
        }
    }

    override fun setVolume(level: Float) {
        val target = steered() ?: return
        // A player whose volume isn't its own to move takes no orders —
        // see judgePlexVolume. Until that is settled, a drag is the probe:
        // it goes out, and the next polls say whether it landed. The
        // phone's own level is left alone either way — it has no business
        // changing while the sound is elsewhere.
        if (_volumeControl.value == VolumeControl.NotOurs) return
        val asked = (level * 100).roundToInt()
        plexVolumeAsked = asked
        host.showVolume(level)
        host.scope.launch {
            plexClient()?.companionSetParameters(
                target,
                listOf("volume" to asked.toString()),
                ++plexCommandId,
            )
        }
    }

    override fun newQueue() {
        host.showPosition(0L)
        host.scope.launch { repushPlexQueue(positionMs = 0L) }
    }

    override fun queueEdited(edit: QueueEdit) {
        when (edit) {
            is QueueEdit.Appended ->
                plexQueueEdit { client, queue -> client.addToPlayQueue(queue, edit.ids, next = false) }
            is QueueEdit.PlayNext ->
                plexQueueEdit { client, queue -> client.addToPlayQueue(queue, listOf(edit.id), next = true) }
            // Everything after the playing track goes, then the new set
            // arrives — expressed as edits so the song already playing
            // carries on untouched, which is the whole point of replacing
            // only what is upcoming.
            is QueueEdit.UpcomingReplaced -> plexQueueEdit { client, queue ->
                val emptied = edit.dropped.fold<String, PlexQueue?>(queue) { acc, id ->
                    acc?.let { client.removePlayQueueItem(it, id) }
                }
                if (edit.added.isEmpty()) emptied
                else emptied?.let { client.addToPlayQueue(it, edit.added, next = false) }
            }
            is QueueEdit.Removed -> plexQueueEdit { client, queue ->
                // One at a time, each answer carrying the places for the next.
                edit.ids.fold<String, PlexQueue?>(queue) { acc, id ->
                    acc?.let { client.removePlayQueueItem(it, id) }
                }
            }
            // Plex places an item *after* another one, so the block is
            // expressed as whoever its first track now follows, and each
            // one after that follows the one before it — one call at a
            // time, each answer carrying the places for the next.
            is QueueEdit.Moved -> plexQueueEdit { client, queue ->
                edit.ids.foldIndexed<String, PlexQueue?>(queue) { i, acc, id ->
                    acc?.let { client.movePlayQueueItem(it, id, if (i == 0) edit.afterId else edit.ids[i - 1]) }
                }
            }
            // Reordering everything upcoming is the one edit not worth
            // expressing as a move apiece — a shuffled hundred is a hundred
            // round trips. The player gets a fresh queue, and pays a blip
            // for it.
            QueueEdit.Reordered -> host.scope.launch { repushPlexQueue() }
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
        while (coroutineContext.isActive && steered()?.id == target.id) {
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
            delay(if (host.isPlaying.value) PLEX_POLL_MS else PLEX_IDLE_POLL_MS)
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
        host.showPlaying(t.state == "playing")
        host.showPosition(t.timeMs)
        if (t.durationMs > 0) host.showDuration(t.durationMs)
        judgePlexVolume(t.volume, t.controllable)
        // Repeat can be changed on the player's own remote, and a mode the
        // phone doesn't know about is a phone showing the wrong one.
        t.repeat?.let { reported ->
            val mode = when (reported) {
                1 -> RepeatMode.TRACK
                2 -> RepeatMode.QUEUE
                else -> RepeatMode.OFF
            }
            if (mode != host.repeatMode.value) {
                android.util.Log.i("AmpPlex", "player's repeat is $mode")
                host.showRepeat(mode)
            }
        }
        t.shuffle?.let { on -> if (on != host.shuffle.value) host.showShuffle(on) }
        val rk = t.ratingKey
        if (rk != null && host.queue.value.getOrNull(host.index.value)?.id != rk) {
            // The player moved on its own — its remote skipped, or a track
            // simply ended. Follow it; don't fight it.
            val idx = host.queue.value.indexOfFirst { it.id == rk }
            if (idx >= 0) {
                host.showIndex(idx)
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
        if (_volumeControl.value != VolumeControl.NotOurs &&
            controllable != null &&
            !controllable.split(',').any { it.trim().equals("volume", ignoreCase = true) }
        ) {
            plexVolumeNotOurs("player's timeline doesn't list volume")
            return
        }
        if (reported == null) return
        when (_volumeControl.value) {
            VolumeControl.Ours -> host.showVolume((reported / 100f).coerceIn(0f, 1f))
            VolumeControl.NotOurs -> Unit
            VolumeControl.Unknown -> {
                val asked = plexVolumeAsked
                val followed = asked != null && kotlin.math.abs(reported - asked) <= PLEX_VOLUME_SLACK
                if (reported > 0 || followed) {
                    _volumeControl.value = VolumeControl.Ours
                    plexVolumeAsked = null
                    host.showVolume((reported / 100f).coerceIn(0f, 1f))
                    android.util.Log.i("AmpPlex", "volume is ours (reported=$reported)")
                } else if (asked != null && ++plexVolumeProbes >= PLEX_VOLUME_PROBES) {
                    plexVolumeNotOurs("asked for $asked, player stayed at $reported")
                }
            }
        }
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
        val target = steered() ?: return
        val client = plexClient() ?: return
        val queue = plexQueue ?: return
        host.scope.launch {
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
            plexPushedIds = host.queue.value.map { it.id }
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
    private suspend fun repushPlexQueue(positionMs: Long = host.positionMs.value) {
        val target = steered() ?: return
        val client = plexClient() ?: return
        val tracks = host.queue.value
        if (tracks.isEmpty()) return
        val index = host.index.value.coerceIn(0, tracks.lastIndex)
        // A window around where playback is, not the whole queue — see
        // [plexWindowEnd]. Enough history behind it that Previous still works
        // on the player's own remote.
        val from = (index - PLEX_WINDOW_BEHIND).coerceAtLeast(0)
        val to = (from + PLEX_WINDOW).coerceAtMost(tracks.size)
        val window = tracks.subList(from, to)
        val queue = client.createPlayQueue(
            window.map { it.id },
            tracks[index].id,
            repeatAll = host.repeatMode.value == RepeatMode.QUEUE,
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
        val port = plexListener.start(host.scope)
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
        val tracks = host.queue.value
        if (end >= tracks.lastIndex) {
            plexWindowEnd = -1
            return
        }
        if (host.index.value < end - PLEX_WINDOW_REFILL) return
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
        _volumeControl.value = VolumeControl.NotOurs
        plexVolumeAsked = null
        // Full, not the phone's last level: what leaves here is a digital
        // stream at unity gain, and the far end does the attenuating. A bar
        // showing anything less would be describing a knob nobody is turning.
        host.showVolume(1f)
        android.util.Log.i("AmpPlex", "volume isn't ours — $why; fader disabled at unity")
    }

    /** Stop the pushes and close the door behind them. */
    private fun endPlexSubscription(client: PlexClient?, target: PlexPlayer?) {
        val wasSubscribed = plexSubscribed
        plexSubscribed = false
        plexListener.stop()
        if (wasSubscribed && client != null && target != null) {
            host.scope.launch { client.companionUnsubscribe(target, ++plexCommandId) }
        }
    }

    /** The player stopped from its own side: the phone keeps its place, paused. */
    private fun plexCastEnded() {
        val target = host.steering(this) ?: return
        android.util.Log.i("AmpPlex", "player gone — keeping the place, paused")
        endPlexSubscription(plexClient(), (target as PlexCompanionTarget).player)
        plexQueue = null
        host.endedOnItsOwn(this, target)
    }

    private companion object {
        /** How often a player that isn't pushing is asked where it is. */
        const val PLEX_POLL_MS = 1_000L

        /** Consecutive silent/stopped polls before a Companion player counts as gone. */
        const val PLEX_GONE_POLLS = 5

        /**
         * How much of a long queue a player is given at once, and how much of
         * it sits behind the playing track. Small enough that the queue's uri
         * stays a URL rather than a payload — Plex reads it from the request
         * line — and long enough that a top-up is rare.
         */
        const val PLEX_WINDOW = 150
        const val PLEX_WINDOW_BEHIND = 20

        /** Long enough for a player to act on a skip before it is judged. */
        const val PLEX_SKIP_VERIFY_MS = 2_500L

        /** How often a subscribed cast checks that pushes are still coming. */
        const val PLEX_SUBSCRIBED_CHECK_MS = 3_000L

        /** Silence from a subscribed player before falling back to polling. */
        const val PLEX_PUSH_SILENCE_MS = 12_000L

        /** Poll interval while the player is paused — its state isn't moving. */
        const val PLEX_IDLE_POLL_MS = 5_000L

        /** Tracks left ahead of the player before the window is topped up. */
        const val PLEX_WINDOW_REFILL = 30

        /** How many more go over in a top-up. */
        const val PLEX_WINDOW_CHUNK = 80

        /** How near the asked-for level counts as the player having taken it. */
        const val PLEX_VOLUME_SLACK = 5

        /** Polls a player gets to follow a volume before the fader gives up on it. */
        const val PLEX_VOLUME_PROBES = 3
    }
}

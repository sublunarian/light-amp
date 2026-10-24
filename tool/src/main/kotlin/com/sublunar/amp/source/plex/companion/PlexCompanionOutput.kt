package com.sublunar.amp.source.plex.companion

import android.util.Log
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
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Playing to a Plex player (the Apple TV app, another Plexamp) over Plex's
 * Companion protocol.
 *
 * Unlike DLNA, the player is handed a server-side play queue and walks it
 * itself, gapless and skippable from its own remote. So this side mirrors
 * rather than drives: it adopts whatever the player reports, track changes
 * and pauses made on the TV included. Commands go to the player's own
 * Companion port; the queue it walks is the server's.
 *
 * The calls are in PlexCompanionCalls.kt, on the Plex client for its token
 * and its lock.
 */
internal class PlexCompanionOutput(private val host: OutputHost) : Output {
    override val family = PlexCompanionFamily

    override val logTag = "AmpPlex"

    // The player reports its own plays to the server: the phone adding its own would double them.
    override val reportsItself = true

    override val needsLan = false

    /**
     * Whether the fader moves the player's volume, judged by evidence rather
     * than the player's word: an Apple TV feeding a receiver over optical says
     * `volume="0"` for ever, takes the command, and changes nothing. Every
     * player starts [VolumeControl.Unknown], where a drag goes out as a probe
     * and what the player reports next decides — see [judgeVolume].
     */
    private val _volumeControl = MutableStateFlow(VolumeControl.Unknown)
    override val volumeControl: StateFlow<VolumeControl> = _volumeControl

    /** The level last asked for (0–100) while proving control, if any. */
    private var volumeAsked: Int? = null
    private var volumeProbes = 0

    private var job: Job? = null
    private var playQueue: PlexQueue? = null
    private var pushedIds: List<String> = emptyList()

    /** Companion wants a fresh command id per request, per controller. */
    private var commandId = 0

    /**
     * How far into the queue the player has been given, or -1 when it holds
     * the lot. Plex reads a play queue's `uri`, which names every track, from
     * the request line: a 500-track queue is 5 KB of URL, which the server
     * refuses. So a long queue goes over in a window, topped up in place as the
     * player nears its end.
     */
    private var windowEnd = -1

    /** Whether the player is pushing its timeline rather than being asked. */
    @Volatile
    private var subscribed = false

    /** When the last push arrived, so silence can be noticed. */
    @Volatile
    private var lastPush = 0L

    /** The little HTTP server a subscription pushes to. Built once: a port per cast would leak. */
    private val listener = PlexCompanionListener { body ->
        lastPush = System.currentTimeMillis()
        plexTimelineFrom(body)?.let { t ->
            if (t.state == "stopped") {
                // The one push acted on rather than mirrored.
                host.scope.launch(Dispatchers.Main.immediate) { castEnded() }
            } else {
                host.scope.launch(Dispatchers.Main.immediate) { adoptTimeline(t) }
            }
        }
    }

    private fun log(message: String) {
        Log.i(logTag, message)
    }

    /** Read per call, never kept: a client rebuilt for the same source — a renewed token — is the one that answers. */
    private fun plexClient(): PlexClient? = host.client() as? PlexClient

    /** The player this is steering, or null once it has been let go. */
    private fun steered(): PlexPlayer? = (host.steering(this) as? PlexCompanionTarget)?.player

    override fun canSteer() = plexClient() != null

    /**
     * Companion players for the active Plex source. Three answers, because none
     * is complete: the server knows the players it discovered, plex.tv knows
     * the account's devices but forgets sleeping ones, and [PlexGdm] asks this
     * network directly.
     */
    override suspend fun find(): List<OutputTarget> {
        val client = plexClient() ?: return emptyList()
        // Off Wi-Fi in Wi-Fi Only, the remote lists would only name players on
        // a network this phone isn't on.
        val listed = if (host.mayTalkToServer()) client.companionPlayers() else emptyList()
        // Off Wi-Fi there is no LAN to broadcast to, only GDM's timeout to wait out.
        val onNetwork = if (Connectivity.isOnWifi()) PlexGdm.findPlayers() else emptyList()
        val seen = listed.map { it.id }.toMutableSet()
        val extra = onNetwork.filter { seen.add(it.id) }
        log("gdm found ${onNetwork.map { it.name }}, new here ${extra.map { it.name }}")
        return (listed + extra).map(::PlexCompanionTarget)
    }

    /**
     * Push the queue to the server, point the player at it from [fromMs], and
     * start mirroring its reports. A play queue handed to a Plex player starts
     * it, whatever [play] says.
     */
    override fun connect(target: OutputTarget, fromMs: Long, play: Boolean) {
        val player = (target as PlexCompanionTarget).player
        val client = plexClient() ?: run {
            host.dropped(this, target)
            return
        }
        _volumeControl.value = VolumeControl.Unknown
        volumeAsked = null
        volumeProbes = 0
        windowEnd = -1
        job?.cancel()
        job = host.scope.launch {
            if (host.queue.value.isEmpty()) {
                host.dropped(this@PlexCompanionOutput, target)
                return@launch
            }
            // A device named only by plex.tv is named by an address on file, and
            // an address is not an identity: a moved lease would hand this queue
            // to whatever answers there now. Ask it who it is first.
            if (!player.identityKnown && !client.playerIsWhoWeThink(player)) {
                log("${player.name} isn't who plex.tv said — not casting")
                host.dropped(this@PlexCompanionOutput, target)
                return@launch
            }
            repushQueue(positionMs = fromMs)
            if (playQueue == null) {
                log("play queue refused — staying on this device")
                host.dropped(this@PlexCompanionOutput, target)
                return@launch
            }
            if (host.repeatMode.value == RepeatMode.TRACK) {
                client.companionSetParameters(player, listOf("repeat" to "1"), ++commandId)
            }
            subscribe(client, player)
            pollPlayer(client, player)
        }
    }

    /** Stop steering the player, for [why]: told to stop, or left playing when the app is left. */
    override fun disconnect(target: OutputTarget, why: Disconnect) {
        val player = (target as PlexCompanionTarget).player
        if (why == Disconnect.Leaving) {
            // Left playing, as every Plex remote leaves it: the player walks its
            // own queue. The subscription still ends, or the player would push
            // to a port that is about to close.
            log("leaving the player playing")
            endSubscription(plexClient(), player)
            job?.cancel()
            job = null
            playQueue = null
            return
        }
        val client = plexClient()
        job?.cancel()
        job = null
        playQueue = null
        windowEnd = -1
        // A player whose pushes keep failing doesn't stay well (see playerLock
        // in PlexClient), and its own "stopped" push can't clean up: castEnded
        // returns once the controller has let go, as it has.
        endSubscription(client, player)
        host.scope.launch { client?.companionCommand(player, "stop", ++commandId) }
    }

    // Never asked of a source's own output, which goes with its source — see
    // PlaybackController.stop. Stopping the player is what it would mean.
    override fun stopPlayback() {
        val target = steered() ?: return
        playQueue = null
        host.scope.launch { plexClient()?.companionCommand(target, "stop", ++commandId) }
    }

    override fun play() = command("play")

    override fun pause() = command("pause")

    override fun seekTo(ms: Long) {
        val target = steered() ?: return
        host.showPosition(ms)
        host.scope.launch { plexClient()?.companionSeek(target, ms, ++commandId) }
    }

    override fun next() = command("skipNext")

    override fun previous() {
        val target = steered() ?: return
        host.scope.launch {
            if (host.positionMs.value > PREVIOUS_RESTARTS_AFTER_MS) {
                plexClient()?.companionSeek(target, 0, ++commandId)
            } else {
                plexClient()?.companionCommand(target, "skipPrevious", ++commandId)
            }
        }
    }

    /** Send the steered player the Companion command [name]. */
    private fun command(name: String) {
        val target = steered() ?: return
        host.scope.launch { plexClient()?.companionCommand(target, name, ++commandId) }
    }

    override fun jumpTo(index: Int) {
        val target = steered() ?: return
        val track = host.queue.value[index]
        host.showIndex(index)
        host.showPosition(0)
        host.scope.launch {
            if (pushedIds.contains(track.id)) {
                plexClient()?.companionSkipTo(target, track.id, ++commandId)
                // A player may keep less of the queue than it was handed, and a
                // skipTo outside its own window is accepted and silently ignored.
                // So check where it went, and rebuild around the track if not
                // there, as verifyNativeSeek does for the phone.
                delay(SKIP_VERIFY_MS)
                if (steered()?.id == target.id &&
                    host.queue.value.getOrNull(host.index.value)?.id != track.id
                ) {
                    log("skipTo idx=$index didn't land — rebuilding around it")
                    host.showIndex(index)
                    host.showPosition(0)
                    repushQueue(positionMs = 0L)
                }
            } else {
                // Outside the window the player was given: nothing to skip to.
                log("jump to idx=$index is outside the window — rebuilding")
                repushQueue(positionMs = 0L)
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
            plexClient()?.companionSetParameters(target, listOf("repeat" to plexRepeat), ++commandId)
        }
    }

    override fun setVolume(level: Float) {
        val target = steered() ?: return
        // Until judgeVolume settles it, a drag is the probe. The phone's own
        // level is left alone either way while the sound is elsewhere.
        if (_volumeControl.value == VolumeControl.NotOurs) return
        val asked = (level * 100).roundToInt()
        volumeAsked = asked
        host.showVolume(level)
        host.scope.launch {
            plexClient()?.companionSetParameters(
                target,
                listOf("volume" to asked.toString()),
                ++commandId,
            )
        }
    }

    override fun newQueue() {
        host.showPosition(0L)
        host.scope.launch { repushQueue(positionMs = 0L) }
    }

    override fun queueEdited(edit: QueueEdit) {
        when (edit) {
            is QueueEdit.Appended ->
                editPlayQueue { client, queue -> client.addToPlayQueue(queue, edit.ids, next = false) }
            is QueueEdit.PlayNext ->
                editPlayQueue { client, queue -> client.addToPlayQueue(queue, edit.ids, next = true) }
            // As edits, so the song already playing carries on untouched.
            is QueueEdit.UpcomingReplaced -> editPlayQueue { client, queue ->
                val emptied = client.removeAll(queue, edit.dropped)
                if (edit.added.isEmpty()) emptied
                else emptied?.let { client.addToPlayQueue(it, edit.added, next = false) }
            }
            is QueueEdit.Removed -> editPlayQueue { client, queue -> client.removeAll(queue, edit.ids) }
            // Plex places an item *after* another, so the block's first track
            // follows afterId and each one after follows the one before it, one
            // call at a time, each answer carrying the places for the next.
            is QueueEdit.Moved -> editPlayQueue { client, queue ->
                edit.ids.foldIndexed<String, PlexQueue?>(queue) { i, acc, id ->
                    acc?.let { client.movePlayQueueItem(it, id, if (i == 0) edit.afterId else edit.ids[i - 1]) }
                }
            }
            // A shuffled hundred would be a hundred moves: a fresh queue, and a blip, instead.
            QueueEdit.Reordered -> host.scope.launch { repushQueue() }
        }
    }

    /** Remove [ids] one at a time, each answer carrying the places for the next. */
    private suspend fun PlexClient.removeAll(queue: PlexQueue, ids: List<String>): PlexQueue? =
        ids.fold<String, PlexQueue?>(queue) { acc, id -> acc?.let { removePlayQueueItem(it, id) } }

    /**
     * Mirror the player's timeline until it stops being the one steered.
     * Subscribes first, and polls only while the player isn't pushing; a
     * paused player is asked less often.
     */
    private suspend fun pollPlayer(client: PlexClient, target: PlexPlayer) {
        var misses = 0
        while (coroutineContext.isActive && steered()?.id == target.id) {
            // While the player pushes, the loop is only the heartbeat that notices it stop.
            if (subscribed) {
                delay(SUBSCRIBED_CHECK_MS)
                if (System.currentTimeMillis() - lastPush > PUSH_SILENCE_MS) {
                    log("no pushes for a while — polling again")
                    subscribed = false
                }
                continue
            }
            val t = client.companionTimeline(target, ++commandId)
            if (t == null || t.state == "stopped") {
                // Silent or stopped for a few polls: ended from the player's own
                // side. The phone keeps its place, paused.
                if (++misses >= GONE_POLLS) {
                    castEnded()
                    return
                }
            } else {
                misses = 0
                adoptTimeline(t)
                topUpWindow(client, target)
            }
            delay(if (host.isPlaying.value) POLL_MS else IDLE_POLL_MS)
        }
    }

    /**
     * Take the player's word for what is happening, from a poll or a push. The
     * player's state is the truth and Amp's the copy: nothing here is sent
     * back, or a mode read from the player would be set on it again, a loop.
     */
    private fun adoptTimeline(t: PlexPlayerTimeline) {
        // Paused is the player's own say. Buffering is a start still going,
        // and playing is its end.
        if (t.state == "paused") host.showPaused() else host.showPlaying(t.state == "playing")
        if (t.state == "playing") host.showSounding()
        host.showPosition(t.timeMs)
        if (t.durationMs > 0) host.showDuration(t.durationMs)
        judgeVolume(t.volume, t.controllable)
        // Repeat can be changed on the player's own remote.
        t.repeat?.let { reported ->
            val mode = when (reported) {
                1 -> RepeatMode.TRACK
                2 -> RepeatMode.QUEUE
                else -> RepeatMode.OFF
            }
            if (mode != host.repeatMode.value) {
                log("player's repeat is $mode")
                host.showRepeat(mode)
            }
        }
        t.shuffle?.let { on -> if (on != host.shuffle.value) host.showShuffle(on) }
        val rk = t.ratingKey
        if (rk != null && host.queue.value.getOrNull(host.index.value)?.id != rk) {
            // The player moved on its own (its remote, or a track ended): follow it.
            val idx = host.queue.value.indexOfFirst { it.id == rk }
            if (idx >= 0) {
                host.showIndex(idx)
                log("player moved to idx=$idx")
            }
        }
    }

    /**
     * Decide whether the fader owns this player's volume. A timeline that lists
     * what the player accepts without `volume` says so plainly. Otherwise
     * evidence decides: a level that moves on its own or follows where it was
     * put means the fader is real, and one that ignores a few asks means it isn't.
     */
    private fun judgeVolume(reported: Int?, controllable: String?) {
        if (_volumeControl.value != VolumeControl.NotOurs &&
            controllable != null &&
            !controllable.split(',').any { it.trim().equals("volume", ignoreCase = true) }
        ) {
            volumeNotOurs("player's timeline doesn't list volume")
            return
        }
        if (reported == null) return
        when (_volumeControl.value) {
            VolumeControl.Ours -> host.showVolume((reported / 100f).coerceIn(0f, 1f))
            VolumeControl.NotOurs -> Unit
            VolumeControl.Unknown -> {
                val asked = volumeAsked
                val followed = asked != null && abs(reported - asked) <= VOLUME_SLACK
                if (reported > 0 || followed) {
                    _volumeControl.value = VolumeControl.Ours
                    volumeAsked = null
                    host.showVolume((reported / 100f).coerceIn(0f, 1f))
                    log("volume is ours (reported=$reported)")
                } else if (asked != null && ++volumeProbes >= VOLUME_PROBES) {
                    volumeNotOurs("asked for $asked, player stayed at $reported")
                }
            }
        }
    }

    /**
     * Apply a queue edit to the live play queue on the server, then tell the
     * player to re-read it: what comes next changes and what is playing is left
     * alone, where a whole new queue restarts the current track.
     */
    private fun editPlayQueue(edit: suspend (PlexClient, PlexQueue) -> PlexQueue?) {
        val target = steered() ?: return
        val client = plexClient() ?: return
        val queue = playQueue ?: return
        host.scope.launch {
            val edited = edit(client, queue)
            if (edited == null) {
                log("queue edit refused — falling back to a re-push")
                repushQueue()
                return@launch
            }
            // An edit's answer carries only part of the queue, and a place not
            // known can't be edited next time: re-read the pushed window.
            val updated = client.readPlayQueue(edited, WINDOW) ?: edited
            playQueue = updated
            pushedIds = host.queue.value.map { it.id }
            client.companionRefreshQueue(target, updated, ++commandId)
        }
    }

    /**
     * Hand the player a whole new queue, cued at [positionMs] of wherever the
     * index now points: how a new queue arrives, and the last resort for an
     * edit too broad, or refused. It restarts the playing track.
     */
    private suspend fun repushQueue(positionMs: Long = host.positionMs.value) {
        val target = steered() ?: return
        val client = plexClient() ?: return
        val tracks = host.queue.value
        if (tracks.isEmpty()) return
        val index = host.index.value.coerceIn(0, tracks.lastIndex)
        // A window around the index — see [windowEnd] — with enough behind it
        // for Previous on the player's own remote.
        val from = (index - WINDOW_BEHIND).coerceAtLeast(0)
        val to = (from + WINDOW).coerceAtMost(tracks.size)
        val window = tracks.subList(from, to)
        val queue = client.createPlayQueue(
            window.map { it.id },
            tracks[index].id,
            repeatAll = host.repeatMode.value == RepeatMode.QUEUE,
        ) ?: return
        playQueue = queue
        pushedIds = window.map { it.id }
        windowEnd = if (to >= tracks.size) -1 else to - 1
        client.companionPlay(target, queue, tracks[index].id, positionMs, ++commandId, WINDOW)
        log(
            "pushed ${window.size} of ${tracks.size} at idx=$index from ${positionMs}ms" +
                if (windowEnd >= 0) ", window ends at $windowEnd" else "",
        )
    }

    /**
     * Ask the player to push its state here. Best effort: a player may refuse,
     * and a network may not route back to this phone (a guest VLAN, client
     * isolation). Neither fails a cast: the poll carries on instead.
     */
    private suspend fun subscribe(client: PlexClient, target: PlexPlayer) {
        val port = listener.start(host.scope)
        if (port == null) {
            log("no port to be pushed to — polling")
            return
        }
        lastPush = System.currentTimeMillis()
        subscribed = client.companionSubscribe(target, port, ++commandId)
        log(
            if (subscribed) "subscribed; ${target.name} pushes to ${listener.localAddress()}:$port"
            else "subscribe declined — polling",
        )
    }

    /**
     * Hand the player the next stretch of a long queue as it nears the end of
     * what it has. An append edits the queue it holds, so nothing restarts.
     */
    private suspend fun topUpWindow(client: PlexClient, target: PlexPlayer) {
        val end = windowEnd
        if (end < 0) return
        val tracks = host.queue.value
        if (end >= tracks.lastIndex) {
            windowEnd = -1
            return
        }
        if (host.index.value < end - WINDOW_REFILL) return
        val queue = playQueue ?: return
        val to = (end + 1 + WINDOW_CHUNK).coerceAtMost(tracks.size)
        val more = tracks.subList(end + 1, to)
        val updated = client.addToPlayQueue(queue, more.map { it.id }, next = false) ?: return
        playQueue = updated
        pushedIds = pushedIds + more.map { it.id }
        windowEnd = if (to >= tracks.size) -1 else to - 1
        client.companionRefreshQueue(target, updated, ++commandId)
        log("topped up ${more.size} more; window now ends at $windowEnd")
    }

    private fun volumeNotOurs(why: String) {
        _volumeControl.value = VolumeControl.NotOurs
        volumeAsked = null
        // Full, not the phone's last level: what leaves here is a digital stream
        // at unity gain, and the far end does the attenuating.
        host.showVolume(1f)
        log("volume isn't ours — $why; fader disabled at unity")
    }

    /** Stop the pushes and close the door behind them. */
    private fun endSubscription(client: PlexClient?, target: PlexPlayer?) {
        val wasSubscribed = subscribed
        subscribed = false
        listener.stop()
        if (wasSubscribed && client != null && target != null) {
            host.scope.launch { client.companionUnsubscribe(target, ++commandId) }
        }
    }

    /** The player stopped from its own side: the phone keeps its place, paused. */
    private fun castEnded() {
        val target = host.steering(this) ?: return
        log("player gone — keeping the place, paused")
        endSubscription(plexClient(), (target as PlexCompanionTarget).player)
        playQueue = null
        host.endedOnItsOwn(this, target)
    }

    private companion object {
        /** How often a player that isn't pushing is asked where it is. */
        const val POLL_MS = 1_000L

        /** Poll interval while the player is paused: its state isn't moving. */
        const val IDLE_POLL_MS = 5_000L

        /** Consecutive silent/stopped polls before a Companion player counts as gone. */
        const val GONE_POLLS = 5

        /**
         * How much of a long queue a player is given at once, and how much of
         * it sits behind the playing track: small enough that the queue's uri
         * stays a URL — see [windowEnd] — and long enough that a top-up is rare.
         */
        const val WINDOW = 150
        const val WINDOW_BEHIND = 20

        /** Tracks left ahead of the player before the window is topped up. */
        const val WINDOW_REFILL = 30

        /** How many more go over in a top-up. */
        const val WINDOW_CHUNK = 80

        /** Long enough for a player to act on a skip before it is judged. */
        const val SKIP_VERIFY_MS = 2_500L

        /** How often a subscribed cast checks that pushes are still coming. */
        const val SUBSCRIBED_CHECK_MS = 3_000L

        /** Silence from a subscribed player before falling back to polling. */
        const val PUSH_SILENCE_MS = 12_000L

        /** How near the asked-for level counts as the player having taken it. */
        const val VOLUME_SLACK = 5

        /** Polls a player gets to follow a volume before the fader gives up on it. */
        const val VOLUME_PROBES = 3
    }
}

package com.sublunar.amp.playback.output

import com.sublunar.amp.data.LinkRules
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * What every control is routed to while the sound is somewhere other than
 * this phone: a speaker on the network, or a Plex player across the room.
 *
 * The phone's own player is not one of these. It is the controller's default
 * path, and a standby kept up to date even while something else plays — every
 * queue edit reaches it — so "no output" means this phone, and each control
 * asks the output first and falls through to its own code when there is none.
 *
 * Called on the caller's thread, which is Main except where a member says
 * otherwise. The controller's state is still the one the screens read: it
 * writes what it can say for itself before calling — a press of play is shown
 * as playing at once — and the output keeps the rest true as the far end
 * reports.
 */
interface OutputControls {
    /** The log tag its lines go under. A constant, never worked out from a class name. */
    val logTag: String

    /**
     * The far end tells the server about its own playback, as a session of
     * its own: the phone then sends no timeline and no now-playing, which
     * would put a second listener on the server's dashboard.
     */
    val reportsItself: Boolean

    /** Reached over the local network only: dropped when the link stops allowing that. */
    val needsLan: Boolean

    /** Whether handing back to the phone starts its sound, given whether the far end was playing. */
    fun handBackPlays(wasPlaying: Boolean): Boolean

    /** Play. Playing is already shown, and reported where the phone reports. */
    fun play()

    /** Pause. Paused is already shown, and reported where the phone reports. */
    fun pause()

    /** Seek, showing the position it lands on. */
    fun seekTo(ms: Long)

    fun next()

    /** Back to the start of the track, or to the one before, as the phone's own previous does. */
    fun previous()

    /** Play the queue's track at [index], which is already known to be in the queue. */
    fun jumpTo(index: Int)

    /** The repeat mode changed, and is already shown. */
    fun repeatChanged(mode: RepeatMode) {}

    /** Move the volume to [level], 0 to 1. What the fader shows is the output's to decide. */
    fun setVolume(level: Float)

    /**
     * The phone's own level moved — the rocker, or the system's slider — and
     * the fader already shows it. From the handle's collector, off Main.
     */
    suspend fun followDeviceVolume(level: Float) {}

    /** A new queue was set and the phone's player paused on it: play it from its start. */
    fun newQueue()

    /** The queue was edited in place, as [edit] says. Already true of the queue shown. */
    fun queueEdited(edit: QueueEdit) {}

    /**
     * What plays next may have changed: the queue, the index or the repeat
     * mode moved, and has stopped moving for a moment. From a collector, off Main.
     */
    suspend fun upcomingChanged() {}
}

/**
 * One family's way of playing somewhere else, steering one target at a time.
 * Made once per family for the life of the process, by [OutputFamily.create].
 */
interface Output : OutputControls {
    val family: OutputFamily

    /** Whether the fader moves the far end's volume — see [VolumeControl]. */
    val volumeControl: StateFlow<VolumeControl>

    /** What can be played to right now. Does its own waiting off Main. */
    suspend fun find(): List<OutputTarget>

    /** Whether there is anything to steer with right now — a client of the right kind, say. */
    fun canSteer(): Boolean = true

    /**
     * Start playing at [target], from [fromMs] into the queue's current
     * track. On Main, with the phone already paused and this already the
     * output that has the sound.
     */
    fun connect(target: OutputTarget, fromMs: Long)

    /**
     * Stop steering [target], for [why]. Any thread. The controller has
     * already let go of it, so nothing the output is still running reads it
     * as steering on the way out.
     */
    fun disconnect(target: OutputTarget, why: Disconnect)
}

/**
 * A kind of output: speakers on the network, Plex players. Listed by whoever
 * offers it — the phone's own in [Outputs], a kind of source's by its
 * provider.
 */
interface OutputFamily {
    /** Keys its rows and names it in logs. A constant: never stored, never worked out from a class name. */
    val id: String

    /** What the fader says in place of a level once the volume is [VolumeControl.NotOurs]. */
    val volumeElsewhere: String get() = ""

    fun create(host: OutputHost): Output

    /** Hold the family to the link's rules, at boot, before anything can reach the network. */
    fun install(rules: () -> LinkRules) {}
}

/** Somewhere a family can play. [id] stays the same from one search to the next. */
interface OutputTarget {
    val id: String
    val name: String
}

/**
 * The output that has the sound, and the target it was pointed at.
 *
 * Compared by identity, deliberately not a data class: letting go is a
 * compare-and-set on the very instance that was current, so an older cast
 * ending can never clear a newer one to the same place.
 */
class ActiveOutput internal constructor(
    val family: OutputFamily,
    val target: OutputTarget,
    internal val output: Output,
) {
    val volumeControl: StateFlow<VolumeControl> get() = output.volumeControl
}

/** Whether the fader can move the far end's volume. */
enum class VolumeControl {
    /** Not settled yet: a drag goes out as a probe, and what the far end reports next decides. */
    Unknown,

    /** The fader is the far end's volume. */
    Ours,

    /** The far end's volume is owned by something after it — a receiver — so the fader is disabled rather than left to lie. */
    NotOurs,
}

/**
 * What an output is given of the controller: the state the screens read,
 * which the output keeps true while it has the sound, and the few services
 * the core keeps for every kind of output.
 *
 * The flows stay the controller's. An output writes them through the show
 * calls rather than keeping a copy of its own for the screens to follow:
 * what a cast does in one breath — move the index, then announce the song at
 * it — has to be read in that order, and a copy would be a hop behind.
 */
interface OutputHost {
    /** The process's scope. An output's jobs live here, never with a handle: a cast outlives the activity. */
    val scope: CoroutineScope

    val queue: StateFlow<List<Track>>
    val index: StateFlow<Int>
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val repeatMode: StateFlow<RepeatMode>
    val shuffle: StateFlow<Boolean>

    /** The target [output] steers, or null once it has been let go: what every loop and late check asks. */
    fun steering(output: Output): OutputTarget?

    // What the far end says it is doing. From any thread.
    fun showPlaying(playing: Boolean)
    fun showPosition(ms: Long)
    fun showDuration(ms: Long)
    fun showIndex(index: Int)
    fun showRepeat(mode: RepeatMode)
    fun showShuffle(on: Boolean)
    fun showVolume(level: Float)

    /** The active source's client, read fresh each time: one rebuilt for the same source is the one that answers. */
    fun client(): MusicServer?

    /** The link's rules as of now, read on the spot rather than from the flow. */
    fun rules(): LinkRules

    /** Whether the server may be spoken to at all — the data mode's courtesy rule. */
    fun mayTalkToServer(): Boolean

    /** The format this link asks the server for. */
    fun streamFormat(): StreamFormat

    /**
     * The server's own address for [track] in [format], or null with no
     * server. Never the loopback door's: the far end fetches for itself, and
     * the door is on this phone, where a speaker can't reach it.
     */
    fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = true,
        sessionId: String? = null,
    ): String?

    /** The session id for a stream the far end fetches itself: one no timeline names. */
    fun ownStreamSession(trackId: String): String

    /** The song at the index has started: the server hears so, as it does for the phone's own player. */
    fun announceNowPlaying()

    /** The far end ran out of queue: the listening is over. */
    fun listeningEnded()

    /** Move the phone's own media volume. Main only: it is the player's call. */
    fun setPhoneVolume(level: Float)

    /**
     * [output] gave up on [target] before anything played there: let go of it,
     * and nothing else. The phone sits paused where the cast began. Only if
     * [target] is still the one being steered — a newer cast is left alone.
     */
    fun dropped(output: Output, target: OutputTarget)

    /**
     * The far end stopped on its own — stop pressed on the TV, its app quit:
     * let go of [target], show paused, give the fader back to the phone and
     * cue the queue on the phone where the far end left it, paused. Any thread.
     */
    fun endedOnItsOwn(output: Output, target: OutputTarget)
}

/** How long a track has to have played before previous restarts it rather than going back — on every output. */
const val PREVIOUS_RESTARTS_AFTER_MS = 3_000L

/** One edit of the queue, by track id, for an output that holds a queue of its own. */
sealed interface QueueEdit {
    /** Added at the end. */
    data class Appended(val ids: List<String>) : QueueEdit

    /** Put straight after the playing track. */
    data class PlayNext(val id: String) : QueueEdit

    /** Everything after the playing track replaced: [dropped] went, [added] followed it. */
    data class UpcomingReplaced(val dropped: List<String>, val added: List<String>) : QueueEdit

    data class Removed(val ids: List<String>) : QueueEdit

    /** [ids] moved as a block, in order, to follow [afterId] — the front of the queue when null. */
    data class Moved(val ids: List<String>, val afterId: String?) : QueueEdit

    /** Everything upcoming reordered at once — shuffle — which is too broad to say as moves. */
    data object Reordered : QueueEdit
}

/** Why the sound stops going to an output. */
enum class Disconnect {
    /** Back to this phone: This device, or the selected row tapped again. */
    HandBack,

    /** Another output takes over. */
    Switch,

    /** The link stopped allowing the local network, so nothing can be said to it. */
    LinkLost,

    /** Playback stopped and the queue emptied — a source switch. */
    Stopped,

    /** The app is being left. */
    Leaving,
}

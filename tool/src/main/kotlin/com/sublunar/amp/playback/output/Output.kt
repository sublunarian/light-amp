package com.sublunar.amp.playback.output

import com.sublunar.amp.data.KnownStream
import com.sublunar.amp.data.LinkRules
import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.Track
import com.sublunar.amp.playback.wrappedIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * One family's way of playing somewhere else — a speaker on the network, a
 * Plex player across the room — steering one target at a time. Made once per
 * family for the life of the process, by [OutputFamily.create]. Every control
 * goes here while the sound is elsewhere.
 *
 * The phone's own player is not an output. It is the controller's default,
 * kept up to date as a standby while something else plays, so "no output"
 * means this phone and each control falls through to the controller's code.
 *
 * Called on Main unless a member says otherwise. The controller shows what it
 * can say for itself before calling (a press of play shows playing at once);
 * the output keeps the rest true as the far end reports.
 */
interface Output {
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

    /** Whether the fader moves the far end's volume — see [VolumeControl]. */
    val volumeControl: StateFlow<VolumeControl>

    /** What can be played to right now. Does its own waiting off Main. */
    suspend fun find(): List<OutputTarget>

    /** Whether there is anything to steer with right now — a client of the right kind, say. */
    fun canSteer(): Boolean = true

    /**
     * Take the sound to [target], from [fromMs] into the queue's current
     * track: playing there if [play] (the phone was playing, or starting),
     * otherwise chosen and silent until a play. On Main, with the phone
     * already paused and this already the output that has the sound.
     */
    fun connect(target: OutputTarget, fromMs: Long, play: Boolean)

    /**
     * Stop steering [target], for [why]. Any thread. The controller has
     * already let go of it, so nothing the output is still running reads it
     * as steering on the way out.
     */
    fun disconnect(target: OutputTarget, why: Disconnect)

    /**
     * Playback stopped and the queue emptied — a source switch — while this
     * stays the output with the sound: stop the far end and keep nothing of
     * the old queue. The next queue arrives as [newQueue]. Any thread. Asked
     * only of an output the phone offers: a source's own goes with its source.
     */
    fun stopPlayback()

    /**
     * The link is back after [Disconnect.LinkLost] took [target] away: find
     * it again, stop whatever of this queue's it is still playing (as an
     * iPhone does with a speaker it finds again) and say where it had got
     * to. Off Main, and bounded by the caller. Null when it isn't to be found,
     * or is busy with something that isn't this queue's.
     */
    suspend fun findAgain(target: OutputTarget): Refound? = null

    /**
     * Take back what [findAgain] found: stopped, it waits for a play, which
     * sends the song afresh from where it had got to. On Main, already the
     * output that has the sound again.
     */
    fun rejoin(found: Refound) {}
}

/**
 * What an output found of a target it lost with the link — see
 * [Output.findAgain]. [index] is the queue position it had reached, or -1
 * when it held nothing of this queue's; [positionMs] is where in that track.
 */
class Refound(
    val target: OutputTarget,
    val index: Int = -1,
    val positionMs: Long = 0L,
)

/**
 * A kind of output: speakers on the network, Plex players. Listed by whoever
 * offers it — the phone's own in [Outputs], a kind of source's by its
 * provider.
 */
interface OutputFamily {
    /** Keys its rows and names it in logs. A constant: never stored, never worked out from a class name. */
    val id: String

    /** The log tag its lines go under. A constant, never worked out from a class name. */
    val logTag: String

    /**
     * The far end reports its own playback to the server, so the phone sends
     * no timeline and no now-playing: that would be a second listener.
     */
    val reportsItself: Boolean

    /** Reached over the local network only: dropped when the link stops allowing that. */
    val needsLan: Boolean

    /** What the fader says in place of a level once the volume is [VolumeControl.NotOurs]. */
    val volumeElsewhere: String get() = ""

    fun create(host: OutputHost): Output
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
 * which the output keeps true while it has the sound, and a few services.
 *
 * The flows stay the controller's, written through the show calls. A copy of
 * the output's own would be a hop behind: a cast moves the index and then
 * announces the song at it, and those must be read in that order.
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

    /**
     * Paused at the far end (its own remote, say), so nothing is starting any
     * more. Apart from `showPlaying(false)`, which a buffering player also
     * says while it is still starting.
     */
    fun showPaused()

    /**
     * The far end says it is making sound: a start under way has started.
     * Apart from [showPlaying], which a start says before a note has played.
     */
    fun showSounding()
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
     * server. Never the loopback proxy's: the far end fetches for itself and
     * can't reach a door on this phone.
     */
    fun streamUrl(
        track: Track,
        format: StreamFormat,
        timeOffsetSeconds: Int = 0,
        estimateContentLength: Boolean = true,
        sessionId: String? = null,
    ): String?

    /** What the server says the stream for [track] in [format] will be, when it knows without a fetch. */
    fun knownStream(track: Track, format: StreamFormat): KnownStream?

    /** The session id for a stream the far end fetches itself: one no timeline names. */
    fun ownStreamSession(trackId: String): String

    /**
     * [output]'s far end ran out of queue: back to the first track, paused, as
     * the phone's own player does. The output keeps the sound, and plays from
     * there at the next press of play.
     */
    fun queueFinished(output: Output)

    /** Move the phone's own media volume. Main only: it is the player's call. */
    fun setPhoneVolume(level: Float)

    /**
     * [output] gave up on [target] before anything played there: let go of it,
     * and nothing else; the phone is shown paused where the cast began. Only
     * if [target] is still the one being steered: a newer cast is left alone.
     */
    fun dropped(output: Output, target: OutputTarget)

    /**
     * [output] couldn't start what it was asked to play: shown paused, with
     * nothing starting. It stays the output, and a press of play asks again.
     */
    fun couldNotPlay(output: Output)

    /**
     * The far end stopped on its own (stop pressed on the TV, its app quit):
     * let go of [target], give the fader back to the phone and cue the queue
     * on the phone where the far end left it, paused. Any thread.
     */
    fun endedOnItsOwn(output: Output, target: OutputTarget)
}

/**
 * Everywhere the Output page can send the sound: the active source's own
 * families — a Plex server's players — and the phone's.
 */
data class OutputFamilies(val ofSource: List<OutputFamily>, val ofPhone: List<OutputFamily>) {
    /** Asked in this order, each shown as it answers: a server's list is one round trip, the network's a few seconds of listening. */
    val searchOrder: List<OutputFamily> get() = ofSource + ofPhone

    /** Shown in this order: the phone's own first, as they always were. */
    val listOrder: List<OutputFamily> get() = ofPhone + ofSource
}

/** How long a track has to have played before previous restarts it rather than going back — on every output. */
const val PREVIOUS_RESTARTS_AFTER_MS = 3_000L

/**
 * Whether previous starts the song over rather than going back: once it has
 * played a little, and always when there is no row [previousIndex] to go back
 * to (the first song, with nothing to wrap to). The rule on every output.
 */
fun previousRestarts(positionMs: Long, previousIndex: Int, queueSize: Int): Boolean =
    positionMs > PREVIOUS_RESTARTS_AFTER_MS || previousIndex !in 0 until queueSize

/** The row previous goes back to from the playing one: the row before, wrapped under repeat-queue as on the phone. */
internal fun OutputHost.previousRow(): Int =
    wrappedIndex(index.value - 1, queue.value.size, repeatQueue = repeatMode.value == RepeatMode.QUEUE)

/** One edit of the queue, by track id, for an output that holds a queue of its own. */
sealed interface QueueEdit {
    /** Added at the end. */
    data class Appended(val ids: List<String>) : QueueEdit

    /** Put straight after the playing track, in this order. */
    data class PlayNext(val ids: List<String>) : QueueEdit

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

    /**
     * The link stopped allowing the local network, so nothing can be said to
     * it. What the output knew of it is kept, for [Output.findAgain] once the
     * link is back.
     */
    LinkLost,

    /**
     * Playback stopped and the queue emptied — a source switch — and the
     * output goes with it: a source's own, or any when what follows has no
     * server to give it an address from. See PlaybackController.stop.
     */
    Stopped,

    /** The app is being left. */
    Leaving,
}

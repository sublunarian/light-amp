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
     * The speakers playing together, with their levels: empty while one
     * plays alone (the target, at the fader's level). An output that can't
     * group never has one.
     */
    val group: StateFlow<List<GroupMember>> get() = NO_GROUP

    /** Whether [target] (one of this output's) can join what plays now. */
    fun canGroup(target: OutputTarget): Boolean = false

    /** Play on [target] as well, in step with the rest. */
    fun addToGroup(target: OutputTarget) {}

    /** Stop playing on [target]; the others carry on. */
    fun removeFromGroup(target: OutputTarget) {}

    /** One grouped speaker's own level, 0 to 1. */
    fun setMemberVolume(targetId: String, level: Float) {}

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
     * Whether a play after a pause starts afresh — the far end was told to
     * drop what it held, and has to fill up again — rather than carrying on
     * a stream it kept open. The controller shows it as starting.
     */
    val resumesAfresh: Boolean get() = false

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

    /**
     * Plays only what a server can hand it an address for, so a switch to a
     * source with no server (the phone's own files) lets it go.
     */
    val needsServer: Boolean get() = false

    /** What the fader says in place of a level once the volume is [VolumeControl.NotOurs]. */
    val volumeElsewhere: String get() = ""

    /** Whether its speakers can play together as a group — and so whether a cast elsewhere moves over to join one. */
    val groups: Boolean get() = false

    fun create(host: OutputHost): Output
}

/** Somewhere a family can play. [id] stays the same from one search to the next. */
interface OutputTarget {
    val id: String
    val name: String

    /**
     * The device's IPv4 address on the local network, when the family knows
     * it: with [hardwareId], how one speaker that two families both offer is
     * told apart from two speakers — see [OutputFamilies.rows].
     */
    val address: String? get() = null

    /**
     * The device's hardware address, as twelve lower-case hex digits, when
     * the family can read one off it. A family may only suspect it (DLNA
     * reads it off the end of the device's id, where many makers put it):
     * two that agree are one device, two that differ prove nothing.
     */
    val hardwareId: String? get() = null
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

    /** The speakers playing together, empty while one plays alone. */
    val group: StateFlow<List<GroupMember>> get() = output.group
}

/** The same speaker, as two families found it: the same hardware, else the same address. */
fun OutputTarget.isSameDeviceAs(other: OutputTarget): Boolean {
    val a = hardwareId
    val b = other.hardwareId
    return if (a != null && b != null) a.equals(b, ignoreCase = true) else address != null && address == other.address
}

/** A speaker playing in a group, and its own level. */
class GroupMember(val target: OutputTarget, val level: Float)

private val NO_GROUP: StateFlow<List<GroupMember>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())

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

    /**
     * A button pressed at the far end that only [output] hears of — a
     * speaker's own remote — taken as the same press made here, so the
     * screens, the stand-in and the server all follow. From any thread.
     */
    fun pressedAtFarEnd(output: Output, press: FarEndPress)

    /** The active source's client, read fresh each time: one rebuilt for the same source is the one that answers. */
    fun client(): MusicServer?

    /** The link's rules as of now, read on the spot rather than from the flow. */
    fun rules(): LinkRules

    /** Whether the server may be spoken to at all — the data mode's courtesy rule. */
    fun mayTalkToServer(): Boolean

    /** The format this link asks the server for [track] in. */
    fun streamFormat(track: Track): StreamFormat

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

    // For an output that plays the music out of this phone — decoding it
    // here and sending the sound — rather than handing the far end an address.

    /**
     * What the phone's own player would play [track] from right now: its
     * file (a phone file, or a download the link doesn't pass over), or the
     * loopback door's address for the stream, in the format this link asks
     * for and [timeOffsetSeconds] into the track. Null when nothing can be
     * read: no server, or the door shut.
     *
     * The door lets one fetch per track run at a time: a second for the same
     * track ends the first.
     */
    fun phoneSource(track: Track, timeOffsetSeconds: Int = 0): PhoneSource?

    /** Whether [track]'s stream can't be sought within, so a seek asks the server for the stream from there. */
    fun seeksAtServer(track: Track): Boolean

    /**
     * [track]'s cover as a JPEG at most [maxPx] wide, or null: none, artwork
     * hidden, or not to be fetched on this link.
     */
    suspend fun coverJpeg(track: Track, maxPx: Int): ByteArray?

    /** The ReplayGain the phone would play [track] at: a factor of at most 1, and 1 with the setting off. */
    fun gainFor(track: Track): Float

    /** The fader's level, 0 to 1, as it stands. */
    val volume: StateFlow<Float>

    /** A folder for [output]'s working files (a family's id), made if missing. Nothing else writes there. */
    fun workDir(output: String): java.io.File

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

/** Where an output that plays out of the phone reads a track — see [OutputHost.phoneSource]. */
enum class FarEndPress { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS }

sealed interface PhoneSource {
    class File(val file: java.io.File) : PhoneSource

    /** The loopback door's address, the stream starting [offsetMs] into the track. */
    class Url(val url: String, val offsetMs: Long) : PhoneSource
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

    /**
     * The rows the Output page shows: what each family [found], in
     * [listOrder], one row per device.
     *
     * A speaker that two families both offer — a receiver that takes DLNA
     * and AirPlay — is listed once, under the family that comes first in
     * [listOrder] among those that [steerable] says can play the source
     * that's on. A family that can't is left out altogether rather than
     * listed and failing. Whatever has the sound ([active]) is listed
     * whatever was found, and wins over its twin.
     *
     * One device: the same [OutputTarget.hardwareId], or the same
     * [OutputTarget.address] where that address has only one target in each
     * family — a computer offering several AirPlay speakers at one address
     * keeps them all.
     */
    fun rows(
        found: Map<String, List<OutputTarget>>,
        active: ActiveOutput?,
        steerable: (OutputFamily) -> Boolean,
    ): List<Pair<OutputFamily, OutputTarget>> {
        val candidates = ArrayList<Pair<OutputFamily, OutputTarget>>()
        for (family in listOrder) {
            val playing = active?.takeIf { it.family === family }?.target
            if (playing == null && !steerable(family)) continue
            val listed = found[family.id].orEmpty()
            if (playing != null && listed.none { it.id == playing.id }) candidates += family to playing
            for (target in listed) candidates += family to target
        }

        val perAddress = candidates.mapNotNull { (f, t) -> t.address?.let { f.id to it } }.groupingBy { it }.eachCount()
        fun alone(family: OutputFamily, target: OutputTarget) = perAddress[family.id to target.address] == 1
        fun sameDevice(a: Pair<OutputFamily, OutputTarget>, b: Pair<OutputFamily, OutputTarget>): Boolean {
            val (fa, ta) = a
            val (fb, tb) = b
            if (fa === fb) return false
            if (ta.hardwareId != null && ta.hardwareId == tb.hardwareId) return true
            return ta.address != null && ta.address == tb.address && alone(fa, ta) && alone(fb, tb)
        }
        fun isActive(row: Pair<OutputFamily, OutputTarget>) =
            active != null && row.first === active.family && row.second.id == active.target.id

        val kept = ArrayList<Pair<OutputFamily, OutputTarget>>()
        for (row in candidates) {
            if (isActive(row)) {
                kept.removeAll { sameDevice(it, row) }
                kept += row
            } else if (kept.none { sameDevice(it, row) }) {
                kept += row
            }
        }
        return kept
    }
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

/** The row next goes to: the one after, wrapped to the first under repeat-queue as on the phone. */
internal fun OutputHost.nextRow(): Int =
    wrappedIndex(index.value + 1, queue.value.size, repeatQueue = repeatMode.value == RepeatMode.QUEUE)

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

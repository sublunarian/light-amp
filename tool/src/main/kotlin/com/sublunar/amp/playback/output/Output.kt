package com.sublunar.amp.playback.output

import com.sublunar.amp.data.RepeatMode

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

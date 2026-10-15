package com.sublunar.amp.data

/**
 * One track waiting to be fetched, and the source that can serve it.
 *
 * An offline mode's entries are ids alone, and the row is read from the
 * source's tables when its turn comes: Everything over a large library is
 * the whole library queued, and as tracks that was a second copy of it held
 * for as long as the queue took to drain. One asked for by hand keeps its
 * [track], which may be a song the library doesn't hold — a radio's, say.
 */
data class QueuedDownload(val sourceId: String, val trackId: String, val track: Track? = null) {
    constructor(sourceId: String, track: Track) : this(sourceId, track.id, track)

    val key: DownloadQueue.Key get() = DownloadQueue.Key(sourceId, trackId)
}

/**
 * What is waiting to be downloaded, in the order it will be fetched — and
 * nothing else. No I/O, no clock of its own, so the policy can be tested on
 * its own; [Downloader] owns the transfers and tells this what happened.
 *
 * Two lanes, drained in order: what the user asked for, then what a mode
 * decided to fetch on their behalf. Without the split, tapping Download on an
 * album while "Download everything" was working through a ten-thousand-track
 * library put that album ten thousand tracks back. A manual pick also
 * *promotes* a track already waiting in the automatic lane rather than
 * queueing it twice.
 *
 * Entries are keyed by source *and* track: two servers can hand out the same
 * id for different music, and every source's downloads share this one queue
 * whichever of them is being browsed. [next] takes a predicate for that
 * reason — a source whose server has stopped answering, or whose library is
 * mid-sync, is skipped rather than parking everyone else's downloads behind
 * it.
 */
class DownloadQueue {

    data class Key(val sourceId: String, val trackId: String)

    /** An entry handed out by [next], with the lane it came from so it can go back there. */
    data class Picked(val entry: QueuedDownload, val manual: Boolean)

    private val manualLane = LinkedHashMap<Key, QueuedDownload>()
    private val autoLane = LinkedHashMap<Key, QueuedDownload>()

    /**
     * Consecutive failures per entry, so one the server will never serve stops
     * holding up everything behind it — see [fail]. Cleared by [complete].
     */
    private val strikes = HashMap<Key, Int>()

    val size: Int get() = manualLane.size + autoLane.size

    fun isEmpty(): Boolean = manualLane.isEmpty() && autoLane.isEmpty()

    /** The sources with something waiting — for saying *why* nothing is moving. */
    fun sourceIds(): Set<String> =
        HashSet<String>().apply {
            manualLane.keys.forEach { add(it.sourceId) }
            autoLane.keys.forEach { add(it.sourceId) }
        }

    fun strikes(key: Key): Int = strikes[key] ?: 0

    /**
     * Queue [entry]. A manual add moves it out of the automatic lane if it was
     * waiting there; an automatic add of something already asked for by hand
     * is dropped. Re-adding to the same lane keeps the place it had.
     *
     * An automatic add of something already waiting keeps the entry that is
     * there. Each top-up offers the whole of what its mode wants again, and an
     * entry swapped for its equal left the lane holding both their ids — over
     * a library queued whole, every id twice.
     */
    fun add(entry: QueuedDownload, manual: Boolean) {
        val key = entry.key
        if (manual) {
            autoLane.remove(key)
            manualLane[key] = entry
        } else if (key !in manualLane) {
            autoLane.putIfAbsent(key, entry)
        }
    }

    /**
     * Take the next entry whose source [eligible] allows: the first such in the
     * manual lane, else the first such in the automatic lane. Null when nothing
     * qualifies — which, unless [isEmpty], means every waiting source is
     * currently held back.
     */
    fun next(eligible: (sourceId: String) -> Boolean): Picked? {
        take(manualLane, eligible)?.let { return Picked(it, manual = true) }
        take(autoLane, eligible)?.let { return Picked(it, manual = false) }
        return null
    }

    private fun take(
        lane: LinkedHashMap<Key, QueuedDownload>,
        eligible: (String) -> Boolean,
    ): QueuedDownload? {
        val hit = lane.entries.firstOrNull { eligible(it.key.sourceId) } ?: return null
        lane.remove(hit.key)
        return hit.value
    }

    /** The transfer worked: nothing more to remember about it. */
    fun complete(entry: QueuedDownload) {
        strikes.remove(entry.key)
    }

    /**
     * The transfer failed. Back to the front of its own lane — an outage
     * shouldn't cost a track the place it had earned — unless it keeps failing.
     *
     * A track the server can never serve, a file deleted since the last sync,
     * went to the front every time and was picked again immediately, so nothing
     * behind it ever ran. Measured 2026-09-03: one foreign id held 8,303
     * downloads for as long as the app was open. After [FAILURES_BEFORE_BACK]
     * tries it goes to the back instead. The outage case is unharmed —
     * everything is failing, so everything moves and the relative order stays.
     *
     * Returns true when it went to the back.
     */
    fun fail(picked: Picked): Boolean {
        val key = picked.entry.key
        val count = (strikes[key] ?: 0) + 1
        strikes[key] = count
        val toBack = count >= FAILURES_BEFORE_BACK
        if (toBack) {
            // Taken off the lane when it was picked, so a plain put appends.
            laneOf(picked)[key] = picked.entry
        } else {
            requeueFront(picked)
        }
        return toBack
    }

    /**
     * Put back at the front of its lane without a strike — for when the server
     * couldn't be asked at all, which says nothing about the track.
     */
    fun requeueFront(picked: Picked) {
        val lane = laneOf(picked)
        val rest = LinkedHashMap(lane)
        lane.clear()
        lane[picked.entry.key] = picked.entry
        lane.putAll(rest)
    }

    private fun laneOf(picked: Picked) = if (picked.manual) manualLane else autoLane

    /** Drop everything waiting for one source; returns how many went. */
    fun removeSource(sourceId: String): Int {
        val before = size
        manualLane.keys.removeAll { it.sourceId == sourceId }
        autoLane.keys.removeAll { it.sourceId == sourceId }
        strikes.keys.removeAll { it.sourceId == sourceId }
        return before - size
    }

    fun clear() {
        manualLane.clear()
        autoLane.clear()
        strikes.clear()
    }

    companion object {
        /**
         * Failures an entry gets at the front of its lane before it is sent to
         * the back. Three is enough to ride out a server restart without
         * letting one bad id wedge the queue.
         */
        const val FAILURES_BEFORE_BACK = 3
    }
}

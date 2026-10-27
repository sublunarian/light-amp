package com.sublunar.amp.data

import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which of a server's addresses the source talks to, settled again when the
 * one in use stops answering and when a better one may have started to. Only
 * a source that keeps more than one address — today a Plex server's — has
 * anything to settle; the kind's provider does the asking.
 *
 * A Plex source linked at home is saved at the server's LAN address. On
 * cellular that address leads nowhere, and before this only a *failed sync*
 * tried the others (see LibraryRepository.repoint) — while on a metered link
 * an automatic sync waits hours. So a morning off Wi-Fi went like this: every
 * reachability ping went to 192.168.x.x, the server was called gone, the
 * lists narrowed to downloads, and playlists showed only the songs on the
 * phone, with the server's public address unused in the stored list the
 * whole time.
 *
 * Now a ping that misses asks plex.tv for the server's current addresses — a
 * single small request, nothing like a sync — and keeps the best-ranked one
 * that answers: LAN, then public, then relay.
 * And once the server answers, a source that is off its best address tries
 * the ones ranked above it again, so coming home puts it back on the LAN
 * rather than leaving it on the public route, which may not loop back
 * through the router at all, sends downloads out and back over the home
 * upload, and can be held to Plex's rules for remote streams.
 */
class SourceRoute(
    private val source: suspend () -> MusicSource?,
    /**
     * The server's addresses as its directory lists them now, best first —
     * null when it couldn't be asked or doesn't list this server. See
     * SourceProvider.advertisedAddresses.
     */
    private val advertised: suspend (MusicSource) -> List<String>?,
    /** The best-ranked of these that answers, or null — see SourceProvider.firstAnswering. */
    private val firstAnswering: suspend (List<String>, MusicSource) -> String?,
    private val save: suspend (MusicSource) -> Unit,
    /**
     * Whether a LAN address could answer at all. Off Wi-Fi it can't, and
     * probing one is a wait for nothing on every network change.
     */
    private val onLan: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { android.util.Log.i("AmpNet", "route: $it") },
) {
    private val busy = Mutex()
    private var askedDirectoryMs: Long? = null

    /**
     * The address in use didn't answer: find one that does. True when the
     * source moved to another address — saving it rebuilds the client, as a
     * renewed token does.
     *
     * The address in use is tried along with the rest, in its rank: a LAN
     * address that answers this time after all is kept.
     */
    suspend fun recover(): Boolean = busy.withLock {
        val current = routed() ?: return false
        val fresh = askDirectory(current)
        val ordered = fresh ?: current.connections
        if (ordered.isEmpty()) return false
        val found = firstAnswering(ordered, current)
        val next = current.copy(baseUrl = found ?: current.baseUrl, connections = ordered)
        // The list is kept even when nothing answered: plex.tv's is the newer
        // one, and the next try should start from it.
        if (next != current) save(next)
        val moved = found != null && found != current.baseUrl
        if (moved) log("moved ${hostOf(current.baseUrl)} → ${hostOf(found)}")
        moved
    }

    /**
     * The server answers, but perhaps not at its best address: try the ones
     * ranked above it. True when the source moved up.
     *
     * The stored list only — this runs after every successful check, and the
     * list is as new as the last [recover], which is what moved it down.
     */
    suspend fun upgrade(): Boolean = busy.withLock {
        val current = routed() ?: return false
        if (current.baseUrl !in current.connections) return false
        val better = current.connections.takeWhile { it != current.baseUrl }
        if (better.isEmpty() || !onLan()) return false
        val found = firstAnswering(better, current) ?: return false
        save(current.copy(baseUrl = found))
        log("moved back ${hostOf(current.baseUrl)} → ${hostOf(found)}")
        true
    }

    /** The active source, when it keeps other addresses to move between — as SourceProvider.relocate asks. */
    private suspend fun routed(): MusicSource? = source()?.takeIf { it.connections.isNotEmpty() }

    /**
     * The directory's list (plex.tv's, for Plex), at most once a
     * [DIRECTORY_EVERY_MS]. A server that is really down misses every retry in
     * a row, and its addresses don't change between them; the stored list,
     * refreshed by the last ask, serves those.
     */
    private suspend fun askDirectory(current: MusicSource): List<String>? {
        val now = nowMs()
        val last = askedDirectoryMs
        if (last != null && now - last in 0 until DIRECTORY_EVERY_MS) return null
        askedDirectoryMs = now
        return advertised(current)?.takeIf { it.isNotEmpty() }
    }

    private fun hostOf(url: String?): String = url?.let { runCatching { URI(it).host }.getOrNull() } ?: "?"

    companion object {
        const val DIRECTORY_EVERY_MS = 60_000L
    }
}

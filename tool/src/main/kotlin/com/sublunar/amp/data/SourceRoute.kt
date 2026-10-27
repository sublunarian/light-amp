package com.sublunar.amp.data

import java.net.URI
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which of a server's addresses the active source talks to, for a source that keeps several (a Plex
 * server's): ranked LAN, then public, then relay. The kind's provider does the asking.
 */
class SourceRoute(
    private val source: suspend () -> MusicSource?,
    /** What the server's directory (plex.tv) lists now, best first — see SourceProvider.advertisedAddresses. */
    private val advertised: suspend (MusicSource) -> List<String>?,
    /** The first of these that answers, in their order — see SourceProvider.firstAnswering. */
    private val firstAnswering: suspend (uris: List<String>, source: MusicSource, quickOnly: Boolean) -> String?,
    /** Writes the address alone, onto a record still at `from` — see AppSettings.setSourceAddress. */
    private val save: suspend (sourceId: String, from: String, baseUrl: String, connections: List<String>) -> Unit,
    /** Whether a LAN address could answer at all: off Wi-Fi, probing one is a wait for nothing. */
    private val onLan: () -> Boolean,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { android.util.Log.i("AmpNet", "route: $it") },
) {
    private val busy = Mutex()
    private var askedDirectoryMs: Long? = null

    /**
     * The address in use didn't answer: move to the best-ranked one that does, the one in use
     * included, so a LAN address that answers after all is kept. True when one answered.
     */
    suspend fun recover(): Boolean = busy.withLock {
        val current = routed() ?: return false
        val fresh = askDirectory(current)
        val ordered = fresh ?: current.connections
        if (ordered.isEmpty()) return false
        // A quick look: this follows every check's first miss, and a patient pass is up to a
        // minute per check on cellular while the server is off. A failed sync's relocate waits longer.
        val found = firstAnswering(ordered, current, true)
        val baseUrl = found ?: current.baseUrl
        // The list is kept even when nothing answered: plex.tv's is the newer
        // one, and the next try should start from it.
        if (baseUrl != current.baseUrl || ordered != current.connections) {
            save(current.id, current.baseUrl, baseUrl, ordered)
        }
        if (found != null && found != current.baseUrl) log("moved ${hostOf(current.baseUrl)} → ${hostOf(found)}")
        found != null
    }

    /**
     * The server answers: on Wi-Fi, try the stored addresses ranked above the one in use. True when it
     * moved up. Worth it at home, where the public route sends downloads out and back over the upload.
     */
    suspend fun upgrade(): Boolean = busy.withLock {
        val current = routed() ?: return false
        if (current.baseUrl !in current.connections) return false
        val better = current.connections.takeWhile { it != current.baseUrl }
        if (better.isEmpty() || !onLan()) return false
        // A quick look only: this follows every check on any Wi-Fi, and where
        // the LAN address answers at all it answers well inside it.
        val found = firstAnswering(better, current, true) ?: return false
        save(current.id, current.baseUrl, found, current.connections)
        log("moved back ${hostOf(current.baseUrl)} → ${hostOf(found)}")
        true
    }

    /** The active source, when it keeps other addresses to move between — as SourceProvider.relocate asks. */
    private suspend fun routed(): MusicSource? = source()?.takeIf { it.connections.isNotEmpty() }

    /**
     * The directory's list, at most once a [DIRECTORY_EVERY_MS]: a server that is down misses every
     * retry, and its addresses don't change between them.
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

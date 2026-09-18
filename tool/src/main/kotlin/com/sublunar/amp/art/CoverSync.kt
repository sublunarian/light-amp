package com.sublunar.amp.art

/** What one attempt to fetch a cover came to. */
sealed interface CoverFetch {
    class Got(val bytes: ByteArray) : CoverFetch

    /**
     * The server answered, and has no such picture — a 404, or a 200 that is
     * not an image. Asking again tomorrow gets the same answer.
     */
    data object None : CoverFetch

    /** No usable answer: a timeout, a 5xx, the rule closing. Worth another try, another day. */
    data class Failed(val why: String) : CoverFetch
}

/** How a run went, for the log. */
data class CoverSyncResult(
    val wanted: Int,
    val missing: Int,
    val fetched: Int,
    val fetchedBytes: Long,
    /** Why it stopped short, or null when it got to the end. */
    val stopped: String?,
)

/**
 * Fetch the covers a library wants and the disk doesn't have, one at a time.
 *
 * The part of the cover sync that is only decisions — what is missing, when
 * to stop, what never to ask for again — with everything it touches handed
 * in, so it can be run against fakes. ArtworkLoader supplies the real disk
 * and the real server; see [ArtworkLoader.syncCovers] for why it exists.
 *
 * One at a time and in the order given, because this is background work on a
 * link someone may be listening over: it takes one of the loader's four
 * fetch slots, not all of them, and [mayContinue] is asked before every
 * cover so it steps aside for a starting stream and ends the moment the link
 * stops being free.
 */
class CoverSync(
    private val isOnDisk: (String) -> Boolean,
    private val fetch: suspend (String) -> CoverFetch,
    /** Write it; false when the bytes are not a picture after all. */
    private val store: (String, ByteArray) -> Boolean,
    private val cacheBytes: () -> Long,
    /**
     * Bring the cache back under [budgetBytes] by dropping what was used
     * least recently — never this library's covers, never a downloaded
     * album's — and say what it comes to now.
     */
    private val makeRoom: suspend () -> Long,
    private val budgetBytes: Long,
    private val mayContinue: suspend () -> Boolean,
    /**
     * Covers the server has said it doesn't have, remembered for the life of
     * the process so that every sync doesn't ask for the same forty again.
     */
    private val knownMissing: MutableSet<String>,
) {
    suspend fun run(wanted: Collection<String>): CoverSyncResult {
        val ids = wanted.filter { it.isNotBlank() }.distinct()
        val missing = ids.filter { it !in knownMissing && !isOnDisk(it) }
        var fetched = 0
        var fetchedBytes = 0L
        var stopped: String? = null
        if (missing.isEmpty()) return CoverSyncResult(ids.size, 0, 0, 0L, null)

        var total = cacheBytes()
        var failuresInARow = 0
        for (id in missing) {
            if (!mayContinue()) {
                stopped = "no longer allowed"
                break
            }
            when (val answer = fetch(id)) {
                is CoverFetch.Got -> {
                    failuresInARow = 0
                    if (!store(id, answer.bytes)) {
                        knownMissing += id
                        continue
                    }
                    fetched++
                    fetchedBytes += answer.bytes.size
                    total += answer.bytes.size
                    if (total > budgetBytes) {
                        total = makeRoom()
                        // Nothing left that may be dropped: this library and
                        // the downloads are, between them, the whole budget.
                        // Carrying on would only fetch covers to evict covers.
                        if (total > budgetBytes) {
                            stopped = "cache full"
                            break
                        }
                    }
                }
                CoverFetch.None -> {
                    failuresInARow = 0
                    knownMissing += id
                }
                is CoverFetch.Failed -> {
                    // A server that has stopped answering is not asked seven
                    // hundred more times, a timeout each.
                    if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) {
                        stopped = "server not answering (${answer.why})"
                        break
                    }
                }
            }
        }
        return CoverSyncResult(ids.size, missing.size, fetched, fetchedBytes, stopped)
    }

    private companion object {
        const val MAX_FAILURES_IN_A_ROW = 5
    }
}

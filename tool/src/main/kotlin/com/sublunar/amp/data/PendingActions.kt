package com.sublunar.amp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Something the user did that the server hasn't been told about yet.
 *
 * [atMs] is when it happened, not when it is sent — a scrobble replayed after a
 * week offline should land in history where it belongs.
 */
@Serializable
data class PendingAction(
    val kind: Kind,
    /** Song id, album id, or artist *name* — artists are keyed by name here. */
    val id: String,
    /** Stars for [Kind.RATE]; unused otherwise. */
    val value: Int = 0,
    val atMs: Long = System.currentTimeMillis(),
    /**
     * The source it was done on. The queue is one for every source, and a like
     * replayed to a server that never had the song is refused and dropped.
     * Null for anything queued by a version that didn't record it, which goes
     * to whichever source is sent to first.
     */
    val sourceId: String? = null,
) {
    @Serializable
    enum class Kind {
        SCROBBLE,
        STAR_SONG,
        UNSTAR_SONG,
        STAR_ALBUM,
        UNSTAR_ALBUM,
        STAR_ARTIST,
        UNSTAR_ARTIST,
        RATE_SONG,
        RATE_ALBUM,
    }
}

/**
 * Plays, likes and ratings made while the server was out of reach, kept until it
 * comes back.
 *
 * The local database is the immediate truth — a like shows as liked the instant
 * it is tapped, offline or not — and this is the record of what still has to be
 * mirrored outward. Held in preferences rather than a table so adding it needs no
 * schema change, which on this app's destructive-migration setup would cost the
 * user their whole library cache.
 *
 * Order is preserved and a run stops at the first write that went unanswered, so
 * a queue that ends "star, unstar, star" can never be applied out of order and
 * leave the server disagreeing with the phone. A write the server answered no to
 * is dropped and said instead: it would get the same answer every time, and kept,
 * it held everything behind it — plays included — for good.
 */
class PendingActions(private val settings: AppSettings) {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private val _count = MutableStateFlow(0)

    /** How many actions are waiting — surfaced in Settings. */
    val count: StateFlow<Int> = _count

    suspend fun load(): List<PendingAction> = decode(settings.pendingActions())

    /** Call once at boot so the count is right before anything is added. */
    suspend fun refreshCount() {
        _count.value = load().size
    }

    suspend fun add(action: PendingAction) = lock.withLock {
        // A rating or a like replaces the last one for the same thing: only the
        // final state matters, and a long offline session shouldn't send a
        // hundred requests to arrive at it. Plays are never collapsed.
        val queue = decode(settings.pendingActions()).without(action) + action
        val trimmed = if (queue.size > MAX_QUEUED) queue.takeLast(MAX_QUEUED) else queue
        settings.setPendingActions(json.encodeToString(trimmed))
        _count.value = trimmed.size
    }

    /**
     * A like or a rating just reached the server: anything still queued for the
     * same thing is older, and sent later it would undo this one.
     */
    suspend fun forget(landed: PendingAction) {
        if (landed.kind == PendingAction.Kind.SCROBBLE) return
        lock.withLock {
            val queue = decode(settings.pendingActions())
            val left = queue.without(landed)
            if (left.size == queue.size) return@withLock
            settings.setPendingActions(if (left.isEmpty()) "" else json.encodeToString(left))
            _count.value = left.size
        }
    }

    /**
     * Send everything waiting for [sourceId], oldest first, and return what the
     * server refused — each reason once per refusal, for the caller to say.
     *
     * A refusal is dropped and said; the first with no answer stops the run and
     * keeps it and the rest — the server is presumably still unreachable, and
     * draining into a void would lose the lot. Another source's entries are left
     * where they are, untouched.
     */
    suspend fun flush(
        client: MusicServer,
        sourceId: String,
        artistId: suspend (String) -> String?,
    ): List<String> =
        lock.withLock {
            val queue = decode(settings.pendingActions())
            if (queue.isEmpty()) return@withLock emptyList()
            val run = replay(queue, mine = { it.sourceId == null || it.sourceId == sourceId }) {
                send(it, client, artistId)
            }
            settings.setPendingActions(if (run.left.isEmpty()) "" else json.encodeToString(run.left))
            _count.value = run.left.size
            run.refused
        }

    private suspend fun send(
        action: PendingAction,
        client: MusicServer,
        artistId: suspend (String) -> String?,
    ): WriteResult = attemptWrite {
        when (action.kind) {
            PendingAction.Kind.SCROBBLE -> client.scrobble(action.id, action.atMs)
            PendingAction.Kind.STAR_SONG -> client.starSong(action.id)
            PendingAction.Kind.UNSTAR_SONG -> client.unstarSong(action.id)
            PendingAction.Kind.STAR_ALBUM -> client.starAlbum(action.id)
            PendingAction.Kind.UNSTAR_ALBUM -> client.unstarAlbum(action.id)
            // An artist the server doesn't know by that name is refused rather
            // than retried forever; the like still stands locally until the
            // next sync brings back the server's own.
            PendingAction.Kind.STAR_ARTIST ->
                client.starArtist(artistId(action.id) ?: throw RefusedWrite(NO_SUCH_ARTIST))
            PendingAction.Kind.UNSTAR_ARTIST ->
                client.unstarArtist(artistId(action.id) ?: throw RefusedWrite(NO_SUCH_ARTIST))
            PendingAction.Kind.RATE_SONG, PendingAction.Kind.RATE_ALBUM ->
                client.setRating(action.id, action.value)
        }
    }

    private fun decode(raw: String): List<PendingAction> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PendingAction>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        /** Weeks of offline listening, and still nothing near a preferences limit. */
        const val MAX_QUEUED = 2_000
    }
}

/** What a run through the queue left behind, and the reasons for what it dropped. */
internal data class Replay(val left: List<PendingAction>, val refused: List<String>)

/**
 * Send [queue] in order through [send], skipping what isn't [mine].
 *
 * Saved goes, refused goes and is noted, and the first unanswered one ends the
 * run: it and everything after it stay, in order, unsent. What isn't [mine]
 * stays where it was.
 */
internal suspend fun replay(
    queue: List<PendingAction>,
    mine: (PendingAction) -> Boolean,
    send: suspend (PendingAction) -> WriteResult,
): Replay {
    val left = mutableListOf<PendingAction>()
    val refused = mutableListOf<String>()
    queue.forEachIndexed { i, action ->
        if (!mine(action)) {
            left += action
            return@forEachIndexed
        }
        when (val result = send(action)) {
            WriteResult.Saved -> Unit
            is WriteResult.Refused -> refused += result.reason
            is WriteResult.Unreachable -> return Replay(left + queue.drop(i), refused)
        }
    }
    return Replay(left, refused)
}

/**
 * The queue without whatever [landed] replaces. Plays are never collapsed: each
 * one is its own play.
 */
internal fun List<PendingAction>.without(landed: PendingAction): List<PendingAction> =
    if (landed.kind == PendingAction.Kind.SCROBBLE) this else filterNot { it.supersededBy(landed) }

/**
 * Whether [next] sets the same piece of state on the same source. An entry with
 * no source is any source's — it is sent to whichever comes first, so the same
 * thing done again anywhere replaces it.
 */
internal fun PendingAction.supersededBy(next: PendingAction): Boolean =
    id == next.id && kind.group() == next.kind.group() && (sourceId == null || sourceId == next.sourceId)

/** Which actions describe the same piece of state, whichever way they set it. */
internal fun PendingAction.Kind.group(): String = when (this) {
    PendingAction.Kind.STAR_SONG, PendingAction.Kind.UNSTAR_SONG -> "like-song"
    PendingAction.Kind.STAR_ALBUM, PendingAction.Kind.UNSTAR_ALBUM -> "like-album"
    PendingAction.Kind.STAR_ARTIST, PendingAction.Kind.UNSTAR_ARTIST -> "like-artist"
    PendingAction.Kind.RATE_SONG -> "rate-song"
    PendingAction.Kind.RATE_ALBUM -> "rate-album"
    PendingAction.Kind.SCROBBLE -> "scrobble"
}

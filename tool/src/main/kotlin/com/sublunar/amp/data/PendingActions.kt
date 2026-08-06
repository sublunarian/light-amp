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
 * Order is preserved and replay stops at the first failure, so a queue that ends
 * "star, unstar, star" can never be applied out of order and leave the server
 * disagreeing with the phone.
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
        val queue = decode(settings.pendingActions()).toMutableList()
        // A rating or a like replaces the last one for the same thing: only the
        // final state matters, and a long offline session shouldn't send a
        // hundred requests to arrive at it. Plays are never collapsed.
        if (action.kind != PendingAction.Kind.SCROBBLE) {
            queue.removeAll { it.supersededBy(action) }
        }
        queue += action
        val trimmed = if (queue.size > MAX_QUEUED) queue.takeLast(MAX_QUEUED) else queue
        settings.setPendingActions(json.encodeToString(trimmed))
        _count.value = trimmed.size
    }

    /**
     * Send everything waiting, oldest first.
     *
     * Stops at the first failure and keeps the rest — the server is presumably
     * still unreachable, and draining into a void would lose the lot.
     */
    suspend fun flush(client: MusicServer, artistId: suspend (String) -> String?): Int =
        lock.withLock {
            val queue = decode(settings.pendingActions())
            if (queue.isEmpty()) return@withLock 0
            var sent = 0
            for (action in queue) {
                val ok = runCatching { apply(action, client, artistId) }.getOrDefault(false)
                if (!ok) break
                sent++
            }
            val left = queue.drop(sent)
            settings.setPendingActions(if (left.isEmpty()) "" else json.encodeToString(left))
            _count.value = left.size
            sent
        }

    /** True when this action was sent, or when it can never be sent at all. */
    private suspend fun apply(
        action: PendingAction,
        client: MusicServer,
        artistId: suspend (String) -> String?,
    ): Boolean {
        when (action.kind) {
            PendingAction.Kind.SCROBBLE -> client.scrobble(action.id, action.atMs)
            PendingAction.Kind.STAR_SONG -> client.starSong(action.id)
            PendingAction.Kind.UNSTAR_SONG -> client.unstarSong(action.id)
            PendingAction.Kind.STAR_ALBUM -> client.starAlbum(action.id)
            PendingAction.Kind.UNSTAR_ALBUM -> client.unstarAlbum(action.id)
            // An artist the server doesn't know by that name is dropped rather
            // than retried forever; the like still stands locally.
            PendingAction.Kind.STAR_ARTIST -> artistId(action.id)?.let { client.starArtist(it) }
            PendingAction.Kind.UNSTAR_ARTIST -> artistId(action.id)?.let { client.unstarArtist(it) }
            PendingAction.Kind.RATE_SONG, PendingAction.Kind.RATE_ALBUM ->
                if (!client.setRating(action.id, action.value)) return false
        }
        return true
    }

    private fun decode(raw: String): List<PendingAction> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PendingAction>>(raw) }.getOrDefault(emptyList())
    }

    private fun PendingAction.supersededBy(next: PendingAction): Boolean =
        id == next.id && kind.group() == next.kind.group()

    /** Which actions describe the same piece of state, whichever way they set it. */
    private fun PendingAction.Kind.group(): String = when (this) {
        PendingAction.Kind.STAR_SONG, PendingAction.Kind.UNSTAR_SONG -> "like-song"
        PendingAction.Kind.STAR_ALBUM, PendingAction.Kind.UNSTAR_ALBUM -> "like-album"
        PendingAction.Kind.STAR_ARTIST, PendingAction.Kind.UNSTAR_ARTIST -> "like-artist"
        PendingAction.Kind.RATE_SONG -> "rate-song"
        PendingAction.Kind.RATE_ALBUM -> "rate-album"
        PendingAction.Kind.SCROBBLE -> "scrobble"
    }

    private companion object {
        /** Weeks of offline listening, and still nothing near a preferences limit. */
        const val MAX_QUEUED = 2_000
    }
}

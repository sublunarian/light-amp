package com.sublunar.amp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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
    /** Stars for [Kind.RATE_SONG] and [Kind.RATE_ALBUM]; unused otherwise. */
    val value: Int = 0,
    // Always written: left out, it would read back as the time it was read.
    @EncodeDefault val atMs: Long = System.currentTimeMillis(),
    /**
     * The source it was done on: the queue is shared by every source, and a like
     * replayed to a server that never had the song is refused and dropped. Null
     * for anything queued by a build that didn't record it, which goes to
     * whichever source is sent to first.
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
 * comes back. The local database is the immediate truth; this is what still has
 * to be mirrored outward. Held in preferences, one queue for every source.
 *
 * Order is preserved and a run stops at the first write that went unanswered, so
 * "star, unstar, star" can never land out of order. A write the server refuses
 * is dropped and said instead: it would get the same answer every time, and
 * kept, it would hold everything behind it for good.
 *
 * Read one entry at a time. One this build can't read (a kind a newer build
 * added, a field whose type moved) is never sent and stays as stored, where it
 * was, so going back a version loses nothing. A later like or rating for the
 * same thing still replaces it.
 */
class PendingActions(private val settings: AppSettings) {

    private val lock = Mutex()

    private val _count = MutableStateFlow(0)

    /** How many actions are waiting. */
    val count: StateFlow<Int> = _count

    /** Call once at boot so the count is right before anything is added. */
    suspend fun refreshCount() {
        _count.value = decode(settings.pendingActions()).size
    }

    suspend fun add(action: PendingAction) = lock.withLock {
        // A like or rating replaces the last one for the same thing: only the
        // final state matters. Plays are never collapsed.
        val queue = decode(settings.pendingActions()).filterNot { action.replaces(it.about) } + entry(action)
        val trimmed = if (queue.size > MAX_QUEUED) queue.takeLast(MAX_QUEUED) else queue
        settings.setPendingActions(encode(trimmed))
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
            val left = queue.filterNot { landed.replaces(it.about) }
            if (left.size == queue.size) return@withLock
            settings.setPendingActions(encode(left))
            _count.value = left.size
        }
    }

    /**
     * Send everything waiting for [sourceId], oldest first — see [replay] — and
     * return the reasons for what the server refused, for the caller to say.
     * Another source's entries are left where they are.
     */
    suspend fun flush(
        client: MusicServer,
        sourceId: String,
        artistId: suspend (String) -> String?,
    ): List<String> =
        lock.withLock {
            val queue = decode(settings.pendingActions())
            if (queue.isEmpty()) return@withLock emptyList()
            // One this build can't read is nobody's here: it waits for the build that can.
            val mine = { e: Entry -> e.action != null && (e.action.sourceId == null || e.action.sourceId == sourceId) }
            val run = replay(queue, mine) { send(it.action!!, client, artistId) }
            settings.setPendingActions(encode(run.left))
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
            // An artist the server doesn't know by that name is refused, not
            // retried forever; the next sync brings back the server's own likes.
            PendingAction.Kind.STAR_ARTIST ->
                client.starArtist(artistId(action.id) ?: throw RefusedWrite(NO_SUCH_ARTIST))
            PendingAction.Kind.UNSTAR_ARTIST ->
                client.unstarArtist(artistId(action.id) ?: throw RefusedWrite(NO_SUCH_ARTIST))
            PendingAction.Kind.RATE_SONG, PendingAction.Kind.RATE_ALBUM ->
                client.setRating(action.id, action.value)
        }
    }

    /**
     * One stored entry, and what this build reads in it — null when it can't.
     * [about] is what a later like or rating for the same thing replaces.
     */
    private class Entry(val stored: JsonElement, val action: PendingAction?) {
        val about: PendingAction? = action ?: aboutOf(stored)
    }

    private fun entry(action: PendingAction) =
        Entry(json.encodeToJsonElement(PendingAction.serializer(), action), action)

    private fun decode(raw: String): List<Entry> {
        val stored = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        return stored.map { Entry(it, read(it)) }
    }

    /** As stored, character for character, which re-encoding wouldn't always be. */
    private fun encode(queue: List<Entry>): String =
        if (queue.isEmpty()) "" else JsonArray(queue.map { it.stored }).toString()

    private companion object {
        /** Weeks of offline listening, and still nothing near a preferences limit. */
        const val MAX_QUEUED = 2_000

        val ABOUT = setOf("kind", "id", "sourceId")

        val json = Json { ignoreUnknownKeys = true }

        fun read(stored: JsonElement): PendingAction? =
            runCatching { json.decodeFromJsonElement(PendingAction.serializer(), stored) }.getOrNull()

        /** Its kind, id and source alone, which may still read when the rest of it doesn't. */
        fun aboutOf(stored: JsonElement): PendingAction? =
            (stored as? JsonObject)?.let { entry -> read(JsonObject(entry.filterKeys { it in ABOUT })) }
    }
}

/** What a run through the queue left behind, and the reasons for what it dropped. */
internal data class Replay<T>(val left: List<T>, val refused: List<String>)

/**
 * Send [queue] in order through [send], skipping what isn't [mine].
 *
 * Saved goes, refused goes and is noted, and the first unanswered one ends the
 * run: it and everything after it stay, in order, unsent. What isn't [mine]
 * stays where it was.
 */
internal suspend fun <T> replay(
    queue: List<T>,
    mine: (T) -> Boolean,
    send: suspend (T) -> WriteResult,
): Replay<T> {
    val left = mutableListOf<T>()
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
 * Whether this makes [queued] moot. Plays are never collapsed: each one is its
 * own play. One this build can't read is nothing it knows to replace.
 */
internal fun PendingAction.replaces(queued: PendingAction?): Boolean =
    queued != null && kind != PendingAction.Kind.SCROBBLE && queued.supersededBy(this)

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

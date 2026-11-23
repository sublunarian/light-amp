package com.sublunar.amp.data

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One playlist change made while the server couldn't be told, kept to send when
 * it can — see [PlaylistEdits].
 *
 * Adds go as adds, so a song someone else put in meanwhile stays. Removals and
 * reorders go as [Kind.SET], the whole list as the phone has it: positions on a
 * server that may have moved can't be trusted, so the phone's list wins.
 */
@Serializable
data class PlaylistEdit(
    val kind: Kind,
    val playlistId: String,
    /** The name, for [Kind.CREATE] and [Kind.RENAME]. */
    val name: String = "",
    /** Songs to append ([Kind.ADD]), or the whole list ([Kind.CREATE], [Kind.SET]). */
    val trackIds: List<String> = emptyList(),
) {
    @Serializable
    enum class Kind { CREATE, ADD, RENAME, DELETE, SET }
}

/** A playlist made on the phone that the server hasn't seen yet. */
internal const val PHONE_MADE_PREFIX = "amp-offline:"

internal fun newPhoneMadeId(): String = PHONE_MADE_PREFIX + UUID.randomUUID()

internal fun isPhoneMade(playlistId: String): Boolean = playlistId.startsWith(PHONE_MADE_PREFIX)

/**
 * [queue] with [edit] at the end, folded so it holds as little as says the same:
 * a playlist the server hasn't seen stays one [PlaylistEdit.Kind.CREATE] carrying
 * its name and songs, and a whole list replaces the adds and lists before it.
 */
internal fun queued(queue: List<PlaylistEdit>, edit: PlaylistEdit): List<PlaylistEdit> {
    val id = edit.playlistId
    if (isPhoneMade(id) && edit.kind != PlaylistEdit.Kind.CREATE) {
        val create = queue.firstOrNull { it.playlistId == id && it.kind == PlaylistEdit.Kind.CREATE } ?: return queue
        val folded = when (edit.kind) {
            PlaylistEdit.Kind.ADD -> create.copy(trackIds = create.trackIds + edit.trackIds)
            PlaylistEdit.Kind.SET -> create.copy(trackIds = edit.trackIds)
            PlaylistEdit.Kind.RENAME -> create.copy(name = edit.name)
            // Never sent, so nothing to take back: it goes with all it held.
            else -> return queue.filterNot { it.playlistId == id }
        }
        return queue.map { if (it === create) folded else it }
    }
    return when (edit.kind) {
        PlaylistEdit.Kind.SET -> queue.filterNot {
            it.playlistId == id && (it.kind == PlaylistEdit.Kind.ADD || it.kind == PlaylistEdit.Kind.SET)
        } + edit
        PlaylistEdit.Kind.DELETE -> queue.filterNot { it.playlistId == id } + edit
        PlaylistEdit.Kind.ADD -> {
            // Songs added one by one go as one request.
            val last = queue.lastOrNull()
            if (last != null && last.kind == PlaylistEdit.Kind.ADD && last.playlistId == id) {
                queue.dropLast(1) + last.copy(trackIds = last.trackIds + edit.trackIds)
            } else {
                queue + edit
            }
        }
        else -> queue + edit
    }
}

/** [fetched] as the phone has it: its own playlists added, renames and deletes applied. */
internal fun withEdits(fetched: List<Playlist>, queue: List<PlaylistEdit>, nowMs: Long): List<Playlist> {
    if (queue.isEmpty()) return fetched
    var list = fetched
    for (edit in queue) {
        list = when (edit.kind) {
            PlaylistEdit.Kind.CREATE ->
                if (list.any { it.id == edit.playlistId }) list
                else list + Playlist(edit.playlistId, edit.name, null, nowMs, nowMs, edit.trackIds)
            PlaylistEdit.Kind.RENAME -> list.map { if (it.id == edit.playlistId) it.copy(name = edit.name) else it }
            PlaylistEdit.Kind.DELETE -> list.filterNot { it.id == edit.playlistId }
            else -> list
        }
    }
    return list
}

/**
 * What turns [server] into [target] with the calls every server has: the
 * positions to remove (each with the song there), the songs to append, and
 * whether a reorder is still needed after. Extra copies go from the end, so the
 * songs that stay keep their places.
 */
internal data class Replacement(val removeAt: Map<Int, String>, val append: List<String>, val reorder: Boolean)

internal fun replacement(server: List<String>, target: List<String>): Replacement {
    val wanted = target.groupingBy { it }.eachCount().toMutableMap()
    val removeAt = LinkedHashMap<Int, String>()
    val kept = ArrayList<String>()
    for ((index, id) in server.withIndex()) {
        val left = wanted[id] ?: 0
        if (left > 0) {
            wanted[id] = left - 1
            kept += id
        } else {
            removeAt[index] = id
        }
    }
    val append = ArrayList<String>()
    for (id in target) {
        val left = wanted[id] ?: 0
        if (left > 0) {
            wanted[id] = left - 1
            append += id
        }
    }
    return Replacement(removeAt, append, kept + append != target)
}

/**
 * The playlist edits waiting for one source's server, kept in preferences. The
 * phone already shows them; this is what still has to be sent. In order, and a
 * run stops at the first the server didn't answer, so nothing overtakes.
 */
internal class PlaylistEdits(private val settings: AppSettings) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(sourceId: String): List<PlaylistEdit> {
        val raw = settings.playlistEdits(sourceId)
        if (raw.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<PlaylistEdit>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun write(sourceId: String, queue: List<PlaylistEdit>) =
        settings.setPlaylistEdits(sourceId, if (queue.isEmpty()) "" else json.encodeToString(queue))

    /** Add [edit], folded in — see [queued]. Whether the queue was empty before. */
    suspend fun add(sourceId: String, edit: PlaylistEdit): Boolean {
        val before = read(sourceId)
        write(sourceId, queued(before, edit))
        return before.isEmpty()
    }
}

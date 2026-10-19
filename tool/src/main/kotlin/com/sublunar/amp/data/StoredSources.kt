package com.sublunar.amp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * The stored sources list, read one record at a time.
 *
 * [readable] is what this build understands. A record it can't read — a kind
 * a newer build added, a field whose type moved — is kept as it was stored,
 * never shown, and written back where it was by every edit, so a downgrade
 * can't delete a source the upgrade will want back.
 *
 * The whole list used to be read in one go, and one record it couldn't read
 * made all of it read as empty: the Welcome screen, and adding a source from
 * there wrote the new one over every other.
 */
internal class StoredSources private constructor(
    val readable: List<MusicSource>,
    /** Stored position → the record as stored. */
    private val unreadable: Map<Int, JsonElement>,
) {
    /** [edited] — what an edit made of [readable] — as text to store, with the unreadable put back. */
    fun encode(json: Json, edited: List<MusicSource>): String {
        val records = edited.map { json.encodeToJsonElement(MusicSource.serializer(), it) }
        return json.encodeToString(JsonElement.serializer(), JsonArray(withKeptAt(unreadable, records)))
    }

    companion object {
        /** Nothing stored yet: what an edit starts from when there is no list to keep anything of. */
        val NONE = StoredSources(emptyList(), emptyMap())

        /**
         * Null when [text] isn't a JSON array at all, which is what the caller
         * falls back from exactly as before. A list with nothing readable in
         * it is an empty list, as `[]` always was.
         */
        fun decode(json: Json, text: String): StoredSources? {
            val records = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return null
            val readable = mutableListOf<MusicSource>()
            val unreadable = mutableMapOf<Int, JsonElement>()
            records.forEachIndexed { index, record ->
                // Anything at all: whatever this record trips over, it is the
                // record that can't be read, not the list.
                runCatching { json.decodeFromJsonElement(MusicSource.serializer(), record) }
                    .onSuccess { readable += it }
                    .onFailure { unreadable[index] = record }
            }
            return StoredSources(readable, unreadable)
        }
    }
}

/**
 * [edited] with each [kept] value put back at its stored index, lowest first.
 * One whose index is past the end of what is there by then goes at the end.
 */
internal fun <T> withKeptAt(kept: Map<Int, T>, edited: List<T>): List<T> {
    val list = edited.toMutableList()
    for ((index, value) in kept.toSortedMap()) list.add(minOf(index, list.size), value)
    return list
}

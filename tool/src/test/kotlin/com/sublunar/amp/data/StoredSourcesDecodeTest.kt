package com.sublunar.amp.data

import com.sublunar.amp.data.StoredSourcesTest.Companion.LOCAL
import com.sublunar.amp.data.StoredSourcesTest.Companion.UNKNOWN_KIND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/** Reading the sources list one record at a time — see [StoredSources]. */
class StoredSourcesDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a kept record goes back at its stored place`() {
        assertEquals(listOf("A", "X", "B"), withKeptAt(mapOf(1 to "X"), listOf("A", "B")))
        assertEquals(listOf("X"), withKeptAt(mapOf(0 to "X"), emptyList()))
        assertEquals(listOf("X", "A", "Y", "B", "C"), withKeptAt(mapOf(0 to "X", 2 to "Y"), listOf("A", "B", "C")))
        assertEquals(listOf("A"), withKeptAt(emptyMap(), listOf("A")))
    }

    @Test
    fun `or at the end, when the list in front of it got shorter`() {
        assertEquals(listOf("A", "X"), withKeptAt(mapOf(3 to "X"), listOf("A")))
    }

    @Test
    fun `text that isn't a list is no list at all`() {
        assertNull(StoredSources.decode(json, "{}"))
        assertNull(StoredSources.decode(json, ""))
        assertNull(StoredSources.decode(json, "not json"))
    }

    @Test
    fun `a record that can't be read is kept as it was stored`() {
        // The keys keep their order and every value its value. Spacing is the
        // encoder's own, and so is the spelling of a number that isn't whole —
        // the same number, written the way this build would write it.
        val stored = StoredSources.decode(
            json,
            """[ {"kind": "QOBUZ", "id": "q", "bytes": 21474836480, "gain": 1.50e3} , $LOCAL ]""",
        )!!
        assertEquals(listOf(MusicSource.local()), stored.readable)
        assertEquals(
            """[{"kind":"QOBUZ","id":"q","bytes":21474836480,"gain":1500.0},$LOCAL]""",
            stored.encode(json, stored.readable),
        )
    }

    @Test
    fun `nothing readable is an empty list, with what was stored still kept`() {
        val stored = StoredSources.decode(json, "[$UNKNOWN_KIND]")!!
        assertEquals(emptyList(), stored.readable)
        assertEquals("[$UNKNOWN_KIND,$LOCAL]", stored.encode(json, listOf(MusicSource.local())))
    }
}

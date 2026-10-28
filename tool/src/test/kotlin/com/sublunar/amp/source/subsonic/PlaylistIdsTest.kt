package com.sublunar.amp.source.subsonic

import com.sublunar.amp.source.serverJson
import kotlin.test.Test
import kotlin.test.assertEquals

/** getPlaylist read for its song ids alone, whatever else each entry carries. */
class PlaylistIdsTest {

    @Test
    fun `a playlist reply gives its song ids in order and nothing else`() {
        val reply = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","playlist":{"id":"p1","name":"Mix",
            "entry":[{"id":"a","title":"One","album":"X","duration":201,"coverArt":"al-1"},
                     {"id":"b","title":"Two","replayGain":{"trackGain":-6.1}}]}}}
        """.trimIndent()
        val body = serverJson.decodeFromString<PlaylistIdsEnvelope>(reply).response!!
        assertEquals("ok", body.status)
        assertEquals(listOf("a", "b"), body.playlist!!.entry.map { it.id })
    }

    @Test
    fun `a failed reply keeps its error`() {
        val reply = """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Playlist not found"}}}"""
        val body = serverJson.decodeFromString<PlaylistIdsEnvelope>(reply).response!!
        assertEquals("failed", body.status)
        assertEquals(70, body.error!!.code)
    }
}

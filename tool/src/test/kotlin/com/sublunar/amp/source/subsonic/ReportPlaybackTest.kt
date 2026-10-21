package com.sublunar.amp.source.subsonic

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.TimelineState
import com.sublunar.amp.data.promises
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * OpenSubsonic's reportPlayback: what one report says, and that only a
 * server which listed the playbackReport extension is ever sent one.
 */
class ReportPlaybackTest {

    @Test
    fun `a report names the song, where it is and what it is doing, and never counts as a play`() {
        assertEquals(
            listOf(
                "mediaId" to "song-7",
                "mediaType" to "song",
                "positionMs" to "61500",
                "state" to "playing",
                "playbackRate" to "1.0",
                // Amp submits the play itself once enough of it is heard; a
                // report that counted too would put every song in twice.
                "ignoreScrobble" to "true",
            ),
            reportPlaybackParams("song-7", TimelineState.PLAYING, 61_500),
        )
    }

    @Test
    fun `each state is said in OpenSubsonic's words`() {
        fun stateOf(state: TimelineState) = reportPlaybackParams("s", state, 0).toMap().getValue("state")
        assertEquals("playing", stateOf(TimelineState.PLAYING))
        assertEquals("paused", stateOf(TimelineState.PAUSED))
        assertEquals("stopped", stateOf(TimelineState.STOPPED))
        assertEquals("starting", stateOf(TimelineState.BUFFERING))
    }

    @Test
    fun `a place before the start is reported as the start`() {
        assertEquals("0", reportPlaybackParams("s", TimelineState.PAUSED, -40).toMap().getValue("positionMs"))
    }

    // As SubsonicClient reads every reply.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `the extension list is read by name`() {
        val reply = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
             "serverVersion":"0.59.0","openSubsonic":true,"openSubsonicExtensions":[
               {"name":"transcodeOffset","versions":[1]},
               {"name":"playbackReport","versions":[1]},
               {"versions":[1]}]}}
        """.trimIndent()
        val body = json.decodeFromString<SubsonicEnvelope>(reply).response!!
        assertEquals(listOf("transcodeOffset", "playbackReport"), body.extensionNames)
    }

    @Test
    fun `a list in a shape nobody expected costs nothing but the list`() {
        val reply = """{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonicExtensions":{"name":"x"}}}"""
        val body = json.decodeFromString<SubsonicEnvelope>(reply).response!!
        assertEquals("ok", body.status)
        assertEquals(emptyList(), body.extensionNames)
    }

    @Test
    fun `only a finished asking that found the call says the server takes it`() {
        val asked = ServerFeatures(checkedVersion = "1.16.1/navidrome/0.59.0", generation = ServerFeatures.GENERATION)
        assertTrue(asked.promises(ServerFeatures.REPORT_PLAYBACK))
        // Never asked, asked before the question existed, or left unfinished:
        // no — the opposite of what every other feature assumes.
        assertFalse(null.promises(ServerFeatures.REPORT_PLAYBACK))
        assertFalse(asked.copy(generation = 3).promises(ServerFeatures.REPORT_PLAYBACK))
        assertFalse(asked.copy(generation = 0).promises(ServerFeatures.REPORT_PLAYBACK))
        assertFalse(asked.copy(missing = listOf(ServerFeatures.REPORT_PLAYBACK)).promises(ServerFeatures.REPORT_PLAYBACK))
        // And a first asking puts the question.
        assertTrue(ServerFeatures.REPORT_PLAYBACK in ServerFeatures.ALL)
    }

    @Test
    fun `a server's client reports playback only once the server has listed it`() {
        fun clientFor(features: ServerFeatures?) = SubsonicProvider.createClient(
            MusicSource(
                id = "s",
                kind = SourceKind.SUBSONIC,
                name = "Server",
                baseUrl = "http://nas.example:4533",
                username = "me",
                password = "pw",
                features = features,
            ),
        ) as SubsonicClient
        val listed = ServerFeatures(checkedVersion = "1.16.1/navidrome/0.59.0", generation = ServerFeatures.GENERATION)
        assertTrue(clientFor(listed).reportsPlayback)
        assertFalse(clientFor(listed.copy(missing = listOf(ServerFeatures.REPORT_PLAYBACK))).reportsPlayback)
        assertFalse(clientFor(null).reportsPlayback)
    }
}

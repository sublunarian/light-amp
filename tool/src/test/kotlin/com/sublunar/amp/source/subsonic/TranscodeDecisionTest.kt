package com.sublunar.amp.source.subsonic

import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.source.serverJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString

/** Reading OpenSubsonic's `getTranscodeDecision`, in the shape its spec gives. */
class TranscodeDecisionTest {

    private fun decision(json: String): TranscodeDecisionDto? =
        serverJson.decodeFromString<SubsonicEnvelope>(json).response?.transcodeDecision

    private fun reply(canTranscode: Boolean, codec: String?) = """
        {"subsonic-response": {"status": "ok", "version": "1.16.1", "type": "navidrome", "openSubsonic": true,
          "transcodeDecision": {
            "canDirectPlay": false, "canTranscode": $canTranscode,
            "transcodeReason": ["AudioCodecNotSupported"], "errorReason": "",
            "sourceStream": {"protocol": "http", "container": "flac", "codec": "flac", "audioBitrate": 1000000},
            "transcodeStream": ${codec?.let { "{\"protocol\": \"http\", \"container\": \"ogg\", \"codec\": \"$it\", \"audioBitrate\": 192000}" } ?: "null"}
          }}}
    """.trimIndent()

    @Test
    fun `a server that would send the codec asked for makes that format`() {
        assertTrue(decision(reply(true, "opus"))!!.sends(StreamFormat.OPUS))
        assertTrue(decision(reply(true, "MP3"))!!.sends(StreamFormat.MP3))
    }

    @Test
    fun `a server that would send something else doesn't`() {
        // Navidrome with no FLAC encoder, say, falling back to Opus.
        assertFalse(decision(reply(true, "opus"))!!.sends(StreamFormat.MP3))
    }

    @Test
    fun `a server that can't transcode at all makes nothing`() {
        assertFalse(decision(reply(false, null))!!.sends(StreamFormat.OPUS))
    }

    @Test
    fun `a reply without a decision is no answer`() {
        assertEquals(null, decision("""{"subsonic-response": {"status": "ok", "version": "1.16.1"}}"""))
    }
}

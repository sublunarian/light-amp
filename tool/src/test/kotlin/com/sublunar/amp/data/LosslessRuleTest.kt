package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** The chosen quality is a ceiling: a lossy file within it plays as it is, anything else is converted down to it. */
class LosslessRuleTest {

    private fun track(codec: String, kbps: Int? = null) = Track(
        id = "t", title = "T", artist = "A", album = "B", albumArtist = "A", albumId = null,
        coverArtId = null, durationMs = 0, trackNumber = null, discNumber = null, year = null,
        playCount = 0, lastPlayedMs = 0, codec = codec, bitRateKbps = kbps,
    )

    @Test
    fun `formats sort into lossless, lossy and unknown`() {
        assertEquals(true, track("flac").isLossless())
        assertEquals(true, track("alac").isLossless())
        assertEquals(false, track("mp3").isLossless())
        assertEquals(false, track("opus").isLossless())
        assertEquals(null, track("").isLossless())
        // An MP4 file holds ALAC or AAC: told apart by bitrate, unknown without one.
        assertEquals(true, track("m4a", 900).isLossless())
        assertEquals(false, track("m4a", 256).isLossless())
        assertEquals(null, track("m4a").isLossless())
    }

    private val navidrome = MusicSource(id = "s", kind = SourceKind.SUBSONIC, name = "Navidrome")

    @Test
    fun `a lossless file is converted, a lossy one within the quality played as it is`() {
        assertEquals(StreamFormat.OPUS, navidrome.formatFor(track("flac", 900), StreamFormat.OPUS))
        assertEquals(StreamFormat.RAW, navidrome.formatFor(track("mp3", 128), StreamFormat.OPUS))
        assertEquals(StreamFormat.RAW, navidrome.formatFor(track("opus", 160), StreamFormat.MP3))
        assertEquals(StreamFormat.RAW, navidrome.formatFor(track("mp3", 320), StreamFormat.MP3))
        assertEquals(StreamFormat.RAW, navidrome.formatFor(track("mp3"), StreamFormat.RAW))
    }

    @Test
    fun `a lossy file above the quality is converted down to it`() {
        // The quality is a ceiling: Opus 192 means no song costs more.
        assertEquals(StreamFormat.OPUS, navidrome.formatFor(track("mp3", 320), StreamFormat.OPUS))
        assertEquals(StreamFormat.OPUS, navidrome.formatFor(track("aac", 256), StreamFormat.OPUS))
        // Jellyfin's MP3 ceiling is 256, so a 320 MP3 comes down to it.
        val jellyfin = MusicSource(id = "j", kind = SourceKind.JELLYFIN, name = "Jellyfin")
        assertEquals(StreamFormat.MP3, jellyfin.formatFor(track("mp3", 320), StreamFormat.MP3))
        assertEquals(StreamFormat.RAW, jellyfin.formatFor(track("mp3", 256), StreamFormat.MP3))
    }

    @Test
    fun `a lossy file of unknown bitrate goes by the choice`() {
        assertEquals(StreamFormat.OPUS, navidrome.formatFor(track("mp3"), StreamFormat.OPUS))
    }

    @Test
    fun `a file of unknown format goes by the choice`() {
        assertEquals(StreamFormat.OPUS, navidrome.formatFor(track(""), StreamFormat.OPUS))
    }

    @Test
    fun `where the original can't be had, the choice stands`() {
        // A server with no download call: a download can only be a conversion.
        val noDownload = navidrome.copy(features = ServerFeatures(missing = listOf(ServerFeatures.DOWNLOAD)))
        assertEquals(StreamFormat.OPUS, noDownload.formatFor(track("mp3", 128), StreamFormat.OPUS, download = true))
        assertEquals(StreamFormat.RAW, noDownload.formatFor(track("mp3", 128), StreamFormat.OPUS))
        // Bandcamp has one quality, original or not.
        val bandcamp = navidrome.copy(baseUrl = "https://bandcamp.com/api/subsonic")
        assertEquals(StreamFormat.MP3, bandcamp.formatFor(track("mp3", 245), StreamFormat.MP3))
    }
}

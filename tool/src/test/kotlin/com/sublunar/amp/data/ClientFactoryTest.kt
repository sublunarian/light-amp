package com.sublunar.amp.data

import com.sublunar.amp.source.local.LocalProvider
import com.sublunar.amp.source.subsonic.toSubsonicConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which client a source builds, and when it builds none: a source missing
 * what its server needs to be asked anything has no client at all, rather
 * than one that fails on every call.
 */
class ClientFactoryTest {

    /** Builds [source]'s client, hands it to [check], and closes it after. */
    private fun <T> clientOf(source: MusicSource, check: (MusicServer?) -> T): T {
        val client = source.toClient()
        try {
            return check(client)
        } finally {
            client?.close()
        }
    }

    private val subsonic = MusicSource(
        id = "s",
        kind = SourceKind.SUBSONIC,
        name = "Navidrome",
        baseUrl = "https://music.example.com",
        username = "armin",
        password = "pw",
    )

    @Test
    fun `a Subsonic source without an address has no client`() {
        val blank = subsonic.copy(baseUrl = "")
        clientOf(blank) { assertNull(it) }
        assertNull(blank.toSubsonicConfig())
    }

    @Test
    fun `a Subsonic source connects with its own login`() {
        clientOf(subsonic) {
            assertEquals(
                SubsonicConfig("https://music.example.com", "armin", "pw", null),
                assertIs<SubsonicClient>(it).config,
            )
        }
        clientOf(subsonic.copy(playerName = "Kitchen")) {
            assertEquals("Kitchen", assertIs<SubsonicClient>(it).config.clientName)
        }
    }

    @Test
    fun `only a Subsonic source has a Subsonic login`() {
        assertNull(subsonic.copy(kind = SourceKind.PLEX).toSubsonicConfig())
    }

    private val plex = MusicSource(
        id = "p",
        kind = SourceKind.PLEX,
        name = "Plex",
        baseUrl = "http://plex.example:32400",
        token = "tok",
    )

    @Test
    fun `a Plex source needs an address and a token`() {
        clientOf(plex) { assertIs<PlexClient>(it) }
        clientOf(plex.copy(baseUrl = "")) { assertNull(it) }
        clientOf(plex.copy(token = "")) { assertNull(it) }
    }

    private val jellyfin = MusicSource(
        id = "j",
        kind = SourceKind.JELLYFIN,
        name = "Jellyfin",
        baseUrl = "https://jf.example",
        token = "tok",
        userId = "u1",
    )

    @Test
    fun `a Jellyfin source needs an address, a token and a user`() {
        clientOf(jellyfin) { assertIs<JellyfinClient>(it) }
        clientOf(jellyfin.copy(baseUrl = "")) { assertNull(it) }
        clientOf(jellyfin.copy(token = "")) { assertNull(it) }
        clientOf(jellyfin.copy(userId = "")) { assertNull(it) }
    }

    @Test
    fun `a Jellyfin source signs its streams with the device it signed in as`() {
        clientOf(jellyfin) {
            val stream = assertIs<JellyfinClient>(it).streamUrl("t", StreamFormat.OPUS)
            assertTrue("DeviceId=com.sublunar.amp" in stream, stream)
        }
        clientOf(jellyfin.copy(deviceId = "amp-1")) {
            val stream = assertIs<JellyfinClient>(it).streamUrl("t", StreamFormat.OPUS)
            assertTrue("DeviceId=amp-1" in stream, stream)
        }
    }

    @Test
    fun `the phone's own music has no client`() {
        clientOf(LocalProvider.newSource()) { assertNull(it) }
    }
}

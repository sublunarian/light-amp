package com.sublunar.amp.data

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The parts of the Jellyfin mapping that are easy to get quietly wrong, pinned
 * against payloads shaped like the server's own.
 *
 * Every case here is a mistake that costs nothing at compile time and shows up
 * as a library that looks *nearly* right: durations out by a factor of ten
 * thousand, a compilation by nobody, covers that 404 one row at a time.
 */
class JellyfinParseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun item(body: String): JellyfinItem = json.decodeFromString(body)

    @Test
    fun `ticks are not milliseconds`() {
        val track = item(
            """{"Id":"a","Name":"Sixtyniner","RunTimeTicks":3170000000}""",
        ).toTrack()
        // 317 seconds, not 3,170,000 of them.
        assertEquals(317_000L, track.durationMs)
    }

    @Test
    fun `a track carries its own artist, not the record's`() {
        // The trap a compilation sets: every row's album artist is "Various
        // Artists", so a client reading that shows a shelf of songs by nobody.
        val track = item(
            """
            {
              "Id": "t1",
              "Name": "Kid for Today",
              "AlbumArtist": "Various Artists",
              "Artists": ["Boards of Canada"],
              "Album": "A Compilation"
            }
            """.trimIndent(),
        ).toTrack()
        assertEquals("Boards of Canada", track.artist)
        assertEquals("Various Artists", track.albumArtist)
    }

    @Test
    fun `a cover is claimed only where the item says it has one`() {
        // Jellyfin serves /Items/{anything}/Images/Primary and only 404s when
        // the bytes are asked for, so this cannot be assumed from the id alone.
        val withCover = item("""{"Id":"al","Name":"X","ImageTags":{"Primary":"abc"}}""")
        assertEquals("al", withCover.imageId())

        val withNone = item("""{"Id":"al","Name":"X"}""")
        assertNull(withNone.imageId())
    }

    @Test
    fun `a track with no cover of its own falls back to the album's`() {
        val track = item(
            """{"Id":"t1","Name":"X","AlbumId":"al1","AlbumPrimaryImageTag":"tag"}""",
        )
        assertEquals("al1", track.imageId())
    }

    @Test
    fun `favourites and play counts come off the user data`() {
        val track = item(
            """
            {
              "Id": "t1",
              "Name": "X",
              "UserData": { "PlayCount": 7, "IsFavorite": true, "LastPlayedDate": "2026-08-15T22:19:08.0000000Z" }
            }
            """.trimIndent(),
        ).toTrack()
        assertEquals(7, track.playCount)
        assertTrue(track.liked)
        assertTrue(track.lastPlayedMs > 0L)
    }

    @Test
    fun `a release date becomes a sortable number`() {
        assertEquals(20260815L, jellyfinDateNumber("2026-08-15T00:00:00.0000000Z"))
        assertEquals(0L, jellyfinDateNumber(null))
        assertEquals(0L, jellyfinDateNumber("nonsense"))
    }

    @Test
    fun `a missing date is zero rather than an exception`() {
        assertEquals(0L, jellyfinDateMs(null))
        assertEquals(0L, jellyfinDateMs(""))
        assertEquals(0L, jellyfinDateMs("2026"))
    }

    @Test
    fun `the item envelope carries the total, which is how paging ends`() {
        val page: JellyfinItems = json.decodeFromString(
            """{"Items":[{"Id":"a","Name":"A"}],"TotalRecordCount":1200}""",
        )
        assertEquals(1, page.items.size)
        assertEquals(1200, page.totalRecordCount)
    }

    @Test
    fun `only music views are offered as libraries`() {
        val views: JellyfinItems = json.decodeFromString(
            """
            {"Items":[
              {"Id":"1","Name":"Music","CollectionType":"music"},
              {"Id":"2","Name":"Films","CollectionType":"movies"},
              {"Id":"3","Name":"Songs","CollectionType":"MUSIC"}
            ]}
            """.trimIndent(),
        )
        val music = views.items.filter { it.collectionType.equals("music", ignoreCase = true) }
        assertEquals(listOf("1", "3"), music.map { it.id })
    }

    @Test
    fun `the authorization header names the client and carries the token`() {
        val anonymous = JellyfinClient.authorization()
        assertTrue(anonymous.startsWith("MediaBrowser "))
        assertTrue(anonymous.contains("""Client="Amp""""))
        // Sign-in happens before there is a token, and a Token="" would be sent
        // as a credential the server then rejects.
        assertTrue(!anonymous.contains("Token="))

        val named = JellyfinClient.authorization(product = "Kitchen", token = "abc123")
        assertTrue(named.contains("""Client="Kitchen""""))
        assertTrue(named.contains("""Token="abc123""""))
        // The device stays the hardware — two sources are still one phone.
        assertTrue(named.contains("""Device="Light Phone III""""))
    }

    @Test
    fun `a playlist entry keeps its own id, which is not the song's`() {
        val entry = item("""{"Id":"song1","Name":"X","PlaylistItemId":"entry9"}""")
        assertEquals("song1", entry.id)
        assertEquals("entry9", entry.playlistItemId)
    }

    @Test
    fun `synced lyrics keep their timings and unsynced ones do not`() {
        val synced: JellyfinLyrics = json.decodeFromString(
            """{"Lyrics":[{"Start":10000000,"Text":"one"},{"Start":25000000,"Text":"two"}]}""",
        )
        val lines = synced.lyrics.map { LyricLine(it.start?.div(TICKS_PER_MS), it.text) }
        assertEquals(listOf(1000L, 2500L), lines.map { it.timeMs })

        val plain: JellyfinLyrics = json.decodeFromString("""{"Lyrics":[{"Text":"one"}]}""")
        assertNull(plain.lyrics.first().start)
    }
}

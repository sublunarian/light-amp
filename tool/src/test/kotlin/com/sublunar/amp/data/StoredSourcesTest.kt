package com.sublunar.amp.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The sources list as the phone stores it, read and written through
 * [AppSettings] itself.
 *
 * Every source a user has set up lives in this one string, and an install
 * reads whatever an older or a newer build left there. So the bytes are
 * pinned, not only the records: a default moved in [MusicSource] or a field
 * renamed changes what every stored source means, and these say so before a
 * phone does.
 */
class StoredSourcesTest {

    /** A phone holding [prefs], and the settings that read it. */
    private class Phone(vararg prefs: Preferences.Pair<*>) {
        val store = MemoryDataStore(preferencesOf(*prefs))
        val settings = store.settings()
        val stored: String? get() = store.raw("pref.sources")
    }

    private fun list(vararg records: String) = records.joinToString(",", "[", "]")

    @Test
    fun `every kind reads back as the record it was saved as`() = runBlocking {
        val phone = Phone(SOURCES to list(*CANONICAL))
        assertEquals(
            listOf(subsonicFull, bandcamp, plex, plexOld, jellyfin, jellyfinOld, local, subsonicOld),
            phone.settings.sources.first(),
        )
    }

    @Test
    fun `an edit that changes nothing writes the same bytes`() = runBlocking {
        val phone = Phone(SOURCES to list(*CANONICAL))
        phone.settings.setSourceLibraryId("src-p1", null)
        assertEquals(list(*CANONICAL), phone.stored)
    }

    @Test
    fun `an older shape reads with today's defaults and is rewritten in today's shape`() = runBlocking {
        val phone = Phone(SOURCES to list(NON_CANONICAL))
        assertEquals(
            listOf(MusicSource("src-s1", SourceKind.SUBSONIC, "Reordered", baseUrl = "https://r.example")),
            phone.settings.sources.first(),
        )
        phone.settings.setSourceLibraryId("src-s1", null)
        assertEquals(
            list("""{"id":"src-s1","kind":"SUBSONIC","name":"Reordered","baseUrl":"https://r.example"}"""),
            phone.stored,
        )
    }

    @Test
    fun `a key this build doesn't know is ignored on read and kept on write`() = runBlocking {
        val phone = Phone(SOURCES to list(EXTRA_KEY))
        assertEquals(
            listOf(MusicSource("src-s2", SourceKind.SUBSONIC, "Extra", baseUrl = "https://x.example")),
            phone.settings.sources.first(),
        )
        phone.settings.setSourceLibraryId("src-s2", null)
        assertEquals(list(EXTRA_KEY), phone.stored)
        phone.settings.setSourceLibraryId("src-s2", "4")
        assertEquals(
            list(
                """{"id":"src-s2","kind":"SUBSONIC","name":"Extra","baseUrl":"https://x.example","libraryId":"4","futureField":{"a":1}}""",
            ),
            phone.stored,
        )
    }

    @Test
    fun `a known field set back to its default stays there, beside the unknown ones`() = runBlocking {
        val phone = Phone(
            SOURCES to list(
                """{"id":"src-s2","kind":"SUBSONIC","name":"Extra","baseUrl":"https://x.example","playerName":"Kitchen","futureField":{"a":1}}""",
            ),
        )
        phone.settings.saveSource(MusicSource("src-s2", SourceKind.SUBSONIC, "Extra", baseUrl = "https://x.example"))
        assertEquals(list(EXTRA_KEY), phone.stored)
    }

    @Test
    fun `only the record they came with keeps them`() = runBlocking {
        val phone = Phone(SOURCES to list(EXTRA_KEY))
        phone.settings.saveSource(local)
        assertEquals(list(EXTRA_KEY, LOCAL), phone.stored)
        // Removed and added again is a new record: what the old one carried
        // went with it.
        phone.settings.removeSource("src-s2")
        phone.settings.saveSource(MusicSource("src-s2", SourceKind.SUBSONIC, "Extra", baseUrl = "https://x.example"))
        assertEquals(
            list(LOCAL, """{"id":"src-s2","kind":"SUBSONIC","name":"Extra","baseUrl":"https://x.example"}"""),
            phone.stored,
        )
    }

    @Test
    fun `a stored list that isn't JSON reads as none, and adding a source starts a new one`() = runBlocking {
        val phone = Phone(SOURCES to "not json")
        assertEquals(emptyList(), phone.settings.sources.first())
        phone.settings.saveSource(local)
        assertEquals(list(LOCAL), phone.stored)
    }

    @Test
    fun `the first source is active until one is chosen`() = runBlocking {
        val sources = SOURCES to list(SUBSONIC_FULL, PLEX)
        assertEquals("server", Phone(sources).settings.activeSource.first()?.id)
        assertEquals("server", Phone(sources, ACTIVE_SOURCE to "gone").settings.activeSource.first()?.id)
        assertEquals("src-p1", Phone(sources, ACTIVE_SOURCE to "src-p1").settings.activeSource.first()?.id)
    }

    @Test
    fun `a record this build can't read hides only itself`() = runBlocking {
        for (unreadable in listOf(UNKNOWN_KIND, MISSING_NAME, NULL_FOR_STR)) {
            val phone = Phone(SOURCES to list(SUBSONIC_FULL, unreadable))
            assertEquals(listOf(subsonicFull), phone.settings.sources.first(), unreadable)
            assertEquals(subsonicFull, phone.settings.activeSource.first(), unreadable)
            phone.settings.saveSource(local)
            assertEquals(list(SUBSONIC_FULL, unreadable, LOCAL), phone.stored, unreadable)
        }
    }

    @Test
    fun `it keeps its place through an edit to the others`() = runBlocking {
        val phone = Phone(SOURCES to list(UNKNOWN_KIND, SUBSONIC_FULL, PLEX))
        phone.settings.setSourceLibraryId("src-p1", "7")
        assertEquals(
            list(UNKNOWN_KIND, SUBSONIC_FULL, PLEX.replace(""""libraries"""", """"libraryId":"7","libraries"""")),
            phone.stored,
        )
    }

    @Test
    fun `removing the source in front of it leaves it stored`() = runBlocking {
        val phone = Phone(SOURCES to list(SUBSONIC_FULL, UNKNOWN_KIND))
        phone.settings.removeSource("server")
        assertEquals(list(UNKNOWN_KIND), phone.stored)
    }

    @Test
    fun `the source chosen being unreadable shows the first readable one, and the choice stays`() = runBlocking {
        val phone = Phone(SOURCES to list(SUBSONIC_FULL, UNKNOWN_KIND), ACTIVE_SOURCE to "src-q1")
        assertEquals(subsonicFull, phone.settings.activeSource.first())
        assertEquals("src-q1", phone.store.raw("pref.activeSource"))
    }

    @Test
    fun `a list with nothing readable is empty, and adding a source keeps it`() = runBlocking {
        // The Welcome screen, as "[]" always was.
        val phone = Phone(SOURCES to list(UNKNOWN_KIND))
        assertEquals(emptyList(), phone.settings.sources.first())
        assertNull(phone.settings.activeSource.first())
        phone.settings.saveSource(local)
        assertEquals(list(UNKNOWN_KIND, LOCAL), phone.stored)
    }

    private val subsonicFull = MusicSource(
        id = "server",
        kind = SourceKind.SUBSONIC,
        name = "Navidrome",
        baseUrl = "https://music.example.com",
        username = "armin",
        password = "secret",
        streamFormatId = "flac",
        cellularFormatId = "mp3",
        downloadFormatId = "raw",
        offlineModeName = "FAVORITES",
        downloadLimitBytes = 21474836480L,
        playerName = "Kitchen",
        parserGeneration = 2,
        libraryId = "3",
        libraries = listOf(SourceLibrary("1", "Music"), SourceLibrary("3", "Kids"), SourceLibrary("5", "Live")),
        hiddenLibraryIds = listOf("5", null),
        features = ServerFeatures(checkedVersion = "1.16.1/navidrome/0.58.0", generation = 3),
    )

    private val bandcamp = MusicSource(
        id = "src-b1",
        kind = SourceKind.SUBSONIC,
        name = "Bandcamp",
        baseUrl = "https://bandcamp.com/api/subsonic",
        username = "fan123",
        password = "0123456789abcdef0123456789abcdef01234567",
        downloadFormatId = "mp3",
        features = ServerFeatures(
            checkedVersion = "1.16.1/BandcampServer/1.0",
            missing = listOf("getScanStatus", "setRating", "getSimilarSongs", "download"),
            generation = 3,
        ),
    )

    private val plex = MusicSource(
        id = "src-p1",
        kind = SourceKind.PLEX,
        name = "Living Room",
        baseUrl = "http://192.168.1.20:32400",
        streamFormatId = "mp3",
        token = "plex-token",
        machineIdentifier = "4f6c9a0e1b2d",
        deviceId = "amp-0001",
        libraries = listOf(SourceLibrary("7", "Music"), SourceLibrary("9", "Audiobooks")),
        hiddenLibraryIds = listOf("9"),
        connections = listOf("http://192.168.1.20:32400", "https://203-0-113-9.4f6c9a0e1b2d.plex.direct:32400"),
    )

    private val plexOld = MusicSource(
        id = "src-p0",
        kind = SourceKind.PLEX,
        name = "Plex",
        baseUrl = "http://10.0.0.2:32400",
        token = "t0",
        machineIdentifier = "m0",
    )

    private val jellyfin = MusicSource(
        id = "src-j1",
        kind = SourceKind.JELLYFIN,
        name = "Jellyfin",
        baseUrl = "https://jf.example",
        username = "armin",
        cellularFormatId = "opus",
        token = "jf-token",
        userId = "5f2b8c1d",
        deviceId = "amp-0002",
        libraries = listOf(SourceLibrary("a1", "Music")),
        features = ServerFeatures(missing = listOf("keepCopies")),
    )

    private val jellyfinOld = MusicSource(
        id = "src-j0",
        kind = SourceKind.JELLYFIN,
        name = "Jellyfin",
        baseUrl = "http://10.0.0.3:8096",
        username = "armin",
        token = "t1",
        userId = "u0",
    )

    private val local = MusicSource(id = "local", kind = SourceKind.LOCAL, name = "Local Music")

    private val subsonicOld = MusicSource(
        id = "src-s0",
        kind = SourceKind.SUBSONIC,
        name = "Old",
        baseUrl = "https://old.example",
        username = "u",
        password = "p",
        streamFormatId = "mp3",
    )

    companion object {
        // Each canonical record is exactly what today's encoder writes: keys in
        // constructor order, defaults left out.
        const val SUBSONIC_FULL =
            """{"id":"server","kind":"SUBSONIC","name":"Navidrome","baseUrl":"https://music.example.com","username":"armin","password":"secret","streamFormatId":"flac","cellularFormatId":"mp3","downloadFormatId":"raw","offlineModeName":"FAVORITES","downloadLimitBytes":21474836480,"playerName":"Kitchen","parserGeneration":2,"libraryId":"3","libraries":[{"id":"1","name":"Music"},{"id":"3","name":"Kids"},{"id":"5","name":"Live"}],"hiddenLibraryIds":["5",null],"features":{"checkedVersion":"1.16.1/navidrome/0.58.0","generation":3}}"""
        const val BANDCAMP =
            """{"id":"src-b1","kind":"SUBSONIC","name":"Bandcamp","baseUrl":"https://bandcamp.com/api/subsonic","username":"fan123","password":"0123456789abcdef0123456789abcdef01234567","downloadFormatId":"mp3","features":{"checkedVersion":"1.16.1/BandcampServer/1.0","missing":["getScanStatus","setRating","getSimilarSongs","download"],"generation":3}}"""
        const val PLEX =
            """{"id":"src-p1","kind":"PLEX","name":"Living Room","baseUrl":"http://192.168.1.20:32400","streamFormatId":"mp3","token":"plex-token","machineIdentifier":"4f6c9a0e1b2d","deviceId":"amp-0001","libraries":[{"id":"7","name":"Music"},{"id":"9","name":"Audiobooks"}],"hiddenLibraryIds":["9"],"connections":["http://192.168.1.20:32400","https://203-0-113-9.4f6c9a0e1b2d.plex.direct:32400"]}"""
        const val PLEX_OLD =
            """{"id":"src-p0","kind":"PLEX","name":"Plex","baseUrl":"http://10.0.0.2:32400","token":"t0","machineIdentifier":"m0"}"""
        const val JELLYFIN =
            """{"id":"src-j1","kind":"JELLYFIN","name":"Jellyfin","baseUrl":"https://jf.example","username":"armin","cellularFormatId":"opus","token":"jf-token","userId":"5f2b8c1d","deviceId":"amp-0002","libraries":[{"id":"a1","name":"Music"}],"features":{"missing":["keepCopies"]}}"""
        const val JELLYFIN_OLD =
            """{"id":"src-j0","kind":"JELLYFIN","name":"Jellyfin","baseUrl":"http://10.0.0.3:8096","username":"armin","token":"t1","userId":"u0"}"""
        const val LOCAL = """{"id":"local","kind":"LOCAL","name":"Local Music"}"""
        const val SUBSONIC_OLD =
            """{"id":"src-s0","kind":"SUBSONIC","name":"Old","baseUrl":"https://old.example","username":"u","password":"p","streamFormatId":"mp3"}"""

        val CANONICAL = arrayOf(SUBSONIC_FULL, BANDCAMP, PLEX, PLEX_OLD, JELLYFIN, JELLYFIN_OLD, LOCAL, SUBSONIC_OLD)

        /** Keys out of order, and defaults spelled out — readable, but not what is written. */
        const val NON_CANONICAL =
            """{"kind":"SUBSONIC","id":"src-s1","name":"Reordered","cellularFormatId":null,"playerName":"","parserGeneration":0,"hiddenLibraryIds":[],"baseUrl":"https://r.example"}"""

        /** A key a newer build might add. */
        const val EXTRA_KEY =
            """{"id":"src-s2","kind":"SUBSONIC","name":"Extra","baseUrl":"https://x.example","futureField":{"a":1}}"""

        // Records this build can't read: a kind a newer build added, a field
        // that can't be missing, and a null where a string has to be.
        const val UNKNOWN_KIND = """{"id":"src-q1","kind":"QOBUZ","name":"Qobuz","token":"q"}"""
        const val MISSING_NAME = """{"id":"src-n1","kind":"PLEX"}"""
        const val NULL_FOR_STR = """{"id":"src-z1","kind":"SUBSONIC","name":"Z","baseUrl":null}"""

        val SOURCES = stringPreferencesKey("pref.sources")
        val ACTIVE_SOURCE = stringPreferencesKey("pref.activeSource")
    }
}

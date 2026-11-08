package com.sublunar.amp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * The names everything is stored under, and the type each is stored as. An
 * install reads them back after every update, so a renamed key or a changed
 * type is a setting quietly lost — and the per-source ones carry the source's
 * id, which nothing may change either.
 *
 * Each test writes every key of one part of [AppSettings] and pins the whole
 * store, so a key added, renamed or dropped shows as a line of the map.
 */
class SettingsKeysTest {

    private val store = MemoryDataStore()
    private val settings = store.settings()

    @Test
    fun `the sources`() = runBlocking {
        settings.saveSource(MusicSource("src-1", SourceKind.LOCAL, "Local Music"))
        settings.setActiveSource("src-1")
        assertEquals(
            mapOf(
                "pref.sources" to """[{"id":"src-1","kind":"LOCAL","name":"Local Music"}]""",
                "pref.activeSource" to "src-1",
                // A first source settles the defaults — see ChangedDefaultsTest.
                "pref.layoutMode" to "SIMPLIFIED",
                "pref.artworkMode" to "NONE",
                "pref.artistAlbumOrder" to "DATE_RELEASED",
            ),
            store.all(),
        )
    }

    @Test
    fun `the library lists`() = runBlocking {
        settings.albumSort.set(AlbumSort.YEAR)
        settings.songSort.set(SongSort.ARTIST)
        settings.artistSort.set(ArtistSort.MOST_PLAYED)
        settings.playlistSort.set(PlaylistSort.NAME)
        settings.albumSortReversed.set(true)
        settings.songSortReversed.set(true)
        settings.artistSortReversed.set(true)
        settings.playlistSortReversed.set(true)
        settings.likedAlbumsOnly.set(true)
        settings.likedSongsOnly.set(true)
        settings.likedArtistsOnly.set(true)
        settings.albumsGenre.set("Jazz")
        settings.songsGenre.set("Folk")
        settings.bumpShuffleNonce()
        assertEquals(
            mapOf(
                "pref.albumSort" to "YEAR",
                "pref.songSort" to "ARTIST",
                "pref.artistSort" to "MOST_PLAYED",
                "pref.playlistSort" to "NAME",
                "pref.albumSortReversed" to true,
                "pref.songSortReversed" to true,
                "pref.artistSortReversed" to true,
                "pref.playlistSortReversed" to true,
                "pref.likedAlbumsOnly" to true,
                "pref.likedSongsOnly" to true,
                "pref.likedArtistsOnly" to true,
                "pref.albumsGenre" to "Jazz",
                "pref.songsGenre" to "Folk",
                "pref.shuffleNonce" to 1,
            ),
            store.all(),
        )
    }

    @Test
    fun `display and playback`() = runBlocking {
        settings.invertColors.set(true)
        settings.syncedLyrics.set(false)
        settings.latinLyrics.set(true)
        settings.layoutMode.set(LayoutMode.SIMPLIFIED)
        settings.lastSection.set(LastSection.NOW_PLAYING)
        settings.lastTab.set("SONGS")
        settings.albumGrid.set(true)
        settings.artistAlbumGrid.set(true)
        settings.hideArtistImages.set(false)
        settings.hideDownloadIcons.set(true)
        settings.artwork.set(ArtworkMode.NONE)
        assertEquals(
            mapOf(
                "pref.invertColors" to true,
                "pref.karaokeLyrics" to false,
                "pref.latinLyrics" to true,
                "pref.layoutMode" to "SIMPLIFIED",
                "pref.lastSection" to "NOW_PLAYING",
                "pref.lastTab" to "SONGS",
                "pref.albumGrid" to true,
                "pref.artistAlbumGrid" to true,
                "pref.hideArtistImages" to false,
                "pref.hideDownloadIcons" to true,
                "pref.artworkMode" to "NONE",
            ),
            store.all(),
        )
    }

    @Test
    fun `network and downloads`() = runBlocking {
        settings.dataMode.set(DataMode.WIFI_ONLY)
        settings.downloadLimit.set(5L shl 30)
        settings.downloadsPaused.set(true)
        assertEquals(
            mapOf(
                "pref.dataMode" to "WIFI_ONLY",
                "pref.downloadLimitBytes" to (5L shl 30),
                "pref.downloadsPaused" to true,
            ),
            store.all(),
        )
    }

    @Test
    fun `what is kept between launches`() = runBlocking {
        settings.saveQueue(listOf("t1", "t2"), index = 1, positionMs = 42_000L)
        settings.setPendingActions("[]")
        settings.setLastSyncedMs("src-1", 5)
        settings.setSongCheck("src-1", SongCheck(mark = "m"))
        settings.setCachedPlaylists("src-1", "[]")
        assertEquals(
            mapOf(
                "state.queueIds" to "t1,t2",
                "state.queueIndex" to 1,
                "state.positionMs" to 42_000L,
                "state.pendingActions" to "[]",
                "state.lastSynced.src-1" to "5",
                "state.songCheck.src-1" to """{"mark":"m"}""",
                "state.playlists.src-1" to "[]",
            ),
            store.all(),
        )
    }

    @Test
    fun `a removed source takes its own keys with it, and only its own`() = runBlocking {
        settings.saveSource(MusicSource("src-1", SourceKind.LOCAL, "Local Music"))
        settings.saveSource(MusicSource("src-2", SourceKind.SUBSONIC, "Home", baseUrl = "https://x.example"))
        settings.setActiveSource("src-1")
        settings.setPendingActions("[]")
        for (id in listOf("src-1", "src-2")) {
            settings.setLastSyncedMs(id, 5)
            settings.setSongCheck(id, SongCheck(mark = "m"))
            settings.setCachedPlaylists(id, "[]")
        }
        settings.removeSource("src-1")
        assertEquals(
            mapOf(
                // A fresh install's first source settles the new looks — see AppSettings.CHANGED_DEFAULTS.
                "pref.layoutMode" to "SIMPLIFIED",
                "pref.artworkMode" to "NONE",
                "pref.artistAlbumOrder" to "DATE_RELEASED",
                "pref.sources" to """[{"id":"src-2","kind":"SUBSONIC","name":"Home","baseUrl":"https://x.example"}]""",
                "state.pendingActions" to "[]",
                "state.lastSynced.src-2" to "5",
                "state.songCheck.src-2" to """{"mark":"m"}""",
                "state.playlists.src-2" to "[]",
            ),
            store.all(),
        )
    }
}

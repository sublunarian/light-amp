package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.ArtistRow
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.AppText
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.AlbumGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.components.SplitActionRow
import com.sublunar.amp.ui.components.listPadding
import com.sublunar.amp.ui.components.rememberGridAnchor
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberSelection
import com.sublunar.amp.ui.nSp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

/** All artists the user has hearted (opened from the Artists tab). */
class LikedArtistsScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val artists by App.library.likedArtists.collectAsState()

        LibrarySubPage {
            AppHeader(
                leftAction = HeaderAction(AppIcons.Sort) { go { ArtistsSortScreen(it) } },
                title = "Liked Artists",
                // Filled: this is the liked list, and the heart is the way out.
                secondaryLeftAction = HeaderAction(AppIcons.Favorite) { showAllArtists() },
                searchAction = { openLibrarySearch(withKeyboard = true) },
                rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
            )
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // Kept below the way out rather than instead of it: with nothing
                // liked yet, that row is the only thing on the page worth
                // tapping.
                if (artists.isEmpty()) {
                    item { EmptyState("No liked artists yet") }
                }
                items(artists, key = { it.name }) { artist ->
                    ArtistRow(
                        name = artist.name,
                        subtitle = "${artist.albumCount} albums · ${artist.trackCount} songs",
                        onClick = { go { ArtistDetailScreen(it, artist.name) } },
                        onLongClick = { go { ArtistActionsScreen(it, artist.name) } },
                    )
                }
            }
        }
    }
}

class ArtistDetailScreen(
    sealed: SealedLightActivity,
    private val name: String,
) : SimpleLightScreen<Unit>(sealed) {

    /**
     * The two ways into this artist's songs.
     *
     * All Songs holds its place whether or not the server has popular tracks to
     * offer — the list is fetched, and a button that slides sideways when the
     * answer arrives is worse than one that started where it belongs.
     */
    @Composable
    private fun SongShortcuts(hasPopular: Boolean) {
        SplitActionRow(
            leftIcon = AppIcons.FormatListBulleted,
            leftLabel = "All Songs",
            onLeft = { go { ArtistSongsScreen(it, name) } },
            rightIcon = if (hasPopular) AppIcons.Whatshot else null,
            rightLabel = "Popular",
            onRight = if (hasPopular) {
                { go { ArtistTopSongsScreen(it, name) } }
            } else {
                null
            },
        )
    }
    @Composable
    override fun Content() {
        val tracks by App.library.tracks.collectAsState()
        val albums by App.library.albums.collectAsState()
        val artistAlbums = remember(tracks, albums, name) { App.library.albumsForArtist(name) }
        val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()

        // Only offer Popular Songs when the server actually returns some (it needs
        // Last.fm configured on the Navidrome side).
        var hasPopular by remember(name) { mutableStateOf(false) }
        LaunchedEffect(name) {
            hasPopular = App.library.topSongsForArtist(name).isNotEmpty()
        }

        val grid = App.albumGrid.collectAsState().value

        LibrarySubPage {
            AppHeader(
                onBack = { goBack() },
                title = name,
                // The same list-or-grid menu the album lists have: a
                // discography is an album list too.
                onTitleClick = if (App.hideArtwork.collectAsState().value) {
                    null
                } else {
                    { go { AlbumViewScreen(it) } }
                },
                searchAction = { openLibrarySearch(withKeyboard = true) },
                rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
            )
            if (grid) {
                AlbumGrid(
                    albums = artistAlbums,
                    onOpen = { album -> go { AlbumDetailScreen(it, album.id) } },
                    onLongPress = { album -> go { AlbumActionsScreen(it, album.id) } },
                    // One anchor per artist, and one header item — the song
                    // shortcuts spanning the first row.
                    state = rememberGridAnchor("artist-albums:$name/grid", headerCount = 1),
                ) {
                    // Spans the row, so the two song shortcuts read as a header
                    // over the records rather than as the first one.
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SongShortcuts(hasPopular)
                    }
                }
                return@LibrarySubPage
            }
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                // Liking lives on the artist list's long-press, not here: this
                // page is the discography, and a heart at the top of it was the
                // one row that wasn't a way into the music.
                item { SongShortcuts(hasPopular) }
                // No Shuffle here: this page is a discography, and shuffling a
                // whole artist belongs on the song lists (All / Popular) instead.
                items(artistAlbums, key = { it.id }) { album ->
                    TrackRow(
                        title = album.title,
                        subtitle = album.year?.toString() ?: "",
                        coverArtId = album.coverArtId,
                        fallback = AppIcons.Album,
                        downloaded = album.id in downloadedAlbums,
                        onClick = { go { AlbumDetailScreen(it, album.id) } },
                        onLongClick = { go { AlbumActionsScreen(it, album.id) } },
                    )
                }
            }
        }
    }
}

/**
 * Long-press sheet for an artist: the one place an artist is liked.
 *
 * A heart on the artist page competed with the discography for the top of the
 * screen while being the one row there that didn't lead to music; here it is
 * where every other list keeps its per-item actions.
 */
class ArtistActionsScreen(
    sealed: SealedLightActivity,
    private val name: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val artists by App.library.artists.collectAsState()
        val liked = artists.firstOrNull { it.name == name }?.liked == true
        val source by App.source.collectAsState()

        ListScreen(onBack = { goBack() }, title = name) {
            ActionList {
                if (source.supportsLikes) {
                    TextRow(title = if (liked) "Unlike Artist" else "Like Artist") {
                        App.scope.launch { App.library.setArtistLiked(name, !liked) }
                        goBack()
                    }
                }
                TextRow(title = "All Songs") { go { ArtistSongsScreen(it, name) } }
            }
        }
    }
}

/** Back to the full artist list, from wherever in the stack this page sits. */
private fun SimpleLightScreen<*>.showAllArtists() {
    LibraryNav.setLiked(LibraryTab.ARTISTS, false)
    LibraryNav.selectTab(LibraryTab.ARTISTS)
    popToRoot()
}

/**
 * An artist's top tracks in popularity order. Deliberately just the top songs —
 * shuffling here shouldn't drop the whole discography into the queue.
 */
class ArtistTopSongsScreen(
    sealed: SealedLightActivity,
    private val name: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        var songs by remember(name) { mutableStateOf<List<Track>?>(null) }
        LaunchedEffect(name) { songs = App.library.topSongsForArtist(name) }
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()

        val selection = rememberSelection("artist-top:$name")

        LibrarySubPage {
            if (selection.active) {
                SelectionHeader(selection) {
                    openSelectionActions(selection.pick(songs.orEmpty()) { it.id }, selection)
                }
            } else {
                AppHeader(
                    onBack = { goBack() },
                    titleContent = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AppText("Popular Songs", nSp(18), lineHeight = nSp(21), maxLines = 1)
                            AppText(name, nSp(14), lineHeight = nSp(16), dim = true, maxLines = 1)
                        }
                    },
                    searchAction = { openLibrarySearch(withKeyboard = true) },
                    rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
                )
            }
            val list = songs
            when {
                list == null -> EmptyState("Loading…")
                list.isEmpty() -> EmptyState("No popular songs")
                else -> LazyColumn(
                    state = rememberListAnchor(
                        "artist-top:$name",
                        headerCount = if (selection.active) 0 else 1,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listPadding(),
                ) {
                    if (!selection.active) {
                        item {
                            SplitActionRow(
                                leftIcon = AppIcons.PlayArrow,
                                leftLabel = "Play",
                                onLeft = {
                                    App.playback.playQueue(list, 0)
                                    go { NowPlayingScreen(it) }
                                },
                                rightIcon = AppIcons.Shuffle,
                                rightLabel = "Shuffle",
                                onRight = {
                                    App.playback.playQueue(shuffled(list), 0)
                                    go { NowPlayingScreen(it) }
                                },
                            )
                        }
                    }
                    itemsIndexed(list, key = { _, t -> t.id }) { index, track ->
                        TrackRow(
                            title = track.title,
                            subtitle = track.album,
                            coverArtId = track.coverArtId,
                            downloaded = track.id in downloadedIds,
                            selected = if (selection.active) track.id in selection.selected else null,
                            onClick = {
                                if (selection.active) {
                                    selection.toggle(track.id)
                                } else {
                                    App.playback.playQueue(list, index)
                                    go { NowPlayingScreen(it) }
                                }
                            },
                            onLongClick = {
                                if (!selection.active) openTrackActions(track.id, selection)
                            },
                        )
                    }
                }
            }
        }
    }
}

class ArtistSongsScreen(
    sealed: SealedLightActivity,
    private val name: String,
) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val tracks by App.library.tracks.collectAsState()
        val songs = remember(tracks, name) { App.library.tracksForArtist(name) }
        val current by App.playback.currentTrack.collectAsState()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()

        val selection = rememberSelection("artist-songs:$name")

        LibrarySubPage {
            if (selection.active) {
                SelectionHeader(selection) {
                    openSelectionActions(selection.pick(songs) { it.id }, selection)
                }
            } else {
                AppHeader(
                    onBack = { goBack() },
                    title = name,
                    searchAction = { openLibrarySearch(withKeyboard = true) },
                    rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
                )
            }
            LazyColumn(
                state = rememberListAnchor(
                    "artist-songs:$name",
                    headerCount = if (selection.active) 0 else 1,
                ),
                modifier = Modifier.fillMaxSize(),
                contentPadding = listPadding(),
            ) {
                if (!selection.active) {
                    item {
                        SplitActionRow(
                            leftIcon = AppIcons.PlayArrow,
                            leftLabel = "Play",
                            onLeft = {
                                App.playback.playQueue(songs, 0)
                                go { NowPlayingScreen(it) }
                            },
                            rightIcon = AppIcons.Shuffle,
                            rightLabel = "Shuffle",
                            onRight = {
                                App.playback.playQueue(shuffled(songs), 0)
                                go { NowPlayingScreen(it) }
                            },
                        )
                    }
                }
                itemsIndexed(songs, key = { _, t -> t.id }) { index, track ->
                    TrackRow(
                        title = track.title,
                        subtitle = track.album,
                        coverArtId = track.coverArtId,
                        downloaded = track.id in downloadedIds,
                        selected = if (selection.active) track.id in selection.selected else null,
                        onClick = {
                            if (selection.active) {
                                selection.toggle(track.id)
                            } else {
                                App.playback.playQueue(songs, index)
                                go { NowPlayingScreen(it) }
                            }
                        },
                        onLongClick = { if (!selection.active) openTrackActions(track.id, selection) },
                    )
                }
            }
        }
    }
}

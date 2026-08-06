package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.sortAlbums
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.SplitActionRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.sublunar.amp.ui.components.AlbumGrid
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.ScrollableList
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

/** Liked Albums subpage (opened from the Albums tab). */
class AlbumsScreen(
    sealed: SealedLightActivity,
    private val liked: Boolean = true,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val likedAlbums by App.library.likedAlbums.collectAsState()
        val allAlbums by App.library.albums.collectAsState()
        // Held rather than re-read from the store — see App.songSort.
        val sort by App.albumSort.collectAsState()
        val reversed by App.albumSortReversed.collectAsState()
        val base = if (liked) likedAlbums else allAlbums
        val sorted = remember(base, sort, reversed) { sortAlbums(base, sort, reversed) }
        val downloadedAlbums by App.library.downloadedAlbumIds.collectAsState()
        val grid = App.albumGrid.collectAsState().value

        LibrarySubPage {
            // Sort takes the back button's place: the way out of a favourites
            // list is the "All Albums" row at the top of it, and sorting is what
            // you actually reach for while you're in one.
            AppHeader(
                leftAction = HeaderAction(AppIcons.Sort) { go { AlbumsSortScreen(it) } },
                title = if (liked) "Liked Albums" else "All Albums",
                // Filled: this is the liked list, and the heart is the way out.
                secondaryLeftAction = HeaderAction(
                    if (liked) AppIcons.Favorite else AppIcons.FavoriteBorder,
                ) {
                    if (liked) showAllAlbums() else showLikedAlbums()
                },
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
                    albums = sorted,
                    onOpen = { album -> go { AlbumDetailScreen(it, album.id) } },
                    onLongPress = { album -> go { AlbumActionsScreen(it, album.id) } },
                    // No anchor, so it opens at the top every time — matching
                    // the list on this page, which keeps no position either. A
                    // favourites page is somewhere you arrive at deliberately to
                    // see what is there; the tab's own grid is the one you scroll
                    // through and come back to, and that one remembers.
                    state = rememberLazyGridState(),
                )
            } else {
                ScrollableList(modifier = Modifier.fillMaxSize()) {
                    // Nothing above the list: the heart in the header is the way
                    // in and out of the liked view.
                    if (sorted.isEmpty()) {
                        item { EmptyState("No liked albums yet") }
                    }
                    items(sorted, key = { it.id }) { album ->
                        TrackRow(
                            title = album.title,
                            subtitle = album.artist,
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

    /** Whole albums, in the order shown or with the records shuffled. */
    private fun play(albumIds: List<String>, shuffle: Boolean) {
        App.scope.launch {
            val ids = if (shuffle) albumIds.shuffled() else albumIds
            val queue = App.library.albumQueue(ids)
            if (queue.isEmpty()) return@launch
            App.playback.playQueue(queue, 0)
            go { NowPlayingScreen(it) }
        }
    }
}

/** Into the liked list, for the page that shows everything. */
private fun SimpleLightScreen<*>.showLikedAlbums() {
    LibraryNav.setLiked(LibraryTab.ALBUMS, true)
    go { AlbumsScreen(it, liked = true) }
}

/** Back to the full album list, from wherever in the stack this page sits. */
private fun SimpleLightScreen<*>.showAllAlbums() {
    LibraryNav.setLiked(LibraryTab.ALBUMS, false)
    LibraryNav.selectTab(LibraryTab.ALBUMS)
    popToRoot()
}

fun albumSortLabel(sort: AlbumSort): String = when (sort) {
    AlbumSort.TITLE -> "Title"
    // Labels say exactly what the key is: this orders by album artist, not by
    // whoever is credited on a given track, and by the full release date.
    AlbumSort.ARTIST -> "Album Artist"
    AlbumSort.YEAR -> "Date Released"
    AlbumSort.DATE_ADDED -> "Recently Added"
    AlbumSort.RECENTLY_PLAYED -> "Recently Played"
    AlbumSort.MOST_PLAYED -> "Plays"
    AlbumSort.RATING -> "Rating"
    AlbumSort.RANDOM -> "Random"
}

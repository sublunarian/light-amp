package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.SongSort
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.data.sortSongs
import com.sublunar.amp.ui.components.AppHeader
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.EmptyState
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.SplitActionRow
import com.sublunar.amp.ui.components.SelectionHeader
import com.sublunar.amp.ui.components.TrackRow
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.rememberListAnchor
import com.sublunar.amp.ui.components.rememberSelection
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/** Liked Songs subpage (opened from the Songs tab). */
class SongsScreen(
    sealed: SealedLightActivity,
    private val liked: Boolean = true,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val likedTracks by App.library.likedTracks.collectAsState()
        val allTracks by App.library.tracks.collectAsState()
        // Held rather than re-read from the store — see App.songSort. Collected
        // straight from settings, the first frame sorts by the placeholder and
        // then re-sorts, which is the flicker on opening this page.
        val sort by App.songSort.collectAsState()
        val reversed by App.songSortReversed.collectAsState()
        val base = if (liked) likedTracks else allTracks
        val sorted = remember(base, sort, reversed) { sortSongs(base, sort, reversed) }
        val selection = rememberSelection(if (liked) "liked-songs" else "all-songs")
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()

        LibrarySubPage {
            if (selection.active) {
                SelectionHeader(selection) {
                    openSelectionActions(selection.pick(sorted) { it.id }, selection)
                }
            } else {
                AppHeader(
                    leftAction = HeaderAction(AppIcons.Sort) { go { SongsSortScreen(it) } },
                    title = if (liked) "Liked Songs" else "All Songs",
                    // Filled, because this *is* the liked list: pressing it is
                    // how you come back out of it.
                    secondaryLeftAction = HeaderAction(
                        if (liked) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    ) {
                        if (liked) showAllSongs() else showLikedSongs()
                    },
                    searchAction = { openLibrarySearch(withKeyboard = true) },
                    rightAction = HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } },
                )
            }
            ScrollableList(
                state = rememberListAnchor(
                    if (liked) "liked-songs" else "all-songs",
                    headerCount = if (selection.active) 0 else 1,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!selection.active) {
                    item {
                        SplitActionRow(
                            leftIcon = AppIcons.PlayArrow,
                            leftLabel = "Play",
                            onLeft = {
                                App.playback.playQueue(sorted, 0)
                                go { NowPlayingScreen(it) }
                            },
                            rightIcon = AppIcons.Shuffle,
                            rightLabel = "Shuffle",
                            onRight = {
                                App.playback.playQueue(shuffled(sorted), 0)
                                go { NowPlayingScreen(it) }
                            },
                        )
                    }
                }
                if (sorted.isEmpty()) {
                    item { EmptyState("No liked songs yet") }
                }
                itemsIndexed(sorted, key = { _, t -> t.id }) { index, track ->
                    TrackRow(
                        title = track.title,
                        subtitle = track.artist,
                        coverArtId = track.coverArtId,
                        downloaded = track.id in downloadedIds,
                        selected = if (selection.active) track.id in selection.selected else null,
                        onClick = {
                            if (selection.active) {
                                selection.toggle(track.id)
                            } else {
                                App.playback.playQueue(sorted, index)
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

/** Into the liked list, for the page that shows everything. */
private fun SimpleLightScreen<*>.showLikedSongs() {
    LibraryNav.setLiked(LibraryTab.SONGS, true)
    go { SongsScreen(it, liked = true) }
}

/** Back to the full song list, from wherever in the stack this page sits. */
private fun SimpleLightScreen<*>.showAllSongs() {
    LibraryNav.setLiked(LibraryTab.SONGS, false)
    LibraryNav.selectTab(LibraryTab.SONGS)
    popToRoot()
}

fun songSortLabel(sort: SongSort): String = when (sort) {
    SongSort.TITLE -> "Title"
    SongSort.ARTIST -> "Artist"
    SongSort.DATE_ADDED -> "Recently Added"
    SongSort.RECENTLY_PLAYED -> "Recently Played"
    SongSort.MOST_PLAYED -> "Plays"
    SongSort.RATING -> "Rating"
}

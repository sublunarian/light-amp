package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.ScrollAnchors
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.ArtistSort
import com.sublunar.amp.data.PlaylistSort
import com.sublunar.amp.data.SongSort
import com.sublunar.amp.data.descendingByNature
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.n
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

private val ALBUM_SORT_OPTIONS = AlbumSort.entries.toList()
private val SONG_SORT_OPTIONS = SongSort.entries.toList()
private val ARTIST_SORT_OPTIONS = ArtistSort.entries.toList()
private val PLAYLIST_SORT_OPTIONS =
    listOf(PlaylistSort.NAME, PlaylistSort.DATE_CREATED, PlaylistSort.RECENTLY_UPDATED)

/**
 * Shared body for every "Sort by" menu.
 *
 * Picking a different option applies it and closes; tapping the already-selected
 * option flips the sort direction and also closes, so either way a tap takes you
 * straight back to the reordered list. The arrow beside the selected option shows
 * which direction is in effect when the menu is reopened.
 */
@Composable
private fun <T> SortOptions(
    options: List<T>,
    current: T,
    reversed: Boolean,
    label: (T) -> String,
    naturallyDescending: (T) -> Boolean,
    onSelect: (T) -> Unit,
    onFlip: () -> Unit,
    onBack: () -> Unit,
) {
    ListScreen(onBack = onBack, title = "Sort by") {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(options) { option ->
                val selected = option == current
                TextRow(
                    title = label(option),
                    onClick = { if (selected) onFlip() else onSelect(option) },
                    trailing = {
                        if (selected) {
                            // `reversed` inverts the option's natural direction.
                            val descending = naturallyDescending(option) != reversed
                            AppIcon(
                                if (descending) AppIcons.ArrowDownward else AppIcons.ArrowUpward,
                                size = n(18),
                            )
                        }
                    },
                )
            }
        }
    }
}

class AlbumsSortScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val current by App.settings.albumSort.collectAsState(initial = AlbumSort.TITLE)
        val reversed by App.settings.albumSortReversed.collectAsState(initial = false)
        SortOptions(
            options = ALBUM_SORT_OPTIONS,
            current = current,
            reversed = reversed,
            label = ::albumSortLabel,
            naturallyDescending = { it.descendingByNature },
            onSelect = { option ->
                App.scope.launch {
                    App.settings.setAlbumSort(option)
                    App.settings.setAlbumSortReversed(false)
                }
                // A new order makes the old position meaningless: the index you
                // were at is a different record now, so the list would open
                // part-way down at something you never chose. Sorting is a
                // request to look at the shelf afresh.
                ScrollAnchors.clear("tab:albums", "albums")
                goBack()
            },
            onFlip = {
                App.scope.launch { App.settings.setAlbumSortReversed(!reversed) }
                ScrollAnchors.clear("tab:albums", "albums")
                goBack()
            },
            onBack = { goBack() },
        )
    }
}

class SongsSortScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.songSort.collectAsState(initial = SongSort.TITLE)
        val reversed by App.settings.songSortReversed.collectAsState(initial = false)
        SortOptions(
            options = SONG_SORT_OPTIONS,
            current = current,
            reversed = reversed,
            label = ::songSortLabel,
            naturallyDescending = { it.descendingByNature },
            onSelect = { option ->
                App.scope.launch {
                    App.settings.setSongSort(option)
                    App.settings.setSongSortReversed(false)
                }
                // A new order makes the old position meaningless: the index you
                // were at is a different record now, so the list would open
                // part-way down at something you never chose. Sorting is a
                // request to look at the shelf afresh.
                ScrollAnchors.clear("tab:songs")
                goBack()
            },
            onFlip = {
                App.scope.launch { App.settings.setSongSortReversed(!reversed) }
                ScrollAnchors.clear("tab:songs")
                goBack()
            },
            onBack = { goBack() },
        )
    }
}

class ArtistsSortScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.artistSort.collectAsState(initial = ArtistSort.NAME)
        val reversed by App.settings.artistSortReversed.collectAsState(initial = false)
        SortOptions(
            options = ARTIST_SORT_OPTIONS,
            current = current,
            reversed = reversed,
            label = ::artistSortLabel,
            naturallyDescending = { it.descendingByNature },
            onSelect = { option ->
                App.scope.launch {
                    App.settings.setArtistSort(option)
                    App.settings.setArtistSortReversed(false)
                }
                // A new order makes the old position meaningless: the index you
                // were at is a different record now, so the list would open
                // part-way down at something you never chose. Sorting is a
                // request to look at the shelf afresh.
                ScrollAnchors.clear("tab:artists")
                goBack()
            },
            onFlip = {
                App.scope.launch { App.settings.setArtistSortReversed(!reversed) }
                ScrollAnchors.clear("tab:artists")
                goBack()
            },
            onBack = { goBack() },
        )
    }
}

class PlaylistsSortScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.playlistSort.collectAsState(initial = PlaylistSort.RECENTLY_UPDATED)
        val reversed by App.settings.playlistSortReversed.collectAsState(initial = false)
        SortOptions(
            options = PLAYLIST_SORT_OPTIONS,
            current = current,
            reversed = reversed,
            label = ::playlistSortLabel,
            naturallyDescending = { it.descendingByNature },
            onSelect = { option ->
                App.scope.launch {
                    App.settings.setPlaylistSort(option)
                    App.settings.setPlaylistSortReversed(false)
                }
                // A new order makes the old position meaningless: the index you
                // were at is a different record now, so the list would open
                // part-way down at something you never chose. Sorting is a
                // request to look at the shelf afresh.
                ScrollAnchors.clear("tab:playlists")
                goBack()
            },
            onFlip = {
                App.scope.launch { App.settings.setPlaylistSortReversed(!reversed) }
                ScrollAnchors.clear("tab:playlists")
                goBack()
            },
            onBack = { goBack() },
        )
    }
}

fun artistSortLabel(sort: ArtistSort): String = when (sort) {
    ArtistSort.NAME -> "Name"
    ArtistSort.MOST_PLAYED -> "Plays"
}

fun playlistSortLabel(sort: PlaylistSort): String = when (sort) {
    PlaylistSort.NAME -> "Name"
    PlaylistSort.DATE_CREATED -> "Date Created"
    PlaylistSort.RECENTLY_UPDATED -> "Recently Updated"
}

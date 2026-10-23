package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.ArtistSort
import com.sublunar.amp.data.PlaylistSort
import com.sublunar.amp.data.SongSort
import com.sublunar.amp.data.descendingByNature
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollAnchors
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.launch

private val ALBUM_SORT_OPTIONS = AlbumSort.entries.toList()
private val SONG_SORT_OPTIONS = SongSort.entries.toList()
private val ARTIST_SORT_OPTIONS = ArtistSort.entries.toList()
private val PLAYLIST_SORT_OPTIONS =
    listOf(PlaylistSort.NAME, PlaylistSort.DATE_CREATED, PlaylistSort.RECENTLY_UPDATED)

/**
 * The page a sort menu belongs to, named under the menu's own "Sort by" heading as
 * Filter and View do, with its narrowing, so sorting a filtered list says so.
 */
@Composable
private fun sortedPage(tab: LibraryTab): String = tabTitle(tab, likedOnly(tab))

/**
 * Narrow a list to one genre, or to one composer. The values are the library's own
 * (LibraryRepository.genres), so each leads somewhere. "All" clears the narrowing.
 * Genre and composer are separate settings and combine.
 */
class TagFilterScreen(
    sealed: SealedLightActivity,
    private val tab: LibraryTab,
    private val byComposer: Boolean,
) : SimpleLightScreen<Unit>(sealed) {
    // While casting, the rocker belongs to the speaker (see handleVolumeKey).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val values by (if (byComposer) App.library.composers else App.library.genres)
            .collectAsState()
        val filter = tagFilter(tab)
        val current = if (byComposer) filter.composer else filter.genre
        ListScreen(
            onBack = { goBack() },
            title = if (byComposer) "Composer" else "Genre",
            subtitle = tab.title,
        ) {
            ScrollableList(modifier = Modifier.fillMaxSize()) {
                item { Choice("All", chosen = current.isEmpty()) { choose("") } }
                items(values) { value ->
                    Choice(value, chosen = value.equals(current, ignoreCase = true)) {
                        choose(value)
                    }
                }
            }
        }
    }

    @Composable
    private fun Choice(label: String, chosen: Boolean, onClick: () -> Unit) {
        TextRow(
            title = label,
            onClick = onClick,
            trailing = { if (chosen) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
        )
    }

    private fun choose(value: String) {
        App.scope.launch {
            when {
                tab == LibraryTab.ALBUMS && byComposer -> App.settings.setAlbumsComposer(value)
                tab == LibraryTab.ALBUMS -> App.settings.setAlbumsGenre(value)
                tab == LibraryTab.SONGS && byComposer -> App.settings.setSongsComposer(value)
                tab == LibraryTab.SONGS -> App.settings.setSongsGenre(value)
                else -> Unit
            }
        }
        // A narrowed list is the point of the tap, so leave with the answer, as
        // the sort pickers do (see MoreScreen.change).
        goBack(Unit)
    }
}

/**
 * Shared body for every "Sort by" menu. Picking a different option applies it and
 * closes. Tapping the selected one flips the direction (or deals a new shuffle for
 * Random) and closes too, and the label up top says so, since nothing else hints at
 * it. The arrow shows the direction in effect, Random has none.
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
    /** The page being sorted, named under the heading (see [sortedPage]). */
    page: String?,
    /** The option that shuffles rather than orders, where the menu has one. */
    isRandom: (T) -> Boolean = { false },
) {
    ListScreen(onBack = onBack, title = "Sort by", subtitle = page) {
        ScrollableList(modifier = Modifier.fillMaxSize()) {
            item {
                SectionLabel(
                    if (isRandom(current)) {
                        "Tap Random again for a new shuffle"
                    } else {
                        "Tap the current sort again to reverse it"
                    },
                )
            }
            items(options) { option ->
                val selected = option == current
                TextRow(
                    title = label(option),
                    onClick = { if (selected) onFlip() else onSelect(option) },
                    trailing = {
                        if (selected && !isRandom(option)) {
                            // `reversed` inverts the option's natural direction.
                            val descending = naturallyDescending(option) != reversed
                            AppIcon(
                                if (descending) AppIcons.ArrowDownward else AppIcons.ArrowUpward,
                                size = px(46),
                            )
                        }
                    },
                )
            }
        }
    }
}

class AlbumsSortScreen(
    sealed: SealedLightActivity,
) : SimpleLightScreen<Unit>(sealed) {
    // While casting, the rocker belongs to the speaker (see handleVolumeKey).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val current by App.settings.albumSort.collectAsState(initial = AlbumSort.TITLE)
        val reversed by App.settings.albumSortReversed.collectAsState(initial = false)
        // No Rating where nothing can be rated (all zeros order nothing), except
        // while it is the current sort, so a source switch can't hide the order
        // in effect.
        val supportsRatings = App.source.collectAsState().value.supportsRatings
        SortOptions(
            options = ALBUM_SORT_OPTIONS.filter {
                it != AlbumSort.RATING || supportsRatings || it == current
            },
            current = current,
            reversed = reversed,
            label = ::albumSortLabel,
            naturallyDescending = { it.descendingByNature },
            isRandom = { it == AlbumSort.RANDOM },
            onSelect = { option ->
                App.scope.launch {
                    App.settings.setAlbumSort(option)
                    App.settings.setAlbumSortReversed(false)
                }
                // A new order makes the old position meaningless: the list opens
                // at the top.
                ScrollAnchors.clear("tab:albums")
                goBack(Unit)
            },
            onFlip = {
                App.scope.launch {
                    if (current == AlbumSort.RANDOM) {
                        // A shuffle has no direction: the second tap deals again.
                        // Reversed is cleared too, for a Random flipped before
                        // it stopped taking a direction.
                        App.settings.bumpShuffleNonce()
                        App.settings.setAlbumSortReversed(false)
                    } else {
                        App.settings.setAlbumSortReversed(!reversed)
                    }
                }
                ScrollAnchors.clear("tab:albums")
                goBack(Unit)
            },
            onBack = { goBack() },
            page = sortedPage(LibraryTab.ALBUMS),
        )
    }
}

class SongsSortScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val current by App.settings.songSort.collectAsState(initial = SongSort.TITLE)
        val reversed by App.settings.songSortReversed.collectAsState(initial = false)
        // Same gate as the albums menu (see AlbumsSortScreen).
        val supportsRatings = App.source.collectAsState().value.supportsRatings
        SortOptions(
            options = SONG_SORT_OPTIONS.filter {
                it != SongSort.RATING || supportsRatings || it == current
            },
            current = current,
            reversed = reversed,
            label = ::songSortLabel,
            naturallyDescending = { it.descendingByNature },
            onSelect = { option ->
                App.scope.launch {
                    App.settings.setSongSort(option)
                    App.settings.setSongSortReversed(false)
                }
                // A new order makes the old position meaningless.
                ScrollAnchors.clear("tab:songs")
                goBack(Unit)
            },
            onFlip = {
                App.scope.launch { App.settings.setSongSortReversed(!reversed) }
                ScrollAnchors.clear("tab:songs")
                goBack(Unit)
            },
            onBack = { goBack() },
            page = sortedPage(LibraryTab.SONGS),
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
                // A new order makes the old position meaningless.
                ScrollAnchors.clear("tab:artists")
                goBack(Unit)
            },
            onFlip = {
                App.scope.launch { App.settings.setArtistSortReversed(!reversed) }
                ScrollAnchors.clear("tab:artists")
                goBack(Unit)
            },
            onBack = { goBack() },
            page = sortedPage(LibraryTab.ARTISTS),
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
                // A new order makes the old position meaningless.
                ScrollAnchors.clear("tab:playlists")
                goBack(Unit)
            },
            onFlip = {
                App.scope.launch { App.settings.setPlaylistSortReversed(!reversed) }
                ScrollAnchors.clear("tab:playlists")
                goBack(Unit)
            },
            onBack = { goBack() },
            page = sortedPage(LibraryTab.PLAYLISTS),
        )
    }
}

fun artistSortLabel(sort: ArtistSort): String = when (sort) {
    ArtistSort.NAME -> "Name"
    ArtistSort.MOST_PLAYED -> "Frequently Played"
}

fun playlistSortLabel(sort: PlaylistSort): String = when (sort) {
    PlaylistSort.NAME -> "Name"
    PlaylistSort.DATE_CREATED -> "Date Created"
    PlaylistSort.RECENTLY_UPDATED -> "Recently Updated"
}


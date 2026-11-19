package com.sublunar.amp.ui.screens

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sublunar.amp.App
import com.sublunar.amp.data.AlbumSort
import com.sublunar.amp.data.ArtistSort
import com.sublunar.amp.data.PlaylistSort
import com.sublunar.amp.data.Pref
import com.sublunar.amp.data.SongSort
import com.sublunar.amp.data.descendingByNature
import com.sublunar.amp.data.hasPlaylistTypes
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.ScrollAnchors
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.SectionLabel
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.px
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.launch

private val ALBUM_SORT_OPTIONS = AlbumSort.entries.toList()
private val SONG_SORT_OPTIONS = SongSort.entries.toList()
private val ARTIST_SORT_OPTIONS = ArtistSort.entries.toList()
private val PLAYLIST_SORT_OPTIONS = PlaylistSort.entries.toList()

/**
 * Narrow a list to one genre. The values are the library's own
 * (LibraryRepository.genres), so each leads somewhere. "All" clears the narrowing.
 */
class TagFilterScreen(
    sealed: SealedLightActivity,
    private val tab: LibraryTab,
) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val values by App.library.genres.collectAsState()
        val current = tagFilter(tab).genre
        ListScreen(
            onBack = { goBack() },
            title = "Genre",
            subtitle = tab.title,
            headingRows = true,
        ) {
            ScrollableList {
                item { TextRow(title = "All", checked = current.isEmpty()) { choose("") } }
                items(values) { value ->
                    TextRow(title = value, checked = value.equals(current, ignoreCase = true)) {
                        choose(value)
                    }
                }
            }
        }
    }

    private fun choose(value: String) {
        App.scope.launch {
            when (tab) {
                LibraryTab.ALBUMS -> App.settings.albumsGenre.set(value)
                LibraryTab.SONGS -> App.settings.songsGenre.set(value)
                else -> Unit
            }
        }
        // Back to the More page's Filters, where another may be set too.
        goBack(Unit)
    }
}

/**
 * A tab's "Sort by" menu. Picking a different option applies it and closes.
 * Tapping the selected one flips the direction and closes too, and the label up
 * top says so, since nothing else hints at it. The arrow shows the direction in
 * effect.
 */
class SortScreen(
    sealed: SealedLightActivity,
    private val tab: LibraryTab,
) : AmpScreen<Unit>(sealed) {
    @Composable
    override fun Content() {
        val settings = App.settings
        when (tab) {
            LibraryTab.ALBUMS -> SortMenu(
                ALBUM_SORT_OPTIONS, settings.albumSort, settings.albumSortReversed, ::albumSortLabel,
                naturallyDescending = { it.descendingByNature },
                rating = AlbumSort.RATING,
            )
            LibraryTab.SONGS -> SortMenu(
                SONG_SORT_OPTIONS, settings.songSort, settings.songSortReversed, ::songSortLabel,
                naturallyDescending = { it.descendingByNature },
                rating = SongSort.RATING,
            )
            LibraryTab.ARTISTS -> SortMenu(
                ARTIST_SORT_OPTIONS, settings.artistSort, settings.artistSortReversed, ::artistSortLabel,
                naturallyDescending = { false },
            )
            LibraryTab.PLAYLISTS -> {
                val playlists by App.playlists.allPlaylists.collectAsState()
                val types = hasPlaylistTypes(playlists)
                SortMenu(
                    PLAYLIST_SORT_OPTIONS, settings.playlistSort, settings.playlistSortReversed, ::playlistSortLabel,
                    naturallyDescending = { it.descendingByNature },
                    offered = { it != PlaylistSort.TYPE || types },
                )
            }
        }
    }

    @Composable
    private fun <T> SortMenu(
        options: List<T>,
        sort: Pref<T>,
        reversed: Pref<Boolean>,
        label: (T) -> String,
        naturallyDescending: (T) -> Boolean,
        /** The order by rating, where the menu has one (see below). */
        rating: T? = null,
        /** Whether this list has any use for an option. */
        offered: (T) -> Boolean = { true },
    ) {
        val current by sort.state.collectAsState()
        val isReversed by reversed.state.collectAsState()
        // No Rating where nothing can be rated (all zeros order nothing). An option
        // stays while it is the current sort, so a source switch can't hide the
        // order in effect.
        val supportsRatings = App.source.collectAsState().value.supportsRatings
        val shown = options.filter { it == current || (offered(it) && (it != rating || supportsRatings)) }

        // The page being sorted is named under the heading, as Filter and View do,
        // with its narrowing, so sorting a filtered list says so.
        ListScreen(
            onBack = { goBack() },
            title = "Sort by",
            subtitle = tabTitle(tab, likedOnly(tab)),
            headingRows = true,
        ) {
            ScrollableList {
                item { SectionLabel("Tap the current sort again to reverse it") }
                items(shown) { option ->
                    val selected = option == current
                    TextRow(
                        title = label(option),
                        onClick = {
                            App.scope.launch {
                                if (selected) {
                                    reversed.set(!isReversed)
                                } else {
                                    sort.set(option)
                                    reversed.set(false)
                                }
                            }
                            // A new order makes the old position meaningless: the
                            // list opens at the top.
                            ScrollAnchors.clear("tab:${tab.name.lowercase()}")
                            goBack(Unit)
                        },
                        trailing = {
                            if (selected) {
                                // `reversed` inverts the option's natural direction.
                                val descending = naturallyDescending(option) != isReversed
                                AppIcon(
                                    if (descending) AppIcons.ArrowDownward else AppIcons.ArrowUpward,
                                    size = px(SORT_ARROW_PX),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

fun albumSortLabel(sort: AlbumSort): String = when (sort) {
    AlbumSort.TITLE -> "Title"
    AlbumSort.ARTIST -> "Artist"
    AlbumSort.YEAR -> "Date Released"
    AlbumSort.DATE_ADDED -> "Recently Added"
    AlbumSort.RECENTLY_PLAYED -> "Recently Played"
    AlbumSort.RATING -> "Rating"
}

fun songSortLabel(sort: SongSort): String = when (sort) {
    SongSort.TITLE -> "Title"
    SongSort.ARTIST -> "Artist"
    SongSort.DATE_ADDED -> "Recently Added"
    SongSort.RECENTLY_PLAYED -> "Recently Played"
    SongSort.RATING -> "Rating"
}

fun artistSortLabel(sort: ArtistSort): String = when (sort) {
    ArtistSort.NAME -> "Album Artist"
    ArtistSort.ARTIST -> "Track Artist"
}

fun playlistSortLabel(sort: PlaylistSort): String = when (sort) {
    PlaylistSort.NAME -> "Title"
    PlaylistSort.DATE_CREATED -> "Recently Added"
    PlaylistSort.RECENTLY_PLAYED -> "Recently Played"
    PlaylistSort.RECENTLY_UPDATED -> "Recently Updated"
    PlaylistSort.TYPE -> "Playlist Type"
}

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
private val PLAYLIST_SORT_OPTIONS =
    listOf(PlaylistSort.NAME, PlaylistSort.DATE_CREATED, PlaylistSort.RECENTLY_UPDATED)

/**
 * Narrow a list to one genre, or to one composer. The values are the library's own
 * (LibraryRepository.genres), so each leads somewhere. "All" clears the narrowing.
 * Genre and composer are separate settings and combine.
 */
class TagFilterScreen(
    sealed: SealedLightActivity,
    private val tab: LibraryTab,
    private val byComposer: Boolean,
) : AmpScreen<Unit>(sealed) {
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
            when {
                tab == LibraryTab.ALBUMS && byComposer -> App.settings.albumsComposer.set(value)
                tab == LibraryTab.ALBUMS -> App.settings.albumsGenre.set(value)
                tab == LibraryTab.SONGS && byComposer -> App.settings.songsComposer.set(value)
                tab == LibraryTab.SONGS -> App.settings.songsGenre.set(value)
                else -> Unit
            }
        }
        // A narrowed list is the point of the tap, so leave with the answer, as
        // the sort pickers do (see MoreScreen.change).
        goBack(Unit)
    }
}

/**
 * A tab's "Sort by" menu. Picking a different option applies it and closes.
 * Tapping the selected one flips the direction (or deals a new shuffle for
 * Random) and closes too, and the label up top says so, since nothing else hints
 * at it. The arrow shows the direction in effect, Random has none.
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
                random = AlbumSort.RANDOM,
            )
            LibraryTab.SONGS -> SortMenu(
                SONG_SORT_OPTIONS, settings.songSort, settings.songSortReversed, ::songSortLabel,
                naturallyDescending = { it.descendingByNature },
                rating = SongSort.RATING,
            )
            LibraryTab.ARTISTS -> SortMenu(
                ARTIST_SORT_OPTIONS, settings.artistSort, settings.artistSortReversed, ::artistSortLabel,
                naturallyDescending = { it.descendingByNature },
            )
            LibraryTab.PLAYLISTS -> SortMenu(
                PLAYLIST_SORT_OPTIONS, settings.playlistSort, settings.playlistSortReversed, ::playlistSortLabel,
                naturallyDescending = { it.descendingByNature },
            )
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
        /** The option that shuffles rather than orders, where the menu has one. */
        random: T? = null,
    ) {
        val current by sort.state.collectAsState()
        val isReversed by reversed.state.collectAsState()
        // No Rating where nothing can be rated (all zeros order nothing), except
        // while it is the current sort, so a source switch can't hide the order
        // in effect.
        val supportsRatings = App.source.collectAsState().value.supportsRatings
        val shown = options.filter { it != rating || supportsRatings || it == current }

        // The page being sorted is named under the heading, as Filter and View do,
        // with its narrowing, so sorting a filtered list says so.
        ListScreen(onBack = { goBack() }, title = "Sort by", subtitle = tabTitle(tab, likedOnly(tab))) {
            ScrollableList {
                item {
                    SectionLabel(
                        if (current == random) {
                            "Tap Random again for a new shuffle"
                        } else {
                            "Tap the current sort again to reverse it"
                        },
                    )
                }
                items(shown) { option ->
                    val selected = option == current
                    TextRow(
                        title = label(option),
                        onClick = {
                            App.scope.launch {
                                when {
                                    !selected -> {
                                        sort.set(option)
                                        reversed.set(false)
                                    }
                                    // A shuffle has no direction: the second tap deals again.
                                    option == random -> App.settings.bumpShuffleNonce()
                                    else -> reversed.set(!isReversed)
                                }
                            }
                            // A new order makes the old position meaningless: the
                            // list opens at the top.
                            ScrollAnchors.clear("tab:${tab.name.lowercase()}")
                            goBack(Unit)
                        },
                        trailing = {
                            if (selected && option != random) {
                                // `reversed` inverts the option's natural direction.
                                val descending = naturallyDescending(option) != isReversed
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
}

fun albumSortLabel(sort: AlbumSort): String = when (sort) {
    AlbumSort.TITLE -> "Title"
    // Labels name the key exactly: the album artist, and the full release date.
    AlbumSort.ARTIST -> "Album Artist"
    AlbumSort.YEAR -> "Date Released"
    AlbumSort.DATE_ADDED -> "Recently Added"
    AlbumSort.RECENTLY_PLAYED -> "Recently Played"
    AlbumSort.MOST_PLAYED -> "Frequently Played"
    AlbumSort.RATING -> "Rating"
    AlbumSort.RANDOM -> "Random"
}

fun songSortLabel(sort: SongSort): String = when (sort) {
    SongSort.TITLE -> "Title"
    SongSort.ARTIST -> "Artist"
    SongSort.DATE_ADDED -> "Recently Added"
    SongSort.RECENTLY_PLAYED -> "Recently Played"
    SongSort.MOST_PLAYED -> "Frequently Played"
    SongSort.RATING -> "Rating"
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

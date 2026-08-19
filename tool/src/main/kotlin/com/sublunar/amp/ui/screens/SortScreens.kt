package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
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
import com.sublunar.amp.ui.components.ScrollableList
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.n
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
 * The page a sort menu belongs to, named under the menu's own heading.
 *
 * The menu says what it is — "Sort by" — and the line under it says what is
 * being sorted, exactly as Filter and View do. It used to take the page's name
 * as its whole title, which read correctly back when the page's own title was
 * the way in. Reached from a More that is already named after the page, that
 * put the same name on two screens in a row and never once said what the menu
 * was for.
 *
 * Carries the narrowing with it, so sorting a filtered list says so.
 */
@Composable
private fun sortedPage(tab: LibraryTab): String = tabTitle(tab, likedOnly(tab))

/**
 * All, or only the ones you kept.
 *
 * Liked is the only narrowing the app has. It used to be a heart in the sort
 * menu's corner, which said what it was set to only by being filled — and only
 * once you had opened a menu about something else. As a page of two rows it
 * says which one is in effect, and More says so without opening anything.
 */
/**
 * Narrow a list to one genre, or to one composer.
 *
 * The values offered are the library's own — see LibraryRepository.genres — so
 * every one of them leads somewhere rather than to an empty page, which is what
 * the browsable tag lists this replaced could not promise. "All" comes first and
 * clears the narrowing.
 *
 * Genre and composer are separate settings and combine, so picking a composer
 * leaves a genre already chosen alone.
 */
class TagFilterScreen(
    sealed: SealedLightActivity,
    private val tab: LibraryTab,
    private val byComposer: Boolean,
) : SimpleLightScreen<Unit>(sealed) {
    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
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
        // A narrowed list is the point of the tap, so leave with the answer —
        // as the sort pickers do. See MoreScreen.change.
        goBack(Unit)
    }
}

/**
 * Shared body for every "Sort by" menu.
 *
 * Picking a different option applies it and closes; tapping the already-selected
 * option flips the sort direction and also closes, so either way one tap is the
 * whole interaction. The arrow beside the selected option shows which direction
 * is in effect when the menu is reopened.
 *
 * Closing lands wherever the menu was opened from — the list, for the title
 * tap, or More, which is a panel of modifiers you may well want to set two of
 * before going back to look at the result.
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
    /** The page being sorted, named under the heading — see [sortedPage]. */
    page: String?,
) {
    ListScreen(onBack = onBack, title = "Sort by", subtitle = page) {
        ScrollableList(modifier = Modifier.fillMaxSize()) {
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

class AlbumsSortScreen(
    sealed: SealedLightActivity,
) : SimpleLightScreen<Unit>(sealed) {
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
                goBack(Unit)
            },
            onFlip = {
                App.scope.launch { App.settings.setAlbumSortReversed(!reversed) }
                ScrollAnchors.clear("tab:albums", "albums")
                goBack(Unit)
            },
            onBack = { goBack() },
            page = sortedPage(LibraryTab.ALBUMS),
        )
    }

}

class SongsSortScreen(
    sealed: SealedLightActivity,
    /** Set when opened from a page that isn't the tab — see LibraryShell. */
    private val pageTitle: String? = null,
) : SimpleLightScreen<Unit>(sealed) {
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
                goBack(Unit)
            },
            onFlip = {
                App.scope.launch { App.settings.setSongSortReversed(!reversed) }
                ScrollAnchors.clear("tab:songs")
                goBack(Unit)
            },
            onBack = { goBack() },
            page = pageTitle ?: sortedPage(LibraryTab.SONGS),
        )
    }
}

class ArtistsSortScreen(
    sealed: SealedLightActivity,
    /** Set when opened from a page that isn't the tab — see LibraryShell. */
    private val pageTitle: String? = null,
) : SimpleLightScreen<Unit>(sealed) {
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
                goBack(Unit)
            },
            onFlip = {
                App.scope.launch { App.settings.setArtistSortReversed(!reversed) }
                ScrollAnchors.clear("tab:artists")
                goBack(Unit)
            },
            onBack = { goBack() },
            page = pageTitle ?: sortedPage(LibraryTab.ARTISTS),
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
    ArtistSort.MOST_PLAYED -> "Plays"
}

fun playlistSortLabel(sort: PlaylistSort): String = when (sort) {
    PlaylistSort.NAME -> "Name"
    PlaylistSort.DATE_CREATED -> "Date Created"
    PlaylistSort.RECENTLY_UPDATED -> "Recently Updated"
}


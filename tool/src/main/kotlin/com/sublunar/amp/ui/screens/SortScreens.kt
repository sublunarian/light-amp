package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import com.sublunar.amp.data.TagSort
import com.sublunar.amp.data.descendingByNature
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.HeaderAction
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.SectionLabel
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
private val TAG_SORT_OPTIONS = TagSort.entries.toList()

/**
 * What a tab's menu calls itself: the name of the page it was opened from,
 * narrowing and all, so the menu and the list behind it agree.
 */
@Composable
private fun sortMenuTitle(tab: LibraryTab): String = tabTitle(tab, likedOnly(tab))

/**
 * The liked toggle, in the menu header's corner.
 *
 * The only narrowing the app has, so a labelled section holding one row was
 * more furniture than the thing deserved — and a heart says "these are the ones
 * you kept" in a way no row of text does. Filled when it is on; the page's own
 * title says so too, underneath.
 */
@Composable
private fun SimpleLightScreen<*>.likedToggle(tab: LibraryTab): HeaderAction? {
    if (!App.source.collectAsState().value.supportsLikes) return null
    val on = likedOnly(tab)
    // Nothing liked yet, nothing to narrow to: a heart here would only ever
    // empty the page. It stays while the narrowing is *on*, though — unliking
    // the last one must not strand you on an empty list with the way out gone.
    val any = when (tab) {
        LibraryTab.ALBUMS -> App.library.likedAlbums.collectAsState().value.isNotEmpty()
        LibraryTab.SONGS -> App.library.likedTracks.collectAsState().value.isNotEmpty()
        LibraryTab.ARTISTS -> App.library.likedArtists.collectAsState().value.isNotEmpty()
        LibraryTab.PLAYLISTS -> false
    }
    if (!on && !any) return null
    return HeaderAction(if (on) AppIcons.Favorite else AppIcons.FavoriteBorder) {
        App.scope.launch {
            when (tab) {
                LibraryTab.ALBUMS -> App.settings.setLikedAlbumsOnly(!on)
                LibraryTab.SONGS -> App.settings.setLikedSongsOnly(!on)
                LibraryTab.ARTISTS -> App.settings.setLikedArtistsOnly(!on)
                LibraryTab.PLAYLISTS -> Unit
            }
        }
        // Back to the list it just changed — the point of the tap.
        goBack()
    }
}

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
    /**
     * The page this menu was opened from — it keeps that name, because a menu
     * that renames the header says less about where you are, not more.
     */
    title: String = "Sort by",
    /** The liked toggle, for the tabs that have one. */
    action: HeaderAction? = null,
    /** Rows above the sort options — see AlbumsSortScreen. */
    extra: (LazyListScope.() -> Unit)? = null,
) {
    ListScreen(onBack = onBack, title = title, rightAction = action) {
        ScrollableList(modifier = Modifier.fillMaxSize()) {
            extra?.invoke(this)
            if (extra != null) item { SectionLabel("Sort by") }
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
    /** Set when opened from a page that isn't the tab — see LibraryShell. */
    private val pageTitle: String? = null,
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
                goBack()
            },
            onFlip = {
                App.scope.launch { App.settings.setAlbumSortReversed(!reversed) }
                ScrollAnchors.clear("tab:albums", "albums")
                goBack()
            },
            onBack = { goBack() },
            title = pageTitle ?: sortMenuTitle(LibraryTab.ALBUMS),
            action = if (pageTitle == null) likedToggle(LibraryTab.ALBUMS) else null,
            extra = if (pageTitle == null) viewRows() else null,
        )
    }

    /**
     * List or grid, folded in under the sort options.
     *
     * Compact has no separate button for either — the title is the one menu for
     * "how am I looking at this", and both questions belong in it. Absent with
     * artwork off, where a grid of nothing is not a choice, and absent in the
     * classic layout, which keeps its own picker.
     */
    @Composable
    private fun viewRows(): (LazyListScope.() -> Unit)? {
        val grid = App.albumGrid.collectAsState().value
        if (App.hideArtwork.collectAsState().value) return null
        return {
            item { SectionLabel("View") }
            item { ViewChoice("List", chosen = !grid) { chooseView(false) } }
            item { ViewChoice("Grid", chosen = grid) { chooseView(true) } }
        }
    }

    @Composable
    private fun ViewChoice(label: String, chosen: Boolean, onClick: () -> Unit) {
        TextRow(
            title = label,
            onClick = onClick,
            trailing = { if (chosen) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
        )
    }

    private fun chooseView(grid: Boolean) {
        App.scope.launch { App.settings.setAlbumGrid(grid) }
        goBack()
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
                goBack()
            },
            onFlip = {
                App.scope.launch { App.settings.setSongSortReversed(!reversed) }
                ScrollAnchors.clear("tab:songs")
                goBack()
            },
            onBack = { goBack() },
            title = pageTitle ?: sortMenuTitle(LibraryTab.SONGS),
            action = if (pageTitle == null) likedToggle(LibraryTab.SONGS) else null,
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
                goBack()
            },
            onFlip = {
                App.scope.launch { App.settings.setArtistSortReversed(!reversed) }
                ScrollAnchors.clear("tab:artists")
                goBack()
            },
            onBack = { goBack() },
            title = pageTitle ?: sortMenuTitle(LibraryTab.ARTISTS),
            action = if (pageTitle == null) likedToggle(LibraryTab.ARTISTS) else null,
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
            title = sortMenuTitle(LibraryTab.PLAYLISTS),
        )
    }
}

/**
 * Sort menu for the genre and composer lists.
 *
 * No scroll anchor to clear: neither list is deep enough to be left part-way
 * down, and they have no A–Z strip whose buckets would go stale.
 */
class TagsSortScreen(
    sealed: SealedLightActivity,
    /** The page this menu was opened from, which it keeps as its own name. */
    private val pageTitle: String? = null,
) : SimpleLightScreen<Unit>(sealed) {
    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val current by App.tagSort.collectAsState()
        val reversed by App.tagSortReversed.collectAsState()
        SortOptions(
            options = TAG_SORT_OPTIONS,
            current = current,
            reversed = reversed,
            label = ::tagSortLabel,
            naturallyDescending = { it.descendingByNature },
            title = pageTitle ?: "Sort by",
            onSelect = { option ->
                App.scope.launch {
                    App.settings.setTagSort(option)
                    App.settings.setTagSortReversed(false)
                }
                goBack()
            },
            onFlip = {
                App.scope.launch { App.settings.setTagSortReversed(!reversed) }
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

fun tagSortLabel(sort: TagSort): String = when (sort) {
    TagSort.NAME -> "Name"
    TagSort.SONGS -> "Songs"
}

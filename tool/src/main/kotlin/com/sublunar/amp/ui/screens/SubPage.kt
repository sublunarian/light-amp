package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.HeaderAction
import com.thelightphone.sdk.SimpleLightScreen

/**
 * Open library search from a sub-page: search is a state of the shell, so
 * activate it and unwind to the shell.
 */
fun SimpleLightScreen<*>.openLibrarySearch() {
    LibraryNav.openSearch()
    backToRoot()
}

/**
 * A library page's right-hand corner: More, or Settings when held.
 *
 * [page] tells More whose view, sort and filter it is showing, since the corner
 * is drawn by the page itself.
 */
@Composable
fun SimpleLightScreen<*>.libraryCornerAction(
    page: LibraryPage,
    /** Only where the page's name isn't a constant — see [MoreScreen]. */
    pageTitle: String? = null,
): HeaderAction =
    HeaderAction(
        AppIcons.Sort,
        onLongClick = { go { SettingsScreen(it) } },
    ) { go { MoreScreen(it, page, pageTitle) } }

/**
 * Chrome for every library sub-page (album, artist, playlist, …): the page's own
 * content with the library's bottom bar kept underneath it.
 */
@Composable
fun SimpleLightScreen<*>.LibrarySubPage(
    page: LibraryPage,
    pageTitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) { content() }
            }
            val current by LibraryNav.currentTab.collectAsState()
            Navbar(
                // The tab you came from stays lit however deep this page is: a
                // branch is still that tab, and back walks up it one level at a
                // time.
                current = current,
                onSelect = { tab ->
                    LibraryNav.selectTab(tab)
                    backToRoot()
                },
                onSearch = { openLibrarySearch() },
                onNowPlaying = { go { NowPlayingScreen(it) } },
                // The top of the library, however deep this page is.
                onBrowse = {
                    LibraryNav.openLibraryIndex()
                    backToRoot()
                },
            )
        }
    }
}

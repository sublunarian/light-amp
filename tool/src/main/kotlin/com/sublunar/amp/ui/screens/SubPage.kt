package com.sublunar.amp.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.ui.PlayerTheme
import com.sublunar.amp.ui.components.AppIcons
import com.sublunar.amp.ui.components.HeaderAction
import com.thelightphone.sdk.SimpleLightScreen

/**
 * Chrome for every library sub-page (album, artist, playlist, liked, …): the
 * page's own content with the library bottom bar kept underneath it.
 */
/**
 * Open library search from a sub-page: the search field lives in the shell's
 * header, so activate it and unwind to the shell.
 */
fun SimpleLightScreen<*>.openLibrarySearch(withKeyboard: Boolean = false) {
    LibraryNav.openSearch(withKeyboard)
    popToRoot()
}

/**
 * More sits in every library page's right-hand corner — see LibraryShell.
 *
 * [page] is what More carries up: the corner is drawn by the page itself, so
 * this is where the menu learns whose view, sort and filter it is showing.
 */
@Composable
fun SimpleLightScreen<*>.libraryCornerAction(
    page: LibraryPage,
    /** Only where the page's name isn't a constant — see [MoreScreen]. */
    pageTitle: String? = null,
): HeaderAction =
    HeaderAction(AppIcons.MoreHoriz) { go { MoreScreen(it, page, pageTitle) } }


@Composable
fun SimpleLightScreen<*>.LibrarySubPage(
    /** Which page this is, for the More the bar below opens — see [MoreScreen]. */
    page: LibraryPage,
    /** Only where the page's name isn't a constant — see [MoreScreen]. */
    pageTitle: String? = null,
    /** Set by More, which is a tab in its own right rather than a page of one. */
    moreActive: Boolean = false,
    onNowPlaying: (() -> Unit)? = null,
    onBrowse: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) { content() }
            }
            val current by LibraryNav.currentTab.collectAsState()
            val offTab by LibraryNav.offTab.collectAsState()
            Navbar(
                current = if (moreActive || offTab) null else current,
                moreActive = moreActive,
                onSelect = { tab ->
                    LibraryNav.selectTab(tab)
                    popToRoot()
                },
                // Nothing to open when this *is* More: the bar stays on that
                // page now, and a destination that pushes a copy of itself is a
                // stack that only grows.
                onMore = { if (!moreActive) go { MoreScreen(it, page, pageTitle) } },
                // The bar is on these pages too, so its search reaches the
                // library the way the header's used to: activate and unwind.
                onSearch = { openLibrarySearch() },
                onNowPlaying = { go { NowPlayingScreen(it) } },
                onBrowse = { popToRoot() },
            )
        }
    }
}

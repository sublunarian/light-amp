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
import com.sublunar.amp.data.LayoutMode
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

/** True while the simplified layout is the one in use. */
@Composable
fun simplifiedLayout(): Boolean =
    App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED

/**
 * Back out of a page opened from the simplified layout's library index.
 *
 * Only under Simplified, and for the same reason the corner is free there: the
 * standard layout keeps the player in that slot and leaves by the tab bar,
 * which these pages sit alongside rather than under.
 */
@Composable
fun SimpleLightScreen<*>.libraryBackAction(): (() -> Unit)? =
    if (simplifiedLayout()) ({ goBack() }) else null

/**
 * The player in a header's left corner — the standard layout only.
 *
 * Simplified keeps the player in the bottom bar, so a second one directly above
 * it is one too many, and the title takes the corner's width back instead.
 */
@Composable
fun SimpleLightScreen<*>.nowPlayingCorner(): HeaderAction? =
    if (App.layoutMode.collectAsState().value == LayoutMode.SIMPLIFIED) {
        null
    } else {
        HeaderAction(AppIcons.Waveform) { go { NowPlayingScreen(it) } }
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
                // Simplified puts the player and the library in the bar. From
                // a sub-page the library means the index, the same as it does
                // from a tab — so unwind to the shell and show it there.
                onNowPlaying = { go { NowPlayingScreen(it) } },
                onBrowse = {
                    LibraryNav.openLibraryIndex()
                    popToRoot()
                },
            )
        }
    }
}

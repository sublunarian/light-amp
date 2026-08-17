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
 * page's own content with the library tab bar kept underneath it, so navigation
 * never disappears just because the user drilled in.
 *
 * Tapping a tab records the choice in [LibraryNav] and unwinds the whole stack
 * back to the root screen, which hosts the shell — that's what makes the tabs
 * work from any depth rather than only one level down.
 */
/**
 * Open library search from a sub-page: the search field lives in the shell's
 * header, so activate it and unwind to the shell.
 */
fun SimpleLightScreen<*>.openLibrarySearch(withKeyboard: Boolean = false) {
    LibraryNav.openSearch(withKeyboard)
    popToRoot()
}

/** More sits in every library page's right-hand corner — see LibraryShell. */
@Composable
fun SimpleLightScreen<*>.libraryCornerAction(): HeaderAction =
    HeaderAction(AppIcons.MoreHoriz) { go { MoreScreen(it) } }

/**
 * The library's right-hand corner, on a page below the tabs.
 *
 * More, until Inline Search puts More back in the tab bar — and the bar is on
 * these pages too, so keeping it in both places would be the same button twice.
 * The corner then belongs to this page's own sort-and-view menu, and to nothing
 * at all on a page that has none.
 */
@Composable
fun SimpleLightScreen<*>.libraryCorner(menu: (() -> Unit)? = null): HeaderAction? =
    if (App.inlineSearch.collectAsState().value) {
        menu?.let { HeaderAction(AppIcons.Sort, it) }
    } else {
        libraryCornerAction()
    }

/** A title menu, which Inline Search moves into the corner instead. */
@Composable
fun titleMenu(menu: (() -> Unit)?): (() -> Unit)? =
    menu.takeIf { !App.inlineSearch.collectAsState().value }

@Composable
fun SimpleLightScreen<*>.LibrarySubPage(
    /** Set by More, which is a tab in its own right rather than a page of one. */
    moreActive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    PlayerTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.fillMaxSize()) { content() }
            }
            // The tab that led here stays lit, so drilling Artists → artist →
            // album still reads as "you are in Artists" until a tab is tapped.
            // Unless no tab led here at all: a page opened as a peer of the tabs
            // lights none of them, and nor does anything opened from one.
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
                onMore = { if (!moreActive) go { MoreScreen(it) } },
            )
        }
    }
}

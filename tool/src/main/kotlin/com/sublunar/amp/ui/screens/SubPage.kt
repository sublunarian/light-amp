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

/** More sits in every library page's right-hand corner — see LibraryShell. */
@Composable
fun SimpleLightScreen<*>.libraryCornerAction(): HeaderAction =
    HeaderAction(AppIcons.MoreHoriz) { go { MoreScreen(it) } }

@Composable
fun SimpleLightScreen<*>.LibrarySubPage(
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
                onSearch = { openLibrarySearch() },
                onNowPlaying = onNowPlaying ?: { go { NowPlayingScreen(it) } },
                onBrowse = onBrowse ?: { popToRoot() },
            )
        }
    }
}

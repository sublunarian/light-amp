package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.AppIcon
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.sublunar.amp.ui.n
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons

/**
 * Picker for the four library tabs. Reached only by tapping the center of the
 * bottom bar while on a library root (not from subs). Uses ListScreen + TextRow
 * like AlbumViewScreen.
 */
class TypePickerScreen(sealed: SealedLightActivity) : SimpleLightScreen<Unit>(sealed) {

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val current by LibraryNav.currentTab.collectAsState()
        val source by App.source.collectAsState()

        ListScreen(
            onBack = { goBack() },
            title = "Library",
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                LibraryTypeRow(LibraryTab.ALBUMS, current) { choose(it) }
                LibraryTypeRow(LibraryTab.ARTISTS, current) { choose(it) }
                LibraryTypeRow(LibraryTab.SONGS, current) { choose(it) }
                if (source.supportsPlaylists) {
                    LibraryTypeRow(LibraryTab.PLAYLISTS, current) { choose(it) }
                }
            }
        }
    }

    @Composable
    private fun LibraryTypeRow(
        tab: LibraryTab,
        current: LibraryTab,
        onClick: (LibraryTab) -> Unit,
    ) {
        TextRow(
            leading = { AppIcon(iconFor(tab), size = n(22)) },
            title = tab.title,
            trailing = { if (tab == current) LightIcon(LightIcons.ACCEPT, size = 1.4f) },
            onClick = { onClick(tab) },
        )
    }

    private fun choose(tab: LibraryTab) {
        LibraryNav.selectTab(tab)
        goBack()
    }
}

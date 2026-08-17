package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import kotlinx.coroutines.launch

/**
 * How an album list is laid out: a column of titles, or a wall of covers.
 *
 * Reached from the album list's own title rather than from Settings — it
 * belongs to the list you are looking at, and the header had no room for a
 * fourth button. Only offered while artwork is on, since the grid is nothing
 * but artwork.
 */
class AlbumViewScreen(
    sealed: SealedLightActivity,
    /** Editing an artist's own page rather than the whole album list. */
    private val forArtist: Boolean = false,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val setting = if (forArtist) App.settings.artistAlbumGrid else App.settings.albumGrid
        val grid by setting.collectAsState(initial = false)

        // Named, because the two pickers are otherwise identical and setting one
        // no longer changes the other.
        ListScreen(
            onBack = { goBack() },
            title = "View",
            subtitle = if (forArtist) "An artist's albums" else "All albums",
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Choice("List", chosen = !grid) { choose(false) }
                Choice("Grid", chosen = grid) { choose(true) }
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

    private fun choose(grid: Boolean) {
        App.scope.launch {
            if (forArtist) App.settings.setArtistAlbumGrid(grid) else App.settings.setAlbumGrid(grid)
        }
        // Reported, so the More that pushed this closes with it — see MoreScreen.
        goBack(Unit)
    }
}

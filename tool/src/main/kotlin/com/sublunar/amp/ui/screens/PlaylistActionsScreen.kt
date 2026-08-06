package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

/** Long-press actions for a playlist row: play, rename, delete. */
class PlaylistActionsScreen(
    sealed: SealedLightActivity,
    private val playlistId: String,
    private val playlistName: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        var tracks by remember(playlistId) { mutableStateOf<List<Track>?>(null) }
        var confirmDelete by remember { mutableStateOf(false) }
        LaunchedEffect(playlistId) {
            tracks = App.library.playlistTracks(playlistId)
        }
        val list = tracks ?: emptyList()
        // Plex's smart playlists are real objects on the server, so Delete here
        // would really delete one — and Plex builds their contents from a
        // filter, so renaming is the only thing it would even accept. Neither
        // is offered: see Playlist.readOnly.
        val readOnly = App.library.playlists.collectAsState().value
            .firstOrNull { it.id == playlistId }?.readOnly == true
        val kind = if (readOnly) "Smart playlist" else "Playlist"

        ListScreen(onBack = { goBack() }, title = playlistName, subtitle = kind) {
            ActionList {
                TextRow(title = "Play") {
                    if (list.isNotEmpty()) {
                        App.playback.playQueue(list, 0)
                        go { NowPlayingScreen(it) }
                    }
                }
                TextRow(title = "Shuffle") {
                    if (list.isNotEmpty()) {
                        App.playback.playQueue(shuffled(list), 0)
                        go { NowPlayingScreen(it) }
                    }
                }
                if (!readOnly) {
                    TextRow(title = "Rename") { rename() }
                    TextRow(title = if (confirmDelete) "Tap again to delete" else "Delete Playlist") {
                        if (confirmDelete) {
                            App.scope.launch { App.library.deletePlaylist(playlistId) }
                            goBack()
                        } else {
                            confirmDelete = true
                        }
                    }
                }
            }
        }
    }

    private fun rename() {
        navigateTo<String?>(
            { TextEntryScreen(it, title = "Rename Playlist", initial = playlistName) },
            resultCallback = { newName ->
                if (!newName.isNullOrBlank()) {
                    App.scope.launch { App.library.renamePlaylist(playlistId, newName) }
                }
            },
        )
    }
}

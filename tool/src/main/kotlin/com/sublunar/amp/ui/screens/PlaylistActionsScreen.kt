package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.launch

/** Long-press actions for a playlist row: play, download, rename, delete. */
class PlaylistActionsScreen(
    sealed: SealedLightActivity,
    private val playlistId: String,
    private val playlistName: String,
    /**
     * Opened from the playlist's own page, so Delete unwinds past it rather than
     * landing on the page of a playlist that no longer exists.
     */
    private val fromDetail: Boolean = false,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        var tracks by remember(playlistId) { mutableStateOf<List<Track>?>(null) }
        var confirmDelete by remember { mutableStateOf(false) }
        LaunchedEffect(playlistId) {
            tracks = App.playlists.playlistTracks(playlistId)
        }
        val list = tracks ?: emptyList()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val fullyDownloaded = list.isNotEmpty() && list.all { it.id in downloadedIds }
        // A Plex smart playlist is offered neither Delete (it would really delete
        // it) nor Rename (see Playlist.readOnly).
        val readOnly = App.playlists.allPlaylists.collectAsState().value
            .firstOrNull { it.id == playlistId }?.readOnly == true
        val kind = if (readOnly) "Smart playlist" else "Playlist"

        ListScreen(onBack = { goBack() }, title = playlistName, subtitle = kind) {
            ActionList {
                PlayRows(list)
                DownloadRow(source, list, fullyDownloaded, "Download Playlist")
                if (!readOnly) {
                    TextRow(title = "Rename") { rename() }
                    TextRow(title = if (confirmDelete) "Tap again to delete" else "Delete Playlist") {
                        if (confirmDelete) {
                            App.scope.launch { App.playlists.deletePlaylist(playlistId) }
                            if (fromDetail) backToRoot() else goBack()
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
                    App.scope.launch { App.playlists.renamePlaylist(playlistId, newName) }
                }
            },
        )
    }
}

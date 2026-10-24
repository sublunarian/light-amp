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

/** Long-press actions for an album: play or queue it whole, like, rate, download, go to the artist. */
class AlbumActionsScreen(
    sealed: SealedLightActivity,
    private val albumId: String,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        val albums by App.library.albums.collectAsState()
        val album = remember(albums, albumId) { albums.firstOrNull { it.id == albumId } }
        var tracks by remember(albumId) { mutableStateOf<List<Track>?>(null) }
        LaunchedEffect(albumId) { tracks = App.library.getAlbumTracks(albumId) }
        val list = tracks ?: emptyList()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val fullyDownloaded = list.isNotEmpty() && list.all { it.id in downloadedIds }

        ListScreen(onBack = { goBack() }, title = album?.title ?: "Album", subtitle = album?.artist) {
            ActionList {
                PlayRows(list)
                TextRow(title = "Play Next") {
                    if (list.isNotEmpty()) App.playback.playNext(list)
                    goBack()
                }
                TextRow(title = "Add to Queue") {
                    if (list.isNotEmpty()) App.playback.addToQueue(list)
                    goBack()
                }
                if (album != null && source.supportsLikes) {
                    TextRow(title = if (album.liked) "Unlike Album" else "Like Album") {
                        App.scope.launch { App.library.setAlbumLiked(album, !album.liked) }
                        goBack()
                    }
                }
                if (album != null && album.artist.isNotBlank() && source.supportsRatings) {
                    RatingRow(album.id, album.title, album.rating, isAlbum = true)
                }
                DownloadRow(source, list, fullyDownloaded, "Download Album")
                if (album != null && album.artist.isNotBlank()) {
                    TextRow(title = "Go to Artist") {
                        openArtist(album.artist, Parent.tab(LibraryTab.ARTISTS))
                    }
                }
            }
        }
    }
}

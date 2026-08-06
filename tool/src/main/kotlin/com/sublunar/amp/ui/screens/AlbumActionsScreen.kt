package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

/** Long-press actions for an album row: play/queue the whole album, jump to artist. */
class AlbumActionsScreen(
    sealed: SealedLightActivity,
    private val albumId: String,
) : SimpleLightScreen<Unit>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

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
                    TextRow(title = "Rating", subtitle = ratingStars(album.rating)) {
                        go { RatingScreen(it, album.id, album.title, album.rating, isAlbum = true) }
                    }
                }
                if (list.isNotEmpty() && source.supportsDownloads) {
                    if (fullyDownloaded) {
                        TextRow(title = "Remove from Downloads") {
                            App.scope.launch { App.downloader.removeAll(list.map { it.id }) }
                            goBack()
                        }
                    } else {
                        TextRow(title = "Download Album") {
                            App.downloader.enqueue(list)
                            goBack()
                        }
                    }
                }
                if (album != null && album.artist.isNotBlank()) {
                    TextRow(title = "Go to Artist") {
                        go { ArtistDetailScreen(it, album.artist) }
                    }
                }
            }
        }
    }
}

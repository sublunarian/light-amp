package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import com.sublunar.amp.App
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.shuffled
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.launch

// The rows more than one long-press sheet offers. Each sheet keeps its own
// order and its own conditions; these are only what a row says and does.

/** Play [tracks] in order, or shuffled: the player takes the sheet's place. */
@Composable
fun SimpleLightScreen<Unit>.PlayRows(tracks: List<Track>) {
    TextRow(title = "Play") {
        if (tracks.isNotEmpty()) {
            App.playback.playQueue(tracks, 0)
            replaceWithPlayer()
        }
    }
    TextRow(title = "Shuffle") {
        if (tracks.isNotEmpty()) {
            App.playback.playQueue(shuffled(tracks), 0)
            replaceWithPlayer()
        }
    }
}

@Composable
fun SimpleLightScreen<Unit>.LikeTrackRow(track: Track) {
    TextRow(title = if (track.liked) "Unlike" else "Like") {
        App.scope.launch { App.library.setTrackLiked(track, !track.liked) }
        goBack()
    }
}

@Composable
fun SimpleLightScreen<*>.RatingRow(id: String, title: String, rating: Int, isAlbum: Boolean) {
    TextRow(title = "Rating", value = ratingStars(rating)) {
        go { RatingScreen(it, id, title, rating, isAlbum) }
    }
}

/**
 * The playlist picker for [trackIds]. [onAdded] runs once it reports back, to
 * close this sheet too: a goBack() straight after the push would pop the picker.
 */
@Composable
fun SimpleLightScreen<*>.AddToPlaylistRow(trackIds: List<String>, onAdded: () -> Unit) {
    TextRow(title = "Add to Playlist") {
        navigateTo<Unit>({ AddToPlaylistScreen(it, trackIds) }, resultCallback = { onAdded() })
    }
}

/**
 * Remove [tracks] from the phone once every one is there ([downloaded]), or
 * download them under [addLabel]. Nothing where the source has no downloads
 * (the phone's own music is there already), and no offer to download where it
 * refuses them (see MusicSource.mayDownload).
 */
@Composable
fun SimpleLightScreen<Unit>.DownloadRow(
    source: MusicSource,
    tracks: List<Track>,
    downloaded: Boolean,
    addLabel: String,
) {
    if (tracks.isEmpty()) return
    when {
        downloaded && source.supportsDownloads -> TextRow(title = "Remove from Downloads") {
            App.scope.launch { App.downloader.removeAll(tracks.map { it.id }) }
            goBack()
        }
        !downloaded && source.mayDownload -> TextRow(title = addLabel) {
            App.downloader.enqueue(tracks)
            goBack()
        }
    }
}

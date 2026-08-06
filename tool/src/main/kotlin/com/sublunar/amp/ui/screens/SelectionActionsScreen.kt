package com.sublunar.amp.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen

/**
 * What to do with a multi-selection.
 *
 * Returns true when the caller should leave selection mode — i.e. the action was
 * carried out — and false when the user backed out, so a mis-tap doesn't discard
 * a selection that took a while to build.
 */
class SelectionActionsScreen(
    sealed: SealedLightActivity,
    private val tracks: List<Track>,
    /** Cleared from the queue, where they are all queued already. */
    private val showAddToQueue: Boolean = true,
    /** Cleared from the queue and the player: storage is a library job. */
    private val showDownload: Boolean = true,
) : SimpleLightScreen<Boolean>(sealed) {

    // While casting, the rocker belongs to the speaker — see handleVolumeKey.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        App.playback.handleVolumeKey(keyCode) || super.onKeyDown(keyCode, event)

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        val count = tracks.size
        val noun = if (count == 1) "song" else "songs"

        ListScreen(onBack = { goBack(false) }, title = "$count $noun") {
            ActionList {
                if (source.supportsPlaylists) {
                TextRow(title = "Add to Playlist") {
                    // Unwind only once the picker reports back: calling goBack()
                    // straight after the push would pop the picker instead.
                    navigateTo<Unit>(
                        { AddToPlaylistScreen(it, tracks.map { t -> t.id }) },
                        resultCallback = { goBack(true) },
                    )
                }
                }
                TextRow(title = "Play Next") {
                    App.playback.playNext(tracks)
                    goBack(true)
                }
                if (showAddToQueue) {
                    TextRow(title = "Add to Queue") {
                        App.playback.addToQueue(tracks)
                        goBack(true)
                    }
                }
                if (showDownload && source.supportsDownloads) {
                    TextRow(title = "Add to Downloads") {
                        App.downloader.enqueue(tracks)
                        goBack(true)
                    }
                }
            }
        }
    }
}

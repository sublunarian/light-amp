package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sublunar.amp.App
import com.sublunar.amp.data.Track
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity

/**
 * What to do with a multi-selection. Returns true when an action was carried out
 * and the caller should leave selection mode, false when the user backed out, so a
 * mis-tap doesn't discard a selection that took a while to build.
 */
class SelectionActionsScreen(
    sealed: SealedLightActivity,
    private val tracks: List<Track>,
    /**
     * Opened from the queue: no Add to Queue, where every song is queued already,
     * and no download, which is a library job.
     */
    private val fromQueue: Boolean = false,
) : AmpScreen<Boolean>(sealed) {

    @Composable
    override fun Content() {
        val source by App.source.collectAsState()
        val count = tracks.size
        val noun = if (count == 1) "song" else "songs"

        ListScreen(onBack = { goBack(false) }, title = "$count $noun") {
            ActionList {
                AddToPlaylistRow(tracks.map { it.id }) { goBack(true) }
                TextRow(title = "Play Next") {
                    App.playback.playNext(tracks)
                    goBack(true)
                }
                if (!fromQueue) {
                    TextRow(title = "Add to Queue") {
                        App.playback.addToQueue(tracks)
                        goBack(true)
                    }
                }
                if (!fromQueue && source.mayDownload) {
                    TextRow(title = "Add to Downloads") {
                        App.downloader.enqueue(tracks)
                        goBack(true)
                    }
                }
            }
        }
    }
}

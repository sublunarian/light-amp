package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sublunar.amp.App
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.ListScreen
import com.sublunar.amp.ui.components.TextRow
import com.thelightphone.sdk.SealedLightActivity
import kotlinx.coroutines.launch

/** The queue page's ••• menu: the queue kept as a playlist, or what is still to come cleared. */
class QueueActionsScreen(sealed: SealedLightActivity) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val queue by App.playback.queue.collectAsState()
        val index by App.playback.index.collectAsState()
        val queueName by App.playback.queueName.collectAsState()

        ListScreen(onBack = { goBack() }, title = "Queue", headingRows = true) {
            ActionList {
                // How a song radio becomes something to come back to (or download):
                // you hear it, you decide. The queue's name ("Judith Radio") is
                // offered where it has one.
                if (queue.isNotEmpty()) {
                    TextRow(title = "Save Queue as Playlist") {
                        val ids = queue.map { it.id }
                        enterText("Playlist Name", queueName.orEmpty(), TextKind.NAME) { name ->
                            if (name.isNotBlank()) {
                                App.scope.launch { App.playlists.createPlaylist(name, ids) }
                                goBack()
                            }
                        }
                    }
                }
                // Only what is still to come: the song playing keeps playing.
                if (index + 1 in queue.indices) {
                    TextRow(title = "Clear Queue") {
                        App.playback.clearUpcoming()
                        goBack()
                    }
                }
            }
        }
    }
}

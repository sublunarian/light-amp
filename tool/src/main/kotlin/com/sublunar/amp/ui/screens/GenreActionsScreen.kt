package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.sublunar.amp.App
import com.sublunar.amp.data.hasGenre
import com.sublunar.amp.data.titleKey
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.ListScreen
import com.thelightphone.sdk.SealedLightActivity

/**
 * A genre's songs to play or queue, from a hold on its row: album by album in
 * name order, each in disc and track order. Under Downloaded, only those.
 */
class GenreActionsScreen(
    sealed: SealedLightActivity,
    private val genre: String,
    private val downloadedOnly: Boolean,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        val tracks by App.library.tracks.collectAsState()
        val downloadedIds by App.library.downloadedTrackIds.collectAsState()
        val songs = remember(tracks, downloadedIds) {
            tracks.filter { it.hasGenre(genre) && (!downloadedOnly || it.id in downloadedIds) }
                .sortedWith(compareBy({ titleKey(it.album) }, { it.discNumber ?: 0 }, { it.trackNumber ?: 0 }))
        }
        ListScreen(onBack = { goBack() }, title = genre, subtitle = "Genre", headingRows = true) {
            ActionList {
                PlayRows(songs, queueName = genre)
                QueueRows(songs)
            }
        }
    }
}

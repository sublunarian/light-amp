package com.sublunar.amp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sublunar.amp.App
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.data.Track
import com.sublunar.amp.data.isLossless
import com.sublunar.amp.playback.PlayingAs
import com.sublunar.amp.ui.components.ActionList
import com.sublunar.amp.ui.components.InfoRow
import com.sublunar.amp.ui.components.ListScreen
import com.thelightphone.sdk.SealedLightActivity

/** A song's details whole, for when the player or a list had to cut them short. */
class SongInfoScreen(
    sealed: SealedLightActivity,
    private val track: Track,
) : AmpScreen<Unit>(sealed) {

    @Composable
    override fun Content() {
        // As it was when the page opened: the page is a look, not a monitor.
        val playingAs = remember { App.playback.playingAs(track.id) }
        ListScreen(onBack = { goBack() }, title = "Song Info") {
            ActionList {
                InfoRow("Title", track.title)
                if (track.artist.isNotBlank()) InfoRow("Artist", track.artist)
                if (track.album.isNotBlank()) InfoRow("Album", track.album)
                fileFormatOf(track)?.let { InfoRow("File Format", it) }
                playingAs?.let { playingAsOf(track, it) }?.let { InfoRow("Playing As", it) }
            }
        }
    }
}

/**
 * The file as the server keeps it, "FLAC (1012 kbps)", in the quality picker's
 * style. An MP4 file is named by what it holds, told apart by its bitrate as
 * Amp tells them apart everywhere (see Track.isLossless); "M4A" where nothing says.
 */
private fun fileFormatOf(track: Track): String? {
    val name = when (track.codec) {
        "" -> null
        "opus" -> "Opus"
        "m4a", "mp4", "m4b" -> when (track.isLossless()) {
            true -> "ALAC"
            false -> "AAC"
            null -> track.codec.uppercase()
        }
        else -> track.codec.uppercase()
    }
    val rate = track.bitRateKbps?.takeIf { it > 0 }?.let { "$it kbps" }
    return if (name != null && rate != null) "$name ($rate)" else name ?: rate
}

/**
 * What reaches the phone, as [fileFormatOf] says the file: the file's own where
 * it plays as it is, a download's measured bitrate, or the quality a stream is
 * converted to.
 */
private fun playingAsOf(track: Track, playing: PlayingAs): String? {
    if (playing.format == StreamFormat.RAW) return fileFormatOf(track)
    val name = formatLabel(playing.format, null).substringBefore(" (")
    return playing.copyKbps?.let { "$name ($it kbps)" } ?: formatLabel(playing.format, App.source.value)
}

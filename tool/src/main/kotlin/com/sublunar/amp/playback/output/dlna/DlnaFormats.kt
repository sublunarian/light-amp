package com.sublunar.amp.playback.output.dlna

import com.sublunar.amp.data.RepeatMode
import com.sublunar.amp.data.StreamFormat

/** The MIME type a stream in [format] is taken to be when the server can't be asked. */
internal fun assumedMime(format: StreamFormat): String = when (format) {
    StreamFormat.MP3 -> "audio/mpeg"
    StreamFormat.OPUS -> "audio/ogg"
    StreamFormat.FLAC -> "audio/flac"
    // Unknown container up front; the DLNA wildcard lets the device sniff.
    StreamFormat.RAW -> "*"
}

/**
 * Whether the renderer's advertised sink covers [mime]. Compared through
 * alias groups because devices and servers spell the same container
 * differently (`audio/mp4` vs `audio/m4a`, `audio/mpeg` vs `audio/mp3`).
 */
internal fun sinkAccepts(sink: Set<String>, mime: String): Boolean {
    if (sink.isEmpty() || mime == "*") return true
    val aliases = when (mime) {
        "audio/mpeg", "audio/mp3" -> setOf("audio/mpeg", "audio/mp3")
        "audio/flac", "audio/x-flac" -> setOf("audio/flac", "audio/x-flac")
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" ->
            setOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac")
        "audio/ogg", "audio/opus", "application/ogg" ->
            setOf("audio/ogg", "audio/opus", "application/ogg")
        "audio/wav", "audio/x-wav" -> setOf("audio/wav", "audio/x-wav")
        else -> setOf(mime)
    }
    return sink.any { it.substringBefore(';').trim() in aliases }
}

/** The index the queue would move to after [index], or null at the end of it. */
internal fun nextCastIndex(index: Int, size: Int, repeat: RepeatMode): Int? {
    if (size == 0) return null
    return when (repeat) {
        RepeatMode.TRACK -> index
        RepeatMode.QUEUE -> (index + 1) % size
        RepeatMode.OFF -> (index + 1).takeIf { it < size }
    }
}

package com.sublunar.amp.source

import com.sublunar.amp.data.MusicFolder
import com.sublunar.amp.data.MusicSource

/** A server we can reach, and the music libraries on it. */
data class PendingServer(val source: MusicSource, val sections: List<MusicFolder>)

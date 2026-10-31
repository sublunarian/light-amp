package com.sublunar.amp.art

import com.sublunar.amp.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn

/**
 * The room inside the Storage Limit kept for what Amp caches rather than
 * downloads: covers, artist pictures, lyrics. Downloads stop at the limit less
 * this, and the cache keeps itself inside it (see ArtworkLoader.trimToBudget),
 * so a limit full of music never starves the covers of new releases, and the
 * covers never take room meant for music. Shown on the Storage page.
 *
 * Sized from the libraries, not from the limit: a fully cached album is some
 * 225 KB (a full cover, a small one, its share of an artist picture, lyrics)
 * whatever its songs weigh, which is half a percent of it at Opus 128.
 */
class CacheReserve(
    private val settings: AppSettings,
    /** Every source's albums — what the cache is for. */
    private val albumCount: suspend () -> Int,
    scope: CoroutineScope,
) {
    private val albums = MutableStateFlow<Int?>(null)

    val bytes: StateFlow<Long> =
        combine(settings.downloadLimit.state, albums) { limit, count -> reserveFor(count ?: 0, limit) }
            .stateIn(scope, SharingStarted.Eagerly, reserveFor(0, settings.downloadLimit.state.value))

    /** Count the albums again: at launch, and as a sync changes them. */
    suspend fun recount() {
        runCatching { albumCount() }.onSuccess { albums.value = it }
    }

    /** The reserve as it stands, counting first if nothing has been. */
    suspend fun now(): Long {
        if (albums.value == null) recount()
        return reserveFor(albums.value ?: 0, settings.downloadLimit.state.first())
    }

    companion object {
        private const val MB = 1024L * 1024

        /** A fully cached album, with a quarter more for what arrives. */
        private const val PER_ALBUM_BYTES = 225L * 1024 * 5 / 4
        private const val MIN_BYTES = 150 * MB
        private const val MAX_BYTES = 1024 * MB

        /** At most this share of the limit, so a small one still holds mostly music. */
        private const val MAX_SHARE_OF_LIMIT = 4

        fun reserveFor(albums: Int, limitBytes: Long): Long {
            val ceiling = minOf(MAX_BYTES, limitBytes / MAX_SHARE_OF_LIMIT)
            return minOf(maxOf(albums * PER_ALBUM_BYTES, MIN_BYTES), ceiling)
        }
    }
}

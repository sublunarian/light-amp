package com.sublunar.amp.source.jellyfin

import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.source.AddAction
import com.sublunar.amp.source.AddEntry
import com.sublunar.amp.source.AddIcon
import com.sublunar.amp.source.ConnectionRow
import com.sublunar.amp.source.SourceCapabilities
import com.sublunar.amp.source.SourceProvider
import com.sublunar.amp.source.SourceUi
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A Jellyfin server's music library.
 *
 * Of the fields on a MusicSource, `username`, `token`, `userId` and
 * `deviceId` are this kind's own. Everything else on the record is shared.
 */
object JellyfinProvider : SourceProvider {
    override val kind = SourceKind.JELLYFIN

    /**
     * The original file, or anything the transcoder will produce. Unlike
     * Plex's, Jellyfin's universal endpoint honours the container and codec
     * it is given, so the whole list is offered rather than Plex's two.
     */
    override val streamFormats: List<StreamFormat> = StreamFormat.entries.toList()

    // Jellyfin's own app lists a library's playlists by their songs and shows each one whole.
    override val libraryNarrowsPlaylistSongs: Boolean get() = false

    /**
     * Closer to Subsonic than Plex is — real favourites, playlists that can be
     * created empty — but with no star ratings: Jellyfin has the favourite and
     * not the stars, Plex the stars and not the favourite, the two missing
     * opposite halves of the same idea.
     */
    override fun capabilities(features: ServerFeatures?) = SourceCapabilities(
        likes = true,
        ratings = false,
        radio = true,
        emptyPlaylists = true,
        downloads = true,
        originalDownload = true,
        serverScan = true,
        libraries = true,
    )

    override fun createClient(source: MusicSource): MusicServer? =
        if (source.baseUrl.isBlank() || source.token.isBlank() || source.userId.isBlank()) {
            null
        } else {
            JellyfinClient(
                source.baseUrl,
                source.token,
                source.userId,
                source.playerName.ifBlank { null },
                source.deviceId.ifBlank { JellyfinClient.LEGACY_DEVICE_ID },
            )
        }

    override val ui: SourceUi get() = JellyfinUi

    /** Matched by the user id — a GUID, so one account on one server. */
    override fun isSameServer(existing: MusicSource, incoming: MusicSource): Boolean =
        existing.userId.isNotBlank() && existing.userId == incoming.userId

    /** Ends the sign-in on the server too, so the phone stops being listed there — see [JellyfinClient.signOut]. */
    override suspend fun signOut(source: MusicSource) {
        val client = createClient(source) as? JellyfinClient ?: return
        try {
            runCatching { withTimeoutOrNull(SIGN_OUT_TIMEOUT_MS) { client.signOut() } }
        } finally {
            client.close()
        }
    }
}

private const val SIGN_OUT_TIMEOUT_MS = 5_000L

private object JellyfinUi : SourceUi {
    override val addEntries = listOf(AddEntry("Jellyfin Server", AddIcon.ADD, AddAction.OpenScreen { JellyfinLinkScreen(it) }))

    // Signing in again, here rather than by logging out and adding the
    // server anew, keeps its library and downloads.
    override fun connection(source: MusicSource) =
        ConnectionRow("Sign in again") { JellyfinLinkScreen(it, again = source) }
}

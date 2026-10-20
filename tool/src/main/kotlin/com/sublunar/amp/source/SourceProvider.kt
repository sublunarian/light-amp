package com.sublunar.amp.source

import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat
import com.sublunar.amp.playback.output.OutputFamily

/**
 * Everything shared code needs from one kind of source.
 *
 * One object per [SourceKind], listed in [Providers]. Shared code asks a
 * source's provider rather than testing its kind, so what a kind can do is
 * said once, in its own file, instead of in a branch in every file that cares.
 *
 * A provider holds nothing of its own. What one source is lives on its
 * MusicSource record, and the provider is looked up by that record's kind.
 */
interface SourceProvider {
    val kind: SourceKind

    /** The formats a stream from this kind may be asked for. Empty means played as stored, with nothing to choose. */
    val streamFormats: List<StreamFormat>

    /** What this kind can do, less whatever [features] says its server answered for as missing. */
    fun capabilities(features: ServerFeatures?): SourceCapabilities

    /**
     * The client that talks to [source], or null where there is none: no
     * server behind this kind at all, or a source missing what its server
     * needs to be asked anything — better no client than one that fails on
     * every call.
     */
    fun createClient(source: MusicSource): MusicServer?

    /** What screens show of this kind — see [SourceUi]. A getter, so loading a provider never loads screens. */
    val ui: SourceUi

    /**
     * Log Out's word to the server, for a kind whose server keeps a sign-in
     * of its own to end. Best effort and brief: the source is forgotten
     * whatever happens here, and nothing waits on a server that may be gone.
     */
    suspend fun signOut(source: MusicSource) {}

    /** Music that is files on this phone — see [PhoneLibrary]. Null for every kind with a server. */
    val phoneLibrary: PhoneLibrary? get() = null

    /**
     * What a chosen library narrows, as this kind's own app does it. True: the songs
     * inside each playlist, with every playlist listed. False: the playlists listed,
     * to those holding a song in that library, with every song inside shown.
     */
    val libraryNarrowsPlaylistSongs: Boolean get() = true

    /**
     * Somewhere else a source of this kind can play to, besides what the
     * phone offers itself: players its server steers. Empty for most kinds.
     */
    val outputFamilies: List<OutputFamily> get() = emptyList()

    /**
     * Whether [incoming], a sign-in under a new id, is the server and account
     * [existing] already is — so it updates that source in place rather than
     * becoming a second one — see mergeSource. Both are of this kind. False
     * unless the kind has an id that names its server and account for
     * certain: without one, a second sign-in is a second source.
     */
    fun isSameServer(existing: MusicSource, incoming: MusicSource): Boolean = false

    /**
     * After a failed sync: [source] at another address of the same server
     * that answers, or null where there is none to try — and for every kind
     * that keeps only the one address it was given.
     */
    suspend fun relocate(source: MusicSource): MusicSource? = null

    /**
     * [source]'s addresses as its server's directory lists them now, best
     * first — null where there is no directory to ask, it couldn't be asked,
     * or it doesn't list this server. Asked, at most once a minute, when the
     * address in use stops answering — see SourceRoute.
     */
    suspend fun advertisedAddresses(source: MusicSource): List<String>? = null

    /**
     * The first of [uris], in their order, at which [source]'s server answers —
     * null where none does, and for every kind that keeps only the one address.
     */
    suspend fun firstAnswering(uris: List<String>, source: MusicSource): String? = null
}

/** What a kind of source can do — read through the matching `supports…` on MusicSource, which is what the screens ask. */
data class SourceCapabilities(
    val likes: Boolean,
    val ratings: Boolean,
    val radio: Boolean,
    val emptyPlaylists: Boolean,
    val downloads: Boolean,
    val originalDownload: Boolean,
    val serverScan: Boolean,
    val libraries: Boolean,
)

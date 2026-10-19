package com.sublunar.amp.source

import com.sublunar.amp.data.MusicServer
import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerFeatures
import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.data.StreamFormat

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

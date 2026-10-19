package com.sublunar.amp.source

import com.sublunar.amp.data.SourceKind
import com.sublunar.amp.source.jellyfin.JellyfinProvider
import com.sublunar.amp.source.local.LocalProvider
import com.sublunar.amp.source.plex.PlexProvider
import com.sublunar.amp.source.subsonic.SubsonicProvider

/**
 * Every kind of source Amp knows, in the order they are offered.
 *
 * Written out by hand rather than discovered: a new kind is a line here, plus
 * its [SourceKind] entry, which is the name its sources are stored under.
 */
object Providers {
    val all: List<SourceProvider> = listOf(SubsonicProvider, PlexProvider, JellyfinProvider, LocalProvider)

    private val byKind = all.associateBy { it.kind }

    /** The provider for [kind]. Every kind has one — ProvidersTest fails the build otherwise. */
    fun of(kind: SourceKind): SourceProvider = byKind.getValue(kind)
}

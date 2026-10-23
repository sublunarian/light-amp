package com.sublunar.amp.source

import com.sublunar.amp.data.MusicSource
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
    val all: List<SourceProvider> = listOf(LocalProvider, PlexProvider, JellyfinProvider, SubsonicProvider)

    private val byKind = all.associateBy { it.kind }

    /** The provider for [kind]. Every kind has one — ProvidersTest fails the build otherwise. */
    fun of(kind: SourceKind): SourceProvider = byKind.getValue(kind)

    /**
     * Every way to add a source, given the ones there are already: the one
     * list both Add Source and the first-run screen show. Least effort first:
     * what needs no server of your own, then the servers.
     */
    fun addEntries(existing: List<MusicSource>): List<AddEntry> =
        all.flatMap { provider ->
            provider.ui.addEntries.filter { entry ->
                !entry.singleton || existing.none { it.kind == provider.kind }
            }
        }.sortedBy { it.selfHosted }

    /** What the kinds cover, where their names alone don't say. */
    val addNotes: List<String> get() = all.mapNotNull { it.ui.addNote }
}

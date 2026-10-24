package com.sublunar.amp.source.plex.companion

import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputFamily
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget

/**
 * Plex players — the Apple TV's Plex app, another Plexamp — steered over
 * Plex's Companion protocol. Offered by the Plex provider, so only a Plex
 * source lists them; [PlexCompanionOutput] does the playing.
 */
object PlexCompanionFamily : OutputFamily {
    override val id = "plex-companion"

    // What a player that doesn't own its volume — an Apple TV feeding a
    // receiver — leaves the fader saying instead of a level.
    override val volumeElsewhere = "on the TV"

    override fun create(host: OutputHost): Output = PlexCompanionOutput(host)
}

/** A Plex player, as the server, plex.tv or the network named it. */
class PlexCompanionTarget(val player: PlexPlayer) : OutputTarget {
    override val id: String get() = player.id
    override val name: String get() = player.name
}

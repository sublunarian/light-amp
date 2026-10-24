package com.sublunar.amp.playback.output.dlna

import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputFamily
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget

/**
 * DLNA speakers and receivers: a renderer on the local network is handed the
 * server's stream URL and steered over UPnP. [DlnaCast] speaks the protocol,
 * [DlnaOutput] does the playing.
 *
 * All of DLNA is Amp's own code in this folder, needing nothing the sandbox
 * withholds. Nothing outside it names it but Outputs, so taking DLNA out is
 * deleting this folder, its tests' folder and that one line.
 */
object DlnaFamily : OutputFamily {
    override val id = "dlna"

    override val logTag = "AmpCast"

    // A renderer tells the server nothing: the phone reports for it.
    override val reportsItself = false

    override val needsLan = true

    override fun create(host: OutputHost): Output {
        // Speakers are reached outside NetworkGate's wall, so the link's rules
        // stand in front of DLNA here, before the output can reach anything.
        // Every search, probe and control call asks them afresh, which a
        // handful of calls a second can afford.
        DlnaCast.allowsNetwork = { host.rules().mayUseLan }
        return DlnaOutput(host)
    }
}

/** A renderer found on the network. */
class DlnaTarget(val renderer: DlnaRenderer) : OutputTarget {
    override val id: String get() = renderer.id
    override val name: String get() = renderer.name
}

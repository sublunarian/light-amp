package com.sublunar.amp.playback.output.dlna

import com.sublunar.amp.data.LinkRules
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

    override fun create(host: OutputHost): Output = DlnaOutput(host)

    // LAN only: every search, probe and control call asks the rules afresh,
    // which a handful of calls a second can afford.
    override fun install(rules: () -> LinkRules) {
        DlnaCast.allowsNetwork = { rules().mayUseLan }
    }
}

/** A renderer found on the network. */
class DlnaTarget(val renderer: DlnaRenderer) : OutputTarget {
    override val id: String get() = renderer.id
    override val name: String get() = renderer.name
}

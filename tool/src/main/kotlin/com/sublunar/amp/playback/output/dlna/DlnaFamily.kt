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

    // The renderer is handed the server's address.
    override val needsServer = true

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

    // The host the renderer's own control endpoint is on.
    override val address: String? get() = runCatching { java.net.URL(renderer.controlUrl).host }.getOrNull()

    // Many makers end the device's UUID with its MAC address (a Denon's
    // reads uuid:…-0005cdd62096); a random UUID's last twelve digits will
    // simply never match another family's.
    override val hardwareId: String? get() = macAtEnd(renderer.id)
}

/** The last twelve hex digits of a UDN, lower-cased, if it ends in twelve. */
internal fun macAtEnd(udn: String): String? {
    val tail = udn.trim().takeLast(12)
    return tail.lowercase().takeIf { it.length == 12 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

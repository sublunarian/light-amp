package com.sublunar.amp.playback.output.airplay

import android.util.Log
import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputFamily
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * AirPlay speakers, as AirPlay 1 (RAOP): the phone sends the sound itself,
 * lossless at CD quality. [AirPlayOutput] does the playing, [RaopSession]
 * speaks the protocol, [Feed] and [Streamer] make and pace the sound, and
 * [Mdns] finds the speakers.
 *
 * Only speakers that take plain AirPlay 1 are listed: none that wants a
 * password, a PIN, pairing or encryption (see [AirPlayReceiver.whyNot]),
 * which leaves out Apple TVs and Macs. AirPlay is a trademark of Apple Inc.;
 * Amp isn't affiliated with Apple.
 *
 * Nothing outside this folder names it but Outputs, so taking AirPlay out is
 * deleting this folder, its tests' folder and that one line.
 */
object AirPlayFamily : OutputFamily {
    override val id = "airplay"

    override val logTag = "AmpAirPlay"

    // The phone plays and the speaker only listens: the phone reports, as for its own playing.
    override val reportsItself = false

    override val needsLan = true

    override val groups = true

    /**
     * Whether a speaker may be spoken to right now: closed until [create],
     * then the link's rules — Wi-Fi, and not a metered hotspot in Wi-Fi
     * Only. Speakers are reached outside NetworkGate's wall, as DLNA's are.
     */
    @Volatile
    internal var allowsNetwork: () -> Boolean = { false }

    override fun create(host: OutputHost): Output {
        allowsNetwork = { host.rules().mayUseLan }
        return AirPlayOutput(host)
    }

    private const val SERVICE = "_raop._tcp.local"
    private const val LISTEN_MS = 2_500L

    /** The speakers Amp can play to, after a few seconds of listening. None off Wi-Fi. */
    internal suspend fun discover(): List<AirPlayReceiver> = withContext(Dispatchers.IO) {
        if (!allowsNetwork()) return@withContext emptyList()
        val services = try {
            Mdns.browse(SERVICE, LISTEN_MS)
        } catch (e: IOException) {
            Log.i(logTag, "search failed (${e::class.simpleName})")
            return@withContext emptyList()
        }
        services.mapNotNull { service ->
            AirPlayReceiver.whyNot(service)?.let { why ->
                Log.i(logTag, "left out ${service.instance.substringBefore("._raop.").substringAfter('@')}: $why")
            }
            AirPlayReceiver.from(service)
        }.distinctBy { it.id }
    }
}

/** A speaker found on the network. */
class AirPlayTarget internal constructor(internal val receiver: AirPlayReceiver) : OutputTarget {
    override val id: String get() = receiver.id
    override val name: String get() = receiver.name
    override val address: String get() = receiver.address

    // The hex before the @ in its name, which every speaker seen makes its MAC.
    override val hardwareId: String? get() = receiver.deviceId.takeIf { it.length == 12 }

    override val isSpeaker: Boolean get() = receiver.isSpeaker
}

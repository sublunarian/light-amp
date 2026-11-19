package com.sublunar.amp.playback.output.airplay

import com.sublunar.amp.log.Log
import com.sublunar.amp.playback.output.Output
import com.sublunar.amp.playback.output.OutputFamily
import com.sublunar.amp.playback.output.OutputHost
import com.sublunar.amp.playback.output.OutputTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
        // The AirPlay record beside each speaker's says who it lets in; heard at the same time.
        val known = recentlySeen()
        val access = async { runCatching { Mdns.browse(AIRPLAY_SERVICE, LISTEN_MS, known) }.getOrDefault(emptyList()) }
        val services = try {
            Mdns.browse(SERVICE, LISTEN_MS, known)
        } catch (e: IOException) {
            Log.i(logTag, "search failed (${e::class.simpleName})")
            return@withContext emptyList()
        }
        val ownerOnly = ownerOnly(access.await())
        remember(services.map { it.address })
        services.mapNotNull { service ->
            val name = service.instance.substringBefore("._raop.").substringAfter('@')
            AirPlayReceiver.whyNot(service)?.let { why -> Log.i(logTag, "left out $name: $why") }
            AirPlayReceiver.from(service)?.takeUnless { r ->
                (r.deviceId in ownerOnly).also { if (it) Log.i(logTag, "left out $name: it lets only its owner's account play") }
            }
        }.distinctBy { it.id }
    }

    /**
     * The device ids (as in a speaker's RAOP name, lower-case hex) of those
     * that let only their owner's account AirPlay to them: a Mac set to
     * "Current User" says `act=2`, and turns everyone else away.
     */
    internal fun ownerOnly(airplay: List<Mdns.Service>): Set<String> =
        airplay.filter { it.txt["act"] == "2" }
            .mapNotNull { it.txt["deviceid"]?.replace(":", "")?.lowercase() }
            .toSet()

    private const val AIRPLAY_SERVICE = "_airplay._tcp.local"

    /**
     * Where speakers answered lately, kept only while Amp runs, so the next
     * search can ask them directly. 75 minutes, as long as an iPhone keeps
     * a speaker it heard: the life its record gives itself.
     */
    private val seen = ConcurrentHashMap<String, Long>()
    private const val SEEN_FOR_NS = 75 * 60 * 1_000_000_000L

    internal fun remember(addresses: List<String>, now: Long = System.nanoTime()) {
        addresses.forEach { seen[it] = now }
    }

    internal fun recentlySeen(now: Long = System.nanoTime()): List<String> {
        seen.entries.removeIf { now - it.value > SEEN_FOR_NS }
        return seen.keys.toList()
    }

    internal fun forgetSeen() = seen.clear()
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

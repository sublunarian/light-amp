package com.sublunar.amp.data

import com.thelightphone.sdk.LightConnectivity
import com.thelightphone.sdk.NetworkStatus
import com.thelightphone.sdk.SealedLightContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What the connection costs, from the SDK's [LightConnectivity].
 *
 * It reads the *active* network, the route the bytes will actually take: a
 * Wi-Fi that stops validating keeps its address while Android moves traffic to
 * cellular, so "a wlan interface with an address" is no answer. The callback
 * makes changes land at once.
 *
 * Gates ask about *metered-ness*, not "is it Wi-Fi": a phone-hotspot Wi-Fi is
 * metered and is treated as the cellular it rides on. The settings keep saying
 * "Wi-Fi", because that is the phone in most hands.
 */
object Connectivity {

    /** Metered until told otherwise, so the gap before [bind] errs cheap. */
    private val status = MutableStateFlow(
        NetworkStatus(isConnected = false, isWifi = false, isMetered = true),
    )

    /** The system's answer, kept for [isUnmeteredNow]; null until [bind]. */
    @Volatile
    private var source: LightConnectivity? = null

    /** Wired once at boot — see App.boot. */
    fun bind(context: SealedLightContext, scope: CoroutineScope) {
        val connectivity = context.connectivity
        source = connectivity
        status.value = runCatching { connectivity.currentStatus }
            .getOrDefault(status.value)
        scope.launch {
            connectivity.observeNetworkStatus().collect { status.value = it }
        }
    }

    /**
     * [isUnmetered] as of this instant, asked of the system rather than read
     * from the last callback, which can arrive after the socket would have.
     *
     * For the gates in front of a connection — see NetworkGate. Two binder
     * reads, so it is asked once per connection, not once per byte. The flow is
     * brought up to date on the way, so the courtesy checks see the same answer.
     * Before [bind] there is nothing to ask, and the cached answer errs metered.
     */
    fun isUnmeteredNow(): Boolean {
        val now = source?.let { runCatching { it.currentStatus }.getOrNull() } ?: return isUnmetered()
        status.value = now
        return now.isConnected && !now.isMetered
    }

    val network: StateFlow<NetworkStatus> = status

    /**
     * Ask the system now and publish the answer — for the moments a callback
     * can't be relied on: the app returning to the front, and a wait that
     * nothing else is going to end. See [isUnmeteredNow] for the cost.
     */
    fun refresh() {
        isUnmeteredNow()
    }

    /**
     * Whether there is a local network to speak to at all — the question a LAN
     * broadcast asks. Not [isUnmetered]: a phone hotspot is Wi-Fi and metered,
     * so it costs money *and* has neighbours.
     */
    fun isOnWifi(): Boolean = status.value.isConnected && status.value.isWifi

    /** Bytes are free here: Wi-Fi, or any other unmetered connection. */
    fun isUnmetered(): Boolean =
        status.value.isConnected && !status.value.isMetered

    val unmetered: Flow<Boolean> =
        status.map { it.isConnected && !it.isMetered }.distinctUntilChanged()

    /**
     * Emits whenever the connection becomes something different — coming back
     * after an outage, or changing what it costs. Both: cellular to Wi-Fi never
     * stops being connected, and a plane landing in Make it Hurt never changes
     * the price.
     */
    val changed: Flow<Pair<Boolean, Boolean>> =
        status.map { it.isConnected to it.isMetered }.distinctUntilChanged()
}

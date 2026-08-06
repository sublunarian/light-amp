package com.sublunar.amp.data

import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Whether the phone is on Wi-Fi, without a `Context`.
 *
 * `ConnectivityManager` is the obvious answer and is unreachable: it needs
 * `getSystemService`, which the plugin sandbox forbids outright, and a `Context`,
 * which a tool module cannot obtain. Enumerating network interfaces is plain
 * `java.net`, needs no permission beyond what the tool already declares, and is
 * good enough for the question being asked — is there a usable Wi-Fi link?
 *
 * A `wlan` interface that is up and holds a real IPv4 address means associated
 * with an access point. Loopback and link-local (169.254.x.x, i.e. a failed DHCP)
 * do not count.
 */
object Connectivity {

    /** How often [wifiConnected] re-checks. Cheap, and Wi-Fi comes and goes. */
    private const val POLL_MS = 5_000L

    fun isOnWifi(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { nic ->
            nic.name.startsWith("wlan", ignoreCase = true) &&
                nic.isUp &&
                nic.inetAddresses.toList().any { address ->
                    address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                }
        }
    }.getOrDefault(false)

    /** Polled rather than event-driven: callbacks would need a Context too. */
    val wifiConnected: Flow<Boolean> = flow {
        while (true) {
            emit(isOnWifi())
            delay(POLL_MS)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}

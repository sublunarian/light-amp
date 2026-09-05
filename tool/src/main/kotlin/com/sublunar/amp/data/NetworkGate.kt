package com.sublunar.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.api.createClientPlugin
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import okhttp3.Dns

/**
 * The wall in front of every byte this app sends: may the network be used
 * right now?
 *
 * The rule itself is App.networkAllowed — false only in Wi-Fi Only on a
 * metered link. This is where it is *enforced*, rather than consulted. The
 * data modes used to be a set of courtesy checks, each code path asking
 * before it started work, and a path that didn't ask (a stream already in the
 * queue, a reconnect, a preload) used cellular data in the mode whose whole
 * promise is that it never does. Now the question is put at the transport,
 * where the bytes are, so a path that forgot to ask is refused all the same:
 *
 * - every HTTP client is built through [httpClient], which asks before each
 *   request is sent, before each name is looked up, and before each socket is
 *   opened — the last two so that nothing reaching the engine another way,
 *   or reusing a pooled connection, can slip past the first;
 * - the platform player asks through the SDK's `LightAudioNetworkPolicy`,
 *   wired to the same rule at boot;
 * - the two raw paths — the download transfer and the Plex LAN broadcast —
 *   ask [check] and [isOpen] directly.
 *
 * Refused means an [IOException], the same failure a dead network produces,
 * so every caller that survives an outage survives the rule — only sooner.
 */
object NetworkGate {

    /**
     * The rule, wired at boot to App.networkAllowed. Closed until then: a
     * client that exists before the app has decided cannot be allowed to
     * guess.
     */
    @Volatile
    var policy: () -> Boolean = { false }

    /** The one phrase for this wait, wherever it is shown. */
    const val WAITING_FOR_WIFI = "Waiting for Wi-Fi"

    fun isOpen(): Boolean = policy()

    /** Throws [NetworkGateClosedException] unless the network may be used now. */
    fun check() {
        if (!policy()) throw NetworkGateClosedException()
    }

    /** A ktor client whose every request, name lookup and socket asks first. */
    fun httpClient(config: HttpClientConfig<OkHttpConfig>.() -> Unit = {}): HttpClient =
        HttpClient(OkHttp) {
            engine {
                config {
                    dns(gatedDns)
                    socketFactory(gatedSockets)
                }
            }
            install(gatePlugin)
            config()
        }

    private val gatePlugin = createClientPlugin("NetworkGate") {
        onRequest { _, _ -> check() }
    }

    private val gatedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            check()
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val gatedSockets = object : SocketFactory() {
        private val system: SocketFactory = getDefault()

        override fun createSocket(): Socket {
            check()
            return system.createSocket()
        }

        override fun createSocket(host: String, port: Int): Socket {
            check()
            return system.createSocket(host, port)
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            check()
            return system.createSocket(host, port, localHost, localPort)
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            check()
            return system.createSocket(host, port)
        }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
            check()
            return system.createSocket(address, port, localAddress, localPort)
        }
    }
}

/**
 * The network may not be used right now — Wi-Fi Only, on a metered link.
 *
 * Its message is the words the screens use for the same wait, so a caller
 * that surfaces `e.message` says the right thing without knowing the type.
 */
class NetworkGateClosedException : IOException(NetworkGate.WAITING_FOR_WIFI)

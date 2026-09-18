package com.sublunar.amp.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.api.createClientPlugin
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

/**
 * The wall in front of every byte this app sends: may the network be used
 * right now?
 *
 * The rule itself is App.networkAllowed — false only in Wi-Fi Only on a
 * metered link. This is where it is *enforced*, rather than consulted. The
 * data modes used to be a set of courtesy checks, each code path asking
 * before it started work, and a path that didn't ask (a stream already in the
 * queue, a reconnect, a redirect) used cellular data in the mode whose whole
 * promise is that it never does. Now the question is put at the transport:
 *
 * - **before each request leaves the client** (the ktor hook) — the cheap
 *   early-out, so nothing is built that would only be refused;
 * - **on the wire, for every attempt** (the OkHttp network interceptor) — a
 *   redirect hop, a silent retry, an auth follow-up, and above all a request
 *   riding a *pooled* connection, none of which pass the hook above or the
 *   two below. It also wraps the body, so a response already arriving is cut
 *   when the rule closes;
 * - **before each name lookup and each new socket** (Dns, SocketFactory) —
 *   the only checks ahead of the handshake. A pooled connection never reaches
 *   them, which is why the interceptor exists.
 *
 * And because the rule answers for the phone's *default* network, not for the
 * network a socket was opened on, closing is not only refusing: [slam] shuts
 * every socket this wall has handed out and empties the pool. A connection
 * opened over cellular in another mode stays usable for minutes and would
 * carry a whole sync after the switch to Wi-Fi Only — so entering that mode
 * slams, as does any check that finds the rule closed.
 *
 * What this cannot do: bind a socket to the Wi-Fi network. That needs the
 * platform's ConnectivityManager, which a sandboxed tool is not given. So if
 * the default network moves between the socket check and `connect()`, one
 * handshake can leave on the wrong link before the interceptor refuses the
 * request — a few kilobytes, in a race measured in milliseconds, and never a
 * request or a byte of a response. See tool/docs/SDK-GAPS.md.
 *
 * The platform player is outside this wall — it fetches with its own stack.
 * See App.networkAllowed for what stands in front of it.
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

    /** Asked of the system, now. Two binder reads — once per request, not per byte. */
    fun isOpen(): Boolean = policy()

    /** Throws [NetworkGateClosedException] unless the network may be used now; slams if not. */
    fun check() {
        val open = policy()
        // The newest live answer always wins over the cached one the body
        // reads use — see isOpenRecently.
        recentAnswer = open
        recentAtMs = System.nanoTime() / 1_000_000
        if (!open) {
            slam()
            throw NetworkGateClosedException()
        }
    }

    /**
     * Whether the link is metered right now, wired at boot. Asked only when a
     * socket is handed out, to remember which ones were opened on a link that
     * costs money — see [slamMetered].
     */
    @Volatile
    var isMetered: () -> Boolean = { true }

    // --- The kill switch -----------------------------------------------------

    /** Every raw socket handed out and possibly still open. Weak: a closed, dropped socket just leaves. */
    private val sockets: MutableSet<Socket> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /**
     * One pool for every client built here, so there is one thing to empty.
     * The cost: ktor empties a client's pool when that client is closed, and
     * this is everyone's — so closing one client (a source switch, the
     * throwaway sign-in client) drops the others' idle connections too. A few
     * extra handshakes after a source switch, accepted for a wall with one door.
     */
    private val pool = ConnectionPool(5, 2, TimeUnit.MINUTES)

    /**
     * Every pool handed out, the shared one included, so [slam] can empty
     * them all. Weak: a client that is gone takes its pool with it.
     */
    private val pools: MutableSet<ConnectionPool> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ConnectionPool, Boolean>()))
            .also { it.add(pool) }

    /**
     * Close every connection this wall has handed out — idle, in flight,
     * HTTP/2 or not — and empty the pool.
     *
     * The raw socket, not the stream on top of it: refusing to *read* does not
     * stop bytes arriving (HTTP/2 keeps filling a 16 MiB window, HTTP/1 drains
     * on close), and a TLS close would block on the very link being left.
     * Callers see an IOException, as they would had the network gone.
     */
    fun slam() {
        val open = synchronized(sockets) { sockets.toList().also { sockets.clear() } }
        synchronized(meteredSockets) { meteredSockets.clear() }
        if (open.isNotEmpty()) lastSlamAtMs = System.nanoTime() / 1_000_000
        for (socket in open) runCatching { socket.close() }
        val all = synchronized(pools) { pools.toList() }
        for (p in all) runCatching { p.evictAll() }
    }

    /**
     * The phone moved to a different network. Every connection there is
     * belongs to the old one — a socket never follows the phone across — and
     * left in the pool it is reused for the next request, which then waits on
     * a path that is going away: a ping that answers in fifty milliseconds
     * took six seconds and was read as "the server is gone", three times in a
     * row, in the half minute after the phone came home to Wi-Fi. Every
     * caller survives a dropped connection; none survives a stalled one well.
     */
    fun linkChanged() {
        slam()
    }

    /**
     * Close only the connections that were opened on a metered link.
     *
     * For the moment the mode becomes Wi-Fi Only. A socket opened over
     * cellular in another mode stays usable for minutes — a socket never
     * changes network, and the rule only ever answers for the phone's
     * *default* one — so with Wi-Fi now the default the rule reads open while
     * a pooled cellular connection carries the next request. Those go. What
     * was opened on Wi-Fi is free and is left alone: shutting it would fail
     * the downloads and the sync in flight, and tell the reachability ping
     * that a server which is plainly there has gone.
     */
    fun slamMetered() {
        val open = synchronized(meteredSockets) { meteredSockets.toList().also { meteredSockets.clear() } }
        if (open.isNotEmpty()) lastSlamAtMs = System.nanoTime() / 1_000_000
        for (socket in open) runCatching { socket.close() }
    }

    /** Whether sockets were shut by this wall at or after [sinceMs] (monotonic ms) — an I/O failure then is ours. */
    fun slammedSince(sinceMs: Long): Boolean = lastSlamAtMs >= sinceMs

    @Volatile private var lastSlamAtMs = Long.MIN_VALUE

    /** The subset of [sockets] opened while the link was metered. */
    private val meteredSockets: MutableSet<Socket> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    // --- Clients -------------------------------------------------------------

    /**
     * A ktor client whose every request, attempt, name lookup and socket asks
     * first.
     *
     * [isolated] gives it a pool of its own. The shared pool means one HTTP/2
     * connection per server for everyone, which is right for bulk work and
     * wrong for the one small request a person is waiting on: multiplexed
     * onto a connection that has stalled, a ping stalls with it. A pool of its
     * own is a connection of its own. Still behind the wall, still slammed.
     */
    fun httpClient(isolated: Boolean = false, config: HttpClientConfig<OkHttpConfig>.() -> Unit = {}): HttpClient =
        HttpClient(OkHttp) {
            config()
            // After the caller's block, so nothing there can replace the wall.
            engine {
                config { wall(if (isolated) ownPool() else pool) }
            }
            install(gatePlugin)
        }

    private fun ownPool(): ConnectionPool =
        ConnectionPool(2, 2, TimeUnit.MINUTES).also { pools.add(it) }

    /**
     * A plain OkHttp client behind the same wall, for transfers that stream
     * straight to disk — see DownloadStore. Redirects are followed, and each
     * hop passes the network interceptor like any other attempt.
     */
    fun transferClient(connectTimeoutMs: Long, readTimeoutMs: Long): OkHttpClient =
        OkHttpClient.Builder()
            .apply { wall(pool) }
            // After wall(), so these win over the API defaults it sets.
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

    private fun OkHttpClient.Builder.wall(with: ConnectionPool) {
        // A page of a library over weak reception can stall for longer than
        // OkHttp's stock ten seconds between bytes without being dead. A
        // transfer that streams to disk sets its own (see transferClient).
        connectTimeout(API_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        readTimeout(API_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        dns(gatedDns)
        socketFactory(gatedSockets)
        connectionPool(with)
        addNetworkInterceptor(onTheWire)
    }

    private val gatePlugin = createClientPlugin("NetworkGate") {
        onRequest { _, _ -> check() }
    }

    private val onTheWire = Interceptor { chain ->
        // Live, not cached: a stale "open" here lets a whole request out.
        // Thrown before proceed(), which OkHttp does not retry.
        check()
        val response = chain.proceed(chain.request())
        response.gated()
    }

    private fun Response.gated(): Response {
        val body = body
        return newBuilder().body(GatedBody(body)).build()
    }

    /**
     * A body that stops arriving when the rule closes. The per-read answer is
     * cached for a quarter of a second — two binder reads per 8 KiB segment
     * would be thousands per track — which is safe here and only here: a
     * socket never moves between networks, so the only way this body's link
     * becomes forbidden mid-flight is the *mode* changing, and that slams
     * every socket by itself.
     */
    private class GatedBody(private val inner: ResponseBody) : ResponseBody() {
        private val source: BufferedSource by lazy {
            object : ForwardingSource(inner.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!isOpenRecently()) {
                        slam()
                        throw NetworkGateClosedException()
                    }
                    return super.read(sink, byteCount)
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = inner.contentType()
        override fun contentLength(): Long = inner.contentLength()
        override fun source(): BufferedSource = source
        override fun close() = inner.close()
    }

    @Volatile private var recentAnswer = false
    @Volatile private var recentAtMs = 0L

    private fun isOpenRecently(): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (now - recentAtMs > RECENT_MS) {
            recentAnswer = policy()
            recentAtMs = now
        }
        return recentAnswer
    }

    private const val RECENT_MS = 250L
    private const val API_CONNECT_TIMEOUT_MS = 15_000L
    private const val API_READ_TIMEOUT_MS = 30_000L

    private val gatedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            check()
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val gatedSockets = object : SocketFactory() {
        private val system: SocketFactory = getDefault()

        private fun handOut(make: () -> Socket): Socket {
            check()
            return make().also {
                sockets.add(it)
                if (isMetered()) meteredSockets.add(it)
            }
        }

        override fun createSocket(): Socket = handOut { system.createSocket() }

        override fun createSocket(host: String, port: Int): Socket =
            handOut { system.createSocket(host, port) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            handOut { system.createSocket(host, port, localHost, localPort) }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            handOut { system.createSocket(host, port) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            handOut { system.createSocket(address, port, localAddress, localPort) }
    }
}

/**
 * The network may not be used right now — Wi-Fi Only, on a metered link.
 *
 * Its message is the words the screens use for the same wait, so a caller
 * that surfaces `e.message` says the right thing without knowing the type.
 */
class NetworkGateClosedException : IOException(NetworkGate.WAITING_FOR_WIFI)

package com.sublunar.amp.playback.output.airplay

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Makes Amp's remote findable on the Wi-Fi it plays over, as [DacpRecords]
 * describes, and answers anyone who asks. Its sockets share port 5353 with
 * the phone's own mDNS service: one bound to the multicast group hears
 * lookups sent to everyone, one bound to this phone's address hears those
 * sent straight here (and sends everything, from 5353 as mDNS requires),
 * and one more does the same over IPv6.
 *
 * With the screen off the phone may drop multicast, so the lookups can go
 * unheard; the announcements are what a speaker mostly goes on, and it
 * keeps them for [DacpRecords.TTL].
 */
internal class DacpAnnouncer private constructor(
    private val records: DacpRecords,
    private val iface: NetworkInterface,
    private val local4: Inet4Address,
    private val local6: Inet6Address?,
    private val log: (String) -> Unit,
) {
    private val group4: InetAddress = InetAddress.getByName("224.0.0.251")
    private val group6: InetAddress = Inet6Address.getByAddress(null, InetAddress.getByName("ff02::fb").address, iface)
    private val schedule = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "AmpAirPlay-dacp-mdns").apply { isDaemon = true } }
    private val sockets = ArrayList<DatagramSocket>()
    private lateinit var send4: MulticastSocket
    private var six: MulticastSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var stopped = false

    private fun open() {
        send4 = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(local4, DacpRecords.MDNS_PORT))
            networkInterface = iface
            timeToLive = HOPS
            hopsForUnicast(this, OsConstants.IPPROTO_IP, OsConstants.IP_TTL)
        }
        sockets += send4
        // The lookups sent to everyone: without these, only announcements and direct lookups work.
        runCatching {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(group4, DacpRecords.MDNS_PORT))
                joinGroup(InetSocketAddress(group4, DacpRecords.MDNS_PORT), iface)
            }
        }.onSuccess { sockets += it; listen(it, direct = false) }
            .onFailure { log("remote: no multicast listening (${it::class.simpleName})") }
        if (local6 != null) {
            runCatching {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("::"), DacpRecords.MDNS_PORT))
                    joinGroup(InetSocketAddress(group6, DacpRecords.MDNS_PORT), iface)
                    networkInterface = iface
                    timeToLive = HOPS
                    hopsForUnicast(this, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_UNICAST_HOPS)
                }
            }.onSuccess { six = it; sockets += it; listen(it, direct = false) }
                .onFailure { log("remote: no IPv6 (${it::class.simpleName})") }
        }
        listen(send4, direct = true)
    }

    /** Probes (the first asking for unicast answers, sent twice), then four announcements 1, 2 and 4 s apart. */
    private fun begin() {
        var at = Random.nextLong(0, 251)
        repeat(PROBES) { i ->
            val qu = i == 0
            schedule.schedule({
                repeat(if (qu) 2 else 1) {
                    multicast(records.hostProbe(qu))
                    multicast(records.instanceProbe(qu))
                }
            }, at, TimeUnit.MILLISECONDS)
            at += PROBE_GAP_MS
        }
        schedule.schedule({ running = !stopped }, at, TimeUnit.MILLISECONDS)
        for (gap in ANNOUNCE_GAPS_MS) {
            at += gap
            schedule.schedule({ if (running) multicast(records.announcement()) }, at, TimeUnit.MILLISECONDS)
        }
    }

    /** The goodbye, three times two seconds apart, then the sockets go. */
    fun stop() {
        if (stopped) return
        stopped = true
        val announced = running
        running = false
        if (announced) {
            repeat(GOODBYES) { i -> schedule.schedule({ multicast(records.goodbye()) }, i * GOODBYE_GAP_MS, TimeUnit.MILLISECONDS) }
        }
        schedule.schedule({
            sockets.forEach { it.close() }
            schedule.shutdown()
        }, if (announced) (GOODBYES - 1) * GOODBYE_GAP_MS + 100 else 0, TimeUnit.MILLISECONDS)
    }

    private fun multicast(packet: ByteArray) {
        runCatching { send4.send(DatagramPacket(packet, packet.size, group4, DacpRecords.MDNS_PORT)) }
        six?.let { s -> runCatching { s.send(DatagramPacket(packet, packet.size, group6, DacpRecords.MDNS_PORT)) } }
    }

    private fun listen(socket: MulticastSocket, direct: Boolean) {
        Thread({
            val buffer = ByteArray(9000)
            while (!socket.isClosed) {
                val p = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(p)
                } catch (e: Exception) {
                    if (socket.isClosed) return@Thread
                    continue
                }
                val from = p.address
                val v6 = socket === six
                if (v6 && from is Inet4Address) continue
                if (from == local4 || from == local6) continue
                if (!running) continue
                val reply = records.replyTo(p.data.copyOf(p.length), p.length, p.port, direct) ?: continue
                reply(reply, from, p.port, v6)
            }
        }, "AmpAirPlay-dacp-listen").apply { isDaemon = true }.start()
    }

    private fun reply(reply: DacpRecords.Reply, to: InetAddress, port: Int, v6: Boolean) {
        val send = Runnable {
            if (!running) return@Runnable
            runCatching {
                if (reply.way == DacpRecords.Way.MULTICAST) {
                    if (v6) six?.send(DatagramPacket(reply.bytes, reply.bytes.size, group6, DacpRecords.MDNS_PORT))
                    else send4.send(DatagramPacket(reply.bytes, reply.bytes.size, group4, DacpRecords.MDNS_PORT))
                } else {
                    (if (v6) six else send4)?.send(DatagramPacket(reply.bytes, reply.bytes.size, to, port))
                }
            }
        }
        // Shared answers to everyone wait a moment, so several responders don't collide.
        val delayMs = if (reply.way == DacpRecords.Way.MULTICAST && reply.onlyShared) Random.nextLong(20, 121) else 0L
        runCatching { schedule.schedule(send, delayMs, TimeUnit.MILLISECONDS) }
    }

    companion object {
        private const val HOPS = 255
        private const val PROBES = 3
        private const val PROBE_GAP_MS = 250L
        private val ANNOUNCE_GAPS_MS = longArrayOf(0, 1_000, 2_000, 4_000)
        private const val GOODBYES = 3
        private const val GOODBYE_GAP_MS = 2_000L

        /** Announce remote [id], served on [port], on the network [local4] is on; null when that can't be done. */
        fun start(id: String, port: Int, local4: Inet4Address, log: (String) -> Unit): DacpAnnouncer? {
            val iface = runCatching { NetworkInterface.getByInetAddress(local4) }.getOrNull() ?: return null
            val local6 = linkLocal6(iface)
            return try {
                DacpAnnouncer(DacpRecords(id, port, local4, local6), iface, local4, local6, log).also {
                    it.open()
                    it.begin()
                }
            } catch (e: Exception) {
                log("remote: can't announce (${e::class.simpleName})")
                null
            }
        }

        fun linkLocal6(iface: NetworkInterface): Inet6Address? =
            iface.inetAddresses.toList().filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }

        /** RFC 6762 §11: every mDNS packet, unicast ones too, leaves with 255 hops, and some speakers drop anything else. */
        private fun hopsForUnicast(socket: DatagramSocket, level: Int, option: Int) {
            runCatching {
                ParcelFileDescriptor.fromDatagramSocket(socket).use { Os.setsockoptInt(it.fileDescriptor, level, option, HOPS) }
            }
        }
    }
}

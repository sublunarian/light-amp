package com.sublunar.amp.playback.output.airplay

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Asking the local network who offers a service, the way Bonjour does, with
 * nothing but a UDP socket.
 *
 * The query goes to the mDNS group from an ordinary socket rather than from
 * port 5353, which makes it a "legacy unicast" query (RFC 6762 §6.7): every
 * responder answers straight back to the asking port, unicast. So nothing
 * has to join the multicast group or hold Wi-Fi's multicast lock, neither of
 * which a tool may do. Checked on the user's network: a Denon receiver, an
 * AirPort Express, an Apple TV and a Mac each answered at once, with the
 * service's address, port and text record in the same packet.
 */
object Mdns {
    private const val GROUP = "224.0.0.251"
    private const val PORT = 5353

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33

    private const val FOLLOW_UPS = 3

    /** One service instance, put together from the records that name it. */
    data class Service(
        /** The instance's full name, as `0005CDD62096@Denon AVR-X1500H._raop._tcp.local`. */
        val instance: String,
        val host: String,
        val port: Int,
        val address: String,
        val txt: Map<String, String>,
    )

    /**
     * Who offers [serviceType] (as `_raop._tcp.local`), listening [listenMs].
     * The question goes out twice, since UDP may drop one; instances missing
     * a record after the replies are asked for it directly.
     */
    fun browse(serviceType: String, listenMs: Long, socket: DatagramSocket = DatagramSocket()): List<Service> {
        val records = ArrayList<Record>()
        socket.use {
            it.soTimeout = 250
            val group = InetAddress.getByName(GROUP)
            val question = query(listOf(serviceType to TYPE_PTR))
            repeat(2) { _ -> it.send(DatagramPacket(question, question.size, group, PORT)) }
            listen(it, listenMs, records)

            // A responder that sent only the pointer is asked for the rest:
            // its SRV and TXT, then the address of the host its SRV names.
            var asked = emptyList<Pair<String, Int>>()
            repeat(FOLLOW_UPS) { _ ->
                val missing = assemble(serviceType, records).missing
                if (missing.isEmpty() || missing == asked) return@repeat
                asked = missing
                val followUp = queryLabels(missing.map { (name, type) -> labelsOf(name, serviceType) to type })
                it.send(DatagramPacket(followUp, followUp.size, group, PORT))
                listen(it, listenMs / 2, records)
            }
        }
        return assemble(serviceType, records).services
    }

    private fun listen(socket: DatagramSocket, ms: Long, into: MutableList<Record>) {
        val buffer = ByteArray(9000)
        val deadline = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadline) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            runCatching { into += parse(packet.data, packet.length) }
        }
    }

    internal class Assembled(val services: List<Service>, val missing: List<Pair<String, Int>>)

    /** The instances of [serviceType] the [records] describe whole, and the questions that would complete the rest. */
    internal fun assemble(serviceType: String, records: List<Record>): Assembled {
        val type = serviceType.lowercase().trimEnd('.')
        val instances = records.filter { it.type == TYPE_PTR && it.name.lowercase().trimEnd('.') == type }
            .map { it.target }
            .distinct()
        val services = ArrayList<Service>()
        val missing = ArrayList<Pair<String, Int>>()
        for (instance in instances) {
            val key = instance.lowercase()
            val srv = records.lastOrNull { it.type == TYPE_SRV && it.name.lowercase() == key }
            val txt = records.lastOrNull { it.type == TYPE_TXT && it.name.lowercase() == key }
            if (srv == null) missing += instance to TYPE_SRV
            if (txt == null) missing += instance to TYPE_TXT
            if (srv == null || txt == null) continue
            val addresses = records.filter { it.type == TYPE_A && it.name.lowercase() == srv.target.lowercase() }
                .map { it.target }
                .distinct()
            // A self-assigned 169.254 address is what a device keeps beside its
            // real one (the AirPort Express does); the phone can't reach it.
            val address = addresses.firstOrNull { !it.startsWith("169.254.") } ?: addresses.firstOrNull()
            if (address == null) {
                missing += srv.target to TYPE_A
                continue
            }
            services += Service(instance, srv.target, srv.port, address, txt.txt)
        }
        return Assembled(services, missing.distinct())
    }

    /**
     * [name]'s labels. An instance name came from a pointer for
     * [serviceType], so it's one label — which may hold dots of its own,
     * "Hi-Fi 2.0" — before the type's; anything else splits at every dot.
     */
    internal fun labelsOf(name: String, serviceType: String): List<String> {
        val type = serviceType.trimEnd('.')
        return if (name.length > type.length + 1 && name.endsWith(".$type", ignoreCase = true)) {
            listOf(name.dropLast(type.length + 1)) + type.split('.')
        } else {
            name.trimEnd('.').split('.')
        }
    }

    /** A query for each (name, type), asking for unicast replies. */
    internal fun query(questions: List<Pair<String, Int>>): ByteArray =
        queryLabels(questions.map { (name, type) -> name.trimEnd('.').split('.') to type })

    private fun queryLabels(questions: List<Pair<List<String>, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v ushr 8 and 0xff)
            out.write(v and 0xff)
        }
        u16(0) // id
        u16(0) // flags: a standard query
        u16(questions.size)
        u16(0)
        u16(0)
        u16(0)
        for ((labels, type) in questions) {
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.UTF_8)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
            u16(type)
            u16(0x8001) // class IN, with the unicast-response bit
        }
        return out.toByteArray()
    }

    /**
     * One resource record. [target] is the pointed-to name (PTR), the host
     * (SRV) or the dotted address (A); [port] is the SRV's; [txt] the TXT's.
     */
    internal data class Record(
        val name: String,
        val type: Int,
        val target: String = "",
        val port: Int = 0,
        val txt: Map<String, String> = emptyMap(),
    )

    /** Every answer, authority and additional record in a DNS message. */
    internal fun parse(data: ByteArray, length: Int): List<Record> {
        val reader = DnsReader(data, length)
        reader.at = 4
        val questions = reader.u16()
        val counts = reader.u16() + reader.u16() + reader.u16()
        repeat(questions) {
            reader.name()
            reader.at += 4
        }
        val records = ArrayList<Record>()
        repeat(counts) {
            val name = reader.name()
            val type = reader.u16()
            reader.u16() // class
            reader.at += 4 // ttl
            val size = reader.u16()
            val start = reader.at
            if (start + size > length) throw IllegalArgumentException("a record runs past the packet")
            when (type) {
                TYPE_PTR -> records += Record(name, type, target = reader.name())
                TYPE_SRV -> {
                    reader.at += 4 // priority, weight
                    val port = reader.u16()
                    records += Record(name, type, target = reader.name(), port = port)
                }
                TYPE_TXT -> records += Record(name, type, txt = txtOf(data, start, size))
                TYPE_A -> if (size == 4) {
                    records += Record(
                        name,
                        type,
                        target = (0 until 4).joinToString(".") { (data[start + it].toInt() and 0xff).toString() },
                    )
                }
            }
            reader.at = start + size
        }
        return records
    }

    /** `key=value` strings, keys lower-cased; a key without `=` reads as present and empty. */
    internal fun txtOf(data: ByteArray, start: Int, size: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var at = start
        while (at < start + size) {
            val length = data[at].toInt() and 0xff
            at++
            if (length == 0 || at + length > start + size) {
                at += length
                continue
            }
            val entry = String(data, at, length, Charsets.UTF_8)
            at += length
            val eq = entry.indexOf('=')
            if (eq == 0) continue
            if (eq < 0) out.putIfAbsent(entry.lowercase(), "") else out.putIfAbsent(entry.substring(0, eq).lowercase(), entry.substring(eq + 1))
        }
        return out
    }

    private class DnsReader(val data: ByteArray, val length: Int) {
        var at = 0

        fun u16(): Int {
            if (at + 2 > length) throw IllegalArgumentException("short packet")
            val v = ((data[at].toInt() and 0xff) shl 8) or (data[at + 1].toInt() and 0xff)
            at += 2
            return v
        }

        /** A name, following compression pointers, leaving [at] after its own bytes. */
        fun name(): String {
            val labels = ArrayList<String>()
            var cursor = at
            var resumeAt = -1
            var jumps = 0
            while (true) {
                if (cursor >= length) throw IllegalArgumentException("a name runs past the packet")
                val len = data[cursor].toInt() and 0xff
                when {
                    len == 0 -> {
                        cursor++
                        break
                    }
                    len and 0xc0 == 0xc0 -> {
                        if (cursor + 1 >= length || ++jumps > 32) throw IllegalArgumentException("bad name pointer")
                        if (resumeAt < 0) resumeAt = cursor + 2
                        cursor = ((len and 0x3f) shl 8) or (data[cursor + 1].toInt() and 0xff)
                    }
                    else -> {
                        if (cursor + 1 + len > length) throw IllegalArgumentException("a label runs past the packet")
                        labels += String(data, cursor + 1, len, Charsets.UTF_8)
                        cursor += 1 + len
                    }
                }
            }
            at = if (resumeAt >= 0) resumeAt else cursor
            return labels.joinToString(".")
        }
    }
}

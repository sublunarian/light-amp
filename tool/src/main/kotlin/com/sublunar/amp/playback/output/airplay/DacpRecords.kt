package com.sublunar.amp.playback.output.airplay

import java.net.Inet4Address
import java.net.Inet6Address
import kotlin.random.Random

/**
 * The mDNS records that let a speaker find Amp's remote-control server (see
 * [DacpRemote]), and every packet [DacpAnnouncer] sends about them. They are
 * shaped as macOS's own responder shapes a DACP registration — the one
 * shape a speaker was seen to find without fail: probes, then four
 * announcements, then answers, then a goodbye.
 *
 * `iTunes_Ctrl_<id>._dacp._tcp` is the name every AirPlay receiver looks up
 * for a sender's remote; it names a protocol, not this app.
 */
internal class DacpRecords(
    val id: String,
    val port: Int,
    private val v4: Inet4Address,
    private val v6: Inet6Address?,
) {
    val host = "amp-${id.takeLast(6).lowercase()}.local"
    val instance = "iTunes_Ctrl_$id.$SERVICE"

    private class Rec(val name: String, val type: Int, val unique: Boolean, val rdata: Packet.() -> Unit)

    // DACP's own version and the remote's database id; nothing naming an Apple product or OS.
    private val txtStrings = listOf("txtvers=1", "Ver=131075", "DbId=$id")

    private val txt = Rec(instance, TXT, true) {
        for (s in txtStrings) {
            val b = s.toByteArray(Charsets.UTF_8)
            u8(b.size)
            bytes(b)
        }
    }
    private val servicesPtr = Rec(SERVICES, PTR, false) { name(SERVICE) }
    private val ptr = Rec(SERVICE, PTR, false) { name(instance) }
    private val srv = Rec(instance, SRV, true) {
        u16(0)
        u16(0)
        u16(port)
        name(host)
    }
    private val aaaa = v6?.let { address -> Rec(host, AAAA, true) { bytes(address.address) } }
    private val a = Rec(host, A, true) { bytes(v4.address) }
    private val hostNsec = Rec(host, NSEC, true) {
        name(host)
        bytes(if (aaaa == null) typeBitmap(A) else typeBitmap(A, AAAA))
    }
    private val instanceNsec = Rec(instance, NSEC, true) {
        name(instance)
        bytes(typeBitmap(TXT, SRV))
    }

    /** The orders macOS writes records in: answers, then additionals. */
    private val answerOrder = listOfNotNull(txt, servicesPtr, ptr, srv, aaaa, a, hostNsec, instanceNsec)
    private val additionalOrder = listOfNotNull(aaaa, a, txt, srv, hostNsec, instanceNsec)

    fun hostProbe(unicastWanted: Boolean): ByteArray = Packet().run {
        question(host, ANY, unicastWanted)
        listOfNotNull(aaaa, a).forEach { authority(it) }
        finish(0, 0)
    }

    fun instanceProbe(unicastWanted: Boolean): ByteArray = Packet().run {
        question(instance, ANY, unicastWanted)
        authority(srv)
        finish(0, 0)
    }

    fun announcement(): ByteArray = Packet().run {
        listOf(txt, servicesPtr, ptr, srv).forEach { answer(it, TTL, flush = true) }
        listOfNotNull(aaaa, a, hostNsec, instanceNsec).forEach { additional(it, TTL, flush = true) }
        finish(0, RESPONSE)
    }

    /** The PTR with no time left to live: speakers drop the remote at once. */
    fun goodbye(): ByteArray = Packet().run {
        answer(ptr, 0, flush = true)
        finish(0, RESPONSE)
    }

    enum class Way { MULTICAST, UNICAST, LEGACY }

    class Reply(val bytes: ByteArray, val way: Way, val onlyShared: Boolean)

    /**
     * The answer to a query that came from [fromPort] — [direct] when it was
     * sent to this phone's own address — or null when it asks nothing of ours.
     * A query from a port other than 5353 is a one-shot ("legacy") lookup:
     * answered to its sender with its id and question echoed, as macOS does.
     */
    fun replyTo(query: ByteArray, length: Int, fromPort: Int, direct: Boolean): Reply? {
        val parsed = runCatching { Query.parse(query, length) }.getOrNull() ?: return null
        if (parsed.isResponse) return null
        val ours = parsed.questions.filter { answersFor(it).isNotEmpty() }
        if (ours.isEmpty()) return null
        val way = when {
            fromPort != MDNS_PORT -> Way.LEGACY
            direct || ours.all { it.unicastWanted } -> Way.UNICAST
            else -> Way.MULTICAST
        }
        val asked = ours.flatMap(::answersFor).toSet()
        // Known-answer suppression: what the asker already holds for long enough isn't said again.
        val answers = answerOrder.filter { it in asked && !parsed.knows(it) }
        if (answers.isEmpty()) return null
        val wanted = answers.flatMap(::additionalsFor).toSet()
        val additionals = additionalOrder.filter { it in wanted && it !in answers && (way == Way.MULTICAST || it.type != NSEC) }
        val legacy = way == Way.LEGACY
        val ttl = if (legacy) LEGACY_TTL else TTL
        val bytes = Packet().run {
            if (legacy) ours.forEach { question(it.name, it.type, unicastWanted = false) }
            answers.forEach { answer(it, ttl, flush = !legacy) }
            additionals.forEach { additional(it, ttl, flush = !legacy) }
            finish(if (way == Way.MULTICAST) 0 else parsed.id, RESPONSE)
        }
        return Reply(bytes, way, onlyShared = answers.none { it.unique })
    }

    private fun answersFor(q: Query.Question): List<Rec> {
        val n = q.name.lowercase()
        val t = q.type
        val out = ArrayList<Rec>()
        when (n) {
            instance.lowercase() -> {
                if (t == TXT || t == ANY) out += txt
                if (t == SRV || t == ANY) out += srv
                if (out.isEmpty()) out += instanceNsec
            }
            host.lowercase() -> {
                if (t == A || t == ANY) out += a
                if ((t == AAAA || t == ANY) && aaaa != null) out += aaaa
                if (out.isEmpty()) out += hostNsec
            }
            SERVICE.lowercase() -> if (t == PTR || t == ANY) out += ptr
            SERVICES.lowercase() -> if (t == PTR || t == ANY) out += servicesPtr
        }
        return out
    }

    private fun additionalsFor(r: Rec): List<Rec> = when (r) {
        ptr -> listOfNotNull(txt, srv, aaaa, a, hostNsec, instanceNsec)
        srv -> listOfNotNull(aaaa, a, hostNsec, instanceNsec)
        txt -> listOf(instanceNsec)
        a, aaaa, hostNsec -> listOfNotNull(aaaa, a, hostNsec)
        else -> emptyList()
    }

    /** Only a PTR to our instance is checked: the known answer a browsing speaker sends. */
    private fun Query.knows(r: Rec): Boolean =
        r === ptr && knownPtrs.any { (name, target, ttl) ->
            name.equals(SERVICE, ignoreCase = true) && target.equals(instance, ignoreCase = true) && ttl >= TTL / 2
        }

    /** The parts of a query a reply depends on. */
    internal class Query(
        val id: Int,
        val isResponse: Boolean,
        val questions: List<Question>,
        val knownPtrs: List<Triple<String, String, Long>>,
    ) {
        class Question(val name: String, val type: Int, val unicastWanted: Boolean)

        companion object {
            fun parse(d: ByteArray, length: Int): Query {
                val r = Reader(d, length)
                val id = r.u16()
                val flags = r.u16()
                val qd = r.u16()
                val an = r.u16()
                r.u16()
                r.u16()
                val questions = List(qd) {
                    val name = r.name()
                    val type = r.u16()
                    val cls = r.u16()
                    Question(name, type, cls and 0x8000 != 0)
                }
                val known = ArrayList<Triple<String, String, Long>>()
                repeat(an) {
                    val name = r.name()
                    val type = r.u16()
                    r.u16()
                    val ttl = (r.u16().toLong() shl 16) or r.u16().toLong()
                    val size = r.u16()
                    val start = r.at
                    if (type == PTR) known += Triple(name, r.name(), ttl)
                    r.at = start + size
                }
                return Query(id, flags and 0x8000 != 0, questions, known)
            }
        }
    }

    private class Reader(val d: ByteArray, val length: Int) {
        var at = 0

        fun u16(): Int {
            require(at + 2 <= length) { "short packet" }
            return ((d[at].toInt() and 0xff) shl 8 or (d[at + 1].toInt() and 0xff)).also { at += 2 }
        }

        fun name(): String {
            val labels = ArrayList<String>()
            var cursor = at
            var resume = -1
            var jumps = 0
            while (true) {
                require(cursor < length) { "name past the packet" }
                val len = d[cursor].toInt() and 0xff
                when {
                    len == 0 -> {
                        cursor++
                        break
                    }
                    len and 0xc0 == 0xc0 -> {
                        require(cursor + 1 < length && ++jumps <= 32) { "bad pointer" }
                        if (resume < 0) resume = cursor + 2
                        cursor = (len and 0x3f) shl 8 or (d[cursor + 1].toInt() and 0xff)
                    }
                    else -> {
                        require(cursor + 1 + len <= length) { "label past the packet" }
                        labels += String(d, cursor + 1, len, Charsets.UTF_8)
                        cursor += 1 + len
                    }
                }
            }
            at = if (resume >= 0) resume else cursor
            return labels.joinToString(".")
        }
    }

    /** A DNS message, every name compressed against the ones before it, rdata names too. */
    private class Packet {
        private var buf = ByteArray(512)
        private var len = HEADER
        private val names = HashMap<String, Int>()
        private var questions = 0
        private var answers = 0
        private var authorities = 0
        private var additionals = 0

        fun u8(v: Int) {
            ensure(1)
            buf[len++] = v.toByte()
        }

        fun u16(v: Int) {
            u8(v ushr 8)
            u8(v)
        }

        fun u32(v: Long) {
            u16((v ushr 16).toInt())
            u16(v.toInt())
        }

        fun bytes(b: ByteArray) {
            ensure(b.size)
            b.copyInto(buf, len)
            len += b.size
        }

        fun name(n: String) {
            val labels = n.split('.')
            for (i in labels.indices) {
                val rest = labels.subList(i, labels.size).joinToString(".").lowercase()
                names[rest]?.let {
                    u16(0xc000 or it)
                    return
                }
                if (len < 0x3fff) names[rest] = len
                val b = labels[i].toByteArray(Charsets.UTF_8)
                u8(b.size)
                bytes(b)
            }
            u8(0)
        }

        fun question(n: String, type: Int, unicastWanted: Boolean) {
            name(n)
            u16(type)
            u16(if (unicastWanted) 0x8001 else 1)
            questions++
        }

        fun answer(r: Rec, ttl: Long, flush: Boolean) {
            record(r, ttl, flush)
            answers++
        }

        fun authority(r: Rec) {
            record(r, TTL, flush = false)
            authorities++
        }

        fun additional(r: Rec, ttl: Long, flush: Boolean) {
            record(r, ttl, flush)
            additionals++
        }

        private fun record(r: Rec, ttl: Long, flush: Boolean) {
            name(r.name)
            u16(r.type)
            u16(if (flush && r.unique) 0x8001 else 1)
            u32(ttl)
            val at = len
            u16(0)
            r.rdata(this)
            val n = len - at - 2
            buf[at] = (n ushr 8).toByte()
            buf[at + 1] = n.toByte()
        }

        fun finish(id: Int, flags: Int): ByteArray {
            val out = buf.copyOf(len)
            intArrayOf(id, flags, questions, answers, authorities, additionals).forEachIndexed { i, v ->
                out[2 * i] = (v ushr 8).toByte()
                out[2 * i + 1] = v.toByte()
            }
            return out
        }

        private fun ensure(n: Int) {
            if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
        }
    }

    companion object {
        const val SERVICE = "_dacp._tcp.local"
        private const val SERVICES = "_services._dns-sd._udp.local"
        const val MDNS_PORT = 5353
        const val A = 1
        const val PTR = 12
        const val TXT = 16
        const val AAAA = 28
        const val SRV = 33
        const val NSEC = 47
        const val ANY = 255

        /** 75 minutes, macOS's: a speaker keeps the remote that long without asking again. */
        const val TTL = 4500L
        const val LEGACY_TTL = 10L
        private const val RESPONSE = 0x8400
        private const val HEADER = 12

        /**
         * A DACP-ID: 16 hex digits, the first never 0 — a speaker reads the id
         * as a number, and a leading zero would name another remote.
         */
        fun newId(random: Random = Random.Default): String {
            val first = "123456789ABCDEF"[random.nextInt(15)]
            val rest = (1 until 16).map { "0123456789ABCDEF"[random.nextInt(16)] }.joinToString("")
            return "$first$rest"
        }

        private fun typeBitmap(vararg types: Int): ByteArray {
            val max = types.max()
            val out = ByteArray(2 + max / 8 + 1)
            out[1] = (max / 8 + 1).toByte()
            for (t in types) out[2 + t / 8] = (out[2 + t / 8].toInt() or (0x80 ushr (t % 8))).toByte()
            return out
        }
    }
}

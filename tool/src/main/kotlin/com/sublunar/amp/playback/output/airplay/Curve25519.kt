package com.sublunar.amp.playback.output.airplay

import java.security.MessageDigest

/**
 * Ed25519 signatures and X25519 key agreement, which AirPlay 2 pairing
 * needs and Android 14 doesn't offer. A port of TweetNaCl's (public domain,
 * Bernstein et al.): small and plain rather than fast, which is plenty for
 * a handful of operations a connection.
 *
 * Field elements are sixteen 16-bit limbs held in Longs, as in the original.
 */
internal object Curve25519 {

    // --- X25519 ----------------------------------------------------------------

    /** X25519(scalar, u): the shared secret, or with [BASE] a public key. */
    fun x25519(scalar: ByteArray, u: ByteArray = BASE): ByteArray {
        val z = scalar.copyOf(32)
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = gf()
        unpack(x, u)
        val a = gf(); val b = x.copyOf(); val c = gf(); val d = gf(); val e = gf(); val f = gf()
        a[0] = 1; d[0] = 1
        for (i in 254 downTo 0) {
            val r = (z[i shr 3].toInt() ushr (i and 7)) and 1
            sel(a, b, r); sel(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d)
            mul(d, e, e); mul(f, a, a); mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c); mul(b, a, a); sub(c, d, f)
            mul(a, c, K121665); add(a, a, d); mul(c, c, a); mul(a, d, f); mul(d, b, x); mul(b, e, e)
            sel(a, b, r); sel(c, d, r)
        }
        inv(c, c)
        mul(a, a, c)
        return pack(a)
    }

    // --- Ed25519 ---------------------------------------------------------------

    /** The public key of a 32-byte [seed]. */
    fun publicKey(seed: ByteArray): ByteArray {
        val d = sha512(seed)
        clamp(d)
        return packPoint(scalarBase(d))
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val d = sha512(seed)
        clamp(d)
        val public = packPoint(scalarBase(d))
        val r = reduce(sha512(d.copyOfRange(32, 64), message))
        val bigR = packPoint(scalarBase(r))
        val h = reduce(sha512(bigR, public, message))
        val x = LongArray(64)
        for (i in 0 until 32) x[i] = (r[i].toLong() and 0xff)
        for (i in 0 until 32) for (j in 0 until 32) x[i + j] += (h[i].toLong() and 0xff) * (d[j].toLong() and 0xff)
        return bigR + modL(x)
    }

    fun verify(public: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (public.size != 32 || signature.size != 64) return false
        val q = unpackNeg(public) ?: return false
        val h = reduce(sha512(signature.copyOfRange(0, 32), public, message))
        val p = scalarMult(q, h)
        val s = scalarBase(signature.copyOfRange(32, 64))
        addPoint(p, s)
        return MessageDigest.isEqual(packPoint(p), signature.copyOfRange(0, 32))
    }

    // --- Points ----------------------------------------------------------------

    private fun scalarBase(s: ByteArray): Array<LongArray> {
        val q = arrayOf(GX.copyOf(), GY.copyOf(), one(), gf().also { mul(it, GX, GY) })
        return scalarMult(q, s)
    }

    private fun scalarMult(q: Array<LongArray>, s: ByteArray): Array<LongArray> {
        val p = arrayOf(gf(), one(), one(), gf())
        for (i in 255 downTo 0) {
            val b = (s[i / 8].toInt() ushr (i and 7)) and 1
            swap(p, q, b)
            addPoint(q, p)
            addPoint(p, p)
            swap(p, q, b)
        }
        return p
    }

    private fun addPoint(p: Array<LongArray>, q: Array<LongArray>) {
        val a = gf(); val b = gf(); val c = gf(); val d = gf(); val t = gf()
        val e = gf(); val f = gf(); val g = gf(); val h = gf()
        sub(a, p[1], p[0]); sub(t, q[1], q[0]); mul(a, a, t)
        add(b, p[0], p[1]); add(t, q[0], q[1]); mul(b, b, t)
        mul(c, p[3], q[3]); mul(c, c, D2)
        mul(d, p[2], q[2]); add(d, d, d)
        sub(e, b, a); sub(f, d, c); add(g, d, c); add(h, b, a)
        mul(p[0], e, f); mul(p[1], h, g); mul(p[2], g, f); mul(p[3], e, h)
    }

    private fun swap(p: Array<LongArray>, q: Array<LongArray>, b: Int) {
        for (i in 0 until 4) sel(p[i], q[i], b)
    }

    private fun packPoint(p: Array<LongArray>): ByteArray {
        val zi = gf(); val tx = gf(); val ty = gf()
        inv(zi, p[2])
        mul(tx, p[0], zi)
        mul(ty, p[1], zi)
        val r = pack(ty)
        r[31] = (r[31].toInt() xor (parity(tx) shl 7)).toByte()
        return r
    }

    /** The negated point [p] encodes, or null when it isn't one. */
    private fun unpackNeg(p: ByteArray): Array<LongArray>? {
        val r = arrayOf(gf(), gf(), one(), gf())
        unpack(r[1], p)
        val num = gf(); val den = gf(); val den2 = gf(); val den4 = gf(); val den6 = gf(); val t = gf(); val chk = gf()
        mul(num, r[1], r[1]); mul(den, num, D); sub(num, num, r[2]); add(den, r[2], den)
        mul(den2, den, den); mul(den4, den2, den2); mul(den6, den4, den2); mul(t, den6, num); mul(t, t, den)
        pow2523(t, t); mul(t, t, num); mul(t, t, den); mul(t, t, den); mul(r[0], t, den)
        mul(chk, r[0], r[0]); mul(chk, chk, den)
        if (!same(chk, num)) mul(r[0], r[0], SQRT_M1)
        mul(chk, r[0], r[0]); mul(chk, chk, den)
        if (!same(chk, num)) return null
        if (parity(r[0]) == (p[31].toInt() and 0xff) ushr 7) sub(r[0], gf(), r[0])
        mul(r[3], r[0], r[1])
        return r
    }

    // --- Scalars mod L ---------------------------------------------------------

    private fun clamp(d: ByteArray) {
        d[0] = (d[0].toInt() and 248).toByte()
        d[31] = ((d[31].toInt() and 127) or 64).toByte()
    }

    private fun reduce(h: ByteArray): ByteArray {
        val x = LongArray(64) { h[it].toLong() and 0xff }
        return modL(x)
    }

    private fun modL(x: LongArray): ByteArray {
        for (i in 63 downTo 32) {
            var carry = 0L
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        var carry = 0L
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * L[j]
        val r = ByteArray(32)
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[i] = (x[i] and 255).toByte()
        }
        return r
    }

    // --- The field, 2^255 − 19 -------------------------------------------------

    private fun gf() = LongArray(16)

    private fun one() = LongArray(16).also { it[0] = 1 }

    private fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(n: LongArray): ByteArray {
        val t = n.copyOf()
        val m = gf()
        carry(t); carry(t); carry(t)
        for (j in 0 until 2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        val o = ByteArray(32)
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
        return o
    }

    private fun unpack(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
    }

    private fun same(a: LongArray, b: LongArray) = MessageDigest.isEqual(pack(a), pack(b))

    private fun parity(a: LongArray) = pack(a)[0].toInt() and 1

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        carry(o); carry(o)
    }

    private fun inv(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            mul(c, c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }

    private fun pow2523(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 250 downTo 0) {
            mul(c, c, c)
            if (a != 1) mul(c, c, i)
        }
        c.copyInto(o)
    }

    private fun sha512(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run {
        parts.forEach { update(it) }
        digest()
    }

    private fun limbs(vararg v: Long) = v

    val BASE: ByteArray = ByteArray(32).also { it[0] = 9 }
    private val K121665 = limbs(0xDB41, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val D = limbs(0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203)
    private val D2 = limbs(0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406)
    private val GX = limbs(0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169)
    private val GY = limbs(0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666)
    private val SQRT_M1 = limbs(0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83)
    private val L = longArrayOf(
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
    )
}

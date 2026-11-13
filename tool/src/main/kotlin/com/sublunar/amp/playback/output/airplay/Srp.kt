package com.sublunar.amp.playback.output.airplay

import java.math.BigInteger
import java.security.MessageDigest

/**
 * The client side of SRP-6a as HomeKit-style pairing uses it: the 3072-bit
 * group, SHA-512, user "Pair-Setup", the code on the screen as password.
 * Proves both ends know the code without either sending it.
 *
 * Numbers go into the proof as their shortest big-endian bytes, and into
 * the multiplier and scrambler padded to the group's length, which is what
 * the receivers check against (and what the open-source senders do).
 */
internal class Srp(private val code: String, private val clientPrivate: ByteArray = randomBytes(32)) {

    class Result(val publicKey: ByteArray, val proof: ByteArray, val sessionKey: ByteArray)

    fun answer(salt: ByteArray, serverPublic: ByteArray): Result {
        val a = BigInteger(1, clientPrivate)
        val bigA = G.modPow(a, N)
        val bigB = BigInteger(1, serverPublic)
        if (bigB.mod(N).signum() == 0) throw java.io.IOException("a bad pairing answer")
        val k = int(hash(bytes(N), pad(G)))
        val u = int(hash(pad(bigA), pad(bigB)))
        val x = int(hash(salt, hash("$USER:$code".toByteArray())))
        val v = G.modPow(x, N)
        val base = bigB.subtract(k.multiply(v)).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)
        val key = hash(bytes(s))
        val ng = int(hash(bytes(N))).xor(int(hash(bytes(G))))
        val m1 = hash(bytes(ng), bytes(int(hash(USER.toByteArray()))), salt, bytes(bigA), bytes(bigB), key)
        return Result(bytes(bigA), m1, key)
    }

    private fun hash(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").run {
            parts.forEach { update(it) }
            digest()
        }

    private fun int(bytes: ByteArray) = BigInteger(1, bytes)

    /** Shortest big-endian form, at least one byte. */
    private fun bytes(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
    }

    private fun pad(n: BigInteger): ByteArray {
        val b = bytes(n)
        return ByteArray(LENGTH - b.size) + b
    }

    companion object {
        const val USER = "Pair-Setup"

        /** RFC 5054's 3072-bit group. */
        val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
                "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
                "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718" +
                "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33" +
                "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864" +
                "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2" +
                "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        val G: BigInteger = BigInteger.valueOf(5)
        private val LENGTH = (N.bitLength() + 7) / 8
    }
}

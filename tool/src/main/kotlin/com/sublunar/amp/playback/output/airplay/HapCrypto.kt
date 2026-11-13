package com.sublunar.amp.playback.output.airplay

import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography AirPlay 2 pairing and its encrypted channels are built
 * on: Ed25519 signatures and X25519 key agreement (Amp's own, see
 * [Curve25519], as Android 14 has neither), HKDF over SHA-512 and
 * ChaCha20-Poly1305 (the platform's). Keys are the raw 32 bytes the wire carries.
 */
internal object HapCrypto {

    // --- Ed25519: who is who -------------------------------------------------

    /** A signing key: its public half, and the 32-byte seed it comes from. */
    class Signer(val publicRaw: ByteArray, seed: ByteArray) {
        // A key kept in its standard wrapping ends with the seed.
        val seed: ByteArray = seed.copyOfRange(seed.size - 32, seed.size)

        fun sign(data: ByteArray): ByteArray = Curve25519.sign(seed, data)
    }

    fun newSigner(): Signer {
        val seed = randomBytes(32)
        return Signer(Curve25519.publicKey(seed), seed)
    }

    fun verify(publicRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        Curve25519.verify(publicRaw, data, signature)

    // --- X25519: a secret both ends reach ------------------------------------

    class Agreement {
        private val secret = randomBytes(32)
        val publicRaw: ByteArray = Curve25519.x25519(secret)

        fun secretWith(theirsRaw: ByteArray): ByteArray = Curve25519.x25519(secret, theirsRaw)
    }

    // --- HKDF-SHA512 ---------------------------------------------------------

    fun hkdf(secret: ByteArray, salt: String, info: String, length: Int = 32): ByteArray {
        val extract = Mac.getInstance(HMAC).apply { init(SecretKeySpec(salt.toByteArray(), HMAC)) }
        val prk = extract.doFinal(secret)
        val expand = Mac.getInstance(HMAC).apply { init(SecretKeySpec(prk, HMAC)) }
        val out = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            expand.update(block)
            expand.update(info.toByteArray())
            expand.update(counter.toByte())
            block = expand.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    // --- ChaCha20-Poly1305 ---------------------------------------------------

    /**
     * The 12-byte nonce AirPlay builds from 8: four zero bytes, then the 8
     * (a counter, little-endian, or a label such as "PV-Msg02").
     */
    fun nonce(eight: ByteArray): ByteArray = ByteArray(4) + eight

    fun counterNonce(counter: Long): ByteArray = ByteArray(8) { (counter ushr (8 * it)).toByte() }

    fun seal(key: ByteArray, nonce8: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray =
        chacha(Cipher.ENCRYPT_MODE, key, nonce8, aad).doFinal(plain)

    fun open(key: ByteArray, nonce8: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray =
        try {
            chacha(Cipher.DECRYPT_MODE, key, nonce8, aad).doFinal(sealed)
        } catch (e: java.security.GeneralSecurityException) {
            throw IOException("a message that doesn't open", e)
        }

    private fun chacha(mode: Int, key: ByteArray, nonce8: ByteArray, aad: ByteArray?): Cipher {
        val cipher = CHACHA_NAMES.firstNotNullOfOrNull { runCatching { Cipher.getInstance(it) }.getOrNull() }
            ?: throw IOException("no ChaCha20-Poly1305 here")
        cipher.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce(nonce8)))
        if (aad != null) cipher.updateAAD(aad)
        return cipher
    }

    // --- Helpers -------------------------------------------------------------

    private const val HMAC = "HmacSHA512"
    private val CHACHA_NAMES = listOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305")

    fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

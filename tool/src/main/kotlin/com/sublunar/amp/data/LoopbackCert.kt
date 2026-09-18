package com.sublunar.amp.data

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A throwaway identity for the loopback stream proxy: an EC key and a
 * self-signed certificate for `127.0.0.1`, made fresh at every launch and kept
 * only in memory. See [StreamProxy] for why a music player needs one.
 *
 * Built by hand because nothing the sandbox gives a tool will do it. The
 * platform can *parse* a certificate but has no public API to *make* one; the
 * usual answer is BouncyCastle, which is on Light's dependency allow-list but
 * not in the store builder's offline cache; and the Android Keystore makes one
 * for its own keys but cannot put an address in it, which the standard
 * hostname check needs. An X.509 certificate is a small, fixed structure, so
 * this writes exactly that structure — version 3, one name, two dates, the
 * key, and a subject-alternative-name of 127.0.0.1 and localhost — and lets
 * the platform's own parser and signature check say whether it is right.
 */
class LoopbackCert private constructor(val keyPair: KeyPair, val certificate: X509Certificate) {

    companion object {
        fun create(now: Date = Date()): LoopbackCert {
            val keys = KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }
                .generateKeyPair()

            val name = seq(set(seq(oid(OID_COMMON_NAME), utf8("Amp loopback"))))
            val day = 24L * 60 * 60 * 1000
            val tbs = seq(
                explicit(0, integer(BigInteger.valueOf(2))), // v3
                integer(BigInteger(127, SecureRandom()).add(BigInteger.ONE)),
                seq(oid(OID_ECDSA_SHA256)),
                name,
                seq(utcTime(Date(now.time - day)), utcTime(Date(now.time + 3650 * day))),
                name,
                keys.public.encoded, // already a DER SubjectPublicKeyInfo
                explicit(
                    3,
                    seq(
                        seq(
                            oid(OID_SUBJECT_ALT_NAME),
                            octets(
                                seq(
                                    tagged(0x87, byteArrayOf(127, 0, 0, 1)), // iPAddress
                                    tagged(0x82, "localhost".toByteArray(Charsets.US_ASCII)), // dNSName
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(tbs)
                sign()
            }
            val der = seq(tbs, seq(oid(OID_ECDSA_SHA256)), bitString(signature))
            val parsed = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
            // The parser accepted the shape; this is the arithmetic.
            parsed.verify(keys.public)
            return LoopbackCert(keys, parsed)
        }

        // --- Just enough DER ---------------------------------------------------

        private val OID_COMMON_NAME = intArrayOf(2, 5, 4, 3)
        private val OID_SUBJECT_ALT_NAME = intArrayOf(2, 5, 29, 17)
        private val OID_ECDSA_SHA256 = intArrayOf(1, 2, 840, 10045, 4, 3, 2)

        private fun tagged(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val n = content.size
            when {
                n < 0x80 -> out.write(n)
                n < 0x100 -> { out.write(0x81); out.write(n) }
                else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
            }
            out.write(content)
            return out.toByteArray()
        }

        private fun concat(parts: Array<out ByteArray>): ByteArray =
            ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

        private fun seq(vararg parts: ByteArray) = tagged(0x30, concat(parts))
        private fun set(vararg parts: ByteArray) = tagged(0x31, concat(parts))
        private fun explicit(n: Int, content: ByteArray) = tagged(0xA0 or n, content)
        private fun integer(value: BigInteger) = tagged(0x02, value.toByteArray())
        private fun octets(content: ByteArray) = tagged(0x04, content)
        private fun utf8(text: String) = tagged(0x0C, text.toByteArray(Charsets.UTF_8))
        private fun bitString(content: ByteArray) = tagged(0x03, byteArrayOf(0) + content)

        /** Valid for years before 2050, which ten years from any launch this decade is. */
        private fun utcTime(date: Date): ByteArray {
            val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return tagged(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
        }

        private fun oid(arcs: IntArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(arcs[0] * 40 + arcs[1])
            for (arc in arcs.drop(2)) {
                // Base 128, high bit set on every byte but the last.
                val bytes = ArrayList<Int>()
                var v = arc
                do { bytes.add(v and 0x7F); v = v shr 7 } while (v > 0)
                for (i in bytes.indices.reversed()) out.write(bytes[i] or if (i > 0) 0x80 else 0)
            }
            return tagged(0x06, out.toByteArray())
        }
    }
}

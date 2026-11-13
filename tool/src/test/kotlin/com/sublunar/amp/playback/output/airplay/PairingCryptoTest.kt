package com.sublunar.amp.playback.output.airplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCryptoTest {

    private fun hex(s: String) = HapCrypto.hex(s)

    /** Against srptools (what pyatv pairs with): fixed salt, client and server secrets, code 5962. */
    @Test
    fun srpMatchesTheReferenceSender() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val clientPrivate = ByteArray(32) { (0x20 + it).toByte() }
        val result = Srp("5962", clientPrivate).answer(salt, hex("ad34a19d31271b080870f12c671a4acf70ae1e6ddcfb2ed49c7560e92a82e35f2c9aa0d123c642a95857f6fb412856c0dfefb6add13154992b725d953bb45c4c3885dc9c12312cdf90c3a77138e0270f0440d2458f3d02ee65298d7a18a62c0d4f3acb110b35bc56b1926eed26d19038e3df3fce6ef09f75ec13d35703f654d2351ad290a523e432edfb34e228f77fbb38a5e670439a8f2dc94560eae7d56959d29b0ddecabddb7f9a194f4b7377ea9e3705072ebbac5859bb3cc20b5ef561a95aac9cb917a21dee5ccec1fc2328016471896a0981f43326a9e019470e5c45f367b1f5f644bcce991bd6e128bb46a680ab6cd59d5f58c0ef5154f64ff136aac052dd2ec8aae60baccf3a2a8beaed20dc3938e3757c7111d0cf64da47d97d935b7258fb2775982985f68c1cf0a0d2c3137792e037ca1d2a9d2dbcba8258741044be723fd59958f8afe2fed42236de534d58bd35c70fde9c0925b6c5dd0304d8b01134eafa7d9b9919d34f8f5434f3c1ee98787b154cb56eecde5c2a6d46d10182"))
        assertArrayEquals(hex("6823a16e8d3da8dda8a489cbe3a749a0381059e67fe720588d2abf43325a134c4f04f54ec63e53a89701e337f53bddd7c4251b1efb7066710786abb4d657d14642d954734f2b2c683febf971038b9d63c9372664108ca8c06c7318f7e375fd86222602ee939a6df598fac3e65a384191556ff015fb2a484d901c12090f8bf5dd430d5b2fe9eadca8ab478d0e77835de48a5b7fdd79e2e39200495806a41095bbeea594d53def231f92f5536d89d42210c720b0e0ba45277a7c136179d7fc1c2cd8500abf42fb277b31e4e111154f334e01b26d842d016a6f4b45d54eb43a7af8bad817ac4bd5e9a1b5870be4e8c64a774d7cf99ed0df2d85a0d7dac8d7978fc09c4b05e840eb7c32ab8a0d3e76e954606c6f19f73ffbffdce10f0143d027a6a291de46f98ba1ef731fcde654cf6c35a3bc0239340b247250d2433c1a8653856283ec656b1dc4f6c78ab489aa46a07a1953b168b194c9517b3af831d8cb60257446709568fbd56bf2cfab568bc6428a0289c574b223835ddd3acf3bebd5ca8590"), result.publicKey)
        assertArrayEquals(hex("c4eaff74c1dad4a1198a56c8a8c941c5e7f8260b9ee589dc82ef854228a18baddd216b77a6f2955cc98792ec2ab57c3d27d40a9b6844f9f7cee1d1e60fdfea93"), result.sessionKey)
        assertArrayEquals(hex("9ec490c3710dc8917b928bb81efbf6ef619621ab85c1a1531dca8ec1bd58d12a32c29acd06cd677bbb1f2d6697649e28fe76c076e1970078dc47324fe18b9924"), result.proof)
    }

    /** HKDF-SHA512 worked out by hand with Python's hmac: one block is all 32 bytes need. */
    @Test
    fun hkdfMatchesTheReference() {
        val out = HapCrypto.hkdf("secret".toByteArray(), "Control-Salt", "Control-Write-Encryption-Key")
        assertEquals("HKDF", 32, out.size)
        assertArrayEquals(hex("158fb2848e8e2c942230e57dba4869c45804016a75c3139fdd136197cf00b405"), out)
    }

    @Test
    fun tlvLongValuesSplitAndJoin() {
        val long = ByteArray(600) { it.toByte() }
        val bytes = Tlv8.write(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to long)
        assertEquals(3 + 2 + 255 + 2 + 255 + 2 + 90, bytes.size)
        val back = Tlv8.read(bytes)
        assertArrayEquals(byteArrayOf(3), back[Tlv8.STATE])
        assertArrayEquals(long, back[Tlv8.PUBLIC_KEY])
    }

    @Test
    fun plistRoundTrip() {
        val value = mapOf(
            "streams" to listOf(mapOf("type" to 96L, "shk" to ByteArray(32) { 7 }, "isMedia" to true, "latencyMax" to 88200L)),
            "name" to "Amp",
            "rate" to 1.0,
            "big" to 0x1_0000_0000L,
        )
        @Suppress("UNCHECKED_CAST")
        val back = Bplist.read(Bplist.write(value)) as Map<String, Any?>
        val stream = (back["streams"] as List<*>)[0] as Map<*, *>
        assertEquals(96L, stream["type"])
        assertEquals(88200L, stream["latencyMax"])
        assertEquals(true, stream["isMedia"])
        assertArrayEquals(ByteArray(32) { 7 }, stream["shk"] as ByteArray)
        assertEquals("Amp", back["name"])
        assertEquals(1.0, back["rate"])
        assertEquals(0x1_0000_0000L, back["big"])
    }

    @Test
    fun encryptedFramingRoundTrip() {
        val key = ByteArray(32) { 3 }
        val wire = ByteArrayOutputStream()
        val out = HapOutputStream(wire, key)
        val message = ByteArray(2500) { (it * 7).toByte() }
        out.write(message)
        out.flush()
        out.write("second".toByteArray())
        out.flush()
        assertEquals("three blocks then one", 2 + 1024 + 16 + 2 + 1024 + 16 + 2 + 452 + 16 + 2 + 6 + 16, wire.size())
        val input = HapInputStream(ByteArrayInputStream(wire.toByteArray()), key)
        val read = input.readBytes()
        assertArrayEquals(message + "second".toByteArray(), read)
    }

    @Test
    fun signaturesAndAgreement() {
        val signer = HapCrypto.newSigner()
        val sig = signer.sign("hello".toByteArray())
        assertTrue(HapCrypto.verify(signer.publicRaw, "hello".toByteArray(), sig))
        assertTrue(!HapCrypto.verify(signer.publicRaw, "hellp".toByteArray(), sig))
        val restored = HapCrypto.Signer(signer.publicRaw, signer.seed)
        assertTrue(HapCrypto.verify(signer.publicRaw, "x".toByteArray(), restored.sign("x".toByteArray())))
        val a = HapCrypto.Agreement()
        val b = HapCrypto.Agreement()
        assertArrayEquals(a.secretWith(b.publicRaw), b.secretWith(a.publicRaw))
    }

    /** RFC 8032, test 1. */
    @Test
    fun ed25519MatchesRfc8032() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val public = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        assertArrayEquals(public, Curve25519.publicKey(seed))
        assertArrayEquals(signature, Curve25519.sign(seed, ByteArray(0)))
        assertTrue(Curve25519.verify(public, ByteArray(0), signature))
        assertTrue(!Curve25519.verify(public, byteArrayOf(1), signature))
    }

    /** RFC 7748, section 5.2, the first vector. */
    @Test
    fun x25519MatchesRfc7748() {
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertArrayEquals(hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"), Curve25519.x25519(scalar, u))
    }

    /** Against this JVM's own Ed25519 and X25519, over random keys and messages. */
    @Test
    fun curve25519MatchesThePlatformsOn100Keys() {
        val random = java.util.Random(7)
        repeat(100) {
            val seed = ByteArray(32).also(random::nextBytes)
            val message = ByteArray(random.nextInt(200)).also(random::nextBytes)
            val jvmPrivate = java.security.KeyFactory.getInstance("Ed25519")
                .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(hex("302e020100300506032b657004220420") + seed))
            val jvmSig = java.security.Signature.getInstance("Ed25519").run { initSign(jvmPrivate); update(message); sign() }
            assertArrayEquals(jvmSig, Curve25519.sign(seed, message))
            assertTrue(Curve25519.verify(Curve25519.publicKey(seed), message, jvmSig))

            val a = ByteArray(32).also(random::nextBytes)
            val b = ByteArray(32).also(random::nextBytes)
            assertArrayEquals(Curve25519.x25519(a, Curve25519.x25519(b)), Curve25519.x25519(b, Curve25519.x25519(a)))
            val kf = java.security.KeyFactory.getInstance("XDH")
            val jvmA = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(hex("302e020100300506032b656e04220420") + a))
            val bPublic = kf.generatePublic(java.security.spec.X509EncodedKeySpec(hex("302a300506032b656e032100") + Curve25519.x25519(b)))
            val jvmShared = javax.crypto.KeyAgreement.getInstance("XDH").run { init(jvmA); doPhase(bPublic, true); generateSecret() }
            assertArrayEquals(jvmShared, Curve25519.x25519(a, Curve25519.x25519(b)))
        }
    }
}

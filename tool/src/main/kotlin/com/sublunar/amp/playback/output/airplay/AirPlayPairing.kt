package com.sublunar.amp.playback.output.airplay

import java.io.IOException
import java.util.Base64
import java.util.UUID

/**
 * What a pairing leaves on both ends: the phone's identity (an id and an
 * Ed25519 key the receiver now trusts) and the receiver's (its id and
 * public key). Kept per receiver and proved afresh at every connection.
 */
internal class PairedKeys(
    val clientId: ByteArray,
    val clientKey: HapCrypto.Signer,
    val receiverId: ByteArray,
    val receiverKey: ByteArray,
) {
    fun encode(): String = listOf(clientId, clientKey.publicRaw, clientKey.seed, receiverId, receiverKey)
        .joinToString(":") { Base64.getEncoder().encodeToString(it) }

    companion object {
        fun decode(text: String): PairedKeys? = runCatching {
            val p = text.split(':').map { Base64.getDecoder().decode(it) }
            PairedKeys(p[0], HapCrypto.Signer(p[1], p[2]), p[3], p[4])
        }.getOrNull()
    }
}

/** The receiver turned the code down: shown as asking for it again. */
internal class WrongCode : IOException("the code didn't match")

/**
 * HomeKit-style pairing with an AirPlay 2 receiver that shows a code
 * (an Apple TV): [start] puts the code on its screen, [finish] proves this
 * phone knows it and swaps long-term keys. [verify] is the quick proof at
 * the start of every connection after, and gives the keys that connection
 * is then encrypted with.
 */
internal object AirPlayPairing {

    /** Asks the receiver to show its code. Returns what [finish] needs. */
    fun start(rtsp: Rtsp): Pending {
        post(rtsp, "/pair-pin-start", ByteArray(0))
        val reply = Tlv8.read(post(rtsp, "/pair-setup", Tlv8.write(Tlv8.METHOD to byteArrayOf(0), Tlv8.STATE to byteArrayOf(1))))
        failOn(reply, "pair-setup")
        val salt = reply[Tlv8.SALT] ?: throw IOException("pair-setup: no salt")
        val key = reply[Tlv8.PUBLIC_KEY] ?: throw IOException("pair-setup: no key")
        return Pending(salt, key)
    }

    class Pending(val salt: ByteArray, val serverPublic: ByteArray)

    fun finish(rtsp: Rtsp, pending: Pending, code: String, name: String): PairedKeys {
        val srp = Srp(code).answer(pending.salt, pending.serverPublic)
        val m4 = Tlv8.read(post(rtsp, "/pair-setup", Tlv8.write(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof)))
        // A wrong code comes back as an error; the receiver's own proof isn't
        // checked, as the senders this follows don't either.
        if (m4.containsKey(Tlv8.ERROR)) throw WrongCode()

        val signer = HapCrypto.newSigner()
        val clientId = UUID.randomUUID().toString().toByteArray()
        val signKey = HapCrypto.hkdf(srp.sessionKey, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val encryptKey = HapCrypto.hkdf(srp.sessionKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val signature = signer.sign(signKey + clientId + signer.publicRaw)
        val inner = Tlv8.write(
            Tlv8.IDENTIFIER to clientId,
            Tlv8.PUBLIC_KEY to signer.publicRaw,
            Tlv8.SIGNATURE to signature,
            Tlv8.NAME to opackName(name),
        )
        val sealed = HapCrypto.seal(encryptKey, "PS-Msg05".toByteArray(), inner)
        val m6 = Tlv8.read(post(rtsp, "/pair-setup", Tlv8.write(Tlv8.STATE to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to sealed)))
        failOn(m6, "pair-setup")
        val theirs = Tlv8.read(HapCrypto.open(encryptKey, "PS-Msg06".toByteArray(), m6[Tlv8.ENCRYPTED_DATA] ?: throw IOException("pair-setup: nothing sealed")))
        val receiverId = theirs[Tlv8.IDENTIFIER] ?: throw IOException("pair-setup: no id")
        val receiverKey = theirs[Tlv8.PUBLIC_KEY] ?: throw IOException("pair-setup: no key")
        return PairedKeys(clientId, signer, receiverId, receiverKey)
    }

    /** Proves both ends are the paired pair; returns the shared secret the connection's keys come from. */
    fun verify(rtsp: Rtsp, keys: PairedKeys): ByteArray {
        val mine = HapCrypto.Agreement()
        val m2 = Tlv8.read(post(rtsp, "/pair-verify", Tlv8.write(Tlv8.STATE to byteArrayOf(1), Tlv8.PUBLIC_KEY to mine.publicRaw)))
        failOn(m2, "pair-verify")
        val theirPublic = m2[Tlv8.PUBLIC_KEY] ?: throw IOException("pair-verify: no key")
        val shared = mine.secretWith(theirPublic)
        val key = HapCrypto.hkdf(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val theirs = Tlv8.read(HapCrypto.open(key, "PV-Msg02".toByteArray(), m2[Tlv8.ENCRYPTED_DATA] ?: throw IOException("pair-verify: nothing sealed")))
        val id = theirs[Tlv8.IDENTIFIER]
        val signature = theirs[Tlv8.SIGNATURE]
        if (id == null || signature == null || !id.contentEquals(keys.receiverId) ||
            !HapCrypto.verify(keys.receiverKey, theirPublic + id + mine.publicRaw, signature)
        ) {
            throw NotPaired()
        }
        val proof = keys.clientKey.sign(mine.publicRaw + keys.clientId + theirPublic)
        val sealed = HapCrypto.seal(key, "PV-Msg03".toByteArray(), Tlv8.write(Tlv8.IDENTIFIER to keys.clientId, Tlv8.SIGNATURE to proof))
        val m4 = Tlv8.read(post(rtsp, "/pair-verify", Tlv8.write(Tlv8.STATE to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to sealed)))
        if (m4.containsKey(Tlv8.ERROR)) throw NotPaired()
        return shared
    }

    /**
     * Pairing for this connection only, as a HomePod takes it: nothing on a
     * screen, nothing kept. Every such receiver takes the same code (3939),
     * so it proves nothing about who is asking; it only sets up the keys the
     * connection is encrypted with. Returns the shared secret they come from.
     */
    fun transient(rtsp: Rtsp, askFirst: Boolean = true): ByteArray {
        if (askFirst) post(rtsp, "/pair-pin-start", ByteArray(0), TRANSIENT_HKP)
        val m2 = Tlv8.read(
            post(
                rtsp,
                "/pair-setup",
                Tlv8.write(Tlv8.METHOD to byteArrayOf(0), Tlv8.STATE to byteArrayOf(1), Tlv8.FLAGS to byteArrayOf(Tlv8.TRANSIENT.toByte())),
                TRANSIENT_HKP,
            ),
        )
        failOn(m2, "pair-setup")
        val salt = m2[Tlv8.SALT] ?: throw IOException("pair-setup: no salt")
        val key = m2[Tlv8.PUBLIC_KEY] ?: throw IOException("pair-setup: no key")
        val srp = Srp(TRANSIENT_CODE).answer(salt, key)
        val m4 = Tlv8.read(post(rtsp, "/pair-setup", Tlv8.write(Tlv8.STATE to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof), TRANSIENT_HKP))
        if (m4.containsKey(Tlv8.ERROR)) throw NotPaired()
        return srp.sessionKey
    }

    private const val TRANSIENT_CODE = "3939"
    private const val CODE_HKP = "3"
    private const val TRANSIENT_HKP = "4"

    /** The receiver no longer knows this phone (unpaired on its side): pair again. */
    class NotPaired : IOException("not paired any more")

    private fun post(rtsp: Rtsp, path: String, body: ByteArray, hkp: String = CODE_HKP): ByteArray {
        // An empty body still says so; a full one has its length added with the type.
        val headers = listOf("Connection" to "keep-alive", "X-Apple-HKP" to hkp) +
            if (body.isEmpty()) listOf("Content-Length" to "0") else emptyList()
        val reply = rtsp.http(path, headers, "application/octet-stream", body)
        if (reply.status == 470 || reply.status == 403) throw NotPaired()
        if (!reply.ok) throw RaopRefused(path, reply.status, reply.reason)
        return reply.body
    }

    private fun failOn(reply: Map<Int, ByteArray>, step: String) {
        val error = reply[Tlv8.ERROR] ?: return
        throw IOException("$step: error ${error.firstOrNull()?.toInt() ?: -1}")
    }

    /** The name the receiver lists this phone under, in Apple's compact object form: {"name": name}. */
    private fun opackName(name: String): ByteArray {
        val text = name.toByteArray().copyOf(minOf(name.toByteArray().size, 0x1f))
        return byteArrayOf(0xE1.toByte(), 0x44) + "name".toByteArray() + byteArrayOf((0x40 + text.size).toByte()) + text
    }
}

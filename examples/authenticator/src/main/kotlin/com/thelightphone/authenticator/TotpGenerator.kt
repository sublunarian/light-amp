package com.thelightphone.authenticator

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

data class TotpCode(
    val code: String,
    val remainingSeconds: Int,
)

object TotpGenerator {
    fun generate(
        secret: String,
        digits: Int,
        period: Int,
        algorithm: String,
        currentUnixTime: Long = System.currentTimeMillis() / 1000,
    ): TotpCode {
        require(digits > 0) { "digits must be positive" }
        require(period > 0) { "period must be positive" }

        val counter = currentUnixTime / period
        val message = ByteBuffer.allocate(8).putLong(counter).array()
        val key = Base32.decode(secret)
        val mac = Mac.getInstance(hmacAlgorithm(algorithm))
        mac.init(SecretKeySpec(key, hmacAlgorithm(algorithm)))
        val hash = mac.doFinal(message)

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary =
            (hash[offset].toInt() and 0x7F shl 24) or
                (hash[offset + 1].toInt() and 0xFF shl 16) or
                (hash[offset + 2].toInt() and 0xFF shl 8) or
                (hash[offset + 3].toInt() and 0xFF)

        val modulus = 10.0.pow(digits).toInt()
        val otp = binary % modulus
        val remainingSeconds = period - (currentUnixTime % period).toInt()

        return TotpCode(
            code = otp.toString().padStart(digits, '0'),
            remainingSeconds = remainingSeconds,
        )
    }

    private fun hmacAlgorithm(algorithm: String): String = when (algorithm) {
        TotpAlgorithm.SHA1 -> "HmacSHA1"
        TotpAlgorithm.SHA256 -> "HmacSHA256"
        TotpAlgorithm.SHA512 -> "HmacSHA512"
        else -> error("Unsupported TOTP algorithm: $algorithm")
    }
}

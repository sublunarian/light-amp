package com.thelightphone.authenticator

object TotpAlgorithm {
    const val SHA1 = "SHA1"
    const val SHA256 = "SHA256"
    const val SHA512 = "SHA512"

    private val supported = setOf(SHA1, SHA256, SHA512)

    fun normalize(raw: String?): String {
        val normalized = raw?.trim()?.uppercase() ?: SHA1
        require(normalized in supported) { "Unsupported TOTP algorithm: $raw" }
        return normalized
    }
}

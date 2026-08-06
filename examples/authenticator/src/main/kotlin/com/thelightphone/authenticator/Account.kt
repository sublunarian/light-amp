package com.thelightphone.authenticator

data class Account(
    val issuer: String,
    val label: String,
    val secret: String,
    val digits: Int,
    val period: Int,
    val algorithm: String = TotpAlgorithm.SHA1,
) {
    val displayName: String
        get() = when {
            issuer.isNotBlank() && label.isNotBlank() -> "$issuer ($label)"
            issuer.isNotBlank() -> issuer
            label.isNotBlank() -> label
            else -> "Unknown"
        }
}

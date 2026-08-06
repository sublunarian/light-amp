package com.thelightphone.authenticator

data class StoredAccount(
    val id: Long,
    val issuer: String,
    val label: String,
    val digits: Int,
    val period: Int,
    val algorithm: String,
) {
    val displayName: String
        get() = when {
            issuer.isNotBlank() && label.isNotBlank() -> "$issuer ($label)"
            issuer.isNotBlank() -> issuer
            label.isNotBlank() -> label
            else -> "Unknown"
        }
}

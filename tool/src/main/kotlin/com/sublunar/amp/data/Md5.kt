package com.sublunar.amp.data

import java.security.MessageDigest

/** Lowercase hex MD5, used for Subsonic token authentication. */
fun md5Hex(input: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4])
        out.append(HEX[v and 0x0f])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"

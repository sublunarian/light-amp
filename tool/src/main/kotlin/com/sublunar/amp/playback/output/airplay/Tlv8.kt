package com.sublunar.amp.playback.output.airplay

import java.io.ByteArrayOutputStream

/**
 * The type-length-value records pairing messages are made of: a one-byte
 * type, a one-byte length, the value. A value longer than 255 bytes goes as
 * several records of the same type in a row, joined again on reading.
 */
internal object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val NAME = 0x11
    const val FLAGS = 0x13

    /** In [FLAGS]: a pairing for this connection only, kept by neither end. */
    const val TRANSIENT = 0x10

    fun write(vararg items: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in items) {
            var at = 0
            do {
                val size = minOf(255, value.size - at)
                out.write(type)
                out.write(size)
                out.write(value, at, size)
                at += size
            } while (at < value.size)
        }
        return out.toByteArray()
    }

    fun read(bytes: ByteArray): Map<Int, ByteArray> {
        val found = LinkedHashMap<Int, ByteArray>()
        var at = 0
        var last = -1
        while (at + 2 <= bytes.size) {
            val type = bytes[at].toInt() and 0xff
            val size = bytes[at + 1].toInt() and 0xff
            if (at + 2 + size > bytes.size) break
            val value = bytes.copyOfRange(at + 2, at + 2 + size)
            found[type] = if (type == last) found.getValue(type) + value else value
            last = type
            at += 2 + size
        }
        return found
    }
}

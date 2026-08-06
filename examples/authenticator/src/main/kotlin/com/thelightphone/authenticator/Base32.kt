package com.thelightphone.authenticator

internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decode(input: String): ByteArray {
        val normalized = input.uppercase()
            .replace(" ", "")
            .replace("=", "")

        if (normalized.isEmpty()) return ByteArray(0)

        var buffer = 0
        var bitsLeft = 0
        val output = ArrayList<Byte>(normalized.length * 5 / 8)

        for (char in normalized) {
            val value = ALPHABET.indexOf(char)
            if (value < 0) {
                throw IllegalArgumentException("Invalid Base32 character: $char")
            }

            buffer = (buffer shl 5) or value
            bitsLeft += 5

            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }

        return output.toByteArray()
    }
}

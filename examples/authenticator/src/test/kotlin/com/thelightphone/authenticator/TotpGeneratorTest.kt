package com.thelightphone.authenticator

import kotlin.test.Test
import kotlin.test.assertEquals

class TotpGeneratorTest {
    @Test
    fun generateKnownTotpCodes() {
        val secret = "JBSWY3DPEHPK3PXP"

        assertEquals(
            "282760",
            TotpGenerator.generate(secret, 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 0).code,
        )
        assertEquals(
            "996554",
            TotpGenerator.generate(secret, 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 59).code,
        )
        assertEquals(
            "742275",
            TotpGenerator.generate(secret, 6, 30, TotpAlgorithm.SHA1, currentUnixTime = 1_234_567_890).code,
        )
    }

    @Test
    fun countdownAtStartOfWindow() {
        val result = TotpGenerator.generate(
            secret = "JBSWY3DPEHPK3PXP",
            digits = 6,
            period = 30,
            algorithm = TotpAlgorithm.SHA1,
            currentUnixTime = 60,
        )

        assertEquals(30, result.remainingSeconds)
    }

    @Test
    fun countdownNearExpiry() {
        val result = TotpGenerator.generate(
            secret = "JBSWY3DPEHPK3PXP",
            digits = 6,
            period = 30,
            algorithm = TotpAlgorithm.SHA1,
            currentUnixTime = 89,
        )

        assertEquals(1, result.remainingSeconds)
    }
}

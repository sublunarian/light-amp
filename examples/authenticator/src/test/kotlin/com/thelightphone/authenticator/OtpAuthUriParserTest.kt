package com.thelightphone.authenticator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtpAuthUriParserTest {
    @Test
    fun parseStandardGoogleUri() {
        val result = OtpAuthUriParser.parse(
            "otpauth://totp/Google:user@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Google",
        )

        val account = result.getOrThrow()
        assertEquals("Google", account.issuer)
        assertEquals("user@example.com", account.label)
        assertEquals("JBSWY3DPEHPK3PXP", account.secret)
        assertEquals(6, account.digits)
        assertEquals(30, account.period)
        assertEquals(TotpAlgorithm.SHA1, account.algorithm)
    }

    @Test
    fun parseIssuerFromPath() {
        val result = OtpAuthUriParser.parse(
            "otpauth://totp/AWS:admin?secret=ABCDEFGH&digits=8&period=60",
        )

        val account = result.getOrThrow()
        assertEquals("AWS", account.issuer)
        assertEquals("admin", account.label)
        assertEquals(8, account.digits)
        assertEquals(60, account.period)
    }

    @Test
    fun parseAlgorithmParam() {
        val result = OtpAuthUriParser.parse(
            "otpauth://totp/Example?secret=ABCDEFGH&algorithm=SHA256",
        )

        val account = result.getOrThrow()
        assertEquals(TotpAlgorithm.SHA256, account.algorithm)
    }

    @Test
    fun rejectsNonOtpauthUri() {
        val result = OtpAuthUriParser.parse("https://example.com")
        assertTrue(result.isFailure)
    }
}

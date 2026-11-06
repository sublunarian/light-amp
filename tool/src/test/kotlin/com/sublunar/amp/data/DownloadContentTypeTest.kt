package com.sublunar.amp.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** What a download may be saved as — see isDocumentSubtype and copyAtMost. */
class DownloadContentTypeTest {

    @Test
    fun `a playlist or an error document is never saved as the song`() {
        listOf("json", "xml", "html", "vnd.apple.mpegurl", "x-mpegurl", "dash+xml", "problem+json")
            .forEach { assertTrue(isDocumentSubtype(it), it) }
    }

    @Test
    fun `audio a server labels oddly still downloads`() {
        listOf("octet-stream", "ogg", "flac", "x-flac", "mp4", "x-matroska")
            .forEach { assertFalse(isDocumentSubtype(it), it) }
    }

    @Test
    fun `a body that fits the room is copied whole`() {
        val body = ByteArray(10_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        copyAtMost(ByteArrayInputStream(body), out, ceiling = 10_000, bufferBytes = 1024)
        assertContentEquals(body, out.toByteArray())
    }

    @Test
    fun `a body past the room stops before writing past it`() {
        val out = ByteArrayOutputStream()
        assertFailsWith<IOException> {
            copyAtMost(ByteArrayInputStream(ByteArray(10_000)), out, ceiling = 9_999, bufferBytes = 1024)
        }
        assertTrue(out.size() <= 9_999, "${out.size()} bytes written")
    }
}

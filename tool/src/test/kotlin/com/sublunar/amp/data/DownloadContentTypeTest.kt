package com.sublunar.amp.data

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** What a download may be saved as — see isDocumentSubtype. */
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
}

package com.sublunar.amp.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ids that name a file on this phone, and how one is turned back into the file. */
class PhoneFilesTest {

    @Test
    fun `a file's id is its absolute path behind the prefix`() {
        assertEquals("file:/a/b.mp3", PhoneFiles.idFor(File("/a/b.mp3")))
        assertEquals("/a/b.mp3", PhoneFiles.pathOf("file:/a/b.mp3"))
    }

    @Test
    fun `a server's id is not a phone file`() {
        assertNull(PhoneFiles.pathOf("x"))
        assertNull(PhoneFiles.fileOf("x"))
        assertFalse(PhoneFiles.isPhoneFile("x"))
    }

    @Test
    fun `an id turns back into its file while the file is there`() {
        val file = File.createTempFile("amp-phone-file", ".mp3")
        try {
            assertEquals(file.absoluteFile, PhoneFiles.fileOf(PhoneFiles.idFor(file)))
        } finally {
            file.delete()
        }
        assertNull(PhoneFiles.fileOf(PhoneFiles.idFor(file)))
    }

    @Test
    fun `an album's id is a phone id too, but names no file`() {
        assertTrue(PhoneFiles.isPhoneFile("file:album/x"))
        assertNull(PhoneFiles.fileOf("file:album/x"))
    }
}

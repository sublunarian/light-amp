package com.sublunar.amp.data

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where a download lives on the phone and what it is called.
 *
 * The files are the durable record: the download table is rebuilt from their
 * names whenever Room drops it (see [DownloadStore.onDisk]), so a name this
 * build would read differently is a download it no longer has.
 */
class DownloadNamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val downloads: File get() = File(tmp.root, "downloads")

    @Test
    fun `each format has the extension it is saved under`() {
        assertEquals(
            listOf("mp3", "opus", "flac", "audio"),
            listOf(StreamFormat.MP3, StreamFormat.OPUS, StreamFormat.FLAC, StreamFormat.RAW).map { it.suffix },
        )
    }

    @Test
    fun `and its place in the quality order`() {
        assertEquals(
            listOf(1, 2, 3, 4),
            listOf(StreamFormat.MP3, StreamFormat.OPUS, StreamFormat.FLAC, StreamFormat.RAW).map { it.qualityRank },
        )
    }

    @Test
    fun `a download is the track id and its extension, in its source's folder`() {
        val store = DownloadStore(tmp.root)
        assertEquals(File(downloads, "s/t.flac"), store.fileFor("s", "t", StreamFormat.FLAC))
    }

    @Test
    fun `what is on disk reads back from the file names alone`() {
        val store = DownloadStore(tmp.root)
        val folder = File(downloads, "s").apply { mkdirs() }
        listOf("t1.mp3", "t2.audio", "a.b.flac", "x.wav", ".hidden", "noext", "t3.opus.part")
            .forEach { File(folder, it).writeText("") }
        File(folder, "sub.mp3").mkdirs()
        assertEquals(
            listOf("a.b" to StreamFormat.FLAC, "t1" to StreamFormat.MP3, "t2" to StreamFormat.RAW),
            store.onDisk("s").sortedBy { it.first },
        )
    }

    @Test
    fun `a download or an mp3 index that never finished is swept at the next start`() {
        val folder = File(downloads, "s").apply { mkdirs() }
        val partial = File(folder, "t.opus.part").apply { writeText("") }
        val indexing = File(folder, "u.mp3.indexing").apply { writeText("") }
        val done = File(folder, "t.mp3").apply { writeText("") }
        DownloadStore(tmp.root)
        assertFalse(partial.exists())
        assertFalse(indexing.exists())
        assertTrue(done.exists())
    }
}

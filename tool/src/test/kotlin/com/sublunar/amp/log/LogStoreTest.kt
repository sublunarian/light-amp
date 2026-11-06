package com.sublunar.amp.log

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogStoreTest {

    private val dir: File = createTempDirectory("amp-log").toFile()

    private class FakeClock(var wall: Long, var elapsed: Long = 1_000) : LogStore.Clock {
        override fun wallMs() = wall
        override fun elapsedMs() = elapsed
        fun advance(ms: Long) {
            wall += ms
            elapsed += ms
        }
    }

    // Now, so that the files' real modification times agree with the clock.
    private val clock = FakeClock(System.currentTimeMillis())
    private var zone: ZoneId = ZoneOffset.UTC
    private val store = LogStore(dir, header = { "Amp test" }, clock = clock, zone = { zone })

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun line(text: String, tag: String = "AmpSync", level: Char = 'I') =
        store.add(LogStore.Entry(clock.wall, clock.elapsed, level, tag, text))

    @Test
    fun `a new file starts with the header, then lines with time, level and tag`() {
        line("sync done")
        store.drain()
        val lines = store.currentText().lines()
        assertTrue(lines[0].startsWith("--- ") && "Amp test" in lines[0] && "[t=${clock.wall}]" in lines[0], lines[0])
        assertTrue(Regex("""\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} I AmpSync sync done""").matches(lines[1]), lines[1])
    }

    @Test
    fun `a full file becomes the previous one and a new one starts`() {
        val text = "x".repeat(200)
        repeat((LogStore.FILE_BYTES / 200).toInt() + 50) {
            line(text, tag = "AmpTag${it % 40}")
            if (it % 500 == 0) store.drain()
        }
        store.drain()
        assertTrue(store.previousText().length > LogStore.FILE_BYTES, "previous ${store.previousText().length}")
        assertTrue(store.currentText().startsWith("--- "))
    }

    @Test
    fun `a week-old file is started afresh and its lines go a week later`() {
        line("old")
        store.drain()
        clock.advance(LogStore.FILE_AGE_MS + 60_000)
        line("new")
        store.drain()
        assertEquals("", store.previousText(), "a rotated file whose last line is over a week old is deleted")
        assertFalse("old" in store.currentText())
        assertTrue("new" in store.currentText())
    }

    @Test
    fun `a young previous file is kept`() {
        val text = "x".repeat(200)
        repeat((LogStore.FILE_BYTES / 200).toInt() + 50) {
            line(text, tag = "AmpTag${it % 40}")
            if (it % 500 == 0) store.drain()
        }
        store.drain()
        clock.advance(LogStore.FILE_AGE_MS - 60 * 60_000)
        line("later")
        store.drain()
        assertTrue(store.previousText().isNotEmpty())
    }

    @Test
    fun `one tag's flood is cut at its budget and counted, warnings never`() {
        repeat(LogStore.LINES_PER_TAG_PER_MINUTE + 10) { line("cover $it", tag = "AmpArt") }
        repeat(5) { line("failed $it", tag = "AmpArt", level = 'W') }
        line("other tag", tag = "AmpNet")
        store.drain()
        clock.advance(60_000)
        line("next minute", tag = "AmpArt")
        store.drain()
        val text = store.currentText()
        assertEquals(LogStore.LINES_PER_TAG_PER_MINUTE, text.lines().count { " I AmpArt cover " in it })
        assertEquals(5, text.lines().count { " W AmpArt failed " in it })
        assertTrue("other tag" in text)
        assertTrue("W AmpLog 10 AmpArt lines left out" in text, text.takeLast(500))
        assertTrue("next minute" in text)
    }

    @Test
    fun `a writer that fell behind says how many lines it lost`() {
        repeat(LogStore.QUEUE_LINES + 76) { line("line $it", tag = "AmpT${it % 30}") }
        store.drain()
        assertTrue("W AmpLog 76 lines dropped" in store.currentText())
    }

    @Test
    fun `the clock or the time zone moving is said`() {
        line("before")
        store.drain()
        clock.wall += 3 * 60 * 60_000
        line("after a clock change")
        store.drain()
        zone = ZoneOffset.ofHours(1)
        clock.advance(1_000)
        line("after travelling")
        store.drain()
        val text = store.currentText()
        assertTrue("clock moved +3h0m" in text, text)
        assertTrue("local time moved +1h0m (time zone)" in text, text)
    }

    @Test
    fun `a saved copy holds the note and both files, oldest first, and marks the moment`() {
        repeat((LogStore.FILE_BYTES / 200).toInt() + 50) {
            line("x".repeat(190) + it, tag = "AmpTag${it % 40}")
            if (it % 500 == 0) store.drain()
        }
        line("the last thing")
        val copy = store.copyTo(File(dir, "saved/amp-log.txt"), "NOTE")
        val text = copy.readText()
        assertTrue(text.startsWith("NOTE\n\n--- "))
        assertTrue(text.indexOf("x".repeat(190) + "0") < text.indexOf("the last thing"))
        assertTrue(text.trimEnd().endsWith("I AmpLog copy saved"))
    }

    @Test
    fun `clearing leaves only the line that says so`() {
        line("something")
        store.drain()
        store.clear()
        val text = store.currentText()
        assertFalse("something" in text)
        assertTrue("I AmpLog log cleared" in text)
        assertEquals("", store.previousText())
    }

    @Test
    fun `a crash writes what was queued, then class names and frames, never a message`() {
        line("just before")
        val crash = IllegalStateException(
            "boom https://navidrome.lan/rest?u=sam&t=abc",
            IOException("u=secret"),
        )
        store.crash("OkHttp https://navidrome.lan/rest", crash)
        val text = store.currentText()
        assertTrue(text.indexOf("just before") < text.indexOf("AmpCrash crash on"))
        assertTrue("E AmpCrash crash on OkHttp <url>: IllegalStateException>IOException" in text, text)
        assertTrue("E AmpCrash   at " in text)
        assertTrue("E AmpCrash   caused by IOException" in text)
        listOf("boom", "navidrome", "secret", "u=sam").forEach { assertFalse(it in text, "$it in crash lines") }
    }

    @Test
    fun `the copy handed to Android keeps classes and frames and drops every message`() {
        val original = RuntimeException("GET https://x.example/rest?u=sam failed", IOException("t=abc"))
        original.addSuppressed(IllegalStateException("suppressed https://y.example"))
        val handed = original.withoutMessages()
        val printed = StringWriter().also { handed.printStackTrace(PrintWriter(it)) }.toString()
        assertTrue(printed.startsWith("java.lang.RuntimeException (message left out)"), printed)
        assertTrue("Caused by: java.io.IOException (message left out)" in printed)
        assertTrue("LogStoreTest" in printed, "the original frames are kept")
        listOf("example", "u=sam", "t=abc", "Suppressed").forEach { assertFalse(it in printed, "$it in: $printed") }
    }
}

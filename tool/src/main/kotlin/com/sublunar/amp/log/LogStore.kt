package com.sublunar.amp.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The phone's own short log: two files in the app's private folder, written by
 * one background thread, so evidence outlives logcat's minute or two on the LP3.
 *
 * Retention, as the README states it: [FILE_BYTES] a file, so at most twice
 * that; the current file is started afresh once its first line is [FILE_AGE_MS]
 * old, and the previous one is deleted [FILE_AGE_MS] after its last line, so no
 * line is kept much over twice [FILE_AGE_MS].
 *
 * Callers never wait: [add] puts a line on a bounded queue, dropping the oldest
 * if the writer has fallen that far behind, and the writer counts what it lost.
 * A flushed write survives the process being killed, so there's no fsync.
 *
 * Lines arrive already scrubbed — see [Log]. Nothing here looks at what they say.
 */
class LogStore(
    private val dir: File,
    /** What Amp is and runs on, at the top of every file. Nothing about the person. */
    private val header: () -> String,
    private val clock: Clock = SystemClocks,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** Wall time for the reader, and a clock that never jumps for telling when the wall one did. */
    interface Clock {
        fun wallMs(): Long
        fun elapsedMs(): Long
    }

    class Entry(val wallMs: Long, val elapsedMs: Long, val level: Char, val tag: String, val text: String)

    private val queue = ArrayBlockingQueue<Entry>(QUEUE_LINES)
    private val dropped = AtomicInteger()

    // Held by whoever writes the file: the writer thread, a crash, a save, a clear.
    private val lock = Any()

    private val current = File(dir, CURRENT)
    private val previous = File(dir, PREVIOUS)
    private var out: BufferedWriter? = null
    private var currentBytes = 0L

    // For noticing the clock or the time zone moving between two lines.
    private var lastWallMs = 0L
    private var lastElapsedMs = 0L
    private var lastOffsetSeconds: Int? = null

    // Lines per tag in the current minute, so one chatty tag can't push out the rest.
    private class Budget(var minute: Long, var lines: Int, var leftOut: Int)
    private val budgets = HashMap<String, Budget>()

    fun add(entry: Entry) {
        while (!queue.offer(entry)) {
            if (queue.poll() != null) dropped.incrementAndGet()
        }
    }

    /** Starts the one writer thread. Lowest priority: it must never compete with the audio. */
    fun start() {
        val writer = Thread({ writeForever() }, "amp-log")
        writer.isDaemon = true
        writer.priority = Thread.MIN_PRIORITY
        writer.start()
    }

    private fun writeForever() {
        while (true) {
            try {
                // Wakes at least hourly, so a quiet phone still lets old lines go.
                val first = queue.poll(1, TimeUnit.HOURS)
                synchronized(lock) {
                    // Before writing: after a long quiet spell the current file
                    // may be due to go, and the new line belongs in the next.
                    expire()
                    if (first != null) {
                        writeQueued(first)
                    } else {
                        sayAllLeftOut()
                        out?.flush()
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Throwable) {
                // A full disk, a folder gone. The lines are lost, the thread isn't.
                synchronized(lock) { closeQuietly() }
                runCatching { android.util.Log.w(TAG, "log writer: ${e.logName()}") }
            }
        }
    }

    /** Whatever is queued, written now. The writer thread's job; tests call it directly. */
    fun drain() = synchronized(lock) {
        expire()
        writeQueued(null)
    }

    private fun writeQueued(first: Entry?) {
        first?.let { write(it) }
        while (true) write(queue.poll() ?: break)
        dropped.getAndSet(0).takeIf { it > 0 }?.let {
            writeLine(now(), 'W', TAG, "$it lines dropped: the log fell behind")
        }
        out?.flush()
    }

    private fun write(entry: Entry) {
        noteClock(entry)
        if (!withinBudget(entry)) return
        writeLine(entry.wallMs, entry.level, entry.tag, entry.text)
    }

    private fun withinBudget(entry: Entry): Boolean {
        // Warnings and errors are rare and are the evidence; never left out.
        if (entry.level != 'I') return true
        val minute = entry.elapsedMs / 60_000
        val budget = budgets.getOrPut(entry.tag) { Budget(minute, 0, 0) }
        if (budget.minute != minute) {
            sayLeftOut(entry.tag, budget, entry.wallMs)
            budget.minute = minute
            budget.lines = 0
        }
        if (budget.lines >= LINES_PER_TAG_PER_MINUTE) {
            budget.leftOut++
            return false
        }
        budget.lines++
        return true
    }

    private fun sayLeftOut(tag: String, budget: Budget, wallMs: Long) {
        if (budget.leftOut == 0) return
        writeLine(wallMs, 'W', TAG, "${budget.leftOut} $tag lines left out (over $LINES_PER_TAG_PER_MINUTE a minute)")
        budget.leftOut = 0
    }

    /** Before a copy, a crash or a quiet hour: a flood that stopped is still said. */
    private fun sayAllLeftOut() {
        val at = now()
        budgets.forEach { (tag, budget) -> sayLeftOut(tag, budget, at) }
    }

    // Wall and elapsed time move together unless someone, or the network,
    // sets the clock; the zone moves when the phone travels. Either would
    // make the times after it misleading without a line saying so.
    private fun noteClock(entry: Entry) {
        if (lastElapsedMs != 0L) {
            val drift = (entry.wallMs - lastWallMs) - (entry.elapsedMs - lastElapsedMs)
            if (abs(drift) > CLOCK_JUMP_MS) writeLine(entry.wallMs, 'I', TAG, "clock moved ${signedMinutes(drift)}")
        }
        val offset = zone().rules.getOffset(Instant.ofEpochMilli(entry.wallMs)).totalSeconds
        lastOffsetSeconds?.let { was ->
            if (offset != was) writeLine(entry.wallMs, 'I', TAG, "local time moved ${signedMinutes((offset - was) * 1000L)} (time zone)")
        }
        lastWallMs = entry.wallMs
        lastElapsedMs = entry.elapsedMs
        lastOffsetSeconds = offset
    }

    private fun writeLine(wallMs: Long, level: Char, tag: String, text: String) {
        val writer = open() ?: return
        val line = "${TIME.withZone(zone()).format(Instant.ofEpochMilli(wallMs))} $level $tag $text\n"
        writer.write(line)
        currentBytes += line.length
        if (currentBytes > FILE_BYTES) rotate()
    }

    private fun open(): BufferedWriter? {
        out?.let { return it }
        dir.mkdirs()
        val fresh = !current.exists() || current.length() == 0L
        currentBytes = current.length()
        val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(current, true), Charsets.UTF_8))
        out = writer
        if (fresh) {
            val started = now()
            val line = "--- ${DAY.withZone(zone()).format(Instant.ofEpochMilli(started))} " +
                "${LogScrubber.scrub(header())} [t=$started]\n"
            writer.write(line)
            currentBytes += line.length
        }
        return writer
    }

    private fun rotate() {
        closeQuietly()
        previous.delete()
        current.renameTo(previous)
    }

    private fun expire() {
        val now = now()
        if (current.exists() && (startedAt(current) ?: now) < now - FILE_AGE_MS) rotate()
        if (previous.exists() && previous.lastModified() < now - FILE_AGE_MS) previous.delete()
    }

    /**
     * Everything still on the phone, oldest first, with the queue written out
     * and a line marking the moment — the reader's anchor for "it went wrong
     * just before I saved this".
     */
    fun copyTo(target: File, note: String): File = synchronized(lock) {
        writeQueued(null)
        sayAllLeftOut()
        writeLine(now(), 'I', TAG, "copy saved")
        out?.flush()
        target.parentFile?.mkdirs()
        target.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write(note)
            w.write("\n\n")
            listOf(previous, current).filter { it.exists() }.forEach { w.write(it.readText(Charsets.UTF_8)) }
        }
        target
    }

    /** Both files gone; the next line starts a new one. */
    fun clear() = synchronized(lock) {
        queue.clear()
        closeQuietly()
        previous.delete()
        current.delete()
        budgets.clear()
        writeLine(now(), 'I', TAG, "log cleared")
        out?.flush()
    }

    /**
     * The process is going down. Written on the crashing thread, after what was
     * queued, and flushed before the platform's own handler ends the process.
     * Frames only: no message, which is where a URL would be.
     */
    fun crash(thread: String, e: Throwable) = synchronized(lock) {
        try {
            writeQueued(null)
            sayAllLeftOut()
            val at = now()
            writeLine(at, 'E', CRASH_TAG, "crash on ${LogScrubber.scrub(thread)}: ${e.logName()}")
            e.logFrames(CRASH_FRAMES).forEach { writeLine(at, 'E', CRASH_TAG, "  at $it") }
            var cause = e.cause
            var depth = 0
            while (cause != null && cause !== e && depth++ < 4) {
                writeLine(at, 'E', CRASH_TAG, "  caused by ${cause::class.simpleName}")
                cause.logFrames(CAUSE_FRAMES).forEach { writeLine(at, 'E', CRASH_TAG, "    at $it") }
                cause = cause.cause
            }
        } catch (_: Throwable) {
            // Out of memory while formatting, most likely. A line without
            // details still says the run ended in a crash, not a kill.
            runCatching { out?.write(CRASH_FALLBACK) }
        }
        runCatching { out?.flush() }
    }

    /** The file being written, for tests. */
    internal fun currentText(): String = synchronized(lock) {
        out?.flush()
        if (current.exists()) current.readText() else ""
    }

    internal fun previousText(): String = if (previous.exists()) previous.readText() else ""

    private fun closeQuietly() {
        runCatching { out?.close() }
        out = null
        currentBytes = 0
    }

    private fun now() = clock.wallMs()

    private fun startedAt(file: File): Long? =
        runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull()
            ?.let { STARTED.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    private fun signedMinutes(ms: Long): String {
        val minutes = ms / 60_000
        val sign = if (minutes < 0) "-" else "+"
        val m = abs(minutes)
        return if (m >= 60) "$sign${m / 60}h${m % 60}m" else "$sign${m}m"
    }

    companion object {
        const val TAG = "AmpLog"
        const val CRASH_TAG = "AmpCrash"
        const val CURRENT = "amp.log"
        const val PREVIOUS = "amp.1.log"

        const val FILE_BYTES = 512L * 1024
        const val FILE_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** How far behind the writer may fall before the oldest waiting lines go. */
        const val QUEUE_LINES = 1024

        /** Info lines one tag may write a minute. A normal hour writes a few dozen in all. */
        const val LINES_PER_TAG_PER_MINUTE = 60

        private const val CLOCK_JUMP_MS = 2L * 60 * 1000
        private const val CRASH_FRAMES = 14
        private const val CAUSE_FRAMES = 4
        private const val CRASH_FALLBACK = "E AmpCrash crash (details lost while writing them)\n"

        private val TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
        private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val STARTED = Regex("""\[t=(\d+)]""")
    }
}

/** The real clocks; wall time for the reader, elapsed for what the wall clock can't be trusted with. */
object SystemClocks : LogStore.Clock {
    override fun wallMs(): Long = System.currentTimeMillis()
    // Counts deep sleep, as the wall clock does; nanoTime stops in it, and
    // every wake would read as the clock moving. The fallback is for JVM tests,
    // where android.os is a stub that throws.
    override fun elapsedMs(): Long =
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: RuntimeException) {
            System.nanoTime() / 1_000_000
        }
}

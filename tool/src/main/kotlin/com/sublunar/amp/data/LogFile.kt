package com.sublunar.amp.data

import java.io.File
import java.io.FileWriter

/**
 * This process's own logcat, kept on the phone.
 *
 * The system's log rolls over in hours, and the phone is rarely on a cable when
 * something goes wrong with the network — out of the house is where the
 * cellular cases happen. By the time it is plugged in, the AmpNet lines that
 * said what the rules did are gone. An app may read its own log without any
 * permission, so this reads it as it is written and copies it into a few
 * rotating files under `files/logs`:
 *
 *     adb exec-out run-as com.sublunar.amp cat files/logs/amp.log.1 files/logs/amp.log
 *
 * Everything this process logs, not just the app's own tags: a player error
 * or a crash's stack trace is as much a part of the story as the rules.
 */
object LogFile {
    private const val MAX_BYTES = 2L * 1024 * 1024
    /** amp.log plus this many older ones — the better part of a day of use. */
    private const val KEEP = 4

    @Volatile private var started = false

    fun start(filesDir: File) {
        if (started) return
        started = true
        val dir = File(filesDir, "logs").apply { mkdirs() }
        val pid = android.os.Process.myPid()
        Thread({ run(dir, pid) }, "amp-logfile").apply { isDaemon = true }.start()
    }

    private fun run(dir: File, pid: Int) {
        val current = File(dir, "amp.log")
        runCatching {
            val process = ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid")
                .redirectErrorStream(true)
                .start()
            var out = FileWriter(current, true)
            out.write("--- process $pid started ---\n")
            process.inputStream.bufferedReader().forEachLine { line ->
                out.write(line)
                out.write("\n")
                // Flushed per line: the line that matters most is the last one
                // before a crash, and a buffer dies with the process.
                out.flush()
                if (current.length() > MAX_BYTES) {
                    out.close()
                    rotate(dir)
                    out = FileWriter(current, true)
                }
            }
        }.onFailure { android.util.Log.w("AmpLog", "log file stopped", it) }
    }

    private fun rotate(dir: File) {
        File(dir, "amp.log.$KEEP").delete()
        for (i in KEEP - 1 downTo 1) File(dir, "amp.log.$i").renameTo(File(dir, "amp.log.${i + 1}"))
        File(dir, "amp.log").renameTo(File(dir, "amp.log.1"))
    }
}

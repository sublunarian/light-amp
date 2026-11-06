package com.sublunar.amp.log

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The parts of tool/docs/LOGGING.md a text scan can check for certain: every
 * line goes through Amp's Log, and no log call reads an exception's message.
 * What a line otherwise says is left to review and to LogScrubber.
 */
class LogRulesTest {

    private class Source(val path: String, val text: String)

    private val main: List<Source> = run {
        val below = "src/main/kotlin/com/sublunar/amp"
        val root = listOf(below, "tool/$below").map { File(it) }.firstOrNull { it.isDirectory }
            ?: fail("No $below under ${File("").absolutePath}: this test reads the sources and must not pass without them.")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { Source(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
            .toList()
            .also { assertTrue(it.size >= 80 && it.any { f -> f.path == "App.kt" }, "only ${it.size} files read") }
    }

    /** The code of a file with its comment lines left out, as Light's plugin reads it. */
    private fun Source.code(): String =
        text.lines().filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/*") } }
            .joinToString("\n")

    @Test
    fun `only the log package talks to android's Log`() {
        val offenders = main.filter { !it.path.startsWith("log/") && "android.util.Log" in it.code() }.map { it.path }
        assertTrue(offenders.isEmpty(), "android.util.Log outside log/: $offenders")
    }

    @Test
    fun `nothing prints a stack trace or to standard out`() {
        val offenders = main.filter { f ->
            val code = f.code()
            "printStackTrace(" in code || Regex("""(?<![.\w])println\(""").containsMatchIn(code) || "System.out" in code || "System.err" in code
        }.map { it.path }
        assertTrue(offenders.isEmpty(), "printing outside the log: $offenders")
    }

    @Test
    fun `no log call reads an exception's message`() {
        val banned = listOf(".message", ".localizedMessage", "stackTraceToString", ".toString()")
        val offenders = main.flatMap { f ->
            logCalls(f.code()).filter { call -> banned.any { it in call } }.map { "${f.path}: ${it.take(120)}" }
        }
        assertTrue(offenders.isEmpty(), "log calls reading a message:\n${offenders.joinToString("\n")}")
    }

    @Test
    fun `the scan finds the log calls it checks`() {
        val calls = main.sumOf { logCalls(it.code()).size }
        assertTrue(calls > 100, "only $calls log calls found: the scan is broken")
    }

    /** The argument text of every Log.i/w/e( call, to its closing parenthesis, across lines. */
    private fun logCalls(code: String): List<String> =
        Regex("""\bLog\.[iwe]\(""").findAll(code).map { match ->
            var depth = 1
            var at = match.range.last + 1
            while (at < code.length && depth > 0) {
                when (code[at]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                at++
            }
            code.substring(match.range.last + 1, (at - 1).coerceAtLeast(match.range.last + 1))
        }.toList()
}

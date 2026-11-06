package com.sublunar.amp.log

import com.sublunar.amp.data.MusicSource
import com.sublunar.amp.data.ServerException
import java.io.File

/**
 * Amp's one logger, in android.util.Log's shape so a file needs only its import
 * changed. Every line is scrubbed ([LogScrubber]), sent to logcat under its tag
 * as before, and kept in the phone's own short log ([LogStore]).
 *
 * There is no Throwable parameter, on purpose. An exception's message can carry
 * a URL with the login in it (ktor puts the whole request URL in its timeouts),
 * so a line names the failure with [logName] and, where the stack is the point,
 * [logFrames]. What a line may say at all is in tool/docs/LOGGING.md.
 *
 * A tag starting AmpTmp is instrumentation for one device test: it reaches the
 * file like any other line, and scripts/release-mapping-check.sh refuses a
 * release while one is left in the code.
 */
object Log {
    @Volatile
    private var store: LogStore? = null

    // Lines from before App.boot: the audio service can start the process
    // without a screen, and boot itself logs before the store exists.
    private val early = ArrayDeque<LogStore.Entry>()
    private const val EARLY_LINES = 200

    fun i(tag: String, msg: String) = write('I', tag, msg)
    fun w(tag: String, msg: String) = write('W', tag, msg)
    fun e(tag: String, msg: String) = write('E', tag, msg)

    private fun write(level: Char, tag: String, msg: String) {
        val text = LogScrubber.scrub(msg)
        logcat(level, tag, text)
        val entry = LogStore.Entry(SystemClocks.wallMs(), SystemClocks.elapsedMs(), level, tag, text)
        store?.let { return it.add(entry) }
        synchronized(early) {
            // Looked at again under the lock: attach may have taken the early
            // lines in between, and one put here now would never be written.
            store?.let { return it.add(entry) }
            if (early.size == EARLY_LINES) early.removeFirst()
            early.addLast(entry)
        }
    }

    private fun logcat(level: Char, tag: String, text: String) {
        val priority = when (level) {
            'E' -> android.util.Log.ERROR
            'W' -> android.util.Log.WARN
            else -> android.util.Log.INFO
        }
        // JVM tests run against android.jar's stubs, which throw.
        try {
            android.util.Log.println(priority, tag, text)
        } catch (_: RuntimeException) {
        }
    }

    /**
     * Opens the log in [dir] and catches crashes from here on. Once per
     * process, first thing in App.boot.
     */
    fun attach(dir: File, header: String) {
        if (store != null) return
        val opened = LogStore(File(dir, "logs"), header = { header })
        synchronized(early) {
            early.forEach(opened::add)
            early.clear()
            store = opened
        }
        opened.start()
        catchCrashes(opened)
        i("AmpLife", "start: $header")
    }

    /** The log as it stands, written to [target] — see LogScreen. Null before [attach]. */
    fun copyTo(target: File, note: String): File? = store?.copyTo(target, note)

    fun clear() {
        store?.clear()
    }

    private fun catchCrashes(into: LogStore) {
        val platform = Thread.getDefaultUncaughtExceptionHandler()
        if (platform is CrashCatcher) return
        Thread.setDefaultUncaughtExceptionHandler(CrashCatcher(into, platform))
    }

    /**
     * Writes the crash into the log, then hands the platform a copy with every
     * message taken out, so the crash record Android files (DropBox, and the
     * crash buffer for crashes delivered by coroutines) holds no URL either.
     * A crash thrown straight on a thread is printed raw by Android before
     * this runs; nothing in an app can stop that.
     */
    private class CrashCatcher(
        private val store: LogStore,
        private val platform: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, e: Throwable) {
            runCatching { store.crash(thread.name, e) }
            val handed = runCatching {
                // OkHttp names its threads after the call's URL.
                if ("://" in thread.name) thread.name = "amp-crashed"
                e.withoutMessages()
            }.getOrDefault(e)
            platform?.uncaughtException(thread, handed)
        }
    }
}

/**
 * A failure named for a log line: the class of each cause, outermost first, and
 * the HTTP status where one of Amp's own exceptions carries it. Never the
 * message.
 */
fun Throwable.logName(): String {
    val chain = causes()
    val names = chain.joinToString(">") { it::class.simpleName ?: "Throwable" }
    val status = chain.filterIsInstance<ServerException>().firstNotNullOfOrNull { it.status }
    val errno = chain.filterIsInstance<android.system.ErrnoException>().firstOrNull()
        ?.let { runCatching { android.system.OsConstants.errnoName(it.errno) }.getOrNull() }
    return names + (status?.let { " status=$it" } ?: "") + (errno?.let { " $it" } ?: "")
}

/**
 * Where it was thrown, without what it said: up to [max] frames, Amp's own
 * package shortened, plus Amp's first frame if it isn't among them.
 */
fun Throwable.logFrames(max: Int): List<String> {
    val frames = stackTrace
    val top = frames.take(max)
    val firstOwn = frames.firstOrNull { it.className.startsWith(OWN_PACKAGE) }
    val shown = if (firstOwn == null || firstOwn in top) top else top + firstOwn
    return shown.map { "${it.className.removePrefix(OWN_PACKAGE)}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
}

/** A source as a log line names it: its kind and the end of Amp's own random id, never its name. */
val MusicSource.logRef: String
    get() = kind.name.lowercase() + if (id.startsWith("src-")) "/" + id.takeLast(4) else ""

/**
 * Text a server or a device chose (a version, a model) as a log line may hold
 * it: plain words and numbers, or "unknown". Anything else could be a name, an
 * address, or a forged line.
 */
fun loggableWord(text: String?): String =
    text?.trim()?.takeIf { LOGGABLE_WORD.matches(it) } ?: "unknown"

private val LOGGABLE_WORD = Regex("""[A-Za-z0-9 .,+()_/-]{1,40}""")

private const val OWN_PACKAGE = "com.sublunar.amp."

private fun Throwable.causes(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var at: Throwable? = this
    while (at != null && chain.size < 6 && chain.none { it === at }) {
        chain += at
        at = at.cause
    }
    return chain
}

/**
 * The same chain, class names and frames, with no message anywhere and no
 * suppressed exceptions: what the platform's crash record may see.
 */
internal fun Throwable.withoutMessages(): Throwable {
    val chain = causes()
    var built: Throwable? = null
    for (original in chain.asReversed()) {
        built = MessageFree(original::class.qualifiedName ?: "Throwable", built).apply {
            stackTrace = original.stackTrace
        }
    }
    return built ?: this
}

private class MessageFree(private val name: String, cause: Throwable?) :
    RuntimeException(null, cause, false, true) {
    override fun toString(): String = "$name (message left out)"
}

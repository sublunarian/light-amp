package com.sublunar.amp.log

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Where the main thread was when it stopped answering. Android's own report of
 * a frozen screen often can't read Amp's threads, and by the time it looks the
 * freeze may be over, so this asks the main thread to answer every second and,
 * past [STALL_MS] without an answer, writes down what it is doing, again at
 * [STILL_MS] (Android gives up on a touch at 5 s), and how long it took once it
 * answers. Asleep, the clock it counts on stops too, so a sleeping phone is never
 * taken for a stuck one.
 */
object StallWatch {
    @Volatile private var started = false
    @Volatile private var answeredAt = 0L

    fun start() {
        if (started) return
        started = true
        val main = Handler(Looper.getMainLooper())
        Thread({ watch(main) }, "amp-stall").apply { isDaemon = true }.start()
    }

    private fun watch(main: Handler) {
        val mainThread = Looper.getMainLooper().thread
        while (true) {
            val askedAt = SystemClock.uptimeMillis()
            main.post { answeredAt = SystemClock.uptimeMillis() }
            var notes = 0
            while (answeredAt < askedAt) {
                Thread.sleep(CHECK_MS)
                val waited = SystemClock.uptimeMillis() - askedAt
                if (notes == 0 && waited >= STALL_MS || notes == 1 && waited >= STILL_MS) {
                    notes++
                    Log.w(TAG, "main thread ${if (notes == 1) "stuck" else "still stuck"} for $waited ms, at:")
                    mainThread.stackTrace.logFrames(FRAMES).forEach { Log.w(TAG, "  at $it") }
                }
            }
            if (notes > 0) Log.w(TAG, "main thread answered after ${answeredAt - askedAt} ms")
            Thread.sleep(CHECK_MS)
        }
    }

    private const val TAG = "AmpStall"
    private const val CHECK_MS = 1_000L
    private const val STALL_MS = 2_000L
    private const val STILL_MS = 4_000L
    private const val FRAMES = 16
}

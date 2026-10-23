package com.sublunar.amp.data

import android.util.Log

/**
 * Logs it when what this [Result] caught was the heap running out, and hands
 * the result on unchanged.
 *
 * A runCatching around library-sized work swallows an OutOfMemoryError like any
 * other failure, so without this a top-up or cover sync that simply didn't
 * happen leaves nothing in the crash log. Read with `adb logcat -s AmpMem`.
 */
fun <T> Result<T>.noteOom(site: String): Result<T> = onFailure {
    if (it is OutOfMemoryError) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
        Log.w("AmpMem", "swallowed OutOfMemoryError in $site ($usedMb of ${runtime.maxMemory() / 1_048_576} MB used)")
    }
}

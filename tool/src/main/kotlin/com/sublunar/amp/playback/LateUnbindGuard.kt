package com.sublunar.amp.playback

import android.os.Handler
import android.os.Looper

/**
 * Keeps the process alive through the one crash [isLateMediaUnbind] describes,
 * and only that one.
 *
 * Nothing of Amp's is on the stack when media3's posted unbind runs, so there
 * is nowhere to put a try around it. The main looper is run from inside one
 * instead: a message posted here calls Looper.loop() itself, and from then on
 * every message the main thread dispatches, for the rest of the process, runs
 * beneath this frame. A matching exception is logged and the loop entered
 * again from the same frame, so the stack doesn't grow however many there are
 * — one process can strand a controller for every activity it outlives, and
 * every one of them is absorbed. Anything else is thrown on as the very same
 * object and ends the process exactly as it would have without this; its
 * trace just ends in this frame and a second Looper.loop, which are this and
 * not part of the crash.
 *
 * Only IllegalArgumentException is caught. An Error never reaches the matcher,
 * so an out-of-memory can't be replaced by whatever the matcher fails with,
 * and a debugger's break-on-uncaught still stops on everything else.
 *
 * Installed with a raw post, from plain main-thread code that is not a
 * composition: App.boot, which the first screen's constructor calls inside the
 * activity's onCreate. Every other way in breaks something. Called directly,
 * loop() never returns and onCreate never finishes. From a coroutine on
 * Dispatchers.Main, that coroutine never completes. From a Compose effect, it
 * runs inside Compose's own dispatch, which schedules nothing more until that
 * returns, and the screens freeze. Posted, it starts as its own message right
 * after the launch, before anything can have released a handle — and media3's
 * unbinds are posted too, so the queue's order puts this loop underneath every
 * one of them.
 *
 * Looper logs "Loop again would have the queued messages be executed before
 * this one completed." whenever loop() is entered from inside a message: once
 * at install and once after every absorb. That is this working, not a fault.
 *
 * When to remove it is on [isLateMediaUnbind].
 */
object LateUnbindGuard {
    /** Its own flag rather than App's: a boot that threw part-way must not post a second, nested guard. */
    private var installed = false

    /** How many have been absorbed in this process. Main thread only, like everything here. */
    private var absorbed = 0

    /** Main thread, once per process; later calls do nothing. */
    fun install() {
        if (installed) return
        installed = true
        Handler(Looper.getMainLooper()).post { loopGuarded() }
    }

    private fun loopGuarded() {
        android.util.Log.i(
            "AmpLife",
            "unbind guard on (Looper's \"Loop again…\" warning, now and after each absorb, is expected)",
        )
        while (true) {
            try {
                Looper.loop()
                // The main looper never returns from loop(): only quit() ends
                // a loop, and the main one refuses to quit. Were this ever on
                // a looper that can, going round again would spin on a loop()
                // that returns at once. Leaving hands back to the loop this
                // message came from, which then ends the way it would have.
                return
            } catch (e: IllegalArgumentException) {
                if (!isLateMediaUnbind(e)) throw e
                absorbed++
                // Every one, counted, so a log shows how often it happens; the
                // whole stack only the first time, which is enough to check it
                // against the crash it stands in for.
                android.util.Log.w(
                    "AmpLife",
                    "absorbed late media3 unbind #$absorbed: the activity that bound it is gone",
                    if (absorbed == 1) e else null,
                )
            }
        }
    }
}

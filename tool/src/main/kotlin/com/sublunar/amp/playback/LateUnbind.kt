package com.sublunar.amp.playback

/**
 * Whether [e] is media3 unbinding a detached player's controller through an
 * activity Android has already destroyed — the one exception [LateUnbindGuard]
 * lets the process live through, and nothing else.
 *
 * How it comes about: the pinned SDK builds the detached MediaController
 * against the activity, not the application. When Android destroys that
 * activity while the process lives on, it drops the activity's bindings itself
 * and logs them as leaked, and neither Amp's handle nor the controller inside
 * it is told. Whenever that controller is released afterwards — by Amp handing
 * over to a new activity, or by media3 when Android stops the idle service —
 * media3 posts its unbind to the main looper against the dead activity, and
 * Android refuses it with exactly this. Posted, so no caller's try is on the
 * stack when it throws, and the process dies.
 *
 * Every condition is a reason to say no, so that anything else on the main
 * thread still crashes exactly as it would have:
 *
 * - An IllegalArgumentException, which is what LoadedApk throws for an unbind
 *   it has no record of. Not an IllegalStateException carrying the same words.
 * - The message names media3's own connection. LoadedApk builds it as "Service
 *   not registered: " plus the connection, and media3's connection class has
 *   no toString of its own, so it reads as that class name and an address. The
 *   SDK's own LightServiceConnection, or any other binding, fails here.
 * - The throw is in LoadedApk.forgetServiceDispatcher, called straight from
 *   ContextImpl.unbindService: the framework turning down an unbind, not some
 *   other code that happens to use the same words.
 * - Above that only ContextWrapper's unbindService, as many as the activity has
 *   wrappers (none is fine), and then media3's controller, every frame of which
 *   starts with MediaControllerImplBase. Nothing more exact than that prefix:
 *   a release build shows only R8's numbered synthetic lambda (52 in 0.7.2),
 *   a debug build the lambda method too, and both names are the compiler's to
 *   change from one build to the next.
 * - And directly above the media3 frames, Handler.handleCallback: the unbind
 *   ran as a runnable posted on its own. No framework step — a lifecycle
 *   change, a service being stopped, a frame being drawn — was underneath it
 *   and left half-done by the throw. All that is left undone is the rest of
 *   that one runnable, media3's tidy-up of a controller it has already
 *   released, and the unbind itself is moot: Android dropped the binding when
 *   it destroyed the activity.
 *
 * Fails closed. A build whose classes were renamed reads "d3.h0" and "d3.W"
 * and matches nothing, and the crash simply comes back. That is why the
 * release build keeps its names (assets/r8/keep-names.txt), and why
 * scripts/release-mapping-check.sh looks in a release build's mapping for
 * media3's connection, unrenamed, and for media3's posted release still running
 * in a MediaControllerImplBase class — R8 merges lambdas across classes even
 * when it renames nothing: run after every local release build, before it
 * is tagged, and by scripts/store-build-check.sh on the store build, which only
 * gets that far once the store build compiles. Never throws: a fault in here
 * answers no, and the original exception goes on as it was.
 *
 * Remove this, [LateUnbindGuard] and LateUnbindTest once the pinned SDK builds
 * the detached MediaController from context.applicationContext, as its own
 * LightServiceConnection already builds its binding. The upstream issue is not
 * filed yet. The rest of what goes with it is under "The detached player is
 * bound to the activity, not the app" in tool/docs/SDK-GAPS.md.
 */
internal fun isLateMediaUnbind(e: Throwable): Boolean = try {
    e is IllegalArgumentException &&
        e.message?.startsWith(LATE_UNBIND_MESSAGE) == true &&
        unbindStackMatches(e.stackTrace)
} catch (_: Throwable) {
    false
}

private const val LATE_UNBIND_MESSAGE =
    "Service not registered: androidx.media3.session.MediaControllerImplBase\$SessionServiceConnection@"

private const val MEDIA3_CONTROLLER = "androidx.media3.session.MediaControllerImplBase"

/** The stack shape set out on [isLateMediaUnbind], read from the throw outwards. */
private fun unbindStackMatches(frames: Array<StackTraceElement>): Boolean {
    var i = 0
    fun at(className: String, method: String): Boolean =
        frames.getOrNull(i)?.let { it.className == className && it.methodName == method } == true
    if (!at("android.app.LoadedApk", "forgetServiceDispatcher")) return false
    i++
    if (!at("android.app.ContextImpl", "unbindService")) return false
    i++
    while (at("android.content.ContextWrapper", "unbindService")) i++
    val firstMedia3 = i
    while (frames.getOrNull(i)?.className?.startsWith(MEDIA3_CONTROLLER) == true) i++
    return i > firstMedia3 && at("android.os.Handler", "handleCallback")
}

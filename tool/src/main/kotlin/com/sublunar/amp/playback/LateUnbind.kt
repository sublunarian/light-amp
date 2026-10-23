package com.sublunar.amp.playback

/**
 * Whether [e] is media3 unbinding a detached player's controller through an
 * activity Android has already destroyed: the one exception [LateUnbindGuard]
 * lets the process live through, and nothing else.
 *
 * The pinned SDK builds the detached MediaController against the activity,
 * not the application. When Android destroys that activity while the process
 * lives on, it drops the activity's bindings itself, and nothing tells Amp's
 * handle or the controller inside it. When that controller is released later
 * (by Amp handing over to a new activity, or by media3 when Android stops the
 * idle service), media3 posts its unbind to the main looper against the dead
 * activity, and Android refuses it with exactly this. Posted, so no caller's
 * try is on the stack when it throws, and the process dies.
 *
 * Every condition is a reason to say no, so anything else on the main thread
 * still crashes exactly as it would have:
 *
 * - An IllegalArgumentException, which is what LoadedApk throws for an unbind
 *   it has no record of. Not an IllegalStateException with the same words.
 * - The message names media3's own connection: "Service not registered: "
 *   plus the connection, whose class has no toString of its own, so it reads
 *   as the class name and an address. The SDK's LightServiceConnection, or
 *   any other binding, fails here.
 * - The throw is in LoadedApk.forgetServiceDispatcher, called straight from
 *   ContextImpl.unbindService: the framework turning down an unbind, not other
 *   code that happens to use the same words.
 * - Above that only ContextWrapper's unbindService, once per wrapper the
 *   activity has (none is fine), and then media3's controller, every frame of
 *   which starts with MediaControllerImplBase. Nothing more exact than that
 *   prefix: a release build shows only R8's numbered synthetic lambda, a debug
 *   build the lambda method too, and both names are the compiler's to change.
 * - Directly above the media3 frames, Handler.handleCallback: the unbind ran
 *   as a runnable of its own, so no framework step (a lifecycle change, a
 *   service being stopped, a frame being drawn) is left half-done by the
 *   throw. All that goes undone is the rest of media3's tidy-up of a
 *   controller it has already released, and the unbind itself is moot, as
 *   the binding went with the activity.
 *
 * Fails closed: a build whose classes were renamed matches nothing, and the
 * crash simply comes back. That is why the release build keeps its names
 * (assets/r8/keep-names.txt), and why scripts/release-mapping-check.sh looks
 * in a release build's mapping for media3's connection, unrenamed, and for
 * media3's posted release still in a MediaControllerImplBase class, since R8
 * merges lambdas across classes even when it renames nothing. It runs after
 * every local release build before tagging, and from
 * scripts/store-build-check.sh on the store build. Never throws: a fault in
 * here answers no, and the original exception goes on as it was.
 *
 * Remove this, [LateUnbindGuard] and LateUnbindTest once the pinned SDK builds
 * the detached MediaController from the application, as its own
 * LightServiceConnection builds its binding. The rest of what goes with it is
 * under "The detached player is bound to the activity, not the app" in
 * tool/docs/SDK-GAPS.md.
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

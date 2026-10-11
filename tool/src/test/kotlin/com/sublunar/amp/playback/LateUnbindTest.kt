package com.sublunar.amp.playback

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LateUnbindTest {

    private val message =
        "Service not registered: androidx.media3.session.MediaControllerImplBase\$SessionServiceConnection@cd2b17f"

    private fun frame(className: String, method: String) = StackTraceElement(className, method, null, -1)

    private val unbind = arrayOf(
        frame("android.app.LoadedApk", "forgetServiceDispatcher"),
        frame("android.app.ContextImpl", "unbindService"),
    )

    private val wrapper = frame("android.content.ContextWrapper", "unbindService")

    private val releaseLambda =
        frame("androidx.media3.session.MediaControllerImplBase\$\$ExternalSyntheticLambda52", "run")

    /** What the main looper runs beneath a posted runnable, down to the process's own entry. */
    private val looper = arrayOf(
        frame("android.os.Handler", "handleCallback"),
        frame("android.os.Handler", "dispatchMessage"),
        frame("android.os.Looper", "loopOnce"),
        frame("android.os.Looper", "loop"),
        frame("android.app.ActivityThread", "main"),
        frame("java.lang.reflect.Method", "invoke"),
        frame("com.android.internal.os.RuntimeInit\$MethodAndArgsCaller", "run"),
        frame("com.android.internal.os.ZygoteInit", "main"),
    )

    private fun illegalArgument(vararg frames: StackTraceElement, text: String? = message): Throwable =
        IllegalArgumentException(text).apply { stackTrace = arrayOf(*frames) }

    /** dist/crash-repro-2026-10-09.txt, frame for frame: R8 has folded the lambda body into its synthetic class. */
    @Test
    fun `the release-build crash from the phone is absorbed`() {
        assertTrue(isLateMediaUnbind(illegalArgument(*unbind, wrapper, releaseLambda, *looper)))
    }

    /** Unminified, the lambda method shows as a frame of its own beneath the synthetic class. */
    @Test
    fun `the debug-build shape is absorbed`() {
        val e = illegalArgument(
            *unbind,
            wrapper,
            frame("androidx.media3.session.MediaControllerImplBase", "lambda\$release\$4"),
            releaseLambda,
            *looper,
        )
        assertTrue(isLateMediaUnbind(e))
    }

    /** A context that isn't wrapped at all unbinds straight from ContextImpl. */
    @Test
    fun `no ContextWrapper frame still matches`() {
        assertTrue(isLateMediaUnbind(illegalArgument(*unbind, releaseLambda, *looper)))
    }

    /**
     * Fails closed: a build that renamed its classes can't be recognised, and
     * the crash comes back rather than something else being swallowed. This
     * is what the 0.7.1 crashes looked like, before keep-names.txt.
     */
    @Test
    fun `a renamed build is not recognised`() {
        val e = illegalArgument(
            *unbind,
            wrapper,
            frame("d3.W", "run"),
            *looper,
            text = "Service not registered: d3.h0@cd2b17f",
        )
        assertFalse(isLateMediaUnbind(e))
    }

    /** Framework work between media3 and the posted runnable would be left half-done by carrying on. */
    @Test
    fun `framework work under the unbind is not absorbed`() {
        val underAFrame = illegalArgument(
            *unbind,
            wrapper,
            releaseLambda,
            frame("android.view.Choreographer\$CallbackRecord", "run"),
            frame("android.view.Choreographer", "doCallbacks"),
            *looper,
        )
        val underALifecycleStep = illegalArgument(
            *unbind,
            wrapper,
            releaseLambda,
            frame("android.app.ActivityThread", "handleDestroyActivity"),
            *looper,
        )
        assertFalse(isLateMediaUnbind(underAFrame))
        assertFalse(isLateMediaUnbind(underALifecycleStep))
    }

    /**
     * The SDK's own binding turned down the same way, as their stack under
     * media3's words and as their words on media3's stack: each fails alone.
     */
    @Test
    fun `someone else's connection is not absorbed`() {
        val other = "Service not registered: com.thelightphone.sdk.LightServiceConnection\$connection\$1@cd2b17f"
        val theirStack = illegalArgument(
            *unbind,
            wrapper,
            frame("com.thelightphone.sdk.LightServiceConnection", "unbind"),
            *looper,
        )
        assertFalse(isLateMediaUnbind(theirStack))
        assertFalse(isLateMediaUnbind(illegalArgument(*unbind, wrapper, releaseLambda, *looper, text = other)))
    }

    /**
     * media3's own words on a stack that isn't media3 unbinding, one condition
     * wrong at a time: each fails alone. Every case replaces the frame it
     * tests rather than dropping it, so the frames around it stay where the
     * matcher expects them and nothing else turns it away.
     */
    @Test
    fun `the right words on the wrong stack are not absorbed`() {
        val notTurnedDownByLoadedApk = illegalArgument(
            frame("android.app.LoadedApk", "getServiceDispatcher"),
            unbind[1],
            wrapper,
            releaseLambda,
            *looper,
        )
        val notFromContextImpl = illegalArgument(
            unbind[0],
            frame("android.app.ActivityThread", "handleUnbindService"),
            wrapper,
            releaseLambda,
            *looper,
        )
        // Nothing at all between the unbind and the posted runnable: any
        // frame there would be turned away by the runnable check instead.
        val noMedia3Frame = illegalArgument(*unbind, wrapper, *looper)
        assertFalse(isLateMediaUnbind(notTurnedDownByLoadedApk))
        assertFalse(isLateMediaUnbind(notFromContextImpl))
        assertFalse(isLateMediaUnbind(noMedia3Frame))
    }

    @Test
    fun `the same words in another exception type are not absorbed`() {
        val e = IllegalStateException(message).apply {
            stackTrace = arrayOf(*unbind, wrapper, releaseLambda, *looper)
        }
        assertFalse(isLateMediaUnbind(e))
    }

    @Test
    fun `an exception with no stack is not absorbed`() {
        assertFalse(isLateMediaUnbind(illegalArgument()))
    }

    @Test
    fun `an exception with no message is not absorbed`() {
        assertFalse(isLateMediaUnbind(illegalArgument(*unbind, wrapper, releaseLambda, *looper, text = null)))
    }
}

package com.thelightphone.sdk.display

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * TEMPORARY SPIKE — OFF-SDK WORKAROUND. NOT FOR SUBMISSION.
 *
 * Lets this app's artwork render in colour on a Light Phone III, by switching
 * the device's monochrome filter off while the app is in front and back on when
 * it leaves.
 *
 * **Why it has to be done this way.** LightOS renders the whole device
 * greyscale with the stock Android accessibility daltonizer:
 *
 * ```
 * settings get secure accessibility_display_daltonizer_enabled  → 1
 * settings get secure accessibility_display_daltonizer          → 0   # MONOCHROMACY
 * ```
 *
 * SurfaceFlinger applies that filter *after* composition, downstream of
 * everything an app draws, so no `ColorFilter`, theme or bitmap trick can defeat
 * it from inside the app. There is no per-app exemption on this device
 * (`dumpsys color_display` reports empty `mSaturationLevels` for every package),
 * and per-app saturation is a privileged `ColorDisplayManager` API the plugin
 * sandbox blocks anyway. The only lever left is the global setting.
 *
 * **Why it lives in `sdk/client`.** A tool module cannot reach a `Context` or a
 * `ContentResolver` — both are blocked imports. This module is exempt from
 * source validation, the same arrangement the DLNA cast and background-audio
 * spikes use.
 *
 * **It is device-wide while the app is open.** Every other app is in colour too
 * for as long as this one is in front. That is the deal; there is no narrower
 * version of it.
 *
 * **Needs a one-time grant**, which survives `install -r` but not a full
 * uninstall:
 * ```
 * adb shell pm grant com.sublunar.amp android.permission.WRITE_SECURE_SETTINGS
 * ```
 * Without it every write throws `SecurityException`, which is caught here — the
 * app runs exactly as before and the panel stays grey.
 *
 * **When Light offers a sanctioned path** (per-app saturation, or a LightOS
 * toggle), this whole file and its two call sites in [LightActivity] should go;
 * see the revert list in the class doc below.
 *
 * REVERT LIST:
 *  1. Delete this file.
 *  2. `LightActivity.onResume`/`onPause`: drop the `LightDisplayColor` calls
 *     and the import.
 *  3. `tool/src/debug/AndroidManifest.xml`: drop the `WRITE_SECURE_SETTINGS`
 *     `uses-permission` and the `tools` namespace.
 */
object LightDisplayColor {

    /**
     * The app's own switch, set from its settings.
     *
     * False means never touch the display at all — the workaround is off and the
     * phone stays as LightOS left it.
     */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            refresh()
        }

    /**
     * The last context to come through [applyForeground], so [enabled] can be
     * acted on the moment it changes rather than at the next screen transition.
     */
    @Volatile
    private var appContext: Context? = null

    /** Colour is all or nothing: if the app wants it, the whole app gets it. */
    private val wantColor: Boolean
        get() = enabled

    /** Re-apply whatever [enabled] now says, if the app is in front. */
    private fun refresh() {
        val context = appContext ?: return
        if (wantColor) applyForeground(context) else restore(context)
    }

    private const val KEY_ENABLED = "accessibility_display_daltonizer_enabled"

    /**
     * Remembers what the device's filter was set to before we touched it.
     *
     * A file rather than memory: if the process dies while the app is in front,
     * nothing runs [restore], and without this the phone would be left in colour
     * with no record of what it should have been. The next launch finds the
     * marker and puts the original value back.
     */
    private const val MARKER = "display-color-restore"

    private const val TAG = "LightDisplayColor"

    /** The filter's two states: off is colour, on is LightOS's grey default. */
    private const val OFF = 0
    private const val DEFAULT_ON = 1

    /** Colour on: the daltonizer goes off while this app is in front. */
    fun applyForeground(context: Context) {
        appContext = context.applicationContext
        // The flags decide; the lifecycle only says when to ask.
        if (!wantColor) return
        // A 0 here is not a baseline worth keeping: LightOS's own default is 1,
        // so the only way the filter is already off is that this workaround (or
        // a manual `settings put` while testing it) turned it off. Recording
        // that would make "restore" mean "stay in colour" for ever.
        val previous = currentValue(context)?.takeIf { it != OFF } ?: DEFAULT_ON
        val marker = File(context.filesDir, MARKER)
        // Only written the first time: a marker already there is from a run that
        // didn't get to restore, and it holds the value that actually matters.
        if (!marker.exists()) {
            runCatching { marker.writeText(previous.toString()) }
        }
        write(context, OFF)
    }

    /** Colour off: whatever the device had before this app opened. */
    fun restore(context: Context) {
        val marker = File(context.filesDir, MARKER)
        val previous = runCatching { marker.readText().trim().toInt() }.getOrNull() ?: DEFAULT_ON
        write(context, previous)
        runCatching { marker.delete() }
    }

    private fun currentValue(context: Context): Int? = runCatching {
        Settings.Secure.getInt(context.contentResolver, KEY_ENABLED, DEFAULT_ON)
    }.getOrElse {
        Log.w(TAG, "Can't read $KEY_ENABLED", it)
        null
    }

    private fun write(context: Context, value: Int) {
        runCatching {
            Settings.Secure.putInt(context.contentResolver, KEY_ENABLED, value)
        }.onFailure {
            // Expected until the one-time grant is run; a no-op, not a crash.
            Log.i(TAG, "No WRITE_SECURE_SETTINGS — leaving the display filter alone")
        }
    }
}

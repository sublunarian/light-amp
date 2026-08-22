package com.thelightphone.sdk

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * SDK PATCH (additive, upstreamable): whether this process holds [permission].
 *
 * [checkPermission] asks the server, and the server answers from its own policy
 * before it looks at the grant — BlockedByServer where the tool isn't meant to
 * have the permission, Unknown where it can't say. What decides whether a file
 * can be read is the grant itself, which the process can ask about directly,
 * and which the user may have made in Android's own settings without the
 * server hearing of it. A tool that only asked the server told such a phone to
 * allow access it already had, and sent it to a prompt with nothing to change.
 *
 * Null until the SDK is bound and has a context to ask.
 */
fun hasRuntimePermission(permission: String): Boolean? =
    LightServiceConnection.applicationContext?.let {
        ContextCompat.checkSelfPermission(it, permission) == PackageManager.PERMISSION_GRANTED
    }

package com.thelightphone.sdk.audio

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * SPIKE — background-audio + media-notification service. Revert with the rest of
 * the background-audio spike when LightOS ships official support.
 *
 * A Media3 [MediaSessionService] that surfaces the app's [MediaSession] (created
 * and owned by [LightAudioPlayer]) so the OS renders the standard media
 * notification + lock-screen transport, and keeps the process alive for
 * background playback. It does NOT own the player — [LightAudioPlayer] does, so
 * this never creates or releases it.
 */
class LightMediaService : MediaSessionService() {

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = activeSession

    // Force the service to stay foreground even while paused, so a backgrounded,
    // paused app isn't reclaimed and resume from the lock screen stays instant.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, true)
    }

    companion object {
        /** The app's live session, published by [LightAudioPlayer] while it exists. */
        @Volatile
        var activeSession: MediaSession? = null
    }
}

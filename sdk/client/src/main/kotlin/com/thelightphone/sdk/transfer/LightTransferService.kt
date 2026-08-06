package com.thelightphone.sdk.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.thelightphone.sdk.SealedLightContext

/**
 * SPIKE (background downloads): keeps a long file transfer running while the tool
 * is not in front.
 *
 * **Why it is needed.** A tool's download loop is just a coroutine in the app
 * process. Once the user leaves the app that process drops to the cached bucket
 * (`oom_score_adj` 900) and Android throttles it hard. Measured on the LP3 against
 * the real library: ~600 KB/s with the app in front, versus a single file taking
 * **350 seconds at 67 KB/s** with the screen off and the app backgrounded — about
 * a ninefold slowdown, which turns an overnight sync into almost nothing. The
 * throttle also ramps in over a minute or two, so short tests read far too
 * optimistically.
 *
 * **Why a foreground service and not WorkManager.** WorkManager is for *deferrable*
 * work: the system decides when it runs and pushes it into Doze maintenance
 * windows, which is the opposite of what a multi-hour user-initiated transfer
 * wants. Expedited work is quota-capped at around ten minutes. `dataSync` is the
 * category Android defines for exactly this case.
 *
 * **Caveats.** Android 14 caps `dataSync` foreground services at roughly six hours
 * per 24, after which the system stops them — a full-library pass may still span
 * more than one run, which is fine because completed tracks are indexed and
 * skipped. The notification below is mandatory for a foreground service; LightOS
 * does not surface notifications, so on the LP3 it is invisible.
 */
class LightTransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Working…"
        startInForegroundCompat(buildNotification(text))
        // Not sticky: if the system kills us, the tool restarts the transfer on
        // its own terms rather than us resurrecting with a stale intent.
        return START_NOT_STICKY
    }

    private fun startInForegroundCompat(notification: Notification) {
        // Unconditional and first: once startForegroundService() has been called,
        // Android gives the service five seconds to call startForeground() or it
        // kills the whole process with ForegroundServiceDidNotStartInTime. Even a
        // service we are about to stop must satisfy that contract.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            // A start can still be refused (quota exhausted, background-start
            // limits). Downloads keep working, just throttled.
            Log.w(TAG, "could not enter the foreground; transfer continues throttled", it)
            stopSelf()
        }
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LightTransfer"
        private const val CHANNEL_ID = "light-transfer"
        private const val NOTIFICATION_ID = 0x11D0
        private const val EXTRA_TEXT = "text"

        /**
         * Hold the process at foreground priority while a transfer runs.
         *
         * Safe to call repeatedly — a second start just refreshes the text.
         */
        fun start(context: SealedLightContext, text: String) =
            start(context.androidContext, text)

        internal fun start(context: Context, text: String) {
            val intent = Intent(context, LightTransferService::class.java)
                .putExtra(EXTRA_TEXT, text)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start transfer service", it) }
        }

        /** Release the process back to normal priority. Safe if never started. */
        fun stop(context: SealedLightContext) = stop(context.androidContext)

        internal fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LightTransferService::class.java)) }
                .onFailure { Log.w(TAG, "could not stop transfer service", it) }
        }
    }
}

package com.dji.sdk.sample.tak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import androidx.core.app.NotificationCompat
import com.dji.sdk.sample.R

/**
 * Keeps the TAK connection + drone PLI bridge alive while TAKPilot2 Go is backgrounded
 * or the screen is off. TakClient already auto-reconnects on socket drop; this service
 * just prevents Android from throttling/killing the process so the 2s PLI loop and the
 * RTSP push keep running during flight.
 *
 * The service doesn't own the connection — TakManager/TakBridgeHolder are process-wide
 * singletons. It just holds a foreground notification for as long as the bridge runs.
 */
class TakForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callsign = intent?.getStringExtra(EXTRA_CALLSIGN) ?: "TAKPilot2"
        startForeground(NOTIF_ID, buildNotification(callsign))
        AppLog.i(TAG, "TAK foreground service started ($callsign)")
        // Restart if the system kills us while flying — the bridge/connection persist.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i(TAG, "TAK foreground service stopped")
    }

    /**
     * Fires ONLY when the whole app task is swiped away from recents — not on ordinary
     * backgrounding, not on screen-off, both of which this service exists specifically to
     * survive (see the class doc). A task removal is a much stronger signal: it is the one
     * thing short of the Home screen's explicit STOP/QUIT that unambiguously means "the pilot
     * is done with this app."
     *
     * Field-reported 2026-07-27: without this, removing the app from recents did not disconnect
     * TAK at all — [START_STICKY] just let Android restart the service, silently re-establishing
     * the very connection the pilot thought they'd closed, and the operator's own presence stayed
     * showing as connected on the TAK server indefinitely. STOP/QUIT was the only thing that
     * actually disconnected, and it is a separate, easy-to-miss button under a "nuclear option"
     * label — not what most pilots would reach for or expect to need.
     *
     * stopSelf() after disconnecting so the notification doesn't linger claiming "connected"
     * once it no longer is, and so START_STICKY has nothing left to restart.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLog.i(TAG, "app removed from recents — disconnecting TAK before the service stops")
        runCatching { TakManager.getInstance().disconnect() }
        stopSelf()
    }

    private fun buildNotification(callsign: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "TAKPilot2 Go Link",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "Keeps the TAK connection and drone feed alive" }
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAKPilot2 Go connected")
            .setContentText("Streaming $callsign to TAK")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "TakForegroundService"
        private const val CHANNEL_ID = "takpilot2_link"
        private const val NOTIF_ID = 4201
        private const val EXTRA_CALLSIGN = "callsign"

        fun start(context: Context, callsign: String) {
            val i = Intent(context, TakForegroundService::class.java)
                .putExtra(EXTRA_CALLSIGN, callsign)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TakForegroundService::class.java))
        }
    }
}

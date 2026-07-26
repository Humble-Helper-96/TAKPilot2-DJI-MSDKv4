package com.dji.sdk.sample.tak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.taklite.util.AppLog

/**
 * Foreground service that hosts the screen-capture RTSP stream (Phase 5). Android 14+ requires
 * a foreground service of type `mediaProjection` to be running BEFORE MediaProjection is used,
 * so this service: starts foreground first, then builds the projection from the permission
 * result, then hands it to [VideoStreamerHolder.startScreenCapture]. The projection lifecycle
 * (and this service) live for as long as the stream does.
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.i(TAG, "stop requested — tearing down projection + service")
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }

        // Must be foreground (with the mediaProjection type) BEFORE getMediaProjection() on 14+.
        AppLog.i(TAG, "starting foreground service (type=mediaProjection, sdk=${Build.VERSION.SDK_INT})")
        startInForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)

        if (data == null) {
            AppLog.w(TAG, "no projection data — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            AppLog.w(TAG, "getMediaProjection returned null — permission unavailable, stopping")
            toast("Screen capture permission unavailable")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = proj
        AppLog.i(TAG, "MediaProjection acquired — handing to VideoStreamerHolder")

        val started = VideoStreamerHolder.startScreenCapture(applicationContext, proj) { ok, msg ->
            AppLog.i(TAG, "stream status: ok=$ok $msg")
            if (!ok) android.os.Handler(mainLooper).post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
        }
        if (!started) {
            AppLog.w(TAG, "startScreenCapture refused — no video server configured; stopping")
            toast("Configure the video server in Pre-Flight Setup first")
            teardown()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val channelId = "takpilot2_screen_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Video Streaming", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TAKPilot2 — Streaming video")
            .setContentText("Sharing the flight screen to the TAK server")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun teardown() {
        runCatching { projection?.stop() }
        projection = null
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy — projection released, screen capture ended")
        teardown()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    private fun toast(msg: String) {
        android.os.Handler(mainLooper).post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_ID = 4711
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "com.dji.sdk.sample.tak.STOP_SCREEN_CAPTURE"

        /** Start capture: call from onActivityResult of the screen-capture permission request. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        /** Stop capture (also stops the projection). Safe to call when not running. */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}

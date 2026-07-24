package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import com.taklite.client.tak.TakManager
import java.io.File
import java.util.UUID

/**
 * Silent TAK reconnect using previously-saved enrollment certs — used on app boot so the
 * operator lands in the flight screen already connected (no menu, no re-entry).
 *
 * Shares the same SharedPreferences keys as [TakConnectActivity].
 */
object TakAutoConnect {
    private const val TAG = "TakAutoConnect"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_HOST = "host"
    private const val KEY_COT_PORT = "cot_port"
    private const val KEY_USERNAME = "username"
    private const val KEY_CALLSIGN = "callsign"
    private const val KEY_UID = "uid"
    private const val KEY_TRUSTSTORE = "truststore_path"
    private const val KEY_CLIENTCERT = "clientcert_path"
    private const val KEY_CAMERA_POINT = "camera_point"
    private const val KEY_LOGGED_OUT = "logged_out"
    private const val KEY_CHANNELS = "channels"

    /** Reconnect in the background if we have saved certs and aren't already connected. */
    fun tryReconnect(context: Context) {
        if (TakManager.getInstance().isConnected) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_LOGGED_OUT, false)) {
            AppLog.i(TAG, "User logged out — skipping auto-connect")
            return
        }
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val cotPort = prefs.getInt(KEY_COT_PORT, 8089)
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        val callsign = prefs.getString(KEY_CALLSIGN, "TAKPilot2-M30") ?: "TAKPilot2-M30"

        if (host.isEmpty() || ts.isEmpty() || cc.isEmpty() ||
            !File(ts).exists() || !File(cc).exists()
        ) {
            AppLog.i(TAG, "No saved enrollment — skipping auto-connect")
            return
        }

        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }

        Thread {
            TakManager.getInstance().connect(
                uid, username, "Cyan", "Team Member",
                host, cotPort, ts, "atakatak", cc, "atakatak",
            )
            // Re-apply the saved channel routing selection.
            (prefs.getString(KEY_CHANNELS, "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                .let { if (it.isNotEmpty()) TakManager.getInstance().setChannels(it) }
            TakBridgeHolder.start("$uid-DRONE", callsign)
            TakBridgeHolder.setCameraPointEnabled(prefs.getBoolean(KEY_CAMERA_POINT, false))
            TakForegroundService.start(context.applicationContext, callsign)
            AppLog.i(TAG, "Auto-connected to $host:$cotPort as $callsign")
        }.start()
    }
}

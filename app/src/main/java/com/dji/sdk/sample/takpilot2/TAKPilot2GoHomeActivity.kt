package com.dji.sdk.sample.takpilot2

import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.R
import com.dji.sdk.sample.DataSyncActivity
import com.dji.sdk.sample.internal.controller.DJISampleApplication
import com.dji.sdk.sample.tak.DebugActivity
import com.dji.sdk.sample.tak.DjiSdkBridge
import com.dji.sdk.sample.tak.TakConnectActivity
import com.taklite.client.tak.TakManager
import com.taklite.util.AppLog
import dji.sdk.sdkmanager.DJISDKManager

/**
 * TAKPilot2 Go home screen (Phase 3) — phone-first replacement for DJI's stock landing
 * screen, and (as of the direct-launch change) the app's launcher activity. Quick Controls
 * card (TAK Setup / Data Sync) + a large "Enter Flight" card that opens the custom flight
 * screen ([TAKPilot2GoFlightActivity]).
 *
 * Registers with the DJI SDK and starts the product connection itself on launch via
 * [DjiSdkBridge] — no more visiting the stock MainActivity/MainContent "Register App" +
 * "Open" screen first (see DJI/TAKPILOT2_V4_PORT_PLAN.md). [updateStatus] already polled
 * [DJISampleApplication.getProductInstance] and rendered "Not connected" gracefully before
 * this change, so no new connecting-state UI was needed — it just needed something to
 * actually trigger the registration/connection.
 */
class TAKPilot2GoHomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var aircraft: TextView
    private lateinit var sdk: TextView
    private lateinit var battery: TextView
    private lateinit var takStatus: TextView
    private lateinit var takDot: android.view.View

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_takpilot2go_home)
        AppLog.v(TAG, "onCreate")

        aircraft = findViewById(R.id.homeAircraft)
        sdk = findViewById(R.id.homeSdk)
        battery = findViewById(R.id.homeBattery)
        takStatus = findViewById(R.id.homeTakStatus)
        takDot = findViewById(R.id.homeTakDot)

        if (DjiSdkBridge.hasMissingPermissions(this)) {
            DjiSdkBridge.requestMissingPermissions(this)
        } else {
            DjiSdkBridge.registerAndConnect(this)
        }

        findViewById<android.view.View>(R.id.homeEnterFlight).setOnClickListener {
            AppLog.v(TAG, "tap: Enter Flight")
            startActivity(Intent(this, TAKPilot2GoFlightActivity::class.java))
        }
        findViewById<Button>(R.id.homeTakSetup).setOnClickListener {
            AppLog.v(TAG, "tap: Pre-Flight Setup")
            startActivity(Intent(this, TakConnectActivity::class.java))
        }
        findViewById<Button>(R.id.homeDataSync).setOnClickListener {
            AppLog.v(TAG, "tap: Data Sync")
            startActivity(Intent(this, DataSyncActivity::class.java))
        }
        findViewById<Button>(R.id.homeDebugLog).setOnClickListener {
            AppLog.v(TAG, "tap: Debug Log")
            startActivity(Intent(this, DebugActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(TAG, "onResume")
        handler.post(refresh)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Ported from MainActivity.onNewIntent: relay the USB accessory attach so the DJI
        // SDK notices the RC-N1 plugged in while this activity is already on top (moved here
        // since TAKPilot2GoHomeActivity is now the launcher — see the manifest).
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED == intent.action) {
            sendBroadcast(Intent(DJISDKManager.USB_ACCESSORY_ATTACHED))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == DjiSdkBridge.PERMISSION_REQUEST_CODE && !DjiSdkBridge.hasMissingPermissions(this)) {
            DjiSdkBridge.registerAndConnect(this)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun updateStatus() {
        val product = DJISampleApplication.getProductInstance()
        aircraft.text = product?.model?.displayName ?: "Not connected"
        sdk.text = "MSDK 4.18"
        // Battery telemetry lands in Phase 4 alongside the rest of the drone -> CoT bridge.
        battery.text = "Battery —"

        val connected = TakManager.getInstance().isConnected
        val color = if (connected) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        takStatus.text = if (connected) "TAK: Connected" else "TAK: Disconnected"
        takStatus.setTextColor(color)
        (takDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: takDot.background?.setTint(color)
    }

    companion object {
        private const val TAG = "TAKPilot2GoHome"
    }
}

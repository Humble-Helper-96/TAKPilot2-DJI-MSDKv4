package com.dji.sdk.sample.takpilot2

import androidx.core.content.ContextCompat
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dji.sdk.sample.BuildConfig
import com.dji.sdk.sample.R
import com.dji.sdk.sample.tak.NetworkStatus
import com.dji.sdk.sample.DataSyncActivity
import com.dji.sdk.sample.internal.controller.DJISampleApplication
import com.dji.sdk.sample.tak.DebugActivity
import com.dji.sdk.sample.tak.DjiSdkBridge
import com.dji.sdk.sample.tak.FlightLimitsController
import com.dji.sdk.sample.tak.FlightPathLogger
import com.dji.sdk.sample.tak.TakAutoConnect
import com.dji.sdk.sample.tak.TakBridgeHolder
import com.dji.sdk.sample.tak.TakConnectActivity
import com.dji.sdk.sample.tak.TakForegroundService
import com.dji.sdk.sample.tak.VideoStreamerHolder
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
 * "Open" screen first (see docs/TAKPILOT2_V4_PORT_SUMMARY.md). [updateStatus] already polled
 * [DJISampleApplication.getProductInstance] and rendered "Not connected" gracefully before
 * this change, so no new connecting-state UI was needed — it just needed something to
 * actually trigger the registration/connection.
 */
class TAKPilot2GoHomeActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var aircraft: TextView
    private lateinit var sdk: TextView
    private lateinit var batteryLevels: TextView
    private lateinit var takStatus: TextView
    private lateinit var takDot: android.view.View
    private lateinit var network: TextView
    private lateinit var networkDot: android.view.View

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else that can throw: the flight screen's OOM-restart guard reads this
        // to tell "the pilot walked here" from "Android resurrected the task into a cold process".
        visitedThisProcess = true
        setContentView(R.layout.activity_takpilot2go_home)
        AppLog.v(TAG, "onCreate")

        aircraft = findViewById(R.id.homeAircraft)
        sdk = findViewById(R.id.homeSdk)
        batteryLevels = findViewById(R.id.homeBatteryLevels)
        takStatus = findViewById(R.id.homeTakStatus)
        takDot = findViewById(R.id.homeTakDot)
        network = findViewById(R.id.homeNetwork)
        networkDot = findViewById(R.id.homeNetworkDot)

        // Fixed at build time, not runtime state — set once, never touched in updateStatus().
        // BuildConfig.VERSION_NAME rather than the PackageManager: same string, no IPC, and it
        // cannot disagree with what the TAK server was told (TakManager reports it as
        // <takv version>). versionCode is deliberately absent — an internal integer with no
        // semver meaning, and BUILD_TIME already identifies a build more precisely.
        findViewById<TextView>(R.id.homeVersion).text =
            "v${BuildConfig.VERSION_NAME}  ·  built ${BuildConfig.BUILD_TIME}"

        if (DjiSdkBridge.hasMissingPermissions(this)) {
            DjiSdkBridge.requestMissingPermissions(this)
        } else {
            DjiSdkBridge.registerAndConnect(this)
        }

        // If a TAK server is configured (saved enrollment), connect and pull channels now —
        // one shot per process, so the pilot never has to open Pre-Flight Setup just to get
        // back online.
        TakAutoConnect.attemptOnAppLaunch(applicationContext)

        findViewById<android.view.View>(R.id.homeEnterFlight).setOnClickListener {
            AppLog.v(TAG, "tap: Enter Flight")
            startActivity(Intent(this, TAKPilot2GoFlightActivity::class.java))
        }
        findViewById<Button>(R.id.homeTakSetup).setOnClickListener {
            AppLog.v(TAG, "tap: Pre-Flight Setup")
            startActivity(Intent(this, TakConnectActivity::class.java))
        }
        findViewById<Button>(R.id.homeFieldGuide).setOnClickListener {
            AppLog.v(TAG, "tap: Field Guide")
            startActivity(Intent(this, FieldGuideActivity::class.java))
        }
        findViewById<Button>(R.id.homeDataSync).setOnClickListener {
            AppLog.v(TAG, "tap: Data Sync")
            startActivity(Intent(this, DataSyncActivity::class.java))
        }
        findViewById<Button>(R.id.homeDebugLog).setOnClickListener {
            AppLog.v(TAG, "tap: Debug Log")
            startActivity(Intent(this, DebugActivity::class.java))
        }
        findViewById<Button>(R.id.homeQuit).setOnClickListener {
            AppLog.v(TAG, "tap: STOP/QUIT")
            confirmQuit()
        }
    }

    /** The "nuclear option": tear down every long-lived TAKPilot2 process (video stream +
     *  screen capture, TAK connection + its foreground service, telemetry bridge) and then
     *  kill this process outright, so a relaunch starts completely clean — for clearing out
     *  any stuck state found mid-operation without having to know which subsystem is wedged. */
    private fun confirmQuit() {
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Stop & Quit")
            .setMessage("Force-stop TAKPilot2 Go and all its background processes (video stream, TAK connection, telemetry)? You'll need to relaunch the app.")
            .setPositiveButton("Stop & Quit") { _, _ -> doQuit() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doQuit() {
        AppLog.i(TAG, "STOP/QUIT — tearing down and killing process")
        runCatching { VideoStreamerHolder.stop() }
        // Before the bridge goes: this posts the GPX write to the logger's worker, and the
        // 200ms delay before killProcess below is what lets it land. If it does not, the
        // orphan sweep completes it at the next launch — the CSV is already on disk either way.
        runCatching { FlightPathLogger.endSession("stop/quit") }
        runCatching { TakBridgeHolder.stop() }
        runCatching { TakManager.getInstance().disconnect() }
        runCatching { TakForegroundService.stop(applicationContext) }
        handler.removeCallbacksAndMessages(null)
        finishAffinity()
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 200)
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

        // Battery levels, straight from the AIRCRAFT — never the saved preference. If Apply
        // did not take, this is where it shows, so falling back to what the pilot typed would
        // hide the one failure this line exists to catch. Blank before a product is connected
        // and a dash until the aircraft answers: unknown must not look like a value.
        val warn = FlightLimitsController.aircraftWarningPct
        val crit = FlightLimitsController.aircraftCriticalPct
        batteryLevels.text = when {
            product == null -> ""
            warn != null && crit != null -> "BATTERY: WARN $warn% \u00b7 CRIT $crit%"
            else -> "BATTERY: \u2014"
        }
        batteryLevels.setTextColor(
            ContextCompat.getColor(
                applicationContext,
                if (product != null && (warn == null || crit == null)) R.color.tp_state_unknown
                else R.color.tp_text_secondary,
            )
        )

        val connected = TakManager.getInstance().isConnected
        val color = if (connected) ContextCompat.getColor(applicationContext, R.color.tp_state_go) else ContextCompat.getColor(applicationContext, R.color.tp_state_danger)
        takStatus.text = if (connected) "TAK: Connected" else "TAK: Disconnected"
        takStatus.setTextColor(color)
        (takDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: takDot.background?.setTint(color)

        updateNetwork()
    }

    /**
     * The network line. Green only when the system has CONFIRMED reachability — an attached
     * network that goes nowhere reads amber, which is the case that otherwise looks like a
     * broken TAK server. See [NetworkStatus].
     */
    private fun updateNetwork() {
        val net = NetworkStatus.read(this)
        val bars = net.bars()
        val suffix = if (bars.isEmpty()) "" else "  $bars"
        network.text = when (net.state) {
            NetworkStatus.State.CONNECTED -> "Network: ${net.label}$suffix"
            NetworkStatus.State.NO_INTERNET -> "Network: ${net.label} — no internet$suffix"
            NetworkStatus.State.OFF -> "Network: none"
        }
        val color = ContextCompat.getColor(
            applicationContext,
            when (net.state) {
                NetworkStatus.State.CONNECTED -> R.color.tp_state_go
                // We KNOW there is no route out — that is caution, not unknown. §6.1.
                NetworkStatus.State.NO_INTERNET -> R.color.tp_state_caution
                NetworkStatus.State.OFF -> R.color.tp_state_danger
            }
        )
        network.setTextColor(color)
        // Same dot treatment as the TAK line it sits under, so the two read as one status block
        // rather than a status and a caption.
        (networkDot.background as? android.graphics.drawable.GradientDrawable)?.setColor(color)
            ?: networkDot.background?.setTint(color)
    }

    companion object {
        private const val TAG = "TAKPilot2GoHome"

        /**
         * True once this PROCESS has passed through Home, which is the only place the DJI SDK is
         * registered and the product connection is started.
         *
         * Deliberately a plain static, not a persisted flag: it must reset when the process dies.
         * That is the whole signal — see the OOM-restart guard in [TAKPilot2GoFlightActivity].
         */
        @Volatile
        var visitedThisProcess = false
            private set
    }
}

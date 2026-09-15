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
import com.dji.sdk.sample.tak.AppPermissions
import com.dji.sdk.sample.tak.MediaServerProbe
import com.dji.sdk.sample.tak.VideoTransport
import com.dji.sdk.sample.tak.ControlResponse
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
    private lateinit var permissionsStatus: TextView
    private lateinit var permissionsDot: android.view.View
    private lateinit var mediaStatus: TextView
    private lateinit var mediaDot: android.view.View
    private lateinit var signalLoss: TextView
    private lateinit var stickMode: TextView
    private lateinit var controlResponse: TextView
    private lateinit var storage: TextView
    private lateinit var initializing: TextView

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
        // Full screen, like the flight screen (operator, 2026-09-14): the status bar and the
        // navigation strip were taking 63px and 126px from a card that is measured to the
        // pixel. Same flags, same re-apply on focus, as TAKPilot2GoFlightActivity.
        applyImmersive()

        aircraft = findViewById(R.id.homeAircraft)
        sdk = findViewById(R.id.homeSdk)
        batteryLevels = findViewById(R.id.homeBatteryLevels)
        takStatus = findViewById(R.id.homeTakStatus)
        takDot = findViewById(R.id.homeTakDot)
        network = findViewById(R.id.homeNetwork)
        networkDot = findViewById(R.id.homeNetworkDot)
        permissionsStatus = findViewById(R.id.homePermissionsStatus)
        permissionsDot = findViewById(R.id.homePermissionsDot)
        mediaStatus = findViewById(R.id.homeMediaStatus)
        mediaDot = findViewById(R.id.homeMediaDot)
        signalLoss = findViewById(R.id.homeSignalLoss)
        stickMode = findViewById(R.id.homeStickMode)
        controlResponse = findViewById(R.id.homeControlResponse)
        storage = findViewById(R.id.homeStorage)
        initializing = findViewById(R.id.homeInitializing)
        // The permissions line asks when it is red — see askForPermissions.
        findViewById<android.view.View>(R.id.homePermissionsRow).setOnClickListener { askForPermissions() }

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
            if (initializingUntilMs > System.currentTimeMillis()) {
                AppLog.v(TAG, "Enter Flight tapped during initialise — ignored")
                android.widget.Toast.makeText(this, "Wait. The app is setting up the aircraft.",
                    android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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

    @Suppress("DEPRECATION")
    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Immersive-sticky flags are cleared by system dialogs and shade swipes; re-apply on
        // focus so the bars do not creep back.
        if (hasFocus) applyImmersive()
    }

    private fun updateStatus() {
        val product = DJISampleApplication.getProductInstance()
        val hud = TakBridgeHolder.hud()
        aircraft.text = product?.model?.displayName ?: "Not connected"
        sdk.text = "MSDK 4.18"

        // The hold starts when the aircraft first appears — that is when the connect-time
        // pushes (limits, exposure, stick mode, control response) are scheduled, so that is
        // when there is something to wait for.
        if (product != null && !sawProduct) { sawProduct = true; startInitializing() }
        else if (product == null) sawProduct = false

        // BATTERY, LIVE — the aircraft's charge and the RC's, from the bridge's snapshot. Zero
        // and null both mean "not reported yet": batteryPct starts at 0 before the first
        // battery frame, and a 0 drawn in red would send a pilot to change a full pack.
        // Coloured by the thresholds the aircraft HOLDS (20/10 on a Mini 2, rule 11).
        val air = hud?.batteryPct?.takeIf { it > 0 }
        val rc = hud?.rcBatteryPct
        val warn = FlightLimitsController.aircraftWarningPct
        val crit = FlightLimitsController.aircraftCriticalPct
        batteryLevels.text = if (product == null && air == null && rc == null) "" else
            "BATTERY: AIRCRAFT ${air?.let { "$it%" } ?: "\u2014"}  \u00b7  RC ${rc?.let { "$it%" } ?: "\u2014"}"
        batteryLevels.setTextColor(color(when {
            air == null -> R.color.tp_state_unknown
            crit != null && air <= crit -> R.color.tp_state_danger
            warn != null && air <= warn -> R.color.tp_state_caution
            else -> R.color.tp_state_go
        }))

        // SIGNAL LOSS — what the AIRCRAFT holds, read back after the connect-time push. Green
        // for a return, caution for hover or land (the aircraft will not come back by itself),
        // amber until it has answered.
        val fs = FlightLimitsController.aircraftFailsafe
        signalLoss.text = when {
            product == null -> ""
            fs == dji.common.flightcontroller.ConnectionFailSafeBehavior.GO_HOME -> "SIGNAL LOSS: RETURNS HOME"
            fs == dji.common.flightcontroller.ConnectionFailSafeBehavior.HOVER -> "SIGNAL LOSS: HOVERS"
            fs == dji.common.flightcontroller.ConnectionFailSafeBehavior.LANDING -> "SIGNAL LOSS: LANDS"
            else -> "SIGNAL LOSS: \u2014"
        }
        signalLoss.setTextColor(color(when (fs) {
            dji.common.flightcontroller.ConnectionFailSafeBehavior.GO_HOME -> R.color.tp_state_go
            dji.common.flightcontroller.ConnectionFailSafeBehavior.HOVER,
            dji.common.flightcontroller.ConnectionFailSafeBehavior.LANDING -> R.color.tp_state_caution
            else -> R.color.tp_state_unknown
        }))

        // STICKS — the mode Pre-Flight pushes at connect; the SDK has no read-back for it.
        stickMode.text = if (product == null) "" else
            "STICKS: ${FlightLimitsController.savedStickMode(this).label.uppercase()}"
        stickMode.setTextColor(color(R.color.tp_text_secondary))

        // CONTROL RESPONSE — the saved mode, amber until the aircraft's pitch-speed read-back
        // says the push landed (ControlResponse.aircraftPitchSpeed).
        controlResponse.text = if (product == null) "" else
            "CONTROL RESPONSE: ${ControlResponse.saved(this).label.uppercase()}"
        controlResponse.setTextColor(color(
            if (ControlResponse.aircraftPitchSpeed != null) R.color.tp_text_secondary else R.color.tp_state_unknown))

        // SD CARD — where the footage goes and whether there is room. Red is "you will get no
        // recording"; amber is "the camera has not said".
        val sd = TakBridgeHolder.storage()
        storage.text = when {
            product == null -> ""
            sd == null -> "SD CARD: \u2014"
            !sd.isInserted -> "SD CARD: NOT INSERTED"
            sd.hasError() || sd.isInvalidFormat || sd.isReadOnly -> "SD CARD: ERROR"
            sd.isFull -> "SD CARD: FULL"
            else -> "SD CARD \u00b7 %.1f GB FREE".format(sd.remainingSpaceInMB / 1024.0)
        }
        storage.setTextColor(color(when {
            sd == null -> R.color.tp_state_unknown
            !sd.isInserted || sd.hasError() || sd.isInvalidFormat || sd.isReadOnly || sd.isFull -> R.color.tp_state_danger
            else -> R.color.tp_state_go
        }))

        // ---- the four checks ----
        val permsOk = AppPermissions.allGranted(this)
        permissionsStatus.text = if (permsOk) "APP PERMISSIONS: Granted" else "APP PERMISSIONS: Denied"
        dot(permissionsStatus, permissionsDot, color(if (permsOk) R.color.tp_state_go else R.color.tp_state_danger))

        val connected = TakManager.getInstance().isConnected
        takStatus.text = if (connected) "TAK: Connected" else "TAK: Disconnected"
        dot(takStatus, takDot, color(if (connected) R.color.tp_state_go else R.color.tp_state_danger))

        updateNetwork()
        renderMediaServer()
    }

    private fun color(res: Int) = ContextCompat.getColor(applicationContext, res)

    private fun dot(text: TextView, d: android.view.View, c: Int) {
        text.setTextColor(c)
        (d.background as? android.graphics.drawable.GradientDrawable)?.setColor(c) ?: d.background?.setTint(c)
    }

    /**
     * The Wi-Fi line. Green only when the system has CONFIRMED reachability — an attached
     * network that goes nowhere reads amber, which is the case that otherwise looks like a
     * broken TAK server. See [NetworkStatus]. The SSID needs location; without it the line
     * still says connected.
     */
    private fun updateNetwork() {
        val net = NetworkStatus.read(this)
        val bars = net.bars()
        val suffix = if (bars.isEmpty()) "" else "  $bars"
        network.text = when (net.state) {
            NetworkStatus.State.CONNECTED -> "WIFI: ${net.label}$suffix"
            NetworkStatus.State.NO_INTERNET -> "WIFI: ${net.label} \u2014 NO INTERNET$suffix"
            NetworkStatus.State.OFF -> "WIFI: NOT CONNECTED"
        }
        dot(network, networkDot, color(when (net.state) {
            NetworkStatus.State.CONNECTED -> R.color.tp_state_go
            NetworkStatus.State.NO_INTERNET -> R.color.tp_state_caution
            NetworkStatus.State.OFF -> R.color.tp_state_danger
        }))
    }

    /**
     * The media-server line, and the probe behind it, re-asked every [MEDIA_PROBE_PERIOD_MS] off
     * the UI thread. ⚠ GREEN MEANS THE SERVER IS UP, NOT THAT IT WILL TAKE THE STREAM — see
     * [MediaServerProbe]. The LIVE pill on the flight screen stays the authority on that.
     */
    private fun renderMediaServer() {
        val p = getSharedPreferences(VIDEO_PREFS, MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val port = p.getInt("video_rtsp_port", VideoTransport.RTSP.defaultPort)
        val now = android.os.SystemClock.elapsedRealtime()
        if (!mediaProbeRunning && now - mediaProbeAtMs > MEDIA_PROBE_PERIOD_MS) {
            mediaProbeRunning = true
            Thread {
                val r = MediaServerProbe.probe(host, port)
                mediaProbe = r
                mediaProbeAtMs = android.os.SystemClock.elapsedRealtime()
                mediaProbeRunning = false
                runOnUiThread { if (!isFinishing) renderMediaServer() }
            }.apply { isDaemon = true; name = "media-probe" }.start()
        }
        mediaStatus.text = when (mediaProbe) {
            MediaServerProbe.Result.REACHABLE -> "MEDIA SERVER: Reachable"
            MediaServerProbe.Result.UNREACHABLE -> "MEDIA SERVER: Unreachable"
            MediaServerProbe.Result.NOT_CONFIGURED -> "MEDIA SERVER: Not set"
            null -> "MEDIA SERVER: \u2014"
        }
        dot(mediaStatus, mediaDot, color(when (mediaProbe) {
            MediaServerProbe.Result.REACHABLE -> R.color.tp_state_go
            MediaServerProbe.Result.UNREACHABLE -> R.color.tp_state_danger
            else -> R.color.tp_state_unknown
        }))
    }
    @Volatile private var mediaProbe: MediaServerProbe.Result? = null
    @Volatile private var mediaProbeRunning = false
    private var mediaProbeAtMs = -MEDIA_PROBE_PERIOD_MS

    /**
     * Asks for every permission still missing, and falls back to the settings page when Android
     * will not ask again. shouldShowRequestPermissionRationale reads false BOTH before the first
     * ask and after a permanent denial; what separates them is whether we have asked before.
     */
    private fun askForPermissions() {
        val missing = AppPermissions.missing(this)
        if (missing.isEmpty()) return
        val prefs = getSharedPreferences(VIDEO_PREFS, MODE_PRIVATE)
        val askedBefore = prefs.getBoolean(KEY_ASKED_PERMISSIONS, false)
        val canAsk = missing.any { androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
        if (!askedBefore || canAsk) {
            AppLog.i(TAG, "asking for permissions: $missing")
            prefs.edit().putBoolean(KEY_ASKED_PERMISSIONS, true).apply()
            androidx.core.app.ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            AppLog.i(TAG, "Android will not ask again — opening the app's settings page")
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null)))
        }
    }

    /**
     * Holds the card for [INITIALIZING_MS] after the aircraft first appears: a pilot who taps
     * straight through lands on the flight screen mid-push and cannot know what was applied.
     * The word PULSES on purpose — a frozen label on a screen that refuses taps reads as a crash.
     */
    private fun startInitializing() {
        initializingUntilMs = System.currentTimeMillis() + INITIALIZING_MS
        initializing.visibility = android.view.View.VISIBLE
        initializing.alpha = 1f
        initializing.animate().cancel()
        pulseInitializing()
        handler.removeCallbacks(endInitializing)
        handler.postDelayed(endInitializing, INITIALIZING_MS)
    }
    private fun pulseInitializing() {
        if (initializingUntilMs <= System.currentTimeMillis()) return
        initializing.animate().alpha(0.25f).setDuration(700L).withEndAction {
            if (initializingUntilMs <= System.currentTimeMillis()) return@withEndAction
            initializing.animate().alpha(1f).setDuration(700L).withEndAction { pulseInitializing() }.start()
        }.start()
    }
    private val endInitializing = Runnable {
        initializingUntilMs = 0L
        initializing.animate().cancel()
        initializing.visibility = android.view.View.GONE
        AppLog.v(TAG, "initialise hold released")
    }
    private var initializingUntilMs = 0L
    private var sawProduct = false

    companion object {
        private const val TAG = "TAKPilot2GoHome"
        private const val VIDEO_PREFS = "takpilot2_tak"
        private const val REQUEST_CODE_PERMISSIONS = 4301
        private const val KEY_ASKED_PERMISSIONS = "asked_app_permissions"
        /** Long enough for the connect-time pushes AND for the pilot to read the card. */
        private const val INITIALIZING_MS = 5000L
        /** The home screen repaints every 1.5 s and a probe is a network round trip; probing per
         *  paint would be a port scan of the operator's own server. */
        private const val MEDIA_PROBE_PERIOD_MS = 10_000L

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

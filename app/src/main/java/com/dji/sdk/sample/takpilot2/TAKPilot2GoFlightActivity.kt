package com.dji.sdk.sample.takpilot2

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R
import com.dji.sdk.sample.internal.controller.DJISampleApplication
import com.dji.sdk.sample.tak.CameraSlantPoint
import com.dji.sdk.sample.tak.ExposureController
import com.dji.sdk.sample.tak.TakBridgeHolder
import com.dji.sdk.sample.tak.VideoStreamerHolder
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAllowOverlap
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconIgnorePlacement
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconImage
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconRotate
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconRotationAlignment
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconSize
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineColor
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineWidth
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.visibility
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.taklite.client.tak.TakManager
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import java.util.UUID

/**
 * TAKPilot2 Go flight screen — live FPV video (full-bleed) + a live map overlay, phone-sized.
 * Owns the [TakBridgeHolder] telemetry-to-CoT bridge's lifecycle (Phase 4): starts it on
 * entry, stops it on exit. TakManager.sendDronePLI() no-ops internally when TAK isn't
 * connected, so this runs safely whether or not TAK Setup has been done yet.
 *
 * Also drives the on-screen telemetry HUD + own-aircraft map marker (Phase 4 addendum) — a
 * 500ms poll of [TakBridgeHolder.hud], same pattern as the Autel sibling app's FlightActivity.
 * No TAK map overlays here yet (that's Phase 6: inbound contact markers/dropped pins/AR);
 * RTSP video push (Phase 5) isn't wired in either.
 */
class TAKPilot2GoFlightActivity : AppCompatActivity() {

    private lateinit var fpvView: FpvTextureView
    private lateinit var mapView: MapView
    private lateinit var noVideoCover: View
    private lateinit var fpvOverlayText: TextView
    private lateinit var toolbarBattery: BatteryGaugeView
    private lateinit var toolbarGps: TextView
    private lateinit var toolbarGpsIcon: ImageView
    private lateinit var toolbarTakDot: ImageView
    private lateinit var toolbarSignal: SignalBarsView
    private lateinit var toolbarSignalText: TextView
    private lateinit var liveToggle: LiveToggleView
    private lateinit var recordToggle: RecordToggleView
    private lateinit var rthButton: ImageButton
    private lateinit var exposureReadout: TextView
    private var currentCallsign: String = ""

    private var map: MapboxMap? = null
    private var aircraftSource: GeoJsonSource? = null
    private var aircraftLayer: SymbolLayer? = null
    private var homeSource: GeoJsonSource? = null
    private var homeLayer: SymbolLayer? = null
    private var homeLineSource: GeoJsonSource? = null
    private var homeLineLayer: LineLayer? = null
    private lateinit var fpvNotice: TextView
    // Edge-triggers the "Home Point Set" notice only on the false->true transition (not every
    // tick while it's already set), and only once per bridge session.
    private var lastHomeSet = false

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateHud()
            handler.postDelayed(this, HUD_INTERVAL_MS)
        }
    }
    private val hideNotice = Runnable { fpvNotice.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        Mapbox.getInstance(applicationContext)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_takpilot2go_flight)
        AppLog.v(REC_TAG, "onCreate")

        // No native ActionBar on this screen (see AppTheme.NoActionBar in the manifest) — the
        // custom toolbar below is the only top bar. That theme also makes the status bar
        // transparent/overlaid, so go immersive here too, or the phone's status bar icons
        // would sit on top of the toolbar instead of the reclaimed dead space.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        fpvView = findViewById(R.id.flightFpvView)
        noVideoCover = findViewById(R.id.flightNoVideoCover)
        // FpvTextureView owns its own decode lifecycle (via SurfaceTextureListener); we just
        // hide the "waiting for video" placeholder once the first frame arrives.
        fpvView.onFirstFrame = { runOnUiThread { noVideoCover.visibility = View.GONE } }
        val crosshair = findViewById<CrosshairView>(R.id.flightCrosshair)
        fpvView.onVideoRectChanged = { rect -> runOnUiThread { crosshair.setVideoRect(rect) } }

        fpvNotice = findViewById(R.id.fpvNotice)
        fpvOverlayText = findViewById(R.id.fpvOverlayText)
        toolbarBattery = findViewById(R.id.toolbarBattery)
        toolbarGps = findViewById(R.id.toolbarGps)
        toolbarGpsIcon = findViewById(R.id.toolbarGpsIcon)
        toolbarTakDot = findViewById(R.id.toolbarTakDot)
        toolbarSignal = findViewById(R.id.toolbarSignal)
        toolbarSignalText = findViewById(R.id.toolbarSignalText)

        mapView = findViewById(R.id.flightMapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapboxMap ->
            map = mapboxMap
            // Deliberately dead-simple, non-interactive mini-map (operator's spec, 2026-07-24):
            // no pan/zoom/rotate/tilt — north stays up (camera bearing is never set away from
            // its 0 default) and zoom stays pinned at MAP_ZOOM. The per-tick recenter in
            // updateHud() is the only thing that ever moves the camera.
            mapboxMap.uiSettings.setAllGesturesEnabled(false)
            // Zoom + center immediately, before any GPS fix — otherwise the map sits at its
            // default continent-scale zoom until the drone locks GPS (the per-tick recenter that
            // also sets zoom is gated behind hasFix). Centered on DEFAULT_CENTER as a sensible
            // starting view; pans to the drone once a fix arrives.
            mapboxMap.cameraPosition = CameraPosition.Builder()
                .target(DEFAULT_CENTER)
                .zoom(MAP_ZOOM)
                .build()
            mapboxMap.setStyle(Style.Builder().fromJson(MaplibreStyle.selectedStyleJson(this))) { style ->
                // Home->aircraft line: added first so it renders underneath both markers.
                // Hidden until both a home point and a live GPS fix exist (see updateHud()).
                val emptyLine = LineString.fromLngLats(
                    listOf(Point.fromLngLat(0.0, 0.0), Point.fromLngLat(0.0, 0.0))
                )
                val lSource = GeoJsonSource(HOME_LINE_SOURCE_ID, emptyLine)
                style.addSource(lSource)
                homeLineSource = lSource
                val lLayer = LineLayer(HOME_LINE_LAYER_ID, HOME_LINE_SOURCE_ID).withProperties(
                    lineColor("#F44336"),
                    lineWidth(2.5f),
                    visibility(Property.NONE),
                )
                style.addLayer(lLayer)
                homeLineLayer = lLayer

                style.addImage(AIRCRAFT_ICON_ID, decodeAircraftIcon())
                val source = GeoJsonSource(AIRCRAFT_SOURCE_ID, Point.fromLngLat(0.0, 0.0))
                style.addSource(source)
                aircraftSource = source
                val layer = SymbolLayer(AIRCRAFT_LAYER_ID, AIRCRAFT_SOURCE_ID).withProperties(
                    iconImage(AIRCRAFT_ICON_ID),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                )
                style.addLayer(layer)
                aircraftLayer = layer

                // Home-point marker: hidden until DroneTakBridge reports a home location.
                style.addImage(HOME_ICON_ID, decodeHomeIcon())
                val hSource = GeoJsonSource(HOME_SOURCE_ID, Point.fromLngLat(0.0, 0.0))
                style.addSource(hSource)
                homeSource = hSource
                val hLayer = SymbolLayer(HOME_LAYER_ID, HOME_SOURCE_ID).withProperties(
                    iconImage(HOME_ICON_ID),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    visibility(Property.NONE),
                )
                style.addLayer(hLayer)
                homeLayer = hLayer
            }
        }

        findViewById<ImageButton>(R.id.flightBackButton).setOnClickListener { finish() }

        recordToggle = findViewById(R.id.flightRecordButton)
        recordToggle.setOnClickListener { onRecordToggleTapped() }

        rthButton = findViewById(R.id.flightRthButton)
        rthButton.setOnClickListener { onRthTapped() }

        findViewById<ImageButton>(R.id.flightResyncButton).setOnClickListener {
            AppLog.v(REC_TAG, "tap: Video Re-Sync")
            fpvView.requestResync()
            Toast.makeText(this, "Re-syncing video…", Toast.LENGTH_SHORT).show()
        }

        liveToggle = findViewById(R.id.flightStreamButton)
        liveToggle.setOnClickListener { onLiveToggleTapped() }
        // The underlying push (DroneVideoStreamer) isn't implemented yet (Phase 5) — start()
        // always reports failure, so the toggle only flips to LIVE once that's real. Refreshed
        // whenever VideoStreamerHolder's state changes, from any trigger (this button, RC
        // hardware, etc.), not just our own taps.
        VideoStreamerHolder.onStateChanged = Runnable { liveToggle.setLive(VideoStreamerHolder.isRunning) }
        liveToggle.setLive(VideoStreamerHolder.isRunning)

        // Exposure control — the camera's exposure mode is forced to shutter-priority +
        // auto-ISO on connect (see ExposureController + DroneTakBridge); this slider biases it
        // brighter/darker (-2..+2 EV). Live ISO/shutter readout is filled in updateHud().
        exposureReadout = findViewById(R.id.exposureReadout)
        val evSlider = findViewById<EvSliderView>(R.id.evSlider)
        evSlider.steps = ExposureController.sliderMax
        evSlider.index = ExposureController.savedSliderIndex(this)
        evSlider.onIndexChanged = { idx, fromUser ->
            if (fromUser) {
                ExposureController.setEvAt(applicationContext,
                    DJISampleApplication.getAircraftInstance()?.camera, idx) {}
            }
        }
    }

    private fun onLiveToggleTapped() {
        if (VideoStreamerHolder.isActive) {
            VideoStreamerHolder.stop()
            Toast.makeText(this, "Video stream stopped", Toast.LENGTH_SHORT).show()
            return
        }
        // Guard on config before prompting for anything.
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        if ((p.getString("video_host", "") ?: "").isEmpty() ||
            (p.getString("video_streamid", "") ?: "").isEmpty()) {
            Toast.makeText(this, "Set up the video server in Pre-Flight Setup first", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = p.getString("video_profile", "standard") ?: "standard"
        if (profile == "original") {
            // Passthrough — no screen capture, no permission needed.
            VideoStreamerHolder.startFromPrefs(applicationContext) { _, msg ->
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            }
            return
        }
        // Transcode profile → screen-capture stream: request the one-time MediaProjection
        // permission. onActivityResult starts the foreground service, which starts the stream.
        AppLog.v(REC_TAG, "tap: LIVE (requesting screen capture)")
        val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        Toast.makeText(this, "Starting screen stream…", Toast.LENGTH_SHORT).show()
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                com.dji.sdk.sample.tak.ScreenCaptureService.start(this, resultCode, data)
            } else {
                Toast.makeText(this, "Screen capture permission denied — no stream started",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Tapping RTH while already going home cancels it (no confirmation needed — canceling is
     *  always safe); otherwise confirms before sending the aircraft home. */
    private fun onRthTapped() {
        val fc = DJISampleApplication.getAircraftInstance()?.flightController
        if (fc == null) {
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (TakBridgeHolder.hud()?.isGoingHome == true) {
            fc.cancelGoHome(toastResultCallback("RTH cancelled", "Cancel failed"))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Return to Home")
            .setMessage("Send the aircraft home now?")
            .setPositiveButton("Return Home") { _, _ ->
                fc.startGoHome(toastResultCallback("Returning home", "RTH failed"))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toastResultCallback(successMsg: String, failurePrefix: String) =
        CommonCallbacks.CompletionCallback<DJIError> { error ->
            runOnUiThread {
                val msg = if (error == null) successMsg else "$failurePrefix: ${error.description}"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

    /** Tapping while already recording stops it; otherwise switches the camera to video mode
     *  and starts recording — no confirmation needed, unlike RTH, since recording is easily
     *  reversible and not flight-safety-critical.
     *
     *  The Mini 2 (and other recent aircraft) reject the legacy `setMode(RECORD_VIDEO)` with
     *  "not supported by the current firmware version" — they use "flat camera mode" instead,
     *  so we switch via `setFlatMode(VIDEO_NORMAL)` when the camera reports it's supported,
     *  falling back to the legacy call for older aircraft. */
    private fun onRecordToggleTapped() {
        val aircraft = DJISampleApplication.getAircraftInstance()
        val camera = aircraft?.camera
        if (camera == null) {
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (TakBridgeHolder.hud()?.isRecording == true) {
            camera.stopRecordVideo(recordResultCallback("Recording stopped", "Stop failed", "stopRecordVideo"))
            return
        }
        val startAfterMode = CommonCallbacks.CompletionCallback<DJIError> { modeError ->
            AppLog.i(REC_TAG, "set video mode result: ${modeError?.description ?: "OK"}")
            if (modeError != null) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't switch to video mode: ${modeError.description}", Toast.LENGTH_SHORT).show()
                }
                return@CompletionCallback
            }
            camera.startRecordVideo(recordResultCallback("Recording started", "Start failed", "startRecordVideo"))
        }
        if (camera.isFlatCameraModeSupported) {
            camera.setFlatMode(SettingsDefinitions.FlatCameraMode.VIDEO_NORMAL, startAfterMode)
        } else {
            camera.setMode(SettingsDefinitions.CameraMode.RECORD_VIDEO, startAfterMode)
        }
    }

    private fun recordResultCallback(successMsg: String, failurePrefix: String, op: String) =
        CommonCallbacks.CompletionCallback<DJIError> { error ->
            AppLog.i(REC_TAG, "$op result: ${error?.description ?: "OK"}")
            runOnUiThread {
                val msg = if (error == null) successMsg else "$failurePrefix: ${error.description}"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

    /** Aircraft marker icon: a cyan heading arrow (rasterized from the vector), sized for the
     *  mini-map — small enough to point accurately, big enough to read. */
    private fun decodeAircraftIcon(): Bitmap {
        val sizePx = (AIRCRAFT_ICON_DP * resources.displayMetrics.density).toInt()
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_self_marker)!!
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    /** Home-point marker icon, small — stationary reference point, doesn't need to read as
     *  large as the aircraft. */
    private fun decodeHomeIcon(): Bitmap {
        val sizePx = (HOME_ICON_DP * resources.displayMetrics.density).toInt()
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_home_marker)!!
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    private fun updateHud() {
        val hud = TakBridgeHolder.hud()
        val takOk = TakManager.getInstance().isConnected

        toolbarBattery.setPercent(hud?.batteryPct)

        toolbarGps.text = if (hud != null && hud.hasFix) hud.satCount.toString() else "—"
        toolbarGpsIcon.setColorFilter(
            if (hud != null && hud.hasFix) 0xFF4CAF50.toInt() else 0xFFAAAAAA.toInt()
        )

        toolbarTakDot.setColorFilter(if (takOk) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())

        recordToggle.setRecording(hud?.isRecording == true)

        // Home point: independent of the aircraft's current GPS fix (the home location, once
        // set, stays valid even if the live fix drops momentarily) — so this isn't gated behind
        // hud.hasFix like the marker/camera-follow logic below.
        val homeSet = hud?.homeSet == true
        rthButton.setImageResource(if (homeSet) R.drawable.ic_rth_home_set else R.drawable.ic_rth)
        if (homeSet) {
            homeSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(hud!!.homeLon, hud.homeLat)))
            homeLayer?.setProperties(visibility(Property.VISIBLE))
        } else {
            homeLayer?.setProperties(visibility(Property.NONE))
        }
        if (homeSet && !lastHomeSet) {
            fpvNotice.text = "Home Point Set"
            fpvNotice.visibility = View.VISIBLE
            handler.removeCallbacks(hideNotice)
            handler.postDelayed(hideNotice, HOME_NOTICE_MS)
        }
        lastHomeSet = homeSet

        exposureReadout.text = "ISO ${hud?.liveIso ?: "—"}   ${hud?.liveShutter ?: "—"}"

        // SignalBarsView handles its own dot color + bar count from the raw %; the text just
        // shows the bucketed value alongside it.
        val signalPct = hud?.uplinkSignalPct
        toolbarSignal.setPercent(signalPct)
        toolbarSignalText.text = if (signalPct != null) "${bucketSignalPct(signalPct)}%" else "—%"
        toolbarSignalText.alpha = if (signalPct != null) 1.0f else 0.4f

        fpvOverlayText.text = buildString {
            append(currentCallsign)
            append('\n')
            if (hud != null && hud.hasFix) {
                append("%.4f, %.4f".format(hud.lat, hud.lon))
            } else {
                append("—, —")
            }
            append('\n')
            if (hud != null && hud.hasFix && hud.homeSet) {
                val dist = CameraSlantPoint.distanceMeters(hud.homeLat, hud.homeLon, hud.lat, hud.lon)
                val bearing = CameraSlantPoint.initialBearingDeg(hud.homeLat, hud.homeLon, hud.lat, hud.lon)
                append("%.0fm  %03.0f°T".format(dist, bearing))
            } else {
                append("— m  —°T")
            }
            append('\n')
            if (hud != null && hud.hasFix) {
                append("%.0f ft AGL".format(hud.alt * 3.28084))
            } else {
                append("— ft AGL")
            }
            append('\n')
            if (hud != null) {
                append("%.0f MPH".format(hud.speedMs * 2.23694))
            } else {
                append("— MPH")
            }
            append('\n')
            if (hud != null) {
                val elapsedMin = hud.flightTimeSec / 60
                // Rough estimate only — no drain-rate model, just battery% against the Mini
                // 2's nominal max flight time. Good enough for a ballpark, not a real RTH clock.
                val remainingMin = (NOMINAL_FULL_FLIGHT_SEC * hud.batteryPct / 100) / 60
                append("$elapsedMin min / ~$remainingMin min")
            } else {
                append("— min / ~— min")
            }
        }

        if (hud == null || !hud.hasFix) return

        aircraftSource?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(hud.lon, hud.lat)))
        aircraftLayer?.setProperties(iconRotate(hud.headingDeg.toFloat()))
        map?.cameraPosition = CameraPosition.Builder()
            .target(LatLng(hud.lat, hud.lon))
            .zoom(MAP_ZOOM)
            .build()

        // Home->aircraft line: the pilot's "which way back" reference on a map that otherwise
        // can't be panned to look around. Only meaningful once a home point exists.
        if (homeSet) {
            homeLineSource?.setGeoJson(
                LineString.fromLngLats(listOf(
                    Point.fromLngLat(hud.homeLon, hud.homeLat),
                    Point.fromLngLat(hud.lon, hud.lat),
                ))
            )
            homeLineLayer?.setProperties(visibility(Property.VISIBLE))
        } else {
            homeLineLayer?.setProperties(visibility(Property.NONE))
        }
    }

    /** Bucket raw signal % into coarse steps for display (operator's spec): 0-10% shows as
     *  0%, otherwise round to the nearest of 25/50/75/100%. */
    private fun bucketSignalPct(pct: Int): Int {
        if (pct <= 10) return 0
        val buckets = intArrayOf(25, 50, 75, 100)
        return buckets.minByOrNull { kotlin.math.abs(it - pct) } ?: 0
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        // FpvTextureView (re)registers the video feed itself when its SurfaceTexture becomes
        // available, so there's no register/keyframe-reset dance to do here.

        // Same uid/callsign scheme as TakConnectActivity (shared "takpilot2_tak" prefs) so a
        // drone started here shows up under the same identity once/if TAK gets connected.
        val prefs = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        var uid = prefs.getString("uid", "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("uid", uid).apply()
        }
        currentCallsign = prefs.getString("callsign", "TAKPilot2 Go-Mini2") ?: "TAKPilot2 Go-Mini2"
        TakBridgeHolder.start("$uid-DRONE", currentCallsign)
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Immersive-sticky flags get cleared by system dialogs/notification-shade swipes;
        // re-apply whenever we regain focus so the status bar doesn't creep back over the
        // toolbar.
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.v(REC_TAG, "onResume")
        mapView.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        AppLog.v(REC_TAG, "onPause")
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(hideNotice)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        AppLog.v(REC_TAG, "onStop")
        TakBridgeHolder.stop()
        lastHomeSet = false
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        VideoStreamerHolder.onStateChanged = null
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        private const val REC_TAG = "TP2Record"
        private const val REQUEST_MEDIA_PROJECTION = 3001
        private const val HUD_INTERVAL_MS = 500L
        private const val AIRCRAFT_ICON_ID = "aircraft-icon"
        private const val AIRCRAFT_SOURCE_ID = "aircraft-source"
        private const val AIRCRAFT_LAYER_ID = "aircraft-layer"
        private const val AIRCRAFT_ICON_DP = 28
        private const val HOME_ICON_ID = "home-icon"
        private const val HOME_SOURCE_ID = "home-source"
        private const val HOME_LAYER_ID = "home-layer"
        private const val HOME_LINE_SOURCE_ID = "home-line-source"
        private const val HOME_LINE_LAYER_ID = "home-line-layer"
        private const val HOME_ICON_DP = 18
        private const val HOME_NOTICE_MS = 5000L

        // Mini-map zoom, street level — every hud tick rebuilds the CameraPosition, and an
        // unspecified zoom() reset it to the map's default (continent-scale) on each update,
        // which is why it looked "stuck" zoomed out rather than just starting there.
        private const val MAP_ZOOM = 15.0

        // Where the mini-map centers before the drone has a GPS fix (operator's home area).
        private val DEFAULT_CENTER = LatLng(61.2182, -149.8963)

        // DJI Mini 2 published max flight time, used only as a rough basis for the
        // "remaining" estimate on the HUD — not a real per-flight drain-rate model.
        private const val NOMINAL_FULL_FLIGHT_SEC = 31 * 60
    }
}

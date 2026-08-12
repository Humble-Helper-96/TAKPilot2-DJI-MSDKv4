package com.dji.sdk.sample.takpilot2

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R
import com.dji.sdk.sample.internal.controller.DJISampleApplication
import com.dji.sdk.sample.tak.ArSettings
import com.dji.sdk.sample.tak.CameraSlantPoint
import com.dji.sdk.sample.tak.DjiObstacleState
import com.dji.sdk.sample.tak.DjiSdkBridge
import android.content.Intent
import com.dji.sdk.sample.tak.ExposureController
import com.dji.sdk.sample.tak.FlightWarnings
import com.dji.sdk.sample.tak.OperatorLocation
import com.dji.sdk.sample.tak.TakBridgeHolder
import com.dji.sdk.sample.tak.TakDropMarkers
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
import dji.sdk.camera.Camera
import java.util.UUID

/**
 * TAKPilot2 Go flight screen — live FPV video (full-bleed) + a live map overlay, phone-sized.
 * Owns the [TakBridgeHolder] telemetry-to-CoT bridge's lifecycle (Phase 4): starts it on
 * entry, stops it on exit. TakManager.sendDronePLI() no-ops internally when TAK isn't
 * connected, so this runs safely whether or not TAK Setup has been done yet.
 *
 * Also drives the on-screen telemetry HUD + own-aircraft map marker (Phase 4 addendum) — a
 * 500ms poll of [TakBridgeHolder.hud], same pattern as the Autel sibling app's FlightActivity.
 * Inbound TAK contacts/markers are drawn by [com.dji.sdk.sample.tak.TakMapMarkers], which owns
 * its own source/layer on this screen's style (Phase 6A); dropped pins and the AR overlay
 * (6B–6D) are still to come.
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
    private lateinit var zoomButton: TextView
    private lateinit var fpvFaaCeiling: TextView
    private lateinit var fpvRthAltitude: TextView
    private lateinit var fpvClock: TextView
    private lateinit var fpvGimbalPitch: TextView
    private lateinit var crosshairView: CrosshairView
    private lateinit var arOverlay: ArOverlayView
    private lateinit var arButton: TextView

    // FAA UASFM ceiling, cached per grid cell. The lookup itself is a hash hit, but re-deriving
    // and re-formatting it on every 500ms tick is pointless when the answer only changes when
    // the aircraft crosses a 1/120-degree cell boundary (~1/2 mile), so we recompute on the
    // crossing instead. MIN_VALUE means "nothing cached yet".
    private var lastFaaGridRow = Int.MIN_VALUE
    private var lastFaaGridCol = Int.MIN_VALUE
    private var cachedFaaCeilingFt: Int? = null
    private var cachedFaaWithinDownloadedArea = false
    private var currentCallsign: String = ""
    private var zoomedIn = false

    private var map: MapboxMap? = null
    private var aircraftSource: GeoJsonSource? = null
    private var aircraftLayer: SymbolLayer? = null
    private var homeSource: GeoJsonSource? = null
    private var homeLayer: SymbolLayer? = null
    private var homeLineSource: GeoJsonSource? = null
    private var homeLineLayer: LineLayer? = null
    private lateinit var fpvNotice: TextView
    private lateinit var flightDiagnostics: TextView
    private lateinit var obstacles: ObstacleEdgeView
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

        // OOM-restart guard. If the app is killed for memory in flight, Android restores the task
        // and recreates THIS activity directly — into a cold process where the DJI SDK was never
        // registered and no product connection was started (that only happens in Home). Coming up
        // here would show a dead aircraft link and a frozen HUD that looks live, which is worse
        // than an obvious failure. The tell: we were restored (savedInstanceState != null) yet
        // this process never passed through Home. Bounce there, which re-registers the SDK and
        // lets the pilot re-enter the flight screen deliberately.
        if (savedInstanceState != null && !TAKPilot2GoHomeActivity.visitedThisProcess) {
            AppLog.w(TAG, "restored into a cold process (OOM restart) — routing to Home")
            startActivity(
                Intent(this, TAKPilot2GoHomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
            return
        }

        setContentView(R.layout.activity_takpilot2go_flight)
        AppLog.v(TAG, "onCreate")

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
        crosshairView = crosshair
        // Quick drop: the reticle itself is the control. Tap places, long-press re-aims.
        crosshair.onReticleTap = { onQuickDropTapped() }
        crosshair.onReticleLongPress = { onQuickDropLongPressed() }
        arOverlay = findViewById(R.id.flightArOverlay)
        // Both consume the same video rectangle: the AR projection has to agree with the
        // crosshair about where the centre of the image is, or a marker dropped at the
        // crosshair won't render under it — which is the whole self-test for this feature.
        fpvView.onVideoRectChanged = { rect ->
            runOnUiThread {
                crosshair.setVideoRect(rect)
                arOverlay.setVideoRect(rect)
                obstacles.setVideoRect(rect)
            }
        }
        // Tell the AR overlay how much of the video our own chrome covers, so an off-frame edge
        // arrow can't be parked underneath the toolbar or the HUD column where it's invisible —
        // the exact case a pilot needs most (aircraft directly overhead). Measured from the real
        // views after layout rather than hardcoded dp, so a toolbar/HUD change can't silently
        // break it. Re-read on every layout pass: rotation, or the h440dp map-size override,
        // both change these.
        val toolbarView = findViewById<View>(R.id.flightToolbar)
        val hudColumn = findViewById<View>(R.id.flightHudColumn)
        toolbarView.viewTreeObserver.addOnGlobalLayoutListener {
            arOverlay.setChromeInsets(
                top = toolbarView.height.toFloat(),
                right = hudColumn.width.toFloat(),
            )
        }

        fpvNotice = findViewById(R.id.fpvNotice)
        flightDiagnostics = findViewById(R.id.flightDiagnostics)
        obstacles = findViewById(R.id.flightObstacles)
        obstacles.update(DjiObstacleState.faces)
        DjiObstacleState.onChanged = { runOnUiThread { obstacles.update(DjiObstacleState.faces) } }
        // Render whatever is ALREADY standing before subscribing — the callback is change-only,
        // so entering the flight screen with a fault already active would otherwise show nothing
        // until the fault happened to change.
        FlightWarnings.onDiagnostics(DjiSdkBridge.diagnostics)
        renderWarning()
        DjiSdkBridge.onDiagnostics = { items ->
            FlightWarnings.onDiagnostics(items)
            runOnUiThread { renderWarning() }
        }
        fpvOverlayText = findViewById(R.id.fpvOverlayText)
        fpvFaaCeiling = findViewById(R.id.fpvFaaCeiling)
        fpvRthAltitude = findViewById(R.id.fpvRthAltitude)
        fpvClock = findViewById(R.id.fpvClock)
        fpvGimbalPitch = findViewById(R.id.fpvGimbalPitch)
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
            // 6C: tapping an inbound contact locally hides it (stays on the server). Confirmed
            // independent of setAllGesturesEnabled(false) above — the locked mini-map's pan/
            // zoom/rotate stay off, only this explicit click hook is added.
            mapboxMap.addOnMapClickListener { latLng ->
                val px = mapboxMap.projection.toScreenLocation(latLng)
                val hit = mapboxMap.queryRenderedFeatures(px, com.dji.sdk.sample.tak.TakMapMarkers.LAYER_ID)
                    .firstOrNull()
                val uid = hit?.getStringProperty(com.dji.sdk.sample.tak.TakMapMarkers.PROP_UID)
                if (uid != null) onInboundMarkerTapped(uid)
                uid != null
            }
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

                // Inbound TAK contacts/markers. Added here, before the aircraft and home
                // layers, so other operators' symbols always render UNDER our own aircraft
                // arrow and home pin — MapLibre draws layers in insertion order.
                com.dji.sdk.sample.tak.TakMapMarkers.onMapReady(style)

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

        findViewById<ImageButton>(R.id.flightBackButton).setOnClickListener {
            AppLog.v(TAG, "tap: menu/back (leaving flight screen)")
            finish()
        }

        recordToggle = findViewById(R.id.flightRecordButton)
        recordToggle.setOnClickListener { onRecordToggleTapped() }

        rthButton = findViewById(R.id.flightRthButton)
        rthButton.setOnClickListener { onRthTapped() }
        rthButton.setOnLongClickListener { onRthLongPressed(); true }

        findViewById<View>(R.id.toolbarTakButton).setOnClickListener {
            AppLog.v(TAG, "tap: TAK connection toggle")
            com.dji.sdk.sample.tak.TakAutoConnect.toggle(applicationContext) { _, msg ->
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            }
        }

        findViewById<ImageButton>(R.id.flightResyncButton).setOnClickListener {
            AppLog.v(TAG, "tap: Video Re-Sync")
            fpvView.requestResync()
            Toast.makeText(this, "Re-syncing video…", Toast.LENGTH_SHORT).show()
        }

        zoomButton = findViewById(R.id.flightZoomButton)
        zoomButton.setOnClickListener { onZoomTapped() }

        // Load the calibrated FOV before the overlay draws anything with it.
        com.dji.sdk.sample.tak.ArSettings.loadFov(this)

        arButton = findViewById(R.id.flightArButton)
        arButton.setOnClickListener { onArToggleTapped() }
        // Same long-press idiom as RTH (reset home) and drop-pin (markers list).
        arButton.setOnLongClickListener { onArOptionsTapped(); true }
        refreshArButton()

        findViewById<ImageButton>(R.id.flightDropPinButton).setOnClickListener { onDropPinTapped() }
        // 6C: long-press the drop button to manage already-dropped pins (move/rename/retype/
        // re-send/delete) — no map interaction needed, consistent with the locked mini-map.
        findViewById<ImageButton>(R.id.flightDropPinButton).setOnLongClickListener {
            onMarkersListTapped(); true
        }
        // TakDropMarkers has no Context of its own for user-facing feedback; this screen owns
        // the toasts. Cleared in onDestroy so a dead Activity is never toasted through.
        TakDropMarkers.ui = object : TakDropMarkers.Ui {
            override fun toast(msg: String) {
                runOnUiThread { Toast.makeText(this@TAKPilot2GoFlightActivity, msg, Toast.LENGTH_SHORT).show() }
            }
        }

        findViewById<ImageButton>(R.id.flightShootPhotoButton).setOnClickListener { onShootPhotoTapped() }

        liveToggle = findViewById(R.id.flightStreamButton)
        liveToggle.setOnClickListener { onLiveToggleTapped() }
        // Refreshed whenever VideoStreamerHolder's state changes, from any trigger (this
        // button, a network-drop auto-reconnect, or the reconnect window giving up), not just
        // our own taps. RECONNECTING (amber, blinking) tells the pilot the app is retrying a
        // dropped link on its own — don't tap LIVE thinking it's off; tapping it now cancels
        // the retry instead of starting fresh.
        var lastLiveState: LiveToggleView.State? = null
        val refreshLiveToggle = Runnable {
            val state = when {
                VideoStreamerHolder.isRunning -> LiveToggleView.State.LIVE
                VideoStreamerHolder.isReconnecting -> LiveToggleView.State.RECONNECTING
                else -> LiveToggleView.State.OFF
            }
            // Edge-triggered: the holder can notify repeatedly for the same state (every
            // reconnect attempt), and logging each one would spam the file during a long
            // network outage — only the actual transitions are interesting.
            if (state != lastLiveState) {
                AppLog.i(TAG, "LIVE pill state -> $state")
                lastLiveState = state
            }
            liveToggle.setState(state)
        }
        VideoStreamerHolder.onStateChanged = refreshLiveToggle
        refreshLiveToggle.run()

        // Exposure control — the camera's exposure mode is forced to shutter-priority +
        // auto-ISO on connect (see ExposureController + DroneTakBridge); this slider biases it
        // brighter/darker (-2..+2 EV). Live ISO/shutter readout is filled in updateHud().
        exposureReadout = findViewById(R.id.exposureReadout)
        val evSlider = findViewById<EvSliderView>(R.id.evSlider)
        evSlider.steps = ExposureController.sliderMax
        evSlider.index = ExposureController.savedSliderIndex(this)
        evSlider.onIndexChanged = { idx, fromUser ->
            if (fromUser) {
                // v() not i(): a slider drag fires this on every step, so keep it in the
                // verbose tier where it won't flood a Standard-level capture.
                AppLog.v(TAG, "EV slider -> ${ExposureController.labelAt(idx)} (index $idx)")
                ExposureController.setEvAt(applicationContext,
                    DJISampleApplication.getAircraftInstance()?.camera, idx) {}
            }
        }
    }

    private fun onLiveToggleTapped() {
        if (VideoStreamerHolder.isActive) {
            AppLog.i(TAG, "tap: LIVE — stopping active stream " +
                "(running=${VideoStreamerHolder.isRunning}, reconnecting=${VideoStreamerHolder.isReconnecting})")
            VideoStreamerHolder.stop()
            Toast.makeText(this, "Video stream stopped", Toast.LENGTH_SHORT).show()
            return
        }
        // Guard on config before prompting for anything.
        val p = getSharedPreferences("takpilot2_tak", MODE_PRIVATE)
        if ((p.getString("video_host", "") ?: "").isEmpty() ||
            (p.getString("video_streamid", "") ?: "").isEmpty()) {
            AppLog.w(TAG, "tap: LIVE ignored — video server not configured in Pre-Flight Setup")
            Toast.makeText(this, "Set up the video server in Pre-Flight Setup first", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = p.getString("video_profile", "standard") ?: "standard"
        if (profile == "original") {
            // Passthrough — no screen capture, no permission needed.
            AppLog.i(TAG, "tap: LIVE — starting passthrough stream (profile=original)")
            VideoStreamerHolder.startFromPrefs(applicationContext) { _, msg ->
                runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
            }
            return
        }
        // Transcode profile → screen-capture stream: request the one-time MediaProjection
        // permission. onActivityResult starts the foreground service, which starts the stream.
        AppLog.i(TAG, "tap: LIVE — requesting screen-capture permission (profile=$profile)")
        val mpm = getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        Toast.makeText(this, "Starting screen stream…", Toast.LENGTH_SHORT).show()
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                AppLog.i(TAG, "screen-capture permission GRANTED — starting ScreenCaptureService")
                com.dji.sdk.sample.tak.ScreenCaptureService.start(this, resultCode, data)
            } else {
                AppLog.w(TAG, "screen-capture permission DENIED (resultCode=$resultCode) — no stream started")
                Toast.makeText(this, "Screen capture permission denied — no stream started",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Tapping RTH while already going home cancels it (no confirmation needed — canceling is
     *  always safe); otherwise confirms before sending the aircraft home. */
    private fun onRthTapped() {
        AppLog.v(TAG, "tap: RTH")
        val fc = DJISampleApplication.getAircraftInstance()?.flightController
        if (fc == null) {
            AppLog.w(TAG, "RTH ignored — aircraft not connected")
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (TakBridgeHolder.hud()?.isGoingHome == true) {
            AppLog.i(TAG, "RTH: already going home — sending cancelGoHome")
            fc.cancelGoHome(toastResultCallback("RTH cancelled", "Cancel failed"))
            return
        }
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Return to Home")
            .setMessage("Send the aircraft home now?")
            .setPositiveButton("Return Home") { _, _ ->
                AppLog.i(TAG, "RTH confirmed — sending startGoHome")
                fc.startGoHome(toastResultCallback("Returning home", "RTH failed"))
            }
            .setNegativeButton("Cancel") { _, _ -> AppLog.i(TAG, "RTH cancelled at confirm dialog") }
            .show()
    }

    /** Long-press RTH: reset the aircraft's home point to the pilot's current position (the
     *  phone's GPS — RC-N1 has no GPS of its own, so the phone standing in for "the
     *  controller's location" is the only sensible reading of that). Useful when the pilot
     *  has walked/driven somewhere else since the aircraft auto-set home at takeoff.
     *  Confirmed first — this changes where RTH sends the aircraft, so a stale/bad GPS fix
     *  here is a real safety concern, unlike RTH-cancel which is always safe. */
    private fun onRthLongPressed() {
        AppLog.v(TAG, "long-press: RTH (reset home point)")
        val fc = DJISampleApplication.getAircraftInstance()?.flightController
        if (fc == null) {
            AppLog.w(TAG, "reset home point ignored — aircraft not connected")
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        // Via OperatorLocation, not getLastKnownLocation. Two reasons, and the second is the
        // safety one: that method reads a cache nothing fills unless an app has requested
        // updates, so on a flying phone it returned null for ever; and the cache has NO expiry,
        // so it can hand back a fix from days ago at wherever the phone last saw sky.
        // OperatorLocation issues the real request and refuses a stale seed — which matters
        // here more than anywhere, because this value decides where RTH flies the aircraft.
        OperatorLocation.start(this)
        val loc = OperatorLocation.latest
        if (loc == null) {
            AppLog.w(TAG, "reset home point aborted — no phone GPS fix from GPS/NETWORK providers")
            Toast.makeText(this, "No phone GPS fix available", Toast.LENGTH_SHORT).show()
            return
        }
        AppLog.i(TAG, "reset home point: phone fix ${"%.6f, %.6f".format(loc.latitude, loc.longitude)} " +
            "(provider=${loc.provider}, age=${(System.currentTimeMillis() - loc.time) / 1000}s, acc=${loc.accuracy}m)")
        // Destructive variant (red accent), matching the marker Delete / Clear All confirms:
        // this doesn't delete anything, but it changes where RTH will fly the aircraft, and a
        // stale phone fix here is a genuine safety problem — the same "read this before you
        // tap" signal the rest of the app's red confirms carry.
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Reset Home Point")
            .setMessage("Set the aircraft's home point to your current location " +
                "(%.6f, %.6f)? This changes where Return to Home will send it.".format(loc.latitude, loc.longitude))
            .setPositiveButton("Set Home Here") { _, _ ->
                AppLog.i(TAG, "reset home point confirmed — sending setHomeLocation")
                fc.setHomeLocation(
                    dji.common.model.LocationCoordinate2D(loc.latitude, loc.longitude),
                    toastResultCallback("Home point updated", "Set home failed"),
                )
            }
            .setNegativeButton("Cancel") { _, _ -> AppLog.i(TAG, "reset home point cancelled at confirm dialog") }
            .show()
    }

    private fun toastResultCallback(successMsg: String, failurePrefix: String) =
        CommonCallbacks.CompletionCallback<DJIError> { error ->
            AppLog.i(TAG, "$successMsg -> ${error?.description ?: "OK"}")
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
        AppLog.v(REC_TAG, "tap: REC")
        val aircraft = DJISampleApplication.getAircraftInstance()
        val camera = aircraft?.camera
        if (camera == null) {
            AppLog.w(REC_TAG, "record ignored — aircraft not connected")
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (TakBridgeHolder.hud()?.isRecording == true) {
            AppLog.i(REC_TAG, "already recording — sending stopRecordVideo")
            camera.stopRecordVideo(recordResultCallback("Recording stopped", "Stop failed", "stopRecordVideo"))
            return
        }
        AppLog.i(REC_TAG, "starting recording (flatModeSupported=${camera.isFlatCameraModeSupported})")
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

    /** Toggles the camera's digital zoom between 1x and 2x — the Mini 2 (and most MSDK
     *  aircraft without a zoom lens) only support pure digital crop-zoom, not optical/hybrid,
     *  so 1x/2x is a simple, broadly-compatible pair rather than trying to expose the
     *  aircraft's full (model-dependent) zoom range. Affects the actual encoded video feed,
     *  so it changes both on-screen FPV and whatever's going out over the Phase 5 RTSP push. */
    /**
     * FAA UASFM ceiling readout. Hidden entirely when the pilot hasn't downloaded any data, so
     * the feature costs nothing on screen if unused.
     *
     * **Advisory only.** UASFM shows what the FAA is likely to authorise, not what's authorised,
     * and the downloaded copy ages. Nothing here touches the aircraft's altitude limit.
     *
     * The ceiling is compared against [agl], which is terrain-corrected via DTED when coverage
     * allows (see `TerrainAgl`) — a UASFM ceiling is height above the ground under the aircraft,
     * so comparing it to a takeoff-relative altitude would misjudge the moment the aircraft
     * leaves the elevation it launched from. Without DTED coverage the comparison falls back to
     * the uncorrected figure, and the readout marks itself `~` so the pilot can see the warning
     * is only as good as flat ground.
     */
    private var lastResourceLogAt = 0L

    /**
     * One resource line every [RESOURCE_LOG_INTERVAL_MS] while the flight screen is up.
     *
     * On the HUD tick rather than a timer of its own, so it stops when the screen does. The
     * interval is long because this is a trend, not an instrument: the thing being watched is
     * whether the contact count and the memory figures CLIMB across a flight, which is what
     * preceded the OOM kills on the sibling. A line every half second would bury that in noise
     * and rotate the log file out from under the evidence.
     */
    private fun logResourcesPeriodically() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastResourceLogAt < RESOURCE_LOG_INTERVAL_MS) return
        lastResourceLogAt = now
        runCatching {
            AppLog.i(RESOURCE_TAG,
                com.dji.sdk.sample.tak.ResourceMonitor.formattedLine(applicationContext))
        }
    }

    /** The one place the ceiling readout says "I do not know", so every unknown looks the same
     *  and none of them look like an answer. */
    private fun showFaaUnknown(text: String) {
        fpvFaaCeiling.visibility = View.VISIBLE
        fpvFaaCeiling.text = text
        fpvFaaCeiling.setTextColor(
            ContextCompat.getColor(applicationContext, R.color.tp_state_unknown))
    }

    private fun updateFaaCeiling(
        hud: com.dji.sdk.sample.tak.DroneTakBridge.Hud?,
        agl: com.dji.sdk.sample.tak.TerrainAgl.Reading,
    ) {
        // NEVER HIDDEN. This used to disappear entirely when no ceilings had been downloaded,
        // and an absent readout reads as "nothing to worry about" rather than "I do not know" —
        // the pilot cannot tell a 400 ft answer they have not been given from one that did not
        // need giving. Unknown is its own state, in its own colour. (Standing rule, CLAUDE.md.)
        if (!com.dji.sdk.sample.tak.UasfmIndex.hasCoverage(this)) {
            showFaaUnknown("FAA — no data downloaded")
            return
        }
        if (hud == null || !hud.hasFix) {
            showFaaUnknown("FAA — no fix")
            return
        }

        val row = com.dji.sdk.sample.tak.UasfmIndex.gridRowFor(hud.lat)
        val col = com.dji.sdk.sample.tak.UasfmIndex.gridColFor(hud.lon)
        if (row != lastFaaGridRow || col != lastFaaGridCol) {
            lastFaaGridRow = row
            lastFaaGridCol = col
            cachedFaaCeilingFt = com.dji.sdk.sample.tak.UasfmIndex.ceilingFtAt(this, hud.lat, hud.lon)
            cachedFaaWithinDownloadedArea =
                com.dji.sdk.sample.tak.UasfmIndex.isWithinDownloadedArea(this, hud.lat, hud.lon)
            AppLog.v(TAG, "FAA cell ($row,$col): ceiling=${cachedFaaCeilingFt ?: "none"} " +
                "withinDownloaded=$cachedFaaWithinDownloadedArea")
        }

        val aglFt = Units.metersToFeet(agl.meters)
        // Marks a ceiling judged against an uncorrected altitude — the comparison is only valid
        // over ground level with the takeoff point, and the pilot should know which they've got.
        val approx = if (agl.terrainCorrected) "" else "~"
        val ceiling = cachedFaaCeilingFt
        fpvFaaCeiling.visibility = View.VISIBLE
        when {
            // "AGL" is spelled out because the readout directly above this now shows MSL, and a
            // bare "FAA 200 ft" next to a "413 ft MSL" invites reading the ceiling as an MSL
            // figure. UASFM ceilings are always height above ground.
            ceiling != null -> {
                fpvFaaCeiling.text = "FAA $ceiling ft AGL$approx"
                fpvFaaCeiling.setTextColor(
                    if (aglFt > ceiling) ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog)
                    else android.graphics.Color.WHITE
                )
            }
            // Inside what was downloaded but in no cell: the FAA publishes no facility map
            // here, which means uncontrolled airspace and the plain Part 107 ceiling. Shown
            // grey and labelled so it never reads as "the facility map says 400".
            cachedFaaWithinDownloadedArea -> {
                val part107 = com.dji.sdk.sample.tak.UasfmIndex.PART_107_DEFAULT_CEILING_FT
                fpvFaaCeiling.text = "Class G · $part107 ft AGL$approx"
                fpvFaaCeiling.setTextColor(
                    if (aglFt > part107) ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog)
                    else ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)
                )
            }
            // Outside the downloaded box entirely — we genuinely don't know. Amber, because
            // silently implying 400 ft here would be a guess dressed up as information.
            else -> showFaaUnknown("FAA — no data here")
        }
    }

    private fun onZoomTapped() {
        AppLog.v(TAG, "tap: zoom (currently ${if (zoomedIn) "2x" else "1x"})")
        val camera = DJISampleApplication.getAircraftInstance()?.camera
        if (camera == null) {
            AppLog.w(TAG, "zoom ignored — aircraft not connected")
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        if (!camera.isDigitalZoomSupported) {
            AppLog.w(TAG, "zoom unsupported on this camera (isDigitalZoomSupported=false)")
            Toast.makeText(this, "This camera doesn't support digital zoom", Toast.LENGTH_SHORT).show()
            return
        }
        val targetZoomedIn = !zoomedIn
        val targetFactor = if (targetZoomedIn) 2.0f else 1.0f
        camera.setDigitalZoomFactor(targetFactor) { error ->
            AppLog.i(TAG, "setDigitalZoomFactor($targetFactor): ${error?.description ?: "OK"}")
            runOnUiThread {
                if (error != null) {
                    Toast.makeText(this, "Zoom failed: ${error.description}", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                zoomedIn = targetZoomedIn
                zoomButton.text = if (zoomedIn) "2X" else "1X"
                // Zoom crops the camera's angular width, so both the FOV cone published to TAK
                // and the AR projection have to narrow with it — without this every AR marker
                // sits at roughly half its correct offset from centre at 2x.
                TakBridgeHolder.setZoomFactor(targetFactor.toDouble())
            }
        }
    }

    /** Shutter button: takes a single still photo, saved to the aircraft's SD card (not the
     *  phone) — same storage target as video recording. First cut of "quickpic" (a later phase
     *  will drop a TAK marker with the image attached); for now this just captures the still.
     *  Switches to PHOTO_SINGLE flat mode to shoot, then restores VIDEO_NORMAL afterward so the
     *  live FPV feed (this screen's primary job) isn't left in photo mode.
     *
     *  Field-found 2026-07-24: a bare `setFlatMode(VIDEO_NORMAL)` after the shoot left the feed
     *  dark and stuck (~ISO 800 · 1/640, EV slider dead) — the PHOTO_SINGLE round-trip resets
     *  the camera's exposure mode off PROGRAM, and nothing was re-forcing it back. Fix: restore
     *  through [ExposureController.applyDefaults], the same call the aircraft's initial connect
     *  uses — it does the VIDEO_NORMAL switch itself AND re-applies PROGRAM + the biased EV, so
     *  a photo can no longer leave the feed in a different exposure state than before it. */
    /**
     * Drop a TAK marker at whatever the camera is pointed at.
     *
     * The mini-map is locked (no pan/zoom by operator spec), so there is no tap-the-map
     * placement — [TakBridgeHolder.lookPoint] is the cursor, giving the DTED-terrain-corrected
     * ground intersection of the camera's line of sight. If that's unavailable (no GPS fix or
     * no gimbal attitude yet) the drop is refused outright: placing a marker at a plausible-
     * looking but wrong position is worse for the shared picture than not placing one.
     */
    /** Restyles a platform AlertDialog neutral button (Reset Numbering / Clear All Markers) as
     *  a compact red button: same red-fill/outline as the rest of the marker-dropper UI, but
     *  at roughly half the system default's height — the system button style's built-in
     *  min-height + vertical padding is sized for a full-width Material button, not a small
     *  in-line action, so both are stripped/shrunk here while leaving the font size untouched. */
    private fun styleRedButton(button: android.widget.Button) {
        button.setTextColor(android.graphics.Color.WHITE)
        button.setBackgroundResource(R.drawable.bg_button_red)
        button.setAllCaps(false)
        button.minHeight = 0
        button.minimumHeight = 0
        val vPad = (4 * resources.displayMetrics.density).toInt()
        button.setPadding(button.paddingLeft, vPad, button.paddingRight, vPad)
    }

    /**
     * AR overlay on/off. Off by default every time the flight screen opens — it draws over the
     * video, so it should be something the pilot switches on deliberately rather than something
     * they inherit from a previous session and have to notice.
     */
    /**
     * Gimbal pitch readout + the crosshair's accuracy tint.
     *
     * Both are driven from the same value so the number and the reticle can't disagree. The
     * thresholds mark how much a marker drop can be trusted: ground error scales as
     * 1/sin^2(pitch), so steeper is dramatically better, and nothing else on screen tells the
     * pilot that. Two threshold pairs — WITH DTED coverage at the aircraft's current position
     * and WITHOUT — since the no-DTED flat-ground assumption stacks its own error on top of the
     * same geometric term; field-calibrated for both, see [CrosshairView]'s constants.
     */
    private fun updateGimbalPitch(hud: com.dji.sdk.sample.tak.DroneTakBridge.Hud?) {
        val pitch = hud?.gimbalPitch
        // Whether a marker dropped RIGHT NOW would get CameraSlantPoint's terrain-corrected
        // solve — the same question CameraSlantPoint.compute itself asks (DTED coverage at the
        // aircraft's OWN current position, not just whether any DTED is loaded anywhere).
        val dtedAvailable = hud != null &&
            com.dji.sdk.sample.tak.DtedIndex.elevationAt(this, hud.lat, hud.lon) != null
        crosshairView.setGimbalPitch(pitch, dtedAvailable)
        if (pitch == null) {
            fpvGimbalPitch.text = "GIMBAL —"
            fpvGimbalPitch.setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            return
        }
        // Sign dropped in favour of an explicit DOWN/UP word: "-20" reads as a negative number
        // rather than as a look angle, and down is the only direction that matters for drops.
        val label = when {
            pitch <= -1.0 -> "GIMBAL %.0f° DOWN".format(-pitch)
            pitch >= 1.0 -> "GIMBAL %.0f° UP".format(pitch)
            else -> "GIMBAL LEVEL"
        }
        fpvGimbalPitch.text = label
        // Shared classifier, not a second copy of the thresholds — these two displays drifting
        // apart would be worse than having only one of them.
        fpvGimbalPitch.setTextColor(CrosshairView.accuracyColorFor(this, pitch, dtedAvailable))
    }

    private fun onArToggleTapped() {
        if (arOverlay.isRunning) arOverlay.stop() else arOverlay.start()
        AppLog.v(TAG, "tap: AR overlay -> ${if (arOverlay.isRunning) "ON" else "OFF"}")
        refreshArButton()
    }

    /**
     * AR options — what the overlay may draw, and how far out it draws air traffic.
     *
     * One dialog showing every switch at once rather than a sequence of prompts: these are
     * independent settings the pilot flips while looking at a picture that is already too busy,
     * so they need the current state of all of them in view. Everything applies LIVE — the
     * overlay reads [ArSettings] every frame — so an adjustment clears or repopulates the video
     * immediately instead of on next entry, which is what makes decluttering a usable in-flight
     * action rather than a setup step.
     *
     * Uses a custom view instead of `setMultiChoiceItems`: the stock two-line list item was the
     * one menu in the app that didn't match the rest of it, and it had nowhere to put a range
     * control.
     */
    private fun onArOptionsTapped() {
        AppLog.v(TAG, "long-press: AR options")
        val view = layoutInflater.inflate(R.layout.dialog_ar_options, null)

        // Rows built from the enum, not written out in XML — a category added later can't be
        // silently missing from the menu that controls it.
        val container = view.findViewById<LinearLayout>(R.id.arCategoryContainer)
        for (category in ArSettings.Category.values()) {
            val row = layoutInflater.inflate(R.layout.row_ar_category, container, false)
                as android.widget.CheckBox
            row.text = category.label
            row.isChecked = ArSettings.isEnabled(this, category)
            row.setOnCheckedChangeListener { _, isChecked ->
                ArSettings.setEnabled(this, category, isChecked)
            }
            container.addView(row)
        }

        val group = view.findViewById<android.widget.RadioGroup>(R.id.arRangeGroup)
        val rangeIds = mapOf(
            ArSettings.AirRange.MI_2_5 to R.id.arRange25,
            ArSettings.AirRange.MI_5 to R.id.arRange5,
            ArSettings.AirRange.MI_15 to R.id.arRange15,
        )
        group.check(rangeIds.getValue(ArSettings.airRange(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            rangeIds.entries.firstOrNull { it.value == checkedId }?.let {
                ArSettings.setAirRange(this, it.key)
            }
        }

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("AR Overlay")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Calibrate FOV…") { _, _ -> onArCalibrateTapped() }
            .show()
    }

    /**
     * AR field-of-view calibration (6D-D).
     *
     * The base FOV is derived from published specs, not measured, and the projection is most
     * sensitive to it at the FRAME EDGES — an FOV error is invisible dead centre and grows
     * outward. So rather than a pass/fail sweep test, this lets the pilot put a marker on a
     * known object near the edge and adjust until the icon sits on it, watching it converge
     * live. Changes apply to the running overlay immediately and persist.
     *
     * Deliberately adjusts the 1x base: the zoom correction is applied on top of it, so
     * calibrating at 1x fixes every zoom level at once.
     */
    private fun onArCalibrateTapped() {
        AppLog.v(TAG, "AR FOV calibration opened")
        val view = layoutInflater.inflate(R.layout.dialog_ar_fov, null)
        val hValue = view.findViewById<TextView>(R.id.arFovHValue)
        val vValue = view.findViewById<TextView>(R.id.arFovVValue)
        val hint = view.findViewById<TextView>(R.id.arFovHint)

        var h = TakBridgeHolder.currentHFovBase
        var v = TakBridgeHolder.currentVFovBase

        fun apply() {
            com.dji.sdk.sample.tak.ArSettings.saveFov(this, h, v)
            h = TakBridgeHolder.currentHFovBase
            v = TakBridgeHolder.currentVFovBase
            hValue.text = "%.1f°".format(h)
            vValue.text = "%.1f°".format(v)
            hint.text = if (TakBridgeHolder.currentZoomFactor > 1.0) {
                "Effective at %.0fx zoom: %.1f° × %.1f°".format(
                    TakBridgeHolder.currentZoomFactor,
                    com.dji.sdk.sample.tak.DroneTakBridge.hFovDeg(TakBridgeHolder.currentZoomFactor),
                    com.dji.sdk.sample.tak.DroneTakBridge.vFovDeg(TakBridgeHolder.currentZoomFactor),
                )
            } else {
                "Marker too far OUT from centre → reduce. Too far IN → increase."
            }
        }
        apply()

        view.findViewById<Button>(R.id.arFovHMinus).setOnClickListener { h -= FOV_STEP_DEG; apply() }
        view.findViewById<Button>(R.id.arFovHPlus).setOnClickListener { h += FOV_STEP_DEG; apply() }
        view.findViewById<Button>(R.id.arFovVMinus).setOnClickListener { v -= FOV_STEP_DEG; apply() }
        view.findViewById<Button>(R.id.arFovVPlus).setOnClickListener { v += FOV_STEP_DEG; apply() }

        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Calibrate AR field of view")
            .setView(view)
            .setPositiveButton("Done", null)
            .setNeutralButton("Reset") { _, _ ->
                com.dji.sdk.sample.tak.ArSettings.resetFov(this)
                Toast.makeText(this, "FOV reset to published specs", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * AR pill on/off state.
     *
     * Running reads as the app's status green (#4CAF50) on a green-filled pill; off is the plain
     * white pill at reduced alpha, like the other inactive toolbar controls. The earlier version
     * only shifted the label from white to the #9AC4FF accent, which is the same tint used for
     * ordinary emphasis all over this screen — at 12sp over moving video that was not a state
     * indication so much as a hint. Green is the colour this app already means "on/good" with
     * (TAK dot, GPS icon, crosshair accuracy ring), and it carries at a glance.
     *
     * Size is deliberately unchanged between states: a control that grows on tap reads as a
     * different control.
     */
    private fun refreshArButton() {
        val on = arOverlay.isRunning
        arButton.alpha = if (on) 1f else 0.45f
        arButton.setBackgroundResource(
            if (on) R.drawable.bg_ar_pill_active else R.drawable.bg_zoom_pill
        )
        arButton.setTextColor(
            if (on) ContextCompat.getColor(applicationContext, R.color.tp_state_go) else android.graphics.Color.WHITE
        )
    }

    /**
     * Transient notice over the top-left of the video, auto-hidden.
     *
     * One implementation shared by every caller so they can't drift on placement or timeout.
     * Distinct from a Toast on purpose: this says "the app did the thing", where the toasts
     * [TakDropMarkers] raises say "the TAK server has it" — during a comms outage the difference
     * between those two is exactly what the pilot needs to see.
     */
    private fun showNotice(text: String) {
        fpvNotice.text = text
        fpvNotice.visibility = View.VISIBLE
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, HOME_NOTICE_MS)
    }

    /**
     * Quick drop — tap the reticle, marker goes down at the look point. No dialog, no menu.
     *
     * The toolbar drop button asks for a name and an affiliation because those drops are a
     * record. This one is a live pointer: the pilot has seen something, wants the rest of the
     * picture looking at it now, and any interaction between seeing it and marking it is
     * interaction spent not watching. So it is always [TakDropMarkers.Affiliation.UNKNOWN] (a
     * marker placed in under a second is unverified by definition) with a fixed callsign, and
     * only one can exist — a second tap is refused rather than silently laying down a duplicate.
     * Re-aiming it is the long-press, so the two gestures can't be confused under pressure.
     */
    private fun onQuickDropTapped() {
        AppLog.v(TAG, "tap: reticle (quick drop)")
        if (TakDropMarkers.quickPin() != null) {
            // Deliberately not "moved it for you": a tap that sometimes places and sometimes
            // moves is a gesture the pilot can't predict the result of.
            Toast.makeText(this,
                "${TakDropMarkers.QUICK_NAME} already placed — long-press the reticle to re-aim it",
                Toast.LENGTH_SHORT).show()
            return
        }
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "quick drop refused — no look point (GPS/gimbal not ready)")
            Toast.makeText(this, "Can't drop a marker yet — waiting on GPS + gimbal",
                Toast.LENGTH_LONG).show()
            return
        }
        val (lat, lon, elev) = look
        if (TakDropMarkers.placeQuick(lat, lon, elev)) {
            showNotice("${TakDropMarkers.QUICK_NAME} dropped")
        }
    }

    /**
     * Long-press the reticle — re-aim the quick-drop marker at whatever the camera is on now,
     * keeping its uid so it moves in place on every other TAK client.
     *
     * Places it if there isn't one yet, rather than scolding the pilot for using the wrong
     * gesture: both gestures then mean "the marker belongs where I'm looking", which is the only
     * thing this feature does.
     */
    private fun onQuickDropLongPressed() {
        AppLog.v(TAG, "long-press: reticle (quick drop re-aim)")
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "quick drop re-aim refused — no look point (GPS/gimbal not ready)")
            Toast.makeText(this, "Can't move the marker yet — waiting on GPS + gimbal",
                Toast.LENGTH_LONG).show()
            return
        }
        val (lat, lon, elev) = look
        if (TakDropMarkers.moveQuick(lat, lon, elev)) {
            showNotice("${TakDropMarkers.QUICK_NAME} re-aimed")
        } else if (TakDropMarkers.placeQuick(lat, lon, elev)) {
            showNotice("${TakDropMarkers.QUICK_NAME} dropped")
        }
    }

    private fun onDropPinTapped() {
        AppLog.v(TAG, "tap: drop pin")
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            AppLog.w(TAG, "drop pin refused — no look point (GPS/gimbal not ready)")
            Toast.makeText(this, "Can't drop a marker yet — waiting on GPS + gimbal",
                Toast.LENGTH_LONG).show()
            return
        }
        val (lat, lon, elev) = look

        val view = layoutInflater.inflate(R.layout.dialog_drop_pin, null)
        val nameField = view.findViewById<android.widget.EditText>(R.id.dropPinName)
        // Only a preview of the next number — TakDropMarkers consumes the counter solely when
        // this exact string comes back unedited, so a custom name doesn't leave a gap.
        var autoName = TakDropMarkers.nextAutoName()
        nameField.setText(autoName)
        nameField.setSelection(autoName.length)
        view.findViewById<TextView>(R.id.dropPinLocation).text =
            // Display only — the elevation sent in the CoT stays in metres (CotBuilder's
            // contract), this is just what the pilot reads before confirming the drop.
            "%.5f, %.5f  ·  %s elev".format(lat, lon, Units.feet(elev))

        // The affiliation icons themselves are the picker (no radio dot) — tapping one outlines
        // it via bg_marker_type_selected and clears the others. Defaults to Unknown: an
        // unverified drop shouldn't read as an affirmative Friendly/Hostile/Neutral call until
        // the pilot actually picks one.
        val chips = mapOf(
            TakDropMarkers.Affiliation.FRIENDLY to view.findViewById<View>(R.id.dropPinFriendly),
            TakDropMarkers.Affiliation.HOSTILE to view.findViewById<View>(R.id.dropPinHostile),
            TakDropMarkers.Affiliation.NEUTRAL to view.findViewById<View>(R.id.dropPinNeutral),
            TakDropMarkers.Affiliation.UNKNOWN to view.findViewById<View>(R.id.dropPinUnknown),
        )
        var selectedAff = TakDropMarkers.Affiliation.UNKNOWN
        fun refreshChipSelection() {
            for ((aff, chip) in chips) {
                chip.setBackgroundResource(
                    if (aff == selectedAff) R.drawable.bg_marker_type_selected
                    else android.R.color.transparent)
            }
        }
        for ((aff, chip) in chips) {
            chip.setOnClickListener {
                selectedAff = aff
                refreshChipSelection()
            }
        }
        refreshChipSelection()

        val dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Drop Marker at Crosshair")
            .setView(view)
            .setPositiveButton("Drop") { _, _ ->
                AppLog.i(TAG, "drop pin confirmed: ${selectedAff.label} @ $lat,$lon elev=$elev")
                TakDropMarkers.placeAt(selectedAff, lat, lon, elev, nameField.text.toString())
            }
            .setNegativeButton("Cancel") { _, _ -> AppLog.v(TAG, "drop pin cancelled") }
            // Placeholder text/listener — restyled and rewired in setOnShowListener below, since
            // the button bar's Views don't exist until the dialog is actually shown.
            .setNeutralButton("Reset Numbering", null)
            .create()
        // Bottom-left, in line with Drop/Cancel — that's simply where AlertDialog puts the
        // neutral button, so red-button.setOnClickListener replaces the (dismissing) listener
        // registered via setNeutralButton above with one that resets the counter in place and
        // leaves the dialog open, rather than closing the whole drop flow on tap.
        dialog.setOnShowListener {
            val resetBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            styleRedButton(resetBtn)
            resetBtn.setOnClickListener {
                AppLog.i(TAG, "auto-name counter reset from drop dialog")
                TakDropMarkers.resetAutoNameCounter()
                val newAutoName = TakDropMarkers.nextAutoName()
                // Only overwrite the field if the pilot hasn't already typed something of
                // their own — same "don't clobber an edit" rule the counter-consume logic
                // itself follows.
                if (nameField.text.toString() == autoName) {
                    nameField.setText(newAutoName)
                    nameField.setSelection(newAutoName.length)
                }
                autoName = newAutoName
                Toast.makeText(this, "Numbering reset — next is $newAutoName", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    /** 6C: local-hide confirm for a tapped inbound contact — the map click listener above
     *  already resolved which uid was hit; this just confirms before dismissing it, since it's
     *  someone else's marker (or one of ours that reappeared after a delete). */
    private fun onInboundMarkerTapped(uid: String) {
        val tm = com.dji.sdk.sample.tak.TakMapMarkers
        val user = tm.inboundUser(uid) ?: return
        AppLog.v(TAG, "tap: inbound marker ${user.callsign} ($uid)")
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle(user.callsign ?: uid)
            .setMessage("Hide this marker from your map? It stays on the TAK server and may " +
                "reappear if another client re-broadcasts it.")
            .setPositiveButton("Hide") { _, _ ->
                AppLog.i(TAG, "inbound marker hide confirmed: $uid")
                tm.hideInbound(uid)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** 6C: markers list panel (dialog_markers_list.xml) — two red action buttons up top
     *  (Reset Numbering, Clear All Markers), then one row per dropped pin (a red X for a quick
     *  individual delete, its affiliation icon, range/bearing from the aircraft). Tapping a
     *  row's body (not its X) opens the full action menu (move/rename/retype/re-send/delete);
     *  tapping the X deletes that pin immediately and refreshes the list in place, since
     *  [row_marker_type.xml]'s X is a separate clickable child that consumes the touch before
     *  the enclosing ListView's own item-click ever fires. No map interaction needed, matching
     *  the locked mini-map. */
    private fun onMarkersListTapped() {
        // No early-return on an empty pin list: Reset Numbering and Clear All Markers are both
        // still meaningful with zero pins (e.g. right after a Clear All, resetting the counter
        // for the next flight) — the panel must stay reachable, just with an empty rows list.
        val view = layoutInflater.inflate(R.layout.dialog_markers_list, null)
        val adapter = IconListAdapter(this)
        view.findViewById<android.widget.ListView>(R.id.markersListView).adapter = adapter
        lateinit var dialog: AlertDialog

        fun refresh() {
            val hud = TakBridgeHolder.hud()
            // Range/bearing from the AIRCRAFT to the marker, for either kind.
            fun range(lat: Double, lon: Double): String {
                if (hud == null) return ""
                val d = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, lat, lon)
                val b = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, lat, lon)
                // Units.distance (not .feet) here: a marker has no geofence bound the way the
                // aircraft's own position does, so this can legitimately run to five digits of
                // feet where miles read better.
                return "  ·  %s @ %03.0f°".format(Units.distance(d), b)
            }

            // Own pins first, then what the team shared. Own first because those are the ones the
            // pilot can act on fully; a shared row offers a local delete and nothing else.
            val rows = ArrayList<IconListAdapter.Row>()
            TakDropMarkers.listPins().forEach { pin ->
                rows.add(IconListAdapter.Row(
                    "${pin.affiliation.label}: ${pin.name}${range(pin.lat, pin.lon)}",
                    pin.affiliation.res, pin))
            }
            com.dji.sdk.sample.tak.TakMapMarkers.listShared().forEach { m ->
                rows.add(IconListAdapter.Row(
                    "Shared: ${m.callsign}${range(m.lat, m.lon)}",
                    com.dji.sdk.sample.tak.TakMapMarkers.sharedIconRes(m.type),
                    pin = null, shared = m))
            }
            adapter.setRows(rows)
        }

        adapter.onDeleteX = onDeleteX@{ row ->
            row.pin?.let {
                AppLog.i(TAG, "marker delete (X): ${it.key}")
                TakDropMarkers.delete(it.key)
                refresh()
                return@onDeleteX
            }
            row.shared?.let {
                // Local only. Moving, renaming or re-sending a shared marker would edit it on
                // every other client's picture, which is not the pilot's call to make from here.
                AppLog.i(TAG, "shared marker hidden (X): ${it.uid}")
                com.dji.sdk.sample.tak.TakMapMarkers.hideInbound(it.uid)
                refresh()
            }
        }
        view.findViewById<android.widget.ListView>(R.id.markersListView)
            .setOnItemClickListener { _, _, position, _ ->
                val row = adapter.rowAt(position)
                when {
                    row.pin != null -> {
                        onMarkerRowTapped(row.pin)
                        dialog.dismiss()
                    }
                    // No action menu for a shared marker: every entry on that menu (move,
                    // rename, change type, re-send) would change it for the whole team. The X
                    // is the one thing the pilot may legitimately do to it.
                    row.shared != null ->
                        Toast.makeText(this, "Shared by another user — X removes it from your map only",
                            Toast.LENGTH_SHORT).show()
                }
            }

        dialog = AlertDialog.Builder(this, R.style.TakDialogTheme)
            // Not "Dropped Markers" any more — the list holds what the team shared as well, and
            // a title naming only one kind is what made the other look missing.
            .setTitle("Markers")
            .setView(view)
            .setNegativeButton("Close", null)
            // Placeholder — restyled/rewired in setOnShowListener below, same pattern as the
            // drop-pin dialog's Reset Numbering button.
            .setNeutralButton("Clear All Markers", null)
            .create()
        // Bottom-left, in line with Close — that's simply where AlertDialog puts the neutral
        // button.
        dialog.setOnShowListener {
            val clearBtn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            styleRedButton(clearBtn)
            clearBtn.setOnClickListener { onClearAllMarkersTapped { refresh() } }
        }
        refresh()
        dialog.show()
    }

    /**
     * Clears BOTH sets, and says so before committing.
     *
     * It used to clear only the pilot's own, which left a "cleared" map still carrying every
     * marker the team had shared — the one outcome a pilot pressing Clear All is not expecting.
     * The two counts are stated separately because the consequences differ: the pilot's own
     * markers stay live on the server and may come back to other clients, while a shared one is
     * only being taken off this picture.
     */
    private fun onClearAllMarkersTapped(onCleared: () -> Unit) {
        val ownCount = TakDropMarkers.listPins().size
        val sharedCount = com.dji.sdk.sample.tak.TakMapMarkers.listShared().size
        val body = buildString {
            append("Remove ")
            append(if (ownCount == 1) "1 marker you dropped" else "$ownCount markers you dropped")
            append(" and ")
            append(if (sharedCount == 1) "1 shared marker" else "$sharedCount shared markers")
            append(" from your map?\n\nThis is local only. Your own markers stay on the TAK ")
            append("server until they go stale (72h) and may still show on other clients. ")
            append("Shared markers come back if the team sends them again.")
        }
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Clear All Markers")
            .setMessage(body)
            .setPositiveButton("Clear All Markers") { _, _ ->
                AppLog.i(TAG, "markers: clear all confirmed ($ownCount own, $sharedCount shared)")
                TakDropMarkers.clearAll()
                com.dji.sdk.sample.tak.TakMapMarkers.clearAllShared()
                onCleared()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMarkerRowTapped(pin: TakDropMarkers.PinInfo) {
        val actions = arrayOf("Move to crosshair", "Rename", "Change type", "Re-send", "Delete")
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle(pin.name)
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> onMoveMarkerTapped(pin)
                    1 -> onRenameMarkerTapped(pin)
                    2 -> onChangeTypeTapped(pin)
                    3 -> {
                        AppLog.i(TAG, "marker re-send: ${pin.key}")
                        TakDropMarkers.resend(pin.key)
                    }
                    4 -> onDeleteMarkerTapped(pin)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMoveMarkerTapped(pin: TakDropMarkers.PinInfo) {
        val look = TakBridgeHolder.lookPoint()
        if (look == null) {
            Toast.makeText(this, "Can't move — waiting on GPS + gimbal", Toast.LENGTH_LONG).show()
            return
        }
        val (lat, lon, elev) = look
        AppLog.i(TAG, "marker move: ${pin.key} -> $lat,$lon elev=$elev")
        TakDropMarkers.moveToLookPoint(pin.key, lat, lon, elev)
    }

    private fun onRenameMarkerTapped(pin: TakDropMarkers.PinInfo) {
        val field = android.widget.EditText(this).apply {
            setText(pin.name)
            setSelection(pin.name.length)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Rename Marker")
            .setView(field)
            .setPositiveButton("Rename") { _, _ ->
                AppLog.i(TAG, "marker rename: ${pin.key} -> '${field.text}'")
                TakDropMarkers.rename(pin.key, field.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onChangeTypeTapped(pin: TakDropMarkers.PinInfo) {
        val affiliations = TakDropMarkers.Affiliation.values()
        val adapter = IconListAdapter(this)
        adapter.setRows(affiliations.map { IconListAdapter.Row(it.label, it.res, pin = null) })
        AlertDialog.Builder(this, R.style.TakDialogTheme)
            .setTitle("Change Type")
            .setAdapter(adapter) { _, index ->
                AppLog.i(TAG, "marker retype: ${pin.key} -> ${affiliations[index].label}")
                TakDropMarkers.changeType(pin.key, affiliations[index])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Icon+label row adapter shared by the markers-list panel and the change-type picker
     *  (row_marker_type.xml) — plain [AlertDialog.setItems] has no icon/delete-X slot, so both
     *  dialogs use setAdapter instead. [setRows] + notifyDataSetChanged lets the markers list
     *  refresh itself in place after an X-delete without closing the dialog. */
    private class IconListAdapter(
        context: android.content.Context,
    ) : android.widget.BaseAdapter() {
        /** Exactly one of [pin] and [shared] is set on a markers-list row; both are null on the
         *  change-type rows, which reuse this adapter and are neither. */
        data class Row(
            val label: String,
            val iconRes: Int?,
            val pin: TakDropMarkers.PinInfo?,
            val shared: com.dji.sdk.sample.tak.TakMapMarkers.SharedMarker? = null,
        )

        /** Fired when a row's delete-X is tapped (markers-list only; null for change-type rows,
         *  which never show an X). */
        var onDeleteX: ((Row) -> Unit)? = null

        private var rows: List<Row> = emptyList()
        private val inflater = android.view.LayoutInflater.from(context)

        fun setRows(newRows: List<Row>) { rows = newRows; notifyDataSetChanged() }
        fun rowAt(position: Int): Row = rows[position]

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            // Not reusing convertView: rows carry per-position click state (the X's target
            // pin), and this list is short enough (a handful of dropped pins) that the
            // simplicity of always inflating fresh is worth more than view recycling here.
            val view = inflater.inflate(R.layout.row_marker_type, parent, false)
            val row = rows[position]

            val icon = view.findViewById<ImageView>(R.id.rowMarkerTypeIcon)
            if (row.iconRes != null) {
                icon.setImageResource(row.iconRes)
                icon.visibility = View.VISIBLE
            } else {
                icon.visibility = View.INVISIBLE
            }

            view.findViewById<TextView>(R.id.rowMarkerTypeLabel).text = row.label

            val deleteX = view.findViewById<ImageView>(R.id.rowMarkerDeleteX)
            // A shared row gets an X too — it removes the marker from THIS picture only. The
            // change-type rows (neither pin nor shared) get none.
            if (row.pin != null || row.shared != null) {
                deleteX.visibility = View.VISIBLE
                deleteX.setOnClickListener { onDeleteX?.invoke(row) }
            } else {
                deleteX.visibility = View.GONE
                deleteX.setOnClickListener(null)
            }
            return view
        }
    }

    private fun onDeleteMarkerTapped(pin: TakDropMarkers.PinInfo) {
        AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
            .setTitle("Delete Marker")
            .setMessage("Remove '${pin.name}' from your map? This is local-only — the marker " +
                "stays on the TAK server until it goes stale (14h) and may reappear on other " +
                "clients' pictures until then.")
            .setPositiveButton("Delete") { _, _ ->
                AppLog.i(TAG, "marker delete: ${pin.key}")
                TakDropMarkers.delete(pin.key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onShootPhotoTapped() {
        AppLog.v(REC_TAG, "tap: shutter (photo)")
        val camera = DJISampleApplication.getAircraftInstance()?.camera
        if (camera == null) {
            AppLog.w(REC_TAG, "photo ignored — aircraft not connected")
            Toast.makeText(this, "Aircraft not connected", Toast.LENGTH_SHORT).show()
            return
        }
        AppLog.i(REC_TAG, "photo: switching to PHOTO_SINGLE (flatModeSupported=${camera.isFlatCameraModeSupported})")
        val restoreVideoMode = CommonCallbacks.CompletionCallback<DJIError> { error ->
            AppLog.i(REC_TAG, "shoot photo result: ${error?.description ?: "OK"}")
            runOnUiThread {
                val msg = if (error == null) "Photo saved to aircraft SD card" else "Photo failed: ${error.description}"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            restoreVideoModeAfterPhoto(camera)
        }
        val shootAfterMode = CommonCallbacks.CompletionCallback<DJIError> { modeError ->
            AppLog.i(REC_TAG, "photo: set PHOTO_SINGLE mode: ${modeError?.description ?: "OK"}")
            if (modeError != null) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't switch to photo mode: ${modeError.description}", Toast.LENGTH_SHORT).show()
                }
                return@CompletionCallback
            }
            // Re-push the same metering/exposure-mode/EV used for video onto photo mode before
            // shooting — PHOTO_SINGLE has its own separately-persisted exposure state, so
            // without this the still's EV wouldn't necessarily match what the live feed showed.
            ExposureController.applyExposureSettings(applicationContext, camera) {
                camera.startShootPhoto(restoreVideoMode)
            }
        }
        if (camera.isFlatCameraModeSupported) {
            camera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE, shootAfterMode)
        } else {
            camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, shootAfterMode)
        }
    }

    /**
     * Puts the camera back in VIDEO_NORMAL after a still — but only once it is actually willing
     * to change mode, and verified rather than assumed.
     *
     * `startShootPhoto`'s completion callback means "the shutter fired", NOT "the camera is
     * done". While the still is still being written the camera rejects a mode change outright.
     * Field-observed 2026-08-03 on the Air 2: the restore ran 14ms after the shoot callback and
     * every call returned "Undefined Error", so the camera stayed in photo mode for the rest of
     * the flight with nothing on screen saying so — the live FPV is this screen's primary job, so
     * that is not a cosmetic failure.
     *
     * So: wait for [TakBridgeHolder.photoInProgress] to clear, then switch, then retry if the
     * switch itself is still refused. Both waits share one attempt budget — after
     * [PHOTO_RESTORE_MAX_ATTEMPTS] it tries anyway rather than waiting forever on a camera state
     * that may never arrive, and if that final attempt fails the pilot is TOLD, because a camera
     * silently left in photo mode is exactly the failure this is here to prevent.
     */
    /**
     * Paints the one warning that currently owns the banner, or hides it.
     *
     * NOTHING IS FILTERED OUT ON THE PILOT'S BEHALF — that rule is unchanged and is why
     * "Cannot takeoff in a no-fly zone" is on this banner at all, after it stayed invisible
     * through two flights. What changed is that the aircraft's faults now arrive through
     * [FlightWarnings] alongside the conditions DJIDiagnostics has no equivalent for, and the
     * banner shows the WORST one at a time with a +N for the rest, instead of concatenating an
     * unbounded list that grew down over the video and strobed whenever a fault flapped.
     *
     * Polled from the HUD tick as well as pushed from the diagnostics callback: the hold and
     * the queue advance with time, not only with events, so the banner has to be re-asked.
     */
    private fun renderWarning() {
        val d = FlightWarnings.display()
        if (d == null) {
            flightDiagnostics.visibility = View.GONE
            return
        }
        flightDiagnostics.text = d.text
        flightDiagnostics.setTextColor(
            ContextCompat.getColor(
                applicationContext,
                if (d.red) R.color.tp_state_danger else R.color.tp_state_unknown,
            )
        )
        flightDiagnostics.visibility = View.VISIBLE
    }

    private fun restoreVideoModeAfterPhoto(camera: Camera, attempt: Int = 1) {
        if (TakBridgeHolder.photoInProgress() && attempt < PHOTO_RESTORE_MAX_ATTEMPTS) {
            handler.postDelayed(
                { restoreVideoModeAfterPhoto(camera, attempt + 1) }, PHOTO_RESTORE_RETRY_MS)
            return
        }
        AppLog.i(REC_TAG, "photo: restoring VIDEO_NORMAL + PROGRAM auto-exposure (attempt $attempt)")
        ExposureController.applyDefaults(applicationContext, camera) { err ->
            if (err == null) {
                if (attempt > 1) AppLog.i(REC_TAG, "photo: VIDEO mode restored on attempt $attempt")
                return@applyDefaults
            }
            if (attempt < PHOTO_RESTORE_MAX_ATTEMPTS) {
                AppLog.w(REC_TAG, "photo: VIDEO mode restore refused (${err.description}) — " +
                    "camera still busy, retrying (attempt $attempt)")
                handler.postDelayed(
                    { restoreVideoModeAfterPhoto(camera, attempt + 1) }, PHOTO_RESTORE_RETRY_MS)
            } else {
                AppLog.e(REC_TAG, "photo: VIDEO mode restore FAILED after $attempt attempts " +
                    "(${err.description}) — camera left in PHOTO mode")
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Camera stuck in photo mode — tap Video Re-Sync or re-enter flight screen",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
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

        // A contact going stale is a rendering change that no inbound CoT announces, so the
        // marker layer needs a clock of its own. Piggybacks on this tick; no-ops unless an
        // icon actually needs regenerating. Deliberately above the no-GPS-fix early return —
        // other operators' markers don't depend on OUR aircraft having a fix.
        com.dji.sdk.sample.tak.TakMapMarkers.tick()

        // Same reasoning as the marker tick: the warning banner's hold and its queue advance
        // with the clock, not only when a warning changes, so it has to be re-asked. Also above
        // the no-GPS-fix early return — losing the fix is itself a condition worth showing.
        renderWarning()
        logResourcesPeriodically()

        fpvClock.text = clockFormat.format(java.util.Date())

        // What the AIRCRAFT holds, not what Pre-Flight asked for. "RTH --" until it answers:
        // an unknown return height must not be shown as a number the pilot can rely on.
        fpvRthAltitude.text = hud?.rthHeightM
            ?.let { "RTH ${Units.feet(it.toDouble())}" } ?: "RTH --"

        toolbarBattery.setPercent(hud?.batteryPct)

        // Show the real satellite count whenever telemetry exists, even below lock threshold —
        // "—" used to mean "no fix," but visually that's indistinguishable from "no telemetry
        // at all," and a pilot watching the count creep up while acquiring a fix is more useful
        // than it vanishing. The icon's color still carries the fix/no-fix distinction.
        toolbarGps.text = hud?.satCount?.toString() ?: "—"
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
        if (homeSet && !lastHomeSet) showNotice("Home Point Set")
        lastHomeSet = homeSet

        exposureReadout.text = "ISO ${hud?.liveIso ?: "—"}   ${hud?.liveShutter ?: "—"}"

        // SignalBarsView handles its own dot color + bar count from the raw %; the text just
        // shows the bucketed value alongside it.
        val signalPct = hud?.uplinkSignalPct
        toolbarSignal.setPercent(signalPct)
        toolbarSignalText.text = if (signalPct != null) "${bucketSignalPct(signalPct)}%" else "—%"
        toolbarSignalText.alpha = if (signalPct != null) 1.0f else 0.4f

        // Computed once per tick and shared: the AGL readout and the FAA ceiling check both
        // want height above the ground *under the aircraft*, and they must never disagree
        // about it — a readout saying one number while the ceiling warning judges another
        // would be worse than having no correction at all.
        val aglReading = if (hud != null) com.dji.sdk.sample.tak.TerrainAgl.reading(this, hud)
            else com.dji.sdk.sample.tak.TerrainAgl.Reading(0.0, terrainCorrected = false, mslMeters = null)

        updateGimbalPitch(hud)

        updateFaaCeiling(hud, aglReading)

        // Five lines, not seven. The right-hand column has to hold the exposure block, this
        // readout AND the mini-map inside one landscape screen height, and it overflowed on the
        // Pixel once MSL and gimbal lines were added — it would be worse on a shorter phone.
        // Callsign+speed and the two altitudes each pair naturally, so merging them costs no
        // information and buys two lines of headroom.
        fpvOverlayText.text = buildString {
            append(currentCallsign)
            append(if (hud != null) "   ${Units.mph(hud.speedMs)}" else "   — MPH")
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
                append("HOME %s  %03.0f°T".format(Units.feet(dist), bearing))
            } else {
                append("HOME — ft  —°T")
            }
            append('\n')
            // Both heights on one line. "AGL" only when DTED actually corrected it to
            // height-above-terrain-below; otherwise "ALT", which is what the raw number really
            // is (height above the takeoff point) — labelling an uncorrected figure AGL is
            // exactly the inaccuracy the terrain correction exists to remove, so the label moves
            // with it. MSL is computed separately and can be present while the first still reads
            // ALT. See TerrainAgl.
            if (hud != null && hud.hasFix) {
                append("%s %s".format(
                    Units.feet(aglReading.meters),
                    if (aglReading.terrainCorrected) "AGL" else "ALT",
                ))
            } else {
                append("— ft AGL")
            }
            append("  ·  ")
            val msl = aglReading.mslMeters
            append(if (msl != null) "%s MSL".format(Units.feet(msl)) else "— ft MSL")
            append('\n')
            if (hud != null) {
                // Remaining time is the AIRCRAFT's own estimate (GoHomeAssessment), which models
                // real battery state and draw. The previous readout was battery-percent times a
                // nominal 31-minute endurance, which ignored payload, wind, temperature and
                // throttle — it read optimistically high in exactly the conditions where an
                // accurate number matters most. Shown as "—" rather than a guess when the
                // aircraft isn't reporting one.
                val elapsedMin = hud.flightTimeSec / 60
                val remaining = hud.remainingFlightTimeSec
                    ?.let { "${it / 60} min" } ?: "— min"
                append("$elapsedMin min / $remaining left")
            } else {
                append("— min / — min left")
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
        // Same fallback as TakConnectActivity and TakDropMarkers, which read this identical
        // key — a different default here meant a pilot who went straight to the flight screen
        // without visiting TAK Setup first saw "TAKPilot2 Go-Mini2" in the HUD until they
        // manually typed "sUAS" into that other screen, even though "sUAS" was always the
        // documented first-launch default.
        currentCallsign = prefs.getString("callsign", "sUAS") ?: "sUAS"
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
        AppLog.v(TAG, "onResume")
        mapView.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        AppLog.v(TAG, "onPause")
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(hideNotice)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        AppLog.v(TAG, "onStop")
        TakBridgeHolder.stop()
        // Leaving the flight screen (back to Home, or the app going to background/closing) —
        // don't keep pushing video nobody's watching the pilot fly against; also releases the
        // screen-capture projection so it doesn't linger as a background foreground service.
        if (VideoStreamerHolder.isActive) {
            AppLog.i(TAG, "onStop: stopping live stream (left flight screen / app backgrounded)")
            VideoStreamerHolder.stop()
        }
        lastHomeSet = false
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        AppLog.v(TAG, "onDestroy")
        // Stop the AR redraw loop explicitly — it posts to a Handler several times a second and
        // would otherwise keep firing against a dead Activity.
        arOverlay.stop()
        VideoStreamerHolder.onStateChanged = null
        // Same reason as the line above: DjiSdkBridge is a process-wide singleton and would
        // otherwise hold this Activity alive through its diagnostics callback.
        DjiSdkBridge.onDiagnostics = null
        DjiObstacleState.onChanged = null
        TakDropMarkers.ui = null
        com.dji.sdk.sample.tak.TakMapMarkers.onMapDestroyed()
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    companion object {
        /** Flight-screen lifecycle + toolbar actions (RTH, zoom, TAK toggle, LIVE, nav). */
        private const val TAG = "TP2Flight"
        /** Camera capture operations specifically — recording and stills. */
        private const val REC_TAG = "TP2Record"
        private const val REQUEST_MEDIA_PROJECTION = 3001
        private const val HUD_INTERVAL_MS = 500L

        private const val RESOURCE_LOG_INTERVAL_MS = 30_000L

        /** 24-hour, no seconds. Seconds would change on every HUD tick and pull the eye for no
         *  reason; a pilot reading a clock in flight wants the minute. */
        private val clockFormat =
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)

        /** Separate from [TAG] on purpose: [TAG] is in AppLog's TAK_TAGS and disappears when an
         *  operator filters TAK logging off — which is exactly when they are chasing a memory
         *  problem and need this most. Same reasoning as the bridge's readiness tag. */
        private const val RESOURCE_TAG = "TP2Resources"
        /** Post-still VIDEO-mode restore: poll interval and total attempts (~3.6s of patience).
         *  Comfortably longer than the Air 2 takes to write a still, while still giving up in
         *  time to warn the pilot rather than retrying silently forever. */
        private const val PHOTO_RESTORE_RETRY_MS = 300L
        private const val PHOTO_RESTORE_MAX_ATTEMPTS = 12
        /** FOV calibration step. 0.5 deg is finer than the eye can judge at the frame edge,
         *  so it never limits how closely the pilot can converge. */
        private const val FOV_STEP_DEG = 0.5
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

        // Where the mini-map centers before the drone has a GPS fix. Town Square Park in
        // downtown Anchorage: a neutral public landmark, chosen deliberately so the default
        // view isn't an operator's home area or a public-safety facility.
        private val DEFAULT_CENTER = LatLng(61.2170, -149.8925)
    }
}

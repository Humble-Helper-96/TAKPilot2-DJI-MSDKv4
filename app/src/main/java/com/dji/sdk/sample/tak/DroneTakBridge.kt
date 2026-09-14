package com.dji.sdk.sample.tak

import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import com.dji.sdk.sample.internal.controller.DJISampleApplication
import com.taklite.client.tak.TakManager
import dji.common.airlink.SignalQualityCallback
import dji.common.battery.BatteryState
import dji.common.camera.ExposureSettings
import dji.common.camera.SystemState
import dji.common.error.DJIError
import dji.common.flightcontroller.FlightControllerState
import dji.common.gimbal.GimbalState
import dji.common.util.CommonCallbacks
import kotlin.math.sqrt

/**
 * DroneTakBridge — V4 SDK telemetry -> TAK air-track PLI (Phase 4).
 *
 * V5's version read everything through KeyManager.listen/getValue. V4 has no Key-value
 * abstraction for this data; instead each component (FlightController/Gimbal/Battery)
 * pushes its own state object through a setStateCallback(...) at its own cadence. We
 * cache the latest of each and build/send a PLI on our own timer tick — same shape as
 * V5's cached-listener + timer pattern, just against V4's callback objects instead of Keys.
 */
class DroneTakBridge(
    private val fallbackUid: String,
    private val droneCallsign: String,
    private val intervalMs: Long = 2000L,
) {
    private val tak = TakManager.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // Prefer the real aircraft serial (matches DJI UAS tool, stable per-aircraft so ATAK
    // associates the sensor cone correctly). Fetched async on start(); falls back to the
    // provided uid until it resolves.
    @Volatile private var droneUid: String = fallbackUid

    /** Optional RTSP/stream url to advertise in the drone CoT (wired in Phase 5). */
    @Volatile
    var videoUrl: String? = null

    /** When true, also push the camera slant point (sensor point of interest). */
    @Volatile
    var cameraPointEnabled: Boolean = false

    private val spiUid: String get() = "$droneUid-SPI"

    // Sensor FOV cone state, refreshed when camera-point is enabled; embedded in the
    // drone PLI so ATAK/taklite draw the cone natively. -1 = omit.
    @Volatile private var sensorFov = -1.0
    @Volatile private var sensorVfov = -1.0

    /** Current digital zoom (1.0 = none). Set by the flight screen's zoom control via
     *  [TakBridgeHolder]; narrows both the published FOV cone and the AR projection. */
    @Volatile var zoomFactor: Double = 1.0
    @Volatile private var sensorAzimuth = -1.0
    @Volatile private var sensorElevation = 0.0
    @Volatile private var sensorRange = -1.0

    // Latest pushed state from each component's callback (V4 has no synchronous "get fresh
    // value now" — every field arrives via its own push callback at its own rate).
    @Volatile private var lastState: FlightControllerState? = null
    /** elapsedRealtime of the last FlightControllerState, 0 = none. The drone CoT is only
     *  published while this is recent — see the freshness gate in [pushOnce]. */
    @Volatile private var lastStateMs = 0L
    @Volatile private var lastGimbal: GimbalState? = null
    @Volatile private var lastBattery: BatteryState? = null
    // RC-to-aircraft link quality, 0-100, from AirLink's uplink callback — the "controller
    // signal strength" a pilot cares about (distinct from downlink/video quality).
    @Volatile private var lastUplinkQuality: Int? = null

    /**
     * AIRCRAFT-to-RC link quality, 0-100 — the direction the VIDEO actually travels.
     *
     * Added 2026-07-27 during the FPV artifacting investigation, which had been reading
     * [lastUplinkQuality] and concluding "signal was perfect, so RF is ruled out." That was the
     * wrong channel: uplink carries control commands, downlink carries video, and a downlink
     * dropout is completely invisible in the uplink number. Both frame losses caught by
     * [com.dji.sdk.sample.takpilot2.FpvTextureView]'s frame_num detector happened while uplink
     * read 100%, which says nothing either way about the link the frames were lost on.
     */
    @Volatile private var lastDownlinkQuality: Int? = null

    /**
     * OcuSync's own reported VIDEO data rate in Mbps — link CAPACITY, as distinct from the
     * encoded bitrate we measure by counting bytes off the wire. If capacity sags while the
     * encoder keeps pushing ~8 Mbps, that gap is where frames get lost, and neither number
     * alone would show it. Null on aircraft without OcuSync, or before the first callback.
     */
    @Volatile private var lastVideoDataRateMbps: Float? = null
    @Volatile private var lastCameraState: SystemState? = null
    // Live exposure the camera actually chose (auto-ISO result under shutter-priority) — for
    // the on-screen ISO/shutter readout.
    @Volatile private var lastExposure: ExposureSettings? = null
    // One-shot guard so we push the auto-exposure setup exactly once per bridge run, the first
    // time the camera reports state (a reliable "camera is connected" signal — more so than
    // start(), where the camera component may not be up yet).
    @Volatile private var exposureApplied = false
    // Same one-shot pattern as exposureApplied, but for the pilot-configured flight-safety
    // limits (Pre-Flight Setup screen) — triggered off the first FlightControllerState report
    // instead of camera state, since these are FlightController settings, not camera ones.
    @Volatile private var limitsApplied = false

    private val flightStateCallback = FlightControllerState.Callback {
        lastState = it
        // Proof of life for the drone CoT — see the freshness gate in pushOnce().
        lastStateMs = android.os.SystemClock.elapsedRealtime()
        if (!limitsApplied) {
            limitsApplied = true
            DJISampleApplication.getAircraftInstance()?.flightController?.let { fc ->
                FlightLimitsController.applyDefaults(DJISampleApplication.getInstance(), fc)
            }
        }
    }
    private val gimbalStateCallback = GimbalState.Callback {
        lastGimbal = it
        // Same one-shot-per-connect pattern as exposure/limits: first gimbal state is the
        // reliable "gimbal is actually up" signal.
        if (!gimbalRangeApplied) {
            gimbalRangeApplied = true
            applyPitchRangeExtension()
            // Fixed per-mode speed, owned by ControlResponse. This used to call a local
            // applyPitchSpeed() that multiplied the CURRENT speed by 1.5 — which compounded on
            // every connect (22 -> 33 -> 49 -> 73 -> 100 in the 2026-08-12 logs) and left the
            // camera handling differently on each flight.
            DJISampleApplication.getInstance()?.let { ControlResponse.apply(it) }
            ControlResponse.logYawSmoothness()
        }
    }

    @Volatile private var gimbalRangeApplied = false

    /**
     * Lets the gimbal tilt UP past level, not just down.
     *
     * DJI ships with the upward range disabled: the gimbal stops at 0 (horizon), so anything
     * above the horizon simply cannot be looked at. Requested 2026-07-27 so a pilot can visually
     * acquire air traffic overhead — the AR overlay will happily draw an aircraft above the
     * frame, and until now the camera physically could not be pointed at it.
     *
     * Capability-gated rather than assumed: not every airframe supports the extension, and
     * `setPitchRangeExtensionEnabled` on one that doesn't is at best a wasted call. Failure is
     * logged and otherwise ignored — this is a nice-to-have, and an aircraft that refuses it
     * should still fly normally.
     */
    private fun applyPitchRangeExtension() {
        val gimbal = DJISampleApplication.getAircraftInstance()?.gimbals?.firstOrNull() ?: return
        val supported = try {
            gimbal.capabilities?.containsKey(dji.common.gimbal.CapabilityKey.PITCH_RANGE_EXTENSION) == true
        } catch (t: Throwable) {
            AppLog.w(TAG, "gimbal capability check failed: ${t.message}")
            false
        }
        if (!supported) {
            AppLog.i(TAG, "gimbal does not report PITCH_RANGE_EXTENSION — leaving pitch range alone")
            return
        }
        gimbal.setPitchRangeExtensionEnabled(true) { err ->
            if (err == null) AppLog.i(TAG, "gimbal pitch range extended — camera can now look up")
            else AppLog.w(TAG, "gimbal pitch range extension refused: ${err.description}")
        }
    }

    private val batteryStateCallback = BatteryState.Callback { lastBattery = it }
    private val uplinkQualityCallback = SignalQualityCallback { lastUplinkQuality = it }
    private val downlinkQualityCallback = SignalQualityCallback { lastDownlinkQuality = it }
    private val videoDataRateCallback =
        dji.sdk.airlink.OcuSyncLink.VideoDataRateCallback { lastVideoDataRateMbps = it }
    private val cameraStateCallback = SystemState.Callback {
        // A media-mode CHANGE is worth one line: the RC-N1's switch and shutter move the camera
        // without this application being asked, and the picture changes shape with it. Steady
        // state is not logged — this push arrives several times a second.
        val before = lastCameraState
        if (before != null && (before.flatMode != it.flatMode || before.mode != it.mode)) {
            AppLog.i(TAG, "camera media mode: ${before.flatMode}/${before.mode} -> ${it.flatMode}/${it.mode}")
        }
        // Likewise a recording starting or stopping: the RC-N1's record button does this with
        // no line from this application, and a flight log that cannot say when the card was
        // being written to cannot answer "was that recorded?".
        if (before != null && before.isRecording != it.isRecording) {
            AppLog.i(TAG, "camera recording: ${before.isRecording} -> ${it.isRecording} (mode ${it.flatMode})")
            cameraEvent(if (it.isRecording) CameraEvent.RECORDING_STARTED else CameraEvent.RECORDING_STOPPED)
        }
        // And a still being taken or written, for the same reason: the RC-N1's shutter takes a
        // photo natively — or does not — with no line from this application.
        if (before != null && (before.isShootingSinglePhoto != it.isShootingSinglePhoto ||
                before.isStoringPhoto != it.isStoringPhoto)) {
            AppLog.i(TAG, "camera still: shooting=${it.isShootingSinglePhoto} storing=${it.isStoringPhoto} (mode ${it.flatMode})")
        }
        lastCameraState = it
        if (!exposureApplied) {
            exposureApplied = true
            DJISampleApplication.getAircraftInstance()?.camera?.let { cam ->
                ExposureController.applyDefaults(DJISampleApplication.getInstance(), cam)
            }
        }
    }
    // Caches the camera's actual live exposure for the on-screen ISO/shutter readout. (No
    // per-change logging — that was a temporary diagnostic for the frozen-exposure issue,
    // resolved by switching to PROGRAM auto-exposure; it fired too often to keep in flight.)
    private val exposureSettingsCallback = ExposureSettings.Callback { lastExposure = it }

    /**
     * The SD card, as the camera reports it. Logged on every CHANGE and held for the HUD.
     *
     * ⚠ BENCH 2026-09-14: the RC-N1's shutter/record button beeped and the camera reported no
     * recording and no photo. "The card refused" is one of the two explanations and this is
     * what separates it from "the press never reached the camera".
     */
    @Volatile var lastStorage: dji.common.camera.StorageState? = null
        private set
    private val storageStateCallback = dji.common.camera.StorageState.Callback { st ->
        val b = lastStorage
        if (b == null || b.isInserted != st.isInserted || b.isVerified != st.isVerified ||
            b.hasError() != st.hasError() || b.isFull != st.isFull || b.isReadOnly != st.isReadOnly ||
            b.isInvalidFormat != st.isInvalidFormat || b.isFormatted != st.isFormatted) {
            AppLog.i(TAG, "SD card: inserted=${st.isInserted} verified=${st.isVerified} error=${st.hasError()} " +
                "full=${st.isFull} readOnly=${st.isReadOnly} invalidFormat=${st.isInvalidFormat} " +
                "formatted=${st.isFormatted} free=${st.remainingSpaceInMB}/${st.totalSpaceInMB}MB " +
                "captures=${st.availableCaptureCount}")
        }
        lastStorage = st
    }

    /**
     * What the CAMERA says happened, for the flight screen's notice (specification §4.8).
     *
     * ⚠ FROM THE CAMERA'S REPORT, NEVER FROM THE REQUEST'S CALLBACK. "Photo saved" fires when
     * the FILE lands on the card — `startShootPhoto`'s callback means the shutter fired, not
     * that anything was written (the Autel tree's v2.0.1 lesson), and the RC-N1's own shutter
     * never calls this application at all. The recording notices fire on the camera's
     * `isRecording` edge, which the REC pill and the hardware button both produce. Delivered on
     * the main thread; null when no flight screen is up.
     */
    enum class CameraEvent { PHOTO_SAVED, RECORDING_STARTED, RECORDING_STOPPED }
    @Volatile var onCameraEvent: ((CameraEvent) -> Unit)? = null
    private fun cameraEvent(e: CameraEvent) { onCameraEvent?.let { cb -> handler.post { cb(e) } } }

    /** A file landing on the card is the one proof that a photo or a recording happened. */
    private val mediaFileCallback = dji.sdk.media.MediaFile.Callback { f ->
        AppLog.i(TAG, "new media file: ${f?.fileName} ${f?.mediaType} ${f?.fileSize}B")
        // A JPEG only: an MP4 landing is the recording STOPPING, and that notice has already
        // gone out on the isRecording edge.
        if (f?.mediaType == dji.sdk.media.MediaFile.MediaType.JPEG) cameraEvent(CameraEvent.PHOTO_SAVED)
    }

    /**
     * The controller's buttons, as the phone hears them.
     *
     * ⚠ THIS SLOT WAS DELIBERATELY LEFT EMPTY UNTIL 2026-09-14 — ControlResponse.logYawSmoothness
     * records why: a listener slot holds one client, and detaching an RC listener killed the
     * signal bars on the Autel sibling. It is taken now because the RC-N1's shutter/record
     * button DOES NOTHING VISIBLE on the bench — a pilot-facing fault, not a diagnostic — and
     * whether the press reaches the phone is the first question. Owned here, like every other
     * SDK callback; armed at start(); NEVER detached in stop() (rule 2). Logged on the CHANGE of
     * any button's clicked state, so a held button is one line and a steady state is none.
     */
    /**
     * Fired on the main thread once per press of the RC-N1's shutter/record button.
     *
     * ⚠ THE PRESS IS THE APP'S TO ACT ON IN VIDEO MODE (bench, 2026-09-14, ledger D26): with
     * this application on the link the aircraft records NOTHING on that button unless the app
     * starts it — three presses, three beeps, no MP4 on the card. In photo mode the camera
     * takes the picture NATIVELY (`new media file` followed the press, and the card agrees),
     * so the consumer must not also shoot. The consumer decides by the camera's mode; this
     * only reports the press. Null when no flight screen is up, and then nothing happens —
     * the same as before v1.2.8.
     */
    @Volatile var onShutterRecordPressed: (() -> Unit)? = null

    private var lastButtons: String? = null
    private val hardwareStateCallback = dji.common.remotecontroller.HardwareState.HardwareStateCallback { hw ->
        fun b(name: String, btn: dji.common.remotecontroller.HardwareState.Button?) =
            if (btn != null && btn.isPresent && btn.isClicked) name else null
        val pressed = listOfNotNull(
            b("shootPhotoAndRecord", hw.shootPhotoAndRecordButton), b("shutter", hw.shutterButton),
            b("record", hw.recordButton), b("photoVideoToggle", hw.photoAndVideoToggleButton),
            b("playback", hw.playbackButton), b("pause", hw.pauseButton), b("goHome", hw.goHomeButton),
            b("C1", hw.c1Button), b("C2", hw.c2Button), b("C3", hw.c3Button), b("function", hw.functionButton),
            b("menu", hw.menuButton),
        ).joinToString(",")
        if (pressed != lastButtons) {
            if (pressed.isNotEmpty()) AppLog.i(TAG, "RC button: $pressed")
            val wasRecordPressed = lastButtons?.contains("shootPhotoAndRecord") == true
            lastButtons = pressed
            // One event per PRESS, on the clicked edge, never on the release and never repeated
            // while held.
            if (pressed.contains("shootPhotoAndRecord") && !wasRecordPressed) {
                onShutterRecordPressed?.let { cb -> handler.post { cb() } }
            }
        }
    }

    /**
     * Arms the RC hardware-state callback on the remote controller object that EXISTS NOW.
     *
     * ⚠ THE RC IS NOT THERE WHEN start() RUNS, AND THE ONE THAT ARRIVES IS REPLACED. Measured
     * 2026-09-14 on the Mini 2 + RC-N1: `aircraft.remoteController` was null at bridge start
     * with every camera callback already live; the REMOTE_CONTROLLER component appeared 2.5 s
     * after productConnect — and 8 s later the SDK swapped it for a DIFFERENT object
     * (`onComponentChange key:REMOTE_CONTROLLER old:bcx@8cc593f new:dcb@6234bb3`). A callback set
     * on the first object dies with it. So this tracks the object's IDENTITY and re-arms from
     * every 2 s tick whenever the SDK's current RC is not the one that was armed. The Autel
     * sibling's v2.0.2 is the same lesson with one fewer twist: ask again, always.
     */
    private var armedRc: dji.sdk.remotecontroller.RemoteController? = null
    private var rcArmWarned = false
    private fun armRemoteControllerIfNeeded() {
        val rc = DJISampleApplication.getAircraftInstance()?.remoteController
        if (rc == null) {
            if (!rcArmWarned) { AppLog.w(TAG, "RC hardware-state callback NOT armed: no remote controller yet — will retry each tick"); rcArmWarned = true }
            armedRc = null
            return
        }
        if (rc === armedRc) return
        try {
            rc.setHardwareStateCallback(hardwareStateCallback)
            AppLog.i(TAG, "RC hardware-state callback armed on $rc" + (if (armedRc != null) " (replaced $armedRc)" else ""))
            armedRc = rc
        } catch (t: Throwable) { AppLog.w(TAG, "RC hardware-state callback unavailable: ${t.message}") }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                armRemoteControllerIfNeeded()
                pushOnce()
            } catch (t: Throwable) {
                AppLog.w(TAG, "telemetry push failed: ${t.message}")
            }
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true

        // New session = new flight = a new takeoff point, so the latched terrain reference from
        // the last one must not carry over (see TerrainAgl).
        TerrainAgl.reset()

        val aircraft = DJISampleApplication.getAircraftInstance()
        if (aircraft == null) {
            AppLog.w(TAG, "start(): no aircraft connected yet, telemetry will be empty until it is")
        } else {
            aircraft.flightController?.setStateCallback(flightStateCallback)
            aircraft.gimbals?.firstOrNull()?.setStateCallback(gimbalStateCallback)
            aircraft.battery?.setStateCallback(batteryStateCallback)
            aircraft.airLink?.setUplinkSignalQualityCallback(uplinkQualityCallback)
            // Downlink is the direction video travels — see lastDownlinkQuality's doc for why
            // watching only uplink misled the artifacting investigation.
            aircraft.airLink?.setDownlinkSignalQualityCallback(downlinkQualityCallback)
            // OcuSync's own video-link data rate. Guarded: only OcuSync aircraft have this, and
            // asking a non-OcuSync product for the link would throw or return null.
            try {
                if (aircraft.airLink?.isOcuSyncLinkSupported == true) {
                    aircraft.airLink?.ocuSyncLink?.setVideoDataRateCallback(videoDataRateCallback)
                    AppLog.i(TAG, "OcuSync video-data-rate callback registered")
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "OcuSync video-data-rate callback unavailable: ${t.message}")
            }
            aircraft.camera?.setSystemStateCallback(cameraStateCallback)
            aircraft.camera?.setExposureSettingsCallback(exposureSettingsCallback)
            try { aircraft.camera?.setStorageStateCallBack(storageStateCallback) } catch (t: Throwable) { AppLog.w(TAG, "storage-state callback unavailable: ${t.message}") }
            try { aircraft.camera?.setMediaFileCallback(mediaFileCallback) } catch (t: Throwable) { AppLog.w(TAG, "media-file callback unavailable: ${t.message}") }
            armRemoteControllerIfNeeded()
            // TODO: resolve the real aircraft serial (BaseProduct.getSerialNumber) as a
            // stable per-aircraft uid, matching V5's approach. Deferred — droneUid falls
            // back to the caller-provided session uid, which is enough for a live PLI.
        }

        // The CONTROLLER's own position, for the operator marker. Idempotent, and it must be a
        // real requestLocationUpdates — see OperatorLocation for why the cache alone is empty.
        DJISampleApplication.getInstance()?.let { OperatorLocation.start(it) }

        // The at-limit warnings compare against the SAME configured values that get pushed to
        // the aircraft, read from the same place, so the banner cannot disagree with the limit
        // the aircraft is enforcing.
        DJISampleApplication.getInstance()?.let { ctx ->
            FlightWarnings.setLimits(
                FlightLimitsController.ftToM(FlightLimitsController.savedMaxAltitudeFt(ctx))?.toDouble(),
                FlightLimitsController.ftToM(FlightLimitsController.savedMaxRadiusFt(ctx))?.toDouble(),
            )
        }
        // A new session must not inherit the last one's banner state.
        FlightWarnings.reset()

        handler.post(tick)
        AppLog.i(TAG, "DroneTakBridge started ($droneCallsign / $droneUid, every ${intervalMs}ms)")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        OperatorLocation.stop()
        // ⚠ ONLY close the track if the AIRCRAFT is down. Stopping this bridge is an APP event —
        // the flight screen calls it on the way out — and it used to end the flight record every
        // time, so backing out to Home mid-flight split one sortie into two. The 2026-08-12 logs
        // show three such splits, one of them a 2-point stub.
        //
        // A session left open is not lost: rows go to the CSV as they are sampled, the landed
        // rule closes it when the aircraft is grounded for 10 s, and the orphan sweep writes the
        // GPX at next launch if the process dies first.
        //
        // A battery swap needs no special case. The aircraft has to land to be swapped, and
        // 10 s grounded finalises the sortie long before the battery comes out — so a swap
        // gives two records, one per sortie, which is what a flight log should show.
        val airborne = lastState?.isFlying == true
        if (airborne) {
            AppLog.i(TAG, "bridge stopped while airborne — flight record stays open")
        } else {
            FlightPathLogger.endSession("bridge stopped, aircraft down")
        }
        val aircraft = DJISampleApplication.getAircraftInstance()
        try { aircraft?.flightController?.setStateCallback(null) } catch (_: Throwable) {}
        try { aircraft?.gimbals?.firstOrNull()?.setStateCallback(null) } catch (_: Throwable) {}
        try { aircraft?.battery?.setStateCallback(null) } catch (_: Throwable) {}
        // ⚠ The three AirLink callbacks are DELIBERATELY NOT removed. On the Autel port,
        // removing the RC info listener at TAK stop killed the RC signal indicator for the
        // life of the process — an SDK asymmetry where removal detaches the underlying
        // packet subscription and re-registration does not re-attach it (found in flight,
        // 2026-08-06; see AutelTakBridge.unsubscribe in that tree). Whether MSDK v4 shares
        // the defect is unverified, but keeping the callbacks armed costs nothing — they
        // only write @Volatile caches — and start() re-arming replaces them in place. The
        // signal bars on the flight screen must never depend on a TAK toggle.
        try { aircraft?.camera?.setSystemStateCallback(null) } catch (_: Throwable) {}
        try { aircraft?.camera?.setExposureSettingsCallback(null) } catch (_: Throwable) {}
        try { aircraft?.camera?.setStorageStateCallBack(null) } catch (_: Throwable) {}
        try { aircraft?.camera?.setMediaFileCallback(null) } catch (_: Throwable) {}
        // The RC hardware-state callback is DELIBERATELY NOT removed — see its declaration and
        // the AirLink note above: an RC listener detached once stayed detached on the sibling.
        lastState = null
        lastGimbal = null
        lastBattery = null
        lastUplinkQuality = null
        lastDownlinkQuality = null
        lastVideoDataRateMbps = null
        lastCameraState = null
        lastExposure = null
        exposureApplied = false
        limitsApplied = false
        gimbalRangeApplied = false
        AppLog.i(TAG, "DroneTakBridge stopped")
    }

    /**
     * One-line flight-readiness snapshot, logged only when something in it CHANGES.
     *
     * Answers "why won't it arm" from the aircraft's own state rather than by hypothesis — the
     * fields that actually gate motor start: GPS quality and whether a home point exists (the
     * max-radius geofence this app enables is measured from it), IMU warm-up, and what mode the
     * controller thinks it is in. `motors`/`flying` make the arming attempt itself visible.
     *
     * Two deliberate choices. It rides the state this bridge ALREADY subscribes to, because DJI
     * permits one `setStateCallback` and a second subscriber would silently steal it. And it logs
     * under [READY_TAG], not [TAG], so it survives the Debug screen's "TAK logging off" filter —
     * [TAG] is in AppLog's TAK_TAGS, which is precisely why two flights' worth of telemetry was
     * missing from the logs during this investigation.
     */
    private var lastReadiness: String? = null

    private fun logReadinessIfChanged(state: dji.common.flightcontroller.FlightControllerState) {
        val line = "mode=${state.flightMode} motors=${state.areMotorsOn()} " +
            "flying=${state.isFlying} sats=${state.satelliteCount} " +
            "gps=${state.getGPSSignalLevel()} homeSet=${state.isHomeLocationSet} " +
            "imuPreheat=${state.isIMUPreheating}"
        if (line != lastReadiness) {
            lastReadiness = line
            AppLog.i(READY_TAG, line)
        }
    }

    private fun pushOnce() {
        val state = lastState ?: run {
            AppLog.d(TAG, "tick: no FlightControllerState pushed yet")
            return
        }
        // FRESHNESS, not just presence. lastState holds the LAST report the aircraft sent and
        // it survives the aircraft going away — it is only cleared in stop(), which a TAK
        // session running on a powered controller never reaches. So this tick kept re-sending
        // the last known position every 2 s, and each send renewed the CoT stale time: the
        // marker could never expire on any other client, whatever duration CotBuilder set.
        // Measured and fixed on the Autel tree first (2026-08-13); this tree has the same
        // shape. An aircraft that stopped talking must stop being reported.
        if (android.os.SystemClock.elapsedRealtime() - lastStateMs > TELEMETRY_FRESH_MS) {
            AppLog.d(TAG, "tick: telemetry stale — not publishing the aircraft")
            return
        }
        logReadinessIfChanged(state)

        val loc = state.aircraftLocation
        val lat = loc?.latitude ?: Double.NaN
        val lon = loc?.longitude ?: Double.NaN
        if (!isValidLat(lat) || !isValidLon(lon)) {
            // No GPS fix yet — skip this tick rather than send a bogus 0,0 marker.
            AppLog.d(TAG, "tick: no valid GPS fix yet (lat=$lat lon=$lon)")
            return
        }
        val hae = loc?.altitude?.toDouble() ?: 0.0

        // Horizontal ground speed from NED-ish velocity components, m/s.
        val speed = sqrt(
            (state.velocityX * state.velocityX + state.velocityY * state.velocityY).toDouble()
        )

        // True heading (deg). aircraftHeadDirection can be -180..180; normalize to 0..360.
        val heading = ((state.aircraftHeadDirection % 360.0) + 360.0) % 360.0

        val batt = lastBattery
        val battery = batt?.chargeRemainingInPercent ?: 0
        val batteryMaxMah = batt?.fullChargeCapacity ?: 0
        val batteryRemainMah = batt?.chargeRemaining ?: 0
        val voltage = (batt?.voltage ?: 0) / 1000.0

        val isFlying = state.isFlying
        val flightTimeSec = state.flightTimeInSeconds

        // Flight record. FED from this callback, never subscribed — the SDK state slot holds one
        // client and it is this bridge's. Deliberately before the TAK publish and outside its
        // connected-check: the record must be written with no server and no network, which is
        // most of what it is for. `hae` here is DJI's height above the TAKEOFF point, not a
        // geodetic altitude (the local name is older than that understanding); MSL is only
        // available once the terrain reference has latched, and is passed as NaN until then so
        // the GPX falls back rather than inventing a datum.
        FlightPathLogger.onTelemetry(
            lat, lon,
            aircraftMsl(hae) ?: Double.NaN, hae,
            speed, heading, battery, state.satelliteCount,
        )

        // Warning policy, fed from the same frame for the same reason: the banner and the PLI
        // must never disagree about whether the aircraft was flying.
        FlightWarnings.onState(state, isFlying, hae, homeDistanceMeters(state, lat, lon))

        val gimbal = lastGimbal
        val gimbalPitch = gimbal?.attitudeInDegrees?.pitch?.toDouble() ?: 0.0
        val gimbalYaw = gimbal?.attitudeInDegrees?.yaw?.toDouble() ?: 0.0

        // Compute the camera look-point + sensor FOV BEFORE the PLI, so the PLI can carry
        // the <sensor> element (ATAK/taklite draw the FOV cone from it).
        if (cameraPointEnabled) {
            pushCameraPoint(lat, lon, hae, heading)
        } else {
            sensorFov = -1.0; sensorVfov = -1.0; sensorAzimuth = -1.0
            sensorElevation = 0.0; sensorRange = -1.0
        }

        AppLog.d(TAG, "tick: lat=$lat lon=$lon hae=$hae hdg=${"%.0f".format(heading)} " +
            "spd=${"%.1f".format(speed)} batt=$battery% flying=$isFlying tak.connected=${tak.isConnected}")

        // TakManager.sendDronePLI() is a no-op internally when not connected, so this call is
        // safe regardless — the log line above is what lets us verify telemetry end-to-end
        // without a live TAK server.
        // north reference = 0: the <sensor azimuth> is an ABSOLUTE true-north bearing (same
        // convention V5 settled on after ATAK testing — see BEARING_OFFSET_DEG note below).
        // The video rides BOTH markers, and the aircraft is the primary one: an operator who
        // wants the camera selects the aircraft, so that is where the play control has to be
        // (operator, 2026-08-12). It stays on the operator marker as well — see pushPilotPli —
        // because the stream is a screen capture of the controller and keeps running when the
        // aircraft is down, while THIS report stops the moment there is no GPS fix. Two markers,
        // one feed: appendVideo derives the video uid from the url, so both advertise the same
        // uid and a client gets one video entry rather than two competing ones.
        tak.sendDronePLI(droneUid, droneCallsign, lat, lon, hae, heading, speed, battery,
            videoUrl, spiUid,
            sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange, 0.0,
            0.0, gimbalPitch, gimbalYaw,
            isFlying, flightTimeSec,
            batteryMaxMah, batteryRemainMah, voltage)

        pushPilotPli()
    }

    /** Latch for the one log line in pushPilotPli. Transition-only: this runs on the 2s tick. */
    @Volatile private var pilotFixMissing = false

    /**
     * The PILOT's marker — the operator on the ground, at the controller's own position.
     *
     * Until 2026-08-05 nothing published this. `TakManager.sendPLI` had no caller anywhere in
     * the source, so the operator's callsign sat at latitude 0, longitude 0 (the connect-time
     * registration message) until it went stale.
     *
     * ## With no fix, the marker still goes out (2026-09-10, from the Autel tree)
     *
     * Before this date the method returned without sending when there was no fix. Thus the
     * application published no pilot marker, and the controller was not in the CONTACT LIST of
     * the other clients. Nobody can send a marker to a client that is not in their list. A phone
     * indoors, or with a cold GPS receiver, could not receive markers. That is a worse condition
     * than an unknown position.
     *
     * The behaviour, and its cost, live in the shared core: `TakManager.sendPilotPLI` takes a
     * null location and sends the "position not known" form (`how="h-g-i-g-o"`, 0,0, `hae`,
     * `ce` and `le` not known, no track, no GPS source). Read the note there.
     */
    private fun pushPilotPli() {
        val fix = OperatorLocation.latest
        if (fix == null) {
            if (!pilotFixMissing) {
                pilotFixMissing = true
                AppLog.w(TAG, "the controller has no position fix — the pilot marker goes out " +
                    "in the \"position not known\" form (0,0). It stays in the contact list " +
                    "of the team, thus you can still send markers to this controller. The " +
                    "real position replaces it at the first fix. See OperatorLocation for " +
                    "what feeds this.")
            }
        } else if (pilotFixMissing) {
            pilotFixMissing = false
            AppLog.i(TAG, "the controller has a fix — the pilot marker publishes a real " +
                "position and no longer publishes 0,0")
        }
        runCatching {
            // No team argument — the pilot marker's colour is TakManager's PILOT_TEAM, always,
            // so the operator is one consistent colour across both airframes.
            tak.sendPilotPLI(fix, droneCallsign, "Team Member", pilotBatteryPct(), videoUrl)
        }.onFailure { AppLog.w(TAG, "pilot PLI failed: ${it.message}") }
    }

    /**
     * CONTROLLER battery, not the aircraft's — here the RC-N1's host phone. The aircraft has its
     * own marker and its own number. A phone about to die is a reason to end the flight, and
     * nothing else on the network reports it.
     *
     * Cached for [BATTERY_CACHE_MS]: getIntProperty is a binder IPC and paying it on every 2s
     * tick buys nothing, since the value drifts about a percent a minute. On failure the LAST
     * GOOD reading is returned, not 100 — a dead BatteryManager must not read as a full battery,
     * which would mask exactly the dying-controller condition this number exists to report. 100
     * as the very first value is the one honest exception: before any reading there is nothing
     * better to say.
     */
    private var batteryPctCache = 100
    private var batteryPctReadAt = 0L
    private fun pilotBatteryPct(): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - batteryPctReadAt < BATTERY_CACHE_MS && batteryPctReadAt != 0L) return batteryPctCache
        runCatching {
            val ctx = DJISampleApplication.getInstance() ?: return@runCatching
            val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE)
                as? android.os.BatteryManager ?: return@runCatching
            val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) {
                batteryPctCache = pct
                batteryPctReadAt = now
            }
        }.onFailure { AppLog.w(TAG, "controller battery read failed: ${it.message}") }
        return batteryPctCache
    }

    /**
     * True geographic bearing the camera points along. Prefers GimbalState's
     * yawRelativeToAircraftHeading (heading-stable — V5 found the raw gimbal yaw alone
     * doesn't track true north reliably), falling back to rawYaw + a fixed offset.
     */
    private fun cameraBearing(rawYaw: Double, aircraftHeading: Double): Double {
        val relYaw = lastGimbal?.yawRelativeToAircraftHeading?.toDouble()
        return if (relYaw != null && relYaw.isFinite())
            CameraSlantPoint.norm360(aircraftHeading + relYaw)
        else
            CameraSlantPoint.norm360(rawYaw + BEARING_OFFSET_DEG)
    }

    private fun pushCameraPoint(lat: Double, lon: Double, aglMeters: Double, aircraftHeading: Double) {
        val gimbal = lastGimbal
        if (gimbal == null) {
            AppLog.d(TAG, "SPI skip: gimbal attitude not yet received")
            return
        }
        val pitch = gimbal.attitudeInDegrees.pitch.toDouble()
        val yaw = gimbal.attitudeInDegrees.yaw.toDouble()
        val bearing = cameraBearing(yaw, aircraftHeading)

        // Slant-range calibration bias — see PITCH_OFFSET_DEG note below.
        val pitchAdj = pitch + PITCH_OFFSET_DEG

        // ABOVE THE HORIZON THERE IS NO LOOK-POINT, SO PUBLISH NOTHING.
        //
        // The camera ray only meets the ground while pointing below horizontal. Looking up,
        // CameraSlantPoint cannot solve and falls back to a FIXED range along the bearing
        // (FALLBACK_RANGE_M) rather than returning nothing. Publishing that puts a confident-
        // looking SPI on the TAK picture at a spot the camera is not seeing, and nobody
        // downstream can tell it was invented. An absent SPI is honest; a fabricated one is worse
        // than none, because the team will act on it.
        //
        // Threshold matches CameraSlantPoint's own `depression > 1.0` guard, so this suppresses
        // exactly the cases it would otherwise have faked.
        if (pitchAdj > -1.0) {
            sensorFov = -1.0; sensorVfov = -1.0; sensorAzimuth = -1.0
            sensorElevation = pitchAdj; sensorRange = -1.0
            AppLog.d(TAG, "SPI suppressed: camera at or above horizon " +
                "(pitch ${"%.1f".format(pitchAdj)}) — no ground intersection to publish")
            return
        }

        val gp = CameraSlantPoint.compute(
            lat, lon, aglMeters, bearing, pitchAdj, ::elevationLookup, aircraftMsl(aglMeters))
        tak.sendCameraPoint(spiUid, droneUid, "$droneCallsign-SPI", gp.lat, gp.lon, gp.rangeMeters)

        // FOV cone: ATAK/taklite draw it natively from the drone PLI's <sensor> element.
        // Mini 2 has one fixed-FOV camera (no lens switching, no live-tracked zoom yet).
        // Zoom-corrected, so the cone drawn on other clients' maps narrows when the pilot zooms
        // in — it was previously pinned to the 1x width regardless.
        sensorFov = hFovDeg(zoomFactor)
        sensorVfov = vFovDeg(zoomFactor)
        sensorAzimuth = bearing
        sensorElevation = pitch
        sensorRange = gp.rangeMeters
        AppLog.d(TAG, "SPI: pitch=$pitch yaw=$yaw heading=${"%.0f".format(aircraftHeading)} " +
            "az=${"%.0f".format(bearing)} alt=$aglMeters range=${Math.round(gp.rangeMeters)}m")
    }

    private fun isValidLat(v: Double) = v.isFinite() && v != 0.0 && v >= -90.0 && v <= 90.0
    private fun isValidLon(v: Double) = v.isFinite() && v != 0.0 && v >= -180.0 && v <= 180.0

    /** Snapshot of cached telemetry for the on-screen HUD (Phase 4 addendum). Same shape as
     *  the Autel port's AutelTakBridge.Hud — reads the same fields pushOnce() already uses,
     *  just without gating on the 2s CoT-push tick. */
    data class Hud(
        val lat: Double, val lon: Double, val alt: Double,
        val speedMs: Double, val headingDeg: Double, val batteryPct: Int,
        val satCount: Int, val gimbalPitch: Double?, val hasFix: Boolean,
        val homeLat: Double, val homeLon: Double, val homeSet: Boolean,
        val flightTimeSec: Int, val uplinkSignalPct: Int?, val isGoingHome: Boolean,
        val isRecording: Boolean, val liveIso: Int?, val liveShutter: String?,
        /** The AIRCRAFT's own remaining-flight-time estimate, seconds, or null if it isn't
         *  reporting one yet. Comes from its GoHomeAssessment, which models actual battery
         *  state and current draw — unlike the battery-percent-times-nominal-endurance guess
         *  this replaced, which ignored both. Null on the ground / before the first
         *  assessment. */
        val remainingFlightTimeSec: Int?,
        /** AIRCRAFT-to-RC link quality — the direction VIDEO travels. See
         *  [lastDownlinkQuality]; not surfaced on the pilot HUD (which shows the control link),
         *  this is for the video-health diagnostics. */
        val downlinkSignalPct: Int? = null,
        /** OcuSync's reported video-link capacity in Mbps, or null off OcuSync aircraft. */
        val videoDataRateMbps: Float? = null,
        /**
         * Return-to-home height IN METRES AS THE AIRCRAFT REPORTS IT, or null before it has said.
         *
         * ⚠ This is the aircraft's answer, not the Pre-Flight preference. Those are different
         * things and the difference is the point: a set can be accepted and not applied, or
         * rejected while the screen still shows the requested number. The sibling flew two
         * sorties on an RTH height the pilot believed they had changed. The HUD shows this one.
         */
        val rthHeightM: Int? = null,
        /**
         * The camera's media mode AS THE CAMERA REPORTS IT, off the same [SystemState] push as
         * [isRecording]. The flat mode is what the Mini 2 acts on; the legacy mode is read only
         * when the flat one is UNKNOWN. [MediaModePolicy] turns the pair into the readout's
         * words. Null before the camera has answered — unknown is its own state (§4.6).
         */
        val cameraFlatMode: dji.common.camera.SettingsDefinitions.FlatCameraMode? = null,
        val cameraMode: dji.common.camera.SettingsDefinitions.CameraMode? = null,
        /** The aircraft is in its automatic landing phase — the RTH menu's Cancel Landing reads
         *  and verifies against this. Off the same state push as [isGoingHome]. */
        val isLanding: Boolean = false,
    )

    /**
     * Whether the camera is still busy taking or WRITING a still.
     *
     * Read from the [SystemState] this bridge already subscribes to — DJI permits exactly one
     * `setSystemStateCallback`, so anything else needing camera state has to come through here
     * rather than registering its own and silently stealing this one.
     *
     * `isStoringPhoto` is the load-bearing half: `startShootPhoto`'s completion callback fires
     * when the shutter has fired, NOT when the file is written, and the camera rejects a mode
     * change in between. Field-observed 2026-08-03 on the Air 2 — the post-photo restore ran 14ms
     * after the shoot callback and every call came back "Undefined Error", leaving the camera
     * stuck in photo mode. Null state (no callback yet) reads as "not busy": a missing
     * subscription must not deadlock the caller into waiting forever.
     */
    fun photoInProgress(): Boolean = lastCameraState?.let {
        it.isStoringPhoto ||
            it.isShootingSinglePhoto ||
            it.isShootingBurstPhoto ||
            it.isShootingRAWBurstPhoto ||
            it.isShootingIntervalPhoto ||
            it.isShootingPanoramaPhoto ||
            it.isShootingShallowFocusPhoto
    } ?: false

    fun hud(): Hud {
        val state = lastState
        val loc = state?.aircraftLocation
        val lat = loc?.latitude ?: Double.NaN
        val lon = loc?.longitude ?: Double.NaN
        val hasFix = isValidLat(lat) && isValidLon(lon)
        val speed = state?.let {
            sqrt((it.velocityX * it.velocityX + it.velocityY * it.velocityY).toDouble())
        } ?: 0.0
        val heading = state?.let { ((it.aircraftHeadDirection % 360.0) + 360.0) % 360.0 } ?: 0.0
        val home = state?.homeLocation
        return Hud(
            lat, lon, loc?.altitude?.toDouble() ?: 0.0, speed, heading,
            lastBattery?.chargeRemainingInPercent ?: 0,
            state?.satelliteCount ?: 0,
            lastGimbal?.attitudeInDegrees?.pitch?.toDouble(), hasFix,
            home?.latitude ?: Double.NaN, home?.longitude ?: Double.NaN,
            state?.isHomeLocationSet ?: false,
            state?.flightTimeInSeconds ?: 0, lastUplinkQuality,
            state?.isGoingHome ?: false,
            lastCameraState?.isRecording ?: false,
            lastExposure?.iso?.takeIf { it > 0 },
            lastExposure?.shutterSpeed?.let { ExposureController.shutterLabel(it) },
            // Treated as "not reporting" rather than "zero minutes left" when non-positive:
            // the aircraft returns 0 before it has a usable estimate (notably on the ground),
            // and a HUD that reads "0 min remaining" while sitting on a full battery would be
            // both wrong and exactly the kind of wrong that erodes trust in the readout.
            state?.goHomeAssessment?.remainingFlightTime?.takeIf { it > 0 },
            lastDownlinkQuality,
            lastVideoDataRateMbps,
            // Straight off the state callback the bridge already receives, so the HUD tracks the
            // aircraft continuously rather than from a one-shot read at connect. Non-positive
            // means it has not reported one yet — shown as unknown, never as 0 ft.
            state?.goHomeHeight?.takeIf { it > 0 },
            lastCameraState?.flatMode,
            lastCameraState?.mode,
            state?.flightMode == dji.common.flightcontroller.FlightMode.AUTO_LANDING,
        )
    }

    /** Where the camera is pointing: true-north bearing and pitch, both degrees. */
    data class CameraPose(val bearingDeg: Double, val pitchDeg: Double)

    /**
     * True if [candidate] is a uid THIS app publishes — our own aircraft PLI or its sensor
     * point. The server echoes both back and they arrive as ordinary contacts, but neither is a
     * target: the aircraft is at its own position, and the SPI is by definition wherever the
     * camera is pointing, so drawing it would pin a marker permanently under the crosshair.
     *
     * Note [TakManager] already drops self-originated CoT, but it matches on the TAK *client's*
     * uid — the drone and SPI carry their own uids, so they get through that filter.
     */
    fun isOwnPublishedUid(candidate: String?): Boolean =
        candidate != null && (candidate == droneUid || candidate == spiUid)

    /**
     * Current camera pose, or null until GPS/gimbal state has arrived.
     *
     * Deliberately computed here from the SAME [cameraBearing] + [PITCH_OFFSET_DEG] model that
     * [lookPoint] uses, rather than handing out raw gimbal yaw for a caller to re-derive. The
     * AR overlay projects markers with this, and marker DROPS are placed with [lookPoint] — if
     * those two ever disagreed, a marker would render somewhere other than where it was placed,
     * and the overlay would look plausible while being wrong. One model, one place.
     */
    fun cameraPose(): CameraPose? {
        val gimbal = lastGimbal ?: return null
        val state = lastState ?: return null
        val heading = ((state.aircraftHeadDirection % 360.0) + 360.0) % 360.0
        val pitch = gimbal.attitudeInDegrees.pitch.toDouble()
        val yaw = gimbal.attitudeInDegrees.yaw.toDouble()
        return CameraPose(cameraBearing(yaw, heading), pitch + PITCH_OFFSET_DEG)
    }

    /**
     * One-shot ground point the camera is currently aimed at (for the "drop marker at
     * look-point" hot key, Phase 7). Returns (lat, lon, alt) or null if GPS/gimbal state
     * hasn't arrived yet.
     */
    fun lookPoint(): Triple<Double, Double, Double>? {
        val gimbal = lastGimbal ?: return null
        val state = lastState ?: return null
        val loc = state.aircraftLocation ?: return null
        if (!isValidLat(loc.latitude) || !isValidLon(loc.longitude)) return null
        val hae = loc.altitude.toDouble()
        val heading = ((state.aircraftHeadDirection % 360.0) + 360.0) % 360.0
        val pitch = gimbal.attitudeInDegrees.pitch.toDouble()
        val yaw = gimbal.attitudeInDegrees.yaw.toDouble()
        val bearing = cameraBearing(yaw, heading)
        val gp = CameraSlantPoint.compute(
            loc.latitude, loc.longitude, hae, bearing, pitch + PITCH_OFFSET_DEG, ::elevationLookup,
            aircraftMsl(hae),
        )
        // Third element is the target's terrain elevation, which dropped markers publish as
        // their CoT hae. 0.0 when there's no DTED coverage — same "unknown, assume sea level"
        // fallback the SPI push has always used.
        return Triple(gp.lat, gp.lon, gp.elevationMeters)
    }

    /**
     * Ground distance from the home point, metres, or NaN when no home point is set yet.
     *
     * Equirectangular approximation: at the ranges a Mini 2 flies (under 8 km) the error against
     * a great-circle solve is centimetres, and this is compared against a distance limit with a
     * 5% band, so precision beyond that buys nothing.
     */
    private fun homeDistanceMeters(
        state: FlightControllerState, lat: Double, lon: Double,
    ): Double {
        val home = state.homeLocation ?: return Double.NaN
        val hLat = home.latitude
        val hLon = home.longitude
        if (!isValidLat(hLat) || !isValidLon(hLon)) return Double.NaN
        val meanLatRad = Math.toRadians((lat + hLat) / 2.0)
        val dLat = Math.toRadians(lat - hLat) * EARTH_RADIUS_M
        val dLon = Math.toRadians(lon - hLon) * EARTH_RADIUS_M * Math.cos(meanLatRad)
        return sqrt(dLat * dLat + dLon * dLon)
    }

    /** Aircraft altitude above MEAN SEA LEVEL, or null before the takeoff terrain reference
     *  latches. [heightAboveTakeoff] is DJI's own altitude; adding the takeoff point's terrain
     *  elevation puts it in the same frame as the DTED samples the slant solver compares against. */
    private fun aircraftMsl(heightAboveTakeoff: Double): Double? =
        TerrainAgl.takeoffTerrainElevMsl?.plus(heightAboveTakeoff)

    /** DTED-backed elevation lookup for [CameraSlantPoint], or null if no tile covers the
     *  point (that's the normal case until the pilot uploads coverage for the area — the
     *  math falls back to the flat-ground estimate). */
    private fun elevationLookup(lat: Double, lon: Double): Double? {
        val context = DJISampleApplication.getInstance() ?: return null
        return DtedIndex.elevationAt(context, lat, lon)
    }

    companion object {
        private const val TAG = "DroneTakBridge"

        /** How old the last FlightControllerState may be and still be worth publishing as the
         *  aircraft's position. The state callback runs at about 2 Hz, so five seconds is many
         *  missed frames — long enough to ride out a hiccup, short enough that a powered-down
         *  aircraft stops being reported at once and its marker can then stale out. */
        private const val TELEMETRY_FRESH_MS = 5_000L

        /** How long a controller-battery reading is reused before another binder IPC. */
        private const val BATTERY_CACHE_MS = 30_000L

        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Flight-readiness snapshots. Separate from [TAG] on purpose — [TAG] is in AppLog's
         *  TAK_TAGS and disappears when an operator filters TAK logging off, which is exactly
         *  when they're diagnosing something and need this most. See [logReadinessIfChanged]. */
        private const val READY_TAG = "TP2Ready"


        // NOT YET FIELD-CALIBRATED for the Mini 2. V5's BEARING_OFFSET_DEG=105.0 was tuned
        // against the M30T's specific gimbal-to-airframe mounting by comparing the FOV cone
        // against two known camera directions in a live TAK client — that calibration does
        // not carry over to a different aircraft. Only matters when yawRelativeToAircraftHeading
        // is unavailable (cameraBearing() prefers that heading-stable value first). To
        // recalibrate: point the camera at a known compass direction, compare the cone in
        // ATAK/WinTAK against reality, and adjust this constant the same way.
        private const val BEARING_OFFSET_DEG = 0.0

        // Slant-range (look-point distance) calibration bias added to gimbal pitch. 0 = no
        // correction; tune the same way as BEARING_OFFSET_DEG if the look-point lands short/long.
        private const val PITCH_OFFSET_DEG = 0.0

        /** Shared so a future AR overlay (Phase 6) uses the same bearing correction as the cone. */
        fun bearingOffsetDeg() = BEARING_OFFSET_DEG

        // Base FOV now lives in TakBridgeHolder so the 6D-D calibration can adjust it in
        // flight; the published-spec defaults are TakBridgeHolder.DEFAULT_HFOV/VFOV.

        /**
         * Camera field of view, **corrected for digital zoom**, shared with the AR overlay so
         * the cone published to ATAK and the on-screen overlay can't disagree about how wide
         * the camera sees.
         *
         * Digital zoom on this aircraft is a centre crop, so the angular width shrinks with the
         * zoom factor — and NOT linearly. Halving the crop does not halve the angle:
         *
         *     effectiveHalfAngle = atan( tan(baseHalfAngle) / zoom )
         *
         * At 1x this returns the base values unchanged. At 2x the 73 deg horizontal becomes
         * ~41 deg, not 36.5 — using the linear approximation would leave markers a few degrees
         * out at the frame edges, which is the same class of error as the linear-projection bug
         * fixed in 6D-A.
         *
         * These base values are what the gimbal-sweep calibration in the 6D plan tunes: if a
         * marker leaves the frame edge before the real object does, the FOV here is too wide.
         */
        fun hFovDeg(zoom: Double = 1.0) = zoomedFov(TakBridgeHolder.currentHFovBase, zoom)
        fun vFovDeg(zoom: Double = 1.0) = zoomedFov(TakBridgeHolder.currentVFovBase, zoom)

        private fun zoomedFov(baseDeg: Double, zoom: Double): Double {
            if (!zoom.isFinite() || zoom <= 1.0) return baseDeg
            val halfRad = Math.toRadians(baseDeg / 2.0)
            return 2.0 * Math.toDegrees(Math.atan(Math.tan(halfRad) / zoom))
        }
    }
}

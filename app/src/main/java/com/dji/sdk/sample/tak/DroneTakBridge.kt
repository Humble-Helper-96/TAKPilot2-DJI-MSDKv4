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
            applyPitchSpeed()
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

    /**
     * Speeds up how fast the gimbal pitches in response to the RC dial — the stock rate was
     * reported as far too slow to work with in the field (2026-07-27).
     *
     * Reads the aircraft's CURRENT max speed and raises it by [PITCH_SPEED_BOOST], rather than
     * writing a fixed number: the valid range is model-specific, and a hardcoded value that
     * happens to suit the Mini 2 could be far too fast (or silently rejected) on the next
     * airframe this app is pointed at. Clamped to the range the aircraft itself reports via
     * [dji.common.gimbal.CapabilityKey.PITCH_CONTROLLER_MAX_SPEED], so it can only ask for
     * something the gimbal has said it accepts.
     *
     * Logs the before/after values — with a percentage applied to an unknown starting point,
     * the actual resulting speed is the only number that means anything, and if the pilot wants
     * it faster or slower still, that log line is where the next adjustment starts from.
     */
    private fun applyPitchSpeed() {
        val gimbal = DJISampleApplication.getAircraftInstance()?.gimbals?.firstOrNull() ?: return
        val range = try {
            gimbal.capabilities?.get(dji.common.gimbal.CapabilityKey.PITCH_CONTROLLER_MAX_SPEED)
                as? dji.common.util.DJIParamMinMaxCapability
        } catch (t: Throwable) {
            AppLog.w(TAG, "gimbal pitch-speed capability check failed: ${t.message}")
            null
        }
        if (range == null) {
            AppLog.i(TAG, "gimbal does not report PITCH_CONTROLLER_MAX_SPEED — leaving speed alone")
            return
        }
        val min = range.min?.toInt() ?: return
        val max = range.max?.toInt() ?: return
        gimbal.getControllerMaxSpeed(
            dji.common.gimbal.Axis.PITCH,
            object : CommonCallbacks.CompletionCallbackWith<Int> {
                override fun onSuccess(current: Int) {
                    val target = (current * PITCH_SPEED_BOOST).toInt().coerceIn(min, max)
                    if (target == current) {
                        AppLog.i(TAG, "gimbal pitch speed already at $current (range $min..$max) " +
                            "— no change")
                        return
                    }
                    gimbal.setControllerMaxSpeed(dji.common.gimbal.Axis.PITCH, target) { err ->
                        if (err == null) {
                            AppLog.i(TAG, "gimbal pitch speed $current -> $target " +
                                "(range $min..$max)")
                        } else {
                            AppLog.w(TAG, "gimbal pitch speed change refused: ${err.description}")
                        }
                    }
                }

                override fun onFailure(err: DJIError) {
                    AppLog.w(TAG, "could not read gimbal pitch speed: ${err.description}")
                }
            },
        )
    }
    private val batteryStateCallback = BatteryState.Callback { lastBattery = it }
    private val uplinkQualityCallback = SignalQualityCallback { lastUplinkQuality = it }
    private val downlinkQualityCallback = SignalQualityCallback { lastDownlinkQuality = it }
    private val videoDataRateCallback =
        dji.sdk.airlink.OcuSyncLink.VideoDataRateCallback { lastVideoDataRateMbps = it }
    private val cameraStateCallback = SystemState.Callback {
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

    private val tick = object : Runnable {
        override fun run() {
            try {
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
            // TODO: resolve the real aircraft serial (BaseProduct.getSerialNumber) as a
            // stable per-aircraft uid, matching V5's approach. Deferred — droneUid falls
            // back to the caller-provided session uid, which is enough for a live PLI.
        }

        handler.post(tick)
        AppLog.i(TAG, "DroneTakBridge started ($droneCallsign / $droneUid, every ${intervalMs}ms)")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
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
        tak.sendDronePLI(droneUid, droneCallsign, lat, lon, hae, heading, speed, battery,
            videoUrl, spiUid,
            sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange, 0.0,
            0.0, gimbalPitch, gimbalYaw,
            isFlying, flightTimeSec,
            batteryMaxMah, batteryRemainMah, voltage)
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

        /** Flight-readiness snapshots. Separate from [TAG] on purpose — [TAG] is in AppLog's
         *  TAK_TAGS and disappears when an operator filters TAK logging off, which is exactly
         *  when they're diagnosing something and need this most. See [logReadinessIfChanged]. */
        private const val READY_TAG = "TP2Ready"

        /** Gimbal pitch-rate multiplier applied on connect — see [applyPitchSpeed]. 1.5 = the
         *  requested +50%. Applied to whatever the aircraft currently reports and clamped to its
         *  own advertised range, so this stays meaningful across airframes rather than encoding
         *  one model's units. */
        private const val PITCH_SPEED_BOOST = 1.5

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

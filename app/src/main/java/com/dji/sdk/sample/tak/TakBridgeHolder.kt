package com.dji.sdk.sample.tak

/** Process-wide holder so the bridge survives screen navigation. */
object TakBridgeHolder {
    // Declared first: the FOV properties below initialise from these, and Kotlin resolves
    // object properties in declaration order.
    const val DEFAULT_HFOV = 73.0
    const val DEFAULT_VFOV = 45.0
    const val MIN_FOV = 5.0
    const val MAX_FOV = 170.0

    private var bridge: DroneTakBridge? = null
    // Remembered so it survives bridge restarts (reconnect) and a start-before-connect order.
    private var videoUrl: String? = null
    private var cameraPointEnabled = false
    private var zoomFactor: Double = 1.0

    // Calibrated camera field of view (degrees at 1x). Defaults are the published Mini 2 specs
    // (83 deg diagonal on a 16:9 frame); the real lens is whatever it is, which is what the 6D-D
    // calibration measures. Held here so the published FOV cone and the AR projection always
    // read the same numbers.
    private var hFovBase: Double = DEFAULT_HFOV
    private var vFovBase: Double = DEFAULT_VFOV

    fun start(droneUid: String, droneCallsign: String) {
        bridge?.stop()
        bridge = DroneTakBridge(droneUid, droneCallsign).also {
            it.videoUrl = videoUrl
            it.cameraPointEnabled = cameraPointEnabled
            it.zoomFactor = zoomFactor
            it.start()
        }
    }

    fun stop() {
        bridge?.stop()
        bridge = null
    }

    /** Advertise (or clear) the video URL in the drone CoT. Null/empty removes it. */
    fun setVideoUrl(url: String?) {
        videoUrl = url?.takeIf { it.isNotBlank() }
        bridge?.videoUrl = videoUrl
    }

    /** Enable/disable the live camera slant-point (sensor point of interest) marker. */
    fun setCameraPointEnabled(enabled: Boolean) {
        cameraPointEnabled = enabled
        bridge?.cameraPointEnabled = enabled
    }

    val isCameraPointEnabled: Boolean get() = cameraPointEnabled

    /**
     * Current digital zoom (1.0 = none), set by the flight screen's zoom control.
     *
     * Held here rather than only in the Activity because TWO things depend on it and must not
     * disagree: the FOV cone published to other TAK clients, and the AR overlay's projection.
     * Zooming crops the camera's angular width, so a fixed 1x FOV puts every AR marker at
     * roughly half its correct offset from centre at 2x — which is exactly how this surfaced.
     */
    fun setZoomFactor(factor: Double) {
        zoomFactor = if (factor.isFinite() && factor >= 1.0) factor else 1.0
        bridge?.zoomFactor = zoomFactor
    }

    val currentZoomFactor: Double get() = zoomFactor

    /**
     * Set the calibrated 1x field of view. Clamped to sane bounds so a mis-tap can't drive the
     * projection somewhere absurd — an FOV near zero sends every marker to infinity.
     */
    fun setFovBase(hDeg: Double, vDeg: Double) {
        hFovBase = hDeg.coerceIn(MIN_FOV, MAX_FOV)
        vFovBase = vDeg.coerceIn(MIN_FOV, MAX_FOV)
    }

    val currentHFovBase: Double get() = hFovBase
    val currentVFovBase: Double get() = vFovBase


    val isRunning: Boolean get() = bridge != null

    /** Ground point the camera is currently aimed at (for the drop-marker-at-look-point hot key),
     *  or null if the bridge isn't running / GPS+gimbal aren't ready. */
    fun lookPoint(): Triple<Double, Double, Double>? = bridge?.lookPoint()

    /** Latest telemetry snapshot for the on-screen HUD, or null if the bridge isn't running. */
    fun hud(): DroneTakBridge.Hud? = bridge?.hud()

    /** See [DroneTakBridge.photoInProgress]. False when the bridge isn't running — with no camera
     *  state to consult, a caller waiting on this must proceed rather than block forever. */
    fun photoInProgress(): Boolean = bridge?.photoInProgress() ?: false

    /** Camera bearing + pitch for the AR overlay's projection — the same model that places
     *  dropped markers, see [DroneTakBridge.cameraPose]. Null until GPS/gimbal are ready. */
    fun cameraPose(): DroneTakBridge.CameraPose? = bridge?.cameraPose()

    /** See [DroneTakBridge.isOwnPublishedUid]. False when the bridge isn't running — nothing is
     *  being published then, so nothing coming back can be ours. */
    fun isOwnPublishedUid(candidate: String?): Boolean =
        bridge?.isOwnPublishedUid(candidate) ?: false
}

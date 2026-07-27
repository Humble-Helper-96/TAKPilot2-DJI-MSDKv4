package com.dji.sdk.sample.tak

/** Process-wide holder so the bridge survives screen navigation. */
object TakBridgeHolder {
    private var bridge: DroneTakBridge? = null
    // Remembered so it survives bridge restarts (reconnect) and a start-before-connect order.
    private var videoUrl: String? = null
    private var cameraPointEnabled = false

    fun start(droneUid: String, droneCallsign: String) {
        bridge?.stop()
        bridge = DroneTakBridge(droneUid, droneCallsign).also {
            it.videoUrl = videoUrl
            it.cameraPointEnabled = cameraPointEnabled
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

    val isRunning: Boolean get() = bridge != null

    /** Ground point the camera is currently aimed at (for the drop-marker-at-look-point hot key),
     *  or null if the bridge isn't running / GPS+gimbal aren't ready. */
    fun lookPoint(): Triple<Double, Double, Double>? = bridge?.lookPoint()

    /** Latest telemetry snapshot for the on-screen HUD, or null if the bridge isn't running. */
    fun hud(): DroneTakBridge.Hud? = bridge?.hud()

    /** Camera bearing + pitch for the AR overlay's projection — the same model that places
     *  dropped markers, see [DroneTakBridge.cameraPose]. Null until GPS/gimbal are ready. */
    fun cameraPose(): DroneTakBridge.CameraPose? = bridge?.cameraPose()

    /** See [DroneTakBridge.isOwnPublishedUid]. False when the bridge isn't running — nothing is
     *  being published then, so nothing coming back can be ours. */
    fun isOwnPublishedUid(candidate: String?): Boolean =
        bridge?.isOwnPublishedUid(candidate) ?: false
}

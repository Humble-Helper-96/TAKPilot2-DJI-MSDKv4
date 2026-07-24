package com.dji.sdk.sample.tak

import android.content.Context

/**
 * PHASE 5 PLACEHOLDER.
 *
 * The real DroneVideoStreamer (ported from the V5 app) pushes the drone's live camera feed
 * out as RTSP via RootEncoder. On V5 it fed the camera stream in via
 * ICameraStreamManager.putCameraStreamSurface (a Surface sink); on V4 the equivalent raw
 * feed comes from VideoFeeder.getPrimaryVideoFeed().addVideoDataListener() delivering raw
 * H264 bytes, which is actually a cleaner integration point for RootEncoder than the V5
 * Surface workaround (see TAKPILOT2_V4_PORT_PLAN.md, Phase 5).
 *
 * [VideoConfig] itself has zero DJI dependency (just URL-building logic) and is copied
 * unchanged from the V5 app. Only start()/stop() are stubbed here — they report failure
 * via [onStatus] instead of actually streaming — so TakConnectActivity's video-config UI
 * (Phase 2) is fully testable before the real capture pipeline is wired in.
 */
class DroneVideoStreamer(
    private val context: Context,
    private val config: VideoConfig,
    private val onStatus: (Boolean, String) -> Unit,
) {
    data class VideoConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val streamId: String,
        val tcp: Boolean,
        val width: Int = 1280,
        val height: Int = 720,
        val fps: Int = 30,
        val bitrateBps: Int = 4_000_000,
        val rotation: Int = 90, // clockwise degrees to correct the DJI feed orientation
    ) {
        private fun path(): String = streamId.trim('/')
        fun pushUrl(): String = "rtsp://$host:$port/${path()}"
        fun advertiseUrl(): String {
            val cred = if (username.isNotEmpty()) "${enc(username)}:${enc(password)}@" else ""
            val q = if (tcp) "?tcp" else ""
            return "rtsp://$cred$host:$port/${path()}$q"
        }
        fun urlSafe(): String {
            val who = if (username.isNotEmpty()) "$username:***@" else ""
            val q = if (tcp) "?tcp" else ""
            return "rtsp://$who$host:$port/${path()}$q"
        }
        private fun enc(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }

    @Volatile var isStreaming = false
        private set

    fun start() {
        com.taklite.util.AppLog.w(
            "DroneVideoStreamer",
            "STUB: not streaming to ${config.urlSafe()} — Phase 5 not implemented yet",
        )
        onStatus(false, "Video push not implemented yet (Phase 5) — config saved.")
    }

    fun stop() {
        isStreaming = false
    }
}

object VideoStreamerHolder {
    private var streamer: DroneVideoStreamer? = null

    /** Notified on every start/stop so UI (e.g. the flight-screen play button) refreshes,
     *  regardless of what triggered the change (soft button, RC physical button, etc.). */
    @JvmField
    var onStateChanged: Runnable? = null
    private fun notifyState() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onStateChanged?.run() }
    }

    fun start(
        context: Context,
        config: DroneVideoStreamer.VideoConfig,
        onStatus: (Boolean, String) -> Unit,
    ) {
        streamer?.stop()
        streamer = DroneVideoStreamer(context.applicationContext, config, onStatus).also { it.start() }
        notifyState()
    }

    fun stop() {
        streamer?.stop()
        streamer = null
        TakBridgeHolder.setVideoUrl(null)
        notifyState()
    }

    val isRunning: Boolean get() = streamer?.isStreaming == true
    val isActive: Boolean get() = streamer != null

    /**
     * Start streaming using the video settings saved by TakConnectActivity. Returns false
     * if no stream is configured. Used by the flight-screen Start Video button.
     */
    fun startFromPrefs(context: Context, onStatus: (Boolean, String) -> Unit): Boolean {
        val p = context.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val streamId = p.getString("video_streamid", "") ?: ""
        if (host.isEmpty() || streamId.isEmpty()) return false
        val cfg = DroneVideoStreamer.VideoConfig(
            host = host,
            port = p.getInt("video_port", 8554),
            username = p.getString("video_user", "") ?: "",
            password = p.getString("video_pass", "") ?: "",
            streamId = streamId,
            tcp = p.getBoolean("video_tcp", true),
        )
        start(context, cfg) { ok, msg ->
            if (ok) TakBridgeHolder.setVideoUrl(cfg.advertiseUrl())
            onStatus(ok, msg)
        }
        return true
    }
}

package com.dji.sdk.sample.tak

import android.content.Context
import android.media.MediaCodec
import com.taklite.util.AppLog
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtspClient
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import dji.sdk.camera.VideoFeeder
import java.nio.ByteBuffer

/**
 * DroneVideoStreamer — Phase 5: RTSP push of the Mini 2's live camera feed, passthrough mode
 * (no re-encode — the aircraft's already-encoded H.264 bytes go straight to the RTSP client).
 *
 * Architecture ported from the Autel sibling app's `AutelVideoStreamer` (same
 * com.pedro.rtsp client — see NOTICE.txt for why it's vendored source here instead of a
 * Gradle dependency), which was itself a port of this app's own V5 `DroneVideoStreamer`.
 * V4's `VideoFeeder` hands us raw encoded H.264 bytes exactly like Autel's SDK does — V5's
 * decode-surface re-encode workaround is not needed here.
 *
 * The one real DJI-V4-specific delta: VideoFeeder delivers ~2KB transport chunks, NOT
 * NAL-aligned frames (Autel's SDK handed over whole frames) — [AnnexBNalAssembler] (a
 * deliberate standalone duplicate of [FpvTextureView]'s proven reassembly logic — see that
 * class's doc) turns the raw stream into whole NALs before anything here inspects them.
 *
 * Registers its OWN [VideoFeeder.VideoDataListener] on the primary feed — VideoFeeder
 * supports multiple simultaneous listeners, so the on-screen FPV display
 * ([FpvTextureView]) is completely untouched by this running or not.
 */
class DroneVideoStreamer(
    private val context: Context,
    private val config: VideoConfig,
    private val onStatus: (Boolean, String) -> Unit,
) : ConnectCheckerRtsp {

    data class VideoConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val streamId: String,
        val tcp: Boolean,
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

    private val client = RtspClient(this)
    private val assembler = AnnexBNalAssembler { nal, type -> onNal(nal, type) }
    private var videoListener: VideoFeeder.VideoDataListener? = null

    @Volatile private var streaming = false
    @Volatile private var paramsSet = false
    @Volatile private var stopped = false
    private var startNs = 0L
    private var frameCount = 0
    private var frameBytesSinceLog = 0L

    // Sniffed parameter sets (kept WITH their 4-byte start code; RootEncoder strips them).
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    // Bootstrap: the Mini 2 sends no SPS/PPS/IDR unprompted, and — field-proven 2026-07-24 —
    // the ONLY reliable way to make it emit one is to resync the on-screen FPV decoder
    // ([IdrRequesterHolder.requestFreshKeyframe] via the FPV hook); the direct dormant-lever
    // calls this used to make are silently ignored whenever FPV is already up. The keyframe
    // that resync produces flows into the shared VideoFeeder stream, so our own listener
    // sniffs the SPS/PPS and connects. Re-requested every few seconds until that happens —
    // spaced generously because each FPV resync itself takes ~0.6-3s to land.
    private val bootstrapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val bootstrapRunnable = object : Runnable {
        override fun run() {
            if (stopped || paramsSet) return
            IdrRequesterHolder.requestFreshKeyframe()
            bootstrapHandler.postDelayed(this, 3000)
        }
    }

    val isStreaming: Boolean get() = streaming

    fun start() {
        val feed = VideoFeeder.getInstance()?.primaryVideoFeed
        if (feed == null) {
            onStatus(false, "Aircraft not connected (no video source)")
            return
        }
        stopped = false
        paramsSet = false
        assembler.reset()
        startNs = System.nanoTime()

        client.setLogs(false)
        client.setProtocol(if (config.tcp) Protocol.TCP else Protocol.UDP)
        if (config.username.isNotEmpty()) client.setAuthorization(config.username, config.password)
        client.setOnlyVideo(true)
        client.setReTries(10)

        val l = VideoFeeder.VideoDataListener { data, size ->
            if (!stopped) {
                try {
                    assembler.feed(data, size)
                } catch (t: Throwable) {
                    AppLog.w(TAG, "assembler feed failed: ${t.message}")
                }
            }
        }
        feed.addVideoDataListener(l)
        videoListener = l

        // Prime the keyframe request lever and start bootstrapping — connect() is deferred
        // until setVideoInfo() actually happens (see onNal), so RootEncoder's internal
        // "wait up to 5s for video info" never has anything to wait on the wrong thing for.
        IdrRequesterHolder.ensureStarted(context)
        bootstrapHandler.removeCallbacks(bootstrapRunnable)
        bootstrapHandler.post(bootstrapRunnable)

        AppLog.i(TAG, "push=${config.pushUrl()}  advertise=${config.urlSafe()}")
        onStatus(true, "Waiting for keyframe → ${config.urlSafe()}")
    }

    fun stop() {
        stopped = true
        bootstrapHandler.removeCallbacks(bootstrapRunnable)
        videoListener?.let {
            runCatching { VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(it) }
        }
        videoListener = null
        try { client.disconnect() } catch (t: Throwable) { AppLog.w(TAG, "disconnect: ${t.message}") }
        streaming = false
        paramsSet = false
        sps = null; pps = null
    }

    // ---- Frame path ----

    private fun onNal(nal: ByteArray, type: Int) {
        if (stopped) return
        try {
            when (type) {
                7 -> sps = nal
                8 -> pps = nal
            }
            if (!paramsSet) {
                val s = sps; val p = pps
                if (s != null && p != null) {
                    paramsSet = true
                    bootstrapHandler.removeCallbacks(bootstrapRunnable)
                    client.setVideoInfo(ByteBuffer.wrap(s), ByteBuffer.wrap(p), null)
                    AppLog.i(TAG, "parameter sets found; sps=${s.size}B pps=${p.size}B — connecting")
                    client.connect(config.pushUrl())
                } else {
                    return // keep waiting for both SPS and PPS
                }
            }

            // Don't forward the parameter-set NALs themselves as video frames.
            if (type == 7 || type == 8) return

            val info = MediaCodec.BufferInfo()
            val ptsUs = (System.nanoTime() - startNs) / 1000
            info.set(0, nal.size, ptsUs, if (type == 5) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            client.sendVideo(ByteBuffer.wrap(nal), info)

            frameCount++
            frameBytesSinceLog += nal.size
            if (frameCount % 150 == 0) {
                AppLog.v(TAG, "video: $frameCount frames pushed, ${frameBytesSinceLog / 1024}KB in last 150")
                frameBytesSinceLog = 0
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "frame push failed: ${t.message}")
        }
    }

    // ---- ConnectCheckerRtsp ----

    override fun onConnectionStartedRtsp(rtspUrl: String) { AppLog.i(TAG, "connecting ${config.urlSafe()}") }
    override fun onConnectionSuccessRtsp() {
        streaming = true
        AppLog.i(TAG, "RTSP push connected — requesting fresh keyframe to arm the packetizer")
        onStatus(true, "Streaming → ${config.urlSafe()}")
        // Arm (and re-arm) RootEncoder's H264Packet.sendKeyFrame — on EVERY connect, first
        // included. connect() is async: the params-carrying IDR from the bootstrap burst was
        // sent (and silently discarded) while RtspSender.running was still false, so the
        // packetizer has no keyframe and drops every P-frame ("waiting for keyframe") forever
        // (the Mini 2 sends no IDR unprompted). Field-diagnosed 2026-07-24: MediaMTX showed
        // the SDP track online but got zero video and read-timed-out the publisher at ~30s.
        // Forcing a fresh keyframe NOW (running is true) makes the next IDR actually arm it.
        // Also covers reconnects, where RtspSender.stop() reset the same one-shot flag.
        IdrRequesterHolder.requestFreshKeyframe()
    }
    override fun onConnectionFailedRtsp(reason: String) {
        AppLog.w(TAG, "connection failed: $reason")
        if (!stopped && client.shouldRetry(reason)) {
            client.reConnect(2000)
        } else {
            streaming = false
            onStatus(false, "Stream failed: $reason")
        }
    }
    override fun onDisconnectRtsp() { streaming = false; AppLog.i(TAG, "disconnected") }
    override fun onAuthErrorRtsp() { streaming = false; onStatus(false, "Stream auth error (check user/pass)") }
    override fun onAuthSuccessRtsp() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrateRtsp(bitrate: Long) {}

    companion object {
        private const val TAG = "DroneVideoStreamer"
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

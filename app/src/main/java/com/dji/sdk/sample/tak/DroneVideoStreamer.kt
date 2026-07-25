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
 * DroneVideoStreamer — Phase 5: RTSP push of the Mini 2's live camera feed to a media server
 * (MediaMTX), with a pilot-selected quality [VideoConfig.profile]:
 *
 *  - "original" — PASSTHROUGH: the aircraft's already-encoded H.264 goes straight to the RTSP
 *    client, no re-encode. Max quality/lowest CPU, BUT the Mini 2 emits no periodic IDR, so
 *    remote viewers (ATAK) can't join mid-stream or recover from loss without a manual Video
 *    Re-Sync (field-measured 112s keyframe gaps, 2026-07-25). Best only on a rock-solid link.
 *  - "low" / "standard" / "high" — TRANSCODE via [StreamTranscoder]: decode on device and
 *    re-encode at the profile's resolution/fps/bitrate with a fixed 2s IDR interval. The
 *    keyframe cadence is now under OUR control, so remote viewers join and self-heal within
 *    ~2s, and the RTSP packetizer is armed by our own encoder (no FPV glitch on connect).
 *    Standard is the default.
 *
 * Architecture ported from the Autel sibling app (same vendored com.pedro.rtsp client — see
 * NOTICE.txt). The DJI-V4 delta: VideoFeeder delivers ~2KB transport chunks, not NAL-aligned
 * frames — [AnnexBNalAssembler] reassembles whole NALs first. Registers its OWN VideoFeeder
 * listener (VideoFeeder supports multiple), so on-screen FPV ([FpvTextureView]) is untouched.
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
        val profile: String = "standard",   // "original" | "low" | "standard" | "high"
    ) {
        val isTranscode: Boolean get() = profile != "original"
        // Transcoded output is published to a "-Low" path (e.g. Feed-A -> Feed-A-Low): it
        // tells the media server this stream is ALREADY reduced/keyframed, so it passes it
        // through to clients instead of running its own transcode on it. Flows through
        // push/advertise/preview URLs alike since they all build on path().
        private fun path(): String = streamId.trim('/') + if (isTranscode) "-Low" else ""
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
    private var transcoder: StreamTranscoder? = null

    @Volatile private var streaming = false
    @Volatile private var paramsSet = false
    @Volatile private var stopped = false
    private var startNs = 0L
    private var frameCount = 0
    private var frameBytesSinceLog = 0L

    // Passthrough-only: sniffed source parameter sets (WITH 4-byte start code; the client's
    // packetizer handles them). Unused in transcode mode (the encoder makes its own).
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    // Bootstrap: the Mini 2 sends no SPS/PPS/IDR unprompted, and the ONLY reliable way to make
    // it emit one is to resync the on-screen FPV decoder (IdrRequesterHolder.requestFreshKeyframe
    // via the FPV hook — field-proven 2026-07-24). That source keyframe is what BOTH modes need
    // to get going: passthrough sniffs its SPS/PPS to connect; transcode needs it to start its
    // own decoder. Re-requested every few seconds until we've connected (paramsSet) — spaced
    // generously because each FPV resync itself takes ~0.6-3s to land.
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

        if (config.isTranscode) {
            transcoder = StreamTranscoder(
                profile = StreamTranscoder.TranscodeProfile.fromPref(config.profile),
                isHevc = false,   // Mini 2 is H.264
                onEncoded = { buf, info -> onEncodedFrame(buf, info) },
                onParamsReady = { s, p -> onEncoderParamsReady(s, p) },
            )
        }

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

        IdrRequesterHolder.ensureStarted(context)
        bootstrapHandler.removeCallbacks(bootstrapRunnable)
        bootstrapHandler.post(bootstrapRunnable)

        AppLog.i(TAG, "start [${config.profile}] push=${config.pushUrl()}  advertise=${config.urlSafe()}")
        onStatus(true, "Waiting for keyframe → ${config.urlSafe()}")
    }

    fun stop() {
        stopped = true
        bootstrapHandler.removeCallbacks(bootstrapRunnable)
        videoListener?.let {
            runCatching { VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(it) }
        }
        videoListener = null
        transcoder?.release()
        transcoder = null
        try { client.disconnect() } catch (t: Throwable) { AppLog.w(TAG, "disconnect: ${t.message}") }
        streaming = false
        paramsSet = false
        sps = null; pps = null
    }

    // ---- Frame path: source NALs from the assembler (DJI video-feed thread) ----

    private fun onNal(nal: ByteArray, type: Int) {
        if (stopped) return
        if (config.isTranscode) {
            // Feed every NAL (incl. SPS/PPS/IDR) into the transcoder's decoder; it produces the
            // outbound stream via onEncoded/onEncoderParamsReady. isIFrame is informational.
            transcoder?.submit(nal, type == 5)
            return
        }
        // ---- Passthrough (original) ----
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
                    AppLog.i(TAG, "source params found; sps=${s.size}B pps=${p.size}B — connecting")
                    client.connect(config.pushUrl())
                } else {
                    return // keep waiting for both SPS and PPS
                }
            }
            if (type == 7 || type == 8) return // don't forward parameter-set NALs as frames

            val info = MediaCodec.BufferInfo()
            val ptsUs = (System.nanoTime() - startNs) / 1000
            info.set(0, nal.size, ptsUs, if (type == 5) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            client.sendVideo(ByteBuffer.wrap(nal), info)
            countFrame(nal.size)
        } catch (t: Throwable) {
            AppLog.w(TAG, "frame push failed: ${t.message}")
        }
    }

    // ---- Transcode path: re-encoded output from StreamTranscoder (its own thread) ----

    private fun onEncoderParamsReady(s: ByteBuffer, p: ByteBuffer) {
        if (stopped || paramsSet) return
        paramsSet = true
        bootstrapHandler.removeCallbacks(bootstrapRunnable)
        try {
            client.setVideoInfo(s, p, null)
            AppLog.i(TAG, "encoder params ready — connecting")
            client.connect(config.pushUrl())
        } catch (t: Throwable) {
            AppLog.w(TAG, "transcode connect failed: ${t.message}")
        }
    }

    private fun onEncodedFrame(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped) return
        try {
            client.sendVideo(buf, info)
            countFrame(info.size)
        } catch (t: Throwable) {
            AppLog.w(TAG, "encoded frame push failed: ${t.message}")
        }
    }

    private fun countFrame(size: Int) {
        frameCount++
        frameBytesSinceLog += size
        if (frameCount % 150 == 0) {
            AppLog.v(TAG, "video: $frameCount frames pushed, ${frameBytesSinceLog / 1024}KB in last 150")
            frameBytesSinceLog = 0
        }
    }

    // ---- ConnectCheckerRtsp ----

    override fun onConnectionStartedRtsp(rtspUrl: String) { AppLog.i(TAG, "connecting ${config.urlSafe()}") }
    override fun onConnectionSuccessRtsp() {
        streaming = true
        onStatus(true, "Streaming → ${config.urlSafe()}")
        // Arm (and re-arm) RootEncoder's one-shot H264Packet.sendKeyFrame flag on EVERY connect.
        // connect() is async, so the first keyframe was sent while RtspSender.running was still
        // false and got discarded; without re-arming, the packetizer drops every P-frame forever
        // ("waiting for keyframe"). In TRANSCODE mode ask our own encoder for an IDR (instant, no
        // FPV disturbance); in PASSTHROUGH ask the aircraft via the FPV resync (glitches FPV).
        if (config.isTranscode) {
            AppLog.i(TAG, "connected — requesting encoder sync frame to arm the packetizer")
            transcoder?.requestSyncFrame()
        } else {
            AppLog.i(TAG, "connected — requesting fresh source keyframe to arm the packetizer")
            IdrRequesterHolder.requestFreshKeyframe()
        }
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
            profile = p.getString("video_profile", "standard") ?: "standard",
        )
        start(context, cfg) { ok, msg ->
            if (ok) TakBridgeHolder.setVideoUrl(cfg.advertiseUrl())
            onStatus(ok, msg)
        }
        return true
    }
}

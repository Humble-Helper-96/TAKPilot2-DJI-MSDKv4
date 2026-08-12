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
    private val mediaProjection: android.media.projection.MediaProjection? = null,
    // Fired once when a reconnect window (see RECONNECT_MAX_MS) expires without success: the
    // stream and capture/projection have already been torn down internally by the time this
    // fires, so the caller (VideoStreamerHolder) just needs to drop its reference and clean up
    // anything it owns (the foreground service).
    private val onGiveUp: () -> Unit = {},
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
        /**
         * The URL preview shown in Pre-Flight, with the password masked.
         *
         * An EMPTY password reads "(NO PASSWORD)" rather than the same `***` a real one gets.
         * Masking both identically meant the preview — the one place a pilot would check — could
         * not answer the question it exists to answer, and a blank password looked exactly like a
         * correct one. That mattered because the password really was being erased on every visit
         * to this screen; see the restore line in TakConnectActivity.setupVideoControls.
         */
        fun urlSafe(): String {
            val who = when {
                username.isEmpty() -> ""
                password.isEmpty() -> "$username:(NO PASSWORD)@"
                else -> "$username:***@"
            }
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
    private var screenEncoder: ScreenCaptureEncoder? = null
    // Screen-capture mode = a transcode profile WITH a MediaProjection (the flight-screen
    // path). Captures the whole flight screen (FPV + HUD) rather than re-decoding the aircraft
    // feed — cheaper, and structurally free of the decode-transcoder's NAL-drop artifacting
    // (see ScreenCaptureEncoder). Transcode profile WITHOUT a projection falls back to the
    // StreamTranscoder decode path (kept as an internal fallback).
    private val screenMode: Boolean get() = config.isTranscode && mediaProjection != null

    @Volatile private var streaming = false
    @Volatile private var paramsSet = false
    @Volatile private var stopped = false
    private var startNs = 0L
    private var frameCount = 0
    private var frameBytesSinceLog = 0L

    // ---- Auto-reconnect with backoff (network drops, server restarts, etc.) ----
    // A dropped connection does NOT tear down the encoder/projection immediately — the capture
    // keeps running (frames are simply not sent) so a transient blip doesn't cost a fresh
    // permission grant. Only if RECONNECT_MAX_MS elapses without a successful reconnect do we
    // give up for real and release everything (see handleConnectionDropped/onGiveUp).
    @Volatile var isReconnecting: Boolean = false
        private set
    private var reconnectStartNs = 0L
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

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
        stopped = false
        paramsSet = false
        startNs = System.nanoTime()

        client.setLogs(false)
        client.setProtocol(if (config.tcp) Protocol.TCP else Protocol.UDP)
        if (config.username.isNotEmpty()) client.setAuthorization(config.username, config.password)
        client.setOnlyVideo(true)
        // Our own handleConnectionDropped() backoff loop is authoritative on when to give up
        // (RECONNECT_MAX_MS wall-clock, not attempt count) — set this high so the library's own
        // internal reTries counter (decremented by every client.reConnect() call) never becomes
        // the limiting factor first.
        client.setReTries(1000)

        if (screenMode) {
            // Screen-capture: no aircraft-feed listener, no NAL assembler, no keyframe
            // bootstrap. The encoder produces frames from the composited screen immediately;
            // params-ready connects, and the encoder's own sync frame arms the packetizer.
            val enc = ScreenCaptureEncoder(
                context, mediaProjection!!,
                StreamTranscoder.TranscodeProfile.fromPref(config.profile),
                onEncoded = { buf, info -> onEncodedFrame(buf, info) },
                onParamsReady = { s, p -> onEncoderParamsReady(s, p) },
            )
            if (!enc.start()) {
                onStatus(false, "Screen capture failed to start")
                return
            }
            screenEncoder = enc
            AppLog.i(TAG, "start [${config.profile}, screen] push=${config.pushUrl()}")
            onStatus(true, "Capturing screen → ${config.urlSafe()}")
            return
        }

        // ---- Aircraft-feed modes (passthrough, or decode-transcode fallback) ----
        val feed = VideoFeeder.getInstance()?.primaryVideoFeed
        if (feed == null) {
            onStatus(false, "Aircraft not connected (no video source)")
            return
        }
        assembler.reset()

        if (config.isTranscode) {
            transcoder = StreamTranscoder(
                profile = StreamTranscoder.TranscodeProfile.fromPref(config.profile),
                isHevc = false,   // Mini 2 is H.264
                onEncoded = { buf, info -> onEncodedFrame(buf, info) },
                onParamsReady = { s, p -> onEncoderParamsReady(s, p) },
            )
        }

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
        if (stopped) return
        stopped = true
        isReconnecting = false
        releaseInternal()
    }

    /** Shared teardown for an explicit [stop] and a give-up-after-timeout. Idempotent-ish via
     *  the [stopped] guard in callers; safe to call once. */
    private fun releaseInternal() {
        bootstrapHandler.removeCallbacks(bootstrapRunnable)
        videoListener?.let {
            runCatching { VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(it) }
        }
        videoListener = null
        transcoder?.release()
        transcoder = null
        screenEncoder?.release()
        screenEncoder = null
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
        if (isReconnecting) {
            AppLog.i(TAG, "reconnected after ${(System.nanoTime() - reconnectStartNs) / 1_000_000}ms")
            isReconnecting = false
        }
        onStatus(true, "Streaming → ${config.urlSafe()}")
        // Arm (and re-arm) RootEncoder's one-shot H264Packet.sendKeyFrame flag on EVERY connect.
        // connect() is async, so the first keyframe was sent while RtspSender.running was still
        // false and got discarded; without re-arming, the packetizer drops every P-frame forever
        // ("waiting for keyframe"). In TRANSCODE mode ask our own encoder for an IDR (instant, no
        // FPV disturbance); in PASSTHROUGH ask the aircraft via the FPV resync (glitches FPV).
        when {
            screenMode -> {
                AppLog.i(TAG, "connected — requesting screen-encoder sync frame to arm the packetizer")
                screenEncoder?.requestSyncFrame()
            }
            config.isTranscode -> {
                AppLog.i(TAG, "connected — requesting encoder sync frame to arm the packetizer")
                transcoder?.requestSyncFrame()
            }
            else -> {
                AppLog.i(TAG, "connected — requesting fresh source keyframe to arm the packetizer")
                IdrRequesterHolder.requestFreshKeyframe()
            }
        }
    }
    override fun onConnectionFailedRtsp(reason: String) {
        AppLog.w(TAG, "connection failed: $reason")
        handleConnectionDropped(reason)
    }
    override fun onDisconnectRtsp() {
        AppLog.i(TAG, "disconnected")
        handleConnectionDropped("disconnected")
    }

    /** Entry point for every "the RTSP link just died" event (failed connect attempt, or a
     *  live connection dropping). Drives the backoff loop; see the reconnect fields above. */
    private fun handleConnectionDropped(reason: String) {
        if (stopped) return
        streaming = false
        if (reason.contains("Endpoint malformed") || reason.contains("access denied")) {
            // Not a transient network problem — a config error retrying won't fix. Give up now.
            AppLog.w(TAG, "non-retryable failure ($reason) — giving up immediately")
            giveUp("Stream failed: $reason")
            return
        }
        val now = System.nanoTime()
        if (!isReconnecting) {
            isReconnecting = true
            reconnectStartNs = now
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            AppLog.w(TAG, "video connection lost ($reason) — reconnecting, capture stays live")
            onStatus(false, "Video connection lost — reconnecting…")
        }
        val elapsedMs = (now - reconnectStartNs) / 1_000_000
        if (elapsedMs >= RECONNECT_MAX_MS) {
            AppLog.w(TAG, "no reconnect after ${elapsedMs}ms — giving up, stopping stream + capture")
            giveUp("Video stream failed — stopped after 60s")
            return
        }
        AppLog.i(TAG, "reconnect attempt in ${reconnectDelayMs}ms (elapsed ${elapsedMs}ms, reason=$reason)")
        client.reConnect(reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private fun giveUp(statusMsg: String) {
        stopped = true
        isReconnecting = false
        releaseInternal()
        onStatus(false, statusMsg)
        onGiveUp()
    }
    override fun onAuthErrorRtsp() { streaming = false; onStatus(false, "Stream auth error (check user/pass)") }
    override fun onAuthSuccessRtsp() { AppLog.i(TAG, "auth ok") }
    override fun onNewBitrateRtsp(bitrate: Long) {}

    companion object {
        private const val TAG = "DroneVideoStreamer"
        private const val RECONNECT_MAX_MS = 60_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}

object VideoStreamerHolder {
    private var streamer: DroneVideoStreamer? = null
    private var appContext: Context? = null

    /** Notified on every start/stop AND on every connection-state change so UI (e.g. the
     *  flight-screen LIVE pill) reflects real streaming state, not just the start/stop call. */
    @JvmField
    var onStateChanged: Runnable? = null
    private fun notifyState() {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onStateChanged?.run() }
    }

    private fun buildConfig(context: Context): DroneVideoStreamer.VideoConfig? {
        val p = context.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
        val host = p.getString("video_host", "") ?: ""
        val streamId = p.getString("video_streamid", "") ?: ""
        if (host.isEmpty() || streamId.isEmpty()) return null
        return DroneVideoStreamer.VideoConfig(
            host = host,
            port = p.getInt("video_port", 8554),
            username = p.getString("video_user", "") ?: "",
            password = p.getString("video_pass", "") ?: "",
            streamId = streamId,
            tcp = p.getBoolean("video_tcp", true),
            profile = p.getString("video_profile", "standard") ?: "standard",
        )
    }

    /** Wraps the caller's onStatus so every status change (incl. the async connect-success
     *  that flips isStreaming true) also refreshes the LIVE pill and advertises the CoT URL. */
    private fun launch(
        context: Context,
        config: DroneVideoStreamer.VideoConfig,
        projection: android.media.projection.MediaProjection?,
        onStatus: (Boolean, String) -> Unit,
    ) {
        appContext = context.applicationContext
        streamer?.stop()
        streamer = DroneVideoStreamer(
            context.applicationContext, config, projection,
            onGiveUp = {
                // Reconnect window expired — DroneVideoStreamer already released its own
                // encoder/transcoder/client; our job is to drop the reference and tear down
                // the foreground service + projection it doesn't own.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    AppLog.w("VideoStreamerHolder", "reconnect window expired — stopping capture")
                    streamer = null
                    TakBridgeHolder.setVideoUrl(null)
                    appContext?.let { ScreenCaptureService.stop(it) }
                    notifyState()
                }
            },
        ) { ok, msg ->
            if (ok) TakBridgeHolder.setVideoUrl(config.advertiseUrl())
            notifyState()
            onStatus(ok, msg)
        }.also { it.start() }
        notifyState()
    }

    fun start(
        context: Context,
        config: DroneVideoStreamer.VideoConfig,
        onStatus: (Boolean, String) -> Unit,
    ) = launch(context, config, null, onStatus)

    fun stop() {
        streamer?.stop()
        streamer = null
        TakBridgeHolder.setVideoUrl(null)
        // Tear down the screen-capture foreground service + projection if one was running.
        appContext?.let { ScreenCaptureService.stop(it) }
        notifyState()
    }

    val isRunning: Boolean get() = streamer?.isStreaming == true
    val isActive: Boolean get() = streamer != null
    val isReconnecting: Boolean get() = streamer?.isReconnecting == true

    /**
     * Start streaming using the video settings saved by TakConnectActivity, with a
     * MediaProjection (screen-capture transcode). Returns false if no stream is configured.
     */
    fun startScreenCapture(
        context: Context,
        projection: android.media.projection.MediaProjection,
        onStatus: (Boolean, String) -> Unit,
    ): Boolean {
        val cfg = buildConfig(context) ?: return false
        launch(context, cfg, projection, onStatus)
        return true
    }

    /**
     * Start streaming using saved settings, no projection (passthrough, or the decode-transcode
     * fallback). Returns false if no stream is configured.
     */
    fun startFromPrefs(context: Context, onStatus: (Boolean, String) -> Unit): Boolean {
        val cfg = buildConfig(context) ?: return false
        launch(context, cfg, null, onStatus)
        return true
    }
}

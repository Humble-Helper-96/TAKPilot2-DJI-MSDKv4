package com.dji.sdk.sample.tak

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import com.taklite.util.AppLog
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

/**
 * On-device transcoder for the outbound RTSP push: decodes the aircraft's native H.264
 * downlink and re-encodes it as a smaller H.264 stream shaped by a pilot-selected
 * [TranscodeProfile], entirely on its own background thread. The pilot's on-screen FPV
 * ([com.dji.sdk.sample.takpilot2.FpvTextureView]) is untouched — it has its own decoder on
 * the same shared VideoFeeder stream.
 *
 * Ported from the Autel sibling app's LowBandwidthTranscoder (see its
 * TAKPilot2-LowBandwidthVideo-DevNotes.md), with the DJI-specific deltas:
 *  - Input is per-NAL (from [AnnexBNalAssembler]), not per-frame — V4's VideoFeeder has no
 *    frame framing. One NAL per decoder input buffer, exactly like the proven FPV pipeline.
 *  - Profile-parameterized (max height / fps / bitrate) instead of a single hardcoded
 *    480p/15/600k mode — see [TranscodeProfile].
 *  - [requestSyncFrame]: asks OUR encoder for an immediate IDR (MediaCodec
 *    PARAMETER_KEY_REQUEST_SYNC_FRAME). This is the whole reason on-device transcode wins:
 *    keyframes for the outbound stream are under our control (arming the RTSP packetizer on
 *    connect, healing viewers) without the aircraft's cooperation and without glitching FPV.
 *  - Crop-aware source dimensions (Exynos decoders report coded size + crop rect; the FPV
 *    pipeline already handles this the same way).
 *
 * Encoder keyframe interval is fixed at 2s — the self-healing property that makes remote
 * viewers (ATAK) able to join mid-stream and recover from loss within ~2s, which the raw
 * passthrough feed could never do (field-measured 112s keyframe gaps, 2026-07-25).
 *
 * Best-effort throughout: any failure drops frames rather than taking the stream down.
 */
class StreamTranscoder(
    private val profile: TranscodeProfile,
    private val isHevc: Boolean,
    private val onEncoded: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onParamsReady: (sps: ByteBuffer, pps: ByteBuffer) -> Unit,
) {
    /** Pilot-selectable outbound quality (Pre-Flight Setup §4). Aspect ratio is always
     *  preserved — [maxHeight] caps the vertical resolution, width follows the source. */
    enum class TranscodeProfile(val maxHeight: Int, val fps: Int, val bitrateBps: Int) {
        LOW(360, 10, 275_000),        // maximum survivability on marginal links
        STANDARD(480, 15, 550_000),   // default — ~2x Low's bitrate, noticeably better
        HIGH(720, 15, 1_000_000);     // ~2x again, plus higher resolution

        companion object {
            fun fromPref(name: String?): TranscodeProfile = when (name) {
                "low" -> LOW
                "high" -> HIGH
                else -> STANDARD
            }
        }
    }

    private val thread = HandlerThread("StreamTranscoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val queue = ArrayBlockingQueue<QueuedNal>(QUEUE_CAPACITY)

    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    @Volatile private var released = false
    private var lastForwardedNs = 0L
    private var encFrameCount = 0
    private var encBytesSinceLog = 0L
    private val frameIntervalNs = 1_000_000_000L / profile.fps

    private class QueuedNal(val bytes: ByteArray, val isIFrame: Boolean)

    /** Called from the assembler on the SDK's frame-delivery thread — hands off, never blocks.
     *  [nal] must be a caller-owned array (the assembler allocates fresh ones), NOT reused. */
    fun submit(nal: ByteArray, isIFrame: Boolean) {
        if (released) return
        if (!queue.offer(QueuedNal(nal, isIFrame))) {
            queue.poll()           // queue full: drop the oldest pending NAL, not the newest
            queue.offer(QueuedNal(nal, isIFrame))
        }
        handler.post { runCatching { processQueue() }.onFailure { AppLog.w(TAG, "transcode error: ${it.message}") } }
    }

    /** Ask our encoder to emit an IDR immediately (next frame). Used to arm the RTSP
     *  packetizer on connection success and to heal remote viewers on demand — no aircraft
     *  round-trip, no FPV disturbance. */
    fun requestSyncFrame() {
        handler.post {
            runCatching {
                encoder?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
                AppLog.i(TAG, "sync frame requested from encoder")
            }.onFailure { AppLog.w(TAG, "requestSyncFrame failed: ${it.message}") }
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            decoder = null; encoder = null
        }
        thread.quitSafely()
    }

    // ---- Pipeline (all on the handler thread) ----

    private fun processQueue() {
        if (released) return
        ensureDecoder()
        var item = queue.poll()
        while (item != null) {
            decodeOne(item)
            item = queue.poll()
        }
        drainDecoder()
        drainEncoder()
    }

    private fun ensureDecoder() {
        if (decoder != null) return
        val mime = if (isHevc) "video/hevc" else "video/avc"
        // Placeholder dimensions — corrected via INFO_OUTPUT_FORMAT_CHANGED once the decoder
        // parses the real SPS out of the inline Annex-B NALs we feed it (proven to work on
        // this exact phone by the FPV pipeline, which does the same csd-less configure).
        val format = MediaFormat.createVideoFormat(mime, 1280, 720)
        decoder = MediaCodec.createDecoderByType(mime).apply {
            configure(format, null, null, 0)
            start()
        }
        AppLog.i(TAG, "decoder started ($mime), profile=${profile.name}")
    }

    private fun decodeOne(item: QueuedNal) {
        val dec = decoder ?: return
        val inIdx = dec.dequeueInputBuffer(10_000)
        if (inIdx < 0) return   // decoder backed up — drop this NAL, best-effort
        val inBuf = dec.getInputBuffer(inIdx) ?: return
        inBuf.clear()
        inBuf.put(item.bytes)
        dec.queueInputBuffer(inIdx, 0, item.bytes.size, System.nanoTime() / 1000, 0)
    }

    private fun drainDecoder() {
        val dec = decoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = dec.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = dec.outputFormat
                    // Prefer the crop rect — coded size can exceed visible size (e.g. 1088
                    // coded for 1080 visible); same handling as FpvTextureView.
                    val w = if (fmt.containsKey("crop-right"))
                        fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
                    else fmt.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (fmt.containsKey("crop-bottom"))
                        fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
                    else fmt.getInteger(MediaFormat.KEY_HEIGHT)
                    ensureEncoder(w, h)
                }
                idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* pre-API21 path, ignore */ }
                idx >= 0 -> {
                    if (info.size > 0) {
                        val image = runCatching { dec.getOutputImage(idx) }.getOrNull()
                        try {
                            image?.let { scaleAndForward(it) }
                        } finally {
                            image?.close()
                        }
                    }
                    dec.releaseOutputBuffer(idx, false)
                }
                else -> return
            }
        }
    }

    private fun ensureEncoder(srcW: Int, srcH: Int) {
        if (encoder != null || srcW <= 0 || srcH <= 0) return
        // Preserve the source aspect ratio; never upscale beyond the source height.
        var targetH = minOf(profile.maxHeight, srcH)
        var targetW = (srcW.toDouble() / srcH * targetH).toInt()
        targetW -= targetW % 2   // most encoders require even dimensions
        targetH -= targetH % 2
        val format = MediaFormat.createVideoFormat("video/avc", targetW, targetH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            runCatching { setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) }
        }
        runCatching {
            encoder = MediaCodec.createEncoderByType("video/avc").apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            AppLog.i(TAG, "encoder [${profile.name}]: ${srcW}x$srcH -> ${targetW}x$targetH " +
                    "@ ${profile.fps}fps ${profile.bitrateBps / 1000}kbps, ${I_FRAME_INTERVAL_S}s IDR")
        }.onFailure { AppLog.w(TAG, "encoder setup failed: ${it.message}") }
    }

    /** Throttles to the profile's fps and nearest-neighbor downsamples each plane into the
     *  encoder's input. Image/Plane API abstracts the pixel/row stride layout (NV12 vs I420). */
    private fun scaleAndForward(src: Image) {
        val enc = encoder ?: return
        val nowNs = System.nanoTime()
        if (nowNs - lastForwardedNs < frameIntervalNs) return
        lastForwardedNs = nowNs

        val inIdx = enc.dequeueInputBuffer(0)
        if (inIdx < 0) return   // encoder busy — drop this frame, best-effort
        val cap = runCatching { enc.getInputBuffer(inIdx)?.capacity() }.getOrNull() ?: 0
        val dstImage = runCatching { enc.getInputImage(inIdx) }.getOrNull()
        if (dstImage == null) {
            enc.queueInputBuffer(inIdx, 0, 0, 0, 0)
            return
        }
        // NOTE: an encoder's *input* Image (getInputImage) must not be closed here — it's
        // invalidated by queueInputBuffer() below, which is what actually submits it.
        val dstW = dstImage.width; val dstH = dstImage.height
        downsamplePlane(src.planes[0], dstImage.planes[0], src.width, src.height, dstW, dstH)
        downsamplePlane(src.planes[1], dstImage.planes[1], src.width / 2, src.height / 2, dstW / 2, dstH / 2)
        downsamplePlane(src.planes[2], dstImage.planes[2], src.width / 2, src.height / 2, dstW / 2, dstH / 2)

        val ptsUs = nowNs / 1000
        enc.queueInputBuffer(inIdx, 0, cap, ptsUs, 0)
        drainEncoder()
    }

    private fun downsamplePlane(src: Image.Plane, dst: Image.Plane, srcW: Int, srcH: Int, dstW: Int, dstH: Int) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return
        val srcBuf = src.buffer
        val dstBuf = dst.buffer
        val srcRowStride = src.rowStride
        val srcPixStride = src.pixelStride
        val dstRowStride = dst.rowStride
        val dstPixStride = dst.pixelStride
        for (y in 0 until dstH) {
            val srcRowStart = (y * srcH / dstH) * srcRowStride
            val dstRowStart = y * dstRowStride
            for (x in 0 until dstW) {
                val srcPos = srcRowStart + (x * srcW / dstW) * srcPixStride
                val dstPos = dstRowStart + x * dstPixStride
                if (srcPos < srcBuf.capacity() && dstPos < dstBuf.capacity()) {
                    dstBuf.put(dstPos, srcBuf.get(srcPos))
                }
            }
        }
    }

    private fun drainEncoder() {
        val enc = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                idx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(idx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            handleCodecConfig(outBuf, info)
                        } else {
                            onEncoded(outBuf, info)
                            encFrameCount++
                            encBytesSinceLog += info.size
                            if (encFrameCount % 150 == 0) {
                                AppLog.v(TAG, "[${profile.name}] $encFrameCount frames encoded, " +
                                        "${encBytesSinceLog / 1024}KB in last 150")
                                encBytesSinceLog = 0
                            }
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
                else -> return
            }
        }
    }

    /** Splits the encoder's SPS+PPS codec-config buffer at the second Annex-B start code. */
    private fun handleCodecConfig(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val bytes = ByteArray(info.size)
        buf.get(bytes)
        var splitAt = -1
        var i = 4
        while (i < bytes.size - 3) {
            if (bytes[i] == Z && bytes[i + 1] == Z &&
                (bytes[i + 2] == O || (bytes[i + 2] == Z && bytes[i + 3] == O))) {
                splitAt = i; break
            }
            i++
        }
        if (splitAt <= 0) return
        val sps = bytes.copyOfRange(0, splitAt)
        val pps = bytes.copyOfRange(splitAt, bytes.size)
        AppLog.i(TAG, "encoder params ready: sps=${sps.size}B pps=${pps.size}B")
        onParamsReady(ByteBuffer.wrap(sps), ByteBuffer.wrap(pps))
    }

    companion object {
        private const val TAG = "StreamTranscoder"
        private const val Z: Byte = 0
        private const val O: Byte = 1
        // Per-NAL queue (the Autel original queued whole frames at capacity 6): sized like the
        // FPV pipeline's NAL queue so a transient stall doesn't shear frames apart.
        private const val QUEUE_CAPACITY = 128
        // The self-healing property: remote viewers can join/recover within ~2s.
        private const val I_FRAME_INTERVAL_S = 2
    }
}

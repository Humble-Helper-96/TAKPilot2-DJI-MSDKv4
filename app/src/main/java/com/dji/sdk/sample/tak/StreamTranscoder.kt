package com.dji.sdk.sample.tak

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import com.taklite.util.AppLog
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device transcoder for the outbound RTSP push: decodes the aircraft's native H.264
 * downlink and re-encodes it as a smaller H.264 stream shaped by a pilot-selected
 * [TranscodeProfile], on its own dedicated thread. The pilot's on-screen FPV
 * ([com.dji.sdk.sample.takpilot2.FpvTextureView]) is untouched — it has its own decoder on
 * the same shared VideoFeeder stream.
 *
 * Ported from the Autel sibling app's LowBandwidthTranscoder (pure MediaCodec/Image, no Autel
 * deps), with the DJI-V4 deltas:
 *  - Input is per-NAL (from [AnnexBNalAssembler]), not per-frame.
 *  - Profile-parameterized (max height / fps / bitrate) — see [TranscodeProfile].
 *  - Encoder emits a fixed 2s IDR so remote viewers (ATAK) can join / self-heal — the whole
 *    reason for on-device transcode; the raw passthrough feed could not (112s keyframe gap,
 *    field-measured 2026-07-25).
 *  - [requestSyncFrame]: asks OUR encoder for an immediate IDR to arm the RTSP packetizer on
 *    connect, without a source round-trip or an FPV glitch.
 *
 * CRITICAL (2026-07-25): the decode loop mirrors [FpvTextureView]'s proven structure — feed
 * ONE NAL, drain, interleaved, with hold-and-retry (never drop a source NAL). The first cut
 * fed the whole burst then drained, so once the decoder's few input buffers filled it
 * silently dropped the rest of the burst — manufacturing exactly the macroblock artifacting
 * FPV was clean of. Dropping a NAL breaks the H.264 reference chain until the next IDR, which
 * the Mini 2 never sends unprompted, so the corruption sticks (until a manual Video Re-Sync).
 * The decoder MUST decode EVERY source NAL; frame-rate downconversion happens later, at the
 * ENCODE input ([scaleAndForward] throttle), which is safe because the decoder's reference
 * state is already updated by then.
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
    /**
     * Resolutions AND bitrates match the Autel sibling exactly (operator, 2026-08-11). Both
     * aircraft push the same numbers to the same media server on the same tactical hotspot, so
     * a tier name means the same bandwidth whichever one is flying.
     *
     * ⚠ THE PICTURE HERE WILL BE SOFTER THAN THE SIBLING'S AT THE SAME TIER, and that is a
     * known, accepted trade rather than a bug to chase. Two things cost quality that the
     * bitrates do not pay for:
     *
     *  1. H.264 needs roughly twice H.265's bitrate for equal quality. The sibling's tiers are
     *     HEVC, and its own note says the extra resolution step "is bought with H.265". This app
     *     encodes AVC only.
     *  2. This screen is 20:9, not 4:3. At the same tier HEIGHT the S20 Ultra's 2400x1080
     *     carries 1.67x the pixels of that 4:3 controller — 1600x720 against 960x720 — and the
     *     capture is the whole screen, so those are real pixels the encoder must spend bits on.
     *
     * Equal quality would need about 3.3x these numbers, putting HIGH near 6 Mbps, which is too
     * much for a shared uplink. So per-pixel quality is what gives: HIGH sits at about 0.046
     * bits/px/frame against the sibling's 0.077.
     *
     * WHERE THAT SHOWS FIRST IS HUD TEXT AND MAP LABELS, not the video. Sharp high-frequency
     * edges cost far more bits than camera imagery and smear first. If a viewer reports an
     * unreadable altitude readout at HIGH, this is the reason, and the fix is a lower tier (a
     * sharp 720p beats a soft 1080p) or a real bandwidth decision — not nudging these numbers.
     */
    enum class TranscodeProfile(val maxHeight: Int, val fps: Int, val bitrateBps: Int) {
        LOW(480, 10, 375_000),        // 1066x480 — marginal/cellular links
        STANDARD(720, 15, 800_000),   // 1600x720 — default
        HIGH(1080, 15, 1_800_000);    // 2400x1080

        companion object {
            fun fromPref(name: String?): TranscodeProfile = when (name) {
                "low" -> LOW
                "high" -> HIGH
                else -> STANDARD
            }
        }
    }

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(true)
    private val syncRequested = AtomicBoolean(false)
    private val frameIntervalNs = 1_000_000_000L / profile.fps

    private var encFrameCount = 0
    private var encBytesSinceLog = 0L
    private var lastForwardedNs = 0L

    private val thread = Thread({ loop() }, "StreamTranscoder").apply { start() }

    /** Called from the SDK's frame-delivery thread — hands off, never blocks it. [nal] must be
     *  a caller-owned array (the assembler allocates fresh ones), not reused. */
    fun submit(nal: ByteArray, @Suppress("UNUSED_PARAMETER") isIFrame: Boolean) {
        if (!running.get()) return
        if (!queue.offer(nal)) {
            // Queue full (decode+encode fell persistently behind): drop the OLDEST pending NAL
            // to bound latency. Rare now that the loop interleaves feed+drain and holds NALs
            // rather than dropping them per-burst; a whole-scene loss here still corrupts until
            // the next 2s encoder IDR, which self-heals remote viewers regardless.
            queue.poll()
            queue.offer(nal)
        }
    }

    /** Ask our encoder to emit an IDR immediately (next frame) — arms the RTSP packetizer on
     *  connect and heals viewers on demand, with no aircraft round-trip / FPV disturbance. */
    fun requestSyncFrame() {
        syncRequested.set(true)
    }

    fun release() {
        running.set(false)
        thread.interrupt()
        runCatching { thread.join(500) }
    }

    // ---- Decode → scale → encode loop (dedicated thread) ----

    private fun loop() {
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var pendingNal: ByteArray? = null
        try {
            val mime = if (isHevc) "video/hevc" else "video/avc"
            // Placeholder dims — corrected via INFO_OUTPUT_FORMAT_CHANGED once the decoder
            // parses the real SPS out of the inline Annex-B NALs (proven csd-less on this phone
            // by the FPV pipeline).
            decoder = MediaCodec.createDecoderByType(mime).apply {
                configure(MediaFormat.createVideoFormat(mime, 1280, 720), null, null, 0)
                start()
            }
            AppLog.i(TAG, "decoder started ($mime), profile=${profile.name}")
            val info = MediaCodec.BufferInfo()

            while (running.get()) {
                // Encoder sync-frame request (from connect / heal).
                if (syncRequested.compareAndSet(true, false)) {
                    runCatching {
                        encoder?.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        })
                    }.onFailure { AppLog.w(TAG, "requestSyncFrame failed: ${it.message}") }
                }

                // Feed ONE NAL, holding it if the decoder's input is momentarily full — NEVER
                // drop (see class doc). Draining below frees input buffers for the next pass.
                val nal = pendingNal ?: queue.poll(10, TimeUnit.MILLISECONDS)
                pendingNal = null
                if (nal != null) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        decoder.getInputBuffer(inIdx)?.apply { clear(); put(nal) }
                        decoder.queueInputBuffer(inIdx, 0, nal.size, System.nanoTime() / 1000, 0)
                    } else {
                        pendingNal = nal
                    }
                }

                // Drain decoder → (throttled) scale+encode; then drain encoder → onEncoded.
                encoder = drainDecoder(decoder, encoder, info)
                encoder?.let { drainEncoder(it, info) }
            }
        } catch (ie: InterruptedException) {
            // normal shutdown
        } catch (t: Throwable) {
            AppLog.e(TAG, "transcoder loop died: ${t.message}", t)
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
        }
    }

    /** @return the encoder (created lazily on the decoder's first real output format). */
    private fun drainDecoder(dec: MediaCodec, encoderIn: MediaCodec?, info: MediaCodec.BufferInfo): MediaCodec? {
        var encoder = encoderIn
        while (running.get()) {
            val idx = dec.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return encoder
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (encoder == null) {
                        val fmt = dec.outputFormat
                        // Prefer the crop rect — coded size can exceed visible (e.g. 1088 for
                        // 1080); same handling as FpvTextureView.
                        val w = if (fmt.containsKey("crop-right"))
                            fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1
                        else fmt.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (fmt.containsKey("crop-bottom"))
                            fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1
                        else fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        encoder = createEncoder(w, h)
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* pre-API21, ignore */ }
                idx >= 0 -> {
                    if (info.size > 0 && encoder != null) {
                        val image = runCatching { dec.getOutputImage(idx) }.getOrNull()
                        try {
                            image?.let { scaleAndForward(it, encoder!!, info) }
                        } finally {
                            image?.close()
                        }
                    }
                    dec.releaseOutputBuffer(idx, false)
                }
                else -> return encoder
            }
        }
        return encoder
    }

    private fun createEncoder(srcW: Int, srcH: Int): MediaCodec? {
        if (srcW <= 0 || srcH <= 0) return null
        var targetH = minOf(profile.maxHeight, srcH)        // never upscale
        var targetW = (srcW.toDouble() / srcH * targetH).toInt()
        targetW -= targetW % 2   // most encoders require even dimensions
        targetH -= targetH % 2
        // CBR FIRST ON THIS PATH, unlike screen capture. VBR overshot the target about 2x on
        // detailed aerial scenes when it was field-measured on 2026-07-25, which defeats the
        // low-bandwidth tier. Screen capture prefers VBR for the opposite reason — see
        // EncoderConfig. Same ladder for both, so both get Baseline+Level4 and a graceful
        // fallback instead of a whole-stream failure on one unsupported key.
        val configured = EncoderConfig.configure(
            targetW, targetH, profile.bitrateBps, profile.fps, I_FRAME_INTERVAL_S, TAG,
            preferVbr = false,
            colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        ) ?: return null
        return runCatching {
            configured.first.apply { start() }.also {
                AppLog.i(TAG, "encoder [${profile.name}]: ${srcW}x$srcH -> ${targetW}x$targetH")
            }
        }.onFailure { AppLog.w(TAG, "encoder start failed: ${it.message}") }.getOrNull()
    }

    /** Throttle to the profile's fps and nearest-neighbor downsample each plane into the
     *  encoder's input (Image/Plane API abstracts NV12/I420 stride layout). */
    private fun scaleAndForward(src: Image, enc: MediaCodec, encInfo: MediaCodec.BufferInfo) {
        val nowNs = System.nanoTime()
        if (nowNs - lastForwardedNs < frameIntervalNs) return
        lastForwardedNs = nowNs

        val inIdx = enc.dequeueInputBuffer(0)
        if (inIdx < 0) return   // encoder busy this instant — skip (drops an OUTPUT frame, safe)
        val cap = runCatching { enc.getInputBuffer(inIdx)?.capacity() }.getOrNull() ?: 0
        val dstImage = runCatching { enc.getInputImage(inIdx) }.getOrNull()
        if (dstImage == null) {
            enc.queueInputBuffer(inIdx, 0, 0, 0, 0)
            return
        }
        // An encoder INPUT image (getInputImage) must not be closed — queueInputBuffer submits it.
        val dstW = dstImage.width; val dstH = dstImage.height
        downsamplePlane(src.planes[0], dstImage.planes[0], src.width, src.height, dstW, dstH)
        downsamplePlane(src.planes[1], dstImage.planes[1], src.width / 2, src.height / 2, dstW / 2, dstH / 2)
        downsamplePlane(src.planes[2], dstImage.planes[2], src.width / 2, src.height / 2, dstW / 2, dstH / 2)

        enc.queueInputBuffer(inIdx, 0, cap, nowNs / 1000, 0)
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

    private fun drainEncoder(enc: MediaCodec, info: MediaCodec.BufferInfo) {
        while (running.get()) {
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
        // Per-NAL queue sized like the FPV pipeline's — a transient stall shouldn't shear
        // frames apart. Drop-oldest at capacity only if the pipeline persistently can't keep up.
        private const val QUEUE_CAPACITY = 128
        // The self-healing property: remote viewers can join / recover within ~2s.
        private const val I_FRAME_INTERVAL_S = 2
    }
}

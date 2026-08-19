package com.dji.sdk.sample.tak

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.taklite.util.AppLog

/**
 * Configures the outbound RTSP push encoder — H.264 or H.265, the pilot's Pre-Flight choice
 * (see [VideoCodec]) — dropping optional format keys until one combination is accepted.
 *
 * Shared by [ScreenCaptureEncoder] and [StreamTranscoder] so the two cannot drift: both feed the
 * same media server and the same viewers, and a stream that plays from one path and not the
 * other would be a miserable thing to diagnose.
 *
 * ## The bitrate mode is chosen BY THE CALLER, and the two callers disagree
 *
 * Under CBR the encoder must fit every frame into a per-frame budget, so a keyframe — many times
 * the size of the P-frames around it — is quantised down to fit. That produces a pixelated pulse
 * at every I-frame interval, visible only to stream viewers because the artifact is created by
 * this re-encode. The Autel sibling chased it for three days (doubled bitrates, changed codec,
 * neither helped) before finding the rate-control mode was the cause; VBR fixed it outright,
 * I/P frame-size ratio 2.53x to 14x on HEVC and 51x on H.264.
 *
 * That is why SCREEN CAPTURE asks for VBR: its content is HUD text and map labels, whose
 * keyframes are expensive, and it was still on CBR here.
 *
 * The AIRCRAFT-CAMERA TRANSCODE keeps CBR, and that is not an oversight. VBR was field-measured
 * in THIS app on 2026-07-25 overshooting the target by about 2x on detailed aerial scenes, which
 * defeats the point of a low-bandwidth tier. Both findings are real measurements about different
 * pictures. Do not unify them without measuring again.
 *
 * Whichever mode is preferred, the other stays in the ladder underneath, so a device that does
 * not declare the first choice still configures.
 *
 * ## Baseline profile
 *
 * H.264 Baseline, not High. Baseline has no B-frames, which matters beyond compatibility:
 * B-frames reorder output, adding latency to a feed whose whole purpose is telling a pilot what
 * is happening now, and they complicate the RTSP packetiser's timestamping.
 *
 * KEY_LEVEL is now sent alongside KEY_PROFILE. Legacy OMX components commonly reject a profile
 * given without a matching level — the sibling's encoder failed configure with error -38 on
 * exactly that.
 *
 * ## Why a ladder and not a runCatching
 *
 * Both call sites previously wrapped `setInteger(KEY_PROFILE, …)` in `runCatching`. That does
 * nothing useful: `MediaFormat.setInteger` stores a value and cannot fail for an unsupported
 * profile. The rejection happens later, at `configure()`, which was NOT guarded per-key — so an
 * encoder that dislikes one optional key failed the whole stream instead of degrading. The
 * ladder below drops the least important key first, and logs which variant won so a future
 * device tells us what it supports instead of us guessing.
 */
object EncoderConfig {

    /**
     * Builds and configures an encoder for [w]x[h]. Returns the configured (not started) codec
     * and the name of the variant that worked, or null if every variant failed.
     *
     * [codec] decides the mime AND the profile/level pair. They must travel together: the HEVC
     * and AVC profile constants are separate numeric spaces that happen to collide, so an AVC
     * encoder configured with leftover HEVC values "works" by accident and means nothing.
     */
    fun configure(
        w: Int, h: Int, bitrateBps: Int, fps: Int, iFrameIntervalS: Int, tag: String,
        preferVbr: Boolean,
        codec: VideoCodec = VideoCodec.H264,
        colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
    ): Pair<MediaCodec, String>? {
        data class Variant(val name: String, val apply: (MediaFormat) -> Unit)

        val variants = bitrateModes(preferVbr, codec.mime).flatMap { mode ->
            val label = modeLabel(mode)
            listOf(
                Variant("full (profile+level, $label, max-fps)") { f ->
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                    f.setInteger(MediaFormat.KEY_PROFILE, codec.profile)
                    f.setInteger(MediaFormat.KEY_LEVEL, codec.level)
                    if (Build.VERSION.SDK_INT >= 30) {
                        f.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
                    }
                },
                Variant("no max-fps ($label)") { f ->
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                    f.setInteger(MediaFormat.KEY_PROFILE, codec.profile)
                    f.setInteger(MediaFormat.KEY_LEVEL, codec.level)
                },
                Variant("$label only (no profile/level)") { f ->
                    f.setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
                },
            )
        } + Variant("minimal (encoder defaults)") { }

        for (v in variants) {
            val format = MediaFormat.createVideoFormat(codec.mime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalS)
                v.apply(this)
            }
            val enc = runCatching { MediaCodec.createEncoderByType(codec.mime) }.getOrNull() ?: return null
            val ok = runCatching {
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }.isSuccess
            if (ok) {
                AppLog.i(tag, "encoder configured: ${v.name} — ${w}x$h @ ${fps}fps " +
                    "${bitrateBps / 1000}kbps, ${iFrameIntervalS}s keyframe")
                return enc to v.name
            }
            runCatching { enc.release() }
            AppLog.w(tag, "encoder rejected variant '${v.name}' — trying the next")
        }
        AppLog.e(tag, "no encoder variant configured for ${w}x$h")
        return null
    }

    /**
     * Bitrate modes to try, preferred first, filtered to what this device's encoder DECLARES.
     * Asking for a mode the encoder does not declare is how the sibling ended up believing it
     * had CBR when it had something else.
     *
     * ⚠ THE PREFERENCE IS PER PATH, and the two paths disagree for good reasons. Screen capture
     * wants VBR: its content is HUD text and map labels, keyframes are expensive, and CBR
     * quantises them down into a visible pulse. The aircraft-camera transcode wants CBR: VBR was
     * field-measured on 2026-07-25 overshooting the target about 2x on detailed aerial scenes,
     * which defeats the point of a low-bandwidth tier. Both measurements are real; they are
     * about different pictures. Do not unify them without measuring again.
     */
    private fun bitrateModes(preferVbr: Boolean, mime: String): List<Int> {
        val vbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        val cbr = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        @Suppress("UNUSED_VARIABLE")
        val declared = runCatching {
            val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
                ?.getCapabilitiesForType(mime)?.encoderCapabilities
            listOf(vbr, cbr).filter { caps?.isBitrateModeSupported(it) == true }
        }.getOrNull().orEmpty()
        val order = if (preferVbr) listOf(vbr, cbr) else listOf(cbr, vbr)
        val available = order.filter { it in declared }
        // Fail OPEN: if the query told us nothing, try both in the preferred order.
        return available.ifEmpty { order }
    }

    private fun modeLabel(mode: Int) = when (mode) {
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR -> "VBR"
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR -> "CBR"
        else -> "mode$mode"
    }

    /** VPS + SPS + PPS pulled out of an encoder's codec-config buffer, with start codes. */
    data class ParamSets(val sps: ByteArray, val pps: ByteArray, val vps: ByteArray?)

    /**
     * Pulls VPS/SPS/PPS out of the encoder's codec-config buffer.
     *
     * H.265 emits THREE parameter-set NALs where H.264 emits two, so this splits every NAL and
     * classifies by header type rather than assuming a count. NALs keep their start codes; the
     * RTSP library strips them itself. Shared by both encode paths for the same reason as
     * [configure]: the two must not drift.
     */
    fun splitParams(bytes: ByteArray, isHevc: Boolean, tag: String): ParamSets? {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitAnnexB(bytes)) {
            val hdr = nal.getOrNull(startCodeLen(nal)) ?: continue
            if (isHevc) {
                when ((hdr.toInt() shr 1) and 0x3F) {
                    32 -> vps = nal
                    33 -> sps = nal
                    34 -> pps = nal
                }
            } else {
                when (hdr.toInt() and 0x1F) {
                    7 -> sps = nal
                    8 -> pps = nal
                }
            }
        }
        val s = sps; val p = pps
        if (s == null || p == null) {
            AppLog.w(tag, "codec config had no SPS/PPS — not advertising")
            return null
        }
        AppLog.i(tag, "encoder params ready: " +
            (vps?.let { "vps=${it.size}B " } ?: "") + "sps=${s.size}B pps=${p.size}B")
        return ParamSets(s, p, vps)
    }

    private fun startCodeLen(nal: ByteArray): Int =
        if (nal.size >= 4 && nal[0] == Z && nal[1] == Z && nal[2] == Z && nal[3] == O) 4 else 3

    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < bytes.size - 3) {
            if (bytes[i] == Z && bytes[i + 1] == Z) {
                if (bytes[i + 2] == O) { starts.add(i); i += 3; continue }
                if (bytes[i + 2] == Z && bytes[i + 3] == O) { starts.add(i); i += 4; continue }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { idx, from ->
            bytes.copyOfRange(from, if (idx + 1 < starts.size) starts[idx + 1] else bytes.size)
        }
    }

    private const val Z: Byte = 0
    private const val O: Byte = 1
}

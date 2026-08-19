package com.dji.sdk.sample.tak

import android.media.MediaCodecInfo

/**
 * The pilot-selectable video codec for the outbound RTSP push.
 *
 * Ported from the Autel sibling, where it stopped being a compile-time constant on 2026-08-05.
 * It is a pilot choice because the right answer depends on WHO IS WATCHING, which is a field
 * decision and not a build decision:
 *
 *  - **[H264] is the most compatible.** It plays on the widest range of clients, including
 *    anything that decodes video in a browser.
 *  - **[H265] is the more efficient.** It delivers roughly the quality of H.264 at about half the
 *    bitrate. Fewer clients can play it.
 *
 * So neither is correct in general. A pilot on a constrained uplink whose team can decode H.265
 * wants the sharper picture; one who cannot be sure what the team is running wants H.264.
 *
 * **Deliberately no named clients here or on the Pre-Flight screen.** Which player supports which
 * codec changes with every release of that player, and a note naming one is wrong from the day it
 * changes — with no way for this file to find out. The hint text carries the trade; the field
 * carries the specifics.
 *
 * ## Why H.265 failures are hard to diagnose
 *
 * When a client cannot decode H.265 it usually fails at a layer that says nothing about video. A
 * client repackaging the stream for browser playback returned a bare `HTTP 500` on its manifest —
 * no video error, no mention of a codec. Nothing is visible from the controller either: the push,
 * the bitrate and the frame counts all look perfectly healthy, because they are.
 *
 * So if viewers report a broken stream that this end says is fine, **suspect the codec early**,
 * and prove the source from outside before changing anything here:
 * `ffprobe -v error -show_entries stream=codec_name "rtsp://…"`. If that reads the stream, the
 * push and the credentials are good and the fault is in the receiver.
 *
 * The codec applies to the TRANSCODE paths (screen capture and the decode-transcode fallback).
 * Passthrough ("original") sends the aircraft's own H.264 untouched and ignores this choice.
 */
enum class VideoCodec(val mime: String, val label: String) {
    /**
     * H.264 / AVC. The compatible choice — the safe default when the viewing clients are not
     * known in advance.
     *
     * Asks for **Baseline**, not High. Baseline has no B-frames, and that matters here beyond
     * compatibility: B-frames reorder output, which adds latency to a feed whose whole purpose is
     * telling a pilot what is happening now, and they complicate the RTSP packetiser's
     * timestamping. High profile would buy some compression efficiency and cost both.
     */
    H264("video/avc", "H.264"),

    /** H.265 / HEVC. The efficient choice: better picture for the same bitrate, on the clients
     *  that can decode it. See the class note on why its failures are hard to diagnose. */
    H265("video/hevc", "H.265");

    val isHevc: Boolean get() = this == H265

    /**
     * Profile and level to ASK for.
     *
     * These MUST track the codec. The HEVC and AVC profile constants are separate numeric spaces
     * that happen to collide — `HEVCProfileMain` and `AVCProfileBaseline` are both 1 — so leaving
     * the HEVC values in place while encoding AVC "works" by accident and means nothing.
     *
     * Neither value is load-bearing: the variant ladder in [EncoderConfig] drops profile/level
     * entirely if the encoder rejects them.
     */
    val profile: Int get() = when (this) {
        H265 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        H264 -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
    }

    val level: Int get() = when (this) {
        H265 -> MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
        H264 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
    }

    /** The `video_codec` pref value. Derived, so a codec cannot be saved under a typo. */
    val prefValue: String get() = name.lowercase()

    companion object {
        /**
         * Default [H264]. The safe default is the one whose failure mode is visible: choosing
         * H.264 where H.265 would have worked costs a slightly softer picture, which the pilot
         * can see. Choosing H.265 where it is not supported costs the whole stream for those
         * viewers, and shows nothing at all on the controller.
         */
        fun fromPref(name: String?): VideoCodec =
            values().firstOrNull { it.prefValue == name } ?: H264
    }
}

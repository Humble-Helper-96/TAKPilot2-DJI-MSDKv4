package com.dji.sdk.sample.tak

/**
 * Minimal H.264 slice/SPS header parser — enough to answer ONE diagnostic question:
 * **are frames actually being lost in transit, or are they arriving intact-but-corrupt?**
 *
 * Built 2026-07-27 after two instrumented flights ruled out the two obvious explanations for
 * the FPV artifacting (signal was 100% throughout; our own NAL queue essentially never
 * overflowed). What those counters CANNOT see is small loss: NAL throughput sits at ~600 per
 * 5s window with ±3-4 of jitter purely from window alignment, so one or two lost slices is
 * arithmetically indistinguishable from normal variance. Meanwhile the stream runs ~4 NALs per
 * rendered frame, i.e. multi-slice pictures — and losing ONE slice corrupts one horizontal band
 * of the image, which is exactly the reported symptom (corruption appearing in the streets,
 * then rooftops, then grass, accumulating over a static scene).
 *
 * `frame_num` settles it. It is a counter in every slice header that increments once per
 * reference picture, so a jump of more than one is direct, unambiguous evidence that a picture
 * never arrived — regardless of what any RF-level signal metric claims. If frame_num is
 * perfectly sequential while artifacts still build, the frames ARE arriving and the corruption
 * is inside them (bit errors surviving DJI's transport), which is a completely different root
 * cause needing a completely different fix.
 *
 * **Diagnosis only.** Nothing here changes decode behaviour; it only counts. Deciding what to
 * do about the answer comes after the answer.
 *
 * Scope is deliberately tiny — it parses the first few fields of a header and stops. It does not
 * decode anything, and it must never throw into the DJI callback thread it runs on, so every
 * entry point returns null on anything unexpected rather than propagating.
 */
object H264SliceParser {

    /** Result of parsing one VCL slice header. */
    data class SliceHeader(
        val frameNum: Int,
        /** 0 marks the FIRST slice of a picture — the reliable way to count pictures (rather
         *  than frames rendered) when a picture is split across several slices. */
        val firstMbInSlice: Int,
    )

    /**
     * `log2_max_frame_num` from an SPS NAL — needed to read `frame_num`, whose width in bits is
     * declared there rather than fixed. Null if the SPS uses scaling lists (a full parse this
     * doesn't attempt) or is malformed.
     *
     * @param payloadStart index of the NAL HEADER byte (i.e. just past the start code).
     */
    fun parseSpsLog2MaxFrameNum(nal: ByteArray, payloadStart: Int): Int? = runCatching {
        // +1 to skip the NAL header byte itself; 64 bytes is far more than these fields need.
        val r = BitReader(unescape(nal, payloadStart + 1, 64))
        val profileIdc = r.u(8)
        r.u(8)                       // constraint_set flags + reserved
        r.u(8)                       // level_idc
        r.ue()                       // seq_parameter_set_id
        if (profileIdc in HIGH_PROFILES) {
            val chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) r.u(1)   // separate_colour_plane_flag
            r.ue()                   // bit_depth_luma_minus8
            r.ue()                   // bit_depth_chroma_minus8
            r.u(1)                   // qpprime_y_zero_transform_bypass_flag
            // Scaling lists would need a full matrix parse to skip correctly. Bail rather than
            // guess and silently read frame_num from the wrong bit offset — a wrong frame_num
            // would manufacture fake "gaps" and send this whole investigation the wrong way.
            if (r.u(1) == 1) return null
        }
        r.ue() + 4                   // log2_max_frame_num_minus4
    }.getOrNull()

    /**
     * `frame_num` + `first_mb_in_slice` from a coded-slice NAL (type 1 non-IDR or type 5 IDR).
     *
     * Assumes `separate_colour_plane_flag` is 0 — true for everything but 4:4:4 chroma, which no
     * drone camera emits; a `colour_plane_id` field would otherwise sit before frame_num.
     *
     * @param payloadStart index of the NAL HEADER byte (i.e. just past the start code).
     */
    fun parseSliceHeader(nal: ByteArray, payloadStart: Int, log2MaxFrameNum: Int): SliceHeader? =
        runCatching {
            val r = BitReader(unescape(nal, payloadStart + 1, 32))
            val firstMb = r.ue()     // first_mb_in_slice
            r.ue()                   // slice_type
            r.ue()                   // pic_parameter_set_id
            SliceHeader(frameNum = r.u(log2MaxFrameNum), firstMbInSlice = firstMb)
        }.getOrNull()

    /**
     * Strips H.264 emulation-prevention bytes (a 0x03 inserted after two 0x00s so the payload can
     * never contain something that looks like a start code) into a small scratch buffer. Bounded
     * by [maxOut] because only the first handful of header bytes are ever read — copying a whole
     * 50KB IDR NAL to read 4 fields would be pointless work on a per-NAL hot path.
     */
    private fun unescape(nal: ByteArray, from: Int, maxOut: Int): ByteArray {
        if (from >= nal.size) return ByteArray(0)
        val out = ByteArray(minOf(maxOut, nal.size - from))
        var o = 0
        var zeros = 0
        var i = from
        while (i < nal.size && o < out.size) {
            val b = nal[i]
            if (zeros >= 2 && b.toInt() == 0x03) {
                zeros = 0
                i++
                continue
            }
            out[o++] = b
            zeros = if (b.toInt() == 0) zeros + 1 else 0
            i++
        }
        return if (o == out.size) out else out.copyOf(o)
    }

    /** Big-endian bit reader with Exp-Golomb support. Throws past the end; callers use
     *  runCatching, so a truncated NAL yields null rather than a crash. */
    private class BitReader(private val b: ByteArray) {
        private var pos = 0

        fun bit(): Int {
            val byteIdx = pos ushr 3
            if (byteIdx >= b.size) throw IndexOutOfBoundsException("past end of header")
            val v = (b[byteIdx].toInt() ushr (7 - (pos and 7))) and 1
            pos++
            return v
        }

        fun u(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bit() }
            return v
        }

        /** Unsigned Exp-Golomb: count leading zeros n (the terminating 1 is consumed), then
         *  value = 2^n - 1 + next n bits. */
        fun ue(): Int {
            var lz = 0
            while (bit() == 0) {
                lz++
                if (lz > 31) throw IllegalStateException("malformed exp-golomb")
            }
            return if (lz == 0) 0 else (1 shl lz) - 1 + u(lz)
        }
    }

    /** profile_idc values whose SPS carries the extra chroma/bit-depth/scaling fields. */
    private val HIGH_PROFILES =
        setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
}

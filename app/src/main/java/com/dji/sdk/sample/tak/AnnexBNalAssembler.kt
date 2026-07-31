package com.dji.sdk.sample.tak

/**
 * Reassembles whole Annex-B H.264 NAL units out of VideoFeeder's raw transport-sized chunks
 * (~2KB, NOT NAL-aligned — same underlying byte stream [FpvTextureView] decodes on-screen).
 *
 * Deliberately a standalone duplicate of [FpvTextureView.DecoderThread]'s proven start-code
 * scanning logic, not a shared refactor of it — that decoder is the single most fragile piece
 * of this app (see docs/TAKPILOT2_V4_PORT_SUMMARY.md §6), and coupling a brand-new RTSP push feature into
 * its code path risks regressing on-screen video for a feature change unrelated to it. The
 * algorithm is intentionally identical; if either needs to change, change both deliberately.
 *
 * Emits each complete NAL, WITH a normalized 4-byte start code (00 00 00 01) prepended — RTSP
 * push (unlike our own MediaCodec, which strips start codes itself) wants them present.
 */
class AnnexBNalAssembler(private val onNal: (nal: ByteArray, type: Int) -> Unit) {

    private var pending = ByteArray(0)

    /** Called on whatever thread delivers raw chunks (the DJI video-feed callback thread). */
    fun feed(data: ByteArray, size: Int) {
        val buf = ByteArray(pending.size + size)
        System.arraycopy(pending, 0, buf, 0, pending.size)
        System.arraycopy(data, 0, buf, pending.size, size)

        var nalStart = -1
        var i = 0
        while (i + 2 < buf.size) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                val s = if (i > 0 && buf[i - 1].toInt() == 0) i - 1 else i
                if (nalStart >= 0) emit(buf, nalStart, s)
                nalStart = s
                i += 3
            } else {
                i++
            }
        }
        pending = if (nalStart >= 0) buf.copyOfRange(nalStart, buf.size)
                  else buf.copyOfRange(maxOf(0, buf.size - 4), buf.size)
    }

    private fun emit(buf: ByteArray, from: Int, to: Int) {
        val hdr = when {
            to - from > 4 && buf[from + 2].toInt() == 0 -> 4 // 00 00 00 01
            to - from > 3 -> 3                                // 00 00 01
            else -> return
        }
        if (from + hdr >= to) return
        val type = buf[from + hdr].toInt() and 0x1F
        val out = ByteArray(4 + (to - from - hdr))
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1
        System.arraycopy(buf, from + hdr, out, 4, to - from - hdr)
        onNal(out, type)
    }

    /** Resets assembler state (e.g. after a stream restart) — drops any partial tail. */
    fun reset() {
        pending = ByteArray(0)
    }
}

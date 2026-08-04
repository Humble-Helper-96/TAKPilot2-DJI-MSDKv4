package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.AttributeSet
import com.dji.sdk.sample.tak.FpvDecoderHealth
import com.dji.sdk.sample.tak.H264SliceParser
import com.dji.sdk.sample.tak.IdrRequesterHolder
import com.taklite.util.AppLog
import android.view.Surface
import android.view.TextureView
import dji.sdk.camera.VideoFeeder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live FPV view decoding the DJI primary video feed with OUR OWN low-latency MediaCodec
 * pipeline, replacing DJI's DJICodecManager rendering entirely (see
 * docs/TAKPILOT2_V4_PORT_SUMMARY.md §6 for the full root-cause history).
 *
 * Two things DJICodecManager doesn't handle that this does:
 *  - The Mini 2 emits NO SPS/PPS/IDR in steady state — it sends a keyframe only when asked.
 *    A dormant, off-screen DJICodecManager is kept solely as that "ask for a keyframe" lever
 *    ([IdrRequesterHolder]; it never renders anything itself). That holder is a process-wide
 *    singleton, NOT owned per-surface here — DJI's native video engine doesn't tolerate being
 *    destroyed/recreated on every surface cycle (screen lock/unlock, screen navigation); see
 *    the holder's doc for the in-flight black-FPV incident that fix addressed.
 *  - VideoFeeder.onReceive() bytes are transport-sized chunks (~2 KB), NOT NAL-aligned. An
 *    assembler splits the Annex-B stream (3- or 4-byte start codes) into whole NALs, regroups
 *    those into whole ACCESS UNITS (one picture each), queues the pictures boundedly (dumping
 *    the backlog on overflow, so latency can never accumulate), and a decode thread renders
 *    only the newest ready frame each pass.
 *
 * **Pictures are multi-slice, and that has to be reassembled before the decoder sees them.**
 * A VCL NAL is one SLICE — a horizontal band — not a frame; the Mini 2 sends ~4 NALs per
 * picture and the Air 2 exactly 5 slices per picture. MediaCodec's contract is one complete
 * access unit per input buffer, so feeding it a slice at a time (with `pts` advancing a full
 * frame interval per slice, as this originally did) tells it every band is its own frame. Field
 * evidence, Air 2 on a Pixel 10a, 2026-08-03: the decoder emitted at least 270 output buffers
 * for 149 transmitted pictures — partial pictures, one band fresh and the rest stale — while
 * `gaps=0 lost=0 parseFail=0` and signal sat at 100%, i.e. the corruption was ours, not the
 * link's. Two failures compounded it: the NAL-counted queue held only 0.24s of a 246 NAL/s
 * stream (the "~2s at 30fps" comment silently assumed one NAL per frame), and its overflow
 * `clear()` then dumped slices from the middle of pictures several times a second. Assembly
 * fixes the tearing; counting the queue in pictures fixes the overflow that fed it.
 *
 * **The decode loop recovers from a codec that dies mid-stream, not just from a codec that
 * never got a keyframe.** Found on an Oukitel RT3 (MediaTek MT6762): its hardware AVC decoder
 * (`c2.mtk.avc.decoder`) threw `previous call to queue exceeded timeout` -> MediaCodec
 * `UNKNOWN_ERROR` about 3 seconds into every session, something never seen on the Pixel 8 Pro
 * or 10a. Before this, ANY MediaCodec-level exception was fatal to the whole decode thread —
 * caught once, logged, and the thread simply ended with no restart path, while the DJI video
 * callback (on a different thread) kept assembling and queueing NALs forever with nobody left
 * to drain them: a permanently frozen frame with the app otherwise fully responsive, "queue
 * overflow" spamming the log, and no recovery short of leaving and re-entering the flight
 * screen (the only thing that rebuilds the TextureView surface). See
 * `docs/TAKPILOT2_V4_PORT_SUMMARY.md` §6 for the full diagnosis.
 *
 * Now a codec-level exception during the per-iteration feed/drain is caught locally: the dead
 * `MediaCodec` is stopped/released and a fresh one is created against the SAME [Surface] (the
 * Surface itself is never touched here — only [shutdown] releases it), decode state resets to
 * "unsynced" so the existing keyframe-request path re-syncs it, and the SAME thread keeps
 * running. Backoff grows with consecutive failures (capped) so a persistently broken decoder
 * doesn't spin-loop, and resets to zero the moment a frame actually renders, so an isolated
 * failure recovers at full speed next time.
 *
 * **Two-tier, one-way-per-session escalation on repeated failure.** First failure drops the
 * `KEY_LOW_LATENCY` hint (a known weak spot in some budget SoCs' Codec2 HAL implementations) and
 * recreates via the SAME decoder-by-MIME-type call. **Confirmed on the RT3 that this alone is
 * not sufficient** — `c2.mtk.avc.decoder` failed identically with the hint already off, same
 * error, same ~7s cadence. That is expected once you know `MediaCodec.createDecoderByType()` is
 * deterministic: it hands back the platform's one preferred component for that MIME type every
 * time, so recreating with different format flags can never escape a defect that lives in the
 * component itself. The second failure therefore switches decoder SELECTION, not configuration:
 * it looks up a software-only AVC decoder via [MediaCodecList] and requests it BY NAME
 * ([MediaCodec.createByCodecName]) for the rest of the session — every certified Android device
 * ships one (a CDD requirement), and it is a genuinely different implementation, not the same
 * broken part with different settings.
 *
 * **What's learned is remembered across sessions, per device — see [FpvDecoderHealth].** Without
 * that, a device whose hardware decoder is broken pays this whole escalation ramp (field-measured
 * on the RT3: ~45 seconds of visible freezing across two failed hardware attempts and their
 * resync waits, before the software decoder syncs) EVERY time the flight screen's video surface
 * is recreated — leaving and re-entering, the app backgrounding — even on the tenth flight on a
 * device that told us the answer on the first one. A fresh session starts at whichever tier
 * already worked last time, on this device, and only re-escalates if that tier itself fails.
 */
class FpvTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var videoListener: VideoFeeder.VideoDataListener? = null
    private var decoder: DecoderThread? = null
    @Volatile private var sawFirstFrame = false

    /** Invoked (on the DJI callback thread) the first time raw video bytes arrive. */
    var onFirstFrame: (() -> Unit)? = null

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // Keyframe-request lever is a process-wide singleton (IdrRequesterHolder) — DJI's
        // native video engine doesn't tolerate being destroyed/recreated on every surface
        // cycle (screen lock/unlock, screen navigation); see that class's doc for the
        // in-flight incident this fixed.
        IdrRequesterHolder.ensureStarted(context)

        val d = DecoderThread(Surface(surface), context.applicationContext)
        d.onSyncNeeded = { IdrRequesterHolder.requestKeyframe() }
        d.onHardResyncNeeded = { IdrRequesterHolder.forceResync() }
        d.onVideoSize = { w, h -> post { applyAspect(w, h) } }
        d.start()
        decoder = d
        // Expose our (reliable) resync to anything that needs the aircraft to emit a fresh
        // SPS/PPS/IDR — notably the RTSP streamer's keyframe bootstrap. See IdrRequesterHolder.
        IdrRequesterHolder.fpvResync = { requestResync() }

        val feed = VideoFeeder.getInstance()?.primaryVideoFeed
        if (feed != null && videoListener == null) {
            val l = VideoFeeder.VideoDataListener { data, size ->
                if (!sawFirstFrame) {
                    sawFirstFrame = true
                    onFirstFrame?.invoke()
                }
                try {
                    decoder?.feed(data, size)
                } catch (t: Throwable) {
                    // Surface any assembler bug loudly — an exception thrown into DJI's
                    // callback thread otherwise kills the feed silently.
                    AppLog.e(TAG, "FPV: feed() threw", t)
                }
            }
            feed.addVideoDataListener(l)
            videoListener = l
            AppLog.i(TAG, "FPV: decoder started (${width}x$height), listener registered")
        } else if (feed == null) {
            AppLog.w(TAG, "FPV: no primary video feed available yet")
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        val (w, h) = videoSize
        if (w > 0 && h > 0) applyAspect(w, h)
    }

    // ---- Aspect-ratio handling ----
    @Volatile private var videoSize = 0 to 0

    /**
     * Letterbox/pillarbox the decoded video (fit-left). A TextureView stretches its content
     * to the view bounds by default, which distorted the 16:9 feed on the wider flight-screen
     * view; setTransform corrects it using the real video size reported by the decoder.
     * Pillarbox space is pinned to the right edge (pivot x=0) rather than split evenly on both
     * sides, so the video hugs the left edge and leaves one contiguous blank strip on the right
     * for HUD content — vertical letterboxing (rare on this landscape screen) still centers.
     */
    private fun applyAspect(videoW: Int, videoH: Int) {
        videoSize = videoW to videoH
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || videoW <= 0 || videoH <= 0) return
        val viewAspect = vw / vh
        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val m = Matrix()
        if (videoAspect > viewAspect) {
            // Video wider than view: full width, shrink height (letterbox top/bottom).
            m.setScale(1f, viewAspect / videoAspect, 0f, vh / 2f)
            val h = vh * (viewAspect / videoAspect)
            videoRect.set(0f, vh / 2f - h / 2f, vw, vh / 2f + h / 2f)
        } else {
            // Video taller than view: full height, shrink width — pivot at the left edge so
            // the blank strip lands entirely on the right instead of split both sides.
            m.setScale(videoAspect / viewAspect, 1f, 0f, vh / 2f)
            videoRect.set(0f, 0f, vw * (videoAspect / viewAspect), vh)
        }
        setTransform(m)
        onVideoRectChanged?.invoke(videoRect)
    }

    // ---- Video content rect, for the sibling CrosshairView ----
    // TextureView.onDraw()/draw() are both final in this SDK (it owns SurfaceTexture
    // rendering), so the crosshair can't be drawn inside this view — instead we just track and
    // publish the actual video content bounds (not the screen's — the video is left-
    // pillarboxed, so its center isn't the view's center) for a sibling overlay view to use.
    private val videoRect = RectF()

    /** Invoked whenever the video content's on-screen bounds change (aspect/size updates). */
    var onVideoRectChanged: ((RectF) -> Unit)? = null

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Stop advertising a resync hook that's about to point at a dead decoder.
        IdrRequesterHolder.fpvResync = null
        videoListener?.let { VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(it) }
        videoListener = null
        decoder?.shutdown()
        decoder = null
        // IdrRequesterHolder is intentionally NOT torn down here — it's a process-wide
        // singleton that outlives this View's surface lifecycle (see FpvTextureView's
        // onSurfaceTextureAvailable / IdrRequesterHolder's doc).
        sawFirstFrame = false
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    /** Pilot-triggered "Video Re-Sync" (flight-screen button): forces a hard resync of the
     *  live decoder to clear accumulated static-scene artifacting on demand. No-op if the
     *  decoder isn't running yet. */
    fun requestResync() {
        AppLog.i(TAG, "requestResync (pilot Video Re-Sync) — decoder ${if (decoder != null) "present" else "NOT started"}")
        decoder?.requestResync()
    }

    /**
     * Owns the MediaCodec: assembles NALs from raw chunks, decodes, and renders newest-only.
     */
    private class DecoderThread(
        private val surface: Surface,
        private val appContext: Context,
    ) : Thread("FpvDecoder") {

        private val running = AtomicBoolean(true)
        /** Whole ACCESS UNITS (one complete picture each), not individual NALs — see
         *  [flushAccessUnit] for why that distinction is the difference between a clean picture
         *  and a torn one. */
        private val auQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)
        @Volatile private var waitForSync = true // drop everything until an SPS arrives

        /**
         * Whether [createCodec] requests `KEY_LOW_LATENCY` (API 30+ only regardless). Starts at
         * whatever [FpvDecoderHealth] already knows about THIS device — true (untried, or proven
         * fine, as on the Pixel 8 Pro/10a) unless a prior session on this exact device already
         * had to drop it, in which case a fresh session doesn't waste time re-discovering that.
         * Dropped permanently for the rest of THIS session on a codec failure regardless (see the
         * recovery catch below), and that failure is what feeds [FpvDecoderHealth] for next time.
         * Low-latency decode modes are a known weak spot in some budget SoCs' Codec2 HAL
         * implementations; if that's what's destabilizing a device's hardware decoder, this
         * removes the variable without giving up the faster mode on hardware that handles it
         * fine.
         */
        private var useLowLatencyHint = FpvDecoderHealth.startWithLowLatency(appContext)

        /**
         * Second-tier escalation: request a software AVC decoder BY NAME instead of the
         * platform's preferred hardware one for this MIME type. Starts true immediately, skipping
         * the hardware attempt(s) entirely, if [FpvDecoderHealth] already knows this device needs
         * it. Otherwise only reached after a failure has ALREADY survived having
         * [useLowLatencyHint] dropped — see the class doc for why that ordering matters
         * (recreating a hardware decoder that's broken for reasons other than the low-latency
         * hint just hands back the identical broken component). One-way per
         * session, same rationale as [useLowLatencyHint].
         */
        private var preferSoftwareDecoder = FpvDecoderHealth.startWithSoftwareDecoder(appContext)

        /** Resolved once and cached — no reason to re-scan [MediaCodecList] on every recreate
         *  once we already know which software decoder this device has. */
        private var softwareDecoderName: String? = null

        /**
         * Finds a software-only AVC decoder. Null if none is found — shouldn't happen (every
         * certified device has one per the Android CDD), but this runs on hardware outside our
         * control, so failing closed to the platform default is safer than assuming.
         *
         * [MediaCodecInfo.isSoftwareOnly] exists from API 29; below that this falls back to the
         * long-standing naming convention (a vendor's own decoder is never named "OMX.google."
         * or "c2.android.").
         */
        private fun findSoftwareAvcDecoder(): String? {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }) continue
                val isSoftware = if (android.os.Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
                    else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
                if (isSoftware) return info.name
            }
            return null
        }

        /** Invoked (rate-limited) while unsynced — asks the aircraft for a fresh keyframe. */
        @Volatile var onSyncNeeded: (() -> Unit)? = null
        private var lastSyncReq = 0L

        /** Invoked (rate-limited) when [onSyncNeeded]'s lightweight nudge hasn't produced a
         *  fresh SPS within [HARD_RESYNC_AFTER_MS] — see IdrRequesterHolder.forceResync doc
         *  for why this exists as a distinct, heavier escalation. */
        @Volatile var onHardResyncNeeded: (() -> Unit)? = null
        private var unsyncedSince = 0L
        private var lastHardResync = 0L

        /** Invoked with the real decoded video dimensions (from INFO_OUTPUT_FORMAT_CHANGED). */
        @Volatile var onVideoSize: ((Int, Int) -> Unit)? = null

        /** Set by [requestResync]. Handled at the top of the decode loop: drop the queue, go
         *  unsynced, and force the proven resetDecoder recovery now. */
        @Volatile private var resyncRequested = false
        /** Whether the pending [resyncRequested] came from the pilot's button rather than from
         *  automatic frame-loss detection. Kept separate purely so the health counters stay
         *  honest — conflating the two would make "how often did the PILOT have to intervene"
         *  unanswerable, and that number is the whole measure of whether the auto-resync works. */
        @Volatile private var resyncWasPilotInitiated = false

        fun requestResync() {
            resyncWasPilotInitiated = true
            resyncRequested = true
        }

        // ---- Annex-B assembler state (called on the DJI feed thread) ----
        private var pending = ByteArray(0)

        fun feed(data: ByteArray, size: Int) {
            // Append the new chunk to any leftover bytes.
            val buf = ByteArray(pending.size + size)
            System.arraycopy(pending, 0, buf, 0, pending.size)
            System.arraycopy(data, 0, buf, pending.size, size)

            // Split on Annex-B start codes — BOTH 3-byte (00 00 01) and 4-byte
            // (00 00 00 01) forms occur in real streams (slices commonly use 3-byte).
            var nalStart = -1
            var i = 0
            while (i + 2 < buf.size) {
                if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                    // Start-code begins one byte earlier if a third zero precedes it.
                    val s = if (i > 0 && buf[i - 1].toInt() == 0) i - 1 else i
                    if (nalStart >= 0) emitNal(buf, nalStart, s)
                    nalStart = s
                    i += 3
                } else {
                    i++
                }
            }
            // Keep the (possibly incomplete) tail — from the last start code, or just the
            // last few bytes (a start code could straddle the chunk boundary).
            pending = if (nalStart >= 0) buf.copyOfRange(nalStart, buf.size)
                      else buf.copyOfRange(maxOf(0, buf.size - 4), buf.size)
        }

        private fun emitNal(buf: ByteArray, from: Int, to: Int) {
            val nal = buf.copyOfRange(from, to)
            // Header byte follows the 3- or 4-byte start code.
            val hdr = when {
                nal.size > 4 && nal[2].toInt() == 0 -> 4 // 00 00 00 01
                nal.size > 3 -> 3                        // 00 00 01
                else -> return
            }
            val type = nal[hdr].toInt() and 0x1F
            if (waitForSync) {
                if (type == 7) {
                    waitForSync = false
                    // Whatever partial picture was mid-assembly belongs to the pre-resync
                    // stream and can only tear the first decoded frame. Drop it here — this is
                    // the one point both threads agree the stream restarts.
                    discardPartialAccessUnit()
                    AppLog.i(TAG, "FPV: SPS received — decoder syncing")
                } else return // drop until the requested keyframe burst arrives
            }
            nalsFed.incrementAndGet()
            bytesFed.addAndGet(nal.size.toLong())
            inspectForFrameLoss(nal, hdr, type)

            // ---- Access-unit assembly ----
            //
            // A VCL NAL (type 1-5) is a SLICE, not a picture. Both aircraft send multi-slice
            // pictures (Mini 2 ~4 NALs/picture, Air 2 measured at exactly 5 slices/picture), and
            // MediaCodec's contract is one complete ACCESS UNIT — every NAL of one picture — per
            // input buffer. Feeding it a slice at a time told it each fifth of the image was its
            // own frame: field-measured on the Air 2, the decoder emitted at least 270 output
            // buffers for 149 transmitted pictures, i.e. partial pictures with one band updated
            // and the rest stale. That is the "corrupted FPV" symptom, and it is self-inflicted,
            // not RF loss (gaps=0 lost=0 throughout, signal 100%).
            //
            // Boundary rule is the standard one (H.264 7.4.1.2.4): once the current AU holds at
            // least one slice, a new AU starts at either the first slice of the next picture
            // (first_mb_in_slice == 0) or any of the NAL types that may only PRECEDE a picture.
            val isVcl = type in 1..5
            val firstMb = if (isVcl) H264SliceParser.parseFirstMbInSlice(nal, hdr) else null
            if (isVcl && firstMb == null) auBoundaryParseFails.incrementAndGet()
            val startsNewAu = auHasVcl &&
                if (isVcl) firstMb == 0 else type in AU_PREFIX_NAL_TYPES
            if (startsNewAu) flushAccessUnit()

            if (type == 5 && firstMb == 0) {
                // IDR arriving while already synced — should only happen right after a
                // periodic-refresh or hard-resync request. Logged (rare, request-only) to
                // verify from the field whether those requests are actually landing, since
                // resetKeyFrame() has shown itself unreliable beyond a session's first use
                // (see IdrRequesterHolder doc) — if this line never appears during a long
                // static hover, the periodic anti-artifact refresh isn't working either.
                // Gated on first_mb_in_slice == 0 so a 5-slice IDR logs ONCE, as one picture,
                // instead of five times — that flood is what made the Air 2 log read as a
                // resync storm when the real event rate was a quarter of it.
                AppLog.i(TAG, "FPV: IDR received post-sync (periodic/hard-resync refresh landed)")
            }

            auNals.add(nal)
            auBytes += nal.size
            if (isVcl) auHasVcl = true
        }

        // ---- Access-unit assembly state (feed thread only) ----
        /** NALs accumulated for the picture currently being assembled, in stream order. Each
         *  already carries its own Annex-B start code (emitNal copies from the start code), so
         *  concatenating them yields a valid access unit with no fixups. */
        private val auNals = ArrayList<ByteArray>(16)
        private var auBytes = 0
        /** Whether [auNals] holds at least one slice yet. Without this, the leading SPS/PPS/SEI
         *  of the very first picture would each look like a boundary and flush empty AUs. */
        private var auHasVcl = false

        private fun discardPartialAccessUnit() {
            auNals.clear()
            auBytes = 0
            auHasVcl = false
        }

        /**
         * Concatenates the assembled picture into one buffer and queues it for the decoder.
         *
         * Queue depth is now counted in PICTURES, which is what [QUEUE_CAP] always claimed to
         * mean. Counting NALs made it a per-aircraft lie: 60 NALs is ~2s of Mini 2 but only 0.24s
         * of the Air 2's 246 NAL/s. Dropping is likewise per-picture — the old `clear()` dumped
         * whatever slices happened to be queued, which tore the pictures either side of the drop
         * on top of the latency it was trying to reclaim.
         */
        private fun flushAccessUnit() {
            if (auNals.isEmpty()) return
            val au = ByteArray(auBytes)
            var o = 0
            for (n in auNals) {
                System.arraycopy(n, 0, au, o, n.size)
                o += n.size
            }
            val slices = auNals.size
            discardPartialAccessUnit()
            accessUnits.incrementAndGet()
            nalsPerAu.addAndGet(slices)
            if (!auQueue.offer(au)) {
                // Queue full — the decoder fell persistently behind. Reset latency by dropping
                // the backlog and keeping the newest PICTURE, but do NOT force a resync/freeze:
                // keep decoding (brief concealed corruption, no freeze — the Mini 2 sends no
                // keyframe to recover to on its own anyway). The pilot's Video Re-Sync button
                // clears any lingering artifacting on demand.
                auQueue.clear()
                auQueue.offer(au)
                overflowDrops.incrementAndGet()
                AppLog.w(TAG, "FPV: queue overflow — dropped backlog (whole pictures), continuing")
            }
        }

        // ---- Video health telemetry ----
        //
        // Built 2026-07-27 to answer a question field data alone couldn't: some flights need
        // repeated Video Re-Sync taps, some never artifact at all, and it was unclear whether
        // that's real RF/link loss (nothing to fix in code), CPU contention from AR/RTSP running
        // alongside decode (fixable), or something specific to a given decoder tier/device.
        //
        // These counters are cheap (an AtomicInteger increment at points the loop already visits)
        // and get emitted as ONE low-volume summary line every [HEALTH_SUMMARY_INTERVAL_MS], own
        // tag, so it survives log rotation/interleaving and is a single grep away from a clean
        // time series — instead of reconstructing event frequency by hand from hundreds of
        // interleaved per-event lines. AtomicInteger because [nalsFed]/[overflowDrops] are written
        // from emitNal() on the DJI callback thread while everything else here runs on this
        // decode thread; making all six uniformly atomic removes any doubt rather than mixing
        // plain Int fields in by argument.
        private val nalsFed = java.util.concurrent.atomic.AtomicInteger(0)
        /**
         * BYTES fed, not just NAL count — a second blind spot found 2026-07-27 after a third
         * flight artifacted heavily during LANDING with every existing counter clean.
         *
         * Two reasons this matters. First, the Annex-B assembler splits on start codes, so if
         * bytes are lost *inside* a NAL it still emerges as one NAL, merely shorter — byte-level
         * loss is invisible to a NAL counter by construction. Second, landing is a high-motion
         * phase that should demand markedly MORE bytes per frame; if throughput instead flattens
         * out exactly when the scene gets busy, that points at the link saturating, which is a
         * different failure from either "picture lost" or "bits corrupted".
         */
        private val bytesFed = java.util.concurrent.atomic.AtomicLong(0)
        private val framesRendered = java.util.concurrent.atomic.AtomicInteger(0)
        private val overflowDrops = java.util.concurrent.atomic.AtomicInteger(0)
        /** The 3s/6s escalation firing because we've lost sync entirely — connect, or a real
         *  signal dropout. Counted separately from [manualResyncs]: this is a different, more
         *  severe event than the pilot noticing artifacting and tapping the button. */
        private val autoResyncs = java.util.concurrent.atomic.AtomicInteger(0)
        /** The pilot tapping Video Re-Sync — the direct, unambiguous signal for "this flight's
         *  artifacting got bad enough to act on," which is the exact behavior under
         *  investigation. */
        private val manualResyncs = java.util.concurrent.atomic.AtomicInteger(0)
        private val codecRecoveries = java.util.concurrent.atomic.AtomicInteger(0)
        /** Complete pictures handed to the decoder, one per input buffer. `aus` vs `pics` is the
         *  direct check that assembly is keeping up with the stream (they should match), and
         *  `rendered` can no longer legitimately exceed it — that it DID (270 vs 149 pics) is
         *  what identified the torn-picture bug in the first place. */
        private val accessUnits = java.util.concurrent.atomic.AtomicInteger(0)
        /** NALs consumed into those access units — `nalsPerAu / aus` is the stream's real
         *  slices-per-picture, the number every queue/throughput assumption here depends on and
         *  which differs per aircraft (Mini 2 ~4, Air 2 exactly 5). */
        private val nalsPerAu = java.util.concurrent.atomic.AtomicInteger(0)
        /** Slices whose first_mb_in_slice wouldn't parse, so a boundary may have been missed and
         *  two pictures merged into one AU. Non-zero invalidates `aus`. */
        private val auBoundaryParseFails = java.util.concurrent.atomic.AtomicInteger(0)
        /** Pictures too large for the codec's input buffer — see the feed path. Should be 0;
         *  anything else means KEY_MAX_INPUT_SIZE needs raising for that device. */
        private val auOversize = java.util.concurrent.atomic.AtomicInteger(0)
        private var lastHealthSummary = 0L

        // ---- Frame-loss detection (the "why does it artifact" instrument) ----
        //
        // Counting NALs proved we aren't dropping them ourselves, but it CANNOT see small
        // upstream loss: throughput sits at ~600 NALs per 5s window with ±3-4 of jitter from
        // window alignment alone, so one or two missing slices is arithmetically invisible. The
        // stream runs ~4 NALs per picture (multi-slice), and losing one slice corrupts one
        // horizontal band — exactly the reported symptom. `frame_num` is the field that settles
        // it: it increments once per reference picture, so a jump >1 is proof a picture never
        // arrived, no matter what the RF signal metric says. See [H264SliceParser].
        /** Slice NALs seen (type 1/5) — distinguishes slices from total NALs, confirming the
         *  slices-per-picture ratio directly rather than inferring it from fed/rendered. */
        private val sliceNals = java.util.concurrent.atomic.AtomicInteger(0)
        /** Pictures seen, counted by `first_mb_in_slice == 0` (the first slice of a picture). */
        private val picturesSeen = java.util.concurrent.atomic.AtomicInteger(0)
        /** Times frame_num jumped by more than one — each is one or more lost pictures. */
        private val frameNumGaps = java.util.concurrent.atomic.AtomicInteger(0)
        /** Total pictures estimated lost across those gaps (a gap can skip several). */
        private val framesLost = java.util.concurrent.atomic.AtomicInteger(0)
        /** Slice headers that wouldn't parse — tracked so a parser problem can never be
         *  mistaken for a clean stream. A non-zero count here invalidates the gap numbers. */
        private val sliceParseFails = java.util.concurrent.atomic.AtomicInteger(0)

        /** From the SPS: how many bits wide `frame_num` is. Null until an SPS is parsed, which
         *  is also why gap detection can't start until the first sync. */
        private var log2MaxFrameNum: Int? = null
        private var lastFrameNum = -1

        /**
         * Diagnosis only — counts, never changes decode behaviour. Runs on the DJI callback
         * thread, so [H264SliceParser] is written to return null rather than throw.
         */
        private fun inspectForFrameLoss(nal: ByteArray, hdr: Int, type: Int) {
            if (type == 7) {
                H264SliceParser.parseSpsLog2MaxFrameNum(nal, hdr)?.let {
                    if (log2MaxFrameNum != it) {
                        log2MaxFrameNum = it
                        AppLog.i(TAG, "FPV: SPS parsed — frame_num is $it bits " +
                            "(max ${1 shl it}); frame-loss detection active")
                    }
                }
                // An IDR restarts the sequence at 0, and an SPS always precedes one here.
                lastFrameNum = -1
                return
            }
            if (type != 1 && type != 5) return   // not a coded slice
            val bits = log2MaxFrameNum ?: return // no SPS yet — can't read frame_num
            sliceNals.incrementAndGet()

            val h = H264SliceParser.parseSliceHeader(nal, hdr, bits)
            if (h == null) {
                sliceParseFails.incrementAndGet()
                return
            }
            // Only the first slice of a picture advances the sequence; the rest repeat the same
            // frame_num, so counting every slice would manufacture false "no gap" evidence.
            if (h.firstMbInSlice != 0) return
            picturesSeen.incrementAndGet()

            val max = 1 shl bits
            if (type == 5) {           // IDR — frame_num resets, a jump here is expected
                lastFrameNum = h.frameNum
                return
            }
            if (lastFrameNum >= 0) {
                val delta = ((h.frameNum - lastFrameNum) + max) % max
                // delta 1 = normal. delta 0 = a non-reference picture (doesn't advance the
                // counter) — legitimate, not loss. Anything more means pictures went missing.
                if (delta > 1) {
                    frameNumGaps.incrementAndGet()
                    framesLost.addAndGet(delta - 1)
                    onFrameLossDetected(delta - 1)
                }
            }
            lastFrameNum = h.frameNum
        }

        /**
         * **The fix this whole investigation was for.** A lost reference frame breaks the
         * prediction chain, and because the Mini 2 emits NO periodic IDR, every later frame is
         * predicted from a corrupted reference — so a single lost frame produces corruption that
         * persists and compounds until something forces a keyframe. Field-measured 2026-07-27:
         * two lost frames in one 8-minute flight produced roughly five minutes of degraded video
         * across two separate build-ups, each ending only when the pilot manually tapped
         * Video Re-Sync 30-70 seconds after first noticing it.
         *
         * Requesting the keyframe here closes that loop: recovery starts ~30ms after the loss
         * instead of when a human notices. The pilot's button stays exactly as it is — this
         * doesn't replace it, it just means the common case no longer needs it.
         *
         * **Why this is safe where the old periodic resync was not.** A 15s blind timer was
         * field-rejected in 2026-07 because it cost a 0.6-3s freeze every 15 seconds regardless
         * of whether anything was wrong. This fires only on measured loss — twice in 8 minutes
         * in the reference flight, i.e. ~30x rarer — and each occurrence replaces minutes of
         * accumulating corruption rather than interrupting a perfectly good stream.
         *
         * **[MIN_LOSS_RESYNC_INTERVAL_MS] is load-bearing, not defensive.** On a genuinely bad
         * link (long range, interference) losses can arrive continuously, and a resync per loss
         * would then be exactly the every-few-seconds freeze that was already rejected — worse
         * than the artifacting it is trying to fix. Rate-limited, a degrading link converges on
         * "one brief recovery attempt every few seconds" and the pilot still has manual control.
         */
        private fun onFrameLossDetected(lost: Int) {
            val now = System.currentTimeMillis()
            if (now - lastLossResync < MIN_LOSS_RESYNC_INTERVAL_MS) {
                lossResyncSuppressed.incrementAndGet()
                return
            }
            lastLossResync = now
            lossResyncs.incrementAndGet()
            AppLog.w(TAG, "FPV: $lost frame(s) lost upstream — forcing re-sync to stop the " +
                "corruption propagating")
            // MUST be the full requestResync() path, not a bare onHardResyncNeeded().
            //
            // Field-proven 2026-07-27, the hard way: the first cut called onHardResyncNeeded()
            // alone, trying to avoid the brief picture glitch that clearing the queue causes.
            // It fired correctly at 19:31:32 and produced NOTHING — no IDR ever arrived. That is
            // exactly the behaviour IdrRequesterHolder.forceResync's own doc warns about:
            // resetDecoder() is a documented NO-OP while DJI's decoder believes the stream is
            // healthy, and by deliberately not going unsynced we had guaranteed it believed
            // exactly that. The keyframe request was silently discarded.
            //
            // requestResync() is the path the pilot's button uses and the one field-proven to
            // work (18:44:33 -> IDR at 18:44:34): it clears the queue and sets waitForSync, so
            // the decoder IS genuinely unsynced when resetDecoder lands, which is the condition
            // that makes the aircraft actually emit SPS/IDR. The brief glitch is the price of
            // the request working at all, and it is far cheaper than the minutes of compounding
            // corruption a lost reference frame otherwise causes.
            resyncRequested = true      // NOT requestResync() — that would mark it pilot-initiated
        }

        /** Keyframe requests triggered by detected loss, and ones suppressed by the rate limit
         *  — the suppressed count is what shows a link degrading faster than we can recover. */
        private val lossResyncs = java.util.concurrent.atomic.AtomicInteger(0)
        private val lossResyncSuppressed = java.util.concurrent.atomic.AtomicInteger(0)
        private var lastLossResync = 0L

        // ---- Downlink-recovery resync ----
        //
        // The frame_num detector above only catches WHOLE lost pictures. Flight 19:54-20:00
        // showed the more common and more damaging case it cannot see:
        //
        //   19:56:48-19:57:03  downlink sags to 80%, bitrate collapses 8000 -> 4084 kbps
        //   19:57:10           operator reports EXTREME artifacting
        //   19:57:13 onward    link fully recovered: sigDown 100%, kbps back to ~8000
        //   19:58:32           still corrupt 75s later; only a MANUAL resync cleared it
        //
        // Throughout, pics stayed at ~150/5s with gaps=0 — no whole picture was lost. The damage
        // is INSIDE frames: when the link sags, parts of a frame don't survive, but the slice
        // header carrying frame_num sits at the very start of the NAL and still parses cleanly,
        // so the sequence looks perfect while the payload is wrecked. Those damaged frames become
        // references, and since P-frames encode only DIFFERENCES from a reference, restoring full
        // bandwidth cannot undo it — the corruption is latched in until a keyframe replaces the
        // reference outright. That is exactly why full bitrate for 75s changed nothing and one
        // resync fixed it instantly.
        //
        // So: watch the downlink, and when it RECOVERS from a sag, ask for a keyframe. Firing
        // during the sag would be worse than useless — there is no bandwidth to carry a keyframe
        // and it would likely arrive damaged too. Rate of ~2 sags per flight in the observed data
        // means this fires about as often as the operator was manually resyncing anyway; it just
        // does it within a second instead of after a minute of degraded video.
        //
        // This is inference from strong correlation, not proof: sub-frame damage is not something
        // we can observe directly. linkRecoveryResync in the health line plus the operator no
        // longer needing the manual button is what will confirm or refute it.
        @Volatile private var linkWasDegraded = false
        private var worstDownlinkInSag = 100
        private var lastLinkCheck = 0L
        private val linkRecoveryResyncs = java.util.concurrent.atomic.AtomicInteger(0)

        /** Polls downlink quality and fires one resync per sag, on recovery. */
        private fun checkDownlinkRecovery(now: Long) {
            if (now - lastLinkCheck < LINK_CHECK_INTERVAL_MS) return
            lastLinkCheck = now
            val dn = com.dji.sdk.sample.tak.TakBridgeHolder.hud()?.downlinkSignalPct ?: return
            if (dn < LINK_DEGRADED_PCT) {
                if (!linkWasDegraded) worstDownlinkInSag = dn
                else worstDownlinkInSag = minOf(worstDownlinkInSag, dn)
                linkWasDegraded = true
                return
            }
            if (!linkWasDegraded) return
            linkWasDegraded = false
            val worst = worstDownlinkInSag
            worstDownlinkInSag = 100
            if (now - lastLossResync < MIN_LOSS_RESYNC_INTERVAL_MS) {
                lossResyncSuppressed.incrementAndGet()
                return
            }
            lastLossResync = now
            linkRecoveryResyncs.incrementAndGet()
            AppLog.w(TAG, "FPV: downlink recovered (sagged to $worst%) — forcing re-sync; any " +
                "frame damaged during the sag is latched into the reference chain until a keyframe")
            resyncRequested = true   // NOT requestResync() — that would mark it pilot-initiated
        }

        /** Emits one summary line and resets the since-last-summary counters. Reads
         *  [TakBridgeHolder]'s uplink signal, [ArOverlayView]'s and
         *  [com.dji.sdk.sample.tak.VideoStreamerHolder]'s global state — none of which need a
         *  view/streamer reference, the same "ask the singleton" pattern already used throughout
         *  this codebase — so a weak-signal flight and a CPU-contended one (AR + RTSP both
         *  running) leave a visibly different signature in this one line, instead of looking
         *  identical from drop/resync counts alone. */
        private fun logHealthSummary() {
            val fed = nalsFed.getAndSet(0)
            val bytes = bytesFed.getAndSet(0)
            val rendered = framesRendered.getAndSet(0)
            val drops = overflowDrops.getAndSet(0)
            val autoR = autoResyncs.getAndSet(0)
            val manualR = manualResyncs.getAndSet(0)
            val recov = codecRecoveries.getAndSet(0)
            val tier = when {
                preferSoftwareDecoder -> "sw"
                !useLowLatencyHint -> "hw-nolowlat"
                else -> "hw-lowlat"
            }
            val slices = sliceNals.getAndSet(0)
            val pics = picturesSeen.getAndSet(0)
            val gaps = frameNumGaps.getAndSet(0)
            val lost = framesLost.getAndSet(0)
            val parseFail = sliceParseFails.getAndSet(0)
            val hud = com.dji.sdk.sample.tak.TakBridgeHolder.hud()
            val sig = hud?.uplinkSignalPct
            // Downlink is the direction video travels — the uplink number says nothing about
            // whether a video frame survived the trip. See DroneTakBridge.lastDownlinkQuality.
            val dn = hud?.downlinkSignalPct
            val linkMbps = hud?.videoDataRateMbps
            AppLog.i(
                HEALTH_TAG,
                "fed=$fed kbps=${bytes * 8 / (HEALTH_SUMMARY_INTERVAL_MS / 1000) / 1000} " +
                    "avgNal=${if (fed > 0) bytes / fed else 0} " +
                    "rendered=$rendered drops=$drops autoResync=$autoR " +
                    "manualResync=$manualR codecRecover=$recov tier=$tier " +
                    "sigUp=${sig?.let { "$it%" } ?: "—"} " +
                    "sigDown=${dn?.let { "$it%" } ?: "—"} " +
                    "linkMbps=${linkMbps?.let { "%.1f".format(it) } ?: "—"} " +
                    "AR=${ArOverlayView.isRunningAnywhere} " +
                    "RTSP=${com.dji.sdk.sample.tak.VideoStreamerHolder.isActive} " +
                    // The frame-loss instrument. lost>0 = pictures genuinely missing upstream.
                    // lost=0 while artifacts build = they're arriving corrupt instead, which is
                    // a different root cause entirely. parseFail>0 invalidates gaps/lost.
                    "slices=$slices pics=$pics gaps=$gaps lost=$lost parseFail=$parseFail " +
                    // Access-unit assembly. aus should track pics 1:1 and rendered must not
                    // exceed aus; nalPerAu is the stream's slices-per-picture (per aircraft).
                    "aus=${accessUnits.getAndSet(0)} " +
                    "nalPerAu=${nalsPerAu.getAndSet(0)} " +
                    "auBoundaryFail=${auBoundaryParseFails.getAndSet(0)} " +
                    "auOversize=${auOversize.getAndSet(0)} " +
                    // lossResync = auto keyframe requests from detected loss (the new fix).
                    // suppressed > 0 means loss is arriving faster than the rate limit allows
                    // recovery — i.e. a genuinely degrading link, not an isolated glitch.
                    "lossResync=${lossResyncs.getAndSet(0)} " +
                    "linkRecoveryResync=${linkRecoveryResyncs.getAndSet(0)} " +
                    "lossSuppressed=${lossResyncSuppressed.getAndSet(0)}",
            )
        }

        /** Builds and starts a MediaCodec against this thread's [surface]. Used for the initial
         *  decoder and, on a codec-level failure, to rebuild in place without touching the
         *  Surface or the thread itself — see the class doc. */
        private fun createCodec(): MediaCodec {
            val base = if (preferSoftwareDecoder) {
                val name = softwareDecoderName ?: findSoftwareAvcDecoder()?.also { softwareDecoderName = it }
                if (name != null) {
                    AppLog.i(TAG, "FPV: creating decoder by name (software fallback): $name")
                    MediaCodec.createByCodecName(name)
                } else {
                    AppLog.w(TAG, "FPV: no software AVC decoder found on this device — " +
                        "falling back to the platform default despite it having failed")
                    MediaCodec.createDecoderByType(MIME)
                }
            } else {
                MediaCodec.createDecoderByType(MIME)
            }
            return base.apply {
                val fmt = MediaFormat.createVideoFormat(MIME, 1280, 720)
                fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = realtime
                // Input buffers now carry a whole picture, not one slice — an IDR access unit is
                // an order of magnitude larger than any single NAL this used to feed. Ask for
                // headroom rather than discover a device's default was too small mid-flight.
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                if (useLowLatencyHint && android.os.Build.VERSION.SDK_INT >= 30) {
                    fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                configure(fmt, surface, null, 0)
                start()
            }
        }

        // ---- Decode loop (this thread) ----
        override fun run() {
            var codec: MediaCodec? = null
            try {
                codec = createCodec()
                val info = MediaCodec.BufferInfo()
                var pts = 0L
                // A picture we polled but couldn't feed yet because the codec's input was
                // momentarily full. Held (not dropped) and retried next iteration after draining
                // output frees a buffer — so a transient decoder stall no longer manufactures
                // artifacting.
                var pendingAu: ByteArray? = null
                // Consecutive codec-level failures since the last successfully rendered frame —
                // drives the recreate backoff below and resets to 0 the moment a frame renders.
                var codecFailures = 0

                while (running.get()) {
                    val now = System.currentTimeMillis()

                    if (now - lastHealthSummary >= HEALTH_SUMMARY_INTERVAL_MS) {
                        lastHealthSummary = now
                        logHealthSummary()
                    }
                    checkDownlinkRecovery(now)

                    // Pilot-tapped Video Re-Sync failsafe: clear the backlog, go unsynced, and
                    // fire the proven resetDecoder recovery immediately (don't wait out the 3s
                    // auto-escalation) to clear accumulated artifacting on demand.
                    if (resyncRequested) {
                        resyncRequested = false
                        pendingAu = null
                        auQueue.clear()
                        waitForSync = true
                        unsyncedSince = 0L
                        lastSyncReq = 0L        // let onSyncNeeded fire right away while unsynced
                        lastHardResync = now    // we're firing the hard resync now
                        if (resyncWasPilotInitiated) {
                            resyncWasPilotInitiated = false
                            manualResyncs.incrementAndGet()
                            AppLog.i(TAG, "FPV: manual re-sync requested by pilot")
                        } else {
                            AppLog.i(TAG, "FPV: re-sync from automatic frame-loss detection")
                        }
                        onHardResyncNeeded?.invoke()
                    }

                    // The aircraft sends keyframes ONLY on request: while unsynced, keep
                    // asking (rate-limited) until the SPS/PPS/IDR burst arrives.
                    if (waitForSync) {
                        if (unsyncedSince == 0L) unsyncedSince = now
                        if (now - lastSyncReq > 500) {
                            lastSyncReq = now
                            onSyncNeeded?.invoke()
                        }
                        // Escalation: the lightweight keyframe nudge above has been observed
                        // to reliably work only for the FIRST decode session in a process —
                        // a second FpvTextureView instance (e.g. after Home->Flight
                        // navigation) can call it forever without ever getting a fresh SPS.
                        // If we've been stuck this long, force a harder resync instead of
                        // waiting on a nudge that apparently isn't landing. Retries at the
                        // same interval if still stuck after that.
                        if (now - unsyncedSince > HARD_RESYNC_AFTER_MS && now - lastHardResync > HARD_RESYNC_AFTER_MS) {
                            lastHardResync = now
                            autoResyncs.incrementAndGet()
                            AppLog.w(TAG, "FPV: still unsynced after ${now - unsyncedSince}ms, forcing hard resync")
                            onHardResyncNeeded?.invoke()
                        }
                    } else {
                        // Synced and decoding. No periodic self-resync: it was field-rejected
                        // 2026-07-23 as too disruptive (a ~0.6-3s freeze every 15s — a non-
                        // starter in flight). We accept gradual macroblock artifacting over a
                        // long STATIC scene instead (a lost NAL has nothing to overwrite it
                        // until motion or a real resync). The genuine recovery paths above
                        // (initial sync + hard-resync escalation when video actually drops, e.g.
                        // Home<->Flight / lock-unlock) still run; only the blind timer is gone.
                        // Real fix for the artifacting (frame-loss-triggered resync) is TODO.
                        unsyncedSince = 0L
                    }

                    // Feed/drain, isolated in its own try: a codec-level exception here (a HW
                    // decoder wedging mid-stream — see the class doc for the RT3/MediaTek case
                    // that motivated this) is NOT allowed to kill the thread. It's recovered by
                    // rebuilding the MediaCodec in place, below.
                    val cd = codec!!
                    try {
                        // Feed one complete PICTURE if the codec has room (SPS/PPS ride in-band,
                        // inside the same access unit). Prefer one we were holding from a prior
                        // stalled iteration; otherwise poll a fresh one.
                        val au = pendingAu ?: auQueue.poll(10, TimeUnit.MILLISECONDS)
                        pendingAu = null
                        if (au != null) {
                            val inIdx = cd.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val ib = cd.getInputBuffer(inIdx)
                                if (ib != null && ib.capacity() >= au.size) {
                                    ib.clear()
                                    ib.put(au)
                                    cd.queueInputBuffer(inIdx, 0, au.size, pts, 0)
                                    // One picture, one timestamp. Advancing per NAL (as this did
                                    // while feeding slices) claimed 5 frames' worth of time for
                                    // every real frame.
                                    pts += 33_333
                                } else {
                                    // A whole picture no longer fits the codec's input buffer.
                                    // Queue empty rather than letting ByteBuffer.put throw — the
                                    // catch below would read that as a codec failure and escalate
                                    // this device all the way to software decode over a sizing
                                    // problem. Drop the picture; the next one is 33ms away.
                                    auOversize.incrementAndGet()
                                    cd.queueInputBuffer(inIdx, 0, 0, pts, 0)
                                    AppLog.w(TAG, "FPV: picture ${au.size}B exceeds codec input " +
                                        "buffer ${ib?.capacity() ?: 0}B — dropped")
                                }
                            } else {
                                // Codec input momentarily full — HOLD this picture (don't drop it)
                                // and retry next iteration once draining output below frees a
                                // buffer. Dropping here used to manufacture persistent artifacting
                                // on any transient stall (GPU contention w/ the map/HUD); the
                                // overflow failsafe still bounds latency if we fall behind for
                                // real.
                                pendingAu = au
                            }
                        }

                        // Drain: render ONLY the newest ready frame, discard older ones.
                        var outIdx = cd.dequeueOutputBuffer(info, 0)
                        var lastIdx = -1
                        while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val f = cd.outputFormat
                                // Prefer the crop rect — it gives the true visible size.
                                val w = if (f.containsKey("crop-right"))
                                    f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                                else f.getInteger(MediaFormat.KEY_WIDTH)
                                val h = if (f.containsKey("crop-bottom"))
                                    f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                                else f.getInteger(MediaFormat.KEY_HEIGHT)
                                onVideoSize?.invoke(w, h)
                            } else {
                                if (lastIdx >= 0) cd.releaseOutputBuffer(lastIdx, false)
                                lastIdx = outIdx
                            }
                            outIdx = cd.dequeueOutputBuffer(info, 0)
                        }
                        if (lastIdx >= 0) {
                            cd.releaseOutputBuffer(lastIdx, true)
                            framesRendered.incrementAndGet()
                            // A frame actually rendered — whatever was wrong is no longer
                            // happening. Reset so the NEXT failure (if any) starts its backoff
                            // from scratch instead of inheriting an escalated delay from an
                            // unrelated earlier incident.
                            if (codecFailures > 0) {
                                AppLog.i(TAG, "FPV: decoder recovered after $codecFailures failure(s)")
                                codecFailures = 0
                            }
                        }
                    } catch (e: InterruptedException) {
                        // NOT a codec failure — this is shutdown() (running.set(false) then
                        // interrupt()) interrupting the blocked auQueue.poll() above. Field-
                        // observed 2026-07-27: without this separate clause, a screen
                        // navigation tearing down the surface mid-decode got misread as a codec
                        // error, wastefully rebuilt a MediaCodec that was immediately discarded,
                        // and — worse — COULD escalate/persist a wrong "this device needs
                        // software decode" conclusion from an ordinary shutdown rather than an
                        // actual failure, if the interrupt happened to land before any real
                        // failure had occurred. running.get() is already false by the time
                        // interrupt() fires, so the outer while loop ends the thread on its own;
                        // just stop touching decoder state here.
                        break
                    } catch (e: Exception) {
                        // Deliberately Exception, not Throwable: an OutOfMemoryError or similar
                        // should still escalate to the outer catch rather than feed a recreate
                        // loop.
                        codecFailures++
                        codecRecoveries.incrementAndGet()
                        AppLog.e(TAG, "FPV: codec error (failure #$codecFailures) — " +
                            "recreating decoder", e)
                        try { cd.stop() } catch (_: Throwable) {}
                        try { cd.release() } catch (_: Throwable) {}
                        // Escalation ladder — cheapest fix tried first, applied on the
                        // createCodec call below:
                        if (useLowLatencyHint) {
                            // Tier 1: any instability at all is enough to stop asking for the
                            // demanding mode. See useLowLatencyHint's doc.
                            useLowLatencyHint = false
                            FpvDecoderHealth.recordLowLatencyFailed(appContext)
                            AppLog.w(TAG, "FPV: dropping low-latency decode hint for the rest of " +
                                "this session after a codec failure")
                        } else if (!preferSoftwareDecoder) {
                            // Tier 2: it failed again even without the low-latency hint, so the
                            // hint was never the cause — recreating via the same MIME type just
                            // hands back the identical (broken) hardware component. Switch which
                            // decoder gets selected, not how it's configured. See the class doc.
                            preferSoftwareDecoder = true
                            FpvDecoderHealth.recordSoftwareDecoderNeeded(appContext)
                            AppLog.w(TAG, "FPV: hardware decoder still failing without low-" +
                                "latency mode — switching to a software AVC decoder for the " +
                                "rest of this session")
                        }
                        // Grows with consecutive failures so a persistently broken decoder
                        // doesn't spin-loop recreating itself several times a second; capped so
                        // it still retries at a bounded rate rather than giving up outright —
                        // there's no other recovery path available to the pilot short of leaving
                        // and re-entering the flight screen, so this never stops trying.
                        Thread.sleep(minOf(
                            RECREATE_BACKOFF_BASE_MS * codecFailures, RECREATE_BACKOFF_MAX_MS))
                        codec = createCodec()
                        // A new codec has no decode state — it needs a full SPS/PPS/IDR burst
                        // before it can produce anything, same as first sync. Anything queued
                        // for the old instance is stale.
                        pendingAu = null
                        auQueue.clear()
                        waitForSync = true
                        unsyncedSince = 0L
                        lastSyncReq = 0L
                        pts = 0L
                    }
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "FPV decoder died: ${t.message}", t)
            } finally {
                try { codec?.stop() } catch (_: Throwable) {}
                try { codec?.release() } catch (_: Throwable) {}
                try { surface.release() } catch (_: Throwable) {}
            }
        }

        fun shutdown() {
            running.set(false)
            interrupt()
            join(1000)
        }

        companion object {
            private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
            /** Depth in PICTURES — ~1s at 30fps before we dump the backlog. Was 60 and counted
             *  NALs, which made the real depth depend on the aircraft's slices-per-picture: the
             *  same 60 slots were ~2s of Mini 2 but 0.24s of the Air 2, which is why the Air 2
             *  overflowed several times a second. Counting pictures makes this aircraft-agnostic
             *  and halves worst-case latency at the same time. */
            private const val QUEUE_CAP = 30

            /** Input-buffer size hint, ample for a 1080p IDR access unit (largest thing the
             *  aircraft sends) with room to spare. */
            private const val MAX_INPUT_SIZE = 512 * 1024

            /** NAL types that may only appear BEFORE the slices of a picture, never between
             *  them — so meeting one while slices are already assembled marks a new access unit.
             *  AUD(9), SEI(6), SPS(7), PPS(8) and the prefix/subset-SPS/slice-extension
             *  types(14/15/20) of H.264 7.4.1.2.4. */
            private val AU_PREFIX_NAL_TYPES = setOf(6, 7, 8, 9, 14, 15, 20)
            private const val HARD_RESYNC_AFTER_MS = 3000L // escalate if still unsynced this long
            // Codec-recreate backoff: delay = min(BASE * consecutive failures, MAX). One-off
            // failure recovers in 300ms; a decoder that's persistently broken settles at a 4s
            // retry rate instead of spin-looping. Resets to 0 on the next rendered frame.
            private const val RECREATE_BACKOFF_BASE_MS = 300L
            private const val RECREATE_BACKOFF_MAX_MS = 4000L

            /** Own tag so a health-summary line is one grep away regardless of what else is
             *  logging (TAK/CoT chatter, verbose UI events) or how the TAK-tags filter is set —
             *  see [logHealthSummary]. */
            private const val HEALTH_TAG = "TP2VideoHealth"
            /** 5s: fine enough to localize a bad stretch within a 20-30 min flight (which
             *  maneuver, how far into the flight), coarse enough that a full flight is a few
             *  hundred lines, not thousands. */
            private const val HEALTH_SUMMARY_INTERVAL_MS = 5000L

            /**
             * Floor between loss-triggered keyframe requests. See [onFrameLossDetected] — this
             * is what stops a badly degrading link from turning recovery into a continuous
             * freeze, which is precisely the failure mode that got the old periodic resync
             * rejected in the field. 3s matches [HARD_RESYNC_AFTER_MS], the interval the sync
             * escalation already uses, so the two recovery paths can't fight each other.
             */
            private const val MIN_LOSS_RESYNC_INTERVAL_MS = 3000L

            /**
             * Downlink quality at or above this is "healthy". DJI reports this coarsely — only
             * 100/80/60 were ever observed — so anything below full is a real sag, not noise.
             */
            private const val LINK_DEGRADED_PCT = 100

            /** How often to poll downlink quality. 1s is far finer than the 5s health summary
             *  (a brief sag could start and end inside one summary window and be missed
             *  entirely) while costing one volatile read per second. */
            private const val LINK_CHECK_INTERVAL_MS = 1000L
        }
    }

    companion object {
        private const val TAG = "FpvTextureView"
    }
}

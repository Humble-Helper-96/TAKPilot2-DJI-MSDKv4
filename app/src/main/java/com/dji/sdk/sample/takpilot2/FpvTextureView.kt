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
 * TAKPILOT2_V4_PORT_PLAN.md "Video debugging journal" for the full root-cause history).
 *
 * Two things DJICodecManager doesn't handle that this does:
 *  - The Mini 2 emits NO SPS/PPS/IDR in steady state — it sends a keyframe only when asked.
 *    A dormant, off-screen DJICodecManager is kept solely as that "ask for a keyframe" lever
 *    ([IdrRequesterHolder]; it never renders anything itself). That holder is a process-wide
 *    singleton, NOT owned per-surface here — DJI's native video engine doesn't tolerate being
 *    destroyed/recreated on every surface cycle (screen lock/unlock, screen navigation); see
 *    the holder's doc for the in-flight black-FPV incident that fix addressed.
 *  - VideoFeeder.onReceive() bytes are transport-sized chunks (~2 KB), NOT NAL-aligned. An
 *    assembler splits the Annex-B stream (3- or 4-byte start codes) into whole NALs, queues
 *    them boundedly (dumping + resyncing at the next SPS on overflow, so latency can never
 *    accumulate), and a decode thread renders only the newest ready frame each pass.
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
 * `docs/TAKPILOT2_V4_PORT_PLAN.md` for the full diagnosis.
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
        private val nalQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)
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

        /** Set by [requestResync] (pilot-tapped Video Re-Sync). Handled at the top of the decode
         *  loop: drop the queue, go unsynced, and force the proven resetDecoder recovery now —
         *  the manual failsafe to clear accumulated static-scene artifacting on demand. */
        @Volatile private var resyncRequested = false
        fun requestResync() { resyncRequested = true }

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
                    AppLog.i(TAG, "FPV: SPS received — decoder syncing")
                } else return // drop until the requested keyframe burst arrives
            } else if (type == 5) {
                // IDR arriving while already synced — should only happen right after a
                // periodic-refresh or hard-resync request. Logged (rare, request-only) to
                // verify from the field whether those requests are actually landing, since
                // resetKeyFrame() has shown itself unreliable beyond a session's first use
                // (see IdrRequesterHolder doc) — if this line never appears during a long
                // static hover, the periodic anti-artifact refresh isn't working either.
                AppLog.i(TAG, "FPV: IDR received post-sync (periodic/hard-resync refresh landed)")
            }
            nalsFed.incrementAndGet()
            if (!nalQueue.offer(nal)) {
                // Queue full — the decoder fell persistently behind (rare now that transient
                // stalls hold-and-retry instead of dropping). Reset latency by dropping the
                // backlog and keeping the newest NAL, but do NOT force a resync/freeze: keep
                // decoding (brief concealed corruption, no freeze — the Mini 2 sends no keyframe
                // to recover to on its own anyway). The pilot's Video Re-Sync button clears any
                // lingering artifacting on demand.
                nalQueue.clear()
                nalQueue.offer(nal)
                overflowDrops.incrementAndGet()
                AppLog.w(TAG, "FPV: queue overflow — dropped backlog (no freeze), continuing")
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
        private var lastHealthSummary = 0L

        /** Emits one summary line and resets the since-last-summary counters. Reads
         *  [TakBridgeHolder]'s uplink signal, [ArOverlayView]'s and
         *  [com.dji.sdk.sample.tak.VideoStreamerHolder]'s global state — none of which need a
         *  view/streamer reference, the same "ask the singleton" pattern already used throughout
         *  this codebase — so a weak-signal flight and a CPU-contended one (AR + RTSP both
         *  running) leave a visibly different signature in this one line, instead of looking
         *  identical from drop/resync counts alone. */
        private fun logHealthSummary() {
            val fed = nalsFed.getAndSet(0)
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
            val sig = com.dji.sdk.sample.tak.TakBridgeHolder.hud()?.uplinkSignalPct
            AppLog.i(
                HEALTH_TAG,
                "fed=$fed rendered=$rendered drops=$drops autoResync=$autoR " +
                    "manualResync=$manualR codecRecover=$recov tier=$tier " +
                    "sig=${sig?.let { "$it%" } ?: "—"} AR=${ArOverlayView.isRunningAnywhere} " +
                    "RTSP=${com.dji.sdk.sample.tak.VideoStreamerHolder.isActive}",
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
                // A NAL we polled but couldn't feed yet because the codec's input was momentarily
                // full. Held (not dropped) and retried next iteration after draining output frees
                // a buffer — so a transient decoder stall no longer manufactures artifacting.
                var pendingNal: ByteArray? = null
                // Consecutive codec-level failures since the last successfully rendered frame —
                // drives the recreate backoff below and resets to 0 the moment a frame renders.
                var codecFailures = 0

                while (running.get()) {
                    val now = System.currentTimeMillis()

                    if (now - lastHealthSummary >= HEALTH_SUMMARY_INTERVAL_MS) {
                        lastHealthSummary = now
                        logHealthSummary()
                    }

                    // Pilot-tapped Video Re-Sync failsafe: clear the backlog, go unsynced, and
                    // fire the proven resetDecoder recovery immediately (don't wait out the 3s
                    // auto-escalation) to clear accumulated artifacting on demand.
                    if (resyncRequested) {
                        resyncRequested = false
                        pendingNal = null
                        nalQueue.clear()
                        waitForSync = true
                        unsyncedSince = 0L
                        lastSyncReq = 0L        // let onSyncNeeded fire right away while unsynced
                        lastHardResync = now    // we're firing the hard resync now
                        manualResyncs.incrementAndGet()
                        AppLog.i(TAG, "FPV: manual re-sync requested by pilot")
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
                        // Feed one NAL if the codec has room (SPS/PPS ride in-band). Prefer a NAL
                        // we were holding from a prior stalled iteration; otherwise poll a fresh
                        // one.
                        val nal = pendingNal ?: nalQueue.poll(10, TimeUnit.MILLISECONDS)
                        pendingNal = null
                        if (nal != null) {
                            val inIdx = cd.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                cd.getInputBuffer(inIdx)?.apply { clear(); put(nal) }
                                cd.queueInputBuffer(inIdx, 0, nal.size, pts, 0)
                                pts += 33_333
                            } else {
                                // Codec input momentarily full — HOLD this NAL (don't drop it) and
                                // retry next iteration once draining output below frees a buffer.
                                // Dropping here used to manufacture persistent artifacting on any
                                // transient stall (GPU contention w/ the map/HUD); the overflow
                                // failsafe below still bounds latency if we fall behind for real.
                                pendingNal = nal
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
                        // interrupt()) interrupting the blocked nalQueue.poll() above. Field-
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
                        pendingNal = null
                        nalQueue.clear()
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
            private const val QUEUE_CAP = 60 // ~2s at 30fps before we dump + resync
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
        }
    }

    companion object {
        private const val TAG = "FpvTextureView"
    }
}

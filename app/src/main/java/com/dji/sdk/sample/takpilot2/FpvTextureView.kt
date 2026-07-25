package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.AttributeSet
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

        val d = DecoderThread(Surface(surface))
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
        decoder?.requestResync()
    }

    /**
     * Owns the MediaCodec: assembles NALs from raw chunks, decodes, and renders newest-only.
     */
    private class DecoderThread(private val surface: Surface) : Thread("FpvDecoder") {

        private val running = AtomicBoolean(true)
        private val nalQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAP)
        @Volatile private var waitForSync = true // drop everything until an SPS arrives

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
            if (!nalQueue.offer(nal)) {
                // Queue full — the decoder fell persistently behind (rare now that transient
                // stalls hold-and-retry instead of dropping). Reset latency by dropping the
                // backlog and keeping the newest NAL, but do NOT force a resync/freeze: keep
                // decoding (brief concealed corruption, no freeze — the Mini 2 sends no keyframe
                // to recover to on its own anyway). The pilot's Video Re-Sync button clears any
                // lingering artifacting on demand.
                nalQueue.clear()
                nalQueue.offer(nal)
                AppLog.w(TAG, "FPV: queue overflow — dropped backlog (no freeze), continuing")
            }
        }

        // ---- Decode loop (this thread) ----
        override fun run() {
            var codec: MediaCodec? = null
            try {
                codec = MediaCodec.createDecoderByType(MIME).apply {
                    val fmt = MediaFormat.createVideoFormat(MIME, 1280, 720)
                    fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = realtime
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                    configure(fmt, surface, null, 0)
                    start()
                }
                val info = MediaCodec.BufferInfo()
                var pts = 0L
                // A NAL we polled but couldn't feed yet because the codec's input was momentarily
                // full. Held (not dropped) and retried next iteration after draining output frees
                // a buffer — so a transient decoder stall no longer manufactures artifacting.
                var pendingNal: ByteArray? = null

                while (running.get()) {
                    val now = System.currentTimeMillis()

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

                    // Feed one NAL if the codec has room (SPS/PPS ride in-band). Prefer a NAL we
                    // were holding from a prior stalled iteration; otherwise poll a fresh one.
                    val nal = pendingNal ?: nalQueue.poll(10, TimeUnit.MILLISECONDS)
                    pendingNal = null
                    if (nal != null) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            codec.getInputBuffer(inIdx)?.apply { clear(); put(nal) }
                            codec.queueInputBuffer(inIdx, 0, nal.size, pts, 0)
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
                    var outIdx = codec.dequeueOutputBuffer(info, 0)
                    var lastIdx = -1
                    while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val f = codec.outputFormat
                            // Prefer the crop rect — it gives the true visible size.
                            val w = if (f.containsKey("crop-right"))
                                f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                            else f.getInteger(MediaFormat.KEY_WIDTH)
                            val h = if (f.containsKey("crop-bottom"))
                                f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                            else f.getInteger(MediaFormat.KEY_HEIGHT)
                            onVideoSize?.invoke(w, h)
                        } else {
                            if (lastIdx >= 0) codec.releaseOutputBuffer(lastIdx, false)
                            lastIdx = outIdx
                        }
                        outIdx = codec.dequeueOutputBuffer(info, 0)
                    }
                    if (lastIdx >= 0) codec.releaseOutputBuffer(lastIdx, true)
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
        }
    }

    companion object {
        private const val TAG = "FpvTextureView"
    }
}

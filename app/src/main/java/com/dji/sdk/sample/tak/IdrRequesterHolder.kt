package com.dji.sdk.sample.tak

import android.content.Context
import android.graphics.SurfaceTexture
import com.taklite.util.AppLog
import dji.sdk.codec.DJICodecManager

/**
 * Process-wide holder for the dormant, off-screen [DJICodecManager] that
 * [com.dji.sdk.sample.takpilot2.FpvTextureView] uses purely as a "send me a keyframe" lever
 * (it never renders anything itself — see that class's doc for the full FPV pipeline).
 *
 * Root-caused 2026-07-23 (in-flight FPV freeze, see docs/TAKPILOT2_V4_PORT_SUMMARY.md §6): this used to
 * be created fresh in every FpvTextureView.onSurfaceTextureAvailable() and torn down in
 * onSurfaceTextureDestroyed() — i.e. once per screen-lock/unlock or Home<->Flight navigation.
 * DJI's underlying native video engine (logcat tag "Lightbridge") doesn't tolerate that churn:
 * destroy-then-recreate in quick succession left it wedged ("startStream videoCtlobjet ==
 * NULL"), permanently breaking keyframe requests — a black FPV that didn't recover even
 * across a brand-new Activity/View instance, because the broken state lives in DJI's
 * process-wide native singleton, not in anything this app owns. Only a full app kill cleared
 * it. Fix: create this exactly once per process and never destroy it — same pattern as
 * [TakBridgeHolder] surviving screen navigation.
 */
object IdrRequesterHolder {
    private const val TAG = "IdrRequesterHolder"
    private var codecManager: DJICodecManager? = null
    private var texture: SurfaceTexture? = null

    /** Creates the dormant codec manager on first call; a no-op on every call after. */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (codecManager != null) return
        try {
            val tex = SurfaceTexture(0).also { it.setDefaultBufferSize(96, 96) }
            texture = tex
            codecManager = DJICodecManager(context.applicationContext, tex, 96, 96)
            AppLog.i(TAG, "created process-wide IDR-request codec manager")
        } catch (t: Throwable) {
            AppLog.w(TAG, "could not create IDR requester: ${t.message}")
        }
    }

    /** Lightweight nudge: asks the aircraft for a fresh keyframe burst. Observed (2026-07-24
     *  in-flight testing) to reliably work only the FIRST time a given process's decode
     *  session goes from cold to synced — a second FpvTextureView instance (e.g. after
     *  Home->Flight navigation) calling this repeatedly never got a fresh SPS, even though
     *  this object itself survived correctly (no native crash, no recreation). Apparently
     *  DJICodecManager's internal keyframe-request state doesn't consider itself needing to
     *  re-arm for what is, from the aircraft's perspective, a second/different decode
     *  session. See [forceResync] for the escalation this feeds into. */
    fun requestKeyframe() {
        codecManager?.resetKeyFrame()
    }

    /** Harder reset: forces DJICodecManager to fully reset its internal decoder state (SPS/
     *  PPS renegotiation from scratch), rather than just asking for one more keyframe on top
     *  of whatever state it thinks it already has. Intended as an escalation when
     *  [requestKeyframe] alone hasn't produced a fresh sync within a bounded timeout (see
     *  FpvTextureView.DecoderThread's hard-resync escalation) — not called on a fixed
     *  interval, since this is a heavier operation than the keyframe nudge. */
    fun forceResync() {
        try {
            codecManager?.resetDecoder()
            AppLog.i(TAG, "forced decoder resync (resetDecoder)")
        } catch (t: Throwable) {
            AppLog.w(TAG, "forceResync failed: ${t.message}")
        }
    }

    /**
     * Set by [com.dji.sdk.sample.takpilot2.FpvTextureView] while its decoder is alive: forces
     * the ON-SCREEN decoder into a genuine unsync → resetDecoder recovery. That is the ONLY
     * mechanism field-proven (2026-07-24) to make the Mini 2 actually emit a fresh SPS/PPS/IDR
     * — direct [requestKeyframe]/[forceResync] on this dormant lever are silently ignored
     * whenever DJI's decoder believes it's already healthy (which it always is once FPV is up).
     * The keyframe the aircraft emits in response flows into the shared VideoFeeder stream, so
     * a SEPARATE listener (the RTSP streamer) receives it too. Null when no flight-screen FPV
     * decoder is active. Costs a brief on-screen FPV glitch — inherent to passthrough, since
     * FPV IS the only decoder that can be resynced.
     */
    @Volatile
    var fpvResync: (() -> Unit)? = null

    /** Get a fresh keyframe by the reliable path (FPV resync) when a decoder is on screen,
     *  else fall back to the best-effort dormant lever. */
    fun requestFreshKeyframe() {
        val hook = fpvResync
        if (hook != null) {
            AppLog.i(TAG, "requestFreshKeyframe: via FPV decoder resync (reliable)")
            hook()
        } else {
            AppLog.w(TAG, "requestFreshKeyframe: no FPV decoder active — best-effort dormant lever")
            forceResync()
        }
    }
}

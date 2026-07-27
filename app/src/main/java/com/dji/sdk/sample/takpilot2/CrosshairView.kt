package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * ATAK-UAS-Tool-style center reticle, drawn over the FPV video's actual content area (not the
 * screen center — the video is left-pillarboxed, so those differ). A sibling overlay rather than
 * drawn inside [FpvTextureView] because TextureView locks down both onDraw() and draw() (it owns
 * SurfaceTexture rendering), so [videoRect] is fed in from [FpvTextureView.onVideoRectChanged].
 *
 * Marks where the camera is pointed — today just a visual reference for the pilot; the plan is
 * to let a future tap here drop a marker/SPoI at the look-point (Phase 6/7 territory), once
 * marker-placement exists at all.
 */
class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val videoRect = RectF()

    fun setVideoRect(rect: RectF) {
        videoRect.set(rect)
        invalidate()
    }

    /**
     * Centre-ring colour as a marker-accuracy cue, driven by gimbal pitch AND whether DTED
     * covers the aircraft's current position.
     *
     * Ground-point accuracy falls off as `1/sin²(pitch)` — the shallower the look angle, the
     * more a given pitch or bearing error smears along the ground. So the same marker drop is
     * an order of magnitude tighter looking steeply down than obliquely, and the pilot has no
     * other way to see that. Thresholds: [PITCH_GOOD_DEG]/[PITCH_FAIR_DEG] (with DTED),
     * [PITCH_GOOD_DEG_NO_DTED]/[PITCH_FAIR_DEG_NO_DTED] (without) — steeper is required for the
     * same trust level without DTED, because the flat-ground assumption stacks an extra error
     * source on top of the same geometric term.
     *
     * Only the centre ring is tinted; the arms stay white so the reticle reads the same as a
     * sighting reference regardless of state.
     *
     * @param dtedAvailable whether DTED covers the aircraft's CURRENT position — i.e. whether a
     *   marker dropped right now would actually get [CameraSlantPoint]'s terrain-corrected
     *   solve, not just whether any DTED is loaded anywhere. Defaults false (the stricter
     *   thresholds) so an omitted argument fails toward more caution, not less.
     */
    fun setGimbalPitch(pitchDeg: Double?, dtedAvailable: Boolean = false) {
        val next = accuracyColorFor(pitchDeg, dtedAvailable)
        if (next == ringColor) return   // avoid invalidating on every HUD tick
        ringColor = next
        ring.color = next
        invalidate()
    }

    private var ringColor = Color.WHITE
    /** Twice the arms' weight (1.5f): the ring carries the accuracy state, so it should read
     *  at a glance without the pilot looking for it. */
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    /** Matching heavier outline, so the thicker ring keeps its dark edge on bright ground. */
    private val ringOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 160
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 160
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    /** Tap inside the reticle — quick-drop a marker at the look point. Set by the flight screen. */
    var onReticleTap: (() -> Unit)? = null

    /** Long-press inside the reticle — re-aim the existing quick-drop marker. */
    var onReticleLongPress: (() -> Unit)? = null

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onReticleTap?.invoke()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            // The action fires without any visible press state (there's no button here to
            // highlight), so the buzz is the only confirmation the long-press registered.
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onReticleLongPress?.invoke()
        }
    })

    /**
     * Touches are claimed ONLY within [HIT_RADIUS_DP] of the reticle centre.
     *
     * This view is `match_parent` and sits over the whole video, so consuming everything would
     * silently swallow every future touch on the FPV area. Rejecting at ACTION_DOWN — rather
     * than filtering later — means a touch that starts outside the reticle is never routed here
     * at all and the rest of its gesture goes wherever it would have gone before.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onReticleTap == null && onReticleLongPress == null) return false
        if (videoRect.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = HIT_RADIUS_DP * resources.displayMetrics.density
            val dist = hypot(event.x - videoRect.centerX(), event.y - videoRect.centerY())
            if (dist > r) return false
        }
        return gesture.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoRect.isEmpty) return
        val cx = videoRect.centerX()
        val cy = videoRect.centerY()
        val armLen = 22f * resources.displayMetrics.density
        val gap = 6f * resources.displayMetrics.density
        val ringR = 5f * resources.displayMetrics.density
        // Arms: dark outline then white, unchanged — the sighting reference stays constant.
        for (p in arrayOf(outline, line)) {
            canvas.drawLine(cx - armLen, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + armLen, cy, p)
            canvas.drawLine(cx, cy - armLen, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + armLen, p)
        }
        // Centre ring carries the accuracy state; outlined first so it stays legible on a
        // bright background whatever colour it is.
        canvas.drawCircle(cx, cy, ringR, ringOutline)
        canvas.drawCircle(cx, cy, ringR, ring)
    }

    companion object {
        /**
         * Tap target around the reticle centre, in dp. Comfortably larger than the drawn reticle
         * (22dp arms) because the pilot is aiming at it with a thumb while flying, but well short
         * of covering the video — an over-wide target here would eat FPV touches for no visible
         * reason.
         */
        private const val HIT_RADIUS_DP = 34f

        /**
         * Steeper than this WITH DTED coverage, a marker drop is worth trusting. Set to -25
         * from field results: the operator reported acceptable placement at ~100 ft AGL and
         * -20 deg, so -30 was stricter than the hardware actually warrants and left the ring
         * amber during perfectly good drops. Roughly +/-10 ft of ground accuracy at this
         * boundary.
         */
        const val PITCH_GOOD_DEG = -25.0

        /**
         * Between this and [PITCH_GOOD_DEG] WITH DTED: usable, but a degree of pointing error
         * is already tens of feet on the ground. Roughly +/-50 ft of ground accuracy at this
         * boundary. Both this and [PITCH_GOOD_DEG] assume good inputs: a weak GPS fix or a
         * magnetically noisy hover degrades them regardless of look angle.
         */
        const val PITCH_FAIR_DEG = -10.0

        /**
         * Steeper than this WITHOUT DTED coverage at the aircraft's current position, a marker
         * drop is worth trusting. Field-calibrated 2026-07-27 on the RT3 (no DTED loaded):
         * drops read roughly +/-50 ft at this boundary — compare [PITCH_GOOD_DEG]'s +/-10 ft
         * WITH DTED at the same trust level. Steeper is required without DTED because the
         * flat-ground assumption ([CameraSlantPoint.computeFlat]) stacks its own error on top
         * of the same 1/sin²(pitch) geometric term DTED coverage would otherwise correct for.
         */
        const val PITCH_GOOD_DEG_NO_DTED = -30.0

        /**
         * Between this and [PITCH_GOOD_DEG_NO_DTED] WITHOUT DTED: roughly +/-100 ft of ground
         * accuracy at this boundary, field-calibrated alongside [PITCH_GOOD_DEG_NO_DTED].
         */
        const val PITCH_FAIR_DEG_NO_DTED = -15.0

        private val ACCURACY_GOOD = Color.parseColor("#4CAF50")
        private val ACCURACY_FAIR = Color.parseColor("#FFEB3B")

        /** Shallower than the fair threshold, either way — a drop here is worth actively
         *  discouraging, not just leaving unmarked (the previous white/neutral state). Red
         *  regardless of DTED availability; only which pitch triggers it differs. */
        private val ACCURACY_POOR = Color.parseColor("#F44336")

        /**
         * Single source for the accuracy tint, shared with the HUD's gimbal readout so the
         * number and the reticle cannot disagree about what state the pilot is in.
         *
         * @param dtedAvailable selects which threshold pair applies — see the constants' docs.
         *   Defaults false (the stricter, no-DTED pair) so an omitted argument fails toward
         *   showing worse accuracy than is actually the case, never better.
         */
        fun accuracyColorFor(pitchDeg: Double?, dtedAvailable: Boolean = false): Int {
            val good = if (dtedAvailable) PITCH_GOOD_DEG else PITCH_GOOD_DEG_NO_DTED
            val fair = if (dtedAvailable) PITCH_FAIR_DEG else PITCH_FAIR_DEG_NO_DTED
            return when {
                pitchDeg == null -> Color.WHITE
                pitchDeg <= good -> ACCURACY_GOOD
                pitchDeg <= fair -> ACCURACY_FAIR
                else -> ACCURACY_POOR
            }
        }
    }
}

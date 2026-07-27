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
     * Centre-ring colour as a marker-accuracy cue, driven by gimbal pitch.
     *
     * Ground-point accuracy falls off as `1/sin²(pitch)` — the shallower the look angle, the
     * more a given pitch or bearing error smears along the ground. Measured at ~40 m up:
     * roughly 4.5 ft of ground error per degree at -45°, 19 ft at -20°, 65 ft at -10°. So the
     * same marker drop is an order of magnitude tighter looking steeply down than obliquely,
     * and the pilot has no other way to see that. Thresholds: [PITCH_GOOD_DEG]/[PITCH_FAIR_DEG].
     *
     * Only the centre ring is tinted; the arms stay white so the reticle reads the same as a
     * sighting reference regardless of state.
     */
    fun setGimbalPitch(pitchDeg: Double?) {
        val next = accuracyColorFor(pitchDeg)
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
         * Steeper than this, a marker drop is worth trusting. Set to -25 from field results:
         * the operator reported acceptable placement at ~100 ft AGL and -20 deg, so -30 was
         * stricter than the hardware actually warrants and left the ring amber during
         * perfectly good drops.
         */
        const val PITCH_GOOD_DEG = -25.0

        /**
         * Between this and [PITCH_GOOD_DEG]: usable, but a degree of pointing error is already
         * tens of feet on the ground. Shallower than this is unmarked — below about -10 the
         * 1/sin^2 term runs away fast enough that a placement isn't worth quoting a figure for.
         *
         * With good GPS and DTED coverage, expect roughly +/-50 ft of ground accuracy in this
         * band against +/-10 ft in the green one. Both assume good inputs: a weak GPS fix or a
         * magnetically noisy hover degrades them regardless of look angle.
         */
        const val PITCH_FAIR_DEG = -10.0

        private val ACCURACY_GOOD = Color.parseColor("#4CAF50")
        private val ACCURACY_FAIR = Color.parseColor("#FFEB3B")

        /**
         * Single source for the accuracy tint, shared with the HUD's gimbal readout so the
         * number and the reticle cannot disagree about what state the pilot is in.
         */
        fun accuracyColorFor(pitchDeg: Double?): Int = when {
            pitchDeg == null -> Color.WHITE
            pitchDeg <= PITCH_GOOD_DEG -> ACCURACY_GOOD
            pitchDeg <= PITCH_FAIR_DEG -> ACCURACY_FAIR
            else -> Color.WHITE
        }
    }
}

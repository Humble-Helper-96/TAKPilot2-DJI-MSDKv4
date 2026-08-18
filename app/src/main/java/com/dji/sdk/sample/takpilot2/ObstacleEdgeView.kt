package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dji.common.flightcontroller.VisionSensorPosition
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R

/**
 * Obstacle proximity drawn as arcs on the edges of the FPV, one per aircraft face.
 *
 * PORTED FROM THE AUTEL BUILD (`takpilot-autel_v1-2/.../tak/ObstacleEdgeView.kt`), which was
 * itself modelled on Autel Explorer after a wall strike. Keeping the visual language identical is
 * the point: the operator flies both aircraft, and an arc bowing in from the edge nearest the
 * obstacle must mean the same thing in both apps. Amber at moderate range, red when close,
 * distance printed on the arc.
 *
 * **Divergence from the Autel view, deliberate: forward is drawn here.** The Autel version omits
 * a front indicator on the reasoning that an obstacle dead ahead is already in the video. That
 * holds for the EVO II, which senses six ways and still has five indicators left. The Air 2S
 * senses forward, backward, up and down and has NO lateral sensors, so the same rule would leave
 * the display blank in the one direction the aircraft actually flies. Forward therefore gets a
 * readout — a captioned chevron pointing up-and-away, mirroring REAR, so the two read as a pair
 * and neither can be confused with the up/down arcs.
 *
 * **Up and down are NOT drawn yet.** Their distances come from a separate feed whose units the
 * SDK does not document (see [com.dji.sdk.sample.tak.DjiObstacleState.logPerception]); they are
 * being logged until a measured hover confirms the scale. An unverified number on a collision
 * display is worse than no number.
 *
 * **Units need no assumption here.** DJI reports metres by API contract, unlike the Autel radar
 * whose centimetre scale had to be inferred and field-validated. Feet for display is one
 * conversion at the point of drawing.
 *
 * Draws inside [videoRect], not the whole view: the video is pillarboxed to the left so the HUD
 * and mini-map can own the right strip, and an arc on the view's right edge would sit under the
 * instrument column instead of on the picture. Same rect [CrosshairView] uses.
 */
class ObstacleEdgeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // Flight-tuned HUD colours, resolved once per view. Were literals here until
    // 2026-08-14 (conformance A1); the values are unchanged — see takpilot_colors.xml.
    private val COLOR_DANGER = ContextCompat.getColor(context, R.color.tp_hud_obstacle_danger)
    private val COLOR_WARN = ContextCompat.getColor(context, R.color.tp_hud_obstacle_warn)

    private val videoRect = RectF()

    /** Fed from [FpvTextureView.onVideoRectChanged], exactly as the crosshair is. */
    fun setVideoRect(rect: RectF) {
        videoRect.set(rect)
        invalidate()
    }

    /** Nearest obstacle per face, in METRES. Empty hides everything. */
    private var faces: Map<VisionSensorPosition, Float> = emptyMap()

    fun update(newFaces: Map<VisionSensorPosition, Float>) {
        faces = newFaces
        invalidate()
    }

    fun clear() {
        faces = emptyMap()
        invalidate()
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    // Reused across draws. This view redraws at the sensor's push rate, so onDraw must not
    // allocate: the chevron Path is reset and refilled, and one FontMetrics is filled in place
    // (the `fontMetrics` property allocates a fresh object on every read).
    private val chevronPath = Path()
    private val fontMetrics = Paint.FontMetrics()

    override fun onDraw(canvas: Canvas) {
        if (videoRect.isEmpty || faces.isEmpty()) return
        faces[VisionSensorPosition.LEFT]?.let { drawEdge(canvas, it, Side.LEFT) }
        faces[VisionSensorPosition.RIGHT]?.let { drawEdge(canvas, it, Side.RIGHT) }
        // Forward and rear as a matched chevron pair — see the class note.
        faces[VisionSensorPosition.NOSE]?.let { drawChevron(canvas, it, forward = true) }
        faces[VisionSensorPosition.TAIL]?.let { drawChevron(canvas, it, forward = false) }
    }

    private enum class Side { LEFT, RIGHT }

    private fun drawEdge(canvas: Canvas, meters: Float, side: Side) {
        if (meters > WARN_M) return                      // nothing worth showing

        // Nearer = more opaque, thicker and redder. A binary red/not-red gives the pilot no sense
        // of closing rate, which is the thing they actually steer on.
        val t = (1f - (meters / WARN_M)).coerceIn(0f, 1f)
        arcPaint.style = Paint.Style.STROKE
        arcPaint.color = if (meters <= DANGER_M) COLOR_DANGER else COLOR_WARN
        arcPaint.alpha = (90 + 165 * t).toInt().coerceAtMost(255)
        arcPaint.strokeWidth = dp(5f) + dp(9f) * t

        val bow = dp(26f) + dp(30f) * t                  // how far the arc bows inward
        val inset = dp(4f)
        val span = 0.62f                                 // fraction of the edge the arc covers
        val h = videoRect.height()
        val len = h * span
        val cyMid = videoRect.centerY()

        val cx: Float
        when (side) {
            Side.LEFT -> {
                rect.set(videoRect.left + inset - bow, cyMid - len / 2f,
                         videoRect.left + inset + bow, cyMid + len / 2f)
                canvas.drawArc(rect, -70f, 140f, false, arcPaint)
                cx = videoRect.left + inset + bow + dp(20f)
            }
            Side.RIGHT -> {
                rect.set(videoRect.right - inset - bow, cyMid - len / 2f,
                         videoRect.right - inset + bow, cyMid + len / 2f)
                canvas.drawArc(rect, 110f, 140f, false, arcPaint)
                cx = videoRect.right - inset - bow - dp(20f)
            }
        }
        drawLabel(canvas, cx, cyMid, meters)
    }

    /**
     * Forward and rear proximity, as captioned chevrons rather than edge arcs.
     *
     * Neither direction has an honest edge to live on: the top edge means UP and the bottom edge
     * means DOWN, so hanging fore/aft on them would make two different hazards look identical on
     * a display whose whole job is to be read at a glance. A chevron is a shape nothing else here
     * uses — pointing away from the aircraft in the direction of the hazard, captioned so it can
     * never be misread. Rear was requested by the operator on the Autel build after flying
     * without it: behind the aircraft is the one direction the camera cannot show, which is
     * exactly where a readout earns the most.
     */
    private fun drawChevron(canvas: Canvas, meters: Float, forward: Boolean) {
        if (meters > WARN_M) return

        val t = (1f - (meters / WARN_M)).coerceIn(0f, 1f)
        arcPaint.style = Paint.Style.STROKE
        arcPaint.color = if (meters <= DANGER_M) COLOR_DANGER else COLOR_WARN
        arcPaint.alpha = (110 + 145 * t).toInt().coerceAtMost(255)
        arcPaint.strokeWidth = dp(4f) + dp(5f) * t

        val cx = videoRect.centerX()
        val cy = if (forward) videoRect.top + dp(FORE_DROP)
                 else videoRect.bottom - dp(REAR_LIFT)

        // Two stacked chevrons. Forward points UP (away from the pilot, into the scene); rear
        // points DOWN-AND-BACK. The opposite sense is what makes the pair instantly readable.
        val half = dp(20f)
        val drop = dp(9f)
        for (i in 0 until 2) {
            val yBase = if (forward) cy - dp(13f) - i * dp(9f) else cy + dp(13f) + i * dp(9f)
            val tip = if (forward) yBase - drop else yBase + drop
            chevronPath.reset()
            chevronPath.moveTo(cx - half, yBase)
            chevronPath.lineTo(cx, tip)
            chevronPath.lineTo(cx + half, yBase)
            canvas.drawPath(chevronPath, arcPaint)
        }

        drawLabel(canvas, cx, cy, meters, if (forward) "FWD " else "REAR ")
    }

    /** Distance in feet on a filled pill, matching Explorer's readout. */
    private fun drawLabel(canvas: Canvas, cx: Float, cy: Float, meters: Float, caption: String = "") {
        val text = caption + "%.0fft".format(meters * FEET_PER_METRE)
        textPaint.textSize = dp(15f)
        val tw = textPaint.measureText(text)
        val padH = dp(8f); val padV = dp(5f)
        val fm = fontMetrics.also { textPaint.getFontMetrics(it) }
        rect.set(cx - tw / 2f - padH, cy - (-fm.ascent) - padV,
                 cx + tw / 2f + padH, cy + fm.descent + padV)
        labelBg.color = arcPaint.color
        labelBg.alpha = 235
        canvas.drawRoundRect(rect, dp(5f), dp(5f), labelBg)
        canvas.drawText(text, cx, cy, textPaint)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    companion object {
        /** Start drawing at this range, go red at this one. METRES — no inference needed, the
         *  SDK documents the unit. ~39 ft and ~13 ft, matching the Autel build's thresholds so
         *  the two apps warn at the same distances. */
        private const val WARN_M = 12f
        private const val DANGER_M = 4f

        // Precomputed so onDraw never runs Color.parseColor (a string parse + allocation) per
        // face per frame. Red inside DANGER_M, amber beyond it.

        private const val FEET_PER_METRE = 3.28084f

        /** dp in from the video's top/bottom edges for the fore/aft chevrons. The rear figure
         *  clears the bottom arc's maximum bow plus its label, inherited from the Autel view;
         *  the forward one clears the toolbar. */
        private const val REAR_LIFT = 118f
        private const val FORE_DROP = 96f
    }
}

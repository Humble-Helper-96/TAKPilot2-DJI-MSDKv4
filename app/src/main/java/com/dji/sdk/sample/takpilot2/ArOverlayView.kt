package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.dji.sdk.sample.tak.CameraSlantPoint
import com.dji.sdk.sample.tak.DroneTakBridge
import com.dji.sdk.sample.tak.TakBridgeHolder
import com.dji.sdk.sample.tak.TakDropMarkers
import com.taklite.util.AppLog
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Augmented-reality overlay: projects TAK marker positions onto the live FPV so they appear
 * pinned to the world. Third port of TAKPilot2's ArOverlayView (V5 → V4), with three deliberate
 * departures from the reference — see below.
 *
 * **Sub-phase A: dropped pins only.** Inbound TAK contacts come in 6B of the phase plan. Pins
 * are first on purpose: a pin is placed at [TakBridgeHolder.lookPoint], which is derived from
 * the same camera pose this view projects with, so **a freshly dropped pin must render dead
 * centre under the crosshair.** That is a ground-truth test needing no second TAK client, no
 * survey point and no flying — if it doesn't land under the crosshair, the projection, the pose
 * or the video rect is wrong, and there's no point adding contacts on top of that.
 *
 * ### Departures from the V5 reference
 *
 * 1. **Perspective, not linear, projection.** V5 maps angle to pixels linearly
 *    (`x = cx + Δbearing / (hFov/2) · halfW`), which is a small-angle approximation. A real lens
 *    is gnomonic, so this uses `tan(Δ)/tan(fov/2)`. At the Mini 2's 73° horizontal FOV the
 *    difference is visible toward the frame edges — and it vanishes at the centre, which is what
 *    makes it easy to miss when eyeballing a marker in the middle of frame.
 * 2. **Draws to the video rectangle, not the view.** [FpvTextureView] letterboxes the image
 *    inside the view; projecting against view bounds shifts everything by the size of the bars.
 *    Same rect [CrosshairView] already uses, fed the same way.
 * 3. **Pitch sign.** DJI reports gimbal pitch negative when looking down; screen Y grows
 *    downward. Both conventions are handled once, in [project], rather than at each call site.
 *
 * Accuracy is bounded by gimbal bearing accuracy and by telemetry lagging the video — markers
 * will swim during fast gimbal movement. This is a "which of those buildings" tool, not a
 * survey instrument; the crosshair drop remains the precise one.
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Video image bounds within this view, fed from [FpvTextureView.onVideoRectChanged]. */
    private val videoRect = RectF()

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    /** Faster than the 500ms HUD tick — at 500ms a marker visibly steps across the frame during
     *  a gimbal sweep. Every frame composites over live video, so this is the one knob to back
     *  off first if the FPV frame rate suffers (that pipeline must not regress). */
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    fun setVideoRect(rect: RectF) {
        videoRect.set(rect)
        invalidate()
    }

    fun start() {
        if (running) return
        running = true
        visibility = VISIBLE
        handler.removeCallbacks(tick)
        handler.post(tick)
        AppLog.i(TAG, "AR overlay ON")
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tick)
        visibility = GONE
        AppLog.i(TAG, "AR overlay OFF")
    }

    val isRunning: Boolean get() = running

    // ---- Paints ----

    private val d get() = resources.displayMetrics.density

    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }

    private val iconCache = HashMap<Int, Bitmap>()

    // Throttled so a persistent "why is nothing drawing" condition logs once rather than at the
    // refresh rate — this runs several times a second.
    private var lastSkipReason: String? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        if (videoRect.isEmpty) return skipped("no video rect yet")

        val pose = TakBridgeHolder.cameraPose() ?: return skipped("no camera pose (GPS/gimbal)")
        val hud = TakBridgeHolder.hud() ?: return skipped("no telemetry")
        if (!hud.hasFix) return skipped("no GPS fix")
        lastSkipReason = null

        val pins = TakDropMarkers.listPins()
        if (pins.isEmpty()) return

        // Throttle per DRAW PASS, not per pin — a per-call throttle only ever logs whichever pin
        // happens to be first in the list, which hid a second marker's trace entirely during
        // troubleshooting.
        val now = System.currentTimeMillis()
        val logThisPass = now - lastDiagMs >= DIAG_INTERVAL_MS
        if (logThisPass) lastDiagMs = now

        // Aircraft altitude in the same reference the pins carry (DTED MSL). Without a terrain
        // reference we can't form a vertical angle at all, so pins are drawn flat-on — better
        // than inventing a height and pinning them to the wrong part of the image.
        val aircraftMsl = com.dji.sdk.sample.tak.TerrainAgl.reading(context, hud).mslMeters

        for (pin in pins) {
            // Ground (great-circle) distance — what the label shows, matching the home-distance
            // and markers-list convention used elsewhere in the app.
            val groundDist = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, pin.lat, pin.lon)
            if (groundDist > MAX_RANGE_M) continue

            val bearing = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, pin.lat, pin.lon)
            // Wrapped to -180..180 so a target behind the aircraft doesn't project as if it were
            // far off to one side.
            val dBearing = ((bearing - pose.bearingDeg + 540.0) % 360.0) - 180.0

            // Height of the pin relative to the aircraft; negative = below, the normal case.
            // Zero when there's no MSL reference, which flattens the geometry (see above).
            val dz = if (aircraftMsl != null) pin.alt - aircraftMsl else 0.0

            // Reject on SLANT range, never on ground distance. Aiming steeply down — which is
            // exactly how a marker gets dropped on something beneath the aircraft — drives
            // ground distance toward zero while the pin is still tens of metres away and
            // perfectly visible. Guarding on ground distance made near-nadir markers
            // undrawable, which is the one case the crosshair self-test most naturally lands in.
            val slantRange = kotlin.math.hypot(groundDist, dz)
            if (slantRange < MIN_RANGE_M) continue

            // Depression angle: rise over run against the ground distance, so straight-down
            // correctly approaches -90 degrees.
            val elevDeg = Math.toDegrees(atan2(dz, groundDist))
            val dElev = elevDeg - pose.pitchDeg

            val xy = project(dBearing, dElev)
            if (logThisPass) diag(pin, pose, groundDist, dz, bearing, dBearing, elevDeg, dElev, xy)
            if (xy == null) continue
            drawPin(canvas, xy.first, xy.second, pin, groundDist)
        }
    }

    /**
     * Angular offset from the camera axis → pixel, or null if outside the frame.
     *
     * Gnomonic (true perspective) rather than the reference's linear mapping: screen offset is
     * proportional to `tan` of the angle, normalised by `tan` of the half-FOV.
     *
     * Guarded at ±85° because tan blows up approaching 90° — without it a target off to the side
     * or behind produces an astronomically large coordinate rather than simply being off-frame.
     */
    private fun project(dBearingDeg: Double, dElevDeg: Double): Pair<Float, Float>? {
        if (abs(dBearingDeg) >= MAX_PROJECT_ANGLE || abs(dElevDeg) >= MAX_PROJECT_ANGLE) return null

        val halfH = Math.toRadians(DroneTakBridge.hFovDeg() / 2.0)
        val halfV = Math.toRadians(DroneTakBridge.vFovDeg() / 2.0)
        val nx = tan(Math.toRadians(dBearingDeg)) / tan(halfH)
        val ny = tan(Math.toRadians(dElevDeg)) / tan(halfV)
        if (abs(nx) > 1.0 || abs(ny) > 1.0) return null   // off-frame; edge arrows come in 6D-C

        val x = videoRect.centerX() + (nx * videoRect.width() / 2.0).toFloat()
        // Screen Y grows downward, camera elevation grows upward — hence the subtraction.
        val y = videoRect.centerY() - (ny * videoRect.height() / 2.0).toFloat()
        return x to y
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float, pin: TakDropMarkers.PinInfo, dist: Double) {
        val size = (ICON_DP * d).toInt()
        // MUST rasterise through the drawable, not BitmapFactory. The affiliation markers are
        // VectorDrawable XML, and BitmapFactory.decodeResource returns null for those — the
        // original version of this silently drew nothing while the projection logged a correct
        // on-screen position, which is about the most misleading failure available. The mini-map
        // has always done it this way; reuse it rather than keeping a second rasteriser.
        val bmp = iconCache.getOrPut(pin.affiliation.res) {
            com.dji.sdk.sample.tak.TakMapMarkers.drawableToBitmap(context, pin.affiliation.res, size)
                ?: run {
                    // Loud, not silent: a marker the pilot cannot see is a marker they will
                    // assume is not there.
                    AppLog.w(TAG, "icon ${pin.affiliation.label} failed to rasterise — not drawn")
                    return
                }
        }
        canvas.drawBitmap(bmp, x - size / 2f, y - size / 2f, iconPaint)

        labelPaint.textSize = LABEL_SP * d
        val text = "${pin.name}  ${Units.distance(dist)}"
        val tw = labelPaint.measureText(text)
        val fm = labelPaint.fontMetrics
        val top = y + size / 2f + 4 * d
        canvas.drawRoundRect(
            x - tw / 2 - 5 * d, top, x + tw / 2 + 5 * d, top + (fm.descent - fm.ascent) + 3 * d,
            3 * d, 3 * d, labelBg,
        )
        canvas.drawText(text, x, top - fm.ascent + 1.5f * d, labelPaint)
    }

    /**
     * Per-pin projection trace, throttled to once a second.
     *
     * Exists because a pin that projects outside the frame is otherwise discarded in total
     * silence — the overlay looks identical whether the maths is right and the target is
     * genuinely off-screen, or the maths is wrong. These are the numbers needed to tell those
     * apart: if `dBrg` is near zero the camera really is pointed at the pin, so an off-frame
     * result means the FOV or the projection is at fault, not the pose.
     */
    private fun diag(
        pin: TakDropMarkers.PinInfo,
        pose: DroneTakBridge.CameraPose,
        groundDist: Double,
        dz: Double,
        bearing: Double,
        dBearing: Double,
        elevDeg: Double,
        dElev: Double,
        xy: Pair<Float, Float>?,
    ) {
        AppLog.d(
            TAG,
            "pin='${pin.name}' gDist=%.1fm dz=%.1fm | camBrg=%.1f pinBrg=%.1f dBrg=%.1f | " .format(
                groundDist, dz, pose.bearingDeg, bearing, dBearing,
            ) + "camPitch=%.1f pinElev=%.1f dElev=%.1f | fov=%.0fx%.0f | %s".format(
                pose.pitchDeg, elevDeg, dElev,
                DroneTakBridge.hFovDeg(), DroneTakBridge.vFovDeg(),
                if (xy == null) "OFF-FRAME (not drawn)"
                else "drawn at %.0f,%.0f in rect %.0f,%.0f-%.0f,%.0f".format(
                    xy.first, xy.second,
                    videoRect.left, videoRect.top, videoRect.right, videoRect.bottom,
                ),
            ),
        )
    }

    private var lastDiagMs = 0L

    private fun skipped(reason: String) {
        if (lastSkipReason != reason) {
            lastSkipReason = reason
            AppLog.v(TAG, "AR not drawing: $reason")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tick)
        iconCache.clear()
    }

    companion object {
        private const val TAG = "TP2Ar"
        private const val REFRESH_MS = 100L
        private const val ICON_DP = 26f
        private const val LABEL_SP = 11f
        /** Slant range below which the pin is effectively at the camera and the angles stop
         *  meaning anything. Deliberately compared against slant range, not ground distance —
         *  see the loop. */
        private const val MIN_RANGE_M = 2.0
        /** Beyond this a marker is a speck the pilot can't act on; also the first line of
         *  defence against a busy TAK picture carpeting the video. */
        private const val MAX_RANGE_M = 5000.0
        private const val MAX_PROJECT_ANGLE = 85.0
        /** Projection trace cadence — the draw loop runs at 10Hz, which is far too fast to log. */
        private const val DIAG_INTERVAL_MS = 1000L
    }
}

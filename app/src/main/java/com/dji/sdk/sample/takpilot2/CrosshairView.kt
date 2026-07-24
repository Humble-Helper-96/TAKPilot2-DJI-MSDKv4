package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoRect.isEmpty) return
        val cx = videoRect.centerX()
        val cy = videoRect.centerY()
        val armLen = 22f * resources.displayMetrics.density
        val gap = 6f * resources.displayMetrics.density
        val ringR = 5f * resources.displayMetrics.density
        for (p in arrayOf(outline, line)) {
            canvas.drawLine(cx - armLen, cy, cx - gap, cy, p)
            canvas.drawLine(cx + gap, cy, cx + armLen, cy, p)
            canvas.drawLine(cx, cy - armLen, cx, cy - gap, p)
            canvas.drawLine(cx, cy + gap, cx, cy + armLen, p)
            canvas.drawCircle(cx, cy, ringR, p)
        }
    }
}

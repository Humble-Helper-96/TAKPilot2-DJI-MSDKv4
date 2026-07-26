package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Small circular battery gauge for the flight-screen toolbar — a colored ring sweeps out the
 * charge percentage with the number centered inside, ATAK-UAS-Tool style, in place of a plain
 * icon + text pair.
 */
class BatteryGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var percent: Int? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(60, 255, 255, 255)
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

    private val arcRect = RectF()

    fun setPercent(pct: Int?) {
        percent = pct
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val strokeWidth = w * STROKE_FRACTION
        trackPaint.strokeWidth = strokeWidth
        arcPaint.strokeWidth = strokeWidth
        textPaint.textSize = w * TEXT_FRACTION
        val inset = strokeWidth / 2f
        arcRect.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        val pct = percent
        if (pct != null) {
            // Banded gauge, not a single color for the whole filled arc: the 0–15% (Critical),
            // 16–30% (Warning), 31–100% (Good) zones each keep their own color as the ring
            // fills, like a fuel gauge's redline band — so at e.g. 80% you see a thin red
            // wedge, a thin amber wedge, then a large green wedge, not one solid green ring.
            val sweepTotal = 360f * (pct.coerceIn(0, 100) / 100f)
            val criticalEnd = 360f * (CRITICAL_PCT / 100f)
            val warningEnd = 360f * (WARNING_PCT / 100f)

            val critSweep = sweepTotal.coerceAtMost(criticalEnd)
            if (critSweep > 0f) {
                arcPaint.color = COLOR_CRITICAL
                canvas.drawArc(arcRect, -90f, critSweep, false, arcPaint)
            }
            if (sweepTotal > criticalEnd) {
                val warnSweep = sweepTotal.coerceAtMost(warningEnd) - criticalEnd
                arcPaint.color = COLOR_WARNING
                canvas.drawArc(arcRect, -90f + criticalEnd, warnSweep, false, arcPaint)
            }
            if (sweepTotal > warningEnd) {
                val goodSweep = sweepTotal - warningEnd
                arcPaint.color = COLOR_GOOD
                canvas.drawArc(arcRect, -90f + warningEnd, goodSweep, false, arcPaint)
            }
        }

        val label = pct?.toString() ?: "—"
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, textY, textPaint)
    }

    companion object {
        private const val STROKE_FRACTION = 0.12f
        private const val TEXT_FRACTION = 0.34f
        private const val CRITICAL_PCT = 15f
        private const val WARNING_PCT = 30f
        private val COLOR_CRITICAL = 0xFFF44336.toInt()
        private val COLOR_WARNING = 0xFFFFB74D.toInt()
        private val COLOR_GOOD = 0xFF4CAF50.toInt()
    }
}

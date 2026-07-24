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
        arcPaint.color = colorFor(pct)
        if (pct != null) {
            val sweep = 360f * (pct.coerceIn(0, 100) / 100f)
            canvas.drawArc(arcRect, -90f, sweep, false, arcPaint)
        }

        val label = pct?.toString() ?: "—"
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, textY, textPaint)
    }

    private fun colorFor(pct: Int?): Int = when {
        pct == null -> Color.argb(60, 255, 255, 255)
        pct <= 15 -> 0xFFF44336.toInt()
        pct <= 30 -> 0xFFFFB74D.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    companion object {
        private const val STROKE_FRACTION = 0.12f
        private const val TEXT_FRACTION = 0.34f
    }
}

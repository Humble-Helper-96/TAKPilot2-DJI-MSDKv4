package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Record-to-SD badge — a "REC" pill with a fixed dot on the left, same fixed-icon-badge
 * pattern as [LiveToggleView] (not a sliding switch): gray pill + gray dot when not recording,
 * red pill + red dot when recording, matching the classic "● REC" badge look.
 */
class RecordToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var isRecording: Boolean = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val trackRect = RectF()

    fun setRecording(recording: Boolean) {
        isRecording = recording
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        trackRect.set(0f, 0f, w.toFloat(), h.toFloat())
        textPaint.textSize = h * 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        val radius = h / 2f

        val stateColor = if (isRecording) COLOR_RECORDING else COLOR_IDLE
        trackPaint.color = stateColor
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        // Knob sits fixed at the left end — only the dot/pill color changes with state.
        val knobInset = h * 0.08f
        val knobRadius = radius - knobInset
        val knobCy = h / 2f
        val knobCx = radius
        canvas.drawCircle(knobCx, knobCy, knobRadius, knobPaint)

        dotPaint.color = stateColor
        canvas.drawCircle(knobCx, knobCy, knobRadius * 0.55f, dotPaint)

        // "REC" is centered in the region right of the knob, so it's never covered.
        val textAreaStart = knobCx + knobRadius
        val textCenterX = (textAreaStart + w) / 2f
        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("REC", textCenterX, textY, textPaint)
    }

    companion object {
        private val COLOR_IDLE = Color.parseColor("#3A3A3A")
        private val COLOR_RECORDING = Color.parseColor("#E53935")
    }
}

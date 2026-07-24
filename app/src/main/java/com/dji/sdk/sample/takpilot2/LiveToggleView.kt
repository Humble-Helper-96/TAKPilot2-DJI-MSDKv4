package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Live-stream badge — a "LIVE" pill with a fixed icon knob on the LEFT (matching
 * [RecordToggleView] so both badges read as "toggled left = off/paused"). Not a sliding switch:
 * the pill just swaps between two static looks — black/gray + pause icon when off, red/white +
 * play icon when live — like the reference badge images, so "LIVE" always stays fully readable
 * instead of being covered by a moving knob.
 */
class LiveToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var isLive: Boolean = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val trackRect = RectF()
    private val iconPath = Path()

    fun setLive(live: Boolean) {
        isLive = live
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

        trackPaint.color = if (isLive) COLOR_LIVE_TRACK else COLOR_OFF_TRACK
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        // Knob sits fixed at the LEFT end in both states — matching RecordToggleView so both
        // badges read as "toggled left = off/paused" consistently; only its icon/color changes.
        val knobInset = h * 0.08f
        val knobRadius = radius - knobInset
        val knobCy = h / 2f
        val knobCx = radius
        canvas.drawCircle(knobCx, knobCy, knobRadius, knobPaint)

        // "LIVE" is centered in the region right of the knob, so the knob never covers it.
        val textAreaStart = knobCx + knobRadius
        val textCenterX = (textAreaStart + w) / 2f
        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("LIVE", textCenterX, textY, textPaint)

        iconPaint.color = if (isLive) COLOR_LIVE_TRACK else COLOR_OFF_TRACK
        val iconRadius = knobRadius * 0.42f
        if (isLive) {
            // Play triangle, pointing right.
            iconPath.reset()
            iconPath.moveTo(knobCx - iconRadius * 0.7f, knobCy - iconRadius)
            iconPath.lineTo(knobCx - iconRadius * 0.7f, knobCy + iconRadius)
            iconPath.lineTo(knobCx + iconRadius, knobCy)
            iconPath.close()
            canvas.drawPath(iconPath, iconPaint)
        } else {
            // Pause bars.
            val barW = iconRadius * 0.55f
            val gap = iconRadius * 0.35f
            canvas.drawRect(
                knobCx - gap - barW, knobCy - iconRadius,
                knobCx - gap, knobCy + iconRadius, iconPaint
            )
            canvas.drawRect(
                knobCx + gap, knobCy - iconRadius,
                knobCx + gap + barW, knobCy + iconRadius, iconPaint
            )
        }
    }

    companion object {
        private val COLOR_OFF_TRACK = Color.parseColor("#3A3A3A")
        private val COLOR_LIVE_TRACK = Color.parseColor("#E53935")
    }
}

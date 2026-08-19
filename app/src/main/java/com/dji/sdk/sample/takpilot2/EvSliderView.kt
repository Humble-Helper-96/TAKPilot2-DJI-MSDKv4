package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R

/**
 * EV-compensation slider for the flight screen: a static full-width line (always visible from
 * end to end, unlike a stock SeekBar which only tints left of the thumb), three small unlabeled
 * tick marks crossing the line at the 1/4, 1/2, 3/4 points (i.e. -1, 0, +1 on a -2..+2 scale),
 * and a draggable thumb dot. Snaps to [steps] + 1 discrete positions.
 */
class EvSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Number of intervals; positions are 0..steps (default 12 → 13 stops for -2..+2 in 1/3s). */
    var steps: Int = 12

    var index: Int = steps / 2
        set(value) {
            val clamped = value.coerceIn(0, steps)
            if (clamped != field) {
                field = clamped
                invalidate()
            }
        }

    /** Fired on user drag/tap (fromUser = true); programmatic [index] sets don't call it. */
    var onIndexChanged: ((Int, Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val thumbRadius = 7f * density
    private val trackInset = thumbRadius + 1f * density
    private val tickHalf = 4f * density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_accent)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tp_accent)
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (28 * density).toInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val left = trackInset
        val right = width - trackInset

        // Static full-width line.
        canvas.drawLine(left, cy, right, cy, linePaint)

        // Three ticks crossing the line at 1/4, 1/2, 3/4.
        for (f in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            val x = left + (right - left) * f
            canvas.drawLine(x, cy - tickHalf, x, cy + tickHalf, tickPaint)
        }

        // Thumb.
        val fx = if (steps <= 0) 0.5f else index.toFloat() / steps
        val thumbX = left + (right - left) * fx
        canvas.drawCircle(thumbX, cy, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val left = trackInset
                val right = width - trackInset
                val f = ((event.x - left) / (right - left)).coerceIn(0f, 1f)
                val newIndex = (f * steps).roundToInt().coerceIn(0, steps)
                if (newIndex != index) {
                    index = newIndex
                    onIndexChanged?.invoke(newIndex, true)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

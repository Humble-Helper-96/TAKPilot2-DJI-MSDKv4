package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R

/**
 * Live-stream badge — a "LIVE" pill with a fixed icon knob on the LEFT (matching
 * [RecordToggleView] so both badges read as "toggled left = off/paused"). Not a sliding switch:
 * the pill just swaps between static looks — black/gray + pause icon when off, red/white + play
 * icon when live, amber/white + a blinking sync icon while [DroneVideoStreamer] is retrying a
 * dropped RTSP connection — like the reference badge images, so "LIVE" always stays fully
 * readable instead of being covered by a moving knob.
 *
 * The RECONNECTING state exists so a pilot watching a network blip doesn't mistake "paused" for
 * "off" and tap LIVE expecting a fresh start — mid auto-reconnect, tapping LIVE cancels it
 * (VideoStreamerHolder.stop()), same as tapping it while live stops it.
 */
class LiveToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class State { OFF, LIVE, RECONNECTING }

    private var state: State = State.OFF
    private var blinkOn = true

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

    // FLIGHT-TUNED colours, resolved once here rather than parsed from literals. See the note in
    // res/values/takpilot_colors.xml: the values are results, not preferences. The active red is
    // shared with RecordToggleView on purpose — both mean "this is going out right now".
    private val colorOffTrack = ContextCompat.getColor(context, R.color.tp_hud_toggle_off)
    private val colorLiveTrack = ContextCompat.getColor(context, R.color.tp_hud_toggle_active)
    private val colorReconnectTrack = ContextCompat.getColor(context, R.color.tp_hud_toggle_reconnect)

    // Reconnect-ring scratch. Fields rather than locals in onDraw — see the RECONNECTING branch.
    private val sweepRect = RectF()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            if (state == State.RECONNECTING) blinkHandler.postDelayed(this, BLINK_INTERVAL_MS)
        }
    }

    /** Back-compat two-state setter; prefer [setState]. */
    fun setLive(live: Boolean) = setState(if (live) State.LIVE else State.OFF)

    fun setState(newState: State) {
        val wasReconnecting = state == State.RECONNECTING
        state = newState
        if (newState == State.RECONNECTING && !wasReconnecting) {
            blinkOn = true
            blinkHandler.removeCallbacks(blinkRunnable)
            blinkHandler.postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
        } else if (newState != State.RECONNECTING && wasReconnecting) {
            blinkHandler.removeCallbacks(blinkRunnable)
            blinkOn = true
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        blinkHandler.removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
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

        val trackColor = when (state) {
            State.LIVE -> colorLiveTrack
            State.RECONNECTING -> if (blinkOn) colorReconnectTrack else colorOffTrack
            State.OFF -> colorOffTrack
        }
        trackPaint.color = trackColor
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        // Knob sits fixed at the LEFT end in every state — matching RecordToggleView so both
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
        canvas.drawText(if (state == State.RECONNECTING) "SYNC" else "LIVE", textCenterX, textY, textPaint)

        iconPaint.color = trackColor
        val iconRadius = knobRadius * 0.42f
        when (state) {
            State.LIVE -> {
                // Play triangle, pointing right.
                iconPath.reset()
                iconPath.moveTo(knobCx - iconRadius * 0.7f, knobCy - iconRadius)
                iconPath.lineTo(knobCx - iconRadius * 0.7f, knobCy + iconRadius)
                iconPath.lineTo(knobCx + iconRadius, knobCy)
                iconPath.close()
                canvas.drawPath(iconPath, iconPaint)
            }
            State.RECONNECTING -> {
                // Circular reconnect arrows — a broken ring with two arrowheads.
                // Rect and paint are reused fields, not fresh objects: this branch is the
                // BLINKING one, so it redraws twice a second for as long as the stream is down —
                // exactly the state where the app is already under stress and should not be
                // handing the collector work every frame.
                sweepRect.set(knobCx - iconRadius, knobCy - iconRadius,
                    knobCx + iconRadius, knobCy + iconRadius)
                ringPaint.color = trackColor
                ringPaint.strokeWidth = iconRadius * 0.35f
                canvas.drawArc(sweepRect, -30f, 150f, false, ringPaint)
                canvas.drawArc(sweepRect, 150f, 150f, false, ringPaint)
            }
            State.OFF -> {
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
    }

    companion object {
        private const val BLINK_INTERVAL_MS = 500L
    }
}

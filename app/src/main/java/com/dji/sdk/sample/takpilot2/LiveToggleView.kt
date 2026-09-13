package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
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
 * Live-stream badge — a glyph and a label, drawn as a toggle pill to the same specification as
 * the pills beside it. Specification §6.7. Ported from the Autel sibling 2026-09-13
 * (conformance D18).
 *
 * Three states: a play triangle and "LIVE" in white on the neutral pill when off, the SAME
 * triangle and label in red when the stream is up, and a blinking sync ring with "SYNC" in
 * amber while the streamer is retrying a dropped RTSP connection.
 *
 * ⚠ **THE OFF STATE KEEPS THE PLAY TRIANGLE — IT MUST NOT GO BACK TO A PAUSE GLYPH.** Off and
 * live differ by COLOUR ALONE, which is how every other pill works: AR reads "AR" in white or in
 * green, never "AR" against some other word. A pause glyph also said the wrong thing — it is the
 * symbol for a stream that is SUSPENDED and could resume where it left off, while this control
 * is stopped and starts a new stream. The triangle says what the next tap does; the colour says
 * what is happening now.
 *
 * The RECONNECTING state exists so a pilot watching a network blip does not read a retry as a
 * stopped stream and tap LIVE expecting a fresh start — mid auto-reconnect, tapping LIVE
 * cancels it (VideoStreamerHolder.stop()), same as tapping it while live stops it. It is the
 * one state that changes both the glyph AND the word, because it is the one state where the
 * next tap does something other than what the triangle promises.
 *
 * ## It was a switch, and the knob was why
 *
 * See [RecordToggleView] for the whole reasoning — the two changed together. In short: a large
 * white knob circle at one end of a round track is the universal affordance for a slider, so
 * both controls read as something to drag. Squaring the corner alone did not fix it.
 *
 * ⚠ **RED AND AMBER ARE NOT GREEN, AND THAT IS THE POINT.** Green on the neighbouring pills
 * means "this feature is on". Red here means "the picture is going out to the team" and amber
 * means "the link dropped and we are retrying". Only the hue departs from the neighbours; the
 * treatment — a 30 % wash, a full-strength stroke, and the content in the same colour — does
 * not.
 */
class LiveToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class State { OFF, LIVE, RECONNECTING }

    private var state: State = State.OFF
    private var blinkOn = true

    // FLIGHT-TUNED colours, resolved once here rather than parsed from literals. See the note in
    // res/values/takpilot_colors.xml: the values are results, not preferences. The active red is
    // shared with RecordToggleView on purpose — both mean "this is going out right now".
    private val colorLive = ContextCompat.getColor(context, R.color.tp_hud_toggle_active)
    private val colorReconnect = ContextCompat.getColor(context, R.color.tp_hud_toggle_reconnect)
    private val colorIdleContent = ContextCompat.getColor(context, R.color.tp_text_primary)

    /** The pill's shape and idle look, shared with the drawable-backed pills beside it. */
    private val cornerRadius = resources.getDimension(R.dimen.hud_pill_radius)
    private val pillStroke = resources.getDimension(R.dimen.hud_pill_stroke)
    private val idleFill = ContextCompat.getColor(context, R.color.tp_pill_idle_fill)
    private val idleStroke = ContextCompat.getColor(context, R.color.tp_pill_idle_stroke)
    private val liveFill = ContextCompat.getColor(context, R.color.tp_pill_live_fill)
    private val syncFill = ContextCompat.getColor(context, R.color.tp_pill_sync_fill)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = pillStroke
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    private val trackRect = RectF()
    private val iconPath = Path()

    // Reconnect-ring scratch. Rect and paint are reused FIELDS, not fresh objects: that branch
    // is the BLINKING one, so it redraws twice a second for as long as the stream is down —
    // exactly the state where the app is already under stress and should not be handing the
    // collector work every frame.
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
        // No-op when nothing moved. The flight screen re-asserts this on every HUD tick so the
        // pill tracks the stream's real state, and without this guard that was a needless
        // invalidate() twice a second for the whole flight. [RecordToggleView.setRecording]
        // already had this guard; this one did not until 2026-09-13.
        if (newState == state) return
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
        // ⚠ INSET BY HALF THE STROKE, OR THE BORDER IS CLIPPED FLAT — see RecordToggleView.
        val half = pillStroke / 2f
        trackRect.set(half, half, w - half, h - half)
        textPaint.textSize = h * 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()

        // The blink dims the WHOLE pill back to idle, not just the glyph, so a retry reads at a
        // glance from the same distance the solid states do.
        val lit = state == State.LIVE || (state == State.RECONNECTING && blinkOn)
        val content = when {
            !lit -> colorIdleContent
            state == State.LIVE -> colorLive
            else -> colorReconnect
        }
        fillPaint.color = when {
            !lit -> idleFill
            state == State.LIVE -> liveFill
            else -> syncFill
        }
        strokePaint.color = if (lit) content else idleStroke
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, strokePaint)

        // Glyph and label are CENTRED AS ONE GROUP — see RecordToggleView for why.
        val iconRadius = h * 0.13f
        val gap = h * 0.16f
        val label = if (state == State.RECONNECTING) "SYNC" else "LIVE"
        val textWidth = textPaint.measureText(label)
        val groupWidth = iconRadius * 2f + gap + textWidth
        val startX = (w - groupWidth) / 2f
        val cy = h / 2f
        val iconCx = startX + iconRadius

        textPaint.color = content
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, startX + iconRadius * 2f + gap, textY, textPaint)

        iconPaint.color = content
        if (state == State.RECONNECTING) {
            // Circular reconnect arrows — a broken ring with two arrowheads.
            sweepRect.set(iconCx - iconRadius, cy - iconRadius, iconCx + iconRadius, cy + iconRadius)
            ringPaint.color = content
            ringPaint.strokeWidth = iconRadius * 0.35f
            canvas.drawArc(sweepRect, -30f, 150f, false, ringPaint)
            canvas.drawArc(sweepRect, 150f, 150f, false, ringPaint)
        } else {
            // Play triangle, pointing right — in BOTH the off and the live state. See the note
            // on the class about why "off" does not get a pause glyph.
            iconPath.reset()
            iconPath.moveTo(iconCx - iconRadius * 0.7f, cy - iconRadius)
            iconPath.lineTo(iconCx - iconRadius * 0.7f, cy + iconRadius)
            iconPath.lineTo(iconCx + iconRadius, cy)
            iconPath.close()
            canvas.drawPath(iconPath, iconPaint)
        }
    }

    companion object {
        private const val BLINK_INTERVAL_MS = 500L
    }
}

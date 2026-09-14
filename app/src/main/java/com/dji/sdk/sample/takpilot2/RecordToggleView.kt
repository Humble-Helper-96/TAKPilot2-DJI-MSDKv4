package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R

/**
 * Record-to-SD badge — a dot and the word "REC", drawn as a toggle pill to the same
 * specification as the pills beside it. Specification §6.7. Ported from the Autel sibling
 * 2026-09-13 (conformance D18).
 *
 * ## What it was, and why that was wrong
 *
 * It was a SWITCH: a fully-round capsule on a flat grey fill with a large white knob circle at
 * the left end. Squaring the corner to `hud_pill_radius` was tried first on the sibling and made
 * it worse — a switch with a border. **The knob was the problem.** A big filled circle at one
 * end of a rounded track is the universal affordance for a slider, so the control read as
 * something the pilot drags rather than something they tap, whatever the corner did.
 *
 * ## What it is now
 *
 * The same treatment as [R.drawable.bg_pill_active], hue changed: a 30 % wash of the state
 * colour, a full-strength stroke of it, and the CONTENT — the dot and the label — in that same
 * colour. Idle takes the neutral fill and stroke with white content. Nothing is white-on-solid
 * any more, and the pills in the capsule read as one family.
 *
 * ⚠ **RED IS NOT GREEN, AND THAT IS THE POINT.** Green on the pills beside this one means "this
 * feature is on". Red here means "the camera is writing to the card" — a different question, and
 * the one a pilot must never misread. Only the hue departs from the neighbours; the treatment
 * does not.
 *
 * ## In stills mode it is a SHUTTER (specification §6.7, ledger D25; ported 2026-09-14)
 *
 * When the CAMERA is in stills, the dot and the word give way to the still-camera symbol
 * alone, and a tap takes a photo. The RC-N1's photo/video toggle moves the camera without this
 * application being asked and leaves it there — measured on the bench 2026-09-14 — and this
 * pill changing shape is the second cue, where the pilot's thumb already is; the HUD readout
 * says it in words a few lines away. Same symbol as that readout, from [CameraGlyphs].
 *
 * ⚠ THE PILL FOLLOWS THE CAMERA; IT DOES NOT SET IT. Stills mode takes the idle treatment and
 * no colour: a shutter is a momentary action, not a state that is on, and red must keep its
 * one meaning. The word was tried on the Autel tree and removed — glyph plus PHOTO filled the
 * pill edge to edge, and the pill cannot widen (§4.2).
 */
class RecordToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // FLIGHT-TUNED, from the token file. The recording red is deliberately the same value
    // LiveToggleView uses for LIVE — both badges mean "this is going out right now".
    private val colorRecording = ContextCompat.getColor(context, R.color.tp_hud_toggle_active)
    private val colorIdleContent = ContextCompat.getColor(context, R.color.tp_text_primary)

    /** The pill's shape and idle look, shared with the drawable-backed pills beside it. */
    private val cornerRadius = resources.getDimension(R.dimen.hud_pill_radius)
    private val pillStroke = resources.getDimension(R.dimen.hud_pill_stroke)
    private val idleFill = ContextCompat.getColor(context, R.color.tp_pill_idle_fill)
    private val idleStroke = ContextCompat.getColor(context, R.color.tp_pill_idle_stroke)
    private val liveFill = ContextCompat.getColor(context, R.color.tp_pill_live_fill)

    private var isRecording: Boolean = false

    /** True when the CAMERA is in stills mode, so this pill is a shutter. Read from the camera's
     *  own push through the HUD tick — never from what was asked. */
    private var photoMode: Boolean = false

    fun setPhotoMode(photo: Boolean) {
        if (photoMode == photo) return
        photoMode = photo
        // Owned here rather than by the caller: the description and the drawing are the same
        // fact, and setting it from the HUD tick would rewrite it twice a second for nothing.
        contentDescription = if (photo) "Take a photo. The camera is in photo mode."
        else "Start or stop recording to the aircraft SD card"
        invalidate()
    }

    /** The still-camera symbol for stills mode. Stroked, like the HUD readout's copy of it. */
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = pillStroke
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphPath = android.graphics.Path()
    private val glyphBox = RectF()

    /** The shutter symbol's size as a fraction of the pill's height — sits in the pill the way
     *  LIVE's content does. */
    private val GLYPH_FRACTION = 0.62f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = pillStroke
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    private val trackRect = RectF()

    fun setRecording(recording: Boolean) {
        // No-op when nothing moved — called from the 2 Hz HUD tick, so without this the view
        // invalidated twice a second for the entire flight regardless of recording state.
        if (recording == isRecording) return
        isRecording = recording
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // ⚠ INSET BY HALF THE STROKE, OR THE BORDER IS CLIPPED FLAT on all four sides. A stroke
        // is CENTRED on the path, thus its outer half falls outside the view, and the canvas
        // handed to onDraw is clipped to the view's bounds. A shape drawable does this for you;
        // a canvas does not. Same fault and same fix as OutlinedTextView.
        val half = pillStroke / 2f
        trackRect.set(half, half, w - half, h - half)
        textPaint.textSize = h * 0.4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()

        val content = if (isRecording) colorRecording else colorIdleContent
        fillPaint.color = if (isRecording) liveFill else idleFill
        strokePaint.color = if (isRecording) colorRecording else idleStroke
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, fillPaint)
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, strokePaint)

        // ⚠ STILLS MODE: THE SYMBOL ALONE, AND NO WORD. A shutter is a glyph in every camera a
        // pilot has used; the HUD readout already says PHOTO in words; and a lone symbol is a
        // stronger SILHOUETTE against REC's dot-and-word than a second word would be — which is
        // the mode cue this pill was changed to carry. Idle colours: see the class note.
        if (photoMode) {
            val glyphSize = h * GLYPH_FRACTION
            CameraGlyphs.still(glyphPath, glyphBox, (w - glyphSize) / 2f,
                h / 2f - glyphSize / 2f, glyphSize)
            glyphPaint.color = content
            canvas.drawPath(glyphPath, glyphPaint)
            return
        }

        // The dot and the label are CENTRED AS ONE GROUP, not pinned to the ends. The knob used
        // to hold the left end and the text was centred in what was left, so the two moved
        // independently when the view's width changed. Measuring the pair keeps the pill
        // readable at any width the layout gives it.
        val dotRadius = h * 0.10f
        val gap = h * 0.16f
        val label = "REC"
        val textWidth = textPaint.measureText(label)
        val groupWidth = dotRadius * 2f + gap + textWidth
        val startX = (w - groupWidth) / 2f
        val cy = h / 2f

        dotPaint.color = content
        canvas.drawCircle(startX + dotRadius, cy, dotRadius, dotPaint)

        textPaint.color = content
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, startX + dotRadius * 2f + gap, textY, textPaint)
    }
}

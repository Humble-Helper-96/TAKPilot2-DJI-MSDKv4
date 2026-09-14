package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R

/**
 * What the CAMERA is set to save: a still camera and `PHOTO`, or a movie camera and `VIDEO`.
 *
 * PORTED FROM THE AUTEL TREE 2026-09-14 (ledger D22, specification §4.3), unchanged but for the
 * package. The mode itself comes from [com.dji.sdk.sample.tak.MediaModePolicy], which reads the
 * camera's flat mode off the `SystemState` push the bridge already owns — no new SDK callback.
 *
 * ⚠ ON THIS TREE THE CONTROLLER IS AN RC-N1 WITH A PHOTO/VIDEO SWITCH AND A SHUTTER. Either
 * moves the camera without this application being asked, which is exactly the case this
 * readout exists for. The on-screen shutter pill also moves it (PHOTO_SINGLE for the shot,
 * then back to VIDEO_NORMAL) — the readout follows the camera through both.
 *
 * ⚠ **THE HARDWARE SHUTTER LEAVES THE CAMERA IN STILLS MODE AND IT STAYS THERE** (operator,
 * confirmed in the 2026-09-13 log). The camera returns to video only when something asks, which
 * in practice is REC. The picture changes shape and field of view with it — 4:3 against 16:9 —
 * and until this existed nothing on the screen said why.
 *
 * ## It is a READOUT and it must not become a control
 *
 * It began as the word alone and the operator asked for something more immediate — a glyph a
 * pilot reads without reading. That is what this is, and it stops there:
 *
 * ⚠ **NO PILL, NO FILL, NO STROKED OUTLINE AROUND THE PAIR.** Specification §6.7 gives the
 * rounded pill to things a pilot TOUCHES, and this application does not drive the media mode.
 * The camera is put in stills mode by a button this application neither commands nor observes.
 * A capsule here would be the same mistake the LIVE and REC switches made with their knob: an
 * affordance promising something the control cannot do. The glyph carries the meaning; the
 * absence of a frame carries "you are being told, not asked".
 *
 * ## How it draws
 *
 * The same two passes as [OutlinedTextView] — a black stroked pass, then the fill — applied to
 * the GLYPH as well as the word, so the whole thing is legible over snow, wet asphalt or a
 * white roof exactly as the readouts around it are. It uses `hud_text_outline_width`, the one
 * per-device edge weight the whole HUD shares, so it cannot drift away from its neighbours.
 *
 * ⚠ The glyph is stroked, not filled, so the outline pass has to be WIDER than the glyph's own
 * stroke or it simply covers it. Both come off the same dimen, the outline at its full width
 * and the glyph at half — one number, and the relationship is kept by construction.
 */
class MediaModeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** What the camera says. Null is UNKNOWN and is its own state — §4.6. */
    enum class Mode { PHOTO, VIDEO }

    private var mode: Mode? = null
    private var otherLabel: String? = null

    private val outlineWidth = resources.getDimension(R.dimen.hud_text_outline_width)
    private val textSize = resources.getDimension(R.dimen.flight_readout_text_size)
    private val unknownColor = ContextCompat.getColor(context, R.color.tp_state_unknown)
    private val outlineColor = ContextCompat.getColor(context, R.color.tp_hud_outline)

    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        textSize = this@MediaModeView.textSize
    }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        textSize = this@MediaModeView.textSize
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth
        color = outlineColor
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth / 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth * 2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = outlineColor
    }

    private val glyph = Path()
    private val body = RectF()

    /** Glyph box is square and a little taller than the cap height, so it reads as the same
     *  weight as the word beside it rather than as an afterthought. */
    private val glyphSize: Float get() = textSize * 1.15f
    private val gap: Float get() = textSize * 0.45f

    fun setMode(m: Mode?, unhandledName: String? = null) {
        if (m == mode && unhandledName == otherLabel) return
        mode = m
        otherLabel = unhandledName
        contentDescription = when {
            unhandledName != null -> "Camera mode $unhandledName"
            m == Mode.PHOTO -> "Camera is in photo mode"
            m == Mode.VIDEO -> "Camera is in video mode"
            else -> "Camera mode not known"
        }
        requestLayout()
        invalidate()
    }

    private fun label(): String = otherLabel ?: when (mode) {
        Mode.PHOTO -> "PHOTO"
        Mode.VIDEO -> "VIDEO"
        null -> "MODE"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // ⚠ HALF THE OUTLINE ON EVERY SIDE, or the stroke is clipped flat — the third fault in
        // specification §4.3, which OutlinedTextView met on its right edge and the EV thumb met
        // at the ends of its travel. A stroke is centred on the path, thus its outer half falls
        // outside whatever the content measures to.
        val pad = outlineWidth
        val w = glyphSize + gap + textFill.measureText(label()) + pad * 2
        val fm = textFill.fontMetrics
        val h = maxOf(glyphSize, fm.descent - fm.ascent) + pad * 2
        setMeasuredDimension(
            resolveSize(Math.ceil(w.toDouble()).toInt(), widthMeasureSpec),
            resolveSize(Math.ceil(h.toDouble()).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val colour = if (mode == null && otherLabel == null) unknownColor else Color.WHITE
        val pad = outlineWidth
        val cy = height / 2f
        val text = label()

        // RIGHT-ALIGNED AS A GROUP. The HUD column is right-aligned and this sits in it, so the
        // pair is measured and placed from the right edge rather than each part independently —
        // the same reason RecordToggleView centres its dot and label as one group.
        val textWidth = textFill.measureText(text)
        val groupRight = width - pad
        val textLeft = groupRight - textWidth
        val glyphLeft = textLeft - gap - glyphSize

        buildGlyph(glyphLeft, cy - glyphSize / 2f, glyphSize)
        canvas.drawPath(glyph, glyphStroke)
        glyphFill.color = colour
        canvas.drawPath(glyph, glyphFill)

        val baseline = cy - (textFill.descent() + textFill.ascent()) / 2f
        canvas.drawText(text, textLeft, baseline, textStroke)
        textFill.color = colour
        canvas.drawText(text, textLeft, baseline, textFill)
    }

    /**
     * A still camera, a movie camera, or a question mark.
     *
     * ⚠ THE SHAPES LIVE IN [CameraGlyphs] AND NOT HERE. The record pill becomes a shutter in
     * stills mode and draws the same still-camera symbol; a pilot learns one symbol per mode,
     * and two copies would drift apart the first time either file was touched alone.
     */
    private fun buildGlyph(left: Float, top: Float, size: Float) {
        when {
            otherLabel != null || mode == null -> CameraGlyphs.unknown(glyph, body, left, top, size)
            mode == Mode.PHOTO -> CameraGlyphs.still(glyph, body, left, top, size)
            else -> CameraGlyphs.movie(glyph, body, left, top, size)
        }
    }
}

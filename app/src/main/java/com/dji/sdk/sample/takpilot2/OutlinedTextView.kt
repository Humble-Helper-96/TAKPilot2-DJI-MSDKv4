package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.dji.sdk.sample.R

/**
 * A TextView with a BLACK OUTLINE around every glyph, for HUD readouts over live video.
 * Specification §4.3. Ported from the Autel sibling 2026-09-13 (conformance D15).
 *
 * ## Why this exists
 *
 * The readouts sit over a picture whose brightness the pilot does not control. White text over
 * snow, wet asphalt or a white roof washes out. Two answers were used before this one:
 *
 *  1. A DROP SHADOW (`hudText`). It is a soft halo below and to one side, not a border. The
 *     specification says plainly that it "is not enough by itself".
 *  2. A TRANSLUCENT PANEL behind each block (`bg_hud_panel`). It works, but it covers live
 *     video that the pilot is flying from. Its opacity was walked down three times on the
 *     sibling — 55 % to 40 % to 20 % — each step trading legibility for picture, because the
 *     two fight and a panel cannot win both.
 *
 * An outline ends that fight. A black border around each glyph is legible on ANY background,
 * light or dark, and it covers only the few pixels around the letters instead of a rectangle.
 * The panels go away and the pilot gets the video back.
 *
 * ## How it draws
 *
 * The text layout is drawn TWICE: first with a black stroked paint, then with the normal fill
 * on top. The [android.text.Layout] is drawn directly and NOT through `super.onDraw`, and the
 * paint's colour and style are set on the paint itself.
 *
 * ⚠ **NEVER USE `setTextColor` OR `setShadowLayer` INSIDE `onDraw` TO DO THIS.** Both call
 * `invalidate()`. On a view that is already drawing, that schedules another draw, which draws,
 * which schedules another — a permanent full-rate repaint of a view that sits over live video.
 * The obvious implementation of a two-pass outline has exactly that bug.
 *
 * Spans survive both passes, thus a [android.text.style.RelativeSizeSpan] on part of the text
 * is outlined at its own size — which is what the height readout needs (§4.4).
 *
 * ## The width is PER-DEVICE, and it is SMALL
 *
 * `hud_text_outline_width` is a dimen for the same reason `flight_readout_text_size` is. This
 * tree targets a PHONE and draws readouts at 12sp where the Autel controller draws them at
 * 18sp — so this tree's stroke is NOT that sibling's number. It matches MSDKv5, which also
 * draws at 12sp.
 *
 * ⚠ **THE DIMEN IS THE STROKE WIDTH, AND IT IS NOT DOUBLED HERE.** The stroke is CENTRED on
 * the glyph edge, thus half of it falls INSIDE the letter and is covered by the fill pass. A
 * value that sounds modest eats the letter from within. Keep the stroke near **1/8th of the
 * text size**. On the sibling a first attempt took its number from a CSS `-webkit-text-stroke`
 * mockup — where the same word means the VISIBLE half — then doubled it again in the view, and
 * the readouts rendered as unreadable black blobs.
 */
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** The stroke width itself — see the note above on why this is NOT doubled. */
    private val strokeWidthPx: Float =
        resources.getDimension(R.dimen.hud_text_outline_width)

    /** One token for every HUD edge — the readouts, the EV slider and the mini-map frame.
     *  Specification §6.1: colours come from takpilot_colors.xml, never from a literal here. */
    private val outlineColor: Int =
        androidx.core.content.ContextCompat.getColor(context, R.color.tp_hud_outline)

    init {
        // ⚠ ROOM FOR THE OUTLINE, OR IT IS CLIPPED FLAT.
        //
        // A TextView is measured for the glyphs' ADVANCE WIDTH, which is the fill. The stroke's
        // outer half falls OUTSIDE that, and the canvas handed to onDraw is clipped to the
        // view's bounds — so the last letter of every line loses its right-hand outline and
        // ends in a flat vertical cut. It looks like a rendering artefact; it is a measurement
        // one.
        //
        // Half the stroke on each side is exactly what escapes. Padding is used rather than a
        // wider onMeasure so the text keeps its own position inside the view and the column's
        // right edge stays where the layout puts it.
        //
        // ⚠ THIS COSTS COLUMN HEIGHT, AND THIS TREE HAS THE LEAST TO SPEND. See the budget in
        // the flight layout: the panels this replaced were paying for most of it, but the
        // margin is small. Re-measure with `dumpsys activity top` after adding an outlined view.
        val pad = kotlin.math.ceil(strokeWidthPx / 2f).toInt()
        setPadding(paddingLeft + pad, paddingTop + pad, paddingRight + pad, paddingBottom + pad)
    }

    override fun onDraw(canvas: Canvas) {
        val textLayout = layout
        if (strokeWidthPx <= 0f || textLayout == null) {
            super.onDraw(canvas)
            return
        }
        val p = paint
        val fillStroke = p.strokeWidth
        val fillJoin = p.strokeJoin

        canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())

        // Pass 1 — the outline. ROUND joins keep the corners of a thin glyph from growing
        // spikes, which MITER does at this stroke width relative to the stroke thickness.
        p.style = Paint.Style.STROKE
        p.strokeWidth = strokeWidthPx
        p.strokeJoin = Paint.Join.ROUND
        p.color = outlineColor
        textLayout.draw(canvas)

        // Pass 2 — the text itself, over the outline. The fill covers the INNER half of the
        // stroke, so the glyph keeps its full weight and only the outer half shows as a border.
        //
        // ⚠ THE COLOUR COMES FROM [currentTextColor], NOT FROM A VALUE SAVED ABOVE. TextView
        // sets the paint's colour from its resolved text colour inside `super.onDraw`, which
        // this method never calls — so the paint's colour is not the text colour, and saving
        // and restoring it just carries the wrong value forward. Reading it here is what makes
        // the text white. Getting this wrong renders the whole readout in near-black and looks
        // exactly like a stroke-width problem; on the sibling it survived the first correction.
        p.style = Paint.Style.FILL
        p.strokeWidth = fillStroke
        p.strokeJoin = fillJoin
        p.color = currentTextColor
        textLayout.draw(canvas)

        canvas.restore()
    }
}

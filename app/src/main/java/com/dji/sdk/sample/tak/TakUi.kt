package com.dji.sdk.sample.tak

import android.content.res.ColorStateList
import android.widget.Button
import androidx.core.content.ContextCompat
import com.dji.sdk.sample.R

/**
 * The button roles from `res/values/styles.xml`, for buttons built in CODE.
 *
 * A programmatic Button cannot take an XML style — `style=` is parsed at inflation, and there is
 * no runtime equivalent that applies a style's attributes to an existing view. So every button
 * this app creates in Kotlin used to carry the platform's default look, which on this device is
 * a pale grey that says nothing about what the button does. Next to the layout's green/blue/red
 * roles it reads as an unfinished screen.
 *
 * These helpers exist so there is still exactly ONE place each role's colour is decided: they
 * resolve the same `tp_btn_*` tokens the XML styles use. Do not set a backgroundTint by hand at
 * a call site — that is how the two paths drift.
 */
object TakUi {

    /** Green. Primary action: the thing the screen exists to do. */
    fun primary(button: Button) = role(button, R.color.tp_btn_primary)

    /** Blue. Informational or reversible: join, open, pull, refresh. */
    fun info(button: Button) = role(button, R.color.tp_btn_info)

    /** Slate. Neutral secondary. */
    fun neutral(button: Button) = role(button, R.color.tp_btn_neutral)

    /** Red. Destructive and hard to undo. */
    fun danger(button: Button) = role(button, R.color.tp_btn_danger)

    private fun role(button: Button, colorRes: Int) {
        val ctx = button.context
        button.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(ctx, colorRes))
        button.setTextColor(ContextCompat.getColor(ctx, R.color.tp_text_primary))
    }
}

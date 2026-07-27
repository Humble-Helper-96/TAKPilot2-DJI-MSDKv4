package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog

/**
 * Per-device FPV decoder health, learned in the field and remembered across sessions.
 *
 * `FpvTextureView`'s decode loop already escalates WITHIN a single session if the platform's
 * hardware AVC decoder proves unreliable (drop the low-latency hint, then fall back to a named
 * software decoder — see that class's doc, and the RT3/MediaTek case that motivated it). That
 * escalation resets every time the flight screen's video surface is recreated — leaving and
 * re-entering the screen, the app backgrounding — which means a device that has ALREADY proven
 * its hardware decoder broken pays the same failure ramp (two failed hardware attempts, each
 * with its own several-second DJI resync wait — roughly 45 seconds end to end, field-measured
 * on the RT3) every single time, forever, even though the outcome is already known.
 *
 * This persists what was learned so the SECOND and every later session can start at whichever
 * tier actually worked, no re-discovery needed. Keyed to the DEVICE (manufacturer + model), not
 * the app install, deliberately — swapping between a debug and release build, or updating the
 * app, must not lose what a device already taught us.
 */
object FpvDecoderHealth {
    private const val TAG = "FpvDecoderHealth"
    private const val PREFS = "takpilot2_fpv_decoder"

    private fun deviceKey(): String =
        "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
            .replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Should a fresh session even try the low-latency decode hint? False once this device has
     *  shown it can't handle it. */
    fun startWithLowLatency(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOW_LATENCY_OK + deviceKey(), true)

    /** Should a fresh session skip straight to the software decoder? True once this device has
     *  shown its hardware decoder is unreliable even without the low-latency hint. */
    fun startWithSoftwareDecoder(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOFTWARE_NEEDED + deviceKey(), false)

    /** Called from the tier-1 escalation (dropping the low-latency hint after a codec failure).
     *  Idempotent — cheap to call every time that tier fires, only actually writes once. */
    fun recordLowLatencyFailed(context: Context) {
        val key = KEY_LOW_LATENCY_OK + deviceKey()
        if (!prefs(context).getBoolean(key, true)) return
        prefs(context).edit().putBoolean(key, false).apply()
        AppLog.i(TAG, "learned: ${deviceKey()} needs the low-latency decode hint disabled")
    }

    /** Called from the tier-2 escalation (switching to the software decoder). Idempotent, same
     *  as [recordLowLatencyFailed]. */
    fun recordSoftwareDecoderNeeded(context: Context) {
        val key = KEY_SOFTWARE_NEEDED + deviceKey()
        if (prefs(context).getBoolean(key, false)) return
        prefs(context).edit().putBoolean(key, true).apply()
        AppLog.i(TAG, "learned: ${deviceKey()} needs the software AVC decoder")
    }

    private const val KEY_LOW_LATENCY_OK = "low_latency_ok_"
    private const val KEY_SOFTWARE_NEEDED = "software_needed_"
}

package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightController

/**
 * Pushes the pilot-configured flight-safety limits (Pre-Flight Setup screen, "Drone Settings"
 * section) to the aircraft on connect: max altitude, max distance (radius), and RTH altitude.
 *
 * Each is optional — an empty field on the setup screen means "don't override, leave the
 * aircraft's current/default setting alone." Fields are entered/persisted in feet (matching the
 * AGL readout already shown on the flight screen); converted to meters only here, at the point
 * of calling the SDK, which takes meters (confirmed via `javap` against the V4.18 aar):
 *   FlightController.setMaxFlightHeight(int meters, CompletionCallback)
 *   FlightController.setMaxFlightRadiusLimitationEnabled(boolean, CompletionCallback)
 *   FlightController.setMaxFlightRadius(int meters, CompletionCallback)
 *   FlightController.setGoHomeHeightInMeters(int meters, CompletionCallback)
 * Valid range for height/radius is documented in the stock demo (res/values/strings.xml) as
 * 20-500m / 15-500m respectively; RTH height uses the same 20-500m range per DJI's public MSDK
 * docs. Out-of-range values are still sent — the SDK's own rejection (via DJIError) is the
 * source of truth, logged here rather than duplicating range validation client-side.
 */
object FlightLimitsController {
    private const val TAG = "TP2Limits"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_MAX_ALT_FT = "limit_max_altitude_ft"
    private const val KEY_MAX_RADIUS_FT = "limit_max_radius_ft"
    private const val KEY_RTH_ALT_FT = "limit_rth_altitude_ft"

    private const val FT_PER_M = 3.28084

    fun savedMaxAltitudeFt(context: Context): String = pref(context, KEY_MAX_ALT_FT, "200")
    fun savedMaxRadiusFt(context: Context): String = pref(context, KEY_MAX_RADIUS_FT, "5280")
    fun savedRthAltitudeFt(context: Context): String = pref(context, KEY_RTH_ALT_FT, "150")

    private fun pref(context: Context, key: String, default: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, default) ?: default

    fun save(context: Context, maxAltFt: String, maxRadiusFt: String, rthAltFt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAX_ALT_FT, maxAltFt.trim())
            .putString(KEY_MAX_RADIUS_FT, maxRadiusFt.trim())
            .putString(KEY_RTH_ALT_FT, rthAltFt.trim())
            .apply()
    }

    /** Apply whichever limits are configured (called once on connect). Skips any limit whose
     *  field is empty/unparseable — that limit is simply not touched. */
    fun applyDefaults(context: Context, fc: FlightController) {
        val maxAltM = ftToM(savedMaxAltitudeFt(context))
        val maxRadiusM = ftToM(savedMaxRadiusFt(context))
        val rthAltM = ftToM(savedRthAltitudeFt(context))
        AppLog.i(TAG, "applyDefaults: maxAltM=$maxAltM maxRadiusM=$maxRadiusM rthAltM=$rthAltM (null = not configured, skipped)")

        maxAltM?.let { m ->
            fc.setMaxFlightHeight(m) { e -> AppLog.i(TAG, "setMaxFlightHeight($m): ${e.logStr()}") }
        }
        maxRadiusM?.let { m ->
            fc.setMaxFlightRadiusLimitationEnabled(true) { e1 ->
                AppLog.i(TAG, "setMaxFlightRadiusLimitationEnabled(true): ${e1.logStr()}")
                fc.setMaxFlightRadius(m) { e2 -> AppLog.i(TAG, "setMaxFlightRadius($m): ${e2.logStr()}") }
            }
        }
        rthAltM?.let { m ->
            fc.setGoHomeHeightInMeters(m) { e -> AppLog.i(TAG, "setGoHomeHeightInMeters($m): ${e.logStr()}") }
        }
    }

    private fun DJIError?.logStr() = this?.description ?: "OK"

    /** Parses a feet string to a rounded meters int, or null if blank/unparseable. */
    private fun ftToM(feetStr: String): Int? {
        val ft = feetStr.trim().toDoubleOrNull() ?: return null
        return Math.round(ft / FT_PER_M).toInt()
    }
}

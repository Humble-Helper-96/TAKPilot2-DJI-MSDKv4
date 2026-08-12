package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import dji.common.error.DJIError
import dji.common.flightcontroller.ConnectionFailSafeBehavior
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightController

/**
 * Pushes the pilot-configured flight-safety limits (Pre-Flight Setup screen, "Drone Settings"
 * section) to the aircraft on connect: max altitude, max distance (radius), RTH altitude, and
 * the signal-loss failsafe behavior.
 *
 * Each is optional — an empty field on the setup screen means "don't override, leave the
 * aircraft's current/default setting alone." Fields are entered/persisted in feet (matching the
 * AGL readout already shown on the flight screen); converted to meters only here, at the point
 * of calling the SDK, which takes meters (confirmed via `javap` against the V4.18 aar):
 *   FlightController.setMaxFlightHeight(int meters, CompletionCallback)
 *   FlightController.setMaxFlightRadiusLimitationEnabled(boolean, CompletionCallback)
 *   FlightController.setMaxFlightRadius(int meters, CompletionCallback)
 *   FlightController.setGoHomeHeightInMeters(int meters, CompletionCallback)
 *   FlightController.setConnectionFailSafeBehavior(ConnectionFailSafeBehavior, CompletionCallback)
 * Valid range for height/radius is documented in the stock demo (res/values/strings.xml) as
 * 20-500m / 15-500m respectively; RTH height uses the same 20-500m range per DJI's public MSDK
 * docs. Out-of-range values are still sent — the SDK's own rejection (via DJIError) is the
 * source of truth, logged here rather than duplicating range validation client-side.
 *
 * **Signal-loss failsafe vs. max distance — two different mechanisms, don't conflate them.**
 * The failsafe here fires when the aircraft *loses the RC link*: it's an aircraft-firmware
 * setting, so it still works if this app (or the whole phone) dies mid-flight. The max-radius
 * limit above is a geofence — the aircraft simply refuses to fly past it and hovers at the
 * boundary; it does NOT trigger a return. There is no SDK setting for "RTH when the fence is
 * reached"; that would have to be app-side distance monitoring, which is deliberately not done
 * here (an app-side watchdog is a weaker guarantee than a firmware failsafe, and having two
 * different things both called "goes home when it's too far" invites exactly the wrong mental
 * model in a safety feature).
 */
object FlightLimitsController {
    private const val TAG = "TP2Limits"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_MAX_ALT_FT = "limit_max_altitude_ft"
    private const val KEY_MAX_RADIUS_FT = "limit_max_radius_ft"
    private const val KEY_RTH_ALT_FT = "limit_rth_altitude_ft"
    private const val KEY_FAILSAFE = "limit_failsafe_behavior"

    private const val FT_PER_M = 3.28084

    /** What the aircraft does when it loses the RC link. Ids are what's persisted.
     *
     *  There is deliberately no "leave the aircraft's setting alone" option (removed at the
     *  operator's request, 2026-07-26): unlike the numeric limits above, where blank sensibly
     *  means "don't override", a signal-loss behaviour the app declines to set is one nobody has
     *  positively confirmed — and this is the setting you least want to be unsure about. A
     *  previously-persisted "leave" now falls through [fromId] to [GO_HOME]. */
    enum class Failsafe(val id: String, val label: String, val sdk: ConnectionFailSafeBehavior) {
        GO_HOME("gohome", "Return to Home", ConnectionFailSafeBehavior.GO_HOME),
        HOVER("hover", "Hover in place", ConnectionFailSafeBehavior.HOVER),
        LAND("land", "Land immediately", ConnectionFailSafeBehavior.LANDING),
        ;
        companion object {
            fun fromId(id: String?): Failsafe = values().firstOrNull { it.id == id } ?: GO_HOME
        }
    }

    fun savedMaxAltitudeFt(context: Context): String = pref(context, KEY_MAX_ALT_FT, "200")
    fun savedMaxRadiusFt(context: Context): String = pref(context, KEY_MAX_RADIUS_FT, "5280")
    fun savedRthAltitudeFt(context: Context): String = pref(context, KEY_RTH_ALT_FT, "150")

    /** Defaults to Return to Home — the safe choice for the "flew out of radio range" case
     *  this was added for, and what the aircraft itself most likely already defaults to (this
     *  makes it explicit and verifiable rather than assumed). */
    fun savedFailsafe(context: Context): Failsafe =
        Failsafe.fromId(pref(context, KEY_FAILSAFE, Failsafe.GO_HOME.id))

    private fun pref(context: Context, key: String, default: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, default) ?: default

    fun save(context: Context, maxAltFt: String, maxRadiusFt: String, rthAltFt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAX_ALT_FT, maxAltFt.trim())
            .putString(KEY_MAX_RADIUS_FT, maxRadiusFt.trim())
            .putString(KEY_RTH_ALT_FT, rthAltFt.trim())
            .apply()
    }

    fun saveFailsafe(context: Context, failsafe: Failsafe) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FAILSAFE, failsafe.id).apply()
    }

    /** Apply whichever limits are configured (called once on connect). Skips any limit whose
     *  field is empty/unparseable — that limit is simply not touched. */
    fun applyDefaults(context: Context, fc: FlightController) {
        val maxAltM = ftToM(savedMaxAltitudeFt(context))
        val maxRadiusM = ftToM(savedMaxRadiusFt(context))
        val rthAltM = ftToM(savedRthAltitudeFt(context))
        AppLog.i(TAG, "applyDefaults: maxAltM=$maxAltM maxRadiusM=$maxRadiusM rthAltM=$rthAltM " +
            "failsafe=${savedFailsafe(context).id} (null = not configured, skipped)")

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

        val sdkBehavior = savedFailsafe(context).sdk
        fc.setConnectionFailSafeBehavior(sdkBehavior) { e ->
            AppLog.i(TAG, "setConnectionFailSafeBehavior($sdkBehavior): ${e.logStr()}")
            // Always read back, including on a reported failure: this is the one limit the
            // pilot can't casually verify in the air (confirming it for real means
            // deliberately dropping the RC link mid-flight), so the log is the practical
            // proof that the aircraft actually took the setting.
            readBackFailsafe(fc)
        }
    }

    /** Asks the aircraft what its signal-loss behavior actually is now, and logs it. */
    private fun readBackFailsafe(fc: FlightController) {
        fc.getConnectionFailSafeBehavior(
            object : CommonCallbacks.CompletionCallbackWith<ConnectionFailSafeBehavior> {
                override fun onSuccess(value: ConnectionFailSafeBehavior?) {
                    AppLog.i(TAG, "aircraft signal-loss behavior is now: $value")
                }
                override fun onFailure(error: DJIError?) {
                    AppLog.w(TAG, "getConnectionFailSafeBehavior failed: ${error.logStr()}")
                }
            })
    }

    private fun DJIError?.logStr() = this?.description ?: "OK"

    /**
     * Parses a feet string to a rounded meters int, or null if blank/unparseable.
     *
     * Internal rather than private so [FlightWarnings] can convert the SAME stored strings this
     * controller pushes to the aircraft. Both must read one source, or the at-limit banner ends
     * up describing a limit the aircraft is not enforcing.
     */
    internal fun ftToM(feetStr: String): Int? {
        val ft = feetStr.trim().toDoubleOrNull() ?: return null
        return Math.round(ft / FT_PER_M).toInt()
    }
}

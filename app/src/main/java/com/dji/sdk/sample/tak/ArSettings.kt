package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog

/**
 * What the AR overlay is allowed to draw. Persisted, because a pilot who turns other operators'
 * position dots off to unclutter the video should not have them return on the next flight.
 *
 * **Three categories, not two.** The obvious split is "mine vs theirs", but the distinction that
 * actually matters in a busy picture is between other operators' POSITIONS and the markers those
 * operators have PLACED: a dozen people's position dots are what carpets the video, while their
 * placed markers are usually the thing worth seeing. Collapsing those two into one toggle would
 * force the pilot to lose both together. The split is free because
 * [TakMapMarkers.milMarkerRes] already classifies them.
 *
 * **Not the same thing as the per-uid local hide.** [TakMapMarkers.isHidden] dismisses one
 * specific marker everywhere, including the mini-map. These flags are AR-only and by category.
 * Both apply — a marker draws only if it passes both.
 */
object ArSettings {
    private const val TAG = "ArSettings"
    private const val PREFS = "takpilot2_ar"

    private const val KEY_MY_MARKERS = "show_my_markers"
    private const val KEY_OTHER_MARKERS = "show_other_markers"
    private const val KEY_OTHER_POSITIONS = "show_other_positions"

    private const val KEY_AIRCRAFT = "show_aircraft"
    private const val KEY_WEATHER = "show_weather"

    /**
     * What a category toggle refers to. Order here is the order shown in the options dialog.
     *
     * Labels are deliberately terse — two or three words, no sentence-case description line.
     * This menu is opened in flight, one-handed, to fix a picture that is already too busy; a
     * pilot reading a paragraph per row is a pilot not looking at the video.
     */
    enum class Category(val key: String, val label: String) {
        MY_MARKERS(KEY_MY_MARKERS, "My Markers"),
        OTHER_MARKERS(KEY_OTHER_MARKERS, "Team Markers"),
        OTHER_POSITIONS(KEY_OTHER_POSITIONS, "Team Positions"),
        AIRCRAFT(KEY_AIRCRAFT, "Air Traffic"),
        WEATHER(KEY_WEATHER, "Weather"),
    }

    /**
     * Which category an inbound contact belongs to.
     *
     * Ordering matters — the checks run most-specific first:
     *  1. **Weather** by uid prefix. METAR markers are `a-u-G`, indistinguishable by type from a
     *     pilot-placed "unknown" marker, so the gateway's stable `METAR-<ICAO>` uid is the only
     *     reliable discriminator.
     *  2. **Aircraft** by the CoT type's third field being `A` (air) rather than `G` (ground) —
     *     e.g. `a-f-A-C-F` for a civil fixed-wing from the ADS-B gateway.
     *  3. Otherwise the existing ground split: a bare `a-{f,h,n,u}-G` is a placed marker,
     *     anything longer is an entity reporting its own position.
     */
    fun categoryFor(uid: String?, type: String?): Category {
        if (uid != null && uid.startsWith(METAR_UID_PREFIX)) return Category.WEATHER
        val parts = type?.split("-").orEmpty()
        if (parts.size >= 3 && parts[0] == "a" && parts[2] == "A") return Category.AIRCRAFT
        return if (TakMapMarkers.milMarkerRes(type) != null) {
            Category.OTHER_MARKERS
        } else {
            Category.OTHER_POSITIONS
        }
    }

    /** Set by the operator's METAR gateway as `METAR-<ICAO>`; see its runbook. */
    private const val METAR_UID_PREFIX = "METAR-"

    /** Statute, not nautical. The whole app displays imperial (see `Units.kt`) and mixing the
     *  two units of "mile" in a pilot-facing menu is exactly how a range gets misread. */
    private const val METERS_PER_MILE = 1609.344

    /**
     * Ground horizon: fixed at 5 statute miles, not adjustable.
     *
     * Ground markers are sparse and static — a range knob for them would be a control the pilot
     * never has a reason to touch. Air traffic is the opposite (see [AirRange]), which is why
     * only that one is exposed.
     */
    private const val GROUND_RANGE_M = 5.0 * METERS_PER_MILE

    /** Airport weather stations are sparse and the nearest one is worth seeing however far it
     *  is, so METAR keeps the widest fixed horizon rather than following [AirRange]. */
    private const val WEATHER_RANGE_M = 15.0 * METERS_PER_MILE

    private const val KEY_AIR_RANGE = "air_range_mi"

    /**
     * How far out ADS-B tracks are drawn — the one range the pilot can change, because it is the
     * one that changes with conditions rather than with preference. Over a quiet area 15 miles of
     * traffic is useful situational awareness; over a busy pattern the same setting carpets the
     * video with diamonds and the pilot needs to pull it in to the traffic that actually matters
     * to them.
     */
    enum class AirRange(val miles: Double) {
        MI_2_5(2.5),
        MI_5(5.0),
        MI_15(15.0);

        val meters: Double get() = miles * METERS_PER_MILE
        /** "2.5 mi" / "5 mi" — no trailing ".0" on the whole-number options. */
        val label: String
            get() = if (miles == miles.toLong().toDouble()) "${miles.toLong()} mi"
            else "$miles mi"
    }

    /** Default 5 mi — operator's call 2026-07-27, after field use showed 15 mi tends to carpet
     *  a busy area with more diamonds than the pilot actually wants by default. The pilot can
     *  still pick 15 mi from the AR options dialog when a wider horizon is actually wanted. */
    fun airRange(context: Context): AirRange {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_AIR_RANGE, AirRange.MI_5.miles.toFloat()).toDouble()
        return AirRange.values().minByOrNull { kotlin.math.abs(it.miles - stored) }
            ?: AirRange.MI_5
    }

    fun setAirRange(context: Context, range: AirRange) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_AIR_RANGE, range.miles.toFloat()).apply()
        AppLog.i(TAG, "AR air-traffic range -> ${range.label}")
    }

    /**
     * How far out a category is drawn, in metres. The single place range is decided, so the
     * overlay's draw loop never has to know which categories are fixed and which are pilot-set.
     */
    fun rangeMeters(context: Context, category: Category): Double = when (category) {
        Category.AIRCRAFT -> airRange(context).meters
        Category.WEATHER -> WEATHER_RANGE_M
        else -> GROUND_RANGE_M
    }

    private const val KEY_HFOV = "fov_h_deg"
    private const val KEY_VFOV = "fov_v_deg"

    /**
     * Calibrated camera field of view (degrees at 1x), persisted across flights.
     *
     * The defaults are derived from published specs, not measured. AR accuracy is most sensitive
     * to this at the FRAME EDGES — an FOV error is invisible at the centre and grows outward —
     * so it is calibrated by putting a marker on a known object near the edge and adjusting
     * until the icon sits on it. See the 6D plan's calibration section.
     */
    fun loadFov(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        TakBridgeHolder.setFovBase(
            p.getFloat(KEY_HFOV, TakBridgeHolder.DEFAULT_HFOV.toFloat()).toDouble(),
            p.getFloat(KEY_VFOV, TakBridgeHolder.DEFAULT_VFOV.toFloat()).toDouble(),
        )
    }

    /** Applies immediately AND persists — the pilot is adjusting while watching the overlay. */
    fun saveFov(context: Context, hDeg: Double, vDeg: Double) {
        TakBridgeHolder.setFovBase(hDeg, vDeg)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_HFOV, TakBridgeHolder.currentHFovBase.toFloat())
            .putFloat(KEY_VFOV, TakBridgeHolder.currentVFovBase.toFloat())
            .apply()
        AppLog.i(TAG, "AR FOV calibrated to %.1f x %.1f deg"
            .format(TakBridgeHolder.currentHFovBase, TakBridgeHolder.currentVFovBase))
    }

    fun resetFov(context: Context) =
        saveFov(context, TakBridgeHolder.DEFAULT_HFOV, TakBridgeHolder.DEFAULT_VFOV)

    /** Default ON: the toggle implies everything shows, so first run should match that. */
    fun isEnabled(context: Context, category: Category): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(category.key, true)

    fun setEnabled(context: Context, category: Category, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(category.key, enabled).apply()
        AppLog.i(TAG, "AR category '${category.label}' -> ${if (enabled) "shown" else "hidden"}")
    }
}

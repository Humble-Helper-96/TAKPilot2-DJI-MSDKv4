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
     * What a category toggle refers to, and how far out that kind of thing is worth drawing.
     * Order here is the order shown in the options dialog.
     *
     * **Range is per-category on purpose.** 5 km is right for ground markers, where anything
     * further is an unactionable speck. It is badly wrong for air traffic: an aircraft at a few
     * thousand feet is plainly relevant at 15 nm and is exactly what a pilot wants to see.
     */
    enum class Category(
        val key: String,
        val label: String,
        val description: String,
        val maxRangeM: Double,
    ) {
        MY_MARKERS(
            KEY_MY_MARKERS,
            "My dropped markers",
            "Markers you placed from this aircraft",
            GROUND_RANGE_M,
        ),
        OTHER_MARKERS(
            KEY_OTHER_MARKERS,
            "Other users' markers",
            "Markers other operators have placed",
            GROUND_RANGE_M,
        ),
        OTHER_POSITIONS(
            KEY_OTHER_POSITIONS,
            "Other users' positions",
            "Where other operators themselves are",
            GROUND_RANGE_M,
        ),
        AIRCRAFT(
            KEY_AIRCRAFT,
            "Aircraft (ADS-B)",
            "Nearby air traffic, out to 15 nm",
            AIR_RANGE_M,
        ),
        WEATHER(
            KEY_WEATHER,
            "Weather (METAR)",
            "Airport weather stations",
            AIR_RANGE_M,
        ),
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

    private const val GROUND_RANGE_M = 5_000.0
    /** 15 nautical miles, the operator's chosen air-traffic horizon. */
    private const val AIR_RANGE_M = 15.0 * 1852.0

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

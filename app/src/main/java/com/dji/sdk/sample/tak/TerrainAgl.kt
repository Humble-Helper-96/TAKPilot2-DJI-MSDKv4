package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog

/**
 * Turns DJI's takeoff-relative altitude into a true height above the ground *under the
 * aircraft*, using the pilot's imported DTED ([DtedIndex]).
 *
 * **The correction.** `FlightControllerState.aircraftLocation.altitude` is height above the
 * takeoff point, not above the terrain below. Over flat ground those agree; fly off a bluff or
 * up a valley and they diverge by exactly the terrain difference. So:
 *
 *     correctedAgl = takeoffRelativeAlt + (terrainElevAtTakeoff − terrainElevUnderAircraft)
 *
 * **Why a difference of two DTED samples, rather than absolute altitudes.** DTED is
 * MSL-referenced while GNSS altitude is WGS84-ellipsoid; that geoid offset is tens of metres in
 * places and is a known open item for this app's SPoI. Here it **cancels** — both samples come
 * from the same dataset in the same datum, so only their difference is used and the offset drops
 * out entirely. That's why this deliberately does NOT use
 * `FlightControllerState.getTakeoffLocationAltitude()`: mixing an SDK "altitude above sea level"
 * (whose datum and error we can't verify) with a DTED MSL sample would reintroduce exactly the
 * datum problem this formulation avoids.
 *
 * **The takeoff reference is latched, not re-read.** It's captured from the home location the
 * first time one is available, because at that moment home *is* the takeoff point — but the
 * pilot can move the home point mid-flight (long-press RTH resets it to the phone's position).
 * The aircraft's altitude stays referenced to where it actually took off, so the terrain
 * reference has to as well; re-reading it from a moved home point would silently corrupt the
 * correction by the terrain difference between the two.
 *
 * Reset per flight via [reset], called when a new [DroneTakBridge] session starts.
 *
 * **Residual error sources** (this is a correction, not truth): DTED's own vertical accuracy,
 * its post spacing smoothing over real terrain, and any difference between the true takeoff
 * elevation and DTED's value there — taking off from a rooftop, a vehicle, or a riverbank the
 * terrain model doesn't resolve all bias the reference. It is still substantially better than
 * no correction the moment the aircraft leaves the elevation it launched from.
 */
object TerrainAgl {
    private const val TAG = "TerrainAgl"

    /** Re-sample the terrain under the aircraft only after it's moved this far horizontally.
     *  Each sample opens a file and does four seeks ([DtedTile.elevationAt] interpolates
     *  bilinearly), and this runs on the flight screen's main-thread HUD tick — while hovering,
     *  the answer isn't changing, so neither should the I/O. Comfortably finer than DTED's own
     *  post spacing, so it costs no meaningful accuracy. */
    private const val RESAMPLE_DISTANCE_M = 15.0

    /** Height above the ground under the aircraft, in metres, plus whether DTED actually
     *  informed it. [terrainCorrected] false means this is the raw takeoff-relative altitude —
     *  callers must label the two differently, since presenting an uncorrected figure as "AGL"
     *  is the inaccuracy this class exists to remove. */
    data class Reading(val meters: Double, val terrainCorrected: Boolean)

    @Volatile private var takeoffTerrainElevM: Double? = null

    private var cachedForLat = Double.NaN
    private var cachedForLon = Double.NaN
    private var cachedTerrainElevM: Double? = null

    /** New flight session — drop the latched takeoff reference and the terrain cache. */
    @Synchronized
    fun reset() {
        takeoffTerrainElevM = null
        cachedForLat = Double.NaN
        cachedForLon = Double.NaN
        cachedTerrainElevM = null
        AppLog.v(TAG, "reset (new bridge session)")
    }

    /** True once a takeoff terrain reference has been captured — i.e. correction is possible
     *  as soon as there's also DTED coverage under the aircraft. */
    val hasTakeoffReference: Boolean get() = takeoffTerrainElevM != null

    @Synchronized
    fun reading(context: Context, hud: DroneTakBridge.Hud): Reading {
        val raw = Reading(hud.alt, terrainCorrected = false)
        if (!hud.hasFix) return raw

        latchTakeoffReference(context, hud)
        val takeoffElev = takeoffTerrainElevM ?: return raw
        val underAircraft = terrainUnderAircraft(context, hud.lat, hud.lon) ?: return raw

        return Reading(hud.alt + (takeoffElev - underAircraft), terrainCorrected = true)
    }

    /** Captures the terrain elevation at the takeoff point, once, from the first home location
     *  we see. Retries on later ticks if DTED had no coverage yet (nothing is latched on a
     *  failed lookup), so importing terrain mid-session still starts working. */
    private fun latchTakeoffReference(context: Context, hud: DroneTakBridge.Hud) {
        if (takeoffTerrainElevM != null) return
        if (!hud.homeSet) return
        if (!hud.homeLat.isFinite() || !hud.homeLon.isFinite()) return
        val elev = DtedIndex.elevationAt(context, hud.homeLat, hud.homeLon) ?: return
        takeoffTerrainElevM = elev
        AppLog.i(TAG, "takeoff terrain reference latched: %.1f m MSL at %.5f, %.5f"
            .format(elev, hud.homeLat, hud.homeLon))
    }

    private fun terrainUnderAircraft(context: Context, lat: Double, lon: Double): Double? {
        if (cachedForLat.isFinite() && cachedForLon.isFinite()) {
            val moved = CameraSlantPoint.distanceMeters(cachedForLat, cachedForLon, lat, lon)
            if (moved < RESAMPLE_DISTANCE_M) return cachedTerrainElevM
        }
        val elev = DtedIndex.elevationAt(context, lat, lon)
        cachedForLat = lat
        cachedForLon = lon
        cachedTerrainElevM = elev
        return elev
    }
}

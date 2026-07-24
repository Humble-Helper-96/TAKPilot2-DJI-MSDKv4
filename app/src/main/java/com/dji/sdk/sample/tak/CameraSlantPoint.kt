package com.dji.sdk.sample.tak

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Computes the ground point the camera is looking at (the "slant point" / sensor
 * point of interest) from the aircraft position, altitude, and camera orientation.
 *
 * Default (no [DtedIndex] coverage) assumes flat ground at the aircraft's takeoff altitude
 * (good over level terrain). When DTED coverage is available for the area, [compute] instead
 * iteratively refines the ground point against actual terrain elevation — see its doc.
 */
object CameraSlantPoint {

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG = Math.PI / 180.0
    private const val MAX_RANGE_M = 10_000.0
    // When altitude/geometry can't give a valid range, plant the point this far along the
    // camera bearing (matches DJI UAS tool's behavior, so a point shows even on the ground).
    private const val FALLBACK_RANGE_M = 300.0
    private const val TERRAIN_ITERATIONS = 4
    private const val CONVERGE_M = 1.0

    data class GroundPoint(val lat: Double, val lon: Double, val rangeMeters: Double)

    /**
     * Where the camera is looking, projected onto the ground.
     *
     * Follows DJI UAS tool's method: slant range = altitude / sin(|pitch|), capped to
     * [MAX_RANGE_M]; if the geometry is invalid (no altitude, camera near level/up), fall
     * back to [FALLBACK_RANGE_M] so a point still shows. Then project that HORIZONTAL
     * distance from the aircraft along the camera bearing.
     *
     * When [elevationAt] is supplied (typically [DtedIndex]::elevationAt) and returns
     * coverage for the aircraft's own position, this refines the flat-ground estimate by
     * fixed-point iteration: [aglMeters] is DJI's altitude-above-takeoff-point, which the
     * flat-ground path already treats as "height above the ground directly below the
     * aircraft." Each iteration samples DTED elevation at the current candidate ground
     * point, adjusts the effective height-above-target by the elevation delta between the
     * aircraft's local ground and the target's ground, and recomputes — typically converges
     * in 2-3 iterations over smooth terrain. Falls back to the flat-ground point if there's
     * no DTED coverage at the aircraft's own position, or if a later iteration's candidate
     * point walks outside the loaded tiles' coverage.
     *
     * Known limitation: DTED elevations are referenced to a geoid/MSL vertical datum, while
     * [aglMeters] comes from DJI's WGS84-ellipsoid-based GPS altitude — the two can differ by
     * tens of meters depending on location (geoid separation), which isn't corrected for
     * here. This still meaningfully improves on the flat-ground assumption over hilly terrain
     * (the dominant error source), but don't expect survey-grade accuracy.
     *
     * @param lat,lon     aircraft position (deg)
     * @param aglMeters   aircraft height above the ground point (m); assume flat ground
     * @param bearingDeg  true bearing the camera points along (deg, 0..360)
     * @param pitchDeg    gimbal pitch (deg). DJI reports level=0, looking down=negative.
     * @param elevationAt optional terrain elevation lookup (meters) at a given (lat, lon);
     *                    null/omitted keeps the original flat-ground behavior.
     */
    fun compute(
        lat: Double, lon: Double, aglMeters: Double,
        bearingDeg: Double, pitchDeg: Double,
        elevationAt: ((Double, Double) -> Double?)? = null,
    ): GroundPoint {
        val flat = computeFlat(lat, lon, aglMeters, bearingDeg, pitchDeg)
        if (elevationAt == null) return flat

        val groundElevAtAircraft = elevationAt(lat, lon) ?: return flat

        var targetElev = groundElevAtAircraft
        var point = flat
        for (i in 0 until TERRAIN_ITERATIONS) {
            val effectiveAgl = aglMeters + (groundElevAtAircraft - targetElev)
            val candidate = computeFlat(lat, lon, effectiveAgl, bearingDeg, pitchDeg)
            val sampled = elevationAt(candidate.lat, candidate.lon) ?: break
            point = candidate
            if (abs(sampled - targetElev) < CONVERGE_M) break
            targetElev = sampled
        }
        return point
    }

    private fun computeFlat(
        lat: Double, lon: Double, aglMeters: Double,
        bearingDeg: Double, pitchDeg: Double,
    ): GroundPoint {
        val depression = -pitchDeg // angle below horizon
        val horizontalRange: Double = if (aglMeters > 0.0 && depression > 1.0) {
            // tan gives the ground (horizontal) distance directly.
            val d = aglMeters / tan(depression * DEG)
            if (d.isFinite() && d in 0.0..MAX_RANGE_M) d else FALLBACK_RANGE_M
        } else {
            FALLBACK_RANGE_M
        }
        return project(lat, lon, bearingDeg, horizontalRange)
    }

    /** Move [distanceM] from (lat,lon) along [bearingDeg] (great-circle, deg result). */
    private fun project(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): GroundPoint {
        val angDist = distanceM / EARTH_RADIUS_M
        val br = bearingDeg * DEG
        val lat1 = lat * DEG
        val lon1 = lon * DEG

        val lat2 = asin(
            sin(lat1) * cos(angDist) + cos(lat1) * sin(angDist) * cos(br)
        )
        val lon2 = lon1 + atan2(
            sin(br) * sin(angDist) * cos(lat1),
            cos(angDist) - sin(lat1) * sin(lat2)
        )
        val outLon = ((lon2 / DEG + 540.0) % 360.0) - 180.0 // normalize to -180..180
        return GroundPoint(lat2 / DEG, outLon, distanceM)
    }

    /** Normalize any heading/bearing to 0..360. */
    fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Great-circle distance between two points (haversine), meters. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLon = (lon2 - lon1) * DEG
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLon / 2).let { it * it }
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** True (not magnetic) initial bearing from point 1 to point 2, degrees 0..360. */
    fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = lat1 * DEG
        val phi2 = lat2 * DEG
        val dLon = (lon2 - lon1) * DEG
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return norm360(atan2(y, x) / DEG)
    }

    /**
     * Project the camera's rectangular field of view onto the ground — the footprint
     * polygon that "fans out" and shrinks/grows with FOV (zoom).
     *
     * Each corner is a ray at (pitch ± vfov/2) elevation and (bearing ± hfov/2) azimuth.
     * Rays that don't hit the ground (corner above horizon, e.g. shallow look angle) are
     * clamped to [MAX_RANGE_M] so the polygon stays finite. Corner order is clockwise:
     * near-left, near-right, far-right, far-left.
     *
     * @param hfovDeg horizontal field of view (deg)
     * @param vfovDeg vertical field of view (deg)
     * @return list of 4 ground corners (may be empty if geometry degenerate)
     */
    fun footprint(
        lat: Double, lon: Double, aglMeters: Double,
        bearingDeg: Double, pitchDeg: Double,
        hfovDeg: Double, vfovDeg: Double,
    ): List<GroundPoint> {
        val halfH = hfovDeg / 2.0
        val halfV = vfovDeg / 2.0
        // pitch is negative looking down; "near" edge looks down less (pitch + halfV),
        // "far" edge looks down more (pitch - halfV).
        val corners = listOf(
            cornerRange(aglMeters, pitchDeg + halfV) to (bearingDeg - halfH), // near-left
            cornerRange(aglMeters, pitchDeg + halfV) to (bearingDeg + halfH), // near-right
            cornerRange(aglMeters, pitchDeg - halfV) to (bearingDeg + halfH), // far-right
            cornerRange(aglMeters, pitchDeg - halfV) to (bearingDeg - halfH), // far-left
        )
        return corners.map { (range, brg) -> project(lat, lon, norm360(brg), range) }
    }

    /** Horizontal ground range for one frustum corner ray, with sane fallbacks. */
    private fun cornerRange(aglMeters: Double, cornerPitchDeg: Double): Double {
        val depression = -cornerPitchDeg
        if (aglMeters <= 0.0 || depression <= 1.0) return FALLBACK_RANGE_M
        val d = aglMeters / tan(depression * DEG)
        return if (d.isFinite() && d in 0.0..MAX_RANGE_M) d else MAX_RANGE_M
    }
}

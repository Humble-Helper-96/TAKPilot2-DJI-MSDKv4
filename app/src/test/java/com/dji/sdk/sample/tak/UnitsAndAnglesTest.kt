package com.dji.sdk.sample.tak

import com.dji.sdk.sample.takpilot2.Units
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Conversions and angle normalisation. Small, but these feed the HUD and the marker maths — a
 * wrong factor or a sign that does not wrap is a wrong picture for the whole team.
 */
class UnitsAndAnglesTest {

    @Test
    fun metersToFeetUsesTheRightFactor() {
        assertEquals(328.084, Units.metersToFeet(100.0), 0.001)
        assertEquals(0.0, Units.metersToFeet(0.0), 0.0)
    }

    @Test
    fun norm360WrapsBothDirections() {
        assertEquals(270.0, CameraSlantPoint.norm360(-90.0), 1e-9)
        assertEquals(90.0, CameraSlantPoint.norm360(450.0), 1e-9)
        assertEquals(0.0, CameraSlantPoint.norm360(360.0), 1e-9)
        assertEquals(359.5, CameraSlantPoint.norm360(-0.5), 1e-9)
        // Already in range: must pass through untouched, not shift by a rounding step.
        assertEquals(180.0, CameraSlantPoint.norm360(180.0), 1e-9)
    }

    /** Bearing and distance between two points, the pair the markers list and the HOME readout
     *  both use. Due north and due east from a known point, so the answers are checkable by
     *  inspection rather than by re-running the same formula. */
    @Test
    fun bearingAndDistanceAgreeWithTheCompass() {
        val lat = 61.2
        val lon = -149.9
        // One tenth of a degree of latitude north — about 11.1 km, bearing 000.
        val north = CameraSlantPoint.initialBearingDeg(lat, lon, lat + 0.1, lon)
        assertEquals(0.0, CameraSlantPoint.norm360(north), 0.5)
        assertEquals(11_119.0, CameraSlantPoint.distanceMeters(lat, lon, lat + 0.1, lon), 50.0)

        // East at this latitude: still 090, and shorter on the ground than the same degree
        // change in latitude — the cos(lat) term is the thing being checked.
        val east = CameraSlantPoint.initialBearingDeg(lat, lon, lat, lon + 0.1)
        assertEquals(90.0, CameraSlantPoint.norm360(east), 0.5)
        val eastM = CameraSlantPoint.distanceMeters(lat, lon, lat, lon + 0.1)
        assertEquals(5_368.0, eastM, 60.0)
    }
}

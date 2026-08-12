package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the banner policy: what is shown, in what order, and for how long.
 *
 * These are the rules a pilot reads during a fault, so they are pinned rather than left to be
 * re-derived. The display half is driven through [FlightWarnings.displayAt] with an explicit
 * clock so the hold can be stepped without sleeping.
 *
 * The state half of the policy ([FlightWarnings.onState]) is not exercised here: it takes a
 * `FlightControllerState`, whose fields are only settable through the SDK's own setters, and
 * constructing one under `returnDefaultValues` yields an object whose getters all return null.
 * The pure predicates it delegates to ARE covered, which is where the airframe-specific mapping
 * decisions actually live.
 */
class FlightWarningsTest {

    @Before
    fun clean() = FlightWarnings.reset()

    // ---- Display policy ----

    @Test
    fun nothingActiveShowsNothing() {
        assertNull(FlightWarnings.displayAt(1_000L))
    }

    @Test
    fun anAircraftFaultIsShownVerbatim() {
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        val d = FlightWarnings.displayAt(1_000L)!!
        assertEquals("Compass error", d.text)
        assertTrue(d.red)
    }

    /**
     * The no-fly-zone decision, pinned. The Autel sibling suppresses this; this tree does not,
     * because it went invisible through two flights here. If someone later ports Autel's
     * log-only rule across, this test is what stops it happening silently.
     */
    @Test
    fun aNoFlyZoneDiagnosticReachesTheBanner() {
        FlightWarnings.onDiagnostics(listOf("Cannot takeoff in a no-fly zone"))
        val d = FlightWarnings.displayAt(1_000L)!!
        assertTrue("no-fly zone must not be filtered out", "no-fly zone" in d.text)
        assertTrue(d.red)
    }

    @Test
    fun severalFaultsAreJoinedNotDropped() {
        FlightWarnings.onDiagnostics(listOf("Compass error", "IMU error"))
        val d = FlightWarnings.displayAt(1_000L)!!
        assertTrue("Compass error" in d.text)
        assertTrue("IMU error" in d.text)
    }

    @Test
    fun aClearedFaultHidesTheBannerOnceTheHoldExpires() {
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        assertEquals("Compass error", FlightWarnings.displayAt(0L)!!.text)
        FlightWarnings.onDiagnostics(emptyList())
        // Still held: a fault that appears and clears within the hold must stay readable.
        assertEquals("Compass error", FlightWarnings.displayAt(1_000L)!!.text)
        // Past the hold, nothing is active, so the banner goes.
        assertNull(FlightWarnings.displayAt(9_000L))
    }

    @Test
    fun theHoldKeepsAWarningReadableWhileItFlickers() {
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        assertEquals("Compass error", FlightWarnings.displayAt(0L)!!.text)
        // Flicker: gone and back inside the hold window. The text must not blank in between.
        FlightWarnings.onDiagnostics(emptyList())
        assertEquals("Compass error", FlightWarnings.displayAt(500L)!!.text)
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        assertEquals("Compass error", FlightWarnings.displayAt(1_000L)!!.text)
    }

    @Test
    fun resetClearsEverything() {
        FlightWarnings.onDiagnostics(listOf("Compass error"))
        FlightWarnings.reset()
        assertNull(FlightWarnings.displayAt(1_000L))
    }

    // ---- Limit band ----

    @Test
    fun atLimitFiresInsideTheMarginAndBeyondIt() {
        // 120 m limit -> 5% margin = 6 m, so 114 m and up is "at" it.
        assertTrue(FlightWarnings.atLimit(114.0, 120.0))
        assertTrue(FlightWarnings.atLimit(120.0, 120.0))
        assertTrue(FlightWarnings.atLimit(200.0, 120.0))
        assertFalse(FlightWarnings.atLimit(113.0, 120.0))
    }

    @Test
    fun atLimitUsesAFloorSoASmallCeilingStillHasABand() {
        // 20 m limit: 5% is 1 m, which is under the 5 m floor, so the band is 5 m.
        assertTrue(FlightWarnings.atLimit(15.0, 20.0))
        assertFalse(FlightWarnings.atLimit(14.0, 20.0))
    }

    @Test
    fun anUnsetLimitOrAnUnknownValueNeverWarns() {
        assertFalse(FlightWarnings.atLimit(500.0, null))
        assertFalse(FlightWarnings.atLimit(Double.NaN, 120.0))
        assertFalse(FlightWarnings.atLimit(500.0, 0.0))
    }

    // ---- NOT COVERED HERE, deliberately ----
    //
    // isAttitudeMode() and isPoorGps() encode which DJI enum values mean "unsafe", and they are
    // exactly what one would want pinned. They cannot be: those enums live only in
    // dji-sdk-provided, whose bytecode is a stub (constructors return before calling super), so
    // touching a constant in a JVM test throws VerifyError. See the note in app/build.gradle.
    // Both were checked against the SDK surface with javap when written — LEVEL_0/1/NONE and the
    // five ATTI_* variants are the real constant sets — and both must be confirmed in flight.

    // ---- Priority ----

    @Test
    fun theAircraftsOwnFaultOutranksEverythingElse() {
        val order = FlightWarnings.Warning.values().toList()
        assertEquals(FlightWarnings.Warning.AIRCRAFT_FAULT, order.first())
        // Every red sits above every amber, so an amber can never mask a red.
        val lastRed = order.indexOfLast { it.red }
        val firstAmber = order.indexOfFirst { !it.red }
        assertTrue("reds must all precede ambers", lastRed < firstAmber)
    }
}

package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the flight-record formats: the CSV row cycle, the GPX document, and the filename
 * canonicalisation the orphan sweep pairs on.
 *
 * These formats are the crash-recovery contract. The sweep parses a CSV that a dead process left
 * behind, so a row must survive the trip out and back, and a row cut mid-write must be rejected
 * rather than misread.
 */
class FlightPathLoggerFormatTest {

    private fun point(
        timeMs: Long = 1_754_580_000_000L,   // whole second, so the trip out and back is exact
        lat: Double = 61.1234567, lon: Double = -149.1234567,
        msl: Double = 123.4, rel: Double = 45.6,
        speed: Double = 5.0, heading: Double = 270.0,
        battery: Int = 88, sats: Int = 17,
    ) = FlightPathLogger.Point(timeMs, lat, lon, msl, rel, speed, heading, battery, sats)

    @Test
    fun csvRowSurvivesTheRoundTrip() {
        val p = point()
        val row = FlightPathLogger.csvRow(p).trimEnd('\n')
        val back = FlightPathLogger.parseCsvRow(row)!!
        assertEquals(p.timeMs, back.timeMs)
        assertEquals(p.lat, back.lat, 1e-7)
        assertEquals(p.lon, back.lon, 1e-7)
        assertEquals(p.mslAltM, back.mslAltM, 0.05)
        assertEquals(p.relAltM, back.relAltM, 0.05)
        assertEquals(p.batteryPct, back.batteryPct)
        assertEquals(p.satellites, back.satellites)
    }

    /**
     * MSL is absent until the terrain reference latches, which on this airframe is most of a
     * flight with no DTED loaded. The column must still be there so the row keeps its shape.
     */
    @Test
    fun missingMslBecomesEmptyFieldAndComesBackAsNaN() {
        val row = FlightPathLogger.csvRow(point(msl = Double.NaN)).trimEnd('\n')
        assertTrue(",," in row)   // the empty field is present, the column count holds
        val back = FlightPathLogger.parseCsvRow(row)!!
        assertTrue(back.mslAltM.isNaN())
        assertEquals(45.6, back.relAltM, 0.05)
    }

    @Test
    fun aRowCutByACrashIsRejectedNotMisread() {
        val full = FlightPathLogger.csvRow(point()).trimEnd('\n')
        val cut = full.substring(0, full.length / 2)
        assertNull(FlightPathLogger.parseCsvRow(cut))
    }

    @Test
    fun theHeaderLineIsNotAPoint() {
        assertNull(FlightPathLogger.parseCsvRow(
            "utc_time,lat,lon,alt_msl_m,alt_above_takeoff_m,speed_ms,heading_deg,battery_pct,satellites"))
    }

    @Test
    fun gpxHoldsOneTrkptPerPointWithIsoTimes() {
        val gpx = FlightPathLogger.gpxDocument(listOf(point(), point(timeMs = 1_754_580_001_000L)))
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertEquals(2, Regex("<time>").findAll(gpx).count())
        assertTrue("<ele>123.4</ele>" in gpx)
        assertTrue(Regex("<time>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z</time>").containsMatchIn(gpx))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun gpxEleFallsBackToAboveTakeoffWhenMslIsMissing() {
        val gpx = FlightPathLogger.gpxDocument(listOf(point(msl = Double.NaN)))
        assertTrue("<ele>45.6</ele>" in gpx)
    }

    // ---- Filename canonicalisation: what stops the orphan sweep looping for ever ----

    @Test
    fun mediaStoreDedupSuffixIsStrippedSoThePairIsFound() {
        val base = "flight-2026-08-11-14-03-22"
        // MediaStore silently renames a colliding insert. The CSV then lives under the renamed
        // form while the GPX is written under the plain base; both must reduce to the same key
        // or the sweep re-converts the same CSV at every launch.
        assertEquals(base, FlightPathLogger.canonicalBase("$base (1)"))
        assertEquals(base, FlightPathLogger.canonicalBase("$base (12)"))
        assertEquals(base, FlightPathLogger.canonicalBase(base))
    }

    @Test
    fun canonicalBaseDoesNotEatPartOfARealName() {
        // Only a trailing " (n)" goes. A parenthesised group anywhere else is left alone, and a
        // name that merely ends in a digit must not be truncated.
        assertEquals("flight-2026-08-11-14-03-22",
            FlightPathLogger.canonicalBase("flight-2026-08-11-14-03-22"))
        assertEquals("flight (a)", FlightPathLogger.canonicalBase("flight (a)"))
        assertEquals("flight (1) tail", FlightPathLogger.canonicalBase("flight (1) tail"))
    }
}

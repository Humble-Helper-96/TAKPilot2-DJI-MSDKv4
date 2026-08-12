package com.taklite.client.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the CoT XML that goes to the TAK server.
 *
 * Not schema validation — these hold the fields ATAK and CloudTAK actually read, so a refactor
 * cannot silently drop one. Everything asserted here is visible on somebody else's screen.
 */
class CotBuilderTest {

    @Test
    fun pilotPliCarriesIdentityPositionAndTakv() {
        val xml = CotBuilder.buildPLI(
            "PILOT-1", "MINI2-Pilot", "Cyan", "Team Member",
            61.1, -149.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot", "SM-G988U", "Android 13", "1.1.0")
        assertTrue("uid=\"PILOT-1\"" in xml)
        assertTrue("callsign=\"MINI2-Pilot\"" in xml)
        assertTrue("lat=\"61.1\"" in xml)
        assertTrue("lon=\"-149.9\"" in xml)
        assertTrue("a-f-G-U-C" in xml)          // PLI type
        assertTrue("<takv" in xml)
        assertTrue("<status battery=\"77\" />" in xml)
    }

    /**
     * The video url rides the OPERATOR marker, not the aircraft — the stream is a screen capture
     * of the controller and keeps running when the aircraft is down, while the drone PLI stops
     * the moment there is no GPS fix.
     */
    @Test
    fun aVideoUrlIsAdvertisedOnThePilotMarkerWhenGiven() {
        val withVideo = CotBuilder.buildPLI(
            "PILOT-1", "MINI2-Pilot", "Cyan", "Team Member",
            61.1, -149.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot", "SM-G988U", "Android 13", "1.1.0",
            "rtsp://server:8554/mini2")
        assertTrue("<__video" in withVideo)
        assertTrue("rtsp://server:8554/mini2" in withVideo)

        // The no-video overload must not emit an empty element for a stream that is not running.
        val without = CotBuilder.buildPLI(
            "PILOT-1", "MINI2-Pilot", "Cyan", "Team Member",
            61.1, -149.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot", "SM-G988U", "Android 13", "1.1.0")
        assertFalse("<__video" in without)
    }

    @Test
    fun dronePliCarriesTrackBatteryAndSensor() {
        val xml = droneXml()
        assertTrue("uid=\"UID-DRONE\"" in xml)
        assertTrue("lat=\"61.2\"" in xml)
        assertTrue("lon=\"-149.8\"" in xml)
        // Exact, not a family of maybes: this is the field a teammate reads off the aircraft.
        assertTrue("<status battery=\"66\" />" in xml)
        assertTrue("<sensor" in xml)
    }

    /**
     * AIRFRAME IDENTITY ON THE WIRE. These three strings tell every other client what aircraft
     * this is, and receiving clients may match on them to decide how to draw it.
     *
     * SENSOR_MODEL said M30T until 2026-08-11 — a stray literal carried over from a Matrice
     * build, so the whole channel was told the wrong aircraft and the wrong camera. Pinned so it
     * cannot drift back, and so the same mistake in the other direction (which is what the Autel
     * sibling had) is caught here rather than in the field.
     */
    @Test
    fun theDronePliIdentifiesThisAirframeAndNotAnother() {
        val xml = droneXml()
        assertTrue("model=\"MINI2\"" in xml)
        assertTrue("typeTag=\"_DJIV5_\"" in xml)
        assertTrue("type=\"DJIV5\"" in xml)
        assertFalse("a Matrice model string must never reappear", "M30T" in xml)
        assertFalse("this is not the Autel build", "AUTEL" in xml)
    }

    @Test
    fun theDroneIsAnAirTrackNotAGroundContact() {
        // Air domain — position 3 of the type. This is what makes other clients draw it as an
        // aircraft, and what CotParser keys the retention rules off.
        assertTrue("a-f-A-" in droneXml())
    }

    /**
     * The 2026-08-12 flight shipped the video url on the wire and NO client offered to play it:
     * the element was `<__video sensor url/>` with no uid and no ConnectionEntry. CloudTAK's CoT
     * library makes ConnectionEntry's uid and address mandatory, so there was nothing to build a
     * player from. These assertions are the regression.
     */
    @Test
    fun videoAdvertisementCarriesAConnectionEntry() {
        val url = "rtsp://tak:pw@anchortak.link:8554/Feed-B-Low?tcp"
        val xml = CotBuilder.buildPLI(
            "PILOT-1", "MINI2-Pilot", "Cyan", "Team Member",
            61.1, -149.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot", "SM-G988U", "Android 13", "1.1.0", url)

        assertTrue("<ConnectionEntry" in xml)
        assertTrue("address=\"anchortak.link\"" in xml)
        assertTrue("port=\"8554\"" in xml)
        assertTrue("path=\"/Feed-B-Low\"" in xml)
        assertTrue("protocol=\"rtsp\"" in xml)
        assertTrue("alias=\"MINI2-Pilot\"" in xml)
        // Both uids present and equal — a client keys its video entry on this.
        val uid = CotBuilder.videoUidFor(url)
        assertTrue("<__video uid=\"$uid\"" in xml)
        assertTrue("<ConnectionEntry uid=\"$uid\"" in xml)
        // The full url, credentials and all, still rides the element for clients that read it.
        assertTrue("url=\"rtsp://tak:pw@anchortak.link:8554/Feed-B-Low?tcp\"" in xml)
    }

    /** One stream, one uid: the aircraft and the operator must not advertise it as two feeds. */
    @Test
    fun aircraftAndOperatorAdvertiseTheSameVideoUid() {
        val url = "rtsp://host:8554/Feed-A"
        val pilot = CotBuilder.buildPLI(
            "PILOT-1", "MINI2-Pilot", "Cyan", "Team Member",
            61.1, -149.9, 35.0, 180.0, 0.0, 77,
            "TAKPilot", "SM-G988U", "Android 13", "1.1.0", url)
        val drone = CotBuilder.buildDronePLI(
            "UID-DRONE", "MINI2-1",
            61.2, -149.8, 100.0, 250.0, 7.5, 66,
            url, "UID-DRONE-SPI",
            73.0, 45.0, 250.0, -10.0, 300.0, 0.0,
            0.0, -10.0, 250.0,
            true, 300,
            2250, 1500, 7.6,
            "PILOT-1")
        val uid = CotBuilder.videoUidFor(url)
        assertTrue("<__video uid=\"$uid\"" in pilot)
        assertTrue("<__video uid=\"$uid\"" in drone)
    }

    /** No url, no element — an absent feed must not advertise an empty one. */
    @Test
    fun noVideoUrlMeansNoVideoElement() {
        assertFalse("__video" in droneXml())
    }

    private fun droneXml(): String = CotBuilder.buildDronePLI(
        "UID-DRONE", "MINI2-1",
        61.2, -149.8, 100.0, 250.0, 7.5, 66,
        null, "UID-DRONE-SPI",
        73.0, 45.0, 250.0, -10.0, 300.0, 0.0,
        0.0, -10.0, 250.0,
        true, 300,
        2250, 1500, 7.6,
        "PILOT-1")
}

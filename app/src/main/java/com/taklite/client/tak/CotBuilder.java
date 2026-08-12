package com.taklite.client.tak;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public class CotBuilder {
    private static final SimpleDateFormat COT_DATE_FORMAT;
    private static final String COT_VERSION = "2.0";
    private static final String PLI_TYPE = "a-f-G-U-C";
    private static final String PLI_HOW = "m-g";
    private static final long STALE_DURATION_MS = 300000; // 5 minutes — matches ATAK default

    // Drone (air) track: friendly-Air-Military-rotorcraftH-unmanned(Q). ATAK/taklite CotParser
    // detects the air domain ("-A-") as a drone. 2 minutes so a momentary GPS-lock loss still
    // shows the aircraft at its last known position on TAK instead of vanishing near-instantly
    // (was 15s — too short: it staled out almost as fast as the telemetry hiccup itself).
    private static final String DRONE_TYPE = "a-f-A-M-H-Q";
    private static final long DRONE_STALE_DURATION_MS = 120000; // 2 minutes

    // Pilot-dropped 2525 markers — see the note in buildMarker().
    //
    // 72 HOURS (operator, 2026-08-02). This is how long a dropped marker survives on OTHER
    // people's screens before their client drops it, so it is an operational choice rather than
    // a technical one: it has to outlast a multi-day callout without leaving last week's markers
    // cluttering the picture. Raised from 14 hours, which did not survive an overnight.
    //
    // Re-sending a marker refreshes its stale time on every client that receives it, so a pin
    // that is still wanted can be kept alive indefinitely from the markers list.
    private static final long MARKER_STALE_DURATION_MS = 72 * 60 * 60 * 1000L; // 72 hours

    // Sensor point of interest — the ground point the drone camera is looking at.
    private static final String SENSOR_POINT_TYPE = "b-m-p-s-p-i";
    private static final long SENSOR_POINT_STALE_MS = 15000; // 15s — clears if the feed stops

    /**
     * AIRFRAME IDENTITY BROADCAST IN EVERY DRONE CoT.
     *
     * <p>The vendor tags are correct here and stay: this really is a DJI aircraft, and receiving
     * TAK clients match on these strings. {@code SENSOR_MODEL} was {@code M30T} until 2026-08-11
     * — a stray literal that told every client on the channel this was a Matrice 30T. It is a
     * Mini 2. A teammate or a log reading the airframe type got the wrong aircraft and the wrong
     * camera. The Autel sibling carried the identical defect in the opposite direction, which is
     * how it was found.
     *
     * <p>Named constants rather than literals at the three append sites, for two reasons: they
     * are one identity expressed in three attributes and must move together, and this class is
     * otherwise sibling-agnostic (see the takvPlatform note below — it is shared in SHAPE with
     * the Autel port, which keeps its own copy of this file with its own values). Anything
     * app-specific in here should be obvious rather than buried in a StringBuilder.
     *
     * <p>⚠ These strings go to OTHER PEOPLE'S CLIENTS, which may match on them to decide how to
     * draw the aircraft. If the drone stops rendering correctly in a TAK client after this
     * change, suspect these first — the receiving side may recognise a fixed set of vendor tags.
     * {@code SENSOR_MODEL} is the one to check on a Mini 2, because it is now the only one of
     * the three that has ever changed.
     */
    private static final String VEHICLE_TYPE_TAG = "_DJIV5_";
    private static final String VEHICLE_TYPE = "DJIV5";
    private static final String SENSOR_MODEL = "MINI2";

    static {
        COT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * @param takvPlatform the client-identity string that becomes {@code <takv platform="...">}
     *   — what a TAK server's "Connected Users" panel shows as the client type. Pass whatever
     *   the calling app actually is; this class stays sibling-agnostic on purpose (shared with
     *   the Autel port, which is a different app and must not be labeled as this one).
     * @param takvDevice   the {@code device} attribute — real hardware identity (and whatever
     *   else the caller wants folded in, e.g. the current callsign) helps a teammate tell two
     *   otherwise-identical installs apart in the server's connected-users list.
     * @param takvOs       the {@code os} attribute.
     * @param takvVersion  the {@code version} attribute — the caller's real app version, not a
     *   string owned by this shared class (which has no version of its own to report).
     */
    /** Back-compat overload: no video advertised. */
    public static String buildPLI(String uid, String callsign, String team, String role,
                                   double lat, double lon, double alt,
                                   double bearing, double speed, int battery,
                                   String takvPlatform, String takvDevice,
                                   String takvOs, String takvVersion) {
        return buildPLI(uid, callsign, team, role, lat, lon, alt, bearing, speed, battery,
                takvPlatform, takvDevice, takvOs, takvVersion, null);
    }

    /**
     * @param videoUrl RTSP url to advertise on THIS marker, or null to omit.
     *
     * The video rides on the OPERATOR marker, not the aircraft (operator, 2026-08-05). The stream
     * is a screen capture of the controller, and it keeps running when the aircraft is down — but
     * the drone PLI stops the moment there is no GPS fix, so a video advertised there became
     * unreachable in exactly the case the screen-capture design exists to cover.
     */
    public static String buildPLI(String uid, String callsign, String team, String role,
                                   double lat, double lon, double alt,
                                   double bearing, double speed, int battery,
                                   String takvPlatform, String takvDevice,
                                   String takvOs, String takvVersion, String videoUrl) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String start = time;
        String stale = formatTime(now + STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(PLI_TYPE).append("\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"").append(PLI_HOW).append("\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(start).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(alt).append("\"");
        sb.append(" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<__group name=\"").append(escapeXml(team)).append("\"");
        sb.append(" role=\"").append(escapeXml(role)).append("\" />");
        sb.append("<status battery=\"").append(battery).append("\" />");
        sb.append("<track course=\"").append(bearing).append("\"");
        sb.append(" speed=\"").append(speed).append("\" />");
        sb.append("<precisionlocation geopointsrc=\"GPS\" altsrc=\"GPS\" />");
        sb.append("<takv device=\"").append(escapeXml(takvDevice)).append("\"");
        sb.append(" os=\"").append(escapeXml(takvOs)).append("\"");
        sb.append(" platform=\"").append(escapeXml(takvPlatform)).append("\"");
        sb.append(" version=\"").append(escapeXml(takvVersion)).append("\" />");
        sb.append("<uid Droid=\"").append(escapeXml(callsign)).append("\" />");
        appendVideo(sb, videoUrl, callsign, null);
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    /**
     * Build a CoT position report for the AIRCRAFT (drone) as a distinct air track.
     * This is separate from the operator's PLI: it has its own uid, an air-domain type,
     * and a sensor/__video detail block so TAK clients can recognize the drone and its feed.
     *
     * @param uid       stable per-drone uid (e.g. "TAKPilot2-DRONE-<serial>")
     * @param callsign  drone callsign shown on TAK
     * @param lat,lon   aircraft position (deg)
     * @param hae       height above ellipsoid (m)
     * @param heading   true heading / course (deg)
     * @param speed     horizontal ground speed (m/s)
     * @param battery   aircraft battery percent (0-100)
     * @param videoUrl  optional RTSP/stream url to advertise (null/empty to omit)
     */
    public static String buildDronePLI(String uid, String callsign,
                                       double lat, double lon, double hae,
                                       double heading, double speed, int battery,
                                       String videoUrl, String spiUid,
                                       double sensorFov, double sensorVfov, double sensorAzimuth,
                                       double sensorElevation, double sensorRange, double northRef,
                                       double gimbalRoll, double gimbalPitch, double gimbalYaw,
                                       boolean isFlying, int flightTimeSec,
                                       int batteryMaxMah, int batteryRemainMah, double voltage,
                                       String operatorUid) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + DRONE_STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(DRONE_TYPE).append("\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"m-g\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(hae).append("\"");
        sb.append(" ce=\"0.0\" le=\"0.0\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<_uastool extendedCot=\"true\" activeRoute=\"false\" />");
        sb.append("<track course=\"").append(heading).append("\"");
        sb.append(" slope=\"0.0\" speed=\"").append(speed).append("\" />");
        if (sensorFov > 0 && sensorRange > 0) {
            sb.append("<sensor");
            sb.append(" elevation=\"").append(sensorElevation).append("\"");
            sb.append(" vfov=\"").append(sensorVfov).append("\"");
            sb.append(" north=\"").append(northRef).append("\"");
            sb.append(" roll=\"0\"");
            sb.append(" range=\"").append((int) Math.round(sensorRange)).append("\"");
            sb.append(" azimuth=\"").append(sensorAzimuth).append("\"");
            sb.append(" model=\"").append(SENSOR_MODEL).append("\"");
            sb.append(" fov=\"").append(sensorFov).append("\"");
            sb.append(" type=\"r-e\"");
            sb.append(" version=\"0.6\" />");
        }
        sb.append("<spatial><attitude");
        sb.append(" roll=\"").append(gimbalRoll).append("\"");
        sb.append(" pitch=\"").append(gimbalPitch).append("\"");
        sb.append(" yaw=\"").append(gimbalYaw).append("\"");
        sb.append(" /><spin roll=\"0.0\" pitch=\"0.0\" yaw=\"0.0\" /></spatial>");
        sb.append("<vehicle goHomeBatteryPercent=\"20\" hal=\"0.0\"");
        sb.append(" flightTimeRemaining=\"0\"");
        sb.append(" typeTag=\"").append(VEHICLE_TYPE_TAG).append("\"");
        sb.append(" batteryRemainingCapacity=\"").append(batteryRemainMah).append("\"");
        sb.append(" isFlying=\"").append(isFlying).append("\"");
        sb.append(" flightTime=\"").append(flightTimeSec).append("\"");
        sb.append(" type=\"").append(VEHICLE_TYPE).append("\"");
        sb.append(" batteryMaxCapacity=\"").append(batteryMaxMah).append("\"");
        sb.append(" voltage=\"").append(String.format("%.2f", voltage)).append("\" />");
        sb.append("<_radio rssi=\"100\" gps=\"true\" />");
        sb.append("<waypointCollection />");
        if (operatorUid != null && !operatorUid.isEmpty()) {
            sb.append("<_route sender=\"").append(escapeXml(operatorUid)).append("\" />");
        }
        sb.append("<commandedData climbRate=\"0.0\" />");
        appendVideo(sb, videoUrl, callsign, spiUid);
        if (operatorUid != null && !operatorUid.isEmpty()) {
            sb.append("<link uid=\"").append(escapeXml(operatorUid)).append("\"");
            sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        }
        sb.append("<status battery=\"").append(battery).append("\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    /**
     * Sensor point of interest — the ground point the drone camera is looking at.
     * Stable uid so it updates the same marker continuously (a live camera crosshair).
     * Linked back to the drone uid so ATAK can associate it with the aircraft.
     *
     * @param uid        stable per-drone sensor-point uid (e.g. "<drone-uid>-SPI")
     * @param droneUid   the drone's CoT uid, for the association link
     * @param callsign   label shown on TAK
     * @param lat,lon    ground point (deg)
     * @param rangeM     slant range to the point (m), for the remarks
     */
    public static String buildSensorPoint(String uid, String droneUid, String callsign,
                                          double lat, double lon, double rangeM) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + SENSOR_POINT_STALE_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(SENSOR_POINT_TYPE).append("\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"m-g\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"0\" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" />");
        sb.append("<link uid=\"").append(escapeXml(droneUid)).append("\"");
        sb.append(" type=\"").append(DRONE_TYPE).append("\" relation=\"p-p\" />");
        sb.append("<remarks>Camera point, slant range ")
                .append(Math.round(rangeM)).append("m</remarks>");
        sb.append("<precisionlocation geopointsrc=\"CALC\" altsrc=\"DTED0\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    /**
     * Camera footprint — the polygon on the ground the camera currently sees.
     * A u-d-f free-form drawing (ATAK polygon) whose vertices are the projected FOV
     * corners. Stable uid so it updates the same shape continuously.
     *
     * @param uid      stable footprint uid (e.g. "<drone-uid>-FOV")
     * @param callsign label
     * @param corners  ground corners as {lat, lon} pairs (clockwise); needs >= 3
     */
    public static String buildFootprintPolygon(String uid, String callsign,
                                               double[][] corners) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + SENSOR_POINT_STALE_MS);

        // Centroid for the <point>.
        double clat = 0, clon = 0;
        for (double[] c : corners) { clat += c[0]; clon += c[1]; }
        clat /= corners.length; clon /= corners.length;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"u-d-f\"");
        sb.append(" uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" how=\"m-g\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(clat).append("\" lon=\"").append(clon).append("\"");
        sb.append(" hae=\"0\" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" />");
        // Polygon vertices (closed: repeat first at the end).
        for (double[] c : corners) {
            sb.append("<link point=\"").append(c[0]).append(",").append(c[1]).append("\" />");
        }
        sb.append("<link point=\"").append(corners[0][0]).append(",").append(corners[0][1]).append("\" />");
        sb.append("<strokeColor value=\"-1\" />");        // white outline
        sb.append("<strokeWeight value=\"3\" />");
        sb.append("<fillColor value=\"533200383\" />");   // translucent cyan fill (0x1F00BFFF)
        sb.append("<labels_on value=\"false\" />");
        sb.append("<remarks>Camera footprint</remarks>");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String buildAlert(String uid, String callsign, String team, String role,
                                     double lat, double lon, double alt, String alertType) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + 300000);
        String alertId = "alert-" + UUID.randomUUID().toString().substring(0, 8);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"b-a-o-tbl\"");
        sb.append(" uid=\"").append(escapeXml(alertId)).append("\"");
        sb.append(" how=\"m-a\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(alt).append("\"");
        sb.append(" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<emergency type=\"").append(escapeXml(alertType)).append("\">");
        sb.append(escapeXml(callsign));
        sb.append("</emergency>");
        sb.append("<link uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        sb.append("<remarks source=\"").append(escapeXml(uid)).append("\">");
        sb.append(escapeXml(callsign)).append(" has activated ").append(escapeXml(alertType));
        sb.append("</remarks>");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String buildAlertCancel(String uid, String callsign, String alertId) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        String stale = formatTime(now + STALE_DURATION_MS);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"b-a-o-can\"");
        sb.append(" uid=\"").append(escapeXml(alertId)).append("\"");
        sb.append(" how=\"m-a\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"0\" lon=\"0\" hae=\"0\" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" endpoint=\"*:-1:stcp\" />");
        sb.append("<emergency cancel=\"true\">");
        sb.append(escapeXml(callsign));
        sb.append("</emergency>");
        sb.append("<link uid=\"").append(escapeXml(uid)).append("\"");
        sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String buildMarker(String senderUid, String senderCallsign, String markerUid,
                                      String affiliation, double lat, double lon, double alt,
                                      String name, String remarks) {
        return buildMarker(senderUid, senderCallsign, markerUid, affiliation, lat, lon, alt, name, remarks, null);
    }

    /**
     * Build a marker CoT. If {@code missionName} is non-null, a
     * {@code <marti><dest mission=".."/></marti>} tag is added so the TAK Server routes it ONLY
     * to that mission/feed's subscribers instead of broadcasting server-wide.
     */
    public static String buildMarker(String senderUid, String senderCallsign, String markerUid,
                                      String affiliation, double lat, double lon, double alt,
                                      String name, String remarks, String missionName) {
        long now = System.currentTimeMillis();
        String time = formatTime(now);
        // 14 hours (operator's call, 2026-07-25): long enough that a dropped marker persists
        // through an entire incident, short enough that it clears itself before the next
        // shift so nobody inherits a map full of yesterday's pins. Deliberately unrelated to
        // DRONE_STALE_DURATION_MS (2 min, live track) and the SPI stale (15s) — a static
        // marker and a moving aircraft want completely different lifetimes.
        String stale = formatTime(now + MARKER_STALE_DURATION_MS);

        String cotType;
        switch (affiliation.toLowerCase()) {
            case "hostile":  cotType = "a-h-G"; break;
            case "unknown":  cotType = "a-u-G"; break;
            case "neutral":  cotType = "a-n-G"; break;
            case "friendly":
            default:         cotType = "a-f-G"; break;
        }

        String callsign = (name != null && !name.isEmpty()) ? name : affiliation;
        String remarksText = (remarks != null && !remarks.isEmpty()) ? remarks
                : "Dropped by " + senderCallsign;

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<event version=\"").append(COT_VERSION).append("\"");
        sb.append(" type=\"").append(cotType).append("\"");
        sb.append(" uid=\"").append(escapeXml(markerUid)).append("\"");
        sb.append(" how=\"h-g-i-g-o\"");
        sb.append(" time=\"").append(time).append("\"");
        sb.append(" start=\"").append(time).append("\"");
        sb.append(" stale=\"").append(stale).append("\"");
        sb.append(">");
        sb.append("<point lat=\"").append(lat).append("\"");
        sb.append(" lon=\"").append(lon).append("\"");
        sb.append(" hae=\"").append(alt).append("\"");
        sb.append(" ce=\"9999999\" le=\"9999999\" />");
        sb.append("<detail>");
        sb.append("<contact callsign=\"").append(escapeXml(callsign)).append("\" />");
        sb.append("<remarks source=\"").append(escapeXml(senderUid)).append("\">");
        sb.append(escapeXml(remarksText));
        sb.append("</remarks>");
        sb.append("<link uid=\"").append(escapeXml(senderUid)).append("\"");
        sb.append(" type=\"a-f-G-U-C\" relation=\"p-p\" />");
        sb.append("<precisionlocation geopointsrc=\"Human\" altsrc=\"DTED0\" />");
        // Scope to a mission/feed so the server routes it only to that feed's subscribers
        // (no server-wide broadcast). ATAK uses <marti><dest mission="..."/></marti>.
        if (missionName != null && !missionName.isEmpty()) {
            sb.append("<marti><dest mission=\"").append(escapeXml(missionName)).append("\" /></marti>");
        }
        sb.append("</detail>");
        sb.append("</event>");
        return sb.toString();
    }

    public static String generateUid() {
        return "TAKLite-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String formatTime(long millis) {
        synchronized (COT_DATE_FORMAT) {
            return COT_DATE_FORMAT.format(new Date(millis));
        }
    }

    /**
     * The video advertisement, in the shape TAK clients actually parse.
     *
     * <p>⚠ This used to emit {@code <__video sensor=".." url=".."/>} and nothing else. That is
     * self-consistent — our own {@link CotParser} reads exactly those two attributes — but no
     * client can build a player from it, so the marker arrived on ATAK/CloudTAK/TAKAware with no
     * play control and the stream never reached the video manager. Confirmed on the 2026-08-12
     * flight: the url WAS on the wire (246 of 702 operator PLIs carried it) and still nothing
     * offered to play it.
     *
     * <p>The shape below is the one CloudTAK's CoT library (dfpc-coe/node-CoT) requires: a nested
     * {@code ConnectionEntry} whose {@code uid} and {@code address} are BOTH mandatory. Optional
     * attributes are sent anyway, with ATAK's own defaults, because a client that reads them and
     * finds them missing falls back to values we did not choose.
     *
     * <p>The video uid is derived from the URL, so it is stable across restarts and IDENTICAL on
     * the aircraft and the operator marker. That is deliberate: it is one stream, and two markers
     * advertising it under one uid give a client one video entry referenced twice, not two
     * competing entries for the same feed.
     *
     * <p>⚠ {@code url} keeps whatever the caller passed, credentials included — that is how the
     * stream authenticates today and removing them would break playback on a server that needs
     * them. {@code address} is the bare host, so a client that builds only from ConnectionEntry
     * gets a clean address. If a feed needs auth and a client uses ConnectionEntry alone, this is
     * where that shows up.
     *
     * @param alias human-readable name for the feed; shown in a client's video manager.
     */
    private static void appendVideo(StringBuilder sb, String videoUrl, String alias, String spiUid) {
        if (videoUrl == null || videoUrl.isEmpty()) return;

        String videoUid = videoUidFor(videoUrl);
        String host = "";
        int port = -1;
        String path = "";
        String protocol = "raw";
        try {
            java.net.URI u = java.net.URI.create(videoUrl);
            if (u.getScheme() != null) protocol = u.getScheme();
            if (u.getHost() != null) host = u.getHost();
            port = u.getPort();
            if (u.getPath() != null) path = u.getPath();
        } catch (IllegalArgumentException e) {
            // An unparseable url is still worth advertising: `url` carries the whole thing, and
            // `address` falling back to it matches how ATAK advertises non-host feeds.
        }
        if (host.isEmpty()) host = videoUrl;

        sb.append("<__video uid=\"").append(escapeXml(videoUid)).append("\"");
        sb.append(" sensor=\"").append(escapeXml(alias)).append("\"");
        if (spiUid != null && !spiUid.isEmpty()) {
            sb.append(" spi=\"").append(escapeXml(spiUid)).append("\"");
        }
        sb.append(" url=\"").append(escapeXml(videoUrl)).append("\">");
        sb.append("<ConnectionEntry uid=\"").append(escapeXml(videoUid)).append("\"");
        sb.append(" alias=\"").append(escapeXml(alias)).append("\"");
        sb.append(" address=\"").append(escapeXml(host)).append("\"");
        sb.append(" port=\"").append(port).append("\"");
        sb.append(" path=\"").append(escapeXml(path)).append("\"");
        sb.append(" protocol=\"").append(escapeXml(protocol)).append("\"");
        sb.append(" networkTimeout=\"12000\" bufferTime=\"-1\" roverPort=\"-1\"");
        sb.append(" rtspReliable=\"0\" ignoreEmbeddedKLV=\"false\" />");
        sb.append("</__video>");
    }

    /**
     * A stable uid for a feed, derived from its URL.
     *
     * <p>Must not be random: a fresh uid on every position report (one every 2 seconds) would
     * have a client either create a new video entry each time or churn the existing one.
     */
    static String videoUidFor(String videoUrl) {
        return UUID.nameUUIDFromBytes(videoUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}

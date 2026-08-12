package com.taklite.client.tak;

import com.taklite.util.AppLog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

public class CotParser {
    private static final String TAG = "CotParser";
    private static final long MIN_STALE_DURATION_MS = 300000; // 5 min minimum stale window

    /**
     * Uid prefix the operator's METAR gateway stamps on every weather station (`METAR-<ICAO>`).
     *
     * The uid is the only reliable discriminator: a station's type is `a-u-G`, which is identical
     * to a pilot-placed "unknown" marker. Mirrored in {@code ArSettings} for the AR category that
     * used to hide them; this parser now drops them before anything sees them.
     */
    private static final String METAR_UID_PREFIX = "METAR-";
    private static final SimpleDateFormat COT_DATE_FORMAT;

    static {
        COT_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public static class AlertMessage {
        public String alertId;
        public String senderCallsign;
        public String alertType;
        public String linkedUid;
        public double lat;
        public double lon;
        public double alt;
        public boolean isCancellation;
    }

    public static TakUser parse(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        try {
            String cleaned = xml.replaceAll("<\\?xml[^?]*\\?>", "").trim();
            if (cleaned.isEmpty()) return null;

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(cleaned));

            String uid = null;
            String type = null;
            long staleTime = 0;
            double lat = 0, lon = 0, alt = 0;
            String callsign = null;
            String team = null;
            String role = null;
            String videoUrl = null;
            String videoAlias = null;
            String sensorModel = null;
            double sensorFov = -1, sensorAzimuth = -1, sensorRange = -1;
            double course = -1;   // <track course>, degrees true; -1 = not reported
            String operatorUid = null;
            boolean archived = false;    // <archived/> in detail — see isPersistentType
            boolean hasTakv = false;     // <takv> = a live TAK CLIENT announcing itself
            boolean hasEndpoint = false; // <contact endpoint=…> = reachable, i.e. also a client

            for (int eventType = parser.getEventType(); eventType != XmlPullParser.END_DOCUMENT; eventType = parser.next()) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("event".equals(tag)) {
                        type = parser.getAttributeValue(null, "type");
                        // Accept any positional CoT we want on the map: unit/PLI (a-*) and
                        // point/marker types (b-m-*, b-i-*, etc.). Reject control/alert types
                        // (alerts b-a-o-* and disconnects t-x-d-* are handled separately before
                        // this parse() runs in TakManager.processCoT).
                        if (type == null) return null;
                        boolean positional = type.startsWith("a-")
                                || type.startsWith("b-m-")   // markers / map points / waypoints
                                || type.startsWith("b-i-")   // imagery / image points
                                || type.startsWith("b-d-")   // detections
                                || type.startsWith("b-r-")   // routes (point reps)
                                || type.startsWith("b-l-")   // alarms/links with position
                                || type.startsWith("b-g-");  // geofence/marker variants
                        if (!positional) return null;
                        uid = parser.getAttributeValue(null, "uid");
                        // METAR WEATHER STATIONS ARE DROPPED AT THE DOOR (operator, 2026-08-04).
                        //
                        // The ADS-B feed carries METAR alongside the aircraft. A TAKPilot pilot
                        // cannot use them: the content is in <remarks>, which this app does not
                        // parse or display, so a station is an unreadable dot competing for
                        // attention with traffic that matters.
                        //
                        // Dropping here rather than hiding at draw time is the point. They were
                        // being stored and PERSISTED: 136 of the 155 entries in the saved-marker
                        // file were METAR stations, which is most of that file. A view-level
                        // toggle leaves that cost in place.
                        if (uid != null && uid.startsWith(METAR_UID_PREFIX)) return null;
                        String staleStr = parser.getAttributeValue(null, "stale");
                        if (staleStr != null) {
                            staleTime = parseTime(staleStr);
                            // Enforce a minimum stale window so contacts don't grey out between
                            // PLI updates from users with long reporting intervals — but NOT for
                            // air-domain contacts (ADS-B tracks, other drones). Those self-declare
                            // an honest, short stale window (an ADS-B ping is typically valid ~30s)
                            // and update every couple of seconds anyway, so the floor buys them
                            // nothing. Applying it anyway was a real bug: near busy airspace the
                            // known-contacts map held every distinct aircraft for a MINIMUM of
                            // ~10 minutes (this floor plus TakUser.isExpired()'s own +5min grace),
                            // growing effectively unbounded over a session — 161 "known" contacts
                            // were held here while the live picture on a second TAK client showed
                            // a handful. That unbounded growth is the root cause traced to a
                            // sequence of app-process OOM kills on 2026-08-03.
                            if (!isAirDomain(type)) {
                                long minStale = System.currentTimeMillis() + MIN_STALE_DURATION_MS;
                                if (staleTime < minStale) {
                                    staleTime = minStale;
                                }
                            }
                        }
                    } else if ("point".equals(tag)) {
                        lat = parseDouble(parser.getAttributeValue(null, "lat"));
                        lon = parseDouble(parser.getAttributeValue(null, "lon"));
                        alt = parseDouble(parser.getAttributeValue(null, "hae"));
                    } else if ("takv".equals(tag)) {
                        hasTakv = true;
                    } else if ("contact".equals(tag)) {
                        callsign = parser.getAttributeValue(null, "callsign");
                        hasEndpoint = parser.getAttributeValue(null, "endpoint") != null;
                    } else if ("__group".equals(tag)) {
                        team = parser.getAttributeValue(null, "name");
                        role = parser.getAttributeValue(null, "role");
                    } else if ("__video".equals(tag)) {
                        videoUrl = parser.getAttributeValue(null, "url");
                        videoAlias = parser.getAttributeValue(null, "sensor");
                    } else if ("ConnectionEntry".equals(tag)) {
                        // The nested element real TAK clients put the feed in. ATAK itself sends
                        // no `url` on __video at all, so a sender's whole video advertisement is
                        // here — read it, but never let it overwrite a url we already took from
                        // __video, which is the more specific of the two.
                        if (videoUrl == null || videoUrl.isEmpty()) {
                            videoUrl = parser.getAttributeValue(null, "address");
                        }
                        if (videoAlias == null || videoAlias.isEmpty()) {
                            videoAlias = parser.getAttributeValue(null, "alias");
                        }
                    } else if ("sensor".equals(tag)) {
                        sensorModel = parser.getAttributeValue(null, "model");
                        sensorFov = parseDouble(parser.getAttributeValue(null, "fov"));
                        sensorAzimuth = parseDouble(parser.getAttributeValue(null, "azimuth"));
                        sensorRange = parseDouble(parser.getAttributeValue(null, "range"));
                    } else if ("track".equals(tag)) {
                        // Course of an inbound track. ADS-B gateways populate this; it is what
                        // lets the map draw an aircraft symbol pointing where the aircraft is
                        // actually going instead of an arbitrary direction.
                        course = parseDouble(parser.getAttributeValue(null, "course"));
                    } else if ("archived".equals(tag) || "archive".equals(tag)) {
                        // TAK's "persist this, it is not a transient track" marker. An empty
                        // element in <detail>. Spelled both ways across clients, so accept both.
                        // Corroborating signal only — see isPersistentType.
                        archived = true;
                    } else if ("link".equals(tag)) {
                        String relation = parser.getAttributeValue(null, "relation");
                        if ("p-p".equals(relation)) {
                            operatorUid = parser.getAttributeValue(null, "uid");
                        }
                    }
                }
            }

            if (uid == null) return null;
            if (lat == 0 && lon == 0) return null;
            if (callsign == null || callsign.isEmpty()) callsign = uid;
            if (team == null) team = "Cyan";
            if (role == null) role = "Team Member";

            TakUser user = new TakUser(uid, callsign, lat, lon, alt, team, role, staleTime);
            user.setType(type);   // raw CoT type, used to resolve the map symbol/icon
            user.setPersistent(isPersistentType(type, archived));

            // Retention diagnostic — AIR DOMAIN EXCLUDED ON PURPOSE.
            //
            // Logging every event flooded the log: ADS-B alone produced 552 events in one capture
            // and rotated the 1 MB file every ~4 minutes, which destroyed the very history being
            // diagnosed. Air tracks can never be persistent, so they tell this line nothing.
            if (!isAirDomain(type)) {
                AppLog.v(TAG, "rx type=" + type + " uid=" + uid + " cs=" + callsign
                        + " archived=" + archived + " takv=" + hasTakv + " endpoint=" + hasEndpoint
                        + " persistent=" + user.isPersistent());
            }

            // Detect drone: type contains "-A-" (Air domain, e.g. a-f-A-M-H-Q)
            if (isAirDomain(type)) {
                user.setDrone(true);
            }
            if (videoUrl != null) user.setVideoUrl(videoUrl);
            if (videoAlias != null) user.setVideoAlias(videoAlias);
            if (sensorModel != null) user.setSensorModel(sensorModel);
            if (sensorFov > 0) user.setSensorFov(sensorFov);
            if (sensorAzimuth >= 0) user.setSensorAzimuth(sensorAzimuth);
            if (sensorRange > 0) user.setSensorRange(sensorRange);
            if (course >= 0) user.setCourse(course);
            if (operatorUid != null) user.setOperatorUid(operatorUid);

            return user;
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to parse CoT: " + e.getMessage());
            return null;
        }
    }

    public static AlertMessage parseAlert(String xml) {
        if (xml == null || xml.isEmpty()) return null;
        try {
            String cleaned = xml.replaceAll("<\\?xml[^?]*\\?>", "").trim();
            if (cleaned.isEmpty()) return null;

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(cleaned));

            AlertMessage alert = new AlertMessage();

            for (int eventType = parser.getEventType(); eventType != XmlPullParser.END_DOCUMENT; eventType = parser.next()) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("event".equals(tag)) {
                        String type = parser.getAttributeValue(null, "type");
                        if (type == null || (!type.equals("b-a-o-tbl") && !type.equals("b-a-o-can"))) {
                            return null;
                        }
                        alert.isCancellation = "b-a-o-can".equals(type);
                        alert.alertId = parser.getAttributeValue(null, "uid");
                    } else if ("point".equals(tag)) {
                        alert.lat = parseDouble(parser.getAttributeValue(null, "lat"));
                        alert.lon = parseDouble(parser.getAttributeValue(null, "lon"));
                        alert.alt = parseDouble(parser.getAttributeValue(null, "hae"));
                    } else if ("contact".equals(tag)) {
                        alert.senderCallsign = parser.getAttributeValue(null, "callsign");
                    } else if ("emergency".equals(tag)) {
                        alert.alertType = parser.getAttributeValue(null, "type");
                    } else if ("link".equals(tag)) {
                        String linkType = parser.getAttributeValue(null, "type");
                        if ("a-f-G-U-C".equals(linkType)) {
                            alert.linkedUid = parser.getAttributeValue(null, "uid");
                        }
                    }
                }
            }

            if (alert.alertId == null) return null;
            return alert;
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to parse alert: " + e.getMessage());
            return null;
        }
    }

    public static String parseDisconnect(String xml) throws XmlPullParserException, IOException {
        if (xml == null || xml.isEmpty()) return null;
        try {
            String cleaned = xml.replaceAll("<\\?xml[^?]*\\?>", "").trim();
            if (cleaned.isEmpty()) return null;

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(cleaned));

            String linkedUid = null;

            for (int eventType = parser.getEventType(); eventType != XmlPullParser.END_DOCUMENT; eventType = parser.next()) {
                if (eventType == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("event".equals(tag)) {
                        String type = parser.getAttributeValue(null, "type");
                        if (!"t-x-d-d".equals(type)) {
                            return null;
                        }
                    } else if ("link".equals(tag)) {
                        linkedUid = parser.getAttributeValue(null, "uid");
                    }
                }
            }
            return linkedUid;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True for a SHARED MARKER — a point somebody placed deliberately, as opposed to a position
     * report that a client re-broadcasts.
     *
     * Two families:
     *  - `a-{f,h,n,u}-G…` that is NOT the `-G-U-…` unit form. These are MIL-STD-2525 affiliation
     *    markers. CloudTAK sends its map points this way (observed: `a-n-G`).
     *  - `b-m-p-*` — the classic ATAK/iTAK map point, waypoint and marker types.
     *
     * ⚠ MUST AGREE WITH {@code TakMapMarkers.milMarkerRes}, which maps the first family onto its
     * 2525 frame drawable. That method is the icon side of the same question; this is the
     * retention side. If one changes, change both.
     */
    public static boolean isMarkerType(String type) {
        if (type == null) return false;
        if (isUnitType(type)) return false;
        if (isTransientPoint(type)) return false;
        if (type.startsWith("b-m-p-")) return true;
        String[] parts = type.split("-");
        if (parts.length < 3 || !"a".equals(parts[0]) || !"G".equals(parts[2])) return false;
        return "f".equals(parts[1]) || "h".equals(parts[1])
                || "n".equals(parts[1]) || "u".equals(parts[1]);
    }

    /**
     * A unit / position report — `a-?-?-U-…`. A person or vehicle reporting where it is.
     *
     * Never persistent, whatever the sender flags. A teammate who goes off the net must fade;
     * an immortal PLI is a person shown somewhere they are not.
     */
    private static boolean isUnitType(String type) {
        if (type == null) return false;
        String[] parts = type.split("-");
        return parts.length >= 4 && "a".equals(parts[0]) && "U".equals(parts[3]);
    }

    /**
     * A point that is continuously RE-DERIVED rather than placed — currently the sensor
     * point-of-interest family `b-m-p-s-p-*`, which is where a camera is looking right now.
     *
     * Never persistent. An SPI is republished every couple of seconds with a short stale window
     * and is meaningless once the aircraft lands; keeping one for 72 hours would leave a camera
     * cue on the map pointing at nothing. Note `b-m-p-s-m` — a placed spot marker — is a
     * different thing and IS persistent.
     */
    private static boolean isTransientPoint(String type) {
        return type != null && type.startsWith("b-m-p-s-p-");
    }

    /**
     * True when this event should survive the stale sweep — see {@code TakManager.removeStaleUsers}.
     *
     * A marker shared to this aircraft is not a track. It does not re-broadcast on a heartbeat, and
     * a sender may set a stale window that is useless: CloudTAK sends its markers with `stale` only
     * ~3.6 SECONDS after `start`, so honouring it deleted them within minutes of arriving. Such an
     * item leaves on an explicit delete, on a local delete, or on the 72-hour eviction in
     * {@code TakMapMarkers} — never on a timeout.
     *
     * ⚠ AIR DOMAIN IS EXCLUDED, AND THAT EXCLUSION IS A SAFETY GUARD, NOT A TIDINESS RULE.
     * Unbounded contact retention is what OOM-killed the flight app in the air on 2026-08-03: the
     * known-contacts map held 161 distinct aircraft while the live picture showed a handful. Air
     * tracks report constantly and declare an honest short stale window; they MUST keep expiring.
     * Do not relax this to "anything with the archived flag" — an ADS-B gateway that sets that flag
     * would reproduce the crash.
     *
     * @param archived whether the sender marked the event archived (see the parse loop). Treated as
     *                 a corroborating signal only — the type test carries the decision, because it
     *                 is not confirmed that every sending client puts the flag on the wire.
     */
    public static boolean isPersistentType(String type, boolean archived) {
        // ARCHIVED IS REQUIRED, AND THE TYPE STRING IS NOT TRUSTED ON ITS OWN.
        //
        // Measured on the operator's live net, 2026-08-04 — 605 consecutive inbound events:
        //
        //   552  a-f-A-C-F    archived=false   ADS-B aircraft
        //    31  a-f-G-E-V-C  archived=false   CloudTAK USERS (live clients, re-broadcasting)
        //    10  a-f-G-E-V    archived=false   ADS-B ground vehicles (ICAO-… uids)
        //     2  a-f-G-U-C    archived=false   team PLI
        //     1  a-u-G        archived=TRUE    a placed marker
        //     1  a-f-G        archived=TRUE    a placed marker
        //
        // Only the two placed markers carry the flag, and they came from two different clients.
        // A type test cannot do this job: `a-f-G-E-V` (an ADS-B ground vehicle) and `a-f-G` (a
        // marker) differ only by a suffix, and `a-f-G-E-V-C` — CloudTAK's own users — reads as a
        // marker by every type rule that also accepts `a-f-G`. Trusting the type made 152 of 155
        // stored entries immortal, including every user who had ever connected.
        //
        // ⚠ THE TRADE: a client that does not set `archived` gets no persistence — its markers
        // expire as they always did. That is the SAFE direction to fail. The opposite default
        // (persist unless told otherwise) is unbounded retention, which is what OOM-killed the
        // flight app in the air on 2026-08-03.
        if (!archived) return false;
        // Belt and braces: a sender that sets `archived` on a track, a position report or a
        // sensor point must not be able to make it immortal.
        if (isAirDomain(type)) return false;
        if (isUnitType(type)) return false;
        if (isTransientPoint(type)) return false;
        return true;
    }

    /**
     * True for CoT types in the Air domain (position index 2 of the dash-separated type, e.g.
     * "A" in a-f-A-M-H-Q or a-f-A-C-F). Covers both drones and ADS-B-fed manned aircraft — both
     * report frequently and self-declare an honest stale window, unlike slow-reporting ground PLI.
     */
    private static boolean isAirDomain(String type) {
        if (type == null || type.length() < 5) return false;
        String[] parts = type.split("-");
        return parts.length >= 3 && "A".equals(parts[2]);
    }

    private static long parseTime(String timeStr) {
        try {
            synchronized (COT_DATE_FORMAT) {
                return COT_DATE_FORMAT.parse(timeStr).getTime();
            }
        } catch (Exception e) {
            try {
                SimpleDateFormat altFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                altFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                return altFormat.parse(timeStr).getTime();
            } catch (Exception e2) {
                return System.currentTimeMillis() + MIN_STALE_DURATION_MS;
            }
        }
    }

    private static double parseDouble(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}

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
            String operatorUid = null;

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
                        String staleStr = parser.getAttributeValue(null, "stale");
                        if (staleStr != null) {
                            staleTime = parseTime(staleStr);
                            // Enforce minimum stale window so contacts don't grey out
                            // between PLI updates from users with long reporting intervals
                            long minStale = System.currentTimeMillis() + MIN_STALE_DURATION_MS;
                            if (staleTime < minStale) {
                                staleTime = minStale;
                            }
                        }
                    } else if ("point".equals(tag)) {
                        lat = parseDouble(parser.getAttributeValue(null, "lat"));
                        lon = parseDouble(parser.getAttributeValue(null, "lon"));
                        alt = parseDouble(parser.getAttributeValue(null, "hae"));
                    } else if ("contact".equals(tag)) {
                        callsign = parser.getAttributeValue(null, "callsign");
                    } else if ("__group".equals(tag)) {
                        team = parser.getAttributeValue(null, "name");
                        role = parser.getAttributeValue(null, "role");
                    } else if ("__video".equals(tag)) {
                        videoUrl = parser.getAttributeValue(null, "url");
                        videoAlias = parser.getAttributeValue(null, "sensor");
                    } else if ("sensor".equals(tag)) {
                        sensorModel = parser.getAttributeValue(null, "model");
                        sensorFov = parseDouble(parser.getAttributeValue(null, "fov"));
                        sensorAzimuth = parseDouble(parser.getAttributeValue(null, "azimuth"));
                        sensorRange = parseDouble(parser.getAttributeValue(null, "range"));
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

            // Detect drone: type contains "-A-" (Air domain, e.g. a-f-A-M-H-Q)
            if (type != null && type.length() >= 5) {
                String[] parts = type.split("-");
                if (parts.length >= 3 && "A".equals(parts[2])) {
                    user.setDrone(true);
                }
            }
            if (videoUrl != null) user.setVideoUrl(videoUrl);
            if (videoAlias != null) user.setVideoAlias(videoAlias);
            if (sensorModel != null) user.setSensorModel(sensorModel);
            if (sensorFov > 0) user.setSensorFov(sensorFov);
            if (sensorAzimuth >= 0) user.setSensorAzimuth(sensorAzimuth);
            if (sensorRange > 0) user.setSensorRange(sensorRange);
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

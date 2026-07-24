package com.taklite.client.tak;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import com.taklite.util.AppLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TakManager implements TakClient.TakClientListener {
    private static final String TAG = "TakManager";
    private static final long STALE_CHECK_INTERVAL_MS = 30000;

    private static TakManager instance;

    private TakClient client;
    private String uid;
    private String callsign;
    // Host + cert material retained from connect(), reused by the HTTPS Mission API client.
    private String serverAddress;
    private String trustStorePath;
    private String trustStorePassword;
    private String clientCertPath;
    private String clientCertPassword;
    private String team;
    private String role;
    /** Channels/groups the user selected on the TAK Setup screen. Empty = server default routing
     *  (whatever the cert's group membership dictates). When set, outbound CoT is directed to
     *  ONLY these channels via <marti><dest group="…"/></marti>. */
    private volatile List<String> channels = new ArrayList<>();
    private boolean connected = false;
    private double lastLat = 0;
    private double lastLon = 0;
    private boolean initialPliSent = false;
    private String activeAlertId;

    private final ConcurrentHashMap<String, TakUser> takUsers = new ConcurrentHashMap<>();
    private final List<TakUserListener> listeners = new ArrayList<>();
    private final List<TakAlertListener> alertListeners = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable staleCheckRunnable = new Runnable() {
        @Override
        public void run() {
            removeStaleUsers();
            mainHandler.postDelayed(this, STALE_CHECK_INTERVAL_MS);
        }
    };

    public interface TakUserListener {
        void onTakUserUpdated(TakUser user);
        void onTakUserRemoved(String uid);
        void onTakConnectionChanged(boolean connected);
    }

    public interface TakAlertListener {
        void onAlertReceived(String senderUid, String senderCallsign, String alertType, double lat, double lon);
        void onAlertCancelled(String senderUid, String senderCallsign);
    }

    private TakManager() {}

    public static synchronized TakManager getInstance() {
        if (instance == null) {
            instance = new TakManager();
        }
        return instance;
    }

    public void addListener(TakUserListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) listeners.add(listener);
        }
    }

    public void removeListener(TakUserListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public void addAlertListener(TakAlertListener listener) {
        synchronized (alertListeners) {
            if (!alertListeners.contains(listener)) alertListeners.add(listener);
        }
    }

    public void removeAlertListener(TakAlertListener listener) {
        synchronized (alertListeners) {
            alertListeners.remove(listener);
        }
    }

    public String getUid() { return uid; }
    public String getCallsign() { return callsign; }
    public String getServerAddress() { return serverAddress; }
    public String getTrustStorePath() { return trustStorePath; }
    public String getTrustStorePassword() { return trustStorePassword; }
    public String getClientCertPath() { return clientCertPath; }
    public String getClientCertPassword() { return clientCertPassword; }

    public TakUser findUserByUid(String uid) {
        return takUsers.get(uid);
    }

    public TakUser findUserByCallsign(String callsign) {
        for (TakUser user : takUsers.values()) {
            if (callsign.equals(user.getCallsign())) return user;
        }
        return null;
    }

    public void connect(String uid, String callsign, String team, String role,
                        String address, int port, String trustStorePath, String trustStorePassword,
                        String clientCertPath, String clientCertPassword) {
        disconnect();
        this.uid = uid;
        this.callsign = callsign;
        this.team = team != null ? team : "Cyan";
        this.role = role != null ? role : "Team Member";
        // Retain host + certs so the HTTPS Mission API client (Data Sync) can reuse them.
        this.serverAddress = address;
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = trustStorePassword;
        this.clientCertPath = clientCertPath;
        this.clientCertPassword = clientCertPassword;
        client = new TakClient(address, port, trustStorePath, trustStorePassword, clientCertPath, clientCertPassword, this);
        client.start();
        mainHandler.postDelayed(staleCheckRunnable, STALE_CHECK_INTERVAL_MS);
    }

    public void disconnect() {
        mainHandler.removeCallbacks(staleCheckRunnable);
        if (client != null) {
            try { client.stopClient(); } catch (Throwable t) { AppLog.w(TAG, "stopClient: " + t.getMessage()); }
            client = null;
        }
        takUsers.clear();
        connected = false;
        initialPliSent = false;
    }

    /** Set the channels/groups outbound CoT should be directed to (from TAK Setup). */
    public void setChannels(List<String> ch) {
        this.channels = (ch != null) ? new ArrayList<>(ch) : new ArrayList<>();
        AppLog.i(TAG, "outbound channels set: " + this.channels);
    }
    public List<String> getChannels() { return new ArrayList<>(channels); }

    /**
     * Send CoT, directing it to the selected channels if any. Injects a
     * {@code <marti><dest group="X" send="true"/>…</marti>} for each selected channel into the
     * event's {@code <detail>}. If the CoT already has a {@code <marti>} (e.g. a mission-scoped
     * marker), the group dests are merged into it instead of adding a second block. With no
     * channels selected, the CoT is sent unchanged (server default routing).
     */
    private void sendCot(String xml) {
        if (client == null || !connected) return;
        client.sendMessage(withChannelDest(xml));
    }

    private String withChannelDest(String xml) {
        List<String> ch = channels;
        if (ch == null || ch.isEmpty() || xml == null) return xml;
        StringBuilder dests = new StringBuilder();
        for (String g : ch) {
            if (g == null || g.isEmpty()) continue;
            dests.append("<dest group=\"").append(escapeXmlAttr(g)).append("\" send=\"true\" />");
        }
        if (dests.length() == 0) return xml;
        int marti = xml.indexOf("<marti>");
        if (marti >= 0) {
            // Merge into the existing <marti> block.
            int insertAt = marti + "<marti>".length();
            return xml.substring(0, insertAt) + dests + xml.substring(insertAt);
        }
        // No <marti> yet — add one just before </detail>.
        int detailEnd = xml.indexOf("</detail>");
        if (detailEnd < 0) return xml;   // malformed; leave as-is
        return xml.substring(0, detailEnd) + "<marti>" + dests + "</marti>" + xml.substring(detailEnd);
    }

    private static String escapeXmlAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public void sendPLI(Location location, String callsign, String team, String role, int battery) {
        if (client != null && connected) {
            lastLat = location.getLatitude();
            lastLon = location.getLongitude();
            String xml = CotBuilder.buildPLI(uid, callsign, team, role,
                    location.getLatitude(), location.getLongitude(), location.getAltitude(),
                    location.getBearing(), location.getSpeed(), battery);
            sendCot(xml);
            AppLog.d(TAG, "PLI sent: " + callsign + " @ " + lastLat + "," + lastLon);
            AppLog.d(TAG, "PLI XML: " + xml);
            if (!initialPliSent) {
                initialPliSent = true;
                AppLog.d(TAG, "First real PLI sent to TAK server");
            }
        }
    }

    /**
     * Send a position report for the AIRCRAFT (drone) as a distinct air track.
     * Independent of the operator's PLI — its own uid/callsign, air-domain CoT type.
     * Safe to call at a high rate (e.g. every 1-2s) while flying.
     */
    public void sendDronePLI(String droneUid, String droneCallsign,
                             double lat, double lon, double hae,
                             double heading, double speed, int battery,
                             String videoUrl, String spiUid,
                             double sensorFov, double sensorVfov, double sensorAzimuth,
                             double sensorElevation, double sensorRange, double northRef,
                             double gimbalRoll, double gimbalPitch, double gimbalYaw,
                             boolean isFlying, int flightTimeSec,
                             int batteryMaxMah, int batteryRemainMah, double voltage) {
        if (client != null && connected) {
            String xml = CotBuilder.buildDronePLI(droneUid, droneCallsign,
                    lat, lon, hae, heading, speed, battery,
                    videoUrl, spiUid,
                    sensorFov, sensorVfov, sensorAzimuth, sensorElevation, sensorRange, northRef,
                    gimbalRoll, gimbalPitch, gimbalYaw,
                    isFlying, flightTimeSec,
                    batteryMaxMah, batteryRemainMah, voltage,
                    this.uid);
            client.sendMessage(xml);
            AppLog.d(TAG, "Drone PLI sent: " + droneCallsign + " @ " + lat + "," + lon
                    + " alt=" + hae + " hdg=" + heading);
        }
    }

    /**
     * Send the camera slant point (sensor point of interest) — the ground point the
     * drone camera is looking at. Stable uid → updates one live marker.
     */
    public void sendCameraPoint(String spiUid, String droneUid, String callsign,
                                double lat, double lon, double rangeM) {
        if (client != null && connected) {
            String xml = CotBuilder.buildSensorPoint(spiUid, droneUid, callsign, lat, lon, rangeM);
            client.sendMessage(xml);
            AppLog.d(TAG, "Camera point sent: " + callsign + " @ " + lat + "," + lon
                    + " range=" + Math.round(rangeM) + "m");
        }
    }

    /** Send the camera footprint polygon (what the camera sees on the ground). */
    public void sendFootprint(String uid, String callsign, double[][] corners) {
        if (client != null && connected && corners != null && corners.length >= 3) {
            String xml = CotBuilder.buildFootprintPolygon(uid, callsign, corners);
            sendCot(xml);
            AppLog.d(TAG, "Footprint sent: " + callsign + " (" + corners.length + " corners)");
        }
    }

    public void sendAlert(Location location, String alertType) {
        if (client == null || !connected) return;
        String xml = CotBuilder.buildAlert(uid, callsign, team, role,
                location.getLatitude(), location.getLongitude(), location.getAltitude(), alertType);
        sendCot(xml);
        int uidStart = xml.indexOf("uid=\"") + 5;
        int uidEnd = xml.indexOf("\"", uidStart);
        activeAlertId = xml.substring(uidStart, uidEnd);
        AppLog.d(TAG, "Alert sent: " + alertType + " id=" + activeAlertId);
    }

    public void cancelAlert() {
        if (client == null || !connected || activeAlertId == null) return;
        String xml = CotBuilder.buildAlertCancel(uid, callsign, activeAlertId);
        sendCot(xml);
        AppLog.d(TAG, "Alert cancelled: " + activeAlertId);
        activeAlertId = null;
    }

    /** Broadcast a marker CoT (server-wide); returns its uid, or null if not connected. */
    public String sendMarker(double lat, double lon, double alt, String affiliation,
                             String name, String remarks) {
        if (client == null || !connected) return null;
        String markerUid = "marker-" + UUID.randomUUID().toString().substring(0, 8);
        String xml = CotBuilder.buildMarker(uid, callsign, markerUid, affiliation, lat, lon, alt,
                name, remarks);
        sendCot(xml);
        AppLog.d(TAG, "Marker sent: " + affiliation + " @ " + lat + "," + lon + " id=" + markerUid);
        return markerUid;
    }

    /** Send a marker scoped to a Data Sync mission/feed only (NOT server-wide) via a
     *  &lt;marti&gt;&lt;dest mission=…/&gt;&lt;/marti&gt; tag. Returns its uid, or null if not connected. */
    public String sendMarkerToMission(double lat, double lon, double alt, String affiliation,
                                      String name, String remarks, String missionName) {
        if (client == null || !connected) return null;
        String markerUid = "marker-" + UUID.randomUUID().toString().substring(0, 8);
        String xml = CotBuilder.buildMarker(uid, callsign, markerUid, affiliation, lat, lon, alt,
                name, remarks, missionName);
        sendCot(xml);
        AppLog.d(TAG, "Marker sent to mission " + missionName + ": " + affiliation + " id=" + markerUid);
        return markerUid;
    }

    public boolean hasActiveAlert() {
        return activeAlertId != null;
    }

    public boolean isConnected() {
        return connected;
    }

    public Collection<TakUser> getTakUsers() {
        return takUsers.values();
    }

    @Override
    public void onCotReceived(String xml) {
        AppLog.d(TAG, "CoT received: " + xml.substring(0, Math.min(xml.length(), 200)));
        processCoT(xml);
    }

    @Override
    public void onConnected() {
        connected = true;
        AppLog.d(TAG, "Connected to TAK server");
        if (uid != null) {
            String cs = callsign != null ? callsign : uid;
            String initCot = CotBuilder.buildPLI(uid, cs, team, role, 0, 0, 0, 0, 0, 100);
            client.sendMessage(initCot);
            AppLog.d(TAG, "Initial PLI sent to register with server");
        }
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (TakUserListener l : listeners) l.onTakConnectionChanged(true);
            }
        });
    }

    @Override
    public void onDisconnected() {
        connected = false;
        AppLog.d(TAG, "Disconnected from TAK server");
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (TakUserListener l : listeners) l.onTakConnectionChanged(false);
            }
        });
    }

    private void processCoT(String xml) {
        // Check for disconnect
        try {
            String disconnectedUid = CotParser.parseDisconnect(xml);
            if (disconnectedUid != null) {
                AppLog.d(TAG, "User disconnected: " + disconnectedUid);
                TakUser user = takUsers.get(disconnectedUid);
                if (user != null) {
                    user.setStaleTime(System.currentTimeMillis() - 1);
                    mainHandler.post(() -> {
                        synchronized (listeners) {
                            for (TakUserListener l : listeners) l.onTakUserUpdated(user);
                        }
                    });
                }
                return;
            }
        } catch (Exception e) {
            // ignore
        }

        // Check for alert
        CotParser.AlertMessage alert = CotParser.parseAlert(xml);
        if (alert != null) {
            String senderUid = alert.linkedUid != null ? alert.linkedUid : "";
            if (senderUid.equals(uid)) return;

            if (alert.isCancellation) {
                TakUser sender = findUserByUid(senderUid);
                String cancelCallsign = alert.senderCallsign != null ? alert.senderCallsign : (sender != null ? sender.getCallsign() : senderUid);
                if (sender != null) {
                    sender.setEmergencyActive(false);
                    sender.setEmergencyType(null);
                }
                mainHandler.post(() -> {
                    synchronized (alertListeners) {
                        for (TakAlertListener l : alertListeners) l.onAlertCancelled(senderUid, cancelCallsign);
                    }
                });
            } else {
                TakUser sender = findUserByUid(senderUid);
                if (sender != null) {
                    sender.setEmergencyActive(true);
                    sender.setEmergencyType(alert.alertType);
                }
                mainHandler.post(() -> {
                    synchronized (alertListeners) {
                        for (TakAlertListener l : alertListeners) {
                            l.onAlertReceived(senderUid,
                                    alert.senderCallsign != null ? alert.senderCallsign : senderUid,
                                    alert.alertType, alert.lat, alert.lon);
                        }
                    }
                });
            }
            return;
        }

        // Parse position (includes drone/video detection)
        TakUser user = CotParser.parse(xml);
        if (user == null || user.getUid().equals(uid)) return;

        if (user.isDrone()) {
            AppLog.d(TAG, "Drone detected: " + user.getCallsign()
                    + (user.getSensorModel() != null ? " (" + user.getSensorModel() + ")" : "")
                    + (user.hasVideo() ? " [video]" : ""));
        }

        takUsers.put(user.getUid(), user);
        mainHandler.post(() -> {
            synchronized (listeners) {
                for (TakUserListener l : listeners) l.onTakUserUpdated(user);
            }
        });
    }

    private void removeStaleUsers() {
        for (String key : takUsers.keySet()) {
            TakUser user = takUsers.get(key);
            if (user != null) {
                if (user.isExpired()) {
                    takUsers.remove(key);
                    mainHandler.post(() -> {
                        synchronized (listeners) {
                            for (TakUserListener l : listeners) l.onTakUserRemoved(key);
                        }
                    });
                } else if (user.isStale()) {
                    mainHandler.post(() -> {
                        synchronized (listeners) {
                            for (TakUserListener l : listeners) l.onTakUserUpdated(user);
                        }
                    });
                }
            }
        }
    }
}

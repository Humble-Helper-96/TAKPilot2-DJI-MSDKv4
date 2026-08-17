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
    private boolean connected = false;
    private double lastLat = 0;
    private double lastLon = 0;
    private boolean initialPliSent = false;
    private String activeAlertId;

    // Client identity for CoT's <takv> block — what a TAK server's "Connected Users" panel shows
    // as this client's type/device/version. Defaults are deliberately generic (never a specific
    // app name): this class is shared with the Autel sibling port, and hardcoding an identity
    // here would mislabel whichever app never calls setClientIdentity(). See that method's doc.
    private String takvPlatform = "TAK Lite";
    private String takvDevice = "Unknown Device";
    private String takvOs = "Android";
    private String takvVersion = "1.0";

    /**
     * Sets the client identity every outbound PLI reports via {@code <takv>}. Call once at app
     * startup — real values here (not this class's placeholder defaults) are what makes a
     * pilot's own entry in a TAK server's connected-users list say the ACTUAL app, not a
     * leftover generic name from the shared core.
     *
     * Deliberately not hardcoded inside this shared class: {@link TakManager}/{@link CotBuilder}
     * are used by more than one sibling app (this DJI port and the separate Autel port), and
     * baking one app's name in here would mislabel the other.
     */
    public void setClientIdentity(String platform, String device, String os, String version) {
        if (platform != null) this.takvPlatform = platform;
        if (device != null) this.takvDevice = device;
        if (os != null) this.takvOs = os;
        if (version != null) this.takvVersion = version;
    }

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
        /**
         * The contact aged out. It may still be held somewhere durable (a saved marker), so a
         * listener may legitimately keep showing it — see TakMapMarkers.
         */
        void onTakUserRemoved(String uid);
        /**
         * The network EXPLICITLY deleted this item (a `t-x-d-d`), as opposed to it merely ageing
         * out. Forget it for good, including any persisted copy.
         *
         * The distinction is the whole point of the persistent-marker work: a timeout must not
         * remove a shared marker, but a real delete must — otherwise a marker the team has
         * retracted lives on this aircraft for ever.
         */
        void onTakUserDeleted(String uid);
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

    /**
     * Puts a marker restored from durable storage back into the live contact map, so views that
     * read {@link #getTakUsers()} can see it.
     *
     * Needed because the AR overlay iterates that collection directly, while the map keeps its own
     * saved-marker store — so a marker restored at map-open was drawn on the map and invisible in
     * AR. It also matters for deletes: a `t-x-d-d` is matched against this map.
     *
     * Does NOT overwrite an existing entry — a live CoT is always better than a saved copy — and
     * does not notify listeners, because the caller is the thing that restored it.
     */
    public void restorePersistentUser(TakUser user) {
        if (user == null || user.getUid() == null) return;
        takUsers.putIfAbsent(user.getUid(), user);
    }

    /**
     * Drops a contact from the live map WITHOUT telling listeners.
     *
     * For a caller that is already removing the thing itself — the marker list's Clear All, which
     * strips the overlay and the saved copy directly. Notifying would send it back through
     * onTakUserRemoved and re-add the marker from the store it is in the middle of clearing.
     */
    public void forgetUser(String uid) {
        if (uid != null) takUsers.remove(uid);
    }

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


    /**
     * CHANNEL SELECTION IS REMOVED (operator, 2026-08-15), and this note is why.
     *
     * This class used to hold the channels a pilot picked on TAK Setup and inject
     * {@code <marti><dest group="…" send="true"/></marti>} into every CoT that went through
     * {@link #sendCot}. IT SILENTLY DESTROYED MARKERS. The server would not route them and
     * simply dropped them; with no channel selected they arrived at once. Proved on the Autel
     * sibling's controller 2026-08-15 by sending one marker each way and reading the bytes.
     *
     * It would have done the same to an alert — {@link #sendAlert} takes the same path — but
     * nothing in this application calls sendAlert, so no alert was lost. That method and its
     * listener are unreachable code carried by this shared core.
     *
     * It was also never applied evenly: the drone PLI and the camera point call
     * {@link TakClient#sendMessage} directly and ignored the selection entirely, so a pilot who
     * picked channels to LIMIT who saw this aircraft still broadcast its position to everyone.
     * The feature failed in both directions, and had done since it was written.
     *
     * Routing is now the certificate's group membership, which is what the server does with no
     * marti block and what every working message here already relied on.
     *
     * DO NOT RE-ADD {@code <dest group>} WITHOUT TESTING A MARKER END TO END on a real server.
     * What a TAK Server accepts for client-chosen channel routing is an open question — that is
     * the work this removal defers, not a detail to guess at.
     */
    /**
     * Send CoT, directing it to the selected channels if any. Injects a
     * {@code <marti><dest group="X" send="true"/>…</marti>} for each selected channel into the
     * event's {@code <detail>}. If the CoT already has a {@code <marti>} (e.g. a mission-scoped
     * marker), the group dests are merged into it instead of adding a second block. With no
     * channels selected, the CoT is sent unchanged (server default routing).
     */
    private void sendCot(String xml) {
        if (client == null || !connected) return;
        client.sendMessage(xml);
    }

    /**
     * The {@code <takv device="...">} value: hardware model plus the CURRENT callsign, so a
     * teammate can tell two otherwise-identical devices apart in the connected-users list
     * without opening each track. Built fresh at send time (not cached alongside
     * {@link #setClientIdentity}) specifically so it can never go stale if the pilot edits their
     * callsign mid-session — {@link #setClientIdentity} is a one-time startup call, callsign is
     * not.
     */
    private String deviceWithCallsign(String cs) {
        if (cs == null || cs.isEmpty()) return takvDevice;
        return takvDevice + " (" + cs + ")";
    }

    /**
     * The PILOT's marker: the operator on the ground, at the controller's own position.
     *
     * ⚠ **The XML is NOT logged here, unlike the debug line below it used to do.** This message
     * now carries the video url, and that url contains the media-server password. Logging it
     * would write a credential to the log file and to logcat, which the security review of
     * 2026-08-03 explicitly recorded this application as not doing.
     *
     * <p>No {@code team} parameter on purpose: the pilot marker is always {@link #PILOT_TEAM}.
     * The signature used to take one and silently ignore it, which promised callers a choice
     * the method never offered.
     */
    public void sendPilotPLI(Location location, String callsign, String role,
                             int battery, String videoUrl) {
        if (client != null && connected) {
            lastLat = location.getLatitude();
            lastLon = location.getLongitude();
            String xml = CotBuilder.buildPLI(uid, pilotCallsign(callsign), PILOT_TEAM, role,
                    location.getLatitude(), location.getLongitude(), location.getAltitude(),
                    location.getBearing(), location.getSpeed(), battery,
                    takvPlatform, deviceWithCallsign(callsign), takvOs, takvVersion, videoUrl);
            sendCot(xml);
            AppLog.d(TAG, "Pilot PLI sent: " + pilotCallsign(callsign) + " @ " + lastLat + ","
                    + lastLon + (videoUrl != null && !videoUrl.isEmpty() ? " (+video)" : ""));
        }
    }

    /**
     * The operator marker's callsign — the aircraft callsign with "-Pilot" (operator, 2026-08-05).
     *
     * Without it the operator marker and the aircraft marker share a name, and a viewer sees the
     * same callsign twice at two positions with no way to tell which is the aircraft.
     */
    public static String pilotCallsign(String callsign) {
        if (callsign == null || callsign.isEmpty()) return "Pilot";
        return callsign.endsWith("-Pilot") ? callsign : callsign + "-Pilot";
    }

    /** The pilot marker is always Cyan (operator, 2026-08-05), whatever team the configuration
     *  uses, so the operator is one consistent colour across every aircraft. */
    private static final String PILOT_TEAM = "Cyan";

    public void sendPLI(Location location, String callsign, String team, String role, int battery) {
        if (client != null && connected) {
            lastLat = location.getLatitude();
            lastLon = location.getLongitude();
            String xml = CotBuilder.buildPLI(uid, callsign, team, role,
                    location.getLatitude(), location.getLongitude(), location.getAltitude(),
                    location.getBearing(), location.getSpeed(), battery,
                    takvPlatform, deviceWithCallsign(callsign), takvOs, takvVersion);
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
                    + " alt=" + hae + " hdg=" + heading
                    + (videoUrl != null && !videoUrl.isEmpty() ? " (+video)" : ""));
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

    /** Mint a marker uid. Only for a marker's FIRST send — reuse it afterwards, see below. */
    public static String newMarkerUid() {
        return "marker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Broadcast a marker CoT (server-wide) under a NEW uid; returns it, or null if not
     *  connected. Use {@link #sendMarkerWithUid} to update a marker that already exists. */
    public String sendMarker(double lat, double lon, double alt, String affiliation,
                             String name, String remarks) {
        return sendMarkerWithUid(newMarkerUid(), lat, lon, alt, affiliation, name, remarks, null);
    }

    /** Send a marker scoped to a Data Sync mission/feed only (NOT server-wide) via a
     *  &lt;marti&gt;&lt;dest mission=…/&gt;&lt;/marti&gt; tag. Returns its uid, or null if not connected. */
    public String sendMarkerToMission(double lat, double lon, double alt, String affiliation,
                                      String name, String remarks, String missionName) {
        return sendMarkerWithUid(newMarkerUid(), lat, lon, alt, affiliation, name, remarks, missionName);
    }

    /**
     * Send a marker CoT under a CALLER-SUPPLIED uid — the one call that can MOVE or otherwise
     * update an existing marker rather than spawning a new one.
     *
     * In CoT the uid <em>is</em> the marker's identity: re-send the same uid with new lat/lon
     * and fresh time/start/stale, and every ATAK/iTAK/TAKAware client moves the existing
     * marker in place. Send a fresh uid instead and they all draw a second marker. So a caller
     * that owns a persistent pin must store the uid from its first send and pass it back here
     * for every subsequent move/rename/retype/re-send.
     *
     * @param missionName Data Sync feed to scope to, or null to broadcast server-wide.
     * @return the uid that was sent, or null if not connected.
     */
    public String sendMarkerWithUid(String markerUid, double lat, double lon, double alt,
                                    String affiliation, String name, String remarks,
                                    String missionName) {
        if (client == null || !connected) return null;
        String xml = CotBuilder.buildMarker(uid, callsign, markerUid, affiliation, lat, lon, alt,
                name, remarks, missionName);
        sendCot(xml);
        AppLog.d(TAG, "Marker sent" + (missionName != null ? " to mission " + missionName : "")
                + ": " + affiliation + " @ " + lat + "," + lon + " id=" + markerUid);
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
            // Registration message so the server lists this client and applies channel routing.
            //
            // ⚠ IT CARRIES 0,0 BECAUSE THERE IS NO FIX YET, AND THAT IS WHY IT MUST BE REPLACED.
            // Until 2026-08-05 nothing replaced it: sendPLI had no caller anywhere, so the
            // operator's callsign sat at latitude 0 longitude 0 on the team's map until it went
            // stale. The bridge now publishes a real pilot position every tick once the
            // controller has a fix. If it never gets one, this message simply goes stale and the
            // marker disappears — which is the right outcome, because a marker at 0,0 is worse
            // than no marker.
            String initCot = CotBuilder.buildPLI(uid, pilotCallsign(cs), PILOT_TEAM, role,
                    0, 0, 0, 0, 0, 100,
                    takvPlatform, deviceWithCallsign(cs), takvOs, takvVersion);
            client.sendMessage(initCot);
            AppLog.d(TAG, "Initial PLI sent to register with server (position follows)");
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
                if (user != null && !user.isPersistent()) {
                    // A client went away. Backdate its stale time and let removeStaleUsers()
                    // collect it, so it greys before it disappears.
                    user.setStaleTime(System.currentTimeMillis() - 1);
                    mainHandler.post(() -> {
                        synchronized (listeners) {
                            for (TakUserListener l : listeners) l.onTakUserUpdated(user);
                        }
                    });
                } else {
                    // A `t-x-d-d` is also TAK's DELETE for a map item. Persistent items are exempt
                    // from the stale sweep, so backdating would never remove them — delete here.
                    //
                    // Fired even when the uid is NOT in takUsers: a marker restored from the saved
                    // store may not be in memory as a contact, and the team deleting it must still
                    // clear the persisted copy. Listeners treat this as "forget it for good".
                    takUsers.remove(disconnectedUid);
                    mainHandler.post(() -> {
                        synchronized (listeners) {
                            for (TakUserListener l : listeners) l.onTakUserDeleted(disconnectedUid);
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

    /**
     * Drops contacts whose stale window has passed.
     *
     * ⚠ PERSISTENT ITEMS ARE EXEMPT FROM DELETION. A marker somebody shared is not a track: it does
     * not re-broadcast on a heartbeat, and a sender may declare a stale window that is useless
     * (CloudTAK sends ~3.6 s). Deleting on a timeout made shared markers vanish within minutes of
     * arriving. They still go stale — the icon greys — but only an explicit delete, a local delete
     * or the 72-hour eviction in TakMapMarkers removes them.
     *
     * ⚠ THIS EXEMPTION MUST NEVER REACH AIR TRACKS. Unbounded contact retention is what OOM-killed
     * the flight app in the air on 2026-08-03. {@link CotParser#isPersistentType} is where that is
     * enforced; read it before widening what counts as persistent.
     */
    private void removeStaleUsers() {
        // RETENTION WATCHDOG. This count is the number that mattered on 2026-08-03: the map held
        // 161 distinct aircraft while the live picture showed a handful, and the process was
        // OOM-killed in the air. It is cheap (one line per 30 s sweep) and it is the only thing
        // that makes a slow retention leak visible before it becomes a crash.
        //
        // What to look for: `total` should oscillate around the size of the real picture, not
        // climb steadily over a session. `persistent` should track the number of markers actually
        // shared with this aircraft — if it grows with air traffic, the persistence rule has
        // sprung a leak. See CotParser.isPersistentType.
        int persistentCount = 0;
        for (TakUser u : takUsers.values()) if (u != null && u.isPersistent()) persistentCount++;
        AppLog.i(TAG, "contacts held: " + takUsers.size() + " total, "
                + persistentCount + " persistent");

        for (String key : takUsers.keySet()) {
            TakUser user = takUsers.get(key);
            if (user != null) {
                if (user.isPersistent()) {
                    // Still notify while stale so the icon can grey — just never remove.
                    if (user.isStale()) {
                        mainHandler.post(() -> {
                            synchronized (listeners) {
                                for (TakUserListener l : listeners) l.onTakUserUpdated(user);
                            }
                        });
                    }
                } else if (user.isExpired()) {
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

package com.dji.sdk.sample.tak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import com.dji.sdk.sample.R
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAllowOverlap
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconIgnorePlacement
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconImage
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconSize
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakUser
import com.taklite.util.AppLog

/**
 * Draws inbound TAK CoT contacts/markers on the flight mini-map. Third port of TAKPilot2's
 * TakMapMarkers (DJI uxsdk mapkit -> osmdroid -> MapLibre); the Autel/osmdroid version is the
 * direct reference.
 *
 * The structural difference from both references: osmdroid and mapkit hand out one `Marker`
 * object per contact, pushed into an overlay list. MapLibre has no such thing — everything on
 * screen is one [GeoJsonSource] holding a [FeatureCollection] plus one [SymbolLayer], with the
 * per-contact bitmaps registered into the style as named images ([Style.addImage]) that each
 * Feature references by name. So [shown] is the model, and [rebuild] re-serializes the whole
 * collection whenever anything changes. That's cheap at TAK-picture scale (tens of contacts)
 * and avoids any incremental-diff bookkeeping.
 *
 * Icon generation, the `iconKeyFor` cache scheme, 2525 type parsing, and persistence of
 * received markers are ports of the Autel logic and behave identically.
 */
object TakMapMarkers {
    private const val TAG = "TakMapMarkers"

    const val SOURCE_ID = "tak-markers-source"
    const val LAYER_ID = "tak-markers-layer"

    private val main = Handler(Looper.getMainLooper())

    private var style: Style? = null
    private var source: GeoJsonSource? = null

    /** uid -> the contact as last rendered. The model behind the FeatureCollection. */
    private val shown = LinkedHashMap<String, TakUser>()

    /** uid -> the icon cache key its current bitmap was built from (see [iconKeyFor]). */
    private val iconKeys = HashMap<String, String>()

    /** Icon cache key -> the style-image name it was registered under. */
    private val registeredImages = HashMap<String, String>()

    private val hidden = HashSet<String>()
    private var listenerRegistered = false
    private var appContext: Context? = null

    // Received MARKERS we persist so they survive restarts. PLI contacts are NOT persisted — they
    // re-broadcast live and would otherwise ghost. Air tracks are never persisted either; see
    // CotParser.isPersistentType for why that one is a safety guard. Keyed by uid.
    private val savedMarkers = LinkedHashMap<String, SavedMarker>()

    private data class SavedMarker(
        val uid: String, val lat: Double, val lon: Double, val alt: Double,
        val type: String, val callsign: String, val team: String,
        /** When this marker was last heard from. Drives [MARKER_RETENTION_MS] eviction. */
        val lastSeen: Long,
    )

    /**
     * How long a shared marker is kept with no update, before it is evicted from the store.
     *
     * A marker anyone still cares about is being re-broadcast, so it never ages out; only genuinely
     * abandoned ones drop. The bound exists because unbounded contact retention is what OOM-killed
     * the Autel sibling in the air on 2026-08-03, and this store has NO cap, NO age field and NO
     * eviction — it only ever grows. Same store, same defect, same aircraft-in-the-air outcome.
     */
    private const val MARKER_RETENTION_MS = 72L * 60 * 60 * 1000

    /**
     * Hard ceiling on stored markers, independent of age. [savedMarkers] is insertion-ordered, so
     * eviction is oldest-first. A second bound on top of the age limit, because a busy net could
     * in principle deliver more markers inside 72 hours than is sensible to hold.
     */
    private const val MAX_SAVED_MARKERS = 1000

    /**
     * Store schema version. Entries carrying it were saved after the persistence rule became
     * "the sender set `archived`"; entries without it were saved by an earlier build whose gate
     * was the 2525 icon lookup, which also accepted platforms and live clients.
     */
    private const val SCHEMA_ARCHIVED_VERIFIED = 1

    /**
     * Conservative re-validation for an entry saved BEFORE `archived` was checked.
     *
     * The old gate accepted anything `milMarkerRes` drew a frame for, which pulled in ADS-B ground
     * vehicles (`a-f-G-E-V`, uid `ICAO-…`) and CloudTAK's own users (`a-f-G-E-V-C`). Left alone
     * those would be restored as permanent, which is the failure this whole change exists to
     * prevent — the old file would make the new rule pointless.
     *
     * A placed marker is a BARE affiliation-plus-domain type: `a-{f,h,n,u}-G` with nothing after
     * it. The trailing qualifiers on `…-G-E-V` say equipment/vehicle — a platform, not a point.
     */
    private fun isLegacyPlacedMarker(type: String?): Boolean {
        if (type == null) return false
        if (type.startsWith("b-m-p-s-p-")) return false          // SPI, never a placed marker
        if (type.startsWith("b-m-p-")) return true
        val parts = type.split("-")
        if (parts.size != 3 || parts[0] != "a" || parts[2] != "G") return false
        return parts[1] in setOf("f", "h", "n", "u")
    }

    /**
     * Evicts markers unseen for [MARKER_RETENTION_MS], then trims the store to
     * [MAX_SAVED_MARKERS] oldest-first.
     */
    private fun evictOldMarkers() {
        val cutoff = System.currentTimeMillis() - MARKER_RETENTION_MS
        val aged = savedMarkers.entries.filter { it.value.lastSeen < cutoff }.map { it.key }
        for (uid in aged) savedMarkers.remove(uid)
        while (savedMarkers.size > MAX_SAVED_MARKERS) {
            val oldest = savedMarkers.keys.firstOrNull() ?: break
            savedMarkers.remove(oldest)
        }
        if (aged.isNotEmpty()) AppLog.i(TAG, "evicted ${aged.size} marker(s) unseen for 72h")
    }

    /** Call once at app start so inbound contacts accumulate before the flight screen opens. */
    fun install(context: Context) {
        appContext = context.applicationContext
        loadSavedMarkers()
        registerListener()
        AppLog.v(TAG, "installed (${savedMarkers.size} saved markers, ${hidden.size} hidden)")
    }

    /**
     * Called from the flight activity's `setStyle` callback. Adds our source + layer; call this
     * BEFORE the aircraft/home layers are added so inbound markers render underneath them
     * (MapLibre draws layers in the order they were added).
     */
    fun onMapReady(readyStyle: Style) {
        style = readyStyle
        // A style is a fresh canvas — nothing we registered against the previous one survives.
        iconKeys.clear()
        registeredImages.clear()
        try {
            val src = GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList()))
            readyStyle.addSource(src)
            source = src
            readyStyle.addLayer(
                SymbolLayer(LAYER_ID, SOURCE_ID).withProperties(
                    // Data-driven: each Feature names its own style image, so one layer covers
                    // every contact regardless of team color / 2525 frame / stale state.
                    iconImage(Expression.get(PROP_ICON)),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                )
            )
            resyncExisting()
        } catch (e: Exception) {
            AppLog.w(TAG, "onMapReady failed: ${e.message}")
        }
        // Our own dropped pins layer goes directly on top of inbound markers (and still below
        // the aircraft/home layers the flight activity adds after this returns).
        TakDropMarkers.onMapReady(readyStyle)
    }

    /** Called from the flight activity's onDestroy — the style and its images are gone. */
    fun onMapDestroyed() {
        style = null
        source = null
        iconKeys.clear()
        registeredImages.clear()
        shown.clear()
        TakDropMarkers.onMapDestroyed()
    }

    /**
     * Cheap per-tick check for rendering changes that no inbound CoT will announce — namely a
     * contact going stale (grey). [TakManager] only notifies on received traffic, so without
     * this a contact that simply stopped transmitting would keep its live color forever.
     * Rebuilds only when an icon key actually changed. Safe to call from the 500ms HUD tick.
     */
    fun tick() {
        if (source == null) return
        var changed = false
        for (user in shown.values) {
            if (iconKeys[user.uid] != iconKeyFor(user)) { changed = true; break }
        }
        if (changed) rebuild()
    }

    private fun registerListener() {
        if (listenerRegistered) return
        listenerRegistered = true
        // TakManager already dispatches these on the main thread (mainHandler.post in
        // processCoT), but upsert/remove marshal anyway — MapLibre style/source mutation off
        // the main thread is a hard crash, not a race we'd get to debug comfortably.
        TakManager.getInstance().addListener(object : TakManager.TakUserListener {
            override fun onTakUserUpdated(user: TakUser) = upsert(user)
            override fun onTakUserRemoved(uid: String) = onContactAgedOut(uid)
            override fun onTakUserDeleted(uid: String) = forget(uid)
            override fun onTakConnectionChanged(connected: Boolean) {}
        })
    }

    private fun SavedMarker.toUser(): TakUser =
        TakUser(uid, callsign, lat, lon, alt, team, "", Long.MAX_VALUE).also {
            it.type = type
            // Marks it exempt from the stale sweep, exactly as the parser would have. Without
            // this a restored marker would be swept ~10 minutes after the map opened.
            it.isPersistent = true
        }

    /**
     * A contact aged out of [TakManager]'s live map.
     *
     * If we hold a SAVED copy, the marker stays: it aged out of the live contact list, but it was
     * never a track — somebody shared it deliberately, and the only things that should remove it
     * are an explicit delete, a local delete, or the 72-hour eviction.
     *
     * The old code called [remove] unconditionally, so a marker still sitting in the saved store
     * was stripped off the map anyway, and did not come back until the flight screen was reopened.
     */
    private fun onContactAgedOut(uid: String) {
        val saved = savedMarkers[uid]
        if (saved != null && !hidden.contains(uid)) {
            upsert(saved.toUser())
        } else {
            remove(uid)
        }
    }

    /**
     * The network explicitly deleted this item. Forget it everywhere, including the saved store —
     * otherwise a marker the team retracted would come back at the next restart.
     */
    private fun forget(uid: String) {
        AppLog.v(TAG, "inbound marker deleted by the network: $uid")
        remove(uid)
        if (savedMarkers.remove(uid) != null) saveSavedMarkers()
    }

    /** True only while [resyncExisting] replays the saved set — see the guard in [upsert]. */
    private var restoring = false

    private fun resyncExisting() {
        shown.clear()
        restoring = true
        try {
            for (s in savedMarkers.values) {
                if (hidden.contains(s.uid)) continue
                val u = s.toUser()
                stage(u)
                // Put it back in the live contact map too. The AR overlay iterates that collection
                // directly while the map keeps this store, so without this a restored marker was
                // drawn on the map and invisible in AR. It also matters for deletes: a `t-x-d-d`
                // is matched against that map.
                TakManager.getInstance().restorePersistentUser(u)
            }
            for (user in TakManager.getInstance().takUsers) stage(user)
            rebuild()
            AppLog.v(TAG, "resync: ${shown.size} markers on map")
        } catch (e: Exception) {
            AppLog.w(TAG, "resync failed: ${e.message}")
        } finally {
            restoring = false
        }
    }

    private fun upsert(user: TakUser) {
        main.post {
            if (stage(user)) rebuild()
            persistIfMarker(user)
        }
    }

    /** Add/update a contact in the model. Returns true if the map needs redrawing. */
    private fun stage(user: TakUser): Boolean {
        if (user.lat == 0.0 && user.lon == 0.0) return false
        if (hidden.contains(user.uid)) return false
        // A marker we currently own is already drawn by TakDropMarkers; the server echoing it
        // back must not draw a second copy. Note "currently" — once the pilot deletes the pin
        // we no longer own the uid, and a later echo is then allowed to land here as an
        // ordinary inbound marker. That reappearance is intended (operator decision), which is
        // why there's no suppression set for deleted uids.
        if (TakDropMarkers.ownsUid(user.uid)) return false
        if (shown.put(user.uid, user) == null) {
            AppLog.v(TAG, "new inbound marker: ${user.uid} (${user.callsign}) type=${user.type}")
        }
        return true
    }

    /**
     * Persists what the PARSER judged persistent, not a second opinion about the icon.
     *
     * This used to gate on `milMarkerRes(type) != null` — the 2525 frame lookup. That is the wrong
     * question twice over. It accepted anything the app could draw a frame for, which on the Autel
     * sibling meant ADS-B ground vehicles and CloudTAK's own users: 152 of 155 stored entries were
     * immortal, including every user who had ever connected. And it REJECTED `b-m-p-*` map points
     * from ATAK/iTAK, which have no 2525 frame — those were drawn but never saved, and vanished on
     * restart. CotParser.isPersistentType is the single decision now.
     */
    private fun persistIfMarker(user: TakUser) {
        if (!user.isPersistent || hidden.contains(user.uid)) return
        if (TakDropMarkers.ownsUid(user.uid)) return
        // Only a LIVE event refreshes the store. A restore from disk must not: `restoring` is set
        // while resyncExisting replays the saved set, and without that guard every trip through
        // the flight screen would stamp lastSeen = now and the 72-hour eviction could never fire —
        // a marker would live for ever as long as the app kept being opened.
        if (restoring) return
        // Re-put so insertion order tracks recency: LinkedHashMap keeps the ORIGINAL position on a
        // plain overwrite, which would make the count-cap evict the most recently refreshed marker
        // instead of the stalest.
        savedMarkers.remove(user.uid)
        savedMarkers[user.uid] = SavedMarker(
            user.uid, user.lat, user.lon, user.alt,
            user.type ?: "", user.callsign ?: user.uid, user.team ?: "Cyan",
            System.currentTimeMillis())
        evictOldMarkers()
        saveSavedMarkers()
    }

    private fun remove(uid: String) {
        main.post {
            if (shown.remove(uid) != null) rebuild()
            iconKeys.remove(uid)
        }
    }

    /** For dedupe / AR checks: is this uid locally hidden? */
    fun isHidden(uid: String): Boolean = hidden.contains(uid)

    /** The inbound contact currently rendered under this uid, or null (used by the 6C list
     *  panel to label an inbound marker before hiding it). */
    fun inboundUser(uid: String): TakUser? = shown[uid]

    /** 6C local-hide: dismiss an inbound contact from this map only — it stays on the server
     *  and reappears if the pilot un-hides it or the app data is cleared. Port of Autel's
     *  hideInbound. This is the path for dismissing a marker that came back after a
     *  TakDropMarkers.delete(), or any other client's marker the pilot wants off their picture. */
    fun hideInbound(uid: String) {
        AppLog.v(TAG, "inbound marker hidden locally: $uid")
        hidden.add(uid)
        savedMarkers.remove(uid)
        saveSavedMarkers()
        main.post {
            if (shown.remove(uid) != null) rebuild()
            iconKeys.remove(uid)
        }
    }

    /**
     * Re-serialize the whole FeatureCollection and push it to the source, registering any icon
     * bitmap the style doesn't have yet. Main thread only.
     */
    private fun rebuild() {
        val st = style ?: return
        val src = source ?: return
        try {
            val features = ArrayList<Feature>(shown.size)
            for (user in shown.values) {
                val key = iconKeyFor(user)
                val imageId = registeredImages[key] ?: run {
                    // Style image names must be stable strings; the raw key contains a
                    // user-supplied callsign, so hash it rather than trusting the characters.
                    val id = "tak-mk-${key.hashCode()}"
                    st.addImage(id, iconBitmapFor(user))
                    registeredImages[key] = id
                    id
                }
                iconKeys[user.uid] = key
                features.add(
                    Feature.fromGeometry(Point.fromLngLat(user.lon, user.lat)).apply {
                        addStringProperty(PROP_UID, user.uid)
                        addStringProperty(PROP_CALLSIGN, user.callsign ?: user.uid)
                        addStringProperty(PROP_ICON, imageId)
                    }
                )
            }
            src.setGeoJson(FeatureCollection.fromFeatures(features))
        } catch (e: Exception) {
            AppLog.w(TAG, "rebuild failed: ${e.message}")
        }
    }

    // ---- Persistence of received 2525 markers (+ locally-hidden uids) across restarts ----
    private const val PREFS = "takpilot2_recv_markers"

    private fun saveSavedMarkers() {
        val ctx = appContext ?: return
        try {
            val arr = org.json.JSONArray()
            for (s in savedMarkers.values) {
                arr.put(org.json.JSONObject().apply {
                    put("uid", s.uid); put("lat", s.lat); put("lon", s.lon); put("alt", s.alt)
                    put("type", s.type); put("cs", s.callsign); put("team", s.team)
                    put("seen", s.lastSeen)
                    // Records that this entry was saved under the rule "the sender set archived on
                    // the wire". Entries without it predate that rule — see loadSavedMarkers.
                    put("v", SCHEMA_ARCHIVED_VERIFIED)
                })
            }
            val hid = org.json.JSONArray().apply { hidden.forEach { put(it) } }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("markers", arr.toString())
                .putString("hidden", hid.toString())
                .apply()
        } catch (e: Exception) { AppLog.w(TAG, "saveSavedMarkers failed: ${e.message}") }
    }

    private fun loadSavedMarkers() {
        val ctx = appContext ?: return
        try {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("hidden", null)?.let {
                val h = org.json.JSONArray(it)
                for (i in 0 until h.length()) hidden.add(h.getString(i))
            }
            p.getString("markers", null)?.let {
                val arr = org.json.JSONArray(it)
                savedMarkers.clear()
                val onDisk = arr.length()
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val uid = o.getString("uid")
                    if (hidden.contains(uid)) continue
                    val type = o.getString("type")
                    // An entry written by an older build was gated on the 2525 icon lookup, which
                    // also accepted ADS-B platforms and live TAK clients. Restoring those as
                    // permanent would make the new rule pointless, so re-validate them on the way
                    // in. Entries carrying the schema version were already checked against
                    // `archived` when they were stored and are trusted as-is.
                    if (o.optInt("v", 0) < SCHEMA_ARCHIVED_VERIFIED && !isLegacyPlacedMarker(type)) {
                        continue
                    }
                    savedMarkers[uid] = SavedMarker(uid, o.getDouble("lat"), o.getDouble("lon"),
                        o.optDouble("alt", 0.0), type,
                        o.optString("cs", uid), o.optString("team", "Cyan"),
                        // No timestamp means an entry from before ageing existed. Treat it as seen
                        // now rather than as 1970, so the 72-hour clock starts here instead of
                        // evicting the whole legacy store on first load.
                        o.optLong("seen", now))
                }
                evictOldMarkers()
                // Rewrite when the load changed anything (a legacy entry rejected, an eviction, a
                // hidden uid). Otherwise the file keeps carrying entries that are ignored on every
                // load, and the same work is redone for ever.
                if (savedMarkers.size != onDisk) {
                    AppLog.i(TAG, "saved markers: $onDisk on disk -> ${savedMarkers.size} kept")
                    saveSavedMarkers()
                }
            }
        } catch (e: Exception) { AppLog.w(TAG, "loadSavedMarkers failed: ${e.message}") }
    }

    // ---- Icon resolution — matches taklite's createTakMarkerIcon exactly ----

    /** Feature property carrying the CoT uid — public so the Activity's map-click hit-test
     *  (6C inbound local-hide) can read it off queryRenderedFeatures results. */
    const val PROP_UID = "uid"
    private const val PROP_CALLSIGN = "callsign"
    private const val PROP_ICON = "icon"

    private val density get() = (appContext?.resources?.displayMetrics?.density ?: 2.5f)

    private fun iconKeyFor(user: TakUser): String {
        // Air tracks key on course alone. The symbol carries no callsign, team colour or stale
        // treatment, so every aircraft at the same course bucket IS the same bitmap — one cache
        // entry per bucket rather than one per aircraft. That matters here: near busy airspace
        // the contact list runs to a hundred aircraft, and one bitmap each would be the same
        // unbounded growth this file's retention work exists to stop.
        if (isAirTrack(user.type)) {
            return if (user.hasCourse()) "air|${courseBucket(user.course)}" else "air|nocourse"
        }
        val team = (user.team ?: "Cyan").lowercase()
        val stale = if (user.isStale) "S" else "A"
        val drone = if (user.isDrone) "D" else "U"
        val mil = milMarkerRes(user.type) ?: 0
        return "$team|$stale|$drone|$mil|${user.callsign}"
    }

    /**
     * An inbound contact that is airborne — the CoT type's third field is `A` (air) rather than
     * `G` (ground), e.g. `a-f-A-C-F` from an ADS-B gateway.
     *
     * Same discriminator [ArSettings.categoryFor] uses for the AR overlay's Air Traffic layer, so
     * a contact drawn as an aircraft on the map is the same set the overlay calls air traffic. If
     * one of these changes, change both.
     */
    fun isAirTrack(type: String?): Boolean {
        val parts = type?.split("-").orEmpty()
        return parts.size >= 3 && parts[0] == "a" && parts[2] == "A"
    }

    /**
     * Course rounded to [COURSE_BUCKET_DEG], which is what the icon cache keys on.
     *
     * Keying on the raw course would mint a fresh bitmap on every position report, because ADS-B
     * course jitters by fractions of a degree — an unbounded cache of near-identical bitmaps,
     * rebuilt on the HUD tick. 15° is finer than the eye reads off a 24dp symbol.
     */
    private fun courseBucket(course: Double): Int =
        (Math.round(course / COURSE_BUCKET_DEG) * COURSE_BUCKET_DEG).toInt() % 360

    private const val COURSE_BUCKET_DEG = 15

    /**
     * MIL-STD-2525 affiliation MARKERS (a-{f,h,n,u}-G, NOT the …-G-U-… unit/PLI form) →
     * frame drawable. Null for PLI/units/drones (those keep the team-colored dot).
     *
     * Public because [com.dji.sdk.sample.takpilot2.ArOverlayView] classifies the same contacts
     * for the FPV overlay. Shared deliberately rather than copied — the V5 reference duplicates
     * this and [teamColor] into its AR view, which is how a map and an overlay end up
     * disagreeing about what a contact is.
     */
    fun milMarkerRes(type: String?): Int? {
        if (type == null) return null
        val parts = type.split("-")
        if (parts.size < 3 || parts[0] != "a" || parts[2] != "G") return null
        // EXACTLY three segments. A placed affiliation marker is bare `a-{f,h,n,u}-G` — that's
        // what this app drops and what ATAK's generic markers use. Anything with a further
        // segment is a typed entity reporting itself, and belongs on the team-dot path:
        //   a-f-G-U-C    iTAK/ATAK person      (Unit)
        //   a-f-G-E-V-C  CloudTAK console      (Equipment/Vehicle)
        // The previous rule only excluded `-U-`, so CloudTAK users — which self-report as
        // equipment, not units — rendered as generic 2525 rectangles instead of their team
        // colour. Testing the segment COUNT rather than enumerating known suffixes avoids
        // rediscovering this for every client that picks a different entity type.
        if (parts.size != 3) return null
        return when (parts[1]) {
            "f" -> R.drawable.marker_friendly
            "h" -> R.drawable.marker_hostile
            "n" -> R.drawable.marker_neutral
            "u" -> R.drawable.marker_unknown
            else -> null
        }
    }

    /**
     * TAK team-name → colour, identical to taklite's getTeamColor().
     *
     * ⚠ DELIBERATELY NOT TOKENISED, and this is not an oversight. These are the TAK PROTOCOL's
     * team colours — the same fifteen names every TAK client renders, and what a teammate picked
     * in their own client. They are not this app's palette and must be free to diverge from it.
     * A few happen to share a hex with a tp_* token today (team "red" and tp_state_danger are
     * both #F44336); binding them would mean a later UI tweak silently repainting somebody's
     * team. Keep these literal and keep them matching taklite.
     */
    fun teamColor(team: String?): Int {
        if (team == null) return Color.GREEN
        return when (team.lowercase()) {
            "cyan" -> Color.parseColor("#00BCD4")
            "red" -> Color.parseColor("#F44336")
            "blue" -> Color.parseColor("#2196F3")
            "green" -> Color.parseColor("#4CAF50")
            "yellow" -> Color.parseColor("#FFEB3B")
            "white" -> Color.WHITE
            "orange" -> Color.parseColor("#FF9800")
            "magenta" -> Color.parseColor("#E91E63")
            "maroon" -> Color.parseColor("#880E4F")
            "purple" -> Color.parseColor("#9C27B0")
            "dark green" -> Color.parseColor("#2E7D32")
            "teal" -> Color.parseColor("#009688")
            "dark blue" -> Color.parseColor("#1565C0")
            "brown" -> Color.parseColor("#795548")
            else -> Color.GREEN
        }
    }

    private fun iconBitmapFor(user: TakUser): Bitmap {
        // Checked BEFORE milMarkerRes so an air track can never fall through to the plain team
        // dot, which is what made ADS-B traffic indistinguishable from a TAK client.
        if (isAirTrack(user.type)) {
            // Already symmetric about its own centre — no label to offset, so it skips
            // centerOnSymbol, which would be a no-op anyway.
            return makeAirIcon(
                if (user.hasCourse()) R.drawable.ic_air_track
                else R.drawable.ic_air_track_nocourse,
                if (user.hasCourse()) courseBucket(user.course).toDouble() else null,
            )
        }
        val res = milMarkerRes(user.type)
        val raw = if (res != null) makeMilIcon(res, user.callsign ?: user.uid)
                  else makeIcon(user.callsign ?: user.uid, user.team, user.isStale)
        return centerOnSymbol(raw, symbolHeightPx(res != null))
    }

    /**
     * The air-track symbol, turned to the reported course.
     *
     * Small on purpose ([AIR_ICON_DP]): ADS-B traffic is context, not something the pilot acts on,
     * and it must not compete with the team and the markers. No callsign label for the same
     * reason — near a busy field the labels overlap into an unreadable mat.
     */
    fun makeAirIcon(resId: Int, courseDeg: Double?): Bitmap {
        val ctx = appContext
        val d = density
        val size = (AIR_ICON_DP * d).toInt()
        val icon = ctx?.let { drawableToBitmap(it, resId, size) }
        // A rotated square needs its diagonal, or the wingtips clip at 45 degrees.
        val box = if (courseDeg != null) (size * 1.42f).toInt() else size

        val bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (icon != null) {
            val left = (box - size) / 2f
            if (courseDeg != null) {
                c.save()
                c.rotate(courseDeg.toFloat(), box / 2f, box / 2f)
                c.drawBitmap(icon, left, left, null)
                c.restore()
            } else {
                c.drawBitmap(icon, left, left, null)
            }
        }
        return bmp
    }

    private const val AIR_ICON_DP = 12f    // ADS-B traffic — context, not something acted on

    /**
     * The generated bitmaps are symbol-on-top, callsign-label-below. osmdroid could anchor at
     * an arbitrary fraction; MapLibre pins a style image by its center, which would hang the
     * symbol above the actual position by half the label. Pad the TOP with transparency until
     * the symbol's own center is the bitmap's center, so the default center anchor lands the
     * symbol exactly on the contact's lat/lon.
     */
    private fun centerOnSymbol(src: Bitmap, symbolPx: Int): Bitmap {
        val symbolCenterY = symbolPx / 2
        val padTop = src.height - 2 * symbolCenterY
        if (padTop <= 0) return src
        val out = Bitmap.createBitmap(src.width, src.height + padTop, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, padTop.toFloat(), null)
        return out
    }

    /** Height of just the symbol part (above the label) — must match the make*Icon sizes. */
    private fun symbolHeightPx(isMil: Boolean): Int =
        if (isMil) (32 * density).toInt() else (14 * density).toInt()

    /**
     * A 2525 affiliation frame + label, padded for MapLibre's center anchoring. Shared with
     * [TakDropMarkers] so our own pins and inbound markers of the same type look identical.
     */
    fun milIconBitmap(resId: Int, label: String): Bitmap =
        centerOnSymbol(makeMilIcon(resId, label), symbolHeightPx(true))

    /** Render any drawable resource (incl. vectors) to a square bitmap. */
    fun drawableToBitmap(ctx: Context, resId: Int, sizePx: Int): Bitmap? = try {
        val dr = androidx.core.content.ContextCompat.getDrawable(ctx, resId)
        dr?.let {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            it.setBounds(0, 0, sizePx, sizePx)
            it.draw(c)
            bmp
        }
    } catch (e: Exception) { null }

    /** MIL-STD-2525 affiliation frame + callsign label below. */
    fun makeMilIcon(resId: Int, callsign: String): Bitmap {
        val ctx = appContext
        val d = density
        val size = (32 * d).toInt()
        val icon = ctx?.let { drawableToBitmap(it, resId, size) }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 10 * d; typeface = Typeface.DEFAULT_BOLD
        }
        val tw = text.measureText(callsign)
        val fm = text.fontMetrics
        val th = fm.descent - fm.ascent
        val gap = (d * 3).toInt(); val padH = (4 * d).toInt(); val padV = (d * 2).toInt()
        val labelW = tw.toInt() + padH * 2
        val labelH = th.toInt() + padV * 2
        val w = maxOf(size, labelW)
        val h = size + gap + labelH

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (icon != null) c.drawBitmap(icon, (w - size) / 2f, 0f, null)

        val labelLeft = (w - labelW) / 2f
        val labelTop = (size + gap).toFloat()
        c.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH, d * 3, d * 3,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0) })
        c.drawText(callsign, labelLeft + padH, labelTop + padV - fm.ascent, text)
        return bmp
    }

    /** Colored dot + callsign label — 1:1 port of taklite's createTakMarkerIcon. */
    private fun makeIcon(callsign: String, team: String?, isStale: Boolean): Bitmap {
        val color = if (isStale) Color.GRAY else teamColor(team)
        val d = density
        val iconSize = (14 * d).toInt()
        val r = iconSize / 2f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 10 * d
            typeface = Typeface.DEFAULT_BOLD
        }
        val textWidth = textPaint.measureText(callsign)
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        val gap = (d * 3).toInt()
        val textPadH = (d * 3).toInt()
        val textPadV = (d * 1.5f).toInt()
        val labelW = textWidth.toInt() + textPadH * 2
        val labelH = textHeight.toInt() + textPadV * 2
        val bmpWidth = maxOf(iconSize, labelW)
        val bmpHeight = iconSize + gap + labelH

        val bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = bmpWidth / 2f
        val cr = r - 1

        canvas.drawCircle(cx, r, cr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.FILL
        })
        canvas.drawCircle(cx, r, cr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = d * 1.5f
        })

        val labelLeft = (bmpWidth - labelW) / 2f
        val labelTop = (iconSize + gap).toFloat()
        canvas.drawRoundRect(labelLeft, labelTop, labelLeft + labelW, labelTop + labelH,
            d * 3, d * 3, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(140, 0, 0, 0) })
        canvas.drawText(callsign, labelLeft + textPadH, labelTop + textPadV - fm.ascent, textPaint)
        return bmp
    }
}

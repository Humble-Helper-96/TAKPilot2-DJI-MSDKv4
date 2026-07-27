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

    // Received 2525 MARKERS (a-{f,h,n,u}-G) we persist so they survive restarts. PLI contacts
    // are NOT persisted — they re-broadcast live and would otherwise ghost. Keyed by uid.
    private val savedMarkers = LinkedHashMap<String, SavedMarker>()

    private data class SavedMarker(
        val uid: String, val lat: Double, val lon: Double, val alt: Double,
        val type: String, val callsign: String, val team: String,
    )

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
            override fun onTakUserRemoved(uid: String) = remove(uid)
            override fun onTakConnectionChanged(connected: Boolean) {}
        })
    }

    private fun SavedMarker.toUser(): TakUser =
        TakUser(uid, callsign, lat, lon, alt, team, "", Long.MAX_VALUE).also { it.type = type }

    private fun resyncExisting() {
        shown.clear()
        try {
            for (s in savedMarkers.values) if (!hidden.contains(s.uid)) stage(s.toUser())
            for (user in TakManager.getInstance().takUsers) stage(user)
            rebuild()
            AppLog.v(TAG, "resync: ${shown.size} markers on map")
        } catch (e: Exception) {
            AppLog.w(TAG, "resync failed: ${e.message}")
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

    private fun persistIfMarker(user: TakUser) {
        if (milMarkerRes(user.type) == null || hidden.contains(user.uid)) return
        if (TakDropMarkers.ownsUid(user.uid)) return
        savedMarkers[user.uid] = SavedMarker(
            user.uid, user.lat, user.lon, user.alt,
            user.type ?: "", user.callsign ?: user.uid, user.team ?: "Cyan")
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
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val uid = o.getString("uid")
                    if (hidden.contains(uid)) continue
                    savedMarkers[uid] = SavedMarker(uid, o.getDouble("lat"), o.getDouble("lon"),
                        o.optDouble("alt", 0.0), o.getString("type"),
                        o.optString("cs", uid), o.optString("team", "Cyan"))
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
        val team = (user.team ?: "Cyan").lowercase()
        val stale = if (user.isStale) "S" else "A"
        val drone = if (user.isDrone) "D" else "U"
        val mil = milMarkerRes(user.type) ?: 0
        return "$team|$stale|$drone|$mil|${user.callsign}"
    }

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

    /** TAK team-name → color, identical to taklite's getTeamColor(). */
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
        val res = milMarkerRes(user.type)
        val raw = if (res != null) makeMilIcon(res, user.callsign ?: user.uid)
                  else makeIcon(user.callsign ?: user.uid, user.team, user.isStale)
        return centerOnSymbol(raw, symbolHeightPx(res != null))
    }

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

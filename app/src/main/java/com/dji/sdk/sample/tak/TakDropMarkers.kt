package com.dji.sdk.sample.tak

import android.content.Context
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
import com.taklite.util.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pilot-dropped MIL-STD-2525 pins. Third port of TAKPilot2's TakDropMarkers (DJI mapkit ->
 * osmdroid -> MapLibre), but the placement UX is deliberately NOT the reference apps':
 *
 * Both references place a pin by tapping the map. This app's mini-map is locked by operator
 * spec — no pan, no zoom, 160dp — so tapping a specific spot on it is neither possible nor
 * precise. Instead the **camera crosshair is the cursor**: the pilot aims the aircraft, taps
 * the toolbar drop button, and the pin lands at [TakBridgeHolder.lookPoint] — the
 * DTED-terrain-corrected ground point the camera is actually looking at.
 *
 * The other structural change is uid stability. Each [Pin] stores the CoT uid assigned on its
 * first send and reuses it forever after, because in CoT the uid *is* the marker's identity —
 * that's what lets 6C move a pin in place on other TAK clients instead of littering duplicates.
 *
 * Rendering follows [TakMapMarkers]: one [GeoJsonSource] + one [SymbolLayer], icons registered
 * as named style images.
 */
object TakDropMarkers {
    private const val TAG = "TakDropMarkers"
    private const val PREFS = "takpilot2_dropped"
    private const val KEY_PINS = "pins"
    private const val KEY_COUNTER = "auto_name_counter"

    const val SOURCE_ID = "tak-pins-source"
    const val LAYER_ID = "tak-pins-layer"

    private const val PROP_KEY = "key"
    private const val PROP_ICON = "icon"

    enum class Affiliation(val id: String, val label: String, val res: Int) {
        // `id` is what CotBuilder.buildMarker switches on to pick the CoT type — these four
        // strings map to a-f-G / a-h-G / a-n-G / a-u-G. Don't rename them casually.
        FRIENDLY("Friendly", "Friendly", R.drawable.marker_friendly),
        HOSTILE("Hostile", "Hostile", R.drawable.marker_hostile),
        NEUTRAL("Neutral", "Neutral", R.drawable.marker_neutral),
        UNKNOWN("Unknown", "Unknown", R.drawable.marker_unknown),
    }

    private class Pin(
        val key: String,
        var lat: Double,
        var lon: Double,
        var alt: Double,
        var affiliation: Affiliation,
        var name: String,
        /** CoT uid from the first send — the marker's identity. Null until it's been sent. */
        var cotUid: String?,
    )

    /** Read-only snapshot for the 6C markers list panel. */
    data class PinInfo(
        val key: String, val name: String, val affiliation: Affiliation,
        val lat: Double, val lon: Double, val alt: Double,
    )

    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var style: Style? = null
    private var source: GeoJsonSource? = null
    private val pins = LinkedHashMap<String, Pin>()

    /** Icon cache key -> registered style-image name. Same scheme as TakMapMarkers. */
    private val registeredImages = HashMap<String, String>()

    /** UI callbacks the flight Activity supplies (it owns the dialogs/toasts). */
    interface Ui {
        fun toast(msg: String)
    }

    @Volatile
    var ui: Ui? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        load()
        AppLog.v(TAG, "init: ${pins.size} pins restored")
    }

    /** Called by [TakMapMarkers.onMapReady] once the flight screen's style exists. */
    fun onMapReady(readyStyle: Style) {
        style = readyStyle
        registeredImages.clear()
        try {
            val src = GeoJsonSource(SOURCE_ID, FeatureCollection.fromFeatures(emptyList()))
            readyStyle.addSource(src)
            source = src
            readyStyle.addLayer(
                SymbolLayer(LAYER_ID, SOURCE_ID).withProperties(
                    iconImage(Expression.get(PROP_ICON)),
                    iconSize(1.0f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                )
            )
            rebuild()
        } catch (e: Exception) {
            AppLog.w(TAG, "onMapReady failed: ${e.message}")
        }
    }

    fun onMapDestroyed() {
        style = null
        source = null
        registeredImages.clear()
    }

    /**
     * Does a pin we currently own hold this CoT uid? Used by [TakMapMarkers] to skip the
     * server's echo of our own marker so it isn't drawn twice.
     */
    fun ownsUid(uid: String): Boolean {
        for (p in pins.values) if (p.cotUid == uid) return true
        return false
    }

    // ---- Auto-naming ----

    /**
     * The name the drop dialog pre-fills: drone callsign + "-P<n>", n being the next unused
     * number. Only a preview — the counter isn't consumed until [placeAt] is handed back this
     * exact string, so a pilot who types a custom name doesn't burn a number and leave a gap.
     */
    fun nextAutoName(): String = "${droneCallsign()}-P${counter() + 1}"

    private fun droneCallsign(): String {
        val ctx = appContext ?: return "sUAS"
        return ctx.getSharedPreferences("takpilot2_tak", Context.MODE_PRIVATE)
            .getString("callsign", "sUAS")?.takeIf { it.isNotBlank() } ?: "sUAS"
    }

    private fun counter(): Int = appContext
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ?.getInt(KEY_COUNTER, 0) ?: 0

    private fun consumeCounter() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_COUNTER, counter() + 1).apply()
    }

    /** Manual reset (pilot-triggered, e.g. after a Clear All) — next auto-name goes back to
     *  -P1. Deliberately manual, not automatic on Clear All: the counter and the pin list are
     *  independent state, and auto-resetting on every clear would be a surprising side effect
     *  the one time a pilot clears a stale batch but wants numbering to keep climbing. */
    fun resetAutoNameCounter() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_COUNTER, 0).apply()
        AppLog.i(TAG, "auto-name counter reset to 0")
    }

    // ---- Placement ----

    /**
     * Place a pin, draw it, persist it, and broadcast it to TAK. [name] is used verbatim; pass
     * the string from [nextAutoName] unchanged to also consume the auto-name counter.
     */
    fun placeAt(aff: Affiliation, lat: Double, lon: Double, alt: Double, name: String) {
        val autoName = nextAutoName()
        val finalName = name.trim().ifEmpty { autoName }
        if (finalName == autoName) consumeCounter()

        val pin = Pin(
            key = "${aff.id}-${System.nanoTime()}",
            lat = lat, lon = lon, alt = alt,
            affiliation = aff, name = finalName, cotUid = null,
        )
        pins[pin.key] = pin
        AppLog.i(TAG, "pin placed: ${pin.key} '$finalName' (${aff.label}) @ $lat,$lon alt=$alt")
        save()
        rebuild()
        sendPin(pin)
    }

    /**
     * Broadcast (or re-broadcast) a pin. First send mints a uid and stores it; every send after
     * that reuses it, so other TAK clients update the marker in place instead of duplicating.
     * Auto-scopes to a joined Data Sync feed when there is one; otherwise broadcasts, which
     * TakManager.sendCot already tags with the pilot's active channels.
     */
    private fun sendPin(pin: Pin) {
        val tak = TakManager.getInstance()
        if (!tak.isConnected) {
            AppLog.w(TAG, "pin ${pin.key} not sent — TAK not connected")
            ui?.toast("Pin saved locally — not connected to TAK")
            return
        }
        val uid = pin.cotUid ?: TakManager.newMarkerUid()
        val feed = TakMissionManager.joinedFeed
        val sent = tak.sendMarkerWithUid(
            uid, pin.lat, pin.lon, pin.alt, pin.affiliation.id, pin.name, "", feed)
        if (sent == null) {
            AppLog.w(TAG, "pin ${pin.key} send failed")
            ui?.toast("Pin saved locally — send failed")
            return
        }
        val isFirstSend = pin.cotUid == null
        pin.cotUid = sent
        save()
        if (feed != null) {
            // Register the uid in the feed's /contents so it shows for feed subscribers.
            if (isFirstSend) TakMissionManager.publishUid(sent)
            AppLog.i(TAG, "pin sent to feed '$feed': ${pin.key} uid=$sent")
            ui?.toast("Sent ${pin.name} to feed '$feed'")
        } else {
            AppLog.i(TAG, "pin sent to TAK: ${pin.key} uid=$sent")
            ui?.toast("Sent ${pin.name} to TAK")
        }
    }

    // ---- 6C: markers list panel / row actions ----

    /** Snapshot of all dropped pins for the markers list panel, newest first. */
    fun listPins(): List<PinInfo> = pins.values.reversed().map {
        PinInfo(it.key, it.name, it.affiliation, it.lat, it.lon, it.alt)
    }

    /** Re-aim to the current crosshair and re-send with the stored uid — moves the marker in
     *  place on other TAK clients instead of duplicating it. */
    fun moveToLookPoint(key: String, lat: Double, lon: Double, alt: Double) {
        val pin = pins[key] ?: return
        pin.lat = lat; pin.lon = lon; pin.alt = alt
        AppLog.i(TAG, "pin ${pin.key} moved to $lat,$lon alt=$alt")
        save(); rebuild(); sendPin(pin)
    }

    fun rename(key: String, newName: String) {
        val pin = pins[key] ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == pin.name) return
        AppLog.i(TAG, "pin ${pin.key} renamed '${pin.name}' -> '$trimmed'")
        pin.name = trimmed
        save(); rebuild(); sendPin(pin)
    }

    fun changeType(key: String, aff: Affiliation) {
        val pin = pins[key] ?: return
        if (aff == pin.affiliation) return
        AppLog.i(TAG, "pin ${pin.key} retyped ${pin.affiliation.label} -> ${aff.label}")
        pin.affiliation = aff
        save(); rebuild(); sendPin(pin)
    }

    /** Re-broadcast unchanged — same uid, refreshed time/stale on the wire. */
    fun resend(key: String) {
        val pin = pins[key] ?: return
        sendPin(pin)
    }

    /** Local-only delete (A2, decided 2026-07-25): removes the pin from our map and storage,
     *  sends nothing. The uid is NOT suppressed — if the server echoes it back it's expected
     *  to reappear as an ordinary inbound marker (see TakMapMarkers.stage). */
    fun delete(key: String) {
        val pin = pins.remove(key) ?: return
        AppLog.i(TAG, "pin ${pin.key} deleted locally (uid=${pin.cotUid})")
        save(); rebuild()
    }

    /** Local-only bulk delete — same semantics as [delete], applied to every pin at once. */
    fun clearAll() {
        if (pins.isEmpty()) return
        AppLog.i(TAG, "clearAll: removing ${pins.size} pins locally")
        pins.clear()
        save(); rebuild()
    }

    // ---- Rendering ----

    private fun rebuild() {
        main.post {
            val st = style ?: return@post
            val src = source ?: return@post
            try {
                val features = ArrayList<Feature>(pins.size)
                for (pin in pins.values) {
                    val key = "${pin.affiliation.id}|${pin.name}"
                    val imageId = registeredImages[key] ?: run {
                        val id = "tak-pin-${key.hashCode()}"
                        st.addImage(id, TakMapMarkers.milIconBitmap(pin.affiliation.res, pin.name))
                        registeredImages[key] = id
                        id
                    }
                    features.add(
                        Feature.fromGeometry(Point.fromLngLat(pin.lon, pin.lat)).apply {
                            addStringProperty(PROP_KEY, pin.key)
                            addStringProperty(PROP_ICON, imageId)
                        }
                    )
                }
                src.setGeoJson(FeatureCollection.fromFeatures(features))
            } catch (e: Exception) {
                AppLog.w(TAG, "rebuild failed: ${e.message}")
            }
        }
    }

    // ---- Persistence ----

    private fun save() {
        val ctx = appContext ?: return
        try {
            val arr = JSONArray()
            for (p in pins.values) {
                arr.put(JSONObject().apply {
                    put("key", p.key); put("lat", p.lat); put("lon", p.lon); put("alt", p.alt)
                    put("aff", p.affiliation.id); put("name", p.name)
                    p.cotUid?.let { put("uid", it) }
                })
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PINS, arr.toString()).apply()
        } catch (e: Exception) { AppLog.w(TAG, "save failed: ${e.message}") }
    }

    private fun load() {
        val ctx = appContext ?: return
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PINS, null) ?: return
            val arr = JSONArray(json)
            pins.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val aff = Affiliation.values().firstOrNull { it.id == o.getString("aff") }
                    ?: Affiliation.FRIENDLY
                val key = o.getString("key")
                pins[key] = Pin(
                    key, o.getDouble("lat"), o.getDouble("lon"), o.optDouble("alt", 0.0),
                    aff, o.optString("name", "Marker"),
                    o.optString("uid", "").takeIf { it.isNotEmpty() },
                )
            }
        } catch (e: Exception) { AppLog.w(TAG, "load failed: ${e.message}") }
    }
}

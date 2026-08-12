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

    /**
     * Callsign carried by the reticle-tap marker (see [placeQuick]). Fixed and short: it is the
     * same marker every time, so a name everyone learns to recognise beats a number, and a fixed
     * string keeps it clear of the `<callsign>-P<n>` auto-naming used by ordinary drops.
     */
    const val QUICK_NAME = "E419"

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
        /** The one reticle-tap pin. See [quickPin]. */
        val quick: Boolean = false,
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
        if (isFirstSend) scheduleRebroadcast(pin)
    }

    /**
     * Re-sends a pin once, [REBROADCAST_DELAY_MS] after its first successful send.
     *
     * WHY (operator, 2026-08-02). CoT markers are fire-and-forget: a teammate whose ATAK connects
     * thirty seconds after the drop never receives it, and nobody in the air knows that happened.
     * The old remedy was for the pilot to notice and hit Re-send by hand, which means the fix
     * depended on the one person least able to spare the attention.
     *
     * Re-sending under the SAME uid is what makes this safe. Clients that already have the marker
     * move it in place — to the identical position, so nothing visibly happens — and clients that
     * missed it draw it for the first time. Nobody sees a duplicate.
     *
     * Deliberately ONCE, not a repeating heartbeat. One extra packet closes the join window that
     * actually occurs in practice; a permanent re-broadcast of every marker ever dropped would
     * grow without bound for the rest of the flight.
     *
     * Skipped if the pin is deleted before the timer fires, and skipped for the quick marker,
     * which is re-sent on every re-aim anyway.
     */
    private fun scheduleRebroadcast(pin: Pin) {
        if (pin.quick) return
        val key = pin.key
        rebroadcast.postDelayed({
            val live = pins[key] ?: run {
                AppLog.v(TAG, "rebroadcast skipped — pin $key no longer exists")
                return@postDelayed
            }
            val tak = TakManager.getInstance()
            val uid = live.cotUid
            if (uid == null || !tak.isConnected) {
                AppLog.w(TAG, "rebroadcast skipped for $key — uid=$uid connected=${tak.isConnected}")
                return@postDelayed
            }
            // Same uid, current values: this is an UPDATE, not a second marker. Reads whatever the
            // pin holds NOW, so a rename or move inside the delay window is carried too.
            tak.sendMarkerWithUid(uid, live.lat, live.lon, live.alt, live.affiliation.id,
                live.name, "", TakMissionManager.joinedFeed)
            AppLog.i(TAG, "rebroadcast \"${live.name}\" uid=$uid (catches late-joining clients)")
        }, REBROADCAST_DELAY_MS)
    }

    private val rebroadcast = android.os.Handler(android.os.Looper.getMainLooper())

    /** Long enough for a late client to finish connecting, short enough to still be the same
     *  tactical moment. */
    private const val REBROADCAST_DELAY_MS = 60_000L

    // ---- Quick drop (reticle tap) ----

    /**
     * The reticle-tap marker: one pin, always [Affiliation.UNKNOWN], placed and re-aimed straight
     * from the crosshair with no dialog in between.
     *
     * **Exactly one may exist at a time, and that is the whole point.** The pilot is not
     * cataloguing places — they are keeping one "what I am looking at right now" marker current
     * for everyone else on the picture. A second tap re-aiming the same marker (rather than
     * dropping another) is what makes it a live pointer instead of a trail of numbered pins, and
     * it is why this needs no menu: with one target there is nothing to choose between. UNKNOWN
     * because a marker placed in under a second is by definition unverified.
     *
     * Identified by a stored flag, not by its name — so the pilot can rename it from the markers
     * list without it quietly becoming an ordinary pin (or, worse, a second quick drop becoming
     * possible). Deleting it from the markers list is what frees the slot.
     */
    fun quickPin(): PinInfo? = pins.values.firstOrNull { it.quick }?.let {
        PinInfo(it.key, it.name, it.affiliation, it.lat, it.lon, it.alt)
    }

    /**
     * Place the quick-drop pin. No-op returning false if one already exists — the caller decides
     * how to tell the pilot, since the answer ("long-press to re-aim it") is UI text.
     *
     * Does NOT touch the auto-name counter: this pin has a fixed callsign, so consuming a -P<n>
     * would leave a gap in the numbering of the pins that actually use it.
     */
    fun placeQuick(lat: Double, lon: Double, alt: Double): Boolean {
        if (quickPin() != null) return false
        val pin = Pin(
            key = "quick-${System.nanoTime()}",
            lat = lat, lon = lon, alt = alt,
            affiliation = Affiliation.UNKNOWN, name = QUICK_NAME, cotUid = null, quick = true,
        )
        pins[pin.key] = pin
        AppLog.i(TAG, "quick drop placed: ${pin.key} @ $lat,$lon alt=$alt")
        save()
        rebuild()
        sendPin(pin)
        return true
    }

    /**
     * Re-aim the quick-drop pin at the current look point, keeping its uid so every other TAK
     * client moves the existing marker instead of collecting duplicates. False if there is none.
     */
    fun moveQuick(lat: Double, lon: Double, alt: Double): Boolean {
        val pin = pins.values.firstOrNull { it.quick } ?: return false
        moveToLookPoint(pin.key, lat, lon, alt)
        return true
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
                    // Only written when set: an absent key reads back as false, so pins saved
                    // before quick-drop existed load unchanged.
                    if (p.quick) put("quick", true)
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
                    o.optBoolean("quick", false),
                )
            }
        } catch (e: Exception) { AppLog.w(TAG, "load failed: ${e.message}") }
    }
}

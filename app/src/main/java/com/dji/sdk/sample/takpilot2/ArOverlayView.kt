package com.dji.sdk.sample.takpilot2

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.dji.sdk.sample.tak.ArSettings
import com.dji.sdk.sample.tak.CameraSlantPoint
import com.dji.sdk.sample.tak.DroneTakBridge
import com.dji.sdk.sample.tak.TakBridgeHolder
import com.dji.sdk.sample.tak.TakDropMarkers
import com.dji.sdk.sample.tak.TakMapMarkers
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakUser
import com.taklite.util.AppLog
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Augmented-reality overlay: projects TAK marker positions onto the live FPV so they appear
 * pinned to the world. Third port of TAKPilot2's ArOverlayView (V5 → V4), with three deliberate
 * departures from the reference — see below.
 *
 * **Sub-phase A: dropped pins only.** Inbound TAK contacts come in 6B of the phase plan. Pins
 * are first on purpose: a pin is placed at [TakBridgeHolder.lookPoint], which is derived from
 * the same camera pose this view projects with, so **a freshly dropped pin must render dead
 * centre under the crosshair.** That is a ground-truth test needing no second TAK client, no
 * survey point and no flying — if it doesn't land under the crosshair, the projection, the pose
 * or the video rect is wrong, and there's no point adding contacts on top of that.
 *
 * ### Departures from the V5 reference
 *
 * 1. **Perspective, not linear, projection.** V5 maps angle to pixels linearly
 *    (`x = cx + Δbearing / (hFov/2) · halfW`), which is a small-angle approximation. A real lens
 *    is gnomonic, so this uses `tan(Δ)/tan(fov/2)`. At the Mini 2's 73° horizontal FOV the
 *    difference is visible toward the frame edges — and it vanishes at the centre, which is what
 *    makes it easy to miss when eyeballing a marker in the middle of frame.
 * 2. **Draws to the video rectangle, not the view.** [FpvTextureView] letterboxes the image
 *    inside the view; projecting against view bounds shifts everything by the size of the bars.
 *    Same rect [CrosshairView] already uses, fed the same way.
 * 3. **Pitch sign.** DJI reports gimbal pitch negative when looking down; screen Y grows
 *    downward. Both conventions are handled once, in [project], rather than at each call site.
 *
 * Accuracy is bounded by gimbal bearing accuracy and by telemetry lagging the video — markers
 * will swim during fast gimbal movement. This is a "which of those buildings" tool, not a
 * survey instrument; the crosshair drop remains the precise one.
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Video image bounds within this view, fed from [FpvTextureView.onVideoRectChanged]. */
    private val videoRect = RectF()

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    /** Faster than the 500ms HUD tick — at 500ms a marker visibly steps across the frame during
     *  a gimbal sweep. Every frame composites over live video, so this is the one knob to back
     *  off first if the FPV frame rate suffers (that pipeline must not regress). */
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    fun setVideoRect(rect: RectF) {
        videoRect.set(rect)
        invalidate()
    }

    /**
     * How much of the video the app's own chrome covers: the toolbar across the top, the HUD
     * column (exposure/readouts/mini-map) down the right. Only [drawEdgeArrow] uses these —
     * projected markers themselves stay pinned to their true position even if chrome partly
     * covers them (moving a marker off its target would be worse than briefly hiding it), but an
     * EDGE ARROW has no true position; it is purely a "look this way" cue, so one parked
     * underneath the toolbar conveys nothing at all.
     *
     * Fed from the flight screen's real measured view bounds rather than hardcoded dp, so this
     * can't drift out of step with a toolbar or HUD layout change.
     */
    fun setChromeInsets(top: Float, right: Float) {
        if (chromeInsetTop == top && chromeInsetRight == right) return
        chromeInsetTop = top
        chromeInsetRight = right
        invalidate()
    }

    private var chromeInsetTop = 0f
    private var chromeInsetRight = 0f

    fun start() {
        if (running) return
        running = true
        isRunningAnywhere = true
        visibility = VISIBLE
        handler.removeCallbacks(tick)
        handler.post(tick)
        AppLog.i(TAG, "AR overlay ON")
    }

    fun stop() {
        if (!running) return
        running = false
        isRunningAnywhere = false
        handler.removeCallbacks(tick)
        visibility = GONE
        AppLog.i(TAG, "AR overlay OFF")
    }

    val isRunning: Boolean get() = running

    // ---- Paints ----

    private val d get() = resources.displayMetrics.density

    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }

    /** PLI contacts draw as a team-coloured dot with a dark ring, matching the mini-map. */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 1.5f * Resources.getSystem().displayMetrics.density
    }

    /** Reused across edge arrows so the draw loop doesn't allocate a Path per frame. */
    private val arrowPath = android.graphics.Path()

    private val iconCache = HashMap<Int, Bitmap>()

    // Throttled so a persistent "why is nothing drawing" condition logs once rather than at the
    // refresh rate — this runs several times a second.
    private var lastSkipReason: String? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        if (videoRect.isEmpty) return skipped("no video rect yet")

        val pose = TakBridgeHolder.cameraPose() ?: return skipped("no camera pose (GPS/gimbal)")
        val hud = TakBridgeHolder.hud() ?: return skipped("no telemetry")
        if (!hud.hasFix) return skipped("no GPS fix")
        lastSkipReason = null

        // NOTE: no early-return when there are no dropped pins — inbound contacts are drawn
        // below and are entirely independent of whether this pilot has placed anything.
        val pins = if (ArSettings.isEnabled(context, ArSettings.Category.MY_MARKERS)) {
            TakDropMarkers.listPins()
        } else {
            emptyList()
        }

        // Throttle per DRAW PASS, not per pin — a per-call throttle only ever logs whichever pin
        // happens to be first in the list, which hid a second marker's trace entirely during
        // troubleshooting.
        val now = System.currentTimeMillis()
        val logThisPass = now - lastDiagMs >= DIAG_INTERVAL_MS
        if (logThisPass) lastDiagMs = now

        // Aircraft altitude in the same reference the pins carry (DTED MSL). Without a terrain
        // reference we can't difference two real elevations, so both pins and contacts fall
        // back to a flat-ground assumption below — degraded, not disabled.
        val aircraftMsl = com.dji.sdk.sample.tak.TerrainAgl.reading(context, hud).mslMeters
        // Loud, because without it EVERY vertical angle silently degrades: reported altitudes
        // can't be differenced against anything, and both pins and contacts fall back to a
        // flat-plane assumption that puts everything at the pilot's own takeoff level. That
        // failure looks like "AR works but heights are wrong" rather than like a missing input.
        if (logThisPass && aircraftMsl == null) {
            AppLog.w(TAG, "no aircraft MSL (no DTED takeoff reference) — pin and contact " +
                "elevations are flat-plane estimates; air traffic will render at your level")
        }

        for (pin in pins) {
            // Ground (great-circle) distance — what the label shows, matching the home-distance
            // and markers-list convention used elsewhere in the app.
            val groundDist = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, pin.lat, pin.lon)
            // Same ground horizon everything else on the ground gets — one source, so our own
            // pins can't out-range another operator's marker sitting beside them.
            if (groundDist > ArSettings.rangeMeters(context, ArSettings.Category.MY_MARKERS)) continue

            val bearing = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, pin.lat, pin.lon)
            // Wrapped to -180..180 so a target behind the aircraft doesn't project as if it were
            // far off to one side.
            val dBearing = ((bearing - pose.bearingDeg + 540.0) % 360.0) - 180.0

            // Height of the pin relative to the aircraft; negative = below, the normal case.
            val dz = if (aircraftMsl != null) {
                pin.alt - aircraftMsl
            } else {
                // No DTED reference for the AIRCRAFT's own position — pin.alt itself is 0.0 in
                // this state too (CameraSlantPoint's "unknown, assume sea level" fallback; see
                // DroneTakBridge.lookPoint), so there is no real elevation on either side of
                // this subtraction to use. Fall back to the SAME flat-ground assumption
                // CameraSlantPoint used to place this pin's lat/lon in the first place, and that
                // drawContacts() below already uses for inbound contacts without DTED: the pin
                // sits at the aircraft's own takeoff-relative ground level, i.e. straight down
                // by however far the aircraft has climbed. A hard 0.0 here (always level with
                // the aircraft) ignored the actual look angle the moment the aircraft wasn't at
                // ground level — the SPoI never had this gap because it always used this exact
                // fallback for the math that placed the pin to begin with; AR just wasn't
                // reusing it.
                -hud.alt
            }

            // Reject on SLANT range, never on ground distance. Aiming steeply down — which is
            // exactly how a marker gets dropped on something beneath the aircraft — drives
            // ground distance toward zero while the pin is still tens of metres away and
            // perfectly visible. Guarding on ground distance made near-nadir markers
            // undrawable, which is the one case the crosshair self-test most naturally lands in.
            val slantRange = kotlin.math.hypot(groundDist, dz)
            if (slantRange < MIN_RANGE_M) continue

            // Depression angle: rise over run against the ground distance, so straight-down
            // correctly approaches -90 degrees.
            val elevDeg = Math.toDegrees(atan2(dz, groundDist))
            val dElev = elevDeg - pose.pitchDeg

            val xy = project(dBearing, dElev)
            if (logThisPass) diag(pin, pose, groundDist, dz, bearing, dBearing, elevDeg, dElev, xy)
            if (xy == null) {
                drawEdgeArrow(canvas, dBearing, dElev, ARROW_COLOR_PIN)
                continue
            }
            drawPin(canvas, xy.first, xy.second, pin)
        }

        drawContacts(canvas, pose, hud, aircraftMsl, logThisPass)
    }

    /**
     * Inbound TAK contacts — other operators' positions and the markers they've placed.
     *
     * Skips, in order: contacts with no position; ones the pilot has locally hidden (that hide
     * is shared with the mini-map, so the two pictures agree); our own dropped pins echoed back
     * by the server (already drawn from [TakDropMarkers]); and our own published aircraft/SPI
     * uids, which are not targets at all.
     */
    private fun drawContacts(
        canvas: Canvas,
        pose: DroneTakBridge.CameraPose,
        hud: DroneTakBridge.Hud,
        aircraftMsl: Double?,
        logThisPass: Boolean,
    ) {
        // Nearest first, so when the label budget runs out it's the distant contacts that lose
        // their plate rather than whichever happened to arrive first.
        val users = runCatching { TakManager.getInstance().takUsers }.getOrNull()
            ?.sortedBy { CameraSlantPoint.distanceMeters(hud.lat, hud.lon, it.lat, it.lon) }
            ?: return
        var drawn = 0
        var offFrame = 0
        var skipped = 0
        var detailed = 0

        for (u in users) {
            val lat = u.lat
            val lon = u.lon
            if (lat == 0.0 && lon == 0.0) { skipped++; continue }
            if (TakMapMarkers.isHidden(u.uid)) { skipped++; continue }
            if (TakDropMarkers.ownsUid(u.uid)) { skipped++; continue }
            if (TakBridgeHolder.isOwnPublishedUid(u.uid)) { skipped++; continue }

            // Category decides BOTH whether this is drawn and how far out it stays relevant —
            // air traffic is worth seeing well past the range a ground marker is.
            val category = ArSettings.categoryFor(u.uid, u.type)
            if (!ArSettings.isEnabled(context, category)) { skipped++; continue }

            val groundDist = CameraSlantPoint.distanceMeters(hud.lat, hud.lon, lat, lon)
            if (groundDist > ArSettings.rangeMeters(context, category)) { skipped++; continue }

            // Height of the contact relative to the aircraft. Two independent ways to get it,
            // and they disagree — which one is right is an open field question (see below).
            //
            // REPORTED (primary, operator's call): the contact's own CoT altitude. TAK clients
            // do know and publish their height, so using it is the obvious thing and it is the
            // only option that can be right for a target NOT standing on the ground — an upper
            // floor, a bridge, another aircraft.
            //   Caveat: CotParser reads it from the `hae` attribute, i.e. height above the WGS84
            //   ELLIPSOID, while aircraftMsl is MSL (DTED takeoff terrain + height climbed).
            //   Subtracting one from the other carries the geoid separation — order 10-15 m
            //   locally. That is nothing at 1 km and about 18 degrees at 30 m, so if contacts
            //   render systematically LOW and worsen as you close on them, this is why.
            //
            // TERRAIN (fallback): ground elevation under the contact from DTED. Self-consistent
            // in MSL with no datum mixing, and accurate to roughly a person's height for anyone
            // on foot — but simply wrong for anything off the ground. This is also what the V5
            // reference settled on after finding reported altitude made contacts "float in the
            // sky and slew as the gimbal tilts".
            val targetGroundMsl = com.dji.sdk.sample.tak.DtedIndex.elevationAt(context, lat, lon)
            val dzTerrain = if (targetGroundMsl != null && aircraftMsl != null) {
                targetGroundMsl - aircraftMsl
            } else {
                // No DTED under the target: assume it sits at our takeoff elevation, which is
                // V5's flat-plane assumption.
                -hud.alt
            }
            val reported = u.alt
            val dzReported = if (aircraftMsl != null && isUsableAltitude(reported)) {
                reported - aircraftMsl
            } else {
                null
            }
            // AIR TRAFFIC BALLPARK (no DTED, category == AIRCRAFT only): we don't know our own
            // MSL, but ADS-B still tells us roughly how high THEY are. Same "assume flat ground
            // at our takeoff elevation" convention as dzTerrain's else-branch and the pin
            // fallback above — their reported altitude, taken as height above that same assumed
            // ground, minus how far above it we currently are. This silently assumes our
            // takeoff point sits at the same true elevation their altimeter is referenced to,
            // which is why [dzIsTrusted] is false here — good enough to place the icon in
            // roughly the right place, not good enough to print a number and imply precision
            // that isn't there. Placing an airliner at our own level (the old fallback here)
            // was a worse approximation than this for the one category where altitude is the
            // entire point of drawing it.
            val dzAirBallpark = if (category == ArSettings.Category.AIRCRAFT &&
                isUsableAltitude(reported)) reported - hud.alt else null
            val dz = dzReported ?: dzAirBallpark ?: dzTerrain
            // Only the fully DTED-backed computation is trustworthy enough to LABEL with a
            // number — see drawAircraft. The ballpark above and the flat-terrain fallback both
            // still drive where the icon is drawn; they just don't get printed as text.
            val dzIsTrusted = dzReported != null

            if (kotlin.math.hypot(groundDist, dz) < MIN_RANGE_M) { skipped++; continue }

            val bearing = CameraSlantPoint.initialBearingDeg(hud.lat, hud.lon, lat, lon)
            val dBearing = ((bearing - pose.bearingDeg + 540.0) % 360.0) - 180.0
            val dElev = Math.toDegrees(atan2(dz, groundDist)) - pose.pitchDeg

            val xy = project(dBearing, dElev)

            // Trace the first few in detail, showing BOTH height methods side by side. The
            // whole point is that "reported" and "terrain" disagree by the geoid offset, and
            // whichever one puts the marker on the actual person is the one to keep — that is
            // an observation to make in the field, not a call to settle from a desk. The
            // elevation angle each would produce is spelled out because degrees, not metres,
            // are what moves the icon on screen.
            if (logThisPass && detailed < CONTACT_DETAIL_LIMIT) {
                detailed++
                val elevRep = dzReported?.let { Math.toDegrees(atan2(it, groundDist)) }
                val elevTer = Math.toDegrees(atan2(dzTerrain, groundDist))
                AppLog.d(
                    TAG,
                    "contact='${u.callsign ?: u.uid}' type=${u.type} gDist=%.0fm | ".format(groundDist) +
                        "dzReported=%s dzTerrain=%.1fm | elevReported=%s elevTerrain=%.1f | using=%s | %s".format(
                            dzReported?.let { "%.1fm".format(it) } ?: "none",
                            dzTerrain,
                            elevRep?.let { "%.1f".format(it) } ?: "none",
                            elevTer,
                            if (dzReported != null) "reported" else "terrain",
                            if (xy == null) "OFF-FRAME" else "drawn at %.0f,%.0f".format(xy.first, xy.second),
                        ),
                )
            }

            if (xy == null) {
                offFrame++
                drawEdgeArrow(canvas, dBearing, dElev, TakMapMarkers.teamColor(u.team))
                continue
            }
            // Label budget: icons stay (they're the position information), but past this many
            // on-screen contacts the name+range plates overlap into an unreadable mass and
            // start obscuring the video, which is a flight-safety regression rather than an
            // aesthetic one. Nearest are drawn first, so the ones that lose their label are the
            // furthest away.
            drawContact(canvas, xy.first, xy.second, u, category, dz, dzIsTrusted, withLabel = drawn < MAX_LABELS)
            drawn++
        }

        // A count for the rest: a busy TAK picture is a dozen-plus contacts, and tracing every
        // one at 1Hz would bury the per-pin detail that actually needs reading.
        if (logThisPass && users.isNotEmpty()) {
            AppLog.d(TAG, "contacts: ${users.size} known, $drawn drawn, $offFrame off-frame, $skipped skipped")
        }
    }

    /**
     * Air traffic: a diamond rather than a dot or a 2525 ground frame, so it cannot be confused
     * with anything on the ground, plus its altitude in the label.
     *
     * **Altitude is the point** for an aircraft — the pilot's question is "how far above me is
     * that" — so it earns label space where a ground contact's range did not. Taken from the CoT
     * `hae`; the ADS-B gateway sources that from BAROMETRIC altitude, so treat it as good to a
     * couple of hundred feet rather than exact.
     *
     * **The number is only printed when [dzIsTrusted]** — the DTED-backed computation, both our
     * own MSL and theirs differenced properly. Without DTED, [dzMeters] still places the icon
     * (the flat-ground ballpark in [drawContacts] beats pinning every aircraft to our own
     * level), but that ballpark silently assumes our takeoff point sits at the same true
     * elevation their altimeter is referenced to — close enough to point the icon at roughly the
     * right spot, not close enough to print as a number implying precision that isn't there.
     * Callsign only in that case.
     *
     * No heading rotation: CotParser does not carry the CoT `course` field, so there is nothing
     * honest to rotate by, and a symmetric diamond does not imply a direction it does not know.
     */
    private fun drawAircraft(
        canvas: Canvas, x: Float, y: Float, u: TakUser, dzMeters: Double, dzIsTrusted: Boolean,
        withLabel: Boolean,
    ) {
        val r = 8f * d
        arrowPath.reset()
        arrowPath.moveTo(x, y - r)
        arrowPath.lineTo(x + r, y)
        arrowPath.lineTo(x, y + r)
        arrowPath.lineTo(x - r, y)
        arrowPath.close()
        dotPaint.color = if (u.isStale) Color.GRAY else AIRCRAFT_COLOR
        canvas.drawPath(arrowPath, dotPaint)
        canvas.drawPath(arrowPath, dotRing)
        if (!withLabel) return
        val callsign = u.callsign ?: u.uid
        // RELATIVE height, signed — "+2400 ft" is the question a pilot is actually asking of
        // another aircraft, and unlike its MSL altitude it is self-checking: a track labelled
        // +2400 ft that renders near the horizon is visibly wrong, where "2900 ft" looks
        // plausible whatever the icon does.
        val text = if (dzIsTrusted) {
            "%s  %s%s".format(callsign, if (dzMeters >= 0) "+" else "-", Units.feet(abs(dzMeters)))
        } else {
            callsign
        }
        drawLabel(canvas, x, y + r, text)
    }

    /**
     * Is a CoT-reported altitude actually a number we can use?
     *
     * **`9999999.0` is the TAK convention for "unknown"**, not a real height, and the operator's
     * own gateways emit it: the METAR weather gateway sets `hae="9999999.0"` on every station
     * marker, as does the seismic gateway. Taken literally that places a marker ~10,000 km
     * overhead, which projects past [MAX_PROJECT_ANGLE] and turns every nearby weather station
     * into a bogus edge arrow pinned to the top of the frame. Anchorage alone has several
     * reporting stations inside AR range, so this was not a theoretical case.
     *
     * Also rejects exact 0.0, which CoT uses about as loosely — CloudTAK self-markers publish it
     * (`"center": [lon, lat, 0]`) when they have no altitude fix.
     */
    private fun isUsableAltitude(alt: Double): Boolean =
        alt.isFinite() && alt != 0.0 && abs(alt) < UNKNOWN_ALT_SENTINEL

    /** 2525 frame for a placed marker; team-coloured dot for a PLI/unit. Grey when stale. */
    private fun drawContact(
        canvas: Canvas, x: Float, y: Float, u: TakUser, category: ArSettings.Category,
        dzMeters: Double, dzIsTrusted: Boolean, withLabel: Boolean,
    ) {
        val label = u.callsign ?: u.uid
        if (category == ArSettings.Category.AIRCRAFT) {
            drawAircraft(canvas, x, y, u, dzMeters, dzIsTrusted, withLabel)
            return
        }
        val milRes = TakMapMarkers.milMarkerRes(u.type)
        if (milRes != null) {
            val size = (ICON_DP * d).toInt()
            val bmp = iconCache.getOrPut(milRes) {
                TakMapMarkers.drawableToBitmap(context, milRes, size) ?: run {
                    AppLog.w(TAG, "contact icon failed to rasterise — not drawn")
                    return
                }
            }
            canvas.drawBitmap(bmp, x - size / 2f, y - size / 2f, iconPaint)
            if (withLabel) drawLabel(canvas, x, y + size / 2f, label)
        } else {
            val r = 7f * d
            dotPaint.color = if (u.isStale) Color.GRAY else TakMapMarkers.teamColor(u.team)
            canvas.drawCircle(x, y, r, dotPaint)
            canvas.drawCircle(x, y, r, dotRing)
            if (withLabel) drawLabel(canvas, x, y + r, label)
        }
    }

    /**
     * Angular offset from the camera axis → pixel, or null if outside the frame.
     *
     * Gnomonic (true perspective) rather than the reference's linear mapping: screen offset is
     * proportional to `tan` of the angle, normalised by `tan` of the half-FOV.
     *
     * Guarded at ±85° because tan blows up approaching 90° — without it a target off to the side
     * or behind produces an astronomically large coordinate rather than simply being off-frame.
     */
    private fun project(dBearingDeg: Double, dElevDeg: Double): Pair<Float, Float>? {
        if (abs(dBearingDeg) >= MAX_PROJECT_ANGLE || abs(dElevDeg) >= MAX_PROJECT_ANGLE) return null

        val zoom = TakBridgeHolder.currentZoomFactor
        val halfH = Math.toRadians(DroneTakBridge.hFovDeg(zoom) / 2.0)
        val halfV = Math.toRadians(DroneTakBridge.vFovDeg(zoom) / 2.0)
        val nx = tan(Math.toRadians(dBearingDeg)) / tan(halfH)
        val ny = tan(Math.toRadians(dElevDeg)) / tan(halfV)
        if (abs(nx) > 1.0 || abs(ny) > 1.0) return null   // off-frame; edge arrows come in 6D-C

        val x = videoRect.centerX() + (nx * videoRect.width() / 2.0).toFloat()
        // Screen Y grows downward, camera elevation grows upward — hence the subtraction.
        val y = videoRect.centerY() - (ny * videoRect.height() / 2.0).toFloat()
        return x to y
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float, pin: TakDropMarkers.PinInfo) {
        val size = (ICON_DP * d).toInt()
        // MUST rasterise through the drawable, not BitmapFactory. The affiliation markers are
        // VectorDrawable XML, and BitmapFactory.decodeResource returns null for those — the
        // original version of this silently drew nothing while the projection logged a correct
        // on-screen position, which is about the most misleading failure available. The mini-map
        // has always done it this way; reuse it rather than keeping a second rasteriser.
        val bmp = iconCache.getOrPut(pin.affiliation.res) {
            com.dji.sdk.sample.tak.TakMapMarkers.drawableToBitmap(context, pin.affiliation.res, size)
                ?: run {
                    // Loud, not silent: a marker the pilot cannot see is a marker they will
                    // assume is not there.
                    AppLog.w(TAG, "icon ${pin.affiliation.label} failed to rasterise — not drawn")
                    return
                }
        }
        canvas.drawBitmap(bmp, x - size / 2f, y - size / 2f, iconPaint)
        drawLabel(canvas, x, y + size / 2f, pin.name)
    }

    /**
     * Marker for something outside the frame, pinned to the edge in its direction.
     *
     * Worth having rather than just dropping off-frame targets: "there is a marker 40° to your
     * left" is the cue that tells a pilot which way to yaw. Without it the overlay is silent
     * about everything it can't currently see, which reads as "there is nothing there".
     *
     * Clamped into the video rect with a margin so an arrow never lands under the toolbar or
     * outside the image, and drawn as a triangle pointing outward along the direction to the
     * target rather than a plain dot, so the direction is readable at a glance.
     */
    private fun drawEdgeArrow(canvas: Canvas, dBearingDeg: Double, dElevDeg: Double, color: Int) {
        // Normalised direction; clamped because a target directly behind produces a huge value
        // that would otherwise dominate the angle.
        val zoom = TakBridgeHolder.currentZoomFactor
        val nx = (dBearingDeg / (DroneTakBridge.hFovDeg(zoom) / 2.0)).coerceIn(-1.0, 1.0)
        val ny = (-dElevDeg / (DroneTakBridge.vFovDeg(zoom) / 2.0)).coerceIn(-1.0, 1.0)
        val margin = 16f * d
        val cx = videoRect.centerX()
        val cy = videoRect.centerY()
        // Clamp into the VISIBLE part of the video, not the whole video rect. The toolbar is
        // drawn on top of the video, and the HUD column (exposure, readouts, mini-map) sits over
        // the right side — an arrow clamped to the raw rect lands underneath them and is simply
        // invisible. Reported from the field 2026-07-27: air traffic directly overhead produced
        // an above-frame arrow the pilot could never see, which is the one case the indicator
        // matters most.
        val x = (cx + nx.toFloat() * (videoRect.width() / 2f - margin))
            .coerceIn(videoRect.left + margin, videoRect.right - chromeInsetRight - margin)
        val y = (cy + ny.toFloat() * (videoRect.height() / 2f - margin))
            .coerceIn(videoRect.top + chromeInsetTop + margin, videoRect.bottom - margin)

        val angle = atan2((y - cy).toDouble(), (x - cx).toDouble())
        val r = 7f * d
        arrowPath.reset()
        arrowPath.moveTo(
            x + (r * kotlin.math.cos(angle)).toFloat(),
            y + (r * kotlin.math.sin(angle)).toFloat(),
        )
        val spread = Math.toRadians(140.0)
        arrowPath.lineTo(
            x + (r * kotlin.math.cos(angle + spread)).toFloat(),
            y + (r * kotlin.math.sin(angle + spread)).toFloat(),
        )
        arrowPath.lineTo(
            x + (r * kotlin.math.cos(angle - spread)).toFloat(),
            y + (r * kotlin.math.sin(angle - spread)).toFloat(),
        )
        arrowPath.close()
        dotPaint.color = color
        canvas.drawPath(arrowPath, dotPaint)
        canvas.drawPath(arrowPath, dotRing)
    }

    /**
     * Name plate, hung just below the symbol. Shared by pins and contacts so the two can't
     * drift apart visually. [symbolBottom] is the bottom edge of whatever was drawn.
     *
     * Name only — range was here initially and removed at the operator's call: it roughly
     * doubles the plate width, and in a crowded picture the plates are the thing that overlaps
     * and starts hiding the video. Range is still available on the mini-map and, for our own
     * pins, in the markers list.
     */
    private fun drawLabel(canvas: Canvas, x: Float, symbolBottom: Float, name: String) {
        labelPaint.textSize = LABEL_SP * d
        val text = name
        val tw = labelPaint.measureText(text)
        val fm = labelPaint.fontMetrics
        val top = symbolBottom + 4 * d
        canvas.drawRoundRect(
            x - tw / 2 - 5 * d, top, x + tw / 2 + 5 * d, top + (fm.descent - fm.ascent) + 3 * d,
            3 * d, 3 * d, labelBg,
        )
        canvas.drawText(text, x, top - fm.ascent + 1.5f * d, labelPaint)
    }

    /**
     * Per-pin projection trace, throttled to once a second.
     *
     * Exists because a pin that projects outside the frame is otherwise discarded in total
     * silence — the overlay looks identical whether the maths is right and the target is
     * genuinely off-screen, or the maths is wrong. These are the numbers needed to tell those
     * apart: if `dBrg` is near zero the camera really is pointed at the pin, so an off-frame
     * result means the FOV or the projection is at fault, not the pose.
     */
    private fun diag(
        pin: TakDropMarkers.PinInfo,
        pose: DroneTakBridge.CameraPose,
        groundDist: Double,
        dz: Double,
        bearing: Double,
        dBearing: Double,
        elevDeg: Double,
        dElev: Double,
        xy: Pair<Float, Float>?,
    ) {
        AppLog.d(
            TAG,
            "pin='${pin.name}' gDist=%.1fm dz=%.1fm | camBrg=%.1f pinBrg=%.1f dBrg=%.1f | " .format(
                groundDist, dz, pose.bearingDeg, bearing, dBearing,
            ) + "camPitch=%.1f pinElev=%.1f dElev=%.1f | fov=%.0fx%.0f | %s".format(
                pose.pitchDeg, elevDeg, dElev,
                // EFFECTIVE fov, zoom included — printing the 1x base while zoomed is
                // actively misleading during calibration, which is when this gets read.
                DroneTakBridge.hFovDeg(TakBridgeHolder.currentZoomFactor),
                DroneTakBridge.vFovDeg(TakBridgeHolder.currentZoomFactor),
                if (xy == null) "OFF-FRAME (not drawn)"
                else "drawn at %.0f,%.0f in rect %.0f,%.0f-%.0f,%.0f".format(
                    xy.first, xy.second,
                    videoRect.left, videoRect.top, videoRect.right, videoRect.bottom,
                ),
            ),
        )
    }

    private var lastDiagMs = 0L

    private fun skipped(reason: String) {
        if (lastSkipReason != reason) {
            lastSkipReason = reason
            AppLog.v(TAG, "AR not drawing: $reason")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(tick)
        iconCache.clear()
    }

    companion object {
        private const val TAG = "TP2Ar"

        /** Process-wide mirror of [isRunning] — only one ArOverlayView instance exists at a
         *  time (the flight screen's), but callers elsewhere in the app (video-health logging)
         *  shouldn't need a view reference just to ask "is AR compositing over the video right
         *  now," the same way [com.dji.sdk.sample.tak.VideoStreamerHolder]'s state is queried
         *  from anywhere without a streamer reference. */
        @Volatile
        var isRunningAnywhere: Boolean = false

        private const val REFRESH_MS = 100L
        private const val ICON_DP = 26f
        private const val LABEL_SP = 11f
        /** Slant range below which the pin is effectively at the camera and the angles stop
         *  meaning anything. Deliberately compared against slant range, not ground distance —
         *  see the loop. */
        private const val MIN_RANGE_M = 2.0
        // Range caps all live in ArSettings.rangeMeters() — ground fixed, air pilot-selectable.
        /** TAK's "unknown altitude" sentinel — see isUsableAltitude. */
        private const val UNKNOWN_ALT_SENTINEL = 999_999.0
        /** Matches the magenta the ADS-B gateway tags its tracks with. */
        private val AIRCRAFT_COLOR = 0xFFFF00FF.toInt()
        private const val MAX_PROJECT_ANGLE = 85.0
        /** Projection trace cadence — the draw loop runs at 10Hz, which is far too fast to log. */
        private const val DIAG_INTERVAL_MS = 1000L
        /** How many contacts get a full trace line per pass; the rest are counted. */
        private const val CONTACT_DETAIL_LIMIT = 3
        /** On-screen contacts that get a name+range plate; the rest keep just their icon. */
        private const val MAX_LABELS = 6
        /** Edge arrows for our own pins use the app's marker-drop accent, not a team colour. */
        private val ARROW_COLOR_PIN = 0xFF9AC4FF.toInt()
    }
}

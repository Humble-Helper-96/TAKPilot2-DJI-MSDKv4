package com.dji.sdk.sample.tak

import com.taklite.util.AppLog

/**
 * What the flight screen's banner shows, and when.
 *
 * ## Why this is an arbitration layer, not a second warning source
 *
 * This aircraft already has a real one. `DJIDiagnostics` is a first-class SDK channel that
 * reports compass, IMU, battery and no-fly-zone faults in DJI's own localised words, and
 * [DjiSdkBridge] already subscribes to it and de-duplicates it. The Autel sibling had to
 * hand-derive all of that from status booleans **because it has no such channel**. Rebuilding
 * the reconstruction here and letting it compete with the real thing would be a regression, and
 * would show the same fault twice in two different wordings.
 *
 * So the aircraft's own faults arrive through [onDiagnostics] and are shown VERBATIM at the top
 * of the priority order. This object never re-words them and never decides one is not worth
 * seeing. What it adds is the tier that `DJIDiagnostics` has no equivalent for — the aircraft
 * flying itself home, its own battery thresholds, the configured altitude and distance limits, a
 * missing home point, wind — plus the display discipline the raw banner lacked.
 *
 * ## What the display discipline is for
 *
 * Before this, `renderDiagnostics` did `items.joinToString("\n")`: every active fault, unbounded,
 * with no hold. A fault that flaps at the callback rate strobes, and a long list grows down over
 * the video. Here one warning owns the banner at a time — the worst — held for [HOLD_MS] so a
 * flicker cannot make the text jump, with a `+N` count for the rest. A worse warning preempts
 * immediately; nothing is ever hidden, only queued.
 *
 * ## No-fly zone stays on the banner (operator, 2026-08-11)
 *
 * The Autel sibling made its NO-FLY ZONE warning log-only, because that fleet flies under an FAA
 * exception and a red banner routinely correct to ignore teaches pilots to ignore red. **That
 * decision is not carried here.** This tree has its own field lesson pointing the other way:
 * "Cannot takeoff in a no-fly zone" was invisible through two flights, and `renderDiagnostics`
 * was written specifically not to filter by severity because of it. DJI's no-fly-zone diagnostic
 * therefore flows through [onDiagnostics] like any other aircraft fault and is displayed.
 *
 * ## Fed, never subscribed
 *
 * Same contract as [FlightPathLogger]. [onState] is called from the `FlightControllerState`
 * callback [DroneTakBridge] already owns; the SDK slot holds one client. Nothing here touches
 * the SDK.
 */
object FlightWarnings {

    /**
     * Priority order IS declaration order: worse first. Red = act now, amber = know it.
     *
     * Deliberately absent, because `DJIDiagnostics` already reports them and arrives as
     * [AIRCRAFT_FAULT]: compass interference, IMU faults, controller temperature, no-fly zones.
     * Also absent: a "returning home — signal lost" variant, because MSDK v4's
     * `FlightControllerState` carries no RC-link indicator to distinguish it from any other
     * return; a generic [RETURNING_HOME] covers that case honestly instead.
     */
    enum class Warning(val red: Boolean, val label: String) {
        /** The aircraft's own words, from DJIDiagnostics. Highest priority because it is the
         *  only source here that reports hardware faults, and because it is authoritative. */
        AIRCRAFT_FAULT(true, ""),
        BATTERY_CRITICAL(true, "BATTERY CRITICAL"),
        /** The aircraft has decided to put itself down where it is. Red: the pilot's remaining
         *  choice is where it lands, and only for a few more seconds. */
        BATTERY_LANDING(true, "LANDING NOW — BATTERY"),
        GPS_LOST(true, "GPS LOST — AIRCRAFT DRIFTS"),
        RTH_BATTERY(false, "RETURNING HOME — LOW BATTERY"),
        RETURNING_HOME(false, "RETURNING HOME"),
        WIND(false, "WIND TOO HIGH"),
        BATTERY_LOW(false, "BATTERY LOW"),
        AT_MAX_ALTITUDE(false, "AT ALTITUDE LIMIT"),
        AT_MAX_RANGE(false, "AT DISTANCE LIMIT"),
        NO_HOME_POINT(false, "NO HOME POINT"),
    }

    /** What the banner should show right now, or null for hidden. */
    data class Display(val text: String, val red: Boolean)

    /** Minimum time a warning owns the banner once shown — long enough to read, short enough
     *  that a stack still cycles usefully. A WORSE warning preempts regardless. */
    private const val HOLD_MS = 4000L

    /**
     * How close to a configured limit counts as "at" it. The aircraft brakes as it approaches
     * rather than stopping dead, so a strict equality test would fire late or never. 5% of the
     * limit, floored at 5 m so a small ceiling does not make the band vanish.
     */
    private const val LIMIT_MARGIN_FRACTION = 0.05
    private const val LIMIT_MARGIN_MIN_M = 5.0

    private val lock = Any()
    private var active: Set<Warning> = emptySet()
    private var faultText: String = ""
    /**
     * The last NON-EMPTY fault text.
     *
     * Needed because [AIRCRAFT_FAULT] can keep the banner through its hold after the fault has
     * already cleared, and reading the live (now empty) text at that moment replaced the
     * aircraft's own words with a placeholder — the banner said "Compass error" and then
     * silently became "aircraft fault" while the pilot was reading it. The hold exists so text
     * does not move under the reader, so the text it holds has to be the text it showed.
     */
    private var lastFaultText: String = ""
    private var shown: Warning? = null
    private var shownAtMs = 0L

    /** Configured limits in metres, or null when the pilot left the field blank. Set by the
     *  bridge at start from [FlightLimitsController], so this agrees with what was pushed. */
    @Volatile private var maxAltM: Double? = null
    @Volatile private var maxRadiusM: Double? = null

    fun setLimits(maxAltitudeM: Double?, maxRadiusMeters: Double?) {
        maxAltM = maxAltitudeM
        maxRadiusM = maxRadiusMeters
    }

    /**
     * The aircraft's own fault list, already de-duplicated and made readable by [DjiSdkBridge].
     * Shown verbatim — never re-worded, never filtered.
     */
    fun onDiagnostics(items: List<String>) {
        synchronized(lock) {
            val text = items.joinToString(" · ")
            if (text == faultText) return
            faultText = text
            if (text.isNotEmpty()) lastFaultText = text
            val next = if (text.isEmpty()) active - Warning.AIRCRAFT_FAULT
                       else active + Warning.AIRCRAFT_FAULT
            logTransitions(next)
            active = next
        }
    }

    /**
     * One state frame from the bridge's callback.
     *
     * @param airborne the same airborne test the PLI publishes, computed in the same callback —
     *   passed in rather than re-derived so the two records cannot disagree.
     * @param relAltM height above the takeoff point, metres.
     * @param homeDistanceM distance from the home point, metres, or NaN when unknown.
     */
    fun onState(
        state: dji.common.flightcontroller.FlightControllerState,
        airborne: Boolean,
        relAltM: Double,
        homeDistanceM: Double,
    ) {
        val computed = compute(state, airborne, relAltM, homeDistanceM)
        synchronized(lock) {
            // AIRCRAFT_FAULT is owned by onDiagnostics; preserve whatever it last said.
            val next = if (Warning.AIRCRAFT_FAULT in active) computed + Warning.AIRCRAFT_FAULT
                       else computed
            if (next == active) return
            logTransitions(next)
            active = next
        }
    }

    /** Caller holds [lock]. Appears at W, clears at I — the record that lets a post-flight read
     *  say when a condition started and when it went away. */
    private fun logTransitions(next: Set<Warning>) {
        (next - active).forEach { AppLog.w(TAG, "warning ACTIVE: ${it.name} (${labelOf(it)})") }
        (active - next).forEach { AppLog.i(TAG, "warning cleared: ${it.name}") }
    }

    /** The display policy. Keep it boring: every rule is one add() with its condition. */
    private fun compute(
        state: dji.common.flightcontroller.FlightControllerState,
        airborne: Boolean,
        relAltM: Double,
        homeDistanceM: Double,
    ): Set<Warning> {
        val out = java.util.EnumSet.noneOf(Warning::class.java)

        // -------- red --------
        // The AIRCRAFT's own thresholds, not ours — this is the level it will act on, and it
        // reports the judgement directly rather than making us reconstruct it from a percentage.
        if (state.isLowerThanSeriousBatteryWarningThreshold) out.add(Warning.BATTERY_CRITICAL)
        if (state.batteryThresholdBehavior ==
            dji.common.flightcontroller.BatteryThresholdBehavior.LAND_IMMEDIATELY) {
            out.add(Warning.BATTERY_LANDING)
        }
        // Gated on airborne: an aircraft acquiring its first fix on the bench is normal, and a
        // red banner during every bench session would teach pilots to ignore red. Airborne
        // without a GPS hold is the state that genuinely drifts.
        if (airborne && (isAttitudeMode(state.flightMode) || isPoorGps(state.gpsSignalLevel))) {
            out.add(Warning.GPS_LOST)
        }

        // -------- amber --------
        // An aircraft flying itself with no banner reads as a runaway, so the pilot is told —
        // with the reason where MSDK offers one. It does not report an RC-loss return
        // distinctly, so anything that is not the battery case is the generic label.
        if (state.isGoingHome ||
            state.flightMode == dji.common.flightcontroller.FlightMode.GO_HOME) {
            if (state.batteryThresholdBehavior ==
                dji.common.flightcontroller.BatteryThresholdBehavior.GO_HOME) {
                out.add(Warning.RTH_BATTERY)
            } else {
                out.add(Warning.RETURNING_HOME)
            }
        }
        val wind = state.flightWindWarning
        if (wind == dji.common.flightcontroller.FlightWindWarning.LEVEL_1 ||
            wind == dji.common.flightcontroller.FlightWindWarning.LEVEL_2) {
            out.add(Warning.WIND)
        }
        if (state.isLowerThanBatteryWarningThreshold &&
            Warning.BATTERY_CRITICAL !in out) out.add(Warning.BATTERY_LOW)
        if (airborne && atLimit(relAltM, maxAltM)) out.add(Warning.AT_MAX_ALTITUDE)
        if (airborne && atLimit(homeDistanceM, maxRadiusM)) out.add(Warning.AT_MAX_RANGE)
        // Airborne-gated for the same reason as GPS: no home point before takeoff is "not ready
        // yet", and the aircraft refuses a one-touch takeoff on its own.
        if (airborne && !state.isHomeLocationSet) out.add(Warning.NO_HOME_POINT)

        return out
    }

    internal fun atLimit(value: Double, limit: Double?): Boolean {
        if (limit == null || !value.isFinite() || limit <= 0.0) return false
        val margin = maxOf(limit * LIMIT_MARGIN_FRACTION, LIMIT_MARGIN_MIN_M)
        return value >= limit - margin
    }

    /** Every attitude-mode variant: no GPS hold, so the aircraft drifts with the air mass. */
    internal fun isAttitudeMode(mode: dji.common.flightcontroller.FlightMode?): Boolean = when (mode) {
        dji.common.flightcontroller.FlightMode.ATTI,
        dji.common.flightcontroller.FlightMode.ATTI_COURSE_LOCK,
        dji.common.flightcontroller.FlightMode.ATTI_HOVER,
        dji.common.flightcontroller.FlightMode.ATTI_LIMITED,
        dji.common.flightcontroller.FlightMode.ATTI_LANDING -> true
        else -> false
    }

    /** LEVEL_0/1 and NONE are "too few satellites to hold position". UNKNOWN is not treated as
     *  bad — an absent reading must not raise a red banner on its own. */
    internal fun isPoorGps(level: dji.common.flightcontroller.GPSSignalLevel?): Boolean = when (level) {
        dji.common.flightcontroller.GPSSignalLevel.LEVEL_0,
        dji.common.flightcontroller.GPSSignalLevel.LEVEL_1,
        dji.common.flightcontroller.GPSSignalLevel.NONE -> true
        else -> false
    }

    private fun labelOf(w: Warning): String =
        if (w == Warning.AIRCRAFT_FAULT) {
            // Live text while the fault stands; the last real text while it rides out its hold.
            // "aircraft fault" is a last resort that should never be reached in practice — an
            // AIRCRAFT_FAULT only becomes active off a non-empty list.
            faultText.ifEmpty { lastFaultText }.ifEmpty { "aircraft fault" }
        } else w.label

    /** Polled from the flight screen's HUD tick. */
    fun display(): Display? = displayAt(System.currentTimeMillis())

    /** [display] with an injectable clock, so tests can step time. Same logic, one body. */
    internal fun displayAt(now: Long): Display? {
        synchronized(lock) {
            val worst = active.minOrNull()
            val cur = shown
            val held = cur != null && now - shownAtMs < HOLD_MS
            // A worse warning takes the banner immediately; otherwise the current one keeps it
            // for the hold, so a flicker at the callback rate cannot make the text strobe.
            val next = when {
                worst == null -> if (held) cur else null
                cur == null || !held || worst < cur -> worst
                else -> cur
            }
            if (next != cur) { shown = next; shownAtMs = now }
            val show = shown ?: return null
            // "+N" counts what is stacked behind this one, from the LIVE set — the shown warning
            // may itself have cleared already and just be riding out its hold.
            val others = active.count { it != show }
            val label = labelOf(show)
            val text = if (others > 0) "$label  +$others" else label
            return Display(text, show.red)
        }
    }

    /**
     * New flight screen or aircraft cycle — drop the hold state so a stale banner from the last
     * session cannot greet the pilot. The active set rebuilds within one frame.
     *
     * LOGGED, unlike the sibling's. Without the line, a flight-screen re-entry produces a second
     * "warning ACTIVE" with no "cleared" between, and a post-flight read cannot tell a condition
     * that persisted from one that went away and came back. (Open finding #2 on the Autel tree.)
     */
    fun reset() {
        synchronized(lock) {
            if (active.isNotEmpty()) {
                AppLog.i(TAG, "warnings reset (${active.size} active discarded: " +
                    active.joinToString(", ") { it.name } + ")")
            }
            active = emptySet()
            faultText = ""
            lastFaultText = ""
            shown = null
            shownAtMs = 0L
        }
    }

    private const val TAG = "FlightWarnings"
}

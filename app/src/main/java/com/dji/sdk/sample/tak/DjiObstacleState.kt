package com.dji.sdk.sample.tak

import android.content.Context
import com.dji.sdk.sample.internal.controller.DJISampleApplication
import com.taklite.util.AppLog
import dji.common.error.DJIError
import dji.common.flightcontroller.VisionDetectionState
import dji.common.flightcontroller.VisionSensorPosition
import dji.common.util.CommonCallbacks
import dji.sdk.flightcontroller.FlightAssistant

/**
 * Obstacle-avoidance state, cached process-wide. The DJI counterpart of the Autel port's
 * `AutelAvoidance` (`takpilot-autel_v1-2/.../tak/AutelAvoidance.kt`), and deliberately the same
 * shape so the two ports stay readable side by side.
 *
 * WHY THIS EXISTS. Until now this app was blind to avoidance: it never read the state, never
 * displayed it, and never set it. Avoidance was therefore in whatever state something else left
 * it — DJI Fly, or the aircraft's default — and nothing on any screen told the pilot which. A
 * disabled avoidance system looks exactly like an enabled one right up until it doesn't stop.
 *
 * **Where this port is BETTER off than the Autel one: units are documented.** Autel's radar
 * returns bare floats whose scale had to be inferred and then field-validated against real
 * obstacles. DJI's [VisionDetectionState.getObstacleDistanceInMeters] and
 * [dji.common.flightcontroller.ObstacleDetectionSector.getObstacleDistanceInMeters] are METRES by
 * API contract, so there is no unit guess anywhere in this file and no single magic constant
 * holding the display up.
 *
 * **Where it is worse off: coverage varies by airframe, so absence must not read as safety.**
 * [VisionSensorPosition] covers NOSE/TAIL/LEFT/RIGHT only. The Air 2S has forward, backward,
 * upward and downward sensors and NO lateral ones; the Mini 2 has no obstacle sensors at all
 * (downward vision is for landing, not avoidance). So a face that never reports means "this
 * aircraft cannot see that way", NOT "that way is clear" — which is why [sensingAircraft] exists
 * and why the flight screen must say nothing rather than imply clearance.
 *
 * Up/down distances come from a different feed ([FlightAssistant.setVisualPerceptionInformationCallback],
 * `PerceptionInformation.getUpwardObstacleDistance`) whose units the SDK does NOT document — they
 * are plain ints. Those are therefore LOGGED ONLY and deliberately not drawn yet; see
 * [logPerception]. Putting an unverified number on a safety display is how the Autel port's
 * centimetre assumption became load-bearing before anyone had checked it, and that one got lucky.
 */
object DjiObstacleState {
    private const val TAG = "TP2Obstacle"

    /** Per-face nearest obstacle in METRES, keyed by sensor position. Absent = that face has not
     *  reported, which is not the same as clear. */
    @Volatile
    var faces: Map<VisionSensorPosition, Float> = emptyMap()
        private set

    /** True once ANY vision face has reported a usable reading — i.e. this airframe actually has
     *  obstacle sensors and they are alive. Stays false forever on a Mini 2, which is exactly how
     *  the display knows to stay silent instead of drawing a reassuring blank. */
    @Volatile
    var sensingAircraft = false
        private set

    /** Notified whenever [faces] changes (on DJI's callback thread — marshal it yourself). */
    @Volatile
    var onChanged: (() -> Unit)? = null

    // ---- Live avoidance switch state, as the AIRCRAFT reports it ----
    // Null means "not read yet", NOT "off". The difference matters in front of a pilot.
    @Volatile var collisionAvoidance: Boolean? = null; private set
    @Volatile var rthAvoidance: Boolean? = null; private set
    @Volatile var landingProtection: Boolean? = null; private set

    private var assistant: FlightAssistant? = null

    /** Wired from [DjiSdkBridge] on every (re)connect — callback registrations do not survive a
     *  product cycle. */
    fun onProductConnected(context: Context) {
        val fc = runCatching {
            DJISampleApplication.getAircraftInstance()?.flightController
        }.getOrNull()
        // isFlightAssistantSupported is the airframe's own answer to "do I have obstacle
        // sensors" — the Mini 2 says no. Asking it beats inferring from a null assistant, and it
        // keeps the Mini 2 path a normal expected outcome rather than a swallowed failure.
        val supported = runCatching { fc?.isFlightAssistantSupported == true }.getOrDefault(false)
        val fa = if (supported) runCatching { fc?.flightAssistant }.getOrNull() else null
        if (fa == null) {
            AppLog.i(TAG, "aircraft reports no flight assistant (supported=$supported) — " +
                "obstacle sensing unavailable, display stays hidden")
            return
        }
        assistant = fa
        runCatching {
            fa.setVisionDetectionStateUpdatedCallback(
                VisionDetectionState.Callback { state -> onVisionState(state) })
            AppLog.i(TAG, "vision-detection callback armed")
        }.onFailure { AppLog.w(TAG, "vision-detection callback failed: ${it.message}") }

        runCatching {
            fa.setVisualPerceptionInformationCallback(
                object : CommonCallbacks.CompletionCallbackWith<dji.common.flightcontroller.flightassistant.PerceptionInformation> {
                    override fun onSuccess(info: dji.common.flightcontroller.flightassistant.PerceptionInformation?) {
                        info?.let { logPerception(it) }
                    }
                    override fun onFailure(error: DJIError?) {}
                })
        }.onFailure { AppLog.i(TAG, "no vertical perception feed: ${it.message}") }

        readSwitches(fa)
        applyAtConnect(context, fa)
    }

    fun onProductDisconnected() {
        assistant = null
        faces = emptyMap()
        sensingAircraft = false
        collisionAvoidance = null; rthAvoidance = null; landingProtection = null
        appliedForThisConnect = false
        lastLoggedNear = -1f
        lastPerceptionLogMs = 0L
        runCatching { onChanged?.invoke() }
    }

    /**
     * One face's worth of update. DJI pushes each [VisionSensorPosition] separately, so this
     * merges into the map rather than replacing it — replacing would make every face except the
     * most recent vanish at the push rate, which is the same strobing failure the Autel view had
     * to fix in its own update path.
     */
    private fun onVisionState(state: VisionDetectionState?) {
        state ?: return
        val pos = state.position ?: return
        if (pos == VisionSensorPosition.UNKNOWN) return

        // isDisabled/isSensorBeingUsed distinguish "this way is clear" from "nothing is looking
        // that way". A disabled sensor must DROP the face, never report a distance.
        if (state.isDisabled || !state.isSensorBeingUsed) {
            if (faces.containsKey(pos)) {
                faces = faces - pos
                runCatching { onChanged?.invoke() }
            }
            return
        }

        // Prefer the nearest SECTOR over the whole-face aggregate: a face reports one distance
        // for the closest thing anywhere across its arc, but the sectors say the same thing at
        // finer grain and the minimum of them is what a pilot is about to hit.
        val sectorMin = state.detectionSectors
            ?.mapNotNull { it?.obstacleDistanceInMeters?.takeIf { d -> d > 0f } }
            ?.minOrNull()
        val faceDist = state.obstacleDistanceInMeters.toFloat().takeIf { it > 0f }
        val nearest = listOfNotNull(sectorMin, faceDist).minOrNull() ?: run {
            if (faces.containsKey(pos)) {
                faces = faces - pos
                runCatching { onChanged?.invoke() }
            }
            return
        }

        sensingAircraft = true
        faces = faces + (pos to nearest)
        logNearIfNotable(pos, nearest, state)
        runCatching { onChanged?.invoke() }
    }

    /** Nearest obstacle on any face in metres, or null if nothing is reporting. */
    fun nearestMeters(): Float? = faces.values.minOrNull()

    // ---- Logging, biased toward what matters ----
    //
    // Same rule the Autel port settled on after its 20-sample cap turned out to spend every
    // sample sitting on the ground: log what is CLOSE, immediately, and rate-limit the rest.
    @Volatile private var lastLoggedNear = -1f
    @Volatile private var lastNearLogMs = 0L

    private fun logNearIfNotable(pos: VisionSensorPosition, m: Float, state: VisionDetectionState) {
        if (m > LOG_NEAR_M) return
        val now = android.os.SystemClock.elapsedRealtime()
        // Log on meaningful movement or on a slow heartbeat, so a steady hover near a wall does
        // not fill the log while a genuine approach still gets sampled.
        if (now - lastNearLogMs < NEAR_MIN_GAP_MS && kotlin.math.abs(m - lastLoggedNear) < 0.5f) return
        lastNearLogMs = now
        lastLoggedNear = m
        AppLog.i(TAG, "obstacle $pos ${"%.1f".format(m)}m warn=${state.systemWarning} " +
            "sectors=${state.detectionSectors?.size ?: 0}")
    }

    /**
     * Up/down distances, logged and NOT displayed.
     *
     * `PerceptionInformation.getUpwardObstacleDistance()` returns a bare int with no documented
     * unit. Magnitudes will say whether it is centimetres, millimetres or decimetres, but a guess
     * on a safety display is not acceptable — so this exists to gather the evidence that would
     * justify drawing it later. Compare a logged value against a known distance (hover a measured
     * height under a ceiling) before wiring it to the view.
     */
    @Volatile private var lastPerceptionLogMs = 0L

    private fun logPerception(info: dji.common.flightcontroller.flightassistant.PerceptionInformation) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPerceptionLogMs < PERCEPTION_GAP_MS) return
        lastPerceptionLogMs = now
        AppLog.i(TAG, "perception (UNITS UNVERIFIED — not displayed): " +
            "up=${info.upwardObstacleDistance} down=${info.downwardObstacleDistance}")
    }

    /** Below this a reading is worth a log line. 15 m, matching the Autel port's threshold. */
    private const val LOG_NEAR_M = 15f
    private const val NEAR_MIN_GAP_MS = 500L
    private const val PERCEPTION_GAP_MS = 10_000L

    // ---- Pre-Flight's saved intent, enforced on every connect ----

    private const val PREFS = "takpilot2_avoid"
    private const val KEY_SYSTEM = "avoid_system"
    private const val KEY_RTH = "avoid_rth"
    private const val KEY_LANDING = "avoid_landing"

    /** Defaults are ON. An install nobody has configured must err toward protection. */
    fun savedSystem(c: Context) = prefs(c).getBoolean(KEY_SYSTEM, true)
    fun savedRth(c: Context) = prefs(c).getBoolean(KEY_RTH, true)
    fun savedLanding(c: Context) = prefs(c).getBoolean(KEY_LANDING, true)

    fun saveIntent(c: Context, system: Boolean, rth: Boolean, landing: Boolean) {
        prefs(c).edit().putBoolean(KEY_SYSTEM, system).putBoolean(KEY_RTH, rth)
            .putBoolean(KEY_LANDING, landing).apply()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Reads what the aircraft currently has, so the pilot can be shown the real state and so
     *  [applyAtConnect] only writes switches that are actually wrong. */
    private fun readSwitches(fa: FlightAssistant) {
        fun boolRead(name: String, set: (Boolean) -> Unit, call: (CommonCallbacks.CompletionCallbackWith<Boolean>) -> Unit) {
            runCatching {
                call(object : CommonCallbacks.CompletionCallbackWith<Boolean> {
                    override fun onSuccess(value: Boolean?) {
                        value?.let(set)
                        AppLog.i(TAG, "$name = $value")
                    }
                    override fun onFailure(error: DJIError?) {
                        // Common and harmless: airframes without the feature reject the getter.
                        AppLog.i(TAG, "$name unavailable: ${error?.description}")
                    }
                })
            }
        }
        boolRead("collisionAvoidance", { collisionAvoidance = it }) { fa.getCollisionAvoidanceEnabled(it) }
        boolRead("rthAvoidance", { rthAvoidance = it }) { fa.getRTHObstacleAvoidanceEnabled(it) }
        boolRead("landingProtection", { landingProtection = it }) { fa.getLandingProtectionEnabled(it) }
    }

    @Volatile private var appliedForThisConnect = false

    /**
     * Enforces the Pre-Flight selection on the aircraft, once per connect.
     *
     * Pushing a saved safety setting is a deliberate trade, taken from the Autel port: leaving
     * "whatever DJI Fly last set" is not neutral, it is UNKNOWN, and a pilot who cannot tell
     * whether avoidance is on is worse off than one flying a state the app enforced and displays.
     * Enforcement is only safe BECAUSE the state is now visible on the flight screen.
     *
     * Deferred by [APPLY_DELAY_MS] so the getters above have answered — writing blind would defeat
     * the "only correct what is wrong" rule and cost a round trip per switch for nothing.
     */
    private fun applyAtConnect(context: Context, fa: FlightAssistant) {
        if (appliedForThisConnect) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (appliedForThisConnect) return@postDelayed
            // NEVER rewrite a safety switch on an aircraft that is already flying. Enforcement is
            // a pre-flight act: the pilot launches having seen what was applied. Changing how the
            // aircraft avoids obstacles underneath it in the air is the opposite of the point.
            // Read straight off the flight controller rather than via the HUD — DroneTakBridge
            // only runs on the flight screen, and this fires at product connect, which is usually
            // before the pilot has got there.
            val st = runCatching {
                DJISampleApplication.getAircraftInstance()?.flightController?.state
            }.getOrNull()
            if (st != null && (st.isFlying || st.areMotorsOn())) {
                AppLog.w(TAG, "aircraft is flying/armed — SKIPPING avoidance enforcement this connect")
                return@postDelayed
            }
            appliedForThisConnect = true
            enforce(fa, "collisionAvoidance", savedSystem(context), collisionAvoidance) { v, cb ->
                fa.setCollisionAvoidanceEnabled(v, cb)
            }
            enforce(fa, "rthAvoidance", savedRth(context), rthAvoidance) { v, cb ->
                fa.setRTHObstacleAvoidanceEnabled(v, cb)
            }
            enforce(fa, "landingProtection", savedLanding(context), landingProtection) { v, cb ->
                fa.setLandingProtectionEnabled(v, cb)
            }
        }, APPLY_DELAY_MS)
    }

    /** Writes one switch only if the aircraft's value actually differs. A needless write costs a
     *  round trip and, on some airframes, an audible acknowledgement. A null `actual` means the
     *  getter never answered — write it, because unknown is not a state worth preserving. */
    private fun enforce(
        fa: FlightAssistant,
        name: String,
        desired: Boolean,
        actual: Boolean?,
        call: (Boolean, CommonCallbacks.CompletionCallback<DJIError>) -> Unit,
    ) {
        if (actual == desired) {
            AppLog.i(TAG, "$name already $desired — no write")
            return
        }
        AppLog.i(TAG, "enforcing $name -> $desired (aircraft had $actual)")
        runCatching {
            call(desired) { err ->
                AppLog.i(TAG, "set $name=$desired: ${err?.description ?: "OK"}")
                if (err == null) when (name) {
                    "collisionAvoidance" -> collisionAvoidance = desired
                    "rthAvoidance" -> rthAvoidance = desired
                    "landingProtection" -> landingProtection = desired
                }
            }
        }.onFailure { AppLog.w(TAG, "set $name threw: ${it.message}") }
    }

    /** Long enough for the three getters to answer before enforcement compares against them. */
    private const val APPLY_DELAY_MS = 4500L
}

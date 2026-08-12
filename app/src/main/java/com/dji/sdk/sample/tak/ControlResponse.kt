package com.dji.sdk.sample.tak

import android.content.Context
import com.dji.sdk.sample.internal.controller.DJISampleApplication
import com.taklite.util.AppLog
import dji.common.util.CommonCallbacks

/**
 * How fast the controller drives the camera: one FIXED number per response mode, written to the
 * aircraft at connect and on an explicit change.
 *
 * ⚠ WHY FIXED, AND NOT A MULTIPLIER. This replaces `DroneTakBridge.applyPitchSpeed`, which read
 * the gimbal's current speed and multiplied it by 1.5. That compounded, because the value it read
 * was the one it had written last time. The 2026-08-12 logs caught it climbing on every connect:
 *
 *     22 -> 33 -> 49 -> 73 -> 100        (range 1..100, so it ended pinned at the ceiling)
 *
 * The camera therefore handled differently on every flight, and a pilot had no way to know which
 * speed they had today. A fixed target is the whole point: the same selection gives the same feel
 * every time, no matter what the last session, the DJI app, or a firmware reset left behind.
 *
 * ⚠ CLAMPED, NOT BLINDLY WRITTEN. The numbers below suit the Mini 2, whose gimbal reports 1..100.
 * The next airframe may report something else entirely, so each value is clamped to the range the
 * gimbal itself declares through [dji.common.gimbal.CapabilityKey.PITCH_CONTROLLER_MAX_SPEED]. A
 * model that reports no range at all is left alone rather than guessed at.
 *
 * ⚠ THE VALUES ARE A STARTING POINT, NOT A MEASUREMENT. Nothing here has been tuned in flight.
 * 22 is the lowest speed we ever observed this airframe holding, so NORMAL sits a little above it
 * and PRECISION well below. They are two named constants precisely so they are easy to move once
 * a pilot has felt them — the read-back line in Pre-Flight reports what the aircraft actually
 * took, and that is the number the next adjustment starts from.
 */
object ControlResponse {

    private const val TAG = "TP2Control"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_MODE = "control_response_precision"

    /** Gimbal pitch speed for each mode. Mini 2 scale (1..100); clamped per airframe on write. */
    private const val NORMAL_PITCH_SPEED = 35
    private const val PRECISION_PITCH_SPEED = 15

    enum class Mode(val label: String, val pitchSpeed: Int) {
        NORMAL("Normal", NORMAL_PITCH_SPEED),
        PRECISION("Precision", PRECISION_PITCH_SPEED),
    }

    /** What the AIRCRAFT reports holding, read back after a write. Null until one lands. */
    @Volatile var aircraftPitchSpeed: Int? = null
        private set

    fun saved(context: Context): Mode =
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_MODE, false)) Mode.PRECISION else Mode.NORMAL

    fun save(context: Context, mode: Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_MODE, mode == Mode.PRECISION).apply()
        AppLog.i(TAG, "control response set to ${mode.label}")
    }

    /**
     * Pushes the saved mode to the gimbal, then reads back what it took.
     *
     * Safe to call when no aircraft is connected — it returns quietly. Called from the gimbal's
     * first state callback per connect (the reliable "gimbal is up" signal) and whenever the
     * pilot changes the setting. Never on a timer: this is a control-feel write, and the rule
     * about not writing to the aircraft on a clock applies to it as much as to flight limits.
     */
    fun apply(context: Context, onDone: (() -> Unit)? = null) {
        val mode = saved(context)
        val gimbal = DJISampleApplication.getAircraftInstance()?.gimbals?.firstOrNull()
        if (gimbal == null) {
            AppLog.v(TAG, "no gimbal — control response not applied")
            onDone?.invoke()
            return
        }

        val range = try {
            gimbal.capabilities?.get(dji.common.gimbal.CapabilityKey.PITCH_CONTROLLER_MAX_SPEED)
                as? dji.common.util.DJIParamMinMaxCapability
        } catch (t: Throwable) {
            AppLog.w(TAG, "gimbal pitch-speed capability check failed: ${t.message}")
            null
        }
        if (range == null) {
            AppLog.i(TAG, "gimbal does not report PITCH_CONTROLLER_MAX_SPEED — leaving speed alone")
            onDone?.invoke()
            return
        }
        val min = range.min?.toInt()
        val max = range.max?.toInt()
        if (min == null || max == null) {
            AppLog.i(TAG, "gimbal reported an incomplete pitch-speed range — leaving speed alone")
            onDone?.invoke()
            return
        }

        val target = mode.pitchSpeed.coerceIn(min, max)
        if (target != mode.pitchSpeed) {
            // Worth its own line: it means the constants above do not suit this airframe, which
            // is the first thing to know when the feel is wrong on a model that is not a Mini 2.
            AppLog.w(TAG, "${mode.label} pitch speed ${mode.pitchSpeed} is outside this " +
                "gimbal's range $min..$max — clamped to $target")
        }
        // ⚠ ONE-SHOT. This gimbal's completion callback fires TWICE for a single write — seen on
        // the 2026-08-12 flight, where every write logged its result and then logged it again
        // about 90 ms later, after the read-back had already answered. It looked like the bridge
        // starting twice; the bridge starts once, and the pitch-range call in the same block
        // runs once. Without this guard the read-back is issued twice for each write.
        val handled = java.util.concurrent.atomic.AtomicBoolean(false)
        gimbal.setControllerMaxSpeed(dji.common.gimbal.Axis.PITCH, target) { err ->
            if (!handled.compareAndSet(false, true)) {
                AppLog.v(TAG, "duplicate gimbal completion callback ignored")
                return@setControllerMaxSpeed
            }
            if (err == null) {
                AppLog.i(TAG, "gimbal pitch speed set to $target (${mode.label}, range $min..$max)")
            } else {
                AppLog.w(TAG, "gimbal pitch speed set to $target failed: ${err.description}")
            }
            readBack(gimbal, onDone)
        }
    }

    /** The answer, not the request. Rule 4: an onSuccess from the gimbal is not proof. */
    private fun readBack(gimbal: dji.sdk.gimbal.Gimbal, onDone: (() -> Unit)?) {
        runCatching {
            gimbal.getControllerMaxSpeed(
                dji.common.gimbal.Axis.PITCH,
                object : CommonCallbacks.CompletionCallbackWith<Int> {
                    override fun onSuccess(value: Int?) {
                        aircraftPitchSpeed = value
                        AppLog.i(TAG, "aircraft gimbal pitch speed is now: $value")
                        onDone?.invoke()
                    }
                    override fun onFailure(error: dji.common.error.DJIError?) {
                        AppLog.w(TAG, "gimbal pitch-speed read-back failed: ${error?.description}")
                        onDone?.invoke()
                    }
                })
        }.onFailure {
            AppLog.w(TAG, "gimbal pitch-speed read-back threw: ${it.message}")
            onDone?.invoke()
        }
    }

    /**
     * One-shot discovery for the yaw half of this setting, which is NOT wired up yet.
     *
     * `FlightController.setAircraftHeadingTurningSmoothness` takes a
     * [dji.common.remotecontroller.HardwareState.FlightModeSwitch], and the SDK names those
     * POSITION_ONE/TWO/THREE with no "Cine" among them. Which position is Cine is a convention,
     * and this project does not guess at that class of mapping — a wrong stick-mode guess swaps
     * throttle and pitch, and this is the same kind of error.
     *
     * So instead of assuming, this logs what the aircraft holds for all three positions. Put the
     * physical switch in Cine and the position that matches the smoothest number is the answer.
     */
    fun logYawSmoothness() {
        val fc = DJISampleApplication.getAircraftInstance()?.flightController ?: return

        // ⚠ NO RC CALLBACK HERE, deliberately. Reading the live switch position would answer
        // "which POSITION is Cine" directly, but it needs setHardwareStateCallback, and this
        // project reserves SDK listener slots for the bridges: a slot holds one client, a second
        // registration silently replaces the first, and detaching an RC listener killed the
        // signal indicator for a whole process on the sibling. A diagnostic is not worth that.
        //
        // It is also unnecessary. The three values below identify Cine on their own — it is the
        // smoothest of them — so the yaw target can be taken from the aircraft's own numbers
        // without ever mapping a position to a name.

        for (pos in dji.common.remotecontroller.HardwareState.FlightModeSwitch.values()) {
            runCatching {
                fc.getAircraftHeadingTurningSmoothness(pos,
                    object : CommonCallbacks.CompletionCallbackWith<Int> {
                        override fun onSuccess(value: Int?) {
                            AppLog.i(TAG, "yaw smoothness at $pos: $value")
                        }
                        override fun onFailure(error: dji.common.error.DJIError?) {
                            AppLog.i(TAG, "yaw smoothness at $pos unavailable: ${error?.description}")
                        }
                    })
            }
        }
    }
}

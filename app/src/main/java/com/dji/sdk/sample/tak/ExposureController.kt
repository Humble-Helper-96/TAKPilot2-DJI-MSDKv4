package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog
import dji.common.camera.SettingsDefinitions.CameraMode
import dji.common.camera.SettingsDefinitions.ExposureCompensation
import dji.common.camera.SettingsDefinitions.ExposureMode
import dji.common.camera.SettingsDefinitions.FlatCameraMode
import dji.common.camera.SettingsDefinitions.ISO
import dji.common.camera.SettingsDefinitions.MeteringMode
import dji.common.camera.SettingsDefinitions.ShutterSpeed
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.sdk.camera.Camera

/**
 * Forces a consistent auto-exposure setup on the camera so FPV/recording adapts to changing
 * light instead of running on whatever DJI Fly last left it in (which is why the feed didn't
 * brighten/darken before — nothing in the app ever set an exposure mode).
 *
 * Strategy, decided for the Mini 2 (fixed f/2.8 aperture — so ISO + shutter are the only real
 * levers): SHUTTER_PRIORITY with a fixed [SHUTTER_FLOOR] and auto-ISO. Pinning the shutter
 * keeps motion sharp; the camera raises ISO automatically (and aggressively) as light dims to
 * hold brightness. EV compensation biases that auto-ISO target and is pilot-adjustable via the
 * flight-screen slider ([EV_SLIDER] / [setEvAt]). All SDK results are logged (TP2Exposure) for
 * the planned debug screen.
 *
 * Note: field-testing showed the camera rejects EV comp beyond +3.0 in this mode ("set param
 * failed"), so the pilot control is deliberately capped at ±2.0 — comfortably inside that.
 */
object ExposureController {
    private const val TAG = "TP2Exposure"
    private const val PREFS = "takpilot2_tak"
    private const val KEY_EV = "exposure_ev"

    /** Fixed shutter for shutter-priority. 1/60 chosen for the Mini 2's wide lens — it tolerates
     *  a slower shutter without visible motion blur, keeping ISO (and noise) lower in dim light
     *  than a faster floor would. */
    val SHUTTER_FLOOR: ShutterSpeed = ShutterSpeed.SHUTTER_SPEED_1_60

    private val EV_ZERO = ExposureCompensation.N_0_0

    /** Every real EV value in order (N_5_0 .. P_5_0, 1/3-stop steps), excluding FIXED/UNKNOWN. */
    private val EV_ALL: List<ExposureCompensation> = ExposureCompensation.values().filter {
        it != ExposureCompensation.FIXED && it != ExposureCompensation.UNKNOWN
    }

    /** The pilot slider's range: -2.0 .. +2.0 EV in 1/3 stops (13 steps). */
    val EV_SLIDER: List<ExposureCompensation> = EV_ALL.filter {
        val i = EV_ALL.indexOf(it)
        i in EV_ALL.indexOf(ExposureCompensation.N_2_0)..EV_ALL.indexOf(ExposureCompensation.P_2_0)
    }

    val sliderMax: Int get() = EV_SLIDER.size - 1

    /** Hidden brightness bias, in 1/3-stop steps, added on top of whatever the pilot sees/sets.
     *  Field-flown 2026-07-22: PROGRAM auto-exposure still ran a touch dark, so the app asked
     *  for +1/3 EV more than the slider showed. Field-flown again 2026-07-23: still too dim at
     *  +1/3, bumped to a full +1.0 EV bias. Retuned 2026-07-25 to +2/3 EV (now paired with
     *  forcing CENTER-weighted metering below — see [applyExposureSettings]). The slider itself
     *  still reads/persists/displays -2.0..+2.0 with 0.0 default — this bias is invisible to
     *  the pilot and only applied at the point we actually talk to the camera. E.g. slider
     *  "0.0" really requests +2/3 EV; slider "+2.0" really requests +2.67 EV; slider "-2.0"
     *  really requests -1.33 EV (see class doc's +3.0 cap note on how far this can go). */
    private const val HIDDEN_BIAS_STEPS = 2

    private fun biased(nominal: ExposureCompensation): ExposureCompensation {
        val i = EV_ALL.indexOf(nominal)
        return EV_ALL[(i + HIDDEN_BIAS_STEPS).coerceIn(0, EV_ALL.size - 1)]
    }

    /** Stored EV, clamped into the slider range (older builds could persist an out-of-range
     *  value before the ±2 cap existed — clamp so applyDefaults never sends a rejected value). */
    fun savedEv(context: Context): ExposureCompensation {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EV, null)
        val stored = name?.let { runCatching { ExposureCompensation.valueOf(it) }.getOrNull() } ?: EV_ZERO
        val fi = EV_ALL.indexOf(stored)
        if (fi < 0) return EV_ZERO
        val lo = EV_ALL.indexOf(EV_SLIDER.first())
        val hi = EV_ALL.indexOf(EV_SLIDER.last())
        return EV_ALL[fi.coerceIn(lo, hi)]
    }

    private fun saveEv(context: Context, ev: ExposureCompensation) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_EV, ev.name).apply()
    }

    /** Slider position (0..[sliderMax]) matching the stored EV. */
    fun savedSliderIndex(context: Context): Int = EV_SLIDER.indexOf(savedEv(context)).coerceAtLeast(0)

    fun labelAt(index: Int): String = evLabel(EV_SLIDER[index.coerceIn(0, sliderMax)])

    private fun evLabel(ev: ExposureCompensation): String = when {
        ev == EV_ZERO -> "0.0"
        ev.name.startsWith("N_") -> "-" + ev.name.removePrefix("N_").replace('_', '.')
        ev.name.startsWith("P_") -> "+" + ev.name.removePrefix("P_").replace('_', '.')
        else -> "0.0"
    }

    /** Human shutter label from the enum, e.g. SHUTTER_SPEED_1_60 -> "1/60",
     *  SHUTTER_SPEED_1_12_DOT_5 -> "1/12.5". Also used for the live-readout display. */
    fun shutterLabel(s: ShutterSpeed): String {
        val raw = s.name.removePrefix("SHUTTER_SPEED_").replace("_DOT_", ".")
        return if (raw.startsWith("1_")) "1/" + raw.removePrefix("1_").replace('_', '.')
        else raw.replace('_', '.')
    }

    /** Push the exposure setup to the camera (called on connect). Switches the camera to VIDEO
     *  mode FIRST — the Mini 2 boots in photo mode (RC trigger snaps stills), and video-exposure
     *  settings don't drive the live FPV until the camera is in video mode. The actual metering/
     *  exposure-mode/EV push is [applyExposureSettings] — factored out so [onShootPhotoTapped]
     *  in the flight screen can apply the IDENTICAL settings after switching to PHOTO_SINGLE to
     *  shoot, so a snapped photo's total EV always matches what the live video was showing. */
    fun applyDefaults(context: Context, camera: Camera) {
        AppLog.i(TAG, "applyDefaults: VIDEO mode -> ${MeteringMode.CENTER}, PROGRAM (full auto), ev=${savedEv(context)}")
        val afterVideoMode = CommonCallbacks.CompletionCallback<DJIError> { mErr ->
            AppLog.i(TAG, "set VIDEO mode: ${mErr?.description ?: "OK"}")
            applyExposureSettings(context, camera)
        }
        if (camera.isFlatCameraModeSupported) {
            camera.setFlatMode(FlatCameraMode.VIDEO_NORMAL, afterVideoMode)
        } else {
            camera.setMode(CameraMode.RECORD_VIDEO, afterVideoMode)
        }
    }

    /** Metering mode + exposure mode + biased EV — independent of flat/camera mode, so calling
     *  this identically right after switching to VIDEO_NORMAL or right after switching to
     *  PHOTO_SINGLE guarantees the same total exposure (mode + compensation) either way, rather
     *  than photo mode drifting to whatever its own separately-persisted settings were.
     *
     *  CENTER-weighted metering (not SPOT or AVERAGE): meters the whole frame but favors the
     *  middle, which is the general-purpose choice DJI itself defaults most aircraft to — SPOT
     *  would chase whatever's under the crosshair (bad for a wide establishing shot), AVERAGE
     *  gives the sky and ground equal weight (blows out ground exposure on a high-contrast
     *  horizon). Requested 2026-07-25 alongside retuning the hidden EV bias to +2/3.
     *
     *  Uses PROGRAM (full auto) exposure: the camera controls both ISO and shutter and adapts
     *  to light on its own. We tried SHUTTER_PRIORITY + fixed 1/60 + auto-ISO first (to guarantee
     *  motion-freezing shutter), and although the camera reported it applied AND read it back
     *  correctly, the live feed never actually auto-exposed — ISO/shutter stayed frozen and EV
     *  did nothing. PROGRAM is the most basic auto mode; if the Mini 2 honors any live AE it's
     *  this. ISO/shutter floor+ceiling limits and the motion-blur tradeoff come later, once
     *  auto-exposure is confirmed working at all. EV compensation still biases it. */
    fun applyExposureSettings(context: Context, camera: Camera, onDone: () -> Unit = {}) {
        camera.setMeteringMode(MeteringMode.CENTER) { e0 ->
            AppLog.i(TAG, "setMeteringMode(CENTER): ${e0?.description ?: "OK"}")
            camera.setExposureMode(ExposureMode.PROGRAM) { e1 ->
                AppLog.i(TAG, "setExposureMode(PROGRAM): ${e1?.description ?: "OK"}")
                if (e1 != null) { onDone(); return@setExposureMode }
                val ev = biased(savedEv(context))
                camera.setExposureCompensation(ev) { e2 ->
                    AppLog.i(TAG, "setExposureCompensation($ev) [biased]: ${e2?.description ?: "OK"}")
                    logReadback(camera)
                    onDone()
                }
            }
        }
    }

    /** Read back what the camera actually applied — the definitive check that shutter-priority
     *  and our 1/60 stuck (vs. reporting OK but silently reverting). */
    private fun logReadback(camera: Camera) {
        camera.getExposureMode(object : CommonCallbacks.CompletionCallbackWith<ExposureMode> {
            override fun onSuccess(m: ExposureMode) { AppLog.i(TAG, "readback exposureMode=$m") }
            override fun onFailure(e: DJIError) { AppLog.i(TAG, "readback exposureMode failed: ${e.description}") }
        })
        camera.getShutterSpeed(object : CommonCallbacks.CompletionCallbackWith<ShutterSpeed> {
            override fun onSuccess(s: ShutterSpeed) { AppLog.i(TAG, "readback shutter=$s") }
            override fun onFailure(e: DJIError) { AppLog.i(TAG, "readback shutter failed: ${e.description}") }
        })
        camera.getISO(object : CommonCallbacks.CompletionCallbackWith<ISO> {
            override fun onSuccess(iso: ISO) { AppLog.i(TAG, "readback iso=$iso") }
            override fun onFailure(e: DJIError) { AppLog.i(TAG, "readback iso failed: ${e.description}") }
        })
    }

    /** Apply the EV at slider [index] (nominal, as displayed), persisting only on success. The
     *  camera is actually sent [biased] on top of it. [onDone] gets the nominal label so the UI
     *  reflects what the pilot set (or persists-and-shows if the camera is unavailable). */
    fun setEvAt(context: Context, camera: Camera?, index: Int, onDone: (String) -> Unit) {
        val nominal = EV_SLIDER[index.coerceIn(0, sliderMax)]
        if (camera == null) {
            saveEv(context, nominal)
            onDone(evLabel(nominal))
            return
        }
        val ev = biased(nominal)
        camera.setExposureCompensation(ev) { e ->
            AppLog.i(TAG, "setExposureCompensation($ev) [biased from $nominal]: ${e?.description ?: "OK"}")
            if (e == null) saveEv(context, nominal)
            onDone(evLabel(nominal))
        }
    }
}

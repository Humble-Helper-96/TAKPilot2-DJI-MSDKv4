package com.dji.sdk.sample.tak

/**
 * Turns what the camera reports into what the HUD's media-mode readout says. Specification §4.3.
 *
 * ⚠ **THE READOUT SHOWS WHAT THE CAMERA HOLDS, NEVER WHAT WAS ASKED.** Both inputs come off the
 * `SystemState` push [DroneTakBridge] already subscribes to (safety rule 1 — one client per
 * listener slot). This object only decides the words.
 *
 * Two mode systems exist on this SDK and the Mini 2 uses the FLAT one: `PHOTO_SINGLE`,
 * `VIDEO_NORMAL` and a score of others. The legacy `CameraMode` is read only when the flat
 * mode is `UNKNOWN`, because a camera that does not support flat mode reports exactly that.
 *
 * ⚠ **A MODE THIS APPLICATION DOES NOT HANDLE IS SHOWN BY NAME** (§4.3), never mapped onto one
 * of the two it knows. `PHOTO_BURST` is not `PHOTO`: the shutter pill would take one picture
 * and the camera would take several. Inventing a label is how a readout starts lying.
 *
 * ⚠ **UNKNOWN IS ITS OWN STATE** (§4.6). Null until the camera answers; the view draws it amber.
 *
 * Takes the enums' NAMES and not the enums: the DJI SDK is `compileOnly` and absent from the
 * unit-test classpath, and a policy that cannot be tested is not a policy. The caller passes
 * `flatMode?.name`.
 */
object MediaModePolicy {

    /** What the readout draws. [other] is the SDK's own name for a mode this code does not handle. */
    data class Reading(val mode: Mode?, val other: String? = null)

    enum class Mode { PHOTO, VIDEO }

    fun reading(flatModeName: String?, legacyModeName: String?): Reading {
        if (flatModeName != null && flatModeName != "UNKNOWN") {
            return when (flatModeName) {
                "PHOTO_SINGLE" -> Reading(Mode.PHOTO)
                "VIDEO_NORMAL" -> Reading(Mode.VIDEO)
                else -> Reading(null, flatModeName)
            }
        }
        return when (legacyModeName) {
            null, "UNKNOWN" -> Reading(null)
            "SHOOT_PHOTO" -> Reading(Mode.PHOTO)
            "RECORD_VIDEO" -> Reading(Mode.VIDEO)
            else -> Reading(null, legacyModeName)
        }
    }
}

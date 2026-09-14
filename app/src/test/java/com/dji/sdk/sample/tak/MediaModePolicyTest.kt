package com.dji.sdk.sample.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the words the media-mode readout says — specification §4.3 and §4.6. */
class MediaModePolicyTest {

    @Test
    fun theTwoModesThisApplicationHandlesAreNamed() {
        assertEquals(MediaModePolicy.Reading(MediaModePolicy.Mode.PHOTO),
            MediaModePolicy.reading("PHOTO_SINGLE", "SHOOT_PHOTO"))
        assertEquals(MediaModePolicy.Reading(MediaModePolicy.Mode.VIDEO),
            MediaModePolicy.reading("VIDEO_NORMAL", "RECORD_VIDEO"))
    }

    @Test
    fun aModeThisApplicationDoesNotHandleIsShownByNameNotMappedOntoOneItKnows() {
        // PHOTO_BURST is not PHOTO: the shutter pill would take one picture and the camera
        // would take several.
        val r = MediaModePolicy.reading("PHOTO_BURST", "SHOOT_PHOTO")
        assertNull(r.mode)
        assertEquals("PHOTO_BURST", r.other)
    }

    @Test
    fun unknownIsItsOwnStateAndNeverOneOfTheRealAnswers() {
        assertEquals(MediaModePolicy.Reading(null), MediaModePolicy.reading(null, null))
        assertEquals(MediaModePolicy.Reading(null),
            MediaModePolicy.reading("UNKNOWN", "UNKNOWN"))
    }

    @Test
    fun theLegacyModeIsReadOnlyWhenTheFlatModeIsUnknown() {
        // A camera without flat mode reports UNKNOWN there and the truth in the legacy field.
        assertEquals(MediaModePolicy.Reading(MediaModePolicy.Mode.VIDEO),
            MediaModePolicy.reading("UNKNOWN", "RECORD_VIDEO"))
        // And the flat mode wins when both are present and disagree — it is the one the
        // Mini 2 acts on.
        assertEquals(MediaModePolicy.Reading(MediaModePolicy.Mode.PHOTO),
            MediaModePolicy.reading("PHOTO_SINGLE", "RECORD_VIDEO"))
    }
}

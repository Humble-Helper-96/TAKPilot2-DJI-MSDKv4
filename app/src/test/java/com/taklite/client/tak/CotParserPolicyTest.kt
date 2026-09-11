package com.taklite.client.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two classification rules the parser applies to every inbound event, pinned against the
 * cases measured on the operator's net.
 *
 * `isPersistentType` decides what survives the stale sweep. `isLiveClient` decides what draws as
 * a team dot rather than a 2525 frame. Each row below is a real event shape from the 2026-08-04
 * or 2026-09-10 wire census — see the notes on the two methods. A change that flips one of these
 * rows changes what a pilot sees on the map.
 */
class CotParserPolicyTest {

    // ---- placed markers ------------------------------------------------------------------

    @Test
    fun aMarkerSentDirectFromCloudTakIsPersistentAndNotALiveClient() {
        // a-h-G archived=true takv=false endpoint=false — the CloudTAK direct send.
        val persistent = CotParser.isPersistentType("a-h-G", true, false)
        assertTrue(persistent)
        assertFalse(CotParser.isLiveClient(false, false, persistent))
    }

    @Test
    fun aMarkerForwardedByTakAwareKeepsItsFrame() {
        // a-h-G archived=true takv=false endpoint=TRUE — the forward that drew as a cyan dot
        // (operator, 2026-09-10). The endpoint must not make it a live client.
        val persistent = CotParser.isPersistentType("a-h-G", true, false)
        assertTrue(persistent)
        assertFalse(CotParser.isLiveClient(false, true, persistent))
    }

    @Test
    fun allFourAffiliationsBehaveTheSame() {
        for (type in listOf("a-f-G", "a-h-G", "a-n-G", "a-u-G")) {
            val persistent = CotParser.isPersistentType(type, true, false)
            assertTrue(type, persistent)
            assertFalse(type, CotParser.isLiveClient(false, true, persistent))
        }
    }

    // ---- live clients --------------------------------------------------------------------

    @Test
    fun aCloudTakUserIsALiveClientAndNeverPersistent() {
        // a-f-G-E-V-C archived=false takv=true endpoint=true — 168 of these in one capture.
        val persistent = CotParser.isPersistentType("a-f-G-E-V-C", false, true)
        assertFalse(persistent)
        assertTrue(CotParser.isLiveClient(true, true, persistent))
    }

    @Test
    fun aTeamPliIsALiveClient() {
        // a-f-G-U-C archived=false takv=true endpoint=true — the ordinary ATAK/iTAK/TAK Aware user.
        val persistent = CotParser.isPersistentType("a-f-G-U-C", false, true)
        assertFalse(persistent)
        assertTrue(CotParser.isLiveClient(true, true, persistent))
    }

    @Test
    fun aClientThatArchivesItsOwnReportStaysALiveClient() {
        // The hole the 2026-09-10 review found: a-f-G-E-V-C with <archived/> AND <takv>. No
        // client on the net does this, but the code must not let it become an immortal frame.
        val persistent = CotParser.isPersistentType("a-f-G-E-V-C", true, true)
        assertFalse("takv marks a client's own report; it is never a placed item", persistent)
        assertTrue(CotParser.isLiveClient(true, true, persistent))
    }

    // ---- the safety guards that predate this change stay in force ------------------------

    @Test
    fun anAirTrackIsNeverPersistentEvenWhenArchived() {
        // The 2026-08-03 OOM guard: 161 ADS-B aircraft must keep expiring.
        assertFalse(CotParser.isPersistentType("a-f-A-C-F", true, false))
    }

    @Test
    fun aUnitReportIsNeverPersistentEvenWhenArchived() {
        assertFalse(CotParser.isPersistentType("a-f-G-U-C", true, false))
    }

    @Test
    fun aSensorPointIsNeverPersistentEvenWhenArchived() {
        assertFalse(CotParser.isPersistentType("b-m-p-s-p-loc", true, false))
    }

    @Test
    fun nothingIsPersistentWithoutTheArchivedFlag() {
        // The safe direction to fail — see the note on isPersistentType.
        assertFalse(CotParser.isPersistentType("a-h-G", false, false))
    }
}

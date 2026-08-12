package com.dji.sdk.sample.tak

import android.content.Context
import com.taklite.util.AppLog

/**
 * In-memory index of parsed DTED tile headers (not full elevation grids — see [DtedTile]'s
 * direct-seek lookup) for whatever's currently in [DtedStore]. Rebuilt lazily on first use;
 * [DtedStore.import] and [DtedStore.delete] call [invalidate] so the next lookup picks up
 * the change.
 */
object DtedIndex {
    private const val TAG = "DtedIndex"
    @Volatile private var tiles: List<DtedTile>? = null

    @Synchronized
    fun invalidate() {
        tiles = null
    }

    @Synchronized
    private fun ensureLoaded(context: Context): List<DtedTile> {
        tiles?.let { return it }
        val files = DtedStore.listFiles(context)
        // FINEST TILE FIRST. elevationAt() returns the first tile that covers the point, and
        // DtedStore.listFiles() hands them back sorted by FILENAME — so for a cell imported at
        // several levels ("w149_n61.dt0", "w149_n61.dt2") the .dt0 sorted first and won every
        // lookup. The DTED2 the pilot imported was parsed, indexed, and never read.
        //
        // That is not a rounding detail. DTED0 is ~900m posts against DTED2's ~30m, and marker
        // placement divides terrain error by tan(look angle): at 21° down, 1m of terrain error
        // becomes 2.6m of horizontal miss (at 54° only 0.7m). Measured on the sibling on
        // 2026-08-01 — drops at 54° landed within a metre while drops at 21° were ~10ft out.
        val loaded = files.mapNotNull { DtedTile.open(it) }.sortedBy { it.postSpacingDeg }
        AppLog.i(TAG, "loaded ${loaded.size}/${files.size} DTED tile(s); " +
            "finest post spacing ${loaded.firstOrNull()?.postSpacingDeg ?: 0.0}°")
        tiles = loaded
        return loaded
    }

    /** Elevation (meters, DTED's native vertical datum) at (lat, lon), or null if no
     *  uploaded tile covers the point. */
    fun elevationAt(context: Context, lat: Double, lon: Double): Double? {
        for (t in ensureLoaded(context)) {
            val e = t.elevationAt(lat, lon)
            if (e != null) return e
        }
        return null
    }

    fun hasCoverage(context: Context): Boolean = ensureLoaded(context).isNotEmpty()
}

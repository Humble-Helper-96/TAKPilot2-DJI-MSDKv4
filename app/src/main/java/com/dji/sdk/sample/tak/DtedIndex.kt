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
        val loaded = files.mapNotNull { DtedTile.open(it) }
        AppLog.i(TAG, "loaded ${loaded.size}/${files.size} DTED tile(s)")
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

package com.dji.sdk.sample.tak

import android.content.Context
import android.net.Uri
import com.taklite.util.AppLog
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Stores pilot-uploaded DTED (Digital Terrain Elevation Data, e.g. .dt0/.dt1/.dt2) files in a
 * single shared `terrain/` directory (no per-region subfolders — see [TerrainDatabase]'s doc
 * for why), while [TerrainDao] tracks which imported "region" (one row per uploaded .zip, e.g.
 * "Anchorage") references which tile filenames. Users manage regions, not individual tiles;
 * overlapping tiles between regions are physically stored once and shared.
 *
 * Purely storage/management today — [CameraSlantPoint]'s flat-ground assumption doesn't yet
 * consult these files to correct the Sensor Point of Interest; wiring an actual DTED reader
 * into that math (binary DTED parsing + terrain-aware slant-range solving) is a separate,
 * larger follow-up. This just gets files onto the device and lets the pilot manage them.
 */
object DtedStore {
    private const val TAG = "DtedStore"
    private const val DIR_NAME = "terrain"
    private const val LEGACY_DIR_NAME = "dted" // pre-Room per-region-folder layout, discarded
    private val TILE_EXTENSIONS = setOf("dt0", "dt1", "dt2")

    data class ImportResult(val importedCount: Int, val error: String? = null)
    data class Region(
        val id: Long,
        val name: String,
        val importedAtMs: Long,
        val fileCount: Int,
        val totalBytes: Long,
    )

    fun dir(context: Context): File {
        wipeLegacyDirOnce(context)
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** One-shot: the old per-region-folder layout is being retired wholesale (no migration —
     *  operators are re-importing their region zips fresh against the new mechanism). */
    @Volatile private var legacyWiped = false
    private fun wipeLegacyDirOnce(context: Context) {
        if (legacyWiped) return
        legacyWiped = true
        val legacy = File(context.filesDir, LEGACY_DIR_NAME)
        if (legacy.exists()) {
            val ok = legacy.deleteRecursively()
            AppLog.i(TAG, "wiped legacy DTED dir ($LEGACY_DIR_NAME) -> $ok")
        }
    }

    /** Every tile file in the shared pool — flat, for [DtedIndex]'s elevation lookups (which
     *  don't care which region a tile came from, only where on Earth it is). Also sweeps any
     *  `pending` import row left behind by a crashed/interrupted import (see [TerrainDatabase]
     *  doc) — its partially-extracted files, if any, are harmless unreferenced leftovers. */
    fun listFiles(context: Context): List<File> {
        reconcileIncompleteImports(context)
        return dir(context).listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
    }

    /** One entry per completed import ("region"), name-sorted. */
    fun listRegions(context: Context): List<Region> {
        reconcileIncompleteImports(context)
        return TerrainDatabase.get(context).terrainDao().listImports()
            .map { Region(it.id, it.displayName, it.importedAtMs, it.fileCount, it.totalBytes) }
    }

    private fun reconcileIncompleteImports(context: Context) {
        val dao = TerrainDatabase.get(context).terrainDao()
        val stuck = dao.listIncompleteImports()
        for (imp in stuck) {
            AppLog.w(TAG, "dropping incomplete import #${imp.id} \"${imp.displayName}\" (interrupted mid-import)")
            dao.deleteImportRow(imp.id)
        }
    }

    /** Deletes any tile file in the shared pool with zero remaining references — leftovers
     *  from an interrupted import or an interrupted deletion (see [TerrainDatabase] doc).
     *  Returns the number of files removed. */
    fun cleanUnreferencedTiles(context: Context): Int {
        val dao = TerrainDatabase.get(context).terrainDao()
        val referenced = dao.allReferencedTiles().toHashSet()
        val orphaned = dir(context).listFiles { f -> f.isFile && f.name !in referenced } ?: emptyArray()
        var removed = 0
        for (f in orphaned) if (runCatching { f.delete() }.getOrDefault(false)) removed++
        if (removed > 0) AppLog.i(TAG, "cleaned $removed unreferenced tile file(s)")
        return removed
    }

    /** Imports the picked document as a new region named after [displayName] (extension
     *  stripped): a .zip has every .dt0/.dt1/.dt2 entry extracted into the shared pool
     *  (overwriting any same-named tile in place — the newest import always wins, no
     *  confirmation needed, per spec); anything else is imported as a lone-tile region.
     *  Crash-safe: the import row is inserted `pending` before any file I/O and only flipped
     *  to `complete` after every tile is on disk — see [TerrainDatabase]. */
    fun import(context: Context, uri: Uri, displayName: String): ImportResult {
        val regionName = sanitizeRegionName(displayName.substringBeforeLast('.'))
        val dao = TerrainDatabase.get(context).terrainDao()
        val importId = dao.insertImport(
            ImportEntity(
                displayName = regionName,
                importedAtMs = System.currentTimeMillis(),
                fileCount = 0,
                totalBytes = 0,
                status = "pending",
            )
        )
        val pool = dir(context)
        val result = if (displayName.lowercase().endsWith(".zip")) {
            importZip(context, uri, pool)
        } else {
            importSingleFile(context, uri, displayName, pool)
        }
        if (result.tileNames.isEmpty()) {
            dao.deleteImportRow(importId) // don't leave an empty/failed region behind
            return ImportResult(0, result.error ?: "No .dt0/.dt1/.dt2 tiles found")
        }
        val totalBytes = result.tileNames.sumOf { File(pool, it).length() }
        dao.finishImport(importId, result.tileNames, totalBytes)
        DtedIndex.invalidate()
        AppLog.i(TAG, "import #$importId \"$regionName\": ${result.tileNames.size} tile(s), $totalBytes bytes")
        return ImportResult(result.tileNames.size, result.error)
    }

    private fun sanitizeRegionName(name: String): String {
        val cleaned = name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_")
        return cleaned.ifEmpty { "Region-${System.currentTimeMillis()}" }
    }

    private data class ExtractResult(val tileNames: List<String>, val error: String? = null)

    private fun importSingleFile(context: Context, uri: Uri, displayName: String, pool: File): ExtractResult {
        return try {
            val dest = File(pool, displayName)
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) return ExtractResult(emptyList(), "Failed to save $displayName")
            ExtractResult(listOf(dest.name))
        } catch (t: Throwable) {
            AppLog.w(TAG, "DTED file import failed: ${t.message}")
            ExtractResult(emptyList(), t.message)
        }
    }

    /** Extracts every .dt0/.dt1/.dt2 entry from the zip into the shared pool, flattening each
     *  entry's path ("w150/n61.dt2" -> "w150_n61.dt2") so same-named tiles from different
     *  longitude folders don't collide with each other WITHIN this zip. Across zips, an
     *  identical flattened name is treated as the same physical tile and overwritten (the
     *  intended "duplicate imports are fine, newest wins" behavior). */
    private fun importZip(context: Context, uri: Uri, pool: File): ExtractResult {
        val tileNames = mutableListOf<String>()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val ext = entry.name.substringAfterLast('.', "").lowercase()
                            if (ext in TILE_EXTENSIONS) {
                                val flatName = entry.name.replace('/', '_').replace('\\', '_')
                                val dest = File(pool, flatName)
                                dest.outputStream().use { out -> zip.copyTo(out) }
                                tileNames.add(flatName)
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return ExtractResult(emptyList(), "Could not open zip")
        } catch (t: Throwable) {
            AppLog.w(TAG, "DTED zip import failed: ${t.message}")
            return ExtractResult(tileNames, "Zip import failed: ${t.message}")
        }
        return ExtractResult(tileNames)
    }

    /** Deletes a region: removes its DB row and any tile file that only it referenced, leaving
     *  tiles still used by another region intact. */
    fun deleteRegion(context: Context, region: Region): Boolean {
        val dao = TerrainDatabase.get(context).terrainDao()
        val orphans = dao.deleteImportAndReturnOrphans(region.id)
        val pool = dir(context)
        for (name in orphans) runCatching { File(pool, name).delete() }
        AppLog.i(TAG, "deleted region \"${region.name}\" (#${region.id}): ${orphans.size} tile(s) freed")
        DtedIndex.invalidate()
        return true
    }
}

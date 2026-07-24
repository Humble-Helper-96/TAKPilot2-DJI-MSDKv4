package com.dji.sdk.sample.tak

import android.content.Context
import android.net.Uri
import com.taklite.util.AppLog
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Stores pilot-uploaded DTED (Digital Terrain Elevation Data, e.g. .dt0/.dt1/.dt2) files for
 * the local flight area under filesDir/dted/.
 *
 * Accepts either a single tile file or a .zip bundle of tiles (ATAK-style import) — DTED
 * distributions are commonly shipped as a zip of one-degree tiles under per-longitude
 * subfolders (e.g. "w150/n61.dt2"), so a zip import extracts every .dt0/.dt1/.dt2 entry it
 * finds, flattening each entry's path into the filename (folder separators -> "_") since the
 * bare tile names repeat across longitude folders (e.g. "n61.dt2" exists under w148, w149,
 * w150, w151 alike) and would otherwise collide/overwrite each other.
 *
 * Purely storage/management today — [CameraSlantPoint]'s flat-ground assumption doesn't yet
 * consult these files to correct the Sensor Point of Interest; wiring an actual DTED reader
 * into that math (binary DTED parsing + terrain-aware slant-range solving) is a separate,
 * larger follow-up. This just gets files onto the device and lets the pilot manage them.
 */
object DtedStore {
    private const val TAG = "DtedStore"
    private const val DIR_NAME = "dted"
    private val TILE_EXTENSIONS = setOf("dt0", "dt1", "dt2")

    data class ImportResult(val importedCount: Int, val error: String? = null)

    fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun listFiles(context: Context): List<File> =
        dir(context).listFiles()?.sortedBy { it.name } ?: emptyList()

    /** Imports the picked document: a .zip is extracted (every .dt0/.dt1/.dt2 entry inside),
     *  anything else is copied in directly under [displayName]. */
    fun import(context: Context, uri: Uri, displayName: String): ImportResult {
        val result = if (displayName.lowercase().endsWith(".zip")) {
            importZip(context, uri)
        } else {
            val saved = importSingleFile(context, uri, displayName)
            if (saved != null) ImportResult(1) else ImportResult(0, "Failed to save $displayName")
        }
        if (result.importedCount > 0) DtedIndex.invalidate()
        return result
    }

    private fun importSingleFile(context: Context, uri: Uri, displayName: String): File? {
        return try {
            val dest = File(dir(context), displayName)
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (!copied) return null
            AppLog.i(TAG, "imported DTED file: $displayName (${dest.length()} bytes)")
            dest
        } catch (t: Throwable) {
            AppLog.w(TAG, "DTED file import failed: ${t.message}")
            null
        }
    }

    /** Extracts every .dt0/.dt1/.dt2 entry from the zip, flattening each entry's path
     *  ("w150/n61.dt2" -> "w150_n61.dt2") so same-named tiles from different longitude
     *  folders don't collide. Non-tile entries (readme, metadata, etc.) are skipped. */
    private fun importZip(context: Context, uri: Uri): ImportResult {
        var count = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val ext = entry.name.substringAfterLast('.', "").lowercase()
                            if (ext in TILE_EXTENSIONS) {
                                val flatName = entry.name.replace('/', '_').replace('\\', '_')
                                val dest = File(dir(context), flatName)
                                dest.outputStream().use { out -> zip.copyTo(out) }
                                count++
                                AppLog.i(TAG, "imported DTED tile from zip: $flatName (${dest.length()} bytes)")
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return ImportResult(0, "Could not open zip")
        } catch (t: Throwable) {
            AppLog.w(TAG, "DTED zip import failed: ${t.message}")
            return ImportResult(count, "Zip import failed: ${t.message}")
        }
        return ImportResult(count, if (count == 0) "No .dt0/.dt1/.dt2 tiles found in zip" else null)
    }

    fun delete(file: File): Boolean {
        val ok = runCatching { file.delete() }.getOrDefault(false)
        AppLog.i(TAG, "delete DTED file ${file.name} -> $ok")
        if (ok) DtedIndex.invalidate()
        return ok
    }
}

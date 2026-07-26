package com.taklite.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drop-in replacement for android.util.Log across the TAK/bridge code.
 * Always forwards to Log.* (adb logcat keeps working identically); additionally
 * appends to a rotating file under filesDir/logs/ while [enabled] is true, AND
 * mirrors the same lines into a user-accessible archive under Downloads/TAKPilot2
 * Logs — so a field session can be pulled off the controller without adb or the
 * in-app export flow. The private working copy (what the Debug screen's Clear/
 * Delete act on) is pruned by age (2h). The public archive is bounded by total
 * size instead — [PUBLIC_ARCHIVE_MAX_BYTES] — since size tracks actual log volume,
 * while wall-clock age doesn't (idle stretches produce no data, so a time window
 * is a poor proxy for "how much log data is this"). Oldest files are deleted
 * first whenever a new session file would push the folder over the cap.
 *
 * Vendor-neutral (JDK + Android framework only) so it can live alongside
 * com.taklite.client.tak without breaking that package's no-SDK-imports rule.
 */
object AppLog {
    private const val PREFS_NAME = "app_log_prefs"
    private const val KEY_ENABLED = "debug_logging_enabled"
    private const val KEY_VERBOSE = "debug_logging_verbose"
    private const val KEY_TAK = "debug_logging_tak"
    private const val ACTIVE_FILE_NAME = "app.log"
    private const val MAX_FILE_SIZE_BYTES = 1L * 1024 * 1024
    private const val RETENTION_MS = 2L * 60 * 60 * 1000
    private const val PUBLIC_SUBFOLDER = "TAKPilot2 Logs"
    private const val PUBLIC_ARCHIVE_MAX_BYTES = 10L * 1024 * 1024

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var initialized = false
    private val writeLock = Any()

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    // Public archive state — a new timestamped file per rotation, opened lazily.
    private var publicUri: Uri? = null
    private var publicLegacyFile: File? = null
    private var publicBytesWritten: Long = 0

    @JvmStatic
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initialized = true
        sweepExpiredLogs()
    }

    @JvmStatic
    var enabled: Boolean
        get() = initialized && prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            if (initialized) prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /**
     * Detail level. false ("Standard") = only the pre-existing bridge/TAK Log.* call
     * sites (state changes, warnings, errors). true ("Detailed") = also captures the
     * whole-app instrumentation added via [v] — screen navigation, button presses,
     * toggle changes, per-tick telemetry — for full-app field diagnosis.
     */
    @JvmStatic
    var verbose: Boolean
        get() = initialized && prefs.getBoolean(KEY_VERBOSE, false)
        set(value) {
            if (initialized) prefs.edit().putBoolean(KEY_VERBOSE, value).apply()
        }

    /**
     * Whether TAK/CoT-subsystem lines (see [TAK_TAGS]) reach the log file. Default true.
     * Turned off from the Debug screen when diagnosing the app itself: the CoT bridge pushes
     * on a 2s tick and TakManager/CotParser are chatty, which buries lower-volume app logs
     * (video pipeline, camera, DTED) in the tail view.
     *
     * Only filters the FILE sink — logcat still gets everything, so `adb logcat` is unaffected.
     */
    @JvmStatic
    var takLogging: Boolean
        get() = !initialized || prefs.getBoolean(KEY_TAK, true)
        set(value) {
            if (initialized) prefs.edit().putBoolean(KEY_TAK, value).apply()
        }

    /**
     * Tags owned by the TAK/CoT side of the app, suppressed when [takLogging] is off.
     *
     * Deliberately an explicit set rather than a "starts with Tak" prefix test: several
     * app-side tags would false-positive on that ("TAKPilot2GoHome" is the home screen,
     * "TakConnectActivity" is the whole Pre-Flight Setup screen incl. drone/map/video/DTED
     * settings), and a prefix rule would silently start eating app logs the moment someone
     * names a new class Tak-something. A tag missing from this set fails OPEN — the line
     * still gets logged — which is the safe direction (extra noise, never silent loss).
     * Add new TAK-subsystem tags here.
     */
    private val TAK_TAGS = setOf(
        "DroneTakBridge",     // telemetry -> CoT push, 2s tick — the loudest of the group
        "TakManager",
        "TakClient",
        "CotParser",
        "TakCertEnroller",
        "TakGroupAssigner",
        "TakMissionClient",
        "TakMissionManager",
        "TakAutoConnect",
        "TakForegroundService",
        "TakMapMarkers",
        "TakDropMarkers",
    )

    /** Verbose-tier detail log: UI actions, navigation, per-tick internals. Only written
     * to file when both [enabled] and [verbose] are on; always forwarded to Log.d. */
    @JvmStatic
    fun v(tag: String, msg: String) {
        Log.d(tag, msg)
        if (verbose) writeToFile("V", tag, msg)
    }

    @JvmStatic fun d(tag: String, msg: String) { Log.d(tag, msg); writeToFile("D", tag, msg) }
    @JvmStatic fun i(tag: String, msg: String) { Log.i(tag, msg); writeToFile("I", tag, msg) }
    @JvmStatic fun w(tag: String, msg: String) { Log.w(tag, msg); writeToFile("W", tag, msg) }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, msg, tr)
        writeToFile("W", tag, msg + "\n" + Log.getStackTraceString(tr))
    }
    @JvmStatic fun e(tag: String, msg: String) { Log.e(tag, msg); writeToFile("E", tag, msg) }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
        writeToFile("E", tag, msg + "\n" + Log.getStackTraceString(tr))
    }

    /** Writes an uncaught-exception trace directly; caller (crash handler) already gates on [enabled]. */
    @JvmStatic
    fun writeCrash(thread: Thread, tr: Throwable) {
        writeToFile("FATAL", "Crash", "Uncaught exception on ${thread.name}\n${Log.getStackTraceString(tr)}")
    }

    @JvmStatic
    fun activeLogFile(): File = File(logDir(), ACTIVE_FILE_NAME)

    @JvmStatic
    fun clearActive() {
        synchronized(writeLock) {
            try {
                FileWriter(activeLogFile(), false).use { it.write("") }
            } catch (t: Throwable) {
                // Never let logging itself crash the app.
            }
        }
    }

    @JvmStatic
    fun deleteAll() {
        synchronized(writeLock) {
            try {
                logDir().listFiles()?.forEach { it.delete() }
            } catch (t: Throwable) {
            }
        }
    }

    @JvmStatic
    fun sweepExpiredLogs() {
        if (!initialized) return
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            logDir().listFiles()?.forEach { f ->
                if (f.lastModified() < cutoff) f.delete()
            }
        } catch (t: Throwable) {
        }
    }

    private fun writeToFile(level: String, tag: String, msg: String) {
        if (!enabled) return
        // FATAL (crash traces) is never filtered — losing a crash to a log-noise setting
        // would be the worst possible failure mode for this switch.
        if (level != "FATAL" && !takLogging && tag in TAK_TAGS) return
        val line = buildString {
            append(timestampFormat.format(Date()))
            append(' ').append(level)
            append('/').append(tag).append(": ").append(msg).append('\n')
        }
        synchronized(writeLock) {
            try {
                val active = activeLogFile()
                if (active.exists() && active.length() > MAX_FILE_SIZE_BYTES) {
                    rotate(active)
                }
                FileWriter(active, true).use { it.append(line) }
            } catch (t: Throwable) {
            }
            writePublic(line)
        }
    }

    private fun rotate(active: File) {
        val rotated = File(active.parentFile, "app-${fileTimestampFormat.format(Date())}.log")
        active.renameTo(rotated)
    }

    private fun logDir(): File {
        val dir = File(appContext.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ---- Public archive: Downloads/TAKPilot2 Logs — capped by total size, not age ----

    private fun writePublic(line: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writePublicMediaStore(line)
            else writePublicLegacy(line)
        } catch (t: Throwable) {
            // Public archive is best-effort — never let it take down the private log path.
        }
    }

    private fun writePublicMediaStore(line: String) {
        val resolver = appContext.contentResolver
        if (publicUri == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "app-${fileTimestampFormat.format(Date())}.log")
                // "application/octet-stream" has no canonical extension for MediaProvider to
                // force onto DISPLAY_NAME (unlike "text/plain" -> .txt), so the .log name sticks.
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBFOLDER")
            }
            publicUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            publicBytesWritten = 0
            enforcePublicArchiveCapMediaStore()
        }
        val uri = publicUri ?: return
        resolver.openOutputStream(uri, "wa")?.use { it.write(line.toByteArray()) }
        publicBytesWritten += line.toByteArray().size
        if (publicBytesWritten > MAX_FILE_SIZE_BYTES) publicUri = null   // next write starts a fresh file
    }

    private fun writePublicLegacy(line: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_SUBFOLDER)
        if (!dir.exists()) dir.mkdirs()
        var f = publicLegacyFile
        if (f == null || f.length() > MAX_FILE_SIZE_BYTES) {
            f = File(dir, "app-${fileTimestampFormat.format(Date())}.log")
            publicLegacyFile = f
            enforcePublicArchiveCapLegacy(dir, keep = f)
        }
        FileWriter(f, true).use { it.append(line) }
    }

    /** Deletes the oldest archive entries (by DATE_ADDED) until the folder is back under the cap. */
    private fun enforcePublicArchiveCapMediaStore() {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
        val relPathPrefix = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBFOLDER%"
        val entries = ArrayList<Pair<Long, Long>>()   // id, size — oldest first via sort order
        resolver.query(
            collection, projection,
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf(relPathPrefix),
            "${MediaStore.Downloads.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            while (c.moveToNext()) entries.add(c.getLong(idCol) to c.getLong(sizeCol))
        }
        var total = entries.sumOf { it.second }
        for ((id, size) in entries) {
            if (total <= PUBLIC_ARCHIVE_MAX_BYTES) break
            runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
            total -= size
        }
    }

    /** Deletes the oldest archive files (by lastModified) until [dir] is back under the cap. */
    private fun enforcePublicArchiveCapLegacy(dir: File, keep: File) {
        val files = dir.listFiles()?.filter { it != keep }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() } + keep.length()
        for (f in files) {
            if (total <= PUBLIC_ARCHIVE_MAX_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}

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
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
 * ## Lines are buffered, and the public file stays open (2026-09-10)
 *
 * Before this date each line went to the public archive on its own: one MediaStore stream
 * opened, one line written, the stream closed. On Android 10 each of those is a call to the
 * system media scanner. Measured in flight with logging on: the scanner used 120 % of a CPU
 * core, more than the application, and the screen stuttered. With logging off, the same
 * flight was smooth.
 *
 * Now a line goes to a buffer. The buffer goes to both files when it holds [FLUSH_BYTES], or
 * [FLUSH_INTERVAL_MS] after its first line, whichever is first. The public stream is opened
 * once per archive file and stays open until that file is full, logging is turned off, or a
 * crash is written. Thus the scanner sees one open and one close per file, not one per line.
 *
 * ⚠ THREE THINGS MUST NOT WAIT FOR THE TIMER. An error line (E) and a crash trace (FATAL)
 * flush at once — a trace that waits one second is a trace that the crash can take with it.
 * Turning logging off flushes and closes. The Debug screen's Clear and Delete flush first,
 * or they would clear the file and leave the last second of lines to reappear after it.
 * A process that the system KILLS (an OOM kill has no crash handler) can lose the last
 * [FLUSH_INTERVAL_MS] of lines. That is the accepted cost.
 *
 * Vendor-neutral (JDK + Android framework only) so it can live alongside
 * com.taklite.client.tak without breaking that package's no-SDK-imports rule.
 */
object AppLog {
    private const val PREFS_NAME = "app_log_prefs"
    private const val KEY_ENABLED = "debug_logging_enabled"
    private const val KEY_VERBOSE = "debug_logging_verbose"
    private const val KEY_TAK = "debug_logging_tak"
    private const val KEY_OBSTACLE = "debug_logging_obstacle"
    private const val KEY_RESOURCE = "debug_logging_resource"
    private const val KEY_RESOURCE_MONITOR = "debug_resource_monitor"
    private const val ACTIVE_FILE_NAME = "app.log"
    private const val MAX_FILE_SIZE_BYTES = 1L * 1024 * 1024
    private const val RETENTION_MS = 2L * 60 * 60 * 1000
    private const val PUBLIC_SUBFOLDER = "TAKPilot2 Logs"
    private const val PUBLIC_ARCHIVE_MAX_BYTES = 10L * 1024 * 1024
    /** The buffer goes to the files at this size. 8 KB is about 60 lines: at the 2 Hz bridge
     *  tick with the TAK subsystem on, that is one or two seconds of log. */
    private const val FLUSH_BYTES = 8 * 1024
    /** …or this long after the buffer's first line, so a quiet period does not hold lines. */
    private const val FLUSH_INTERVAL_MS = 1000L

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var initialized = false
    private val writeLock = Any()

    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)

    // The buffer and its timer. All of these are read and written under [writeLock].
    private val pending = StringBuilder()
    private var pendingBytes = 0
    private var scheduledFlush: ScheduledFuture<*>? = null
    private val flusher: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "AppLog-flush").apply { isDaemon = true }
    }

    // Public archive state — a new timestamped file per rotation, opened lazily. The stream
    // stays open for the life of the file; see the class note.
    private var publicUri: Uri? = null
    private var publicStream: OutputStream? = null
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
            if (!initialized) return
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            // Off: the buffer goes to the files now, and the public file closes, so the
            // archive holds every line up to the switch and the scanner can index the file.
            if (!value) synchronized(writeLock) { flushLocked(closePublic = true) }
        }

    /** Writes the buffer to both files now. For a caller that is about to read the file. */
    @JvmStatic
    fun flush() {
        synchronized(writeLock) { flushLocked(closePublic = false) }
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
     * Whether obstacle-distance lines (see [OBSTACLE_TAGS]) reach the log file. Default **false**
     * — the sensors report continuously and would otherwise dominate every flight log. Turn it on
     * deliberately when obstacle avoidance is the thing under investigation.
     *
     * Avoidance switch changes, enforcement and warnings are NOT affected: they use a different
     * tag and are always logged.
     */
    @JvmStatic
    var obstacleLogging: Boolean
        get() = initialized && prefs.getBoolean(KEY_OBSTACLE, false)
        set(value) {
            if (initialized) prefs.edit().putBoolean(KEY_OBSTACLE, value).apply()
        }

    /**
     * Whether the periodic memory/CPU/contact-count line (see [RESOURCE_TAGS]) reaches the log
     * file. Default **true**, unlike the obstacle filter.
     *
     * On by default because it is one line every 30 seconds and it is the watchdog for the
     * failure that OOM-killed the Autel sibling in the air: a contact count that climbs across a
     * flight instead of oscillating. A diagnostic that is off when the rare fault happens has
     * missed the only chance to catch it. It is a toggle rather than always-on so an operator
     * chasing something else can quieten the log.
     */
    @JvmStatic
    var resourceLogging: Boolean
        get() = !initialized || prefs.getBoolean(KEY_RESOURCE, true)
        set(value) {
            if (initialized) prefs.edit().putBoolean(KEY_RESOURCE, value).apply()
        }

    /**
     * Whether the flight screen shows the resource row ON SCREEN. Default **false**, separate
     * from [resourceLogging] on purpose: a log line costs nothing and is read afterwards, while
     * an overlay covers live video during a flight. Different costs, different defaults, so they
     * get their own switches rather than one that means two things.
     */
    @JvmStatic
    var resourceMonitor: Boolean
        get() = initialized && prefs.getBoolean(KEY_RESOURCE_MONITOR, false)
        set(value) {
            if (initialized) prefs.edit().putBoolean(KEY_RESOURCE_MONITOR, value).apply()
        }

    private val RESOURCE_TAGS = setOf("TP2Resources")

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

    /**
     * Obstacle-distance tags, hidden from the log file unless [obstacleLogging] is on.
     *
     * The vision sensors report continuously while the aircraft is powered, so these are the
     * highest-volume lines the app produces — enough to bury everything else in the tail view.
     * Off by default because obstacle range is almost never what is being diagnosed.
     *
     * **A SEPARATE TAG, not a message-prefix match.** The Autel build filters the equivalent
     * lines with `msg.startsWith("radar(")`, because there the distances and the avoidance
     * switch state share one tag — and its own comment warns that rewording that log line
     * silently breaks the filter. Splitting the noisy readout onto its own tag removes that
     * coupling entirely: the avoidance switch, enforcement and warning lines keep [TAG-visible]
     * tags and survive the filter by construction rather than by careful wording.
     *
     * Same contract as [takLogging]: FILE sink only, logcat still receives everything.
     */
    private val OBSTACLE_TAGS = setOf("TP2ObstacleRange")

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
            // Flush first, or the buffered lines land in the file the pilot just cleared.
            flushLocked(closePublic = false)
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
            flushLocked(closePublic = false)
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
        if (level != "FATAL" && !obstacleLogging && tag in OBSTACLE_TAGS) return
        if (level != "FATAL" && !resourceLogging && tag in RESOURCE_TAGS) return
        val line = buildString {
            append(timestampFormat.format(Date()))
            append(' ').append(level)
            append('/').append(tag).append(": ").append(msg).append('\n')
        }
        synchronized(writeLock) {
            pending.append(line)
            pendingBytes += line.length
            // E and FATAL go to the files NOW — see the class note. FATAL also closes the
            // public file, because the process is about to end and nothing else will.
            val urgent = level == "E" || level == "FATAL"
            if (urgent || pendingBytes >= FLUSH_BYTES) {
                flushLocked(closePublic = level == "FATAL")
            } else if (scheduledFlush == null) {
                scheduledFlush = flusher.schedule(
                    { synchronized(writeLock) { flushLocked(closePublic = false) } },
                    FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    /**
     * Writes the buffer to both files. Caller holds [writeLock].
     *
     * @param closePublic also close the public stream after the write. The next flush opens a
     *   new archive file. Used when the process is about to end (a crash) or logging is off.
     */
    private fun flushLocked(closePublic: Boolean) {
        scheduledFlush?.cancel(false)
        scheduledFlush = null
        if (pending.isNotEmpty()) {
            val chunk = pending.toString()
            pending.setLength(0)
            pendingBytes = 0
            try {
                val active = activeLogFile()
                if (active.exists() && active.length() > MAX_FILE_SIZE_BYTES) {
                    rotate(active)
                }
                FileWriter(active, true).use { it.append(chunk) }
            } catch (t: Throwable) {
            }
            writePublic(chunk)
        }
        if (closePublic) closePublicLocked()
    }

    /** Closes the public stream. The next write opens a new archive file. Caller holds [writeLock]. */
    private fun closePublicLocked() {
        runCatching { publicStream?.close() }
        publicStream = null
        publicUri = null
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

    /** [chunk] is one or more whole lines — the buffer. Caller holds [writeLock]. */
    private fun writePublic(chunk: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) writePublicMediaStore(chunk)
            else writePublicLegacy(chunk)
        } catch (t: Throwable) {
            // Public archive is best-effort — never let it take down the private log path.
            // A stream that failed is dropped; the next flush opens a new file.
            closePublicLocked()
        }
    }

    private fun writePublicMediaStore(chunk: String) {
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
        // ONE open per file, not one per line. This is the whole reason for the buffer.
        val out = publicStream
            ?: resolver.openOutputStream(uri, "wa")?.also { publicStream = it }
            ?: return
        val bytes = chunk.toByteArray()
        out.write(bytes)
        out.flush()
        publicBytesWritten += bytes.size
        // Full: close it, so the scanner indexes it, and let the next flush start a new file.
        if (publicBytesWritten > MAX_FILE_SIZE_BYTES) closePublicLocked()
    }

    private fun writePublicLegacy(chunk: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_SUBFOLDER)
        if (!dir.exists()) dir.mkdirs()
        var f = publicLegacyFile
        if (f == null || f.length() > MAX_FILE_SIZE_BYTES) {
            f = File(dir, "app-${fileTimestampFormat.format(Date())}.log")
            publicLegacyFile = f
            enforcePublicArchiveCapLegacy(dir, keep = f)
        }
        FileWriter(f, true).use { it.append(chunk) }
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

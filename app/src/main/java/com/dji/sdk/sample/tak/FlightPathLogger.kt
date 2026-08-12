package com.dji.sdk.sample.tak

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import com.taklite.util.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Records the path of each flight to Downloads/TAKPilotFlights (v1.5.9, plan §5). During the
 * flight it appends one CSV row each second. At landing it writes one GPX track from the rows.
 *
 * **The design protects the record from a crash.** An append-only CSV is a valid file at each
 * moment. A crash loses only the seconds after the last row. It never loses the file. The GPX
 * (the track for ATAK, Google Earth and GIS tools) is written complete at flight end. If the
 * process dies before that, [sweepOrphans] finds the CSV that has no GPX partner at the next
 * start and completes the conversion. The CSV stays in both cases — it holds speed, heading,
 * battery and satellite count, which GPX cannot hold.
 *
 * **This object is fed. It does not subscribe.** Data arrives through [onTelemetry] from the
 * FlightControllerState callback that [DroneTakBridge] already owns. A second setStateCallback
 * would replace the bridge's with no warning — the SDK slot holds one client — so nothing here
 * touches the SDK. A TAK connection is never a precondition: the bridge ticks regardless of
 * whether TakManager is connected, so the record is written with no server and no network.
 *
 * **The callback thread is never blocked.** All file and MediaStore work occurs on a dedicated
 * worker thread. [onTelemetry] only checks the throttle and posts. This 2Hz safety channel was
 * flooded one time before, and an aircraft hit a wall.
 *
 * The folder has a size limit. The oldest files are deleted first — the same rule as AppLog's
 * public archive (size measures data volume; age does not). 50 MB holds months of flying at
 * approximately 200 KB for each 30-minute flight.
 */
object FlightPathLogger {

    private const val TAG = "FlightPathLogger"
    private const val SUBFOLDER = "TAKPilotFlights"
    private const val FOLDER_MAX_BYTES = 50L * 1024 * 1024
    private const val SAMPLE_MS = 1000L
    /** How long the aircraft must stay on the ground before the flight is declared over.
     *  Keeps a touch-and-go inside one track instead of splitting it into two files. */
    private const val LANDED_HOLD_MS = 10_000L

    private const val CSV_HEADER =
        "utc_time,lat,lon,alt_msl_m,alt_above_takeoff_m,speed_ms,heading_deg,battery_pct,satellites\n"

    private lateinit var appContext: Context
    private var initialized = false

    // All mutable state below lives on this thread — onTelemetry only reads the throttle gate.
    private lateinit var worker: Handler
    @Volatile private var lastSampleAt = 0L

    // ---- Per-session state (worker thread only) ----
    private var sessionBaseName: String? = null      // "flight-2026-08-07-14-03-22"
    private var csvUri: Uri? = null                  // MediaStore row (API 29+)
    private var csvLegacyFile: File? = null          // direct file (< API 29)
    private var groundedSinceMs = 0L
    /** In-memory copy of every row, for the one-shot GPX at flight end. 1Hz keeps even a
     *  multi-hour flight to a few MB; a crash loses only this copy, never the CSV. */
    private val points = ArrayList<Point>()

    internal class Point(
        val timeMs: Long, val lat: Double, val lon: Double,
        val mslAltM: Double, val relAltM: Double,
        val speedMs: Double, val headingDeg: Double,
        val batteryPct: Int, val satellites: Int,
    )

    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US)
    private val isoUtcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    @JvmStatic
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        val thread = HandlerThread("FlightPathLogger")
        thread.start()
        worker = Handler(thread.looper)
        initialized = true
    }

    /**
     * One telemetry frame from the bridge's fly-controller callback (~2Hz). Cheap by contract:
     * a time check and a post. Frames while grounded still arrive here — they are what detect
     * the landing — but only airborne frames become rows.
     */
    fun onTelemetry(
        lat: Double, lon: Double, mslAltM: Double, relAltM: Double,
        speedMs: Double, headingDeg: Double, batteryPct: Int, satellites: Int,
    ) {
        if (!initialized) return
        val now = System.currentTimeMillis()
        if (now - lastSampleAt < SAMPLE_MS) return
        lastSampleAt = now
        // The same airborne test the PLI publishes — the two records must agree on when a
        // flight happened.
        val flying = relAltM.isFinite() && (relAltM > 0.5 || speedMs > 0.5)
        worker.post { onSample(now, flying, Point(now, lat, lon, mslAltM, relAltM, speedMs, headingDeg, batteryPct, satellites)) }
    }

    /** Close out any open session — bridge stop, STOP/QUIT, task removal. Synchronous enough:
     *  the posted finalize runs before any later posted work, and process death right after
     *  this is the same crash case the orphan sweep already covers. */
    @JvmStatic
    fun endSession(reason: String) {
        if (!initialized) return
        worker.post { finalizeSession(reason) }
    }

    // ---- Worker thread from here down ----

    private fun onSample(nowMs: Long, flying: Boolean, p: Point) {
        if (!flying) {
            if (sessionBaseName == null) return
            if (groundedSinceMs == 0L) groundedSinceMs = nowMs
            if (nowMs - groundedSinceMs >= LANDED_HOLD_MS) finalizeSession("landed")
            return
        }
        groundedSinceMs = 0L
        if (sessionBaseName == null) startSession(p)
        // No GPS fix — the aircraft can be airborne in ATTI with nothing worth plotting.
        // The row is dropped rather than written as 0,0: a track point at Null Island is a
        // lie on a map, and the GPX would draw a line to it.
        if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0 ||
            (p.lat == 0.0 && p.lon == 0.0)) return
        points.add(p)
        appendCsv(csvRow(p))
    }

    private fun startSession(first: Point) {
        val base = "flight-${fileNameFormat.format(Date(first.timeMs))}"
        sessionBaseName = base
        points.clear()
        csvUri = null
        csvLegacyFile = null
        runCatching { createCsv(base) }
            .onFailure { AppLog.w(TAG, "could not create $base.csv: ${it.message}") }
        AppLog.i(TAG, "flight session started: $base")
    }

    private fun finalizeSession(reason: String) {
        val base = sessionBaseName ?: return
        AppLog.i(TAG, "flight session ended ($reason): $base, ${points.size} points")
        if (points.isNotEmpty()) {
            runCatching { writeFile("$base.gpx", gpxDocument(points)) }
                .onFailure { AppLog.w(TAG, "GPX write failed for $base: ${it.message}") }
        }
        sessionBaseName = null
        csvUri = null
        csvLegacyFile = null
        groundedSinceMs = 0L
        points.clear()
    }

    /**
     * Convert any CSV that has no GPX partner — the signature of a session that died before
     * [finalizeSession] ran. Call once per app launch, off the main thread. Malformed trailing
     * rows (a write cut mid-line by the crash) are skipped, which is the whole reason the CSV
     * is the recovery format.
     */
    @JvmStatic
    fun sweepOrphans() {
        if (!initialized) return
        worker.post {
            runCatching {
                val names = listFolder().map { it.second }
                val gpx = names.filter { it.endsWith(".gpx") }
                    .map { canonicalBase(it.removeSuffix(".gpx")) }.toSet()
                names.filter { it.endsWith(".csv") && canonicalBase(it.removeSuffix(".csv")) !in gpx }
                    .forEach { csvName ->
                        val base = canonicalBase(csvName.removeSuffix(".csv"))
                        // Never convert the session currently being written.
                        if (base == sessionBaseName) return@forEach
                        val pts = readCsv(csvName)
                        if (pts.isEmpty()) return@forEach
                        AppLog.i(TAG, "recovering crashed session: $base (${pts.size} points)")
                        runCatching { writeFile("$base.gpx", gpxDocument(pts)) }
                            .onFailure { AppLog.w(TAG, "recovery GPX failed for $base: ${it.message}") }
                    }
            }.onFailure { AppLog.w(TAG, "orphan sweep failed: ${it.message}") }
        }
    }

    // ---- Formats ----

    /**
     * Strips a MediaStore de-duplication suffix, so `flight-…-14-03-22 (1)` and
     * `flight-…-14-03-22` are recognised as the same flight.
     *
     * ⚠ THIS IS WHAT KEEPS THE ORPHAN SWEEP FROM LOOPING FOR EVER. MediaStore does not fail an
     * insert whose DISPLAY_NAME already exists — it silently stores it as "name (1)". The CSV is
     * then written under the renamed file while the GPX is written under the plain base, so the
     * sweep sees a CSV with no GPX partner, converts it, and finds the same unpaired CSV again at
     * the next launch — every launch, accumulating a new GPX each time.
     *
     * Collisions are not hypothetical: a name is only unique to the second, and a row can survive
     * in MediaStore after its file is gone. Matching on the canonical base rather than the exact
     * name is what makes the pairing survive that. (Open finding #5 on the Autel sibling, which
     * matches exact names and has this defect; built in here rather than ported.)
     */
    internal fun canonicalBase(name: String): String =
        DEDUP_SUFFIX.replace(name, "")

    private val DEDUP_SUFFIX = Regex(""" \(\d+\)$""")

    private fun num(v: Double, decimals: Int): String =
        if (v.isFinite()) String.format(Locale.US, "%.${decimals}f", v) else ""

    internal fun csvRow(p: Point): String = buildString {
        append(isoUtcFormat.format(Date(p.timeMs))).append(',')
        append(num(p.lat, 7)).append(',')
        append(num(p.lon, 7)).append(',')
        append(num(p.mslAltM, 1)).append(',')
        append(num(p.relAltM, 1)).append(',')
        append(num(p.speedMs, 1)).append(',')
        append(num(p.headingDeg, 0)).append(',')
        append(p.batteryPct).append(',')
        append(p.satellites).append('\n')
    }

    internal fun parseCsvRow(line: String): Point? {
        val f = line.split(',')
        if (f.size != 9) return null
        return runCatching {
            Point(
                isoUtcFormat.parse(f[0])!!.time,
                f[1].toDouble(), f[2].toDouble(),
                f[3].toDoubleOrNull() ?: Double.NaN, f[4].toDoubleOrNull() ?: Double.NaN,
                f[5].toDoubleOrNull() ?: 0.0, f[6].toDoubleOrNull() ?: 0.0,
                f[7].toIntOrNull() ?: 0, f[8].toIntOrNull() ?: 0,
            )
        }.getOrNull()
    }

    /** GPX 1.1, one track, one segment. `<ele>` is MSL metres (falls back to altitude above
     *  takeoff when the GNSS never supplied MSL — wrong datum but right shape, and the CSV
     *  keeps both so nothing is lost). Times are the ISO-UTC instants the rows carry. */
    internal fun gpxDocument(pts: List<Point>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"TAKPilot2-DJI\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk>\n    <name>")
        append(sessionBaseName ?: "flight")
        append("</name>\n    <trkseg>\n")
        for (p in pts) {
            append("      <trkpt lat=\"").append(num(p.lat, 7))
            append("\" lon=\"").append(num(p.lon, 7)).append("\">\n")
            val ele = if (p.mslAltM.isFinite()) p.mslAltM else p.relAltM
            if (ele.isFinite()) append("        <ele>").append(num(ele, 1)).append("</ele>\n")
            append("        <time>").append(isoUtcFormat.format(Date(p.timeMs))).append("</time>\n")
            append("      </trkpt>\n")
        }
        append("    </trkseg>\n  </trk>\n</gpx>\n")
    }

    // ---- Downloads/TAKPilotFlights I/O — the AppLog public-archive pattern ----

    private fun createCsv(base: String) {
        enforceFolderCap()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$base.csv")
                // octet-stream for the same reason as AppLog: no canonical extension for
                // MediaProvider to force onto the name, so ".csv" sticks.
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            }
            csvUri = appContext.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            csvUri?.let { appendToUri(it, CSV_HEADER) }
        } else {
            val f = File(legacyDir(), "$base.csv")
            f.appendText(CSV_HEADER)
            csvLegacyFile = f
        }
    }

    private fun appendCsv(row: String) {
        try {
            csvUri?.let { appendToUri(it, row) }
            csvLegacyFile?.appendText(row)
        } catch (t: Throwable) {
            // Best-effort, like every other sink in this app: recording must never take
            // down telemetry.
        }
    }

    private fun appendToUri(uri: Uri, text: String) {
        appContext.contentResolver.openOutputStream(uri, "wa")
            ?.use { it.write(text.toByteArray()) }
    }

    private fun writeFile(name: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER")
            }
            val uri = appContext.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            appContext.contentResolver.openOutputStream(uri)
                ?.use { it.write(content.toByteArray()) }
        } else {
            File(legacyDir(), name).writeText(content)
        }
    }

    /** (id or -1, displayName) for everything in the folder, oldest first. */
    private fun listFolder(): List<Pair<Long, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return legacyDir().listFiles().orEmpty().sortedBy { it.lastModified() }
                .map { -1L to it.name }
        }
        val out = ArrayList<Pair<Long, String>>()
        appContext.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER%"),
            "${MediaStore.Downloads.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (c.moveToNext()) out.add(c.getLong(idCol) to c.getString(nameCol))
        }
        return out
    }

    private fun readCsv(name: String): List<Point> {
        val text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val id = listFolder().firstOrNull { it.second == name }?.first ?: return emptyList()
            appContext.contentResolver
                .openInputStream(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id))
                ?.bufferedReader()?.use { it.readText() } ?: return emptyList()
        } else {
            val f = File(legacyDir(), name)
            if (!f.exists()) return emptyList()
            f.readText()
        }
        return text.lineSequence().drop(1).mapNotNull { parseCsvRow(it) }.toList()
    }

    /** Oldest files first until the folder is back under [FOLDER_MAX_BYTES] — AppLog's
     *  enforcePublicArchiveCap, pointed at this folder. Run before each new session file. */
    private fun enforceFolderCap() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = appContext.contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val entries = ArrayList<Pair<Long, Long>>()   // id, size — oldest first
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$SUBFOLDER%"),
                    "${MediaStore.Downloads.DATE_ADDED} ASC",
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    while (c.moveToNext()) entries.add(c.getLong(idCol) to c.getLong(sizeCol))
                }
                var total = entries.sumOf { it.second }
                for ((id, size) in entries) {
                    if (total <= FOLDER_MAX_BYTES) break
                    runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
                    total -= size
                }
            } else {
                val files = legacyDir().listFiles().orEmpty().sortedBy { it.lastModified() }
                var total = files.sumOf { it.length() }
                for (f in files) {
                    if (total <= FOLDER_MAX_BYTES) break
                    val len = f.length()
                    if (f.delete()) total -= len
                }
            }
        }
    }

    private fun legacyDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUBFOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}

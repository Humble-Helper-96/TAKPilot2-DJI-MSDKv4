package com.dji.sdk.sample.tak

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.taklite.client.tak.TakManager
import java.io.File

/**
 * Memory, CPU and contact-count diagnostics, written to the log while a flight is running.
 *
 * The contact count is here deliberately. On the Autel sibling a stale-retention bug in
 * [com.taklite.client.tak.CotParser] held every distinct ADS-B contact for at least ten minutes
 * regardless of how briefly it was live: 161 "known" contacts against a handful on a second TAK
 * client, ending in a sequence of app-process OOM kills in the air on 2026-08-03. This tree had
 * the identical bug and it is now fixed, but the count stays as the fastest way to see whether it
 * — or a future variant — is coming back, alongside the raw memory figures, right up to a crash.
 *
 * CPU is instantaneous load, not memory. A software encoder pegging a core shows here first and
 * does not show in the memory figures at all.
 *
 * ⚠ NO GPU READ, unlike the sibling. That one reads an Adreno sysfs node it confirmed was
 * readable by an app process on its specific controller. That is a device and build property,
 * not an Android guarantee — SELinux locks it to root on plenty of devices, and this app runs on
 * whatever phone is attached to the RC-N1. A figure that silently reads "—" on most hardware is
 * not worth the code.
 *
 * ⚠ `cpu=` may show "—/x%" on a modern Android. The system figure comes from `/proc/stat`, which
 * SELinux hides from apps on API 26 and above; the app's own figure uses the official
 * [Process.getElapsedCpuTime] and is always available. The read is wrapped and degrades to null
 * rather than throwing, so this is expected rather than a fault to chase.
 *
 * NOT SURFACED ON THE FLIGHT SCREEN in this tree. The sibling has an overlay row of fixed cells
 * sized against its 1024x720 controller; this app runs on a phone and that row has not been laid
 * out or seen here. The log line carries the whole diagnostic value in the meantime.
 */
object ResourceMonitor {

    data class Snapshot(
        val sysAvailMb: Int,
        val sysTotalMb: Int,
        val lowMemory: Boolean,
        val appPssMb: Int,
        val heapUsedMb: Int,
        val heapMaxMb: Int,
        val contactCount: Int,
        /** Null on the first sample only — both need a delta against the previous call. */
        val sysCpuPct: Int?,
        val appCpuPct: Int?,
    )

    // Previous-sample state for the CPU deltas. SystemClock.elapsedRealtime() (monotonic, immune
    // to RTC/timezone changes) is the wall clock; -1 means "no previous sample yet".
    @Volatile private var lastWallMs: Long = -1
    @Volatile private var lastSysTotalJiffies: Long = -1
    @Volatile private var lastSysIdleJiffies: Long = -1
    @Volatile private var lastAppCpuMs: Long = -1

    fun snapshot(context: Context): Snapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(memInfo) }

        // Process-wide PSS (Proportional Set Size) — what ActivityManager itself weighs an app's
        // footprint by, so this is the same number the OS's own low-memory decision is based on.
        val pssMb = runCatching {
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
                ?.let { it.totalPss / 1024 } ?: 0
        }.getOrDefault(0)

        val rt = Runtime.getRuntime()
        val heapUsedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val heapMaxMb = (rt.maxMemory() / (1024 * 1024)).toInt()

        val contactCount = runCatching { TakManager.getInstance().takUsers.size }.getOrDefault(-1)

        // ---- CPU: delta against the previous snapshot ----
        val nowWallMs = SystemClock.elapsedRealtime()
        val sysJiffies = readSystemCpuJiffies()
        val appCpuMs = Process.getElapsedCpuTime()   // official API: this process's CPU time, in ms

        var sysCpuPct: Int? = null
        var appCpuPct: Int? = null
        if (lastWallMs >= 0) {
            val wallDeltaMs = nowWallMs - lastWallMs
            if (sysJiffies != null && lastSysTotalJiffies >= 0 && wallDeltaMs > 0) {
                val totalDelta = sysJiffies.first - lastSysTotalJiffies
                val idleDelta = sysJiffies.second - lastSysIdleJiffies
                if (totalDelta > 0) {
                    sysCpuPct = (100 * (totalDelta - idleDelta) / totalDelta).toInt().coerceIn(0, 100)
                }
            }
            if (wallDeltaMs > 0) {
                // NOT clamped to 100 — a multi-threaded process can legitimately use more than
                // one core's worth of wall-clock time, same convention `top` uses.
                appCpuPct = (100 * (appCpuMs - lastAppCpuMs) / wallDeltaMs).toInt().coerceAtLeast(0)
            }
        }
        lastWallMs = nowWallMs
        if (sysJiffies != null) { lastSysTotalJiffies = sysJiffies.first; lastSysIdleJiffies = sysJiffies.second }
        lastAppCpuMs = appCpuMs

        return Snapshot(
            sysAvailMb = (memInfo.availMem / (1024 * 1024)).toInt(),
            sysTotalMb = (memInfo.totalMem / (1024 * 1024)).toInt(),
            lowMemory = memInfo.lowMemory,
            appPssMb = pssMb,
            heapUsedMb = heapUsedMb,
            heapMaxMb = heapMaxMb,
            contactCount = contactCount,
            sysCpuPct = sysCpuPct,
            appCpuPct = appCpuPct,
        )
    }

    /** (total, idle+iowait) jiffies from /proc/stat's aggregate "cpu " line, or null if unreadable. */
    private fun readSystemCpuJiffies(): Pair<Long, Long>? = runCatching {
        val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
        // "cpu  57354 18884 56145 282250 1808 6151 3171 0 0 0"
        // fields: user nice system idle iowait irq softirq steal guest guest_nice
        val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (fields.size < 4) return null
        val idle = fields[3] + (fields.getOrElse(4) { 0L })
        val total = fields.sum()
        total to idle
    }.getOrNull()

    /**
     * One line for the log. Fixed field order so a session's lines can be diffed or eyeballed
     * down a column.
     *
     * `tak=` is the number that mattered on 2026-08-03: the contact map held 161 aircraft while
     * the live picture showed a handful, and the process was OOM-killed in the air. It should
     * oscillate around the size of the real picture, not climb across a session.
     */
    fun formattedLine(context: Context): String {
        val s = snapshot(context)
        val lowFlag = if (s.lowMemory) " LOW-MEM" else ""
        val sysCpu = s.sysCpuPct?.let { "$it%" } ?: "—"
        val appCpu = s.appCpuPct?.let { "$it%" } ?: "—"
        return "sys=${s.sysAvailMb}/${s.sysTotalMb}MB$lowFlag " +
            "app=${s.appPssMb}MB heap=${s.heapUsedMb}/${s.heapMaxMb}MB " +
            "cpu=$sysCpu/$appCpu tak=${s.contactCount}"
    }
}

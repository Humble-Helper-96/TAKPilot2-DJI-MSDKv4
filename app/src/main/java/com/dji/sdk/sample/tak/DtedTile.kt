package com.dji.sdk.sample.tak

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.floor

/**
 * Parsed DTED tile (MIL-PRF-89020B: UHL + DSI + ACC + per-longitude data records), with
 * on-demand point-elevation lookup via direct file seeks rather than loading the whole
 * file (~13MB+ for the DTED2-ish tiles this app has been tested with) into memory.
 *
 * The header's own longitude-interval field already bakes in the latitude-dependent post
 * spacing DTED uses above 50°/70°/etc. (fewer longitude posts at high latitude, to keep
 * physical spacing roughly constant) — nothing special-cased here, [nLon]/[lonIntervalDeg]
 * are just read from the file as given.
 */
class DtedTile private constructor(
    private val file: File,
    private val originLonDeg: Double,
    private val originLatDeg: Double,
    private val lonIntervalDeg: Double,
    private val latIntervalDeg: Double,
    private val nLon: Int,
    private val nLat: Int,
    private val dataStartOffset: Long,
) {
    private val recordLength = 12L + 2L * nLat

    private val minLon = originLonDeg
    private val maxLon = originLonDeg + (nLon - 1) * lonIntervalDeg
    private val minLat = originLatDeg
    private val maxLat = originLatDeg + (nLat - 1) * latIntervalDeg

    fun contains(lat: Double, lon: Double): Boolean =
        lon in minLon..maxLon && lat in minLat..maxLat

    /** Bilinear-interpolated elevation (meters, DTED's native vertical datum) at (lat, lon),
     *  or null if outside this tile or a surrounding post is void/unreadable. */
    fun elevationAt(lat: Double, lon: Double): Double? {
        if (!contains(lat, lon)) return null
        val fc = (lon - originLonDeg) / lonIntervalDeg
        val fr = (lat - originLatDeg) / latIntervalDeg
        val c0 = floor(fc).toInt().coerceIn(0, nLon - 1)
        val r0 = floor(fr).toInt().coerceIn(0, nLat - 1)
        val c1 = (c0 + 1).coerceAtMost(nLon - 1)
        val r1 = (r0 + 1).coerceAtMost(nLat - 1)
        val tc = (fc - c0).coerceIn(0.0, 1.0)
        val tr = (fr - r0).coerceIn(0.0, 1.0)

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val e00 = postAt(raf, c0, r0) ?: return null
                val e10 = postAt(raf, c1, r0) ?: return null
                val e01 = postAt(raf, c0, r1) ?: return null
                val e11 = postAt(raf, c1, r1) ?: return null
                val eLow = e00 + (e10 - e00) * tc
                val eHigh = e01 + (e11 - e01) * tc
                eLow + (eHigh - eLow) * tr
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun postAt(raf: RandomAccessFile, col: Int, row: Int): Double? {
        val offset = dataStartOffset + col * recordLength + 8 + 2 * row
        raf.seek(offset)
        val b0 = raf.read()
        val b1 = raf.read()
        if (b0 < 0 || b1 < 0) return null
        val magnitude = ((b0 and 0x7F) shl 8) or b1
        val value = if (b0 and 0x80 != 0) -magnitude else magnitude
        return if (value == VOID_VALUE) null else value.toDouble()
    }

    companion object {
        private const val UHL_LEN = 80L
        private const val DSI_LEN = 648L
        private const val ACC_LEN = 2700L
        private const val DATA_START = UHL_LEN + DSI_LEN + ACC_LEN
        private const val VOID_VALUE = -32767

        /** Parses just the 80-byte UHL header. Returns null if the file isn't a recognizable
         *  DTED tile (wrong magic, unparseable fields, etc.) — never throws. */
        fun open(file: File): DtedTile? {
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val header = ByteArray(80)
                    raf.readFully(header)
                    val text = String(header, Charsets.US_ASCII)
                    if (!text.startsWith("UHL1")) return null

                    val lonStr = text.substring(4, 12).trim()
                    val latStr = text.substring(12, 20).trim()
                    val lonIntervalRaw = text.substring(20, 24).trim().toIntOrNull() ?: return null
                    val latIntervalRaw = text.substring(24, 28).trim().toIntOrNull() ?: return null
                    val nLon = text.substring(47, 51).trim().toIntOrNull() ?: return null
                    val nLat = text.substring(51, 55).trim().toIntOrNull() ?: return null
                    if (nLon <= 0 || nLat <= 0) return null

                    val originLon = parseDmsH(lonStr) ?: return null
                    val originLat = parseDmsH(latStr) ?: return null
                    val lonIntervalDeg = (lonIntervalRaw / 10.0) / 3600.0
                    val latIntervalDeg = (latIntervalRaw / 10.0) / 3600.0
                    if (lonIntervalDeg <= 0 || latIntervalDeg <= 0) return null

                    DtedTile(file, originLon, originLat, lonIntervalDeg, latIntervalDeg, nLon, nLat, DATA_START)
                }
            } catch (t: Throwable) {
                null
            }
        }

        /** Parses a DDDMMSSH-style origin field (degrees/minutes/seconds + hemisphere letter).
         *  Tolerant of 6-8 digit encodings (some tools zero-pad the degree field differently)
         *  by taking the last 2 digits as seconds, the next 2 as minutes, and whatever's left
         *  as degrees. */
        private fun parseDmsH(raw: String): Double? {
            val m = Regex("(\\d+)\\s*([NSEW])").find(raw) ?: return null
            val digits = m.groupValues[1]
            val hemi = m.groupValues[2]
            if (digits.length < 5) return null
            val sec = digits.takeLast(2).toIntOrNull() ?: return null
            val min = digits.dropLast(2).takeLast(2).toIntOrNull() ?: return null
            val deg = digits.dropLast(4).toIntOrNull() ?: return null
            var value = deg + min / 60.0 + sec / 3600.0
            if (hemi == "S" || hemi == "W") value = -value
            return value
        }
    }
}

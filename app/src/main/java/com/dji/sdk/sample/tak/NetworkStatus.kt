package com.dji.sdk.sample.tak

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * One answer to "can this device reach the network?". The home screen's status line and the
 * Pre-Flight enrollment check share it, so the two can never disagree.
 *
 * THREE STATES, NOT TWO. The middle one is the important one. A device can hold a wifi
 * association to a hotspot with no upstream, or sit behind a captive portal. That state produced
 * field reports of "TAK enrollment failed" on the Autel sibling: the wifi icon looked correct and
 * the network went nowhere. So CONNECTED means the system CONFIRMED reachability
 * (NET_CAPABILITY_VALIDATED — Android's own probe). An attached SSID alone is not enough, and
 * association-without-validation gets its own text that says the network is the problem, not the
 * TAK server.
 *
 * ⚠ TRANSPORT-AGNOSTIC, UNLIKE THE AUTEL SIBLING'S VERSION. That one runs on a controller whose
 * only radio is wifi, so it treats "no wifi association" as no network. This runs on a phone.
 * Reporting OFF while the aircraft is being flown on mobile data — a perfectly working
 * configuration — would be a red indicator next to a working TAK connection, which teaches a
 * pilot to ignore the indicator. What matters here is whether the ACTIVE network validates,
 * whatever carries it; the label then says which transport that turned out to be.
 *
 * Polled, not listener-driven. The only consumers are Home's refresh loop and one check when the
 * pilot presses Enroll & Connect. A registered NetworkCallback would just be one more object to
 * release. A capability read is cheap.
 */
object NetworkStatus {

    enum class State {
        /** An active network the system validated as actually reaching the internet. */
        CONNECTED,
        /** A network is attached but not validated — hotspot with no upstream, captive portal. */
        NO_INTERNET,
        /** No active network on any transport. */
        OFF,
    }

    data class Snapshot(
        val state: State,
        /**
         * What is carrying it, for the pilot: the wifi network name, or "Mobile data", or null
         * when nothing is attached. Wifi SSID needs runtime location permission on modern API
         * levels; when the OS withholds it this falls back to "Wi-Fi" rather than showing the
         * placeholder string the framework returns.
         */
        val label: String?,
        /** Wifi signal strength 0..4, WifiManager's own bucketing; -1 when not on wifi. */
        val level: Int,
    ) {
        /** `▂▄▆█` at full strength; always at least one bar while associated, because an EMPTY
         *  meter next to a green dot reads as a contradiction. Empty when not on wifi — there
         *  are no bars to draw for a cellular link. */
        fun bars(): String =
            if (level < 0) "" else BAR_GLYPHS.substring(0, (level + 1).coerceAtMost(4))
    }

    private const val BAR_GLYPHS = "▂▄▆█"

    /**
     * True when the active network has validated reachability. The enrollment precondition —
     * deliberately indifferent to HOW the device reaches the server, only that it can.
     */
    fun hasInternet(context: Context): Boolean {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun read(context: Context): Snapshot {
        val app = context.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Snapshot(State.OFF, null, -1)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return Snapshot(State.OFF, null, -1)

        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val state = if (validated) State.CONNECTED else State.NO_INTERNET

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo
            val level = if (info != null) WifiManager.calculateSignalLevel(info.rssi, 5) else -1
            val ssid = info?.ssid?.trim('"')
                ?.takeUnless { it.isEmpty() || it == "<unknown ssid>" || it == "0x" }
            return Snapshot(state, ssid ?: "Wi-Fi", level)
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return Snapshot(state, "Mobile data", -1)
        }
        // Ethernet over USB, a tethered link, something else. Attached and possibly validated —
        // say so plainly rather than claiming a transport we did not identify.
        return Snapshot(state, "Network", -1)
    }
}

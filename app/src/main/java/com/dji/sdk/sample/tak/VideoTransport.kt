package com.dji.sdk.sample.tak

/**
 * How the encoded video LEAVES THE CONTROLLER. It is a pilot choice because the correct answer
 * is a property of the network, and the pilot is the only person who knows which network this
 * callout is on.
 *
 * ## This changes one leg only
 *
 * ```
 *   controller  --[ RTSP or SRT ]-->  media server  --[ always RTSP ]-->  TAK clients
 * ```
 *
 * **No TAK client plays SRT.** Thus the address in the CoT stays an RTSP address whichever
 * transport is selected, and the media server does the translation.
 *
 * ## The two transports
 *
 *  - **[RTSP] is what this application has always used.** It is TCP, thus every packet arrives
 *    or the connection stalls until it does. On a good link this is correct and simple. On a
 *    lossy link TCP holds every later packet behind the lost one, so the picture stops instead
 *    of degrading, and the recovery is a stall of unknown length.
 *  - **[SRT] asks for a lost packet again, inside a time budget** (see [SRT_LATENCY_DEFAULT_MS]).
 *    A packet that cannot be recovered in that budget is abandoned and the picture continues.
 *    This is the behaviour that suits a cellular uplink: it degrades instead of stalling.
 *
 * ⚠ **SRT does not make a slow link fast.** It recovers LOST packets. If the link does not have
 * the bandwidth for the selected quality, SRT buffers, adds delay and then drops frames, the
 * same as RTSP. The answer to that is the video-quality control, not this one.
 *
 * ## The credentials go in a different place
 *
 * RTSP sends the user and the password in the protocol. SRT HAS NO SUCH FIELD: the credentials
 * go inside the stream id, which is the whole of what the server receives to identify the
 * publisher. See [DroneVideoStreamer.VideoConfig.pushUrl].
 */
enum class VideoTransport(val label: String, val scheme: String, val defaultPort: Int) {

    /**
     * RTSP over TCP.
     *
     * ⚠ **There is no UDP option any more.** The screen had a "Use TCP transport" checkbox and
     * it is gone (operator, 2026-08-29): a pilot who cleared it got RTSP over UDP, which has no
     * retransmission at all and tears the picture on exactly the links that made this control
     * look attractive. SRT is the better answer to that problem and it is now on the screen.
     * An installation that had the box cleared moves to TCP on upgrade.
     */
    RTSP("RTSP", "rtsp", 8554),

    /** SRT caller, publishing to the media server's SRT ingest. */
    SRT("SRT", "srt", 8890);

    /** The `video_transport` pref value. Derived, so a transport cannot be saved under a typo. */
    val prefValue: String get() = name.lowercase()

    companion object {
        /**
         * Default [RTSP] — the transport every controller in the fleet is already flying. A
         * value this function does not recognise is a corrupt or future pref, and the right
         * answer for both is the transport that is known to work here.
         */
        fun fromPref(name: String?): VideoTransport =
            values().firstOrNull { it.prefValue == name } ?: RTSP

        // The playback port was a CONSTANT here (8554) until 2026-08-29, because an SRT push
        // and its RTSP playback are two different ports and the screen had one field. That
        // meant a server serving RTSP on any other port could not be advertised at all.
        //
        // It is a real field now: the RTSP block on Pre-Flight is shown whichever transport is
        // selected, because it describes the address in the CoT and not only the push. See
        // DroneVideoStreamer.VideoConfig.

        /**
         * The SRT latency budget, in MILLISECONDS. **Not on the Pre-Flight screen** — it is on
         * the Debug screen, because it is a network property and not a flight decision.
         *
         * ## What it does
         *
         * SRT repairs a lost packet by asking for it again. That costs approximately ONE MORE
         * ROUND TRIP. This is how long the receiver holds the video before it must show it, so
         * it is the whole time budget the repair has to complete in. A packet repaired after
         * the deadline is thrown away, and the bandwidth spent repairing it is wasted.
         *
         * ## Why 500 and not 250 (ground test, MediaMTX v1.20.0, 2026-08-29)
         *
         * Over laptop → WiFi → CradlePoint → LTE → WireGuard → server, RTT 116 ms, with 250 ms
         * negotiated, the server counted:
         *
         * ```
         *   packetsReceivedLoss      200      30.2% loss rate
         *   packetsReceivedRetrans   193      the repairs were sent
         *   packetsReceivedDrop      193      and every one arrived too late
         *   bytesReceivedRetrans == bytesReceivedDrop      byte for byte
         * ```
         *
         * The drops equalling the retransmissions is what identifies this as a LATE REPAIR
         * and not congestion: a link with no room drops packets it never managed to resend,
         * and these were all resent and then discarded. The controller's own send queue was
         * clean at the same time (`drops=0` in the link log), which is the other half — the
         * encoder was not outrunning the uplink.
         *
         * ⚠ Do NOT use `mbpsLinkCapacity` to reach that conclusion, although it agreed. It is
         * an ESTIMATE from packet-pair timing, and WiFi and cellular both deliver queued
         * packets in bursts that collapse the gap it measures. It read 261 Mbps on that path
         * and 1831 Mbps in flight, and neither is a real number.
         *
         * The recovery machinery worked perfectly and delivered nothing: 250 ms against a
         * 116 ms RTT is about two round trips, which is not enough. **The convention is 3–4x
         * RTT**, so 350–500 ms for that path. 500 is the conservative end, chosen because an
         * aircraft on a congested tower or at range sees a WORSE RTT than a ground test, never
         * a better one.
         *
         * ⚠ **STILL TO BE FLOWN.** The number that says whether this is right is
         * `packetsReceivedDrop` from `GET /v3/srtconns/list` on the server: near zero means the
         * buffer is doing its job, still climbing means go higher. If RTT stays low and the
         * drops persist, the fault is bandwidth or the encoder outrunning the uplink, and MORE
         * LATENCY WILL NOT FIX IT — the video quality control will.
         *
         * ## The cost
         *
         * The team watches half a second behind the aircraft. That is acceptable for situation
         * awareness and it is stated in the Field Guide, because it is better read than
         * discovered.
         */
        const val SRT_LATENCY_DEFAULT_MS = 500

        /**
         * ⚠ **The wire field is 16 bits of milliseconds**, and the writer keeps the low 16 bits
         * of whatever it is handed without complaining. So a value entered in MICROSECONDS —
         * the unit an `srt://…?latency=` url uses, where half a second is `500000` — does not
         * fail; 500000 goes out as 41248 ms and the stream sits forty seconds behind
         * reality, looking like SRT is broken.
         *
         * These bounds are what stops that. 120 ms is the library's own old value and about the
         * least that can repair anything; 4000 ms is far past any useful budget and well inside
         * the 16-bit ceiling.
         */
        const val SRT_LATENCY_MIN_MS = 120
        const val SRT_LATENCY_MAX_MS = 4_000

        /** The Debug-screen override. Milliseconds. Absent or out of range means the default. */
        const val KEY_SRT_LATENCY_MS = "video_srt_latency_ms"

        /**
         * The latency to use, from the field override if there is a sane one.
         *
         * It is read PER STREAM START, not once at launch, so a pilot who changes it on the
         * Debug screen gets the new value on the next LIVE without restarting the application.
         */
        fun srtLatencyMs(prefs: android.content.SharedPreferences): Int =
            clampLatencyMs(prefs.getInt(KEY_SRT_LATENCY_MS, SRT_LATENCY_DEFAULT_MS))

        /** A value outside the bounds is a typo or a unit mistake, thus the default is safer
         *  than the number asked for. See [SRT_LATENCY_MIN_MS]. */
        fun clampLatencyMs(value: Int): Int =
            if (value in SRT_LATENCY_MIN_MS..SRT_LATENCY_MAX_MS) value else SRT_LATENCY_DEFAULT_MS
    }
}

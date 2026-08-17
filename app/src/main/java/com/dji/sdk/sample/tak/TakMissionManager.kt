package com.dji.sdk.sample.tak

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import com.taklite.client.tak.CotParser
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakMissionClient
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Data Sync (TAK Server Mission API) manager. Owns the currently-joined feed, pulls its CoT
 * markers onto the map/AR (reusing [CotParser] + [TakMapMarkers] via TakManager's listeners),
 * polls for changes, and publishes dropped markers to the feed.
 *
 * The Mission API is HTTPS on the cert API port (default 8443), separate from the 8089 CoT stream.
 */
object TakMissionManager {
    private const val TAG = "TakMissionManager"
    private const val API_PORT = 8443
    private const val POLL_MS = 10_000L
    private const val PREFS = "takpilot2_datasync"

    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Feed the user has joined (null = none). Persisted so we rejoin/pull on next launch. */
    @Volatile var joinedFeed: String? = null
        private set

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        appContext = context.applicationContext
        joinedFeed = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("feed", null)
        if (joinedFeed != null) startPolling()
    }

    /** Build a Mission client from the retained certs; null if not enrolled. */
    fun client(): TakMissionClient? = TakMissionClient.fromTakManager(API_PORT)

    /** List feeds on the server (background). Result posted to [cb] on the main thread. */
    fun listFeeds(cb: (List<TakMissionClient.Feed>) -> Unit) {
        io.execute {
            val feeds = client()?.listFeeds() ?: emptyList()
            handler.post { cb(feeds) }
        }
    }

    /** Join (subscribe to) a feed, then pull its contents. [cb](success). */
    fun joinFeed(name: String, password: String?, cb: (Boolean) -> Unit) {
        val uid = TakManager.getInstance().uid ?: run { cb(false); return }
        io.execute {
            val c = client()
            val ok = c?.subscribe(name, uid, password) == true
            if (ok) {
                joinedFeed = name
                appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                    ?.putString("feed", name)?.apply()
                pullFeedNow(c!!, name)
            }
            handler.post {
                cb(ok)
                if (ok) startPolling()
            }
        }
    }

    fun leaveFeed(cb: (Boolean) -> Unit) {
        val name = joinedFeed ?: run { cb(true); return }
        val uid = TakManager.getInstance().uid
        io.execute {
            val ok = client()?.unsubscribe(name, uid) == true
            joinedFeed = null
            appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove("feed")?.apply()
            handler.post { stopPolling(); cb(ok) }
        }
    }

    fun createFeed(name: String, description: String?, defaultRole: String = "MISSION_SUBSCRIBER",
                   group: String? = null, cb: (Boolean) -> Unit) {
        val uid = TakManager.getInstance().uid ?: run { cb(false); return }
        io.execute {
            val ok = client()?.createFeed(name, uid, description, defaultRole, group) == true
            handler.post { cb(ok) }
        }
    }

    /** List the channels/groups this cert can scope a feed to (background). */
    fun listGroups(cb: (List<String>) -> Unit) {
        io.execute {
            val groups = client()?.listGroups() ?: emptyList()
            handler.post { cb(groups) }
        }
    }

    /** Pull the channels/groups the logged-in user belongs to (deduped) for the TAK Setup picker. */
    /** The channels this certificate has, with direction and active state. Background, result
     *  on the main thread. */
    fun listChannels(cb: (List<TakMissionClient.Channel>) -> Unit) {
        io.execute {
            val chans = client()?.listChannels() ?: emptyList()
            handler.post { cb(chans) }
        }
    }

    /**
     * Sets the active channels for this certificate.
     *
     * ⚠ ABSOLUTE — pass the COMPLETE set you want active. Anything not in the list is switched
     * off. ⚠ It applies to the certificate, thus to every controller enrolled as this user.
     */
    fun setActiveChannels(bitpos: List<Int>, cb: (Boolean) -> Unit) {
        io.execute {
            val ok = client()?.setActiveChannels(bitpos) == true
            handler.post { cb(ok) }
        }
    }

    /** Delete a feed the drone created (owner-only on the server). [cb](success). */
    fun deleteFeed(name: String, cb: (Boolean) -> Unit) {
        val uid = TakManager.getInstance().uid ?: run { cb(false); return }
        io.execute {
            val ok = client()?.deleteFeed(name, uid) == true
            if (ok && name == joinedFeed) {
                joinedFeed = null
                appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove("feed")?.apply()
            }
            handler.post { if (ok && name == joinedFeed) stopPolling(); cb(ok) }
        }
    }

    /** The drone's own uid (feed creator id), for "am I the creator?" checks in the UI. */
    fun myUid(): String? = TakManager.getInstance().uid

    /** Publish an already-broadcast CoT (by uid) into the joined feed so it persists + shares. */
    fun publishUid(uid: String) {
        val name = joinedFeed ?: return
        val creator = TakManager.getInstance().uid
        io.execute {
            val ok = client()?.addUidToFeed(name, uid, creator) == true
            AppLog.d(TAG, "publishUid $uid -> feed $name: $ok")
        }
    }

    // ---- Polling + pulling feed CoT into the marker renderer ----
    private val pollRunnable = object : Runnable {
        override fun run() {
            val name = joinedFeed ?: return
            io.execute {
                client()?.let { pullFeedNow(it, name) }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun startPolling() {
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun stopPolling() { handler.removeCallbacks(pollRunnable) }

    /**
     * Pull the feed's CoT (<events>…) and feed each event through the same path inbound stream
     * CoT uses (TakManager.onCotReceived → CotParser → TakMapMarkers), so feed markers render
     * exactly like live ones (incl. 2525 icons + persistence).
     */
    private fun pullFeedNow(c: TakMissionClient, name: String) {
        val xml = c.getFeedCot(name) ?: return
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            val sb = StringBuilder()
            var depth = 0
            var event = parser.eventType
            // Split the <events> wrapper into individual <event>…</event> strings.
            val events = ArrayList<String>()
            var current: StringBuilder? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "event") { current = StringBuilder(); depth = 1; appendStart(current!!, parser) }
                        else if (current != null) { depth++; appendStart(current, parser) }
                    }
                    XmlPullParser.TEXT -> if (current != null) current.append(escape(parser.text))
                    XmlPullParser.END_TAG -> {
                        if (current != null) {
                            current.append("</").append(parser.name).append(">")
                            if (parser.name == "event") { events.add(current.toString()); current = null }
                        }
                    }
                }
                event = parser.next()
            }
            handler.post {
                val tak = TakManager.getInstance()
                for (e in events) tak.onCotReceived(e)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "pullFeed parse failed: ${e.message}")
        }
    }

    private fun appendStart(sb: StringBuilder, p: XmlPullParser) {
        sb.append("<").append(p.name)
        for (i in 0 until p.attributeCount) {
            sb.append(" ").append(p.getAttributeName(i)).append("=\"")
                .append(escape(p.getAttributeValue(i))).append("\"")
        }
        sb.append(">")
    }

    private fun escape(s: String?): String = s?.replace("&", "&amp;")?.replace("<", "&lt;")
        ?.replace(">", "&gt;")?.replace("\"", "&quot;") ?: ""
}

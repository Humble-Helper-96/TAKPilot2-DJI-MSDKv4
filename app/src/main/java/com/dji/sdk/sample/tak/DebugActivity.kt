package com.dji.sdk.sample.tak

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.dji.sdk.sample.BuildConfig
import com.dji.sdk.sample.R
import com.taklite.util.AppLog
import java.io.RandomAccessFile

/**
 * Debug screen: toggle file logging on/off, export/clear/delete the active log, and watch
 * it fill live. Only reads/writes AppLog's own file sink — no full logcat.
 */
class DebugActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var meta: TextView

    // Only re-render when the file actually changed, and tail it so a near-cap 1MB
    // file doesn't get re-laid-out into the TextView every tick.
    private var lastRenderedLength = -1L
    private val maxTailBytes = 500 * 1024L

    // Explicit, touch-driven "follow the tail" state — more robust than re-deriving it
    // from scroll geometry on every poll, which is sensitive to layout-pass timing.
    // The instant the user puts a finger down on the log, we stop auto-scrolling; we
    // only resume following once they've scrolled back to the bottom themselves.
    private var pinnedToBottom = true

    private val poll = object : Runnable {
        override fun run() {
            refreshLogView()
            handler.postDelayed(this, 1000)
        }
    }

    companion object { private const val TAG = "DebugActivity" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        AppLog.sweepExpiredLogs()
        AppLog.v(TAG, "onCreate")

        logText = findViewById(R.id.debugLogText)
        logScroll = findViewById(R.id.debugLogScroll)
        meta = findViewById(R.id.debugLogMeta)

        logScroll.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                pinnedToBottom = false
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                // Let the fling/settle finish, then check if they landed back at the bottom.
                logScroll.postDelayed({ pinnedToBottom = isScrolledToBottom() }, 300)
            }
            false   // don't consume — ScrollView still needs this to handle the drag/fling
        }

        val toggle = findViewById<CheckBox>(R.id.debugLoggingToggle)
        toggle.isChecked = AppLog.enabled
        toggle.setOnCheckedChangeListener { _, on ->
            AppLog.enabled = on
            AppLog.v(TAG, "logging ${if (on) "enabled" else "disabled"}")
        }

        val verboseToggle = findViewById<CheckBox>(R.id.debugVerboseToggle)
        verboseToggle.isChecked = AppLog.verbose
        verboseToggle.setOnCheckedChangeListener { _, on ->
            AppLog.verbose = on
            AppLog.v(TAG, "detail level set to ${if (on) "Detailed" else "Standard"}")
        }

        val takToggle = findViewById<CheckBox>(R.id.debugTakToggle)
        takToggle.isChecked = AppLog.takLogging
        takToggle.setOnCheckedChangeListener { _, on ->
            AppLog.takLogging = on
            // Logged from DebugActivity (an app-side tag), so this line survives either way —
            // it marks the point in the log where the filter changed.
            AppLog.i(TAG, "TAK/CoT logs ${if (on) "INCLUDED" else "HIDDEN"}")
        }

        findViewById<android.widget.Button>(R.id.debugExportButton).setOnClickListener {
            AppLog.v(TAG, "export tapped")
            exportLog()
        }
        findViewById<android.widget.Button>(R.id.debugClearButton).setOnClickListener {
            AppLog.clearActive()
            lastRenderedLength = -1
            pinnedToBottom = true
            refreshLogView()
            toast("Log cleared")
        }
        findViewById<android.widget.Button>(R.id.debugDeleteButton).setOnClickListener {
            AppLog.deleteAll()
            lastRenderedLength = -1
            pinnedToBottom = true
            refreshLogView()
            toast("All logs deleted")
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun refreshLogView() {
        val file = AppLog.activeLogFile()
        if (!file.exists()) {
            if (lastRenderedLength != 0L) {
                logText.text = "(no log yet — enable logging to start capturing)"
                lastRenderedLength = 0
            }
            meta.text = ""
            return
        }
        val length = file.length()
        if (length == lastRenderedLength) return
        lastRenderedLength = length

        val tail = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = maxOf(0L, length - maxTailBytes)
                raf.seek(start)
                val bytes = ByteArray((length - start).toInt())
                raf.readFully(bytes)
                String(bytes)
            }
        }.getOrDefault("(failed to read log file)")

        logText.text = tail
        meta.text = "${file.name} — ${length / 1024} KB"

        // Only auto-scroll if the user hasn't manually scrolled away from the bottom —
        // otherwise a 1s poll would yank them back down mid scroll-back through history.
        if (pinnedToBottom) {
            logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun isScrolledToBottom(): Boolean {
        if (logText.height == 0) return true   // nothing laid out yet — treat as "at bottom"
        val slop = (8 * resources.displayMetrics.density).toInt()
        val bottom = logScroll.scrollY + logScroll.height
        return bottom >= logText.height - slop
    }

    private fun exportLog() {
        val file = AppLog.activeLogFile()
        if (!file.exists() || file.length() == 0L) {
            toast("Nothing to export yet")
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export debug log"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

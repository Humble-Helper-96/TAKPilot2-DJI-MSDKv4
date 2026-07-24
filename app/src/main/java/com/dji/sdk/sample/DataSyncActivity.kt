package com.dji.sdk.sample

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.taklite.client.tak.TakManager
import com.taklite.client.tak.TakMissionClient
import com.dji.sdk.sample.tak.TakMissionManager
import com.taklite.util.AppLog

/**
 * Data Sync (TAK Server Mission API) — list feeds, create a feed, join/leave. Joined feed's
 * markers are pulled onto the map/AR by [TakMissionManager] and dropped markers publish to it.
 */
class DataSyncActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var joined: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_sync)
        AppLog.v("DataSyncActivity", "onCreate")
        status = findViewById(R.id.dsStatus)
        joined = findViewById(R.id.dsJoined)
        list = findViewById(R.id.dsFeedList)

        findViewById<Button>(R.id.dsRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.dsCreate).setOnClickListener { showCreateDialog() }

        if (!TakManager.getInstance().isConnected) {
            status.text = "Not connected to TAK. Enroll in TAK Setup first."
        }
        refresh()
    }

    private fun refresh() {
        updateJoined()
        status.text = "Loading feeds…"
        list.removeAllViews()
        TakMissionManager.listFeeds { feeds ->
            if (feeds.isEmpty()) {
                status.text = "No feeds found (or not connected)."
            } else {
                status.text = "${feeds.size} feed(s) on the server."
            }
            for (f in feeds) addFeedRow(f)
        }
    }

    private fun updateJoined() {
        val j = TakMissionManager.joinedFeed
        joined.text = if (j != null) "Joined: $j" else "Not in a feed."
    }

    private fun addFeedRow(f: TakMissionClient.Feed) {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setBackgroundColor(Color.parseColor("#1A1F26"))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (8 * d).toInt()
        row.layoutParams = lp

        val name = TextView(this).apply {
            text = f.name + (if (f.passwordProtected) "  🔒" else "")
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(name)

        val meta = TextView(this).apply {
            val role = f.defaultRole?.takeIf { it.isNotEmpty() } ?: "—"
            text = "${f.itemCount} items · role $role" +
                    (if (!f.description.isNullOrEmpty()) "\n${f.description}" else "")
            setTextColor(Color.parseColor("#9AA4B2")); textSize = 12f
        }
        row.addView(meta)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val isJoined = f.name == TakMissionManager.joinedFeed
        val btn = Button(this).apply { text = if (isJoined) "Leave" else "Join" }
        btn.setOnClickListener {
            if (isJoined) {
                TakMissionManager.leaveFeed { ok ->
                    Toast.makeText(this, if (ok) "Left ${f.name}" else "Leave failed", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            } else {
                if (f.passwordProtected) promptPasswordThenJoin(f.name) else doJoin(f.name, null)
            }
        }
        btnRow.addView(btn)

        // Delete only when THIS drone created the feed (owner). Server enforces owner-only too.
        val isCreator = f.creatorUid != null && f.creatorUid == TakMissionManager.myUid()
        if (isCreator) {
            val del = Button(this).apply {
                text = "Delete"
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B71C1C"))
                setTextColor(Color.WHITE)
            }
            val dlp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            dlp.marginStart = (8 * d).toInt()
            del.layoutParams = dlp
            del.setOnClickListener { confirmDelete(f.name) }
            btnRow.addView(del)
        }
        row.addView(btnRow)
        list.addView(row)
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete feed?")
            .setMessage("Delete \"$name\" from the TAK Server for everyone? This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                status.text = "Deleting $name…"
                TakMissionManager.deleteFeed(name) { ok ->
                    Toast.makeText(this, if (ok) "Deleted $name" else "Delete failed (owner only)", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .show()
    }

    private fun promptPasswordThenJoin(name: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Feed password"
        }
        AlertDialog.Builder(this)
            .setTitle("Join $name")
            .setView(input)
            .setPositiveButton("Join") { _, _ -> doJoin(name, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doJoin(name: String, password: String?) {
        status.text = "Joining $name…"
        TakMissionManager.joinFeed(name, password) { ok ->
            Toast.makeText(this, if (ok) "Joined $name" else "Join failed", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun showCreateDialog() {
        val d = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val nameIn = EditText(this).apply { hint = "Feed name" }
        val descIn = EditText(this).apply { hint = "Description (optional)" }
        col.addView(nameIn); col.addView(descIn)

        // Default role for users who join this feed.
        val roleLabel = TextView(this).apply {
            text = "Default role for members who join:"
            setTextColor(Color.parseColor("#9AA4B2")); textSize = 12f
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
        col.addView(roleLabel)
        val roleLabels = arrayOf("Subscriber (add/edit items)", "Read-Only (view only)", "Owner (full admin)")
        val roleValues = arrayOf("MISSION_SUBSCRIBER", "MISSION_READONLY_SUBSCRIBER", "MISSION_OWNER")
        val roleSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@DataSyncActivity,
                android.R.layout.simple_spinner_dropdown_item, roleLabels)
            setSelection(0)
        }
        col.addView(roleSpinner)

        // Channel (group) the feed is scoped to — who can see it.
        val chanLabel = TextView(this).apply {
            text = "Channel (who can access this feed):"
            setTextColor(Color.parseColor("#9AA4B2")); textSize = 12f
            setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
        }
        col.addView(chanLabel)
        val chanSpinner = android.widget.Spinner(this)
        col.addView(chanSpinner)
        // Populate with the cert's channels; first entry = default scope (no group param).
        val chanValues = ArrayList<String?>().apply { add(null) }
        val chanNames = ArrayList<String>().apply { add("Default (my scope)") }
        chanSpinner.adapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, chanNames)
        TakMissionManager.listGroups { groups ->
            for (g in groups) { chanValues.add(g); chanNames.add(g) }
            (chanSpinner.adapter as android.widget.ArrayAdapter<*>).notifyDataSetChanged()
        }

        AlertDialog.Builder(this)
            .setTitle("Create Feed")
            .setView(col)
            .setPositiveButton("Create") { _, _ ->
                val n = nameIn.text.toString().trim()
                if (n.isEmpty()) { Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val role = roleValues[roleSpinner.selectedItemPosition]
                val group = chanValues.getOrNull(chanSpinner.selectedItemPosition)
                status.text = "Creating $n…"
                TakMissionManager.createFeed(n, descIn.text.toString().trim().ifEmpty { null }, role, group) { ok ->
                    Toast.makeText(this, if (ok) "Created $n" else "Create failed", Toast.LENGTH_SHORT).show()
                    if (ok) doJoin(n, null) else refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

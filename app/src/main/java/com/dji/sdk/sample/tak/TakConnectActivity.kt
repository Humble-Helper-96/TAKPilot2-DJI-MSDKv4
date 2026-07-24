package com.dji.sdk.sample.tak

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.taklite.client.tak.CotBuilder
import com.taklite.client.tak.TakCertEnroller
import com.taklite.client.tak.TakManager
import com.dji.sdk.sample.R
import com.dji.sdk.sample.takpilot2.MaplibreStyle
import com.taklite.util.AppLog
import java.io.File
import java.util.UUID

/**
 * Minimal TAK enroll + connect screen for TAKPilot2.
 *
 * Reuses taklite's TakCertEnroller (cert enrollment over HTTPS) and TakManager
 * (TLS CoT client). On success it starts a DroneTakBridge that streams the M30's
 * position to the server as an air track. This is the fast path to verify
 * drone -> CoT -> TAK end-to-end; the full QR enrollment wizard comes later.
 */
class TakConnectActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var videoStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tak_connect)
        AppLog.v(TAG, "onCreate")

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setupDroneSettings(prefs)
        setupMapDisplay()
        setupDtedSection()

        val host = findViewById<EditText>(R.id.takHost)
        val enrollPort = findViewById<EditText>(R.id.takEnrollPort)
        val cotPort = findViewById<EditText>(R.id.takCotPort)
        val username = findViewById<EditText>(R.id.takUsername)
        val password = findViewById<EditText>(R.id.takPassword)
        val callsign = findViewById<EditText>(R.id.takCallsign)
        status = findViewById(R.id.takStatus)

        // Restore last-used values (except password).
        host.setText(prefs.getString(KEY_HOST, ""))
        enrollPort.setText(prefs.getInt(KEY_ENROLL_PORT, 8446).toString())
        cotPort.setText(prefs.getInt(KEY_COT_PORT, 8089).toString())
        username.setText(prefs.getString(KEY_USERNAME, ""))
        callsign.setText(prefs.getString(KEY_CALLSIGN, "sUAS"))

        // Camera look-point toggle (applies live to the running bridge + persists).
        val cameraPoint = findViewById<android.widget.CheckBox>(R.id.takCameraPoint)
        cameraPoint.isChecked = prefs.getBoolean(KEY_CAMERA_POINT, false)
        cameraPoint.setOnCheckedChangeListener { _, isOn ->
            prefs.edit().putBoolean(KEY_CAMERA_POINT, isOn).apply()
            TakBridgeHolder.setCameraPointEnabled(isOn)
        }
        TakBridgeHolder.setCameraPointEnabled(cameraPoint.isChecked)

        // My Channels: restore saved selection → apply to TakManager, wire the Pull button.
        selectedChannels = loadChannels(prefs).toMutableSet()
        TakManager.getInstance().setChannels(selectedChannels.toList())
        renderChannels(selectedChannels.toList())   // show saved selection immediately
        findViewById<Button>(R.id.takPullChannels).setOnClickListener { pullChannels(prefs) }

        // Reflect live state on open, and silently reconnect with saved certs if the
        // socket isn't up — so the user never has to re-enter credentials / re-enroll.
        when {
            TakManager.getInstance().isConnected ->
                setStatus("Connected. Drone PLI streaming.", Color.parseColor("#4CAF50"))
            prefs.getBoolean(KEY_LOGGED_OUT, false) ->
                setStatus("Logged out. Enter host, username and password to sign in.", Color.parseColor("#B0B0B0"))
            hasSavedCerts(prefs) -> {
                setStatus("Reconnecting with saved enrollment …", Color.parseColor("#B0B0B0"))
                reconnectFromSaved(prefs, callsign.text.toString().trim().ifEmpty { "sUAS" })
            }
            else -> setStatus("Not connected.", Color.parseColor("#B0B0B0"))
        }

        findViewById<Button>(R.id.takConnectButton).setOnClickListener {
            AppLog.v(TAG, "tap: Connect")
            val h = host.text.toString().trim()
            val u = username.text.toString().trim()
            val p = password.text.toString()
            val cs = callsign.text.toString().trim().ifEmpty { "sUAS" }
            val ep = enrollPort.text.toString().trim().toIntOrNull() ?: 8446
            val cp = cotPort.text.toString().trim().toIntOrNull() ?: 8089

            if (TakManager.getInstance().isConnected) {
                setStatus("Already connected.", Color.parseColor("#4CAF50"))
                return@setOnClickListener
            }
            prefs.edit()
                .putString(KEY_HOST, h.ifEmpty { prefs.getString(KEY_HOST, "") })
                .putInt(KEY_ENROLL_PORT, ep)
                .putInt(KEY_COT_PORT, cp)
                .putString(KEY_USERNAME, u.ifEmpty { prefs.getString(KEY_USERNAME, "") })
                .putString(KEY_CALLSIGN, cs)
                .apply()

            // If we already enrolled before, reconnect with saved certs — no password needed.
            if (hasSavedCerts(prefs) && p.isEmpty()) {
                reconnectFromSaved(prefs, cs)
                return@setOnClickListener
            }
            if (h.isEmpty() || u.isEmpty() || p.isEmpty()) {
                setStatus("Host, username and password are required for first enrollment.",
                    Color.parseColor("#F44336"))
                return@setOnClickListener
            }
            enrollAndConnect(h, ep, cp, u, p, cs)
        }

        findViewById<Button>(R.id.takDisconnectButton).setOnClickListener {
            AppLog.v(TAG, "tap: Disconnect / Log out")
            // Full LOG OUT: stop everything AND clear the saved enrollment so the app won't silently
            // reconnect the old user, and a different user can enroll cleanly. Each teardown step is
            // guarded — a throw from the closing socket must NOT abort the logout (that crash was
            // why logout never stuck). clearEnrollment + the logged-out flag always run.
            runCatching { VideoStreamerHolder.stop() }
            runCatching { TakBridgeHolder.stop() }
            runCatching { TakManager.getInstance().disconnect() }
            runCatching { TakManager.getInstance().setChannels(emptyList()) }
            runCatching { TakForegroundService.stop(applicationContext) }
            runCatching { clearEnrollment(prefs) }
            // Reset the UI fields so it's clearly a fresh login.
            username.setText("")
            password.setText("")
            selectedChannels.clear()
            runCatching { findViewById<android.widget.LinearLayout>(R.id.takChannelsList).removeAllViews() }
            runCatching { findViewById<TextView>(R.id.takChannelsStatus).text = "" }
            setStatus("Logged out. Enter host, username and password to sign in as another user.",
                Color.parseColor("#B0B0B0"))
        }

        setupVideoControls(prefs)
    }

    /** Drone flight-limit fields — auto-saved as the pilot types (no submit action needed;
     *  these are just local prefs, applied to the aircraft on its next connect by
     *  FlightLimitsController, not sent anywhere from here). */
    private fun setupDroneSettings(prefs: android.content.SharedPreferences) {
        val maxAlt = findViewById<EditText>(R.id.limitMaxAltitude)
        val maxRadius = findViewById<EditText>(R.id.limitMaxRadius)
        val rthAlt = findViewById<EditText>(R.id.limitRthAltitude)

        maxAlt.setText(FlightLimitsController.savedMaxAltitudeFt(this))
        maxRadius.setText(FlightLimitsController.savedMaxRadiusFt(this))
        rthAlt.setText(FlightLimitsController.savedRthAltitudeFt(this))

        val save = {
            FlightLimitsController.save(
                this, maxAlt.text.toString(), maxRadius.text.toString(), rthAlt.text.toString(),
            )
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = save()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(maxAlt, maxRadius, rthAlt).forEach { it.addTextChangedListener(watcher) }
    }

    /** Mini-map style choice — explicit "Save" button since a URL field benefits from not
     *  saving mid-edit on every keystroke, unlike the drone-limit fields above. */
    private fun setupMapDisplay() {
        val group = findViewById<RadioGroup>(R.id.mapStyleGroup)
        val customUrl = findViewById<EditText>(R.id.mapCustomUrl)

        when (MaplibreStyle.savedStyleChoice(this)) {
            "street" -> group.check(R.id.mapStyleStreet)
            "custom" -> group.check(R.id.mapStyleCustom)
            else -> group.check(R.id.mapStyleHybrid)
        }
        customUrl.setText(MaplibreStyle.savedCustomUrl(this))

        findViewById<Button>(R.id.mapDisplaySaveButton).setOnClickListener {
            val choice = when (group.checkedRadioButtonId) {
                R.id.mapStyleStreet -> "street"
                R.id.mapStyleCustom -> "custom"
                else -> "hybrid"
            }
            if (choice == "custom" && customUrl.text.toString().isBlank()) {
                Toast.makeText(this, "Enter a custom tile URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MaplibreStyle.saveStyleChoice(this, choice, customUrl.text.toString())
            Toast.makeText(this, "Map display saved", Toast.LENGTH_SHORT).show()
        }
    }

    /** DTED elevation-file management — upload via the system document picker (any file;
     *  DTED extensions aren't a registered MIME type so we don't filter by type), copy it
     *  into app storage, list what's uploaded, allow deleting. */
    private fun setupDtedSection() {
        findViewById<Button>(R.id.dtedUploadButton).setOnClickListener {
            AppLog.v(TAG, "tap: Upload DTED File")
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_CODE_DTED_PICK)
        }
        renderDtedFiles()
    }

    private fun renderDtedFiles() {
        val list = findViewById<LinearLayout>(R.id.dtedFileList)
        val status = findViewById<TextView>(R.id.dtedStatus)
        list.removeAllViews()
        val files = DtedStore.listFiles(this)
        status.text = if (files.isEmpty()) "No DTED files uploaded." else "${files.size} file(s) uploaded."
        for (file in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            val name = TextView(this).apply {
                text = "${file.name}  (${file.length() / 1024} KB)"
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delete = Button(this).apply {
                text = "Delete"
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B71C1C"))
                setOnClickListener {
                    AppLog.v(TAG, "tap: delete DTED file ${file.name}")
                    DtedStore.delete(file)
                    renderDtedFiles()
                }
            }
            row.addView(name)
            row.addView(delete)
            list.addView(row)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_DTED_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = queryDisplayName(uri) ?: "dted-${System.currentTimeMillis()}"
        val status = findViewById<TextView>(R.id.dtedStatus)
        val result = DtedStore.import(this, uri, name)
        status.text = when {
            result.error != null && result.importedCount == 0 -> "Failed to import $name: ${result.error}"
            result.error != null -> "Imported ${result.importedCount} tile(s) from $name (${result.error})"
            name.lowercase().endsWith(".zip") -> "Imported ${result.importedCount} tile(s) from $name."
            else -> "Uploaded $name."
        }
        if (result.importedCount == 0) Toast.makeText(this, status.text, Toast.LENGTH_SHORT).show()
        renderDtedFiles()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    private fun setupVideoControls(prefs: android.content.SharedPreferences) {
        val vHost = findViewById<EditText>(R.id.videoHost)
        val vPort = findViewById<EditText>(R.id.videoPort)
        val vUser = findViewById<EditText>(R.id.videoUser)
        val vPass = findViewById<EditText>(R.id.videoPassword)
        val vStreamId = findViewById<EditText>(R.id.videoStreamId)
        val vTcp = findViewById<android.widget.CheckBox>(R.id.videoTcp)
        val vFullUrl = findViewById<TextView>(R.id.videoFullUrl)
        videoStatus = findViewById(R.id.videoStatus)

        vHost.setText(prefs.getString(KEY_V_HOST, ""))
        vPort.setText(prefs.getInt(KEY_V_PORT, 8554).toString())
        vUser.setText(prefs.getString(KEY_V_USER, ""))
        vStreamId.setText(prefs.getString(KEY_V_STREAMID, ""))
        vTcp.isChecked = prefs.getBoolean(KEY_V_TCP, true)

        fun buildConfig(): DroneVideoStreamer.VideoConfig = DroneVideoStreamer.VideoConfig(
            host = vHost.text.toString().trim(),
            port = vPort.text.toString().trim().toIntOrNull() ?: 8554,
            username = vUser.text.toString().trim(),
            password = vPass.text.toString(),
            streamId = vStreamId.text.toString().trim(),
            tcp = vTcp.isChecked,
        )

        // Live-update the full-URL preview as fields change.
        val refreshUrl = {
            val c = buildConfig()
            vFullUrl.text = if (c.host.isEmpty() || c.streamId.isEmpty())
                "rtsp://…  (enter host + identifier)" else c.urlSafe()
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshUrl()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(vHost, vPort, vUser, vPass, vStreamId).forEach { it.addTextChangedListener(watcher) }
        vTcp.setOnCheckedChangeListener { _, _ -> refreshUrl() }
        refreshUrl()

        findViewById<Button>(R.id.videoStartButton).setOnClickListener {
            val cfg = buildConfig()
            if (cfg.host.isEmpty() || cfg.streamId.isEmpty()) {
                setVideoStatus("Host and broadcast identifier are required.", Color.parseColor("#F44336"))
                return@setOnClickListener
            }
            prefs.edit()
                .putString(KEY_V_HOST, cfg.host)
                .putInt(KEY_V_PORT, cfg.port)
                .putString(KEY_V_USER, cfg.username)
                .putString("video_pass", cfg.password) // so the flight-screen button can start it
                .putString(KEY_V_STREAMID, cfg.streamId)
                .putBoolean(KEY_V_TCP, cfg.tcp)
                .apply()

            setVideoStatus("Starting RTSP push → ${cfg.urlSafe()}", Color.parseColor("#B0B0B0"))
            VideoStreamerHolder.start(this, cfg) { ok, msg ->
                runOnUiThread {
                    if (ok) {
                        // Advertise the full UAS-tool-style URL (creds + ?tcp) in the drone CoT.
                        TakBridgeHolder.setVideoUrl(cfg.advertiseUrl())
                        setVideoStatus("$msg\nAdvertised in drone CoT.", Color.parseColor("#4CAF50"))
                    } else {
                        setVideoStatus(msg, Color.parseColor("#F44336"))
                    }
                }
            }
        }

        findViewById<Button>(R.id.videoStopButton).setOnClickListener {
            VideoStreamerHolder.stop()
            TakBridgeHolder.setVideoUrl(null)
            setVideoStatus("Video stopped; CoT video URL cleared.", Color.parseColor("#B0B0B0"))
        }
    }

    private fun enrollAndConnect(
        host: String, enrollPort: Int, cotPort: Int,
        username: String, password: String, droneCallsign: String,
    ) {
        setStatus("Enrolling with $host:$enrollPort …", Color.parseColor("#B0B0B0"))

        // Stable operator uid persisted across sessions.
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        // The drone gets its own distinct uid so it shows as a separate air track.
        val droneUid = "$uid-DRONE"

        Thread {
            TakCertEnroller.enroll(host, enrollPort, username, password, uid, filesDir,
                object : TakCertEnroller.EnrollmentCallback {
                    override fun onSuccess(trustStorePath: String, clientCertPath: String) {
                        // Persist certs so we never have to re-enroll — future connects reuse these.
                        prefs.edit()
                            .putString(KEY_TRUSTSTORE, trustStorePath)
                            .putString(KEY_CLIENTCERT, clientCertPath)
                            .putBoolean(KEY_LOGGED_OUT, false)   // new enrollment → allow auto-reconnect again
                            .apply()
                        runOnUiThread { setStatus("Enrolled. Connecting …", Color.parseColor("#B0B0B0")) }
                        connectWithCerts(uid, username, droneUid, droneCallsign,
                            host, cotPort, trustStorePath, clientCertPath)
                    }

                    override fun onError(error: String) {
                        runOnUiThread { setStatus("Error: $error", Color.parseColor("#F44336")) }
                    }
                })
        }.start()
    }

    /** Connect using already-enrolled cert files (no re-enrollment / re-entry of password). */
    private fun connectWithCerts(
        uid: String, username: String, droneUid: String, droneCallsign: String,
        host: String, cotPort: Int, trustStorePath: String, clientCertPath: String,
    ) {
        val certPw = "atakatak"
        TakManager.getInstance().connect(
            uid, username, "Cyan", "Team Member",
            host, cotPort, trustStorePath, certPw, clientCertPath, certPw,
        )
        runOnUiThread {
            setStatus("Connected. Streaming drone PLI as \"$droneCallsign\".",
                Color.parseColor("#4CAF50"))
            TakBridgeHolder.start(droneUid, droneCallsign)
            TakForegroundService.start(applicationContext, droneCallsign)
        }
    }

    /** Reconnect using saved certs + saved server settings, no UI entry needed. */
    private fun reconnectFromSaved(prefs: android.content.SharedPreferences, droneCallsign: String) {
        val host = prefs.getString(KEY_HOST, "") ?: ""
        val username = prefs.getString(KEY_USERNAME, "") ?: ""
        val cotPort = prefs.getInt(KEY_COT_PORT, 8089)
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        var uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isEmpty()) {
            uid = "TAKPilot2-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_UID, uid).apply()
        }
        if (host.isEmpty() || ts.isEmpty() || cc.isEmpty()) {
            setStatus("Saved enrollment incomplete — enroll again.", Color.parseColor("#F44336"))
            return
        }
        Thread { connectWithCerts(uid, username, "$uid-DRONE", droneCallsign, host, cotPort, ts, cc) }.start()
    }

    /** Delete the saved enrollment (cert files + prefs) so a different user can sign in clean. */
    private fun clearEnrollment(prefs: android.content.SharedPreferences) {
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        if (ts.isNotEmpty()) { val f = java.io.File(ts); val ok = runCatching { f.delete() }.getOrDefault(false); com.taklite.util.AppLog.i("TakConnect", "delete truststore $ts -> $ok (exists=${f.exists()})") }
        if (cc.isNotEmpty()) { val f = java.io.File(cc); val ok = runCatching { f.delete() }.getOrDefault(false); com.taklite.util.AppLog.i("TakConnect", "delete clientcert $cc -> $ok (exists=${f.exists()})") }
        // Also nuke any cert files by their well-known names, in case the prefs paths drifted.
        listOf("tak_clientcert.p12", "tak_truststore.p12").forEach {
            val f = java.io.File(filesDir, it); if (f.exists()) { val ok = runCatching { f.delete() }.getOrDefault(false); com.taklite.util.AppLog.i("TakConnect", "delete $it -> $ok") }
        }
        prefs.edit()
            .remove(KEY_TRUSTSTORE)
            .remove(KEY_CLIENTCERT)
            .remove(KEY_UID)
            .remove(KEY_USERNAME)
            .remove(KEY_CHANNELS)
            .putBoolean(KEY_LOGGED_OUT, true)   // block auto-reconnect until a fresh enroll
            .apply()
        com.taklite.util.AppLog.i("TakConnect", "enrollment cleared")
    }

    /** True if we have saved cert files on disk from a previous enrollment. */
    private fun hasSavedCerts(prefs: android.content.SharedPreferences): Boolean {
        val ts = prefs.getString(KEY_TRUSTSTORE, "") ?: ""
        val cc = prefs.getString(KEY_CLIENTCERT, "") ?: ""
        return ts.isNotEmpty() && cc.isNotEmpty() &&
            java.io.File(ts).exists() && java.io.File(cc).exists()
    }

    private fun setStatus(text: String, color: Int) {
        status.text = text
        status.setTextColor(color)
    }

    private fun setVideoStatus(text: String, color: Int) {
        videoStatus.text = text
        videoStatus.setTextColor(color)
    }

    // ---- My Channels ----
    private var selectedChannels: MutableSet<String> = mutableSetOf()

    private fun loadChannels(prefs: android.content.SharedPreferences): List<String> =
        (prefs.getString(KEY_CHANNELS, "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun saveChannels(prefs: android.content.SharedPreferences) {
        prefs.edit().putString(KEY_CHANNELS, selectedChannels.joinToString(",")).apply()
        TakManager.getInstance().setChannels(selectedChannels.toList())
    }

    /** Pull the channels the logged-in user belongs to from the TAK server (needs a connection). */
    private fun pullChannels(prefs: android.content.SharedPreferences) {
        val chanStatus = findViewById<TextView>(R.id.takChannelsStatus)
        if (!TakManager.getInstance().isConnected) {
            chanStatus.text = "Connect to TAK first, then pull channels."
            chanStatus.setTextColor(Color.parseColor("#F44336"))
            return
        }
        chanStatus.text = "Pulling channels…"
        chanStatus.setTextColor(Color.parseColor("#B0B0B0"))
        TakMissionManager.listMyChannels { chans ->
            if (chans.isEmpty()) {
                chanStatus.text = "No channels found for this login."
            } else {
                chanStatus.text = "${chans.size} channel(s). Check the ones to publish to."
            }
            // Keep any previously-selected channels even if not returned this pull.
            val all = (chans + selectedChannels).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            renderChannels(all)
        }
    }

    /** Render a checkbox per channel; toggling saves the selection + applies it to routing. */
    private fun renderChannels(channels: List<String>) {
        val list = findViewById<android.widget.LinearLayout>(R.id.takChannelsList)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        list.removeAllViews()
        for (name in channels) {
            val cb = android.widget.CheckBox(this).apply {
                text = name
                setTextColor(Color.WHITE)
                isChecked = selectedChannels.contains(name)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedChannels.add(name) else selectedChannels.remove(name)
                    saveChannels(prefs)
                }
            }
            list.addView(cb)
        }
    }

    companion object {
        private const val TAG = "TakConnectActivity"
        private const val REQUEST_CODE_DTED_PICK = 2001
        private const val PREFS = "takpilot2_tak"
        private const val KEY_HOST = "host"
        private const val KEY_ENROLL_PORT = "enroll_port"
        private const val KEY_COT_PORT = "cot_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_CALLSIGN = "callsign"
        private const val KEY_CAMERA_POINT = "camera_point"
        private const val KEY_CHANNELS = "channels"          // CSV of selected channel names
        private const val KEY_LOGGED_OUT = "logged_out"      // true = user logged out; block auto-reconnect
        private const val KEY_UID = "uid"
        private const val KEY_TRUSTSTORE = "truststore_path"
        private const val KEY_CLIENTCERT = "clientcert_path"
        private const val KEY_V_HOST = "video_host"
        private const val KEY_V_PORT = "video_port"
        private const val KEY_V_USER = "video_user"
        private const val KEY_V_STREAMID = "video_streamid"
        private const val KEY_V_TCP = "video_tcp"
    }
}


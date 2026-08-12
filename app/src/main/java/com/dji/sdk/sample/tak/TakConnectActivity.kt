package com.dji.sdk.sample.tak

import androidx.core.content.ContextCompat
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tak_connect)

        // Menu button on the left of the action bar, on every screen you can reach from Home.
        // Returns to the home screen, same as the system back gesture — a pilot should not have
        // to learn a different way out of each screen.
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }
        AppLog.v(TAG, "onCreate")

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setupDroneSettings(prefs)
        setupMapDisplay()
        setupDtedSection()
        setupUasfmSection()

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
        // The display list already reflects any auto-pull that ran at app launch
        // (TakAutoConnect.reconnect → TakChannelsStore.pull), so this screen opens with a
        // current list even if the pilot never taps Pull Channels themselves.
        selectedChannels = TakChannelsStore.selected(this).toMutableSet()
        TakManager.getInstance().setChannels(selectedChannels.toList())
        renderChannels(TakChannelsStore.displayList(this))
        findViewById<Button>(R.id.takPullChannels).setOnClickListener { pullChannels() }

        // Reflect live state on open. Auto-connect already happened at app launch
        // (TakAutoConnect.attemptOnAppLaunch, from the home screen) — if we're still not
        // connected but have saved certs, try again here too (covers the case where this
        // screen is opened before that first attempt lands, or after a manual disconnect).
        when {
            TakManager.getInstance().isConnected ->
                setStatus("Connected. Drone PLI streaming.", ContextCompat.getColor(applicationContext, R.color.tp_state_go))
            prefs.getBoolean(KEY_LOGGED_OUT, false) ->
                setStatus("Logged out. Enter host, username and password to sign in.", ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
            hasSavedCerts(prefs) -> {
                setStatus("Reconnecting with saved enrollment …", ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
                reconnectFromSaved(prefs, callsign.text.toString().trim().ifEmpty { "sUAS" })
            }
            else -> setStatus("Not connected.", ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
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
                setStatus("Already connected.", ContextCompat.getColor(applicationContext, R.color.tp_state_go))
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
                    ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
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
            runCatching { TakChannelsStore.clearAll(this) }
            runCatching { findViewById<android.widget.LinearLayout>(R.id.takChannelsList).removeAllViews() }
            runCatching { findViewById<TextView>(R.id.takChannelsStatus).text = "" }
            setStatus("Logged out. Enter host, username and password to sign in as another user.",
                ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        }

        setupVideoControls(prefs)
        // LAST, deliberately. Every setup above populates and enables its own fields, so a lock
        // applied before them would be undone on the way past.
        setupConfigLocks()
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

        val limitsWarning = findViewById<TextView>(R.id.limitsWarning)

        /**
         * RTH ALTITUDE ABOVE MAX ALTITUDE IS A REAL CONFIGURATION, AND A BAD ONE.
         *
         * Each field is validated only against the aircraft's own accepted range (20-500m), so
         * both can be individually legal while contradicting each other. The aircraft is then
         * told to climb to an RTH height its own ceiling forbids, and what it does about that is
         * firmware's decision, not the pilot's — it may clamp, or it may refuse the climb and
         * come home low, straight through whatever the pilot set a ceiling to stay above.
         *
         * A warning rather than a block: the pilot may be mid-edit, and refusing input on the way
         * to a valid pair is its own hazard. It says what will happen and lets them fix it.
         */
        fun checkLimits() {
            val maxFt = maxAlt.text.toString().trim().toDoubleOrNull()
            val rthFt = rthAlt.text.toString().trim().toDoubleOrNull()
            if (maxFt != null && rthFt != null && rthFt > maxFt) {
                limitsWarning.text = "RTH altitude (${rthFt.toInt()} ft) is above max altitude " +
                    "(${maxFt.toInt()} ft). The aircraft cannot climb to its return height."
                limitsWarning.visibility = View.VISIBLE
            } else {
                limitsWarning.visibility = View.GONE
            }
        }

        val save = {
            FlightLimitsController.save(
                this, maxAlt.text.toString(), maxRadius.text.toString(), rthAlt.text.toString(),
            )
            checkLimits()
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = save()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(maxAlt, maxRadius, rthAlt).forEach { it.addTextChangedListener(watcher) }
        // Run once on open: a contradiction saved by an earlier build must surface without the
        // pilot having to touch a field.
        checkLimits()

        setupBattery()
        setupStickMode()
        setupFailsafe()
        setupAvoidance()
        setupApplyButton()
    }

    /** Battery warning/critical levels. Saved as typed, like the limits above; pushed to the
     *  aircraft only on Apply, because these decide when it comes home on its own. */
    private fun setupBattery() {
        val low = findViewById<EditText>(R.id.limitLowBattery)
        val crit = findViewById<EditText>(R.id.limitCriticalBattery)
        low.setText(FlightLimitsController.savedLowBatteryPct(this))
        crit.setText(FlightLimitsController.savedCriticalBatteryPct(this))
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                FlightLimitsController.saveBattery(
                    this@TakConnectActivity, low.text.toString(), crit.text.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(low, crit).forEach { it.addTextChangedListener(watcher) }
        renderBatteryReadBack()
    }

    /** Shows what the AIRCRAFT reports, not what was typed. Blank until a read-back has landed —
     *  "unknown" and "what you asked for" must not look the same. */
    private fun renderBatteryReadBack() {
        val w = FlightLimitsController.aircraftWarningPct
        val c = FlightLimitsController.aircraftCriticalPct
        findViewById<TextView>(R.id.limitBatteryStatus).text =
            if (w == null && c == null) ""
            else "Aircraft reports: warning ${w?.let { "$it%" } ?: "—"}, " +
                "critical ${c?.let { "$it%" } ?: "—"}"
    }

    private fun setupStickMode() {
        val group = findViewById<RadioGroup>(R.id.stickModeGroup)
        fun idFor(m: FlightLimitsController.StickMode) = when (m) {
            FlightLimitsController.StickMode.MODE_1 -> R.id.stickMode1
            FlightLimitsController.StickMode.MODE_2 -> R.id.stickMode2
            FlightLimitsController.StickMode.MODE_3 -> R.id.stickMode3
        }
        group.check(idFor(FlightLimitsController.savedStickMode(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            val choice = when (checkedId) {
                R.id.stickMode1 -> FlightLimitsController.StickMode.MODE_1
                R.id.stickMode3 -> FlightLimitsController.StickMode.MODE_3
                else -> FlightLimitsController.StickMode.MODE_2
            }
            FlightLimitsController.saveStickMode(this, choice)
            AppLog.i(TAG, "stick mode selected: ${choice.label} (sent on Apply)")
        }
    }

    /**
     * Pushes everything in this section to the aircraft now, and reads it back.
     *
     * The button disables for the duration. Without that a pilot can queue a second push on top
     * of a running one, and the SDK's callbacks then interleave in an order nobody can reason
     * about — for settings that decide when the aircraft flies itself home.
     */
    private fun setupApplyButton() {
        val button = findViewById<Button>(R.id.limitApplyButton)
        val bar = findViewById<android.widget.ProgressBar>(R.id.limitApplyProgress)
        val status = findViewById<TextView>(R.id.limitApplyStatus)
        button.setOnClickListener {
            AppLog.v(TAG, "tap: Apply to Aircraft")
            button.isEnabled = false
            bar.visibility = View.VISIBLE
            bar.progress = 0
            status.setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_tertiary))
            FlightLimitsController.applyToAircraft(
                this,
                onProgress = { done, total, name ->
                    bar.max = total
                    bar.progress = done
                    status.text = "Applying ($done/$total): $name …"
                },
                onDone = { ok, summary ->
                    button.isEnabled = true
                    bar.visibility = View.GONE
                    status.text = summary
                    status.setTextColor(ContextCompat.getColor(applicationContext,
                        if (ok) R.color.tp_state_go else R.color.tp_state_unknown))
                    renderBatteryReadBack()
                },
            )
        }
    }

    private val takLockedFields = listOf(
        R.id.takHost, R.id.takEnrollPort, R.id.takCotPort,
        R.id.takUsername, R.id.takPassword, R.id.takCallsign,
        R.id.takDisconnectButton,
    )

    /** The server fields only. The quality profile stays live on purpose — it is an in-flight
     *  choice about bandwidth, not part of what the stream IS. */
    private val videoLockedFields = listOf(
        R.id.videoHost, R.id.videoPort, R.id.videoStreamId,
        R.id.videoUser, R.id.videoPassword, R.id.videoTcp,
    )

    /**
     * Per-section locks over settings that are painful to get wrong and rarely need changing.
     *
     * Unlocking asks for a password; locking does not. The asymmetry is deliberate — locking is
     * the safe direction, and gating it would only train people to dismiss dialogs.
     */
    /** The aircraft-settings lock covers the numbers that decide when it flies itself home, and
     *  the stick mode — plus Apply, so a locked configuration cannot be pushed either. */
    private val aircraftLockedFields = listOf(
        R.id.limitMaxAltitude, R.id.limitMaxRadius, R.id.limitRthAltitude,
        R.id.limitLowBattery, R.id.limitCriticalBattery,
        R.id.stickMode1, R.id.stickMode2, R.id.stickMode3,
        R.id.failsafeGoHome, R.id.failsafeHover, R.id.failsafeLand,
        R.id.limitApplyButton,
    )

    private fun setupConfigLocks() {
        setupOneLock(
            R.id.limitBatteryLock, KEY_AIRCRAFT_LOCKED, aircraftLockedFields,
            "Unlock aircraft settings?",
            "These decide when the aircraft returns and lands on its own, and what the control " +
                "sticks do. A wrong value can force a landing away from the pilot.",
        )
        setupOneLock(
            R.id.takLockConfig, KEY_TAK_LOCKED, takLockedFields,
            "Unlock TAK server settings?",
            "The lock prevents an accidental change to a server that works. " +
                "A wrong value stops the aircraft sending data to your team.",
        )
        setupOneLock(
            R.id.videoLockConfig, KEY_VIDEO_LOCKED, videoLockedFields,
            "Unlock video server settings?",
            "These fields are locked so a working stream configuration is not changed by " +
                "accident. Editing them can stop your team seeing the video.",
        )
    }

    private fun setupOneLock(
        checkBoxId: Int,
        prefKey: String,
        fieldIds: List<Int>,
        confirmTitle: String,
        confirmBody: String,
    ) {
        val box = findViewById<android.widget.CheckBox>(checkBoxId)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Default UNLOCKED on a fresh install — a first-run pilot must not have to discover a
        // lock before they can type anything.
        val locked = prefs.getBoolean(prefKey, false)
        box.isChecked = locked
        applyLock(fieldIds, locked)

        box.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                prefs.edit().putBoolean(prefKey, true).apply()
                applyLock(fieldIds, true)
                AppLog.v(TAG, "config locked: $prefKey")
                return@setOnCheckedChangeListener
            }
            // Unlocking: ask for the password, and put the box BACK unless it is right. Our own
            // revert would re-enter this listener, so it is detached around it (inside revert()).
            //
            // A wrong password and Cancel take the same path on purpose: the only way out of this
            // dialog with the fields editable is the correct password.
            val revert = {
                box.setOnCheckedChangeListener(null)
                box.isChecked = true
                setupConfigLocks()
            }
            // Built in code rather than a layout: one field, two call sites, and a layout file
            // would imply this dialog can grow. It must not — it is a speed bump. A programmatic
            // EditText takes the PLATFORM's colours rather than the app theme's, so every colour
            // is set explicitly or it renders black-on-black.
            val pw = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                // Built in code, so takFieldStyle's flagNoExtractUi does not reach it — see
                // that style for why a landscape-locked screen needs it.
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                hint = "Password"
                textSize = 15f
                setTextColor(ContextCompat.getColor(
                    this@TakConnectActivity, R.color.tp_text_primary))
                setHintTextColor(ContextCompat.getColor(
                    this@TakConnectActivity, R.color.tp_text_hint))
                setBackgroundResource(R.drawable.bg_dialog_field)
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val wrap = android.widget.FrameLayout(this).apply {
                val padH = (16 * resources.displayMetrics.density).toInt()
                val padV = (8 * resources.displayMetrics.density).toInt()
                setPadding(padH, padV, padH, padV)
                addView(pw)
            }
            android.app.AlertDialog.Builder(this, R.style.TakDialogTheme_Destructive)
                .setTitle(confirmTitle)
                .setMessage(confirmBody)
                .setView(wrap)
                .setPositiveButton("Unlock") { _, _ ->
                    if (pw.text.toString() == UNLOCK_PASSWORD) {
                        prefs.edit().putBoolean(prefKey, false).apply()
                        applyLock(fieldIds, false)
                        // The entered text is never logged, right or wrong — same rule as every
                        // other credential in this app.
                        AppLog.i(TAG, "config UNLOCKED: $prefKey")
                    } else {
                        Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show()
                        AppLog.i(TAG, "unlock refused (wrong password): $prefKey")
                        revert()
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> revert() }
                .setOnCancelListener { revert() }
                .show()
        }
    }

    /**
     * Greys out and disables a set of views. `isEnabled = false` also makes them unfocusable, so
     * the keyboard cannot be raised on a locked field — read-only in the way a pilot means it —
     * and a disabled Button stops responding to taps.
     *
     * Typed as View, not EditText: the TAK lock covers the Log Out button as well as fields.
     */
    private fun applyLock(fieldIds: List<Int>, locked: Boolean) {
        for (id in fieldIds) {
            findViewById<View>(id)?.apply {
                isEnabled = !locked
                alpha = if (locked) 0.45f else 1.0f
            }
        }
    }

    /**
     * Obstacle-avoidance toggles.
     *
     * Different in kind from the limits above: those are offered to the aircraft, these are
     * ENFORCED on it at every connect (see [DjiObstacleState.applyAtConnect]). The status line
     * therefore reports what the AIRCRAFT currently says, not what the checkbox says — the whole
     * hazard being addressed is a pilot who believes avoidance is on because a box is ticked.
     */
    private fun setupAvoidance() {
        val system = findViewById<android.widget.CheckBox>(R.id.avoidSystem)
        val rth = findViewById<android.widget.CheckBox>(R.id.avoidRth)
        val landing = findViewById<android.widget.CheckBox>(R.id.avoidLanding)
        val status = findViewById<TextView>(R.id.avoidStatus)

        system.isChecked = DjiObstacleState.savedSystem(this)
        rth.isChecked = DjiObstacleState.savedRth(this)
        landing.isChecked = DjiObstacleState.savedLanding(this)

        val save = {
            DjiObstacleState.saveIntent(this, system.isChecked, rth.isChecked, landing.isChecked)
            AppLog.i("TP2Obstacle", "pre-flight avoidance intent: system=${system.isChecked} " +
                "rth=${rth.isChecked} landing=${landing.isChecked} (applies on next connect)")
            status.text = avoidanceStatusText()
        }
        listOf(system, rth, landing).forEach { it.setOnCheckedChangeListener { _, _ -> save() } }
        status.text = avoidanceStatusText()
    }

    /** Says what the aircraft actually reports, and is explicit when it has told us nothing —
     *  "not read yet" and "disabled" are different facts and must never be shown as the same one. */
    private fun avoidanceStatusText(): String {
        fun s(v: Boolean?) = when (v) {
            true -> "on"
            false -> "OFF"
            null -> "not read yet"
        }
        return "Aircraft currently reports: avoidance ${s(DjiObstacleState.collisionAvoidance)}, " +
            "RTH avoidance ${s(DjiObstacleState.rthAvoidance)}, " +
            "landing protection ${s(DjiObstacleState.landingProtection)}."
    }

    /** Signal-loss failsafe picker. Like the numeric limits above it's saved locally and pushed
     *  to the aircraft on its next connect — the status line spells that out, because "I picked
     *  Return to Home" and "the aircraft is actually set to Return to Home" are different
     *  claims, and this is a setting where assuming the first means the second is exactly the
     *  wrong habit. */
    private fun setupFailsafe() {
        val group = findViewById<RadioGroup>(R.id.limitFailsafeGroup)
        val status = findViewById<TextView>(R.id.limitFailsafeStatus)

        val idFor = { f: FlightLimitsController.Failsafe ->
            when (f) {
                FlightLimitsController.Failsafe.GO_HOME -> R.id.failsafeGoHome
                FlightLimitsController.Failsafe.HOVER -> R.id.failsafeHover
                FlightLimitsController.Failsafe.LAND -> R.id.failsafeLand
            }
        }
        group.check(idFor(FlightLimitsController.savedFailsafe(this)))

        status.text = "Sent to the aircraft the next time it connects. " +
            "Check the Debug Log for \"signal-loss behavior is now\" to confirm it took."

        group.setOnCheckedChangeListener { _, checkedId ->
            val choice = when (checkedId) {
                R.id.failsafeHover -> FlightLimitsController.Failsafe.HOVER
                R.id.failsafeLand -> FlightLimitsController.Failsafe.LAND
                else -> FlightLimitsController.Failsafe.GO_HOME
            }
            AppLog.i("TP2Limits", "signal-loss failsafe set to '${choice.label}' (applies on next connect)")
            FlightLimitsController.saveFailsafe(this, choice)
        }
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

    /** DTED elevation-region management — upload a region .zip via the system document picker
     *  (any file; DTED extensions aren't a registered MIME type so we don't filter by type),
     *  list imported regions (one row each — never individual tiles, see DtedStore/TerrainDatabase),
     *  allow deleting a whole region. */
    private fun setupDtedSection() {
        findViewById<Button>(R.id.dtedUploadButton).setOnClickListener {
            AppLog.v(TAG, "tap: Import Region")
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, REQUEST_CODE_DTED_PICK)
        }
        findViewById<Button>(R.id.dtedCleanButton).setOnClickListener {
            AppLog.v(TAG, "tap: Clean Unused Tiles")
            val removed = DtedStore.cleanUnreferencedTiles(this)
            Toast.makeText(this, "Removed $removed unreferenced tile file(s)", Toast.LENGTH_SHORT).show()
        }
        renderDtedRegions()
    }

    // ---- 6. FAA Airspace Ceilings (UASFM) ----

    /** Download UASFM ceilings for an area. Deliberately a manual, explicit action on wifi
     *  rather than anything automatic in flight: the flight screen must never depend on having
     *  a network, and a silent background fetch would be exactly the wrong thing to discover
     *  had failed while airborne. */
    private fun setupUasfmSection() {
        val latField = findViewById<EditText>(R.id.uasfmLat)
        val lonField = findViewById<EditText>(R.id.uasfmLon)
        val radiusField = findViewById<EditText>(R.id.uasfmRadius)
        val status = findViewById<TextView>(R.id.uasfmStatus)
        val downloadBtn = findViewById<Button>(R.id.uasfmDownloadButton)
        val checkBtn = findViewById<Button>(R.id.uasfmCheckButton)

        radiusField.setText("50")
        renderUasfmStatus()

        /** Reads the three fields, or null (with a toast) if they don't make sense. */
        fun readBbox(): UasfmStore.Bbox? {
            val lat = latField.text.toString().trim().toDoubleOrNull()
            val lon = lonField.text.toString().trim().toDoubleOrNull()
            val radius = radiusField.text.toString().trim().toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "Enter a valid centre latitude and longitude", Toast.LENGTH_SHORT).show()
                return null
            }
            if (radius == null || radius <= 0 || radius > 500) {
                Toast.makeText(this, "Enter a radius between 1 and 500 miles", Toast.LENGTH_SHORT).show()
                return null
            }
            return UasfmStore.bboxAround(lat, lon, radius)
        }

        findViewById<Button>(R.id.uasfmUseLocationButton).setOnClickListener {
            AppLog.v(TAG, "tap: UASFM Use My Location")
            val loc = lastKnownPhoneLocation()
            if (loc == null) {
                Toast.makeText(this, "No phone GPS fix available", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            latField.setText("%.4f".format(loc.first))
            lonField.setText("%.4f".format(loc.second))
        }

        checkBtn.setOnClickListener {
            val bbox = readBbox() ?: return@setOnClickListener
            AppLog.v(TAG, "tap: UASFM Check Size")
            checkBtn.isEnabled = false
            status.text = "Checking…"
            UasfmStore.countAsync(bbox) { result ->
                checkBtn.isEnabled = true
                status.text = when {
                    result.error != null -> "Couldn't reach the FAA service: ${result.error}"
                    result.count == 0 ->
                        "No facility-map cells in that area — it's likely all uncontrolled " +
                            "airspace, where the Part 107 400 ft limit applies."
                    else -> "${result.count} cell(s) in that area. Tap Download to store them."
                }
            }
        }

        downloadBtn.setOnClickListener {
            val bbox = readBbox() ?: return@setOnClickListener
            val label = "%.3f, %.3f  ·  %s mi".format(
                latField.text.toString().trim().toDoubleOrNull() ?: 0.0,
                lonField.text.toString().trim().toDoubleOrNull() ?: 0.0,
                radiusField.text.toString().trim(),
            )
            AppLog.i(TAG, "UASFM download starting for $label")
            downloadBtn.isEnabled = false
            status.text = "Downloading…"
            UasfmStore.downloadAsync(
                context = this,
                bbox = bbox,
                areaLabel = label,
                onProgress = { count -> status.text = "Downloading… $count cell(s)" },
                onDone = { result ->
                    downloadBtn.isEnabled = true
                    if (result.error != null) {
                        AppLog.w(TAG, "UASFM download failed: ${result.error}")
                        status.text = result.error
                    } else {
                        // Surface off-grid skips rather than burying them: a non-zero count
                        // means the FAA moved off the 1/120 degree grid this design assumes,
                        // and the pilot would otherwise have coverage holes with no hint why.
                        val warn = if (result.offGridSkipped > 0)
                            "\n⚠ ${result.offGridSkipped} cell(s) skipped — unexpected grid, report this."
                        else ""
                        renderUasfmStatus(extra = warn)
                        Toast.makeText(this, "FAA ceilings downloaded", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        findViewById<Button>(R.id.uasfmClearButton).setOnClickListener {
            AppLog.v(TAG, "tap: UASFM Clear Data")
            UasfmStore.clear(this)
            renderUasfmStatus()
            Toast.makeText(this, "FAA ceiling data cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderUasfmStatus(extra: String = "") {
        val status = findViewById<TextView>(R.id.uasfmStatus)
        val meta = UasfmStore.meta(this)
        status.text = if (meta == null) {
            "No FAA ceiling data downloaded — the flight HUD will show the Part 107 400 ft default."
        } else {
            "${meta.cellCount} cell(s) for ${meta.areaLabel}\n" +
                "Downloaded ${dtedDateFormat.format(java.util.Date(meta.downloadedAtMs))}  ·  " +
                "FAA effective ${meta.effectiveLabel}$extra"
        }
    }

    /**
     * Most recent GPS/network fix from the phone, or null. The RC-N1 has no GPS of its own, so
     * the phone's position IS the controller's position.
     *
     * Goes through [OperatorLocation] rather than calling `getLastKnownLocation` here, because
     * that method reads a CACHE that nothing fills unless some app has asked for position
     * updates. On a phone dedicated to flying, nothing has, so it returned null for ever — which
     * reads as a permission fault when it is not one. [OperatorLocation.start] issues the real
     * `requestLocationUpdates` that fills it, and applies an age gate so a fix from days ago at
     * some other location is not offered up as "here".
     */
    private fun lastKnownPhoneLocation(): Pair<Double, Double>? {
        OperatorLocation.start(this)
        val loc = OperatorLocation.latest ?: return null
        return loc.latitude to loc.longitude
    }

    private val dtedDateFormat = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)

    /** One compact row per imported region — a name/date/file-count/size summary plus a
     *  delete button, never a per-tile listing (that's what used to run the screen on for a
     *  long, mostly-empty scroll with a full multi-hundred-tile region). */
    private fun renderDtedRegions() {
        val container = findViewById<LinearLayout>(R.id.dtedFileList)
        val status = findViewById<TextView>(R.id.dtedStatus)
        container.orientation = LinearLayout.VERTICAL
        container.removeAllViews()
        val regions = DtedStore.listRegions(this)
        status.text = if (regions.isEmpty()) "No terrain regions imported."
            else "${regions.size} region(s) imported."
        for (region in regions) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(ContextCompat.getColor(applicationContext, R.color.tp_surface_dialog))
                setPadding(12, 10, 12, 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6 }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = region.name
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                val mb = region.totalBytes / 1024.0 / 1024.0
                val sizeStr = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
                text = "Imported ${dtedDateFormat.format(java.util.Date(region.importedAtMs))} · " +
                    "${region.fileCount} file(s) · $sizeStr"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_tertiary))
                textSize = 12f
            })
            row.addView(info)
            row.addView(TextView(this).apply {
                text = "Delete"
                setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_btn_danger_dialog))
                textSize = 13f
                setPadding(20, 8, 4, 8)
                setOnClickListener {
                    AppLog.v(TAG, "tap: delete DTED region ${region.name} (#${region.id})")
                    DtedStore.deleteRegion(this@TakConnectActivity, region)
                    renderDtedRegions()
                }
            })
            container.addView(row)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_DTED_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = queryDisplayName(uri) ?: "Region-${System.currentTimeMillis()}"
        val status = findViewById<TextView>(R.id.dtedStatus)
        val result = DtedStore.import(this, uri, name)
        status.text = when {
            result.error != null && result.importedCount == 0 -> "Failed to import $name: ${result.error}"
            result.error != null -> "Imported ${result.importedCount} tile(s) from $name (${result.error})"
            else -> "Imported ${result.importedCount} tile(s) from $name."
        }
        if (result.importedCount == 0) Toast.makeText(this, status.text, Toast.LENGTH_SHORT).show()
        renderDtedRegions()
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
        val vProfileGroup = findViewById<RadioGroup>(R.id.videoProfileGroup)
        val vFullUrl = findViewById<TextView>(R.id.videoFullUrl)

        vHost.setText(prefs.getString(KEY_V_HOST, ""))
        vPort.setText(prefs.getInt(KEY_V_PORT, 8554).toString())
        vUser.setText(prefs.getString(KEY_V_USER, ""))
        // ⚠ THIS LINE WAS MISSING, AND ITS ABSENCE ERASED THE SAVED PASSWORD.
        //
        // Every other field was restored; this one was not, so the box came up blank. The
        // TextWatcher below then calls refreshAndSave() — and refreshAndSave() also runs
        // unconditionally at the end of this method — which writes vPass.text back to the store.
        // Blank. So merely OPENING Pre-Flight Setup wiped the RTSP password, and the next stream
        // failed to authenticate with nothing on screen to explain why: the field looked the same
        // as it always did, because it had always come up empty.
        vPass.setText(prefs.getString(KEY_V_PASS, ""))
        vStreamId.setText(prefs.getString(KEY_V_STREAMID, ""))
        vTcp.isChecked = prefs.getBoolean(KEY_V_TCP, true)
        when (prefs.getString(KEY_V_PROFILE, "standard")) {
            "low" -> vProfileGroup.check(R.id.videoProfileLow)
            "high" -> vProfileGroup.check(R.id.videoProfileHigh)
            else -> vProfileGroup.check(R.id.videoProfileStandard)
        }

        fun selectedProfile(): String = when (vProfileGroup.checkedRadioButtonId) {
            R.id.videoProfileLow -> "low"
            R.id.videoProfileHigh -> "high"
            else -> "standard"
        }

        fun buildConfig(): DroneVideoStreamer.VideoConfig = DroneVideoStreamer.VideoConfig(
            host = vHost.text.toString().trim(),
            port = vPort.text.toString().trim().toIntOrNull() ?: 8554,
            username = vUser.text.toString().trim(),
            password = vPass.text.toString(),
            streamId = vStreamId.text.toString().trim(),
            tcp = vTcp.isChecked,
            profile = selectedProfile(),
        )

        // Live-updates the full-URL preview and persists as fields change (no Start/Stop here
        // — the flight-screen LIVE pill owns starting/stopping the stream; this screen only
        // edits/saves the server config).
        val refreshAndSave = {
            val cfg = buildConfig()
            vFullUrl.text = if (cfg.host.isEmpty() || cfg.streamId.isEmpty())
                "rtsp://…  (enter host + identifier)" else cfg.urlSafe()
            prefs.edit()
                .putString(KEY_V_HOST, cfg.host)
                .putInt(KEY_V_PORT, cfg.port)
                .putString(KEY_V_USER, cfg.username)
                .putString(KEY_V_PASS, cfg.password)
                .putString(KEY_V_STREAMID, cfg.streamId)
                .putBoolean(KEY_V_TCP, cfg.tcp)
                .putString(KEY_V_PROFILE, cfg.profile)
                .apply()
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshAndSave()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(vHost, vPort, vUser, vPass, vStreamId).forEach { it.addTextChangedListener(watcher) }
        vTcp.setOnCheckedChangeListener { _, _ -> refreshAndSave() }
        // Persist the profile the moment it changes, so the flight-screen LIVE button (which
        // reads prefs, not this screen's live state) always uses the pilot's current choice.
        vProfileGroup.setOnCheckedChangeListener { _, _ ->
            prefs.edit().putString(KEY_V_PROFILE, selectedProfile()).apply()
        }
        refreshAndSave()
    }

    private fun enrollAndConnect(
        host: String, enrollPort: Int, cotPort: Int,
        username: String, password: String, droneCallsign: String,
    ) {
        // Check the obvious thing first. Without this the enrollment goes ahead, the socket
        // fails somewhere inside TLS, and the pilot gets a stack-shaped message about a handshake
        // — which reads as "the TAK server is broken" when the truth is there is no network at
        // all. One plain sentence, before anything else happens.
        if (!NetworkStatus.hasInternet(this)) {
            AppLog.w(TAG, "enroll aborted — no validated network")
            setStatus("No network connection. Connect to Wi-Fi or mobile data, then try again.",
                ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
            return
        }
        setStatus("Enrolling with $host:$enrollPort …", ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))

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
                        runOnUiThread { setStatus("Enrolled. Connecting …", ContextCompat.getColor(applicationContext, R.color.tp_text_secondary)) }
                        connectWithCerts(uid, username, droneUid, droneCallsign,
                            host, cotPort, trustStorePath, clientCertPath)
                    }

                    override fun onError(error: String) {
                        runOnUiThread { setStatus("Error: $error", ContextCompat.getColor(applicationContext, R.color.tp_state_danger)) }
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
            uid, droneCallsign, "Cyan", "Team Member",
            host, cotPort, trustStorePath, certPw, clientCertPath, certPw,
        )
        runOnUiThread {
            setStatus("Connected. Streaming drone PLI as \"$droneCallsign\".",
                ContextCompat.getColor(applicationContext, R.color.tp_state_go))
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
            setStatus("Saved enrollment incomplete — enroll again.", ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
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

    // ---- My Channels ----
    private var selectedChannels: MutableSet<String> = mutableSetOf()

    /** Pull the channels the logged-in user belongs to from the TAK server (needs a connection). */
    private fun pullChannels() {
        val chanStatus = findViewById<TextView>(R.id.takChannelsStatus)
        if (!TakManager.getInstance().isConnected) {
            chanStatus.text = "Connect to TAK first, then pull channels."
            chanStatus.setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_state_danger))
            return
        }
        chanStatus.text = "Pulling channels…"
        chanStatus.setTextColor(ContextCompat.getColor(applicationContext, R.color.tp_text_secondary))
        TakChannelsStore.pull(this) { all ->
            chanStatus.text = if (all.isEmpty()) "No channels found for this login."
                else "${all.size} channel(s). Check the ones to publish to."
            renderChannels(all)
        }
    }

    /** Render a 3-column grid of checkboxes, left-to-right then down; toggling a box saves the
     *  selection + applies it to routing. Evenly spaced via equal-weight cells; short rows are
     *  padded with invisible spacers so column alignment stays consistent. */
    private fun renderChannels(channels: List<String>) {
        val container = findViewById<android.widget.LinearLayout>(R.id.takChannelsList)
        container.orientation = LinearLayout.VERTICAL
        container.removeAllViews()
        val cols = 3
        for (rowChannels in channels.chunked(cols)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6 }
            }
            for (name in rowChannels) {
                row.addView(android.widget.CheckBox(this).apply {
                    text = name
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    isChecked = selectedChannels.contains(name)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedChannels.add(name) else selectedChannels.remove(name)
                        TakChannelsStore.saveSelected(this@TakConnectActivity, selectedChannels)
                    }
                })
            }
            repeat(cols - rowChannels.size) {
                row.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            container.addView(row)
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
        /**
         * ⚠ **A speed bump, not security, and it must not be mistaken for one.** The string
         * ships in the APK in plain text — anyone with the file and `strings`, or with adb,
         * reads it in seconds. That is accepted: the threat model is a user tapping into
         * settings they should not adjust, not an adversary. Do not "harden" this with hashing
         * or a per-device secret — that is a stronger lock on the front door of an unlocked
         * house, and it would cost the field recoverability a shared fixed password exists to
         * provide.
         *
         * The entered attempt is never logged, right or wrong.
         */
        private const val UNLOCK_PASSWORD = "takpilot"

        private const val KEY_AIRCRAFT_LOCKED = "aircraft_config_locked"
        private const val KEY_TAK_LOCKED = "tak_config_locked"
        private const val KEY_VIDEO_LOCKED = "video_config_locked"

        private const val KEY_V_USER = "video_user"
        /** Named constant, not a literal. The save site used a bare "video_pass" while the
         *  restore site did not exist at all — a constant makes the pair impossible to miss. */
        private const val KEY_V_PASS = "video_pass"
        private const val KEY_V_STREAMID = "video_streamid"
        private const val KEY_V_TCP = "video_tcp"
        private const val KEY_V_PROFILE = "video_profile"
    }

    /** Action-bar menu button behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

}


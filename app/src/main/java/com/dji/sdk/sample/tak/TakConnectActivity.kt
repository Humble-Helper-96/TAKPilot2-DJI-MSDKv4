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
import com.taklite.client.tak.TakMissionClient
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

    /** True only while code writes the battery fields for display — see the watcher in
     *  [setupBattery], which must not mistake that for the pilot typing. */
    private var suppressBatterySave = false

    override fun onDestroy() {
        // The listeners hold this Activity. TakManager outlives the screen, thus leaving them
        // attached leaks the whole Activity and repaints views that are gone.
        runCatching { TakManager.getInstance().removeGroupChangeListener(groupChangeListener) }
        runCatching { TakManager.getInstance().removeListener(connectionListener) }
        super.onDestroy()
    }

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

        // My Channels. The channels come from the server and go back to the server, and no
        // <dest group> goes on any message — that attribute is what made the server drop every
        // marker on the Autel sibling's v1.6.0. The evidence is in the Autel tree's
        // CHANNELS-FINDINGS.md.
        refreshChannels()
        // The server pushes t-x-g-c when the channels change, from this controller or from an
        // administrator in TAK Portal. Listening beats a timer: the screen follows in about a
        // second, and it asks the server nothing while nothing changes.
        TakManager.getInstance().addGroupChangeListener(groupChangeListener)
        // AND read them again when TAK connects. The refresh above needs a connection, so a
        // screen opened before TAK is up would otherwise show an empty list for ever — the
        // "Pull Channels" button used to be the only way out of that, and it is gone
        // (operator, 2026-08-16, on the sibling).
        TakManager.getInstance().addListener(connectionListener)

        // Reflect live state on open. Auto-connect already happened at app launch
        // (TakAutoConnect.attemptOnAppLaunch, from the home screen) — if we're still not
        // connected but have saved certs, try again here too (covers the case where this
        // screen is opened before that first attempt lands, or after a manual disconnect).
        when {
            TakManager.getInstance().isConnected ->
                setStatus("Connected. Sending the aircraft position to TAK.", ContextCompat.getColor(applicationContext, R.color.tp_state_go))
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
            runCatching { TakForegroundService.stop(applicationContext) }
            runCatching { clearEnrollment(prefs) }
            // Reset the UI fields so it's clearly a fresh login.
            username.setText("")
            password.setText("")
            // Nothing local to clear: the channels live on the server now. Logging out does
            // not change them, which is correct — they belong to the certificate.
            latestChannels = emptyList()
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
        setupControlResponse()
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
                // Suppressed while the refusal path writes the AIRCRAFT's values into these
                // fields for display — those setText calls land here too, and without the guard
                // they saved the aircraft's fixed 20/10 over the pilot's own preference. The
                // preference must survive: it is what gets pushed to the next airframe, and the
                // next airframe may accept it.
                if (suppressBatterySave) return
                FlightLimitsController.saveBattery(
                    this@TakConnectActivity, low.text.toString(), crit.text.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(low, crit).forEach { it.addTextChangedListener(watcher) }
        renderLimitsReadBack()
    }

    /** Shows what the AIRCRAFT reports, not what was typed. Blank until a read-back has landed —
     *  "unknown" and "what you asked for" must not look the same. */
    private fun renderLimitsReadBack() {
        val f = FlightLimitsController
        // Metres in, feet out: the fields above take feet, so a read-back in metres would make
        // the pilot convert to check their own entry.
        fun ft(m: Int?) = m?.let { "${Math.round(it * 3.28084)} ft" } ?: "—"
        val parts = listOfNotNull(
            f.aircraftMaxAltM?.let { "max alt ${ft(it)}" },
            f.aircraftMaxRadiusM?.let { "max dist ${ft(it)}" },
            f.aircraftRthAltM?.let { "RTH ${ft(it)}" },
            f.aircraftWarningPct?.let { "warning $it%" },
            f.aircraftCriticalPct?.let { "critical $it%" },
            f.aircraftFailsafe?.let { "signal loss ${it.name.replace('_', ' ').lowercase()}" },
        )
        findViewById<TextView>(R.id.limitReadBackStatus).text =
            if (parts.isEmpty()) "" else "Aircraft reports: " + parts.joinToString(" · ")

        // Once the aircraft has refused them, the two battery fields stop pretending. They are
        // left in place rather than removed: the getters still work, so the levels the aircraft
        // holds are worth showing, and an airframe that DOES accept them should get working
        // fields. A field that takes a number and silently discards it is the worst of the three.
        if (f.batteryThresholdsRefused) {
            val low = findViewById<EditText>(R.id.limitLowBattery)
            val crit = findViewById<EditText>(R.id.limitCriticalBattery)
            suppressBatterySave = true
            try {
                f.aircraftWarningPct?.let { low.setText(it.toString()) }
                f.aircraftCriticalPct?.let { crit.setText(it.toString()) }
            } finally {
                suppressBatterySave = false
            }
            for (field in listOf(low, crit)) {
                field.isEnabled = false
                field.isFocusable = false
                field.isFocusableInTouchMode = false
            }
            findViewById<TextView>(R.id.limitBatteryNotice).apply {
                visibility = View.VISIBLE
                text = "This aircraft sets its own battery levels. The app cannot change them. " +
                    "It warns at ${f.aircraftWarningPct ?: "—"}% and lands at " +
                    "${f.aircraftCriticalPct ?: "—"}%."
            }
        }
    }

    /**
     * Control response. Applies on selection rather than on the Apply button: it is a camera-feel
     * setting the pilot wants to try, not a flight limit, and waiting for Apply to feel it would
     * make it hard to compare the two.
     */
    private fun setupControlResponse() {
        val group = findViewById<RadioGroup>(R.id.controlResponseGroup)
        group.check(
            if (ControlResponse.saved(this) == ControlResponse.Mode.PRECISION)
                R.id.controlResponsePrecision else R.id.controlResponseNormal
        )
        renderControlResponse()
        group.setOnCheckedChangeListener { _, id ->
            val mode = if (id == R.id.controlResponsePrecision) ControlResponse.Mode.PRECISION
                       else ControlResponse.Mode.NORMAL
            ControlResponse.save(this, mode)
            ControlResponse.apply(this) { runOnUiThread { renderControlResponse() } }
        }
    }

    /** What the GIMBAL reports holding. Blank until a read-back lands. */
    private fun renderControlResponse() {
        val v = ControlResponse.aircraftPitchSpeed
        findViewById<TextView>(R.id.controlResponseStatus).text =
            if (v == null) "" else "Aircraft reports: gimbal speed $v"
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
                        // The apply finished and something did not take. That is an answer,
                        // so it is caution — unknown is for "the aircraft never replied". §6.1.
                        if (ok) R.color.tp_state_go else R.color.tp_state_caution))
                    renderLimitsReadBack()
                },
            )
        }
    }

    private val takLockedFields = listOf(
        R.id.takHost, R.id.takEnrollPort, R.id.takCotPort,
        R.id.takUsername, R.id.takPassword, R.id.takCallsign,
        R.id.takDisconnectButton,
    )

    /** Codec and TCP transport are part of WHAT the stream is — the wrong codec breaks playback
     *  outright for some viewers, so they lock with the server fields (operator, 2026-08-06, on
     *  the sibling). The quality profile stays live; it is an in-flight choice about bandwidth,
     *  not part of what the stream IS. The two codec RadioButtons are listed individually
     *  because disabling a RadioGroup does not disable its children. */
    private val videoLockedFields = listOf(
        R.id.videoName,
        R.id.videoHost, R.id.videoPort, R.id.videoStreamId,
        R.id.videoUser, R.id.videoPassword,
        R.id.videoCodecH264, R.id.videoCodecH265, R.id.videoTcp,
    )
    // ⚠ videoServer1/videoServer2 are NOT in that list, and this is deliberate. applyLock dims
    // to 45%, which on a radio button greys the DOT as well as the label — and the dot is the
    // one thing a pilot must be able to read while locked: which server the video is going to.
    // The channel rows hit exactly this and it made the ticks unreadable (operator,
    // 2026-08-16). The toggle is locked by lockVideoServerToggle instead: full contrast, no
    // touch response.

    /**
     * Per-section locks over settings that are painful to get wrong and rarely need changing.
     *
     * Unlocking asks for a password; locking does not. The asymmetry is deliberate — locking is
     * the safe direction, and gating it would only train people to dismiss dialogs.
     */
    /** The battery levels, the stick mode and the signal-loss behaviour — what a stray tap must
     *  not change. The numeric limit fields stay editable, matching the sibling: editing one
     *  only saves it locally, and nothing reaches the aircraft without Apply or a connect.
     *
     *  ⚠ NOT the Apply button — same philosophy as the sibling, in its words: the lock guards
     *  what the configuration IS, not what you do with it. A locked, known-good configuration
     *  must still be pushable to a freshly connected aircraft; needing to re-send it is exactly
     *  when a pilot must not be fighting a lock. (Apply was in this list until 2026-08-12, which
     *  made the two apps behave differently for the same pilot.) */
    private val aircraftLockedFields = listOf(
        R.id.limitLowBattery, R.id.limitCriticalBattery,
        R.id.stickMode1, R.id.stickMode2, R.id.stickMode3,
        R.id.failsafeGoHome, R.id.failsafeHover, R.id.failsafeLand,
    )

    private fun setupConfigLocks() {
        setupOneLock(
            R.id.limitBatteryLock, KEY_AIRCRAFT_LOCKED, aircraftLockedFields,
            "Unlock battery levels?",
            "These decide when the aircraft returns and lands on its own, and what the control " +
                "sticks do. A wrong value can force a landing away from the pilot.",
        )
        setupOneLock(
            R.id.takLockConfig, KEY_TAK_LOCKED, takLockedFields,
            "Unlock TAK server settings?",
            "The lock prevents an accidental change to a server that works. " +
                "A wrong value stops the aircraft sending data to your team.",
            // The channel rows are built in code, thus applyLock cannot reach them by id. They
            // are painted again instead, and each row reads the lock as it is built.
            afterChange = { renderChannels(latestChannels) },
        )
        setupOneLock(
            R.id.videoLockConfig, KEY_VIDEO_LOCKED, videoLockedFields,
            "Unlock video server settings?",
            "These fields are locked so a working stream configuration is not changed by " +
                "accident. Editing them can stop your team seeing the video.",
            // The toggle is a radio button pair built in the layout, but it must not be dimmed
            // — see the note on videoLockedFields.
            afterChange = { lockVideoServerToggle(it) },
        )
    }

    private fun setupOneLock(
        checkBoxId: Int,
        prefKey: String,
        fieldIds: List<Int>,
        confirmTitle: String,
        confirmBody: String,
        /** Run after the lock state settles, for controls that applyLock cannot reach by id. */
        afterChange: (Boolean) -> Unit = {},
    ) {
        val box = findViewById<android.widget.CheckBox>(checkBoxId)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Default UNLOCKED on a fresh install — a first-run pilot must not have to discover a
        // lock before they can type anything.
        val locked = prefs.getBoolean(prefKey, false)
        box.isChecked = locked
        applyLock(fieldIds, locked)
        afterChange(locked)

        box.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                prefs.edit().putBoolean(prefKey, true).apply()
                applyLock(fieldIds, true)
                afterChange(true)
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
                        afterChange(false)
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
            // A field the AIRCRAFT has refused stays read-only through lock and unlock alike.
            // The lock guards against an accidental edit; the refusal means an edit does
            // nothing at all, and unlocking must not turn a dead field editable again.
            if (!locked && FlightLimitsController.batteryThresholdsRefused &&
                (id == R.id.limitLowBattery || id == R.id.limitCriticalBattery)) continue
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

    /**
     * Video server config. NO Start/Stop here — the flight screen's LIVE pill owns starting and
     * stopping the stream; this screen only edits and SAVES the config.
     *
     * Persisting on every change matters more than it looks: the LIVE pill reads prefs, not this
     * screen's live state.
     */
    private fun setupVideoControls(prefs: android.content.SharedPreferences) {
        migrateVideoSlots(prefs)
        val vName = findViewById<EditText>(R.id.videoName)
        val vServerGroup = findViewById<RadioGroup>(R.id.videoServerGroup)
        val vServer1 = findViewById<android.widget.RadioButton>(R.id.videoServer1)
        val vServer2 = findViewById<android.widget.RadioButton>(R.id.videoServer2)
        val vHost = findViewById<EditText>(R.id.videoHost)
        val vPort = findViewById<EditText>(R.id.videoPort)
        val vUser = findViewById<EditText>(R.id.videoUser)
        val vPass = findViewById<EditText>(R.id.videoPassword)
        val vStreamId = findViewById<EditText>(R.id.videoStreamId)
        val vTcp = findViewById<android.widget.CheckBox>(R.id.videoTcp)
        val vProfileGroup = findViewById<RadioGroup>(R.id.videoProfileGroup)
        val vCodecGroup = findViewById<RadioGroup>(R.id.videoCodecGroup)
        val vCodecHint = findViewById<TextView>(R.id.videoCodecHint)
        val vFullUrl = findViewById<TextView>(R.id.videoFullUrl)

        /** True while the fields are being filled from a slot, so the watchers below do not
         *  treat the repopulation as a pilot's edit and write it straight back. */
        var loadingSlot = false

        /** Fills every field from the given server slot. */
        fun loadSlot(slot: Int) {
            loadingSlot = true
            vName.setText(prefs.getString(vKey(slot, "name"), "") ?: "")
            vHost.setText(prefs.getString(vKey(slot, "host"), "") ?: "")
            vPort.setText(prefs.getInt(vKey(slot, "port"), 8554).toString())
            vUser.setText(prefs.getString(vKey(slot, "user"), "") ?: "")
            // ⚠ THIS LINE WAS MISSING ONCE, AND ITS ABSENCE ERASED THE SAVED PASSWORD.
            //
            // Every other field was restored; this one was not, so the box came up blank. The
            // TextWatcher below then saved the WHOLE config on any edit, writing that blank over
            // the stored value. So the password survived until the pilot next opened this screen
            // and touched anything, and then it was gone — which is why it looked like it never
            // saved.
            //
            // ⚠ THE SAME TRAP IS NOW PER SLOT. Every field this function fills must also be
            // written by the save below. A field read here and not written there loses the
            // OTHER server's value the moment the pilot switches.
            vPass.setText(prefs.getString(vKey(slot, "pass"), "") ?: "")
            vStreamId.setText(prefs.getString(vKey(slot, "streamid"), "") ?: "")
            vTcp.isChecked = prefs.getBoolean(vKey(slot, "tcp"), true)
            when (prefs.getString(vKey(slot, "profile"), "standard")) {
                "low" -> vProfileGroup.check(R.id.videoProfileLow)
                "high" -> vProfileGroup.check(R.id.videoProfileHigh)
                else -> vProfileGroup.check(R.id.videoProfileStandard)
            }
            when (VideoCodec.fromPref(prefs.getString(vKey(slot, "codec"), null))) {
                VideoCodec.H265 -> vCodecGroup.check(R.id.videoCodecH265)
                VideoCodec.H264 -> vCodecGroup.check(R.id.videoCodecH264)
            }
            loadingSlot = false
        }

        fun selectedProfile(): String = when (vProfileGroup.checkedRadioButtonId) {
            R.id.videoProfileLow -> "low"
            R.id.videoProfileHigh -> "high"
            else -> "standard"
        }

        fun selectedCodec(): VideoCodec = when (vCodecGroup.checkedRadioButtonId) {
            R.id.videoCodecH265 -> VideoCodec.H265
            else -> VideoCodec.H264
        }

        // The trade is not obvious and its cost lands on someone the pilot cannot see, so the
        // screen states it. Deliberately NO named clients: which player supports which codec
        // changes with every release, and a hint that names one is wrong the day that changes.
        fun refreshCodecHint() {
            vCodecHint.text = if (selectedCodec() == VideoCodec.H265)
                "More efficient. Better picture for the bandwidth, but fewer clients play it."
            else
                "Most compatible. Plays on the widest range of clients."
        }

        fun buildConfig(): DroneVideoStreamer.VideoConfig = DroneVideoStreamer.VideoConfig(
            host = vHost.text.toString().trim(),
            port = vPort.text.toString().trim().toIntOrNull() ?: 8554,
            username = vUser.text.toString().trim(),
            password = vPass.text.toString(),
            streamId = vStreamId.text.toString().trim(),
            tcp = vTcp.isChecked,
            profile = selectedProfile(),
            codec = selectedCodec().prefValue,
        )

        /** Puts the button labels back to the pilot's names, so the choice reads as the servers
         *  they know. An unnamed slot keeps its position as its label — never a blank button. */
        fun refreshServerLabels() {
            vServer1.text = prefs.getString(vKey(1, "name"), "")?.takeIf { it.isNotBlank() }
                ?: "Server 1"
            vServer2.text = prefs.getString(vKey(2, "name"), "")?.takeIf { it.isNotBlank() }
                ?: "Server 2"
        }

        val refreshAndSave = {
            val cfg = buildConfig()
            vFullUrl.text = if (cfg.host.isEmpty() || cfg.streamId.isEmpty())
                "rtsp://…  (enter host + identifier)" else cfg.urlSafe()
            val slot = activeVideoSlot(prefs)
            prefs.edit()
                // The slot is where the value LIVES. Both servers keep a complete set,
                // including the encoding, so swapping networks can also swap the profile.
                .putString(vKey(slot, "name"), vName.text.toString().trim())
                .putString(vKey(slot, "host"), cfg.host)
                .putInt(vKey(slot, "port"), cfg.port)
                .putString(vKey(slot, "user"), cfg.username)
                .putString(vKey(slot, "pass"), cfg.password)
                .putString(vKey(slot, "streamid"), cfg.streamId)
                .putBoolean(vKey(slot, "tcp"), cfg.tcp)
                .putString(vKey(slot, "profile"), cfg.profile)
                .putString(vKey(slot, "codec"), cfg.codec)
                // ⚠ AND MIRROR THE ACTIVE SLOT ONTO THE PLAIN KEYS. These are what
                // VideoStreamerHolder.buildConfig and the flight screen's LIVE pill read, and
                // those sites read them as STRING LITERALS. Mirroring keeps the whole idea of
                // "two servers" inside this screen: no consumer has to know a slot exists, and
                // the stream still starts if this mirror is ever the only thing left.
                .putString(KEY_V_HOST, cfg.host)
                .putInt(KEY_V_PORT, cfg.port)
                .putString(KEY_V_USER, cfg.username)
                .putString(KEY_V_PASS, cfg.password)
                .putString(KEY_V_STREAMID, cfg.streamId)
                .putBoolean(KEY_V_TCP, cfg.tcp)
                .putString(KEY_V_PROFILE, cfg.profile)
                .putString(KEY_V_CODEC, cfg.codec)
                .apply()
            refreshServerLabels()
        }
        val watcher = object : android.text.TextWatcher {
            // ⚠ The guard is not optional. loadSlot fills the fields one at a time, and without
            // it each setText saves a HALF-SWAPPED config: after the name is the new server's
            // and the host is still the old one, that mixture goes to the slot. The final save
            // corrects it, but the intermediate writes are real and one crash inside the
            // sequence would leave them.
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!loadingSlot) refreshAndSave()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        listOf(vName, vHost, vPort, vUser, vPass, vStreamId)
            .forEach { it.addTextChangedListener(watcher) }
        vTcp.setOnCheckedChangeListener { _, _ -> if (!loadingSlot) refreshAndSave() }
        // Persist the profile the moment it changes, so the flight-screen LIVE button (which
        // reads prefs, not this screen's live state) always uses the pilot's current choice.
        // It goes through refreshAndSave because the profile belongs to the SLOT now, and only
        // that function knows which slot is active and how to mirror it.
        vProfileGroup.setOnCheckedChangeListener { _, _ ->
            if (loadingSlot) return@setOnCheckedChangeListener
            AppLog.v(TAG, "video profile -> ${selectedProfile()}")
            refreshAndSave()
        }
        // Same reasoning as the profile group: the LIVE pill reads prefs, so persist immediately.
        vCodecGroup.setOnCheckedChangeListener { _, _ ->
            refreshCodecHint()
            if (loadingSlot) return@setOnCheckedChangeListener
            AppLog.v(TAG, "video codec -> ${selectedCodec().prefValue}")
            refreshAndSave()
        }

        /**
         * The active-server choice.
         *
         * The fields below show the SELECTED server, thus selecting one also makes it live. That
         * is acceptable here and nowhere else: the flight screen stops the stream in onStop, so
         * nothing can be streaming while this screen is showing. The swap therefore cannot cut a
         * feed the team is watching — it decides where the NEXT start goes.
         *
         * The order matters. The active slot is written FIRST, so the fields that follow load
         * from the new slot and every later save lands on it.
         */
        vServerGroup.setOnCheckedChangeListener { _, checkedId ->
            if (loadingSlot) return@setOnCheckedChangeListener
            val slot = if (checkedId == R.id.videoServer2) 2 else 1
            if (slot == activeVideoSlot(prefs)) return@setOnCheckedChangeListener
            prefs.edit().putInt(KEY_V_ACTIVE_SLOT, slot).apply()
            loadSlot(slot)
            // Mirror the newly selected server onto the plain keys the streamer reads, and
            // repaint the URL line. Without this the toggle would move and the stream would
            // still go to the old server.
            refreshAndSave()
            refreshCodecHint()
            AppLog.i(TAG, "active video server -> slot $slot (${vName.text.toString().trim()})")
        }

        // Fill the screen from whichever server is active, then paint the derived text.
        loadingSlot = true
        vServerGroup.check(if (activeVideoSlot(prefs) == 2) R.id.videoServer2 else R.id.videoServer1)
        loadingSlot = false
        loadSlot(activeVideoSlot(prefs))
        refreshServerLabels()
        refreshCodecHint()
        refreshAndSave()
    }

    /** Preference key for one field of one video server slot. */
    private fun vKey(slot: Int, base: String) = "video_s${slot}_$base"

    /** The server the video goes to now: 1 or 2. */
    private fun activeVideoSlot(prefs: android.content.SharedPreferences): Int =
        if (prefs.getInt(KEY_V_ACTIVE_SLOT, 1) == 2) 2 else 1

    /**
     * Moves a single-server configuration into slot 1, once.
     *
     * An install upgrading from a build that had ONE video server keeps its settings on the
     * plain `video_*` keys. Copying them into slot 1 is what stops the upgrade looking like the
     * video configuration was wiped.
     *
     * It runs one time and marks itself done. It must not run again: after the first edit the
     * slot is the truth and the plain keys are only a mirror of it, so copying back would undo
     * whatever the pilot last did on the other server.
     */
    private fun migrateVideoSlots(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_V_SLOTS_MIGRATED, false)) return
        prefs.edit()
            .putString(vKey(1, "name"), "Server 1")
            .putString(vKey(1, "host"), prefs.getString(KEY_V_HOST, "") ?: "")
            .putInt(vKey(1, "port"), prefs.getInt(KEY_V_PORT, 8554))
            .putString(vKey(1, "user"), prefs.getString(KEY_V_USER, "") ?: "")
            .putString(vKey(1, "pass"), prefs.getString(KEY_V_PASS, "") ?: "")
            .putString(vKey(1, "streamid"), prefs.getString(KEY_V_STREAMID, "") ?: "")
            .putBoolean(vKey(1, "tcp"), prefs.getBoolean(KEY_V_TCP, true))
            .putString(vKey(1, "profile"), prefs.getString(KEY_V_PROFILE, "standard") ?: "standard")
            .putString(vKey(1, "codec"), prefs.getString(KEY_V_CODEC, null) ?: VideoCodec.H264.prefValue)
            // Slot 2 starts empty and inherits only the defaults. A half-filled second server
            // would be worse than an obviously blank one.
            .putString(vKey(2, "name"), "Server 2")
            .putInt(vKey(2, "port"), 8554)
            .putBoolean(vKey(2, "tcp"), true)
            .putString(vKey(2, "profile"), "standard")
            .putString(vKey(2, "codec"), VideoCodec.H264.prefValue)
            .putInt(KEY_V_ACTIVE_SLOT, 1)
            .putBoolean(KEY_V_SLOTS_MIGRATED, true)
            .apply()
        AppLog.i(TAG, "video config migrated to slot 1")
    }

    /**
     * Locks the active-server toggle without hiding which server is active.
     *
     * LOCKED IS NOT DISABLED — the same rule the channel rows follow. The buttons keep full
     * contrast and their tint, and stop taking touches. A pilot must always be able to SEE
     * where the video is going; the lock exists to stop an accidental swap, not to hide the
     * destination.
     */
    private fun lockVideoServerToggle(locked: Boolean) {
        for (id in listOf(R.id.videoServer1, R.id.videoServer2)) {
            findViewById<android.widget.RadioButton>(id)?.apply {
                isClickable = !locked
                isFocusable = !locked
            }
        }
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
            setStatus("Connected. Sending the aircraft position to TAK as \"$droneCallsign\".",
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

    /**
     * The channels, as the SERVER holds them.
     *
     * This is not a local preference any more. The check box shows the server's `active` state,
     * and a change PUTs the new set to the server — the method a real TAK client uses. Nothing
     * is stored on the phone, thus nothing here can disagree with the server. The old local
     * picker injected `<dest group>` into outbound CoT, and the server silently dropped every
     * marker that carried it — proved on the Autel sibling 2026-08-15.
     *
     * EVERY CHANNEL CAN BE SWITCHED ON AND OFF, including a receive-only one. The check box is
     * the `active` flag, and `active` governs RECEIVE as well as send. A first version disabled
     * the box on a receive-only channel, which confused "cannot publish to it" with "cannot use
     * it" — and left a channel that could be switched off from TAK Portal with no way to switch
     * it back on from the controller (operator, 2026-08-16). ADS-B is exactly the channel a
     * pilot wants to turn off and on: it is noisy, and switching it off stops the traffic.
     *
     * The direction is shown as text instead. It tells the pilot what the channel will and will
     * not carry, and it takes nothing away from them.
     */
    private fun renderChannels(channels: List<TakMissionClient.Channel>) {
        val list = findViewById<android.widget.LinearLayout>(R.id.takChannelsList)
        list.removeAllViews()
        latestChannels = channels
        if (channels.isEmpty()) {
            // A server with channels turned off returns none. Say so, and offer no control:
            // writing to such a server is reported to cause real trouble on it.
            findViewById<TextView>(R.id.takChannelsStatus).text =
                "This server has no channels."
            return
        }
        for (ch in channels) {
            val row = android.widget.CheckBox(this).apply {
                // Two-way is the normal case and gets no label — a note on every row is
                // noise, and the exception is what a pilot needs to see (operator,
                // 2026-08-16).
                text = when {
                    ch.canSend && ch.canReceive -> ch.name
                    ch.canReceive -> "${ch.name} - Rx Only"
                    ch.canSend -> "${ch.name} - Tx Only"
                    else -> "${ch.name} - no direction"
                }
                // Secondary text is the only hint that the row is locked. The tick stays
                // full contrast, because the tick is the information.
                setTextColor(ContextCompat.getColor(
                    applicationContext,
                    if (takConfigLocked()) R.color.tp_text_secondary else R.color.tp_text_primary))
                // Enabled for every channel. See the note above: the box is `active`, and a
                // receive-only channel is still one a pilot may want on or off.
                // ⚠ THE LOCK STOPS A CHANGE, NOT THE READING. The rows still follow the
                // server while locked — a pilot must always be able to SEE the scope of this
                // aircraft. The lock exists to stop an accidental change, not to hide the truth
                // (operator, 2026-08-16).
                //
                // ⚠ LOCKED IS NOT DISABLED. isEnabled=false greys the tick as well as the row,
                // and a pilot then cannot tell a checked box from an unchecked one — which
                // defeats the paragraph above. The row stays at full contrast and stops taking
                // touches instead. The check box keeps its own tint for the same reason.
                isChecked = ch.active
                isClickable = !takConfigLocked()
                isFocusable = !takConfigLocked()
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(applicationContext, R.color.tp_accent))
                setOnCheckedChangeListener { _, checked ->
                    if (updatingChannels) return@setOnCheckedChangeListener
                    ch.active = checked
                    pushActiveChannels()
                }
            }
            list.addView(row)
        }
    }

    /**
     * Sends the COMPLETE set of active channels to the server.
     *
     * ⚠ activebits is ABSOLUTE. Anything not in this list is switched off, thus the whole set
     * goes every time and never a change. ⚠ It applies to the CERTIFICATE — every controller
     * enrolled as this user gets this set.
     */
    private fun pushActiveChannels() {
        // ⚠ NEVER WRITE TO A SERVER THAT HAS NO CHANNELS. Cory Foy (TAK Aware) reported
        // 2026-08-16 that a channel change sent to a server which does not have channels
        // enabled can do real damage server side — days of debugging on one deployment. No row
        // exists when the list is empty, thus no toggle can fire this, but the guard is here
        // so that stays true if a caller is ever added.
        if (latestChannels.isEmpty()) {
            AppLog.w(TAG, "channel write refused — this server returned no channels")
            return
        }
        val bits = latestChannels.filter { it.active && it.bitpos >= 0 }.map { it.bitpos }
        val status = findViewById<TextView>(R.id.takChannelsStatus)
        status.text = "Sending ${bits.size} active channel(s) to the server…"
        TakMissionManager.setActiveChannels(bits) { ok ->
            status.text = if (ok) "Server accepted ${bits.size} active channel(s)."
                          else "The server refused the change. See the log."
            status.setTextColor(ContextCompat.getColor(applicationContext,
                if (ok) R.color.tp_state_go else R.color.tp_state_danger))
            // Read it back. The server is the truth, not what was just tapped.
            refreshChannels()
        }
    }

    /** Re-reads the channels from the server and repaints. The server can be changed from TAK
     *  Portal by an administrator, thus the screen must follow it and not a local copy. */
    private fun refreshChannels() {
        TakMissionManager.listChannels { chans ->
            updatingChannels = true
            renderChannels(chans)
            updatingChannels = false
        }
    }

    /** The server told us the channels changed. Read them again — the event carries a notice,
     *  not a list. */
    private val groupChangeListener = TakManager.GroupChangeListener {
        AppLog.i(TAG, "channels changed on the server — re-reading")
        refreshChannels()
        findViewById<TextView>(R.id.takChannelsStatus)?.text =
            "The server changed the channels. The list is up to date."
    }

    /** Reads the channels again when TAK connects. Nothing else here needs contact events. */
    private val connectionListener = object : TakManager.TakUserListener {
        override fun onTakUserUpdated(user: com.taklite.client.tak.TakUser) {}
        override fun onTakUserRemoved(uid: String) {}
        override fun onTakUserDeleted(uid: String) {}
        override fun onTakConnectionChanged(connected: Boolean) {
            if (connected) {
                AppLog.i(TAG, "TAK connected — reading the channels")
                refreshChannels()
            }
        }
    }

    /** The TAK configuration lock. The channel rows read it each time they are painted, thus a
     *  lock or unlock takes effect without leaving the screen. */
    private fun takConfigLocked(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_TAK_LOCKED, false)

    private var latestChannels: List<TakMissionClient.Channel> = emptyList()
    /** True while the check boxes are being set from server data, so the listener does not
     *  treat a repaint as a pilot's tap and PUT it straight back. */
    private var updatingChannels = false

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
        // LEGACY — the old local channel picker's CSV. Nothing writes it any more (the
        // channels live on the server since the <dest group> removal); clearEnrollment still
        // removes it so an upgraded install does not carry it around for ever.
        private const val KEY_CHANNELS = "channels"
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
        /** The outbound codec ("h264"/"h265") — read by VideoStreamerHolder.buildConfig. */
        private const val KEY_V_CODEC = "video_codec"
        /** Which of the two video-server slots is live: 1 or 2. The slot keys themselves are
         *  built by [vKey]; these plain keys stay as the mirror every consumer reads. */
        private const val KEY_V_ACTIVE_SLOT = "video_active_slot"
        /** One-shot marker for [migrateVideoSlots]. */
        private const val KEY_V_SLOTS_MIGRATED = "video_slots_migrated"
    }

    /** Action-bar menu button behaves the same as the system back gesture. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

}


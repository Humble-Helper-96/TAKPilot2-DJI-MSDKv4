package com.dji.sdk.sample.tak

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.taklite.util.AppLog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dji.common.error.DJIError
import dji.common.error.DJISDKError
import dji.common.realname.AppActivationState
import dji.common.useraccount.UserAccountState
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseComponent
import dji.sdk.base.BaseProduct
import dji.sdk.realname.AppActivationManager
import dji.sdk.sdkmanager.DJISDKInitEvent
import dji.sdk.sdkmanager.DJISDKManager
import dji.sdk.useraccount.UserAccountManager
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers the app with the DJI SDK and starts the product connection directly from
 * TAKPilot2's own launcher activity, instead of requiring the stock sample's
 * MainActivity/MainContent "Register App" + "Open" screen first (see
 * docs/TAKPILOT2_V4_PORT_SUMMARY.md for why that screen existed).
 *
 * DJISDKManager is a process-wide singleton — registerApp()/startConnectionToProduct()
 * only need to run once per process, from any Context. This is a straight extraction of
 * MainContent.java's startSDKRegistration()/loginToActivationIfNeeded() logic, kept
 * behaviorally identical, just not tied to a View lifecycle.
 */
object DjiSdkBridge {

    private const val TAG = "DjiSdkBridge"

    /** Aircraft health/readiness. Its own tag so it greps cleanly, and — importantly — one that
     *  is NOT in AppLog's TAK_TAGS, so it stays in the file when TAK logging is filtered off. */
    private const val DIAG_TAG = "TP2Diag"
    const val PERMISSION_REQUEST_CODE = 1001
    private const val ACTIVATION_DELAY_MS = 3000L

    private val REQUIRED_PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.VIBRATE,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.VIBRATE,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO
            )
        }

    private val isRegistrationInProgress = AtomicBoolean(false)
    private val hasAppActivationListenerStarted = AtomicBoolean(false)
    private var appActivationStateListener: AppActivationState.AppActivationStateListener? = null
    private var activityRef: WeakReference<Activity>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activationRunnable = Runnable { loginToActivationIfNeeded() }

    fun missingPermissions(context: Context): Array<String> =
        REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    fun hasMissingPermissions(context: Context): Boolean = missingPermissions(context).isNotEmpty()

    /** Kick off the runtime permission dialog; result lands in the activity's
     *  onRequestPermissionsResult(PERMISSION_REQUEST_CODE, ...) — call [registerAndConnect]
     *  again once everything is granted. */
    fun requestMissingPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(activity, missingPermissions(activity), PERMISSION_REQUEST_CODE)
    }

    /**
     * Registers the app with the DJI SDK and, once registration succeeds, starts the
     * connection to the product. Safe to call more than once (e.g. from onCreate and again
     * from onRequestPermissionsResult) — only the first successful call does anything for
     * the lifetime of the process.
     */
    fun registerAndConnect(activity: Activity) {
        activityRef = WeakReference(activity)

        if (hasMissingPermissions(activity)) {
            AppLog.w(TAG, "registerAndConnect: permissions not yet granted, deferring")
            return
        }
        if (!isRegistrationInProgress.compareAndSet(false, true)) {
            return
        }

        AppLog.i(TAG, "Registering app with the DJI SDK")
        DJISDKManager.getInstance().registerApp(activity.applicationContext, object : DJISDKManager.SDKManagerCallback {
            override fun onRegister(djiError: DJIError) {
                if (djiError == DJISDKError.REGISTRATION_SUCCESS) {
                    AppLog.i(TAG, "DJI SDK registration succeeded, starting connection to product")
                    DJISDKManager.getInstance().startConnectionToProduct()
                } else {
                    AppLog.e(TAG, "DJI SDK registration failed: ${djiError.description}")
                    // Allow a retry (e.g. no network yet on cold boot) on the next call.
                    isRegistrationInProgress.set(false)
                }
            }

            override fun onProductDisconnect() {
                AppLog.d(TAG, "onProductDisconnect")
                lastDiagnostics = ""
                // Stale faults from a disconnected aircraft must not stay on the pilot's screen.
                diagnostics = emptyList()
                runCatching { onDiagnostics?.invoke(emptyList()) }
            }

            override fun onProductConnect(baseProduct: BaseProduct?) {
                AppLog.d(TAG, "onProductConnect: $baseProduct")
                if (baseProduct != null) {
                    addAppActivationListenerIfNeeded()
                    subscribeDiagnostics(baseProduct)
                }
            }

            override fun onProductChanged(baseProduct: BaseProduct?) {
                AppLog.d(TAG, "onProductChanged: $baseProduct")
            }

            override fun onComponentChange(
                componentKey: BaseProduct.ComponentKey?,
                oldComponent: BaseComponent?,
                newComponent: BaseComponent?
            ) {
                AppLog.d(TAG, "onComponentChange key:$componentKey old:$oldComponent new:$newComponent")
            }

            override fun onInitProcess(djisdkInitEvent: DJISDKInitEvent?, i: Int) {}

            override fun onDatabaseDownloadProgress(current: Long, total: Long) {
                if (total > 0) {
                    AppLog.d(TAG, "Fly-zone DB load progress: ${100 * current / total}%")
                }
            }
        })
    }

    /**
     * Subscribes to the aircraft's own health/diagnostics stream — DJI's answer to "why won't it
     * do the thing", the same content DJI Fly prints as red banner text (compass error, IMU
     * calibration required, restricted zone, battery fault, and the rest).
     *
     * Added 2026-08-03 after a motors-won't-start investigation ran out of evidence twice: the
     * app configured the aircraft, logged every call as OK, and then had nothing whatsoever to
     * say about why the aircraft declined to arm, because it never asked. Every hypothesis was
     * therefore a guess, and guesses are expensive when the answer is one subscription away.
     *
     * Logged under [DIAG_TAG], deliberately NOT a TAK tag: the readiness picture must survive the
     * Debug screen's "TAK logging off" filter, which is exactly the setting an operator turns on
     * while diagnosing something else — that filter is what hid the telemetry the first time.
     *
     * Deduped against the last rendered set: this callback re-fires continuously with the same
     * content, so logging unconditionally would bury the log in repeats of one steady condition.
     */
    private var lastDiagnostics = ""

    /**
     * Current aircraft faults/warnings in pilot-readable form, newest snapshot wins. Empty when
     * the aircraft is happy or nothing is connected. Read this on screen entry — the callback
     * only fires on CHANGE, so a screen opened while a fault is already standing would otherwise
     * show nothing.
     */
    @Volatile
    var diagnostics: List<String> = emptyList()
        private set

    /** Notified (on DJI's callback thread — marshal to the UI yourself) whenever [diagnostics]
     *  changes. Single slot: the flight screen owns it while it's up. */
    @Volatile
    var onDiagnostics: ((List<String>) -> Unit)? = null

    /**
     * DJI hands back an untranslated enum token instead of a sentence for conditions it has no
     * localized string for (field-observed: `SHOULD_CHECK_BLADE_INSTALL` alongside the perfectly
     * readable "Cannot takeoff in a no-fly zone"). Showing a pilot SCREAMING_SNAKE is worse than
     * not showing it, so tokens get turned back into words. Anything already containing lowercase
     * is a real message and passes through untouched.
     */
    private fun humanReason(reason: String?): String? {
        val raw = reason?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.matches(Regex("[A-Z0-9_]{4,}"))) return raw
        return raw.split('_')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { c -> c.uppercase() } }
    }

    private fun subscribeDiagnostics(product: BaseProduct) {
        runCatching {
            product.setDiagnosticsInformationCallback { list ->
                val items = list.orEmpty()
                val rendered = if (items.isEmpty()) "none" else items.joinToString(" | ") {
                    "[${it.type}/${it.code}] ${it.reason}${it.solution?.let { s -> " -> $s" } ?: ""}"
                }
                if (rendered == lastDiagnostics) return@setDiagnosticsInformationCallback
                lastDiagnostics = rendered
                AppLog.i(DIAG_TAG, "aircraft diagnostics: $rendered")
                // Distinct: the same condition is reported once per affected component (the
                // no-fly-zone refusal arrived on both 8012 and 8018), and a pilot does not need
                // to read it twice.
                val readable = items.mapNotNull { d ->
                    humanReason(d.reason)?.let { r ->
                        val fix = humanReason(d.solution)
                        if (fix.isNullOrEmpty()) r else "$r — $fix"
                    }
                }.distinct()
                diagnostics = readable
                runCatching { onDiagnostics?.invoke(readable) }
            }
            AppLog.i(DIAG_TAG, "diagnostics subscription active")
        }.onFailure {
            AppLog.w(DIAG_TAG, "could not subscribe to diagnostics: ${it.message}")
        }
    }

    private fun addAppActivationListenerIfNeeded() {
        if (AppActivationManager.getInstance().appActivationState != AppActivationState.ACTIVATED) {
            mainHandler.removeCallbacks(activationRunnable)
            mainHandler.postDelayed(activationRunnable, ACTIVATION_DELAY_MS)
            if (hasAppActivationListenerStarted.compareAndSet(false, true)) {
                val listener = object : AppActivationState.AppActivationStateListener {
                    override fun onUpdate(state: AppActivationState) {
                        mainHandler.removeCallbacks(activationRunnable)
                        if (state != AppActivationState.ACTIVATED) {
                            mainHandler.postDelayed(activationRunnable, ACTIVATION_DELAY_MS)
                        }
                    }
                }
                appActivationStateListener = listener
                AppActivationManager.getInstance().addAppActivationStateListener(listener)
            }
        }
    }

    private fun loginToActivationIfNeeded() {
        if (AppActivationManager.getInstance().appActivationState != AppActivationState.LOGIN_REQUIRED) {
            return
        }
        val activity = activityRef?.get() ?: return
        UserAccountManager.getInstance().logIntoDJIUserAccount(
            activity,
            object : CommonCallbacks.CompletionCallbackWith<UserAccountState> {
                override fun onSuccess(userAccountState: UserAccountState?) {
                    AppLog.i(TAG, "DJI account login succeeded")
                }

                override fun onFailure(djiError: DJIError) {
                    AppLog.e(TAG, "DJI account login failed: ${djiError.description}")
                }
            }
        )
    }
}

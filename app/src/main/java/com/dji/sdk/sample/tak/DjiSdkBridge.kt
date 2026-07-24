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
 * DJI/TAKPILOT2_V4_PORT_PLAN.md for why that screen existed).
 *
 * DJISDKManager is a process-wide singleton — registerApp()/startConnectionToProduct()
 * only need to run once per process, from any Context. This is a straight extraction of
 * MainContent.java's startSDKRegistration()/loginToActivationIfNeeded() logic, kept
 * behaviorally identical, just not tied to a View lifecycle.
 */
object DjiSdkBridge {

    private const val TAG = "DjiSdkBridge"
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
            }

            override fun onProductConnect(baseProduct: BaseProduct?) {
                AppLog.d(TAG, "onProductConnect: $baseProduct")
                if (baseProduct != null) {
                    addAppActivationListenerIfNeeded()
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

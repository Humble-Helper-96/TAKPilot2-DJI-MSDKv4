package com.dji.sdk.sample.internal.controller;

import android.app.Application;
import android.content.Context;

import com.squareup.otto.Bus;
import com.squareup.otto.ThreadEnforcer;
import com.taklite.util.AppLog;

import androidx.multidex.MultiDex;
import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.products.HandHeld;
import dji.sdk.sdkmanager.BluetoothProductConnector;
import dji.sdk.sdkmanager.DJISDKManager;

/**
 * Main application
 */
public class DJISampleApplication extends Application {

    public static final String TAG = DJISampleApplication.class.getName();

    private static BaseProduct product;
    private static BluetoothProductConnector bluetoothConnector = null;
    private static Bus bus = new Bus(ThreadEnforcer.ANY);
    private static Application app = null;

    /**
     * Gets instance of the specific product connected after the
     * API KEY is successfully validated. Please make sure the
     * API_KEY has been added in the Manifest
     */
    public static synchronized BaseProduct getProductInstance() {
        product = DJISDKManager.getInstance().getProduct();
        return product;
    }

    public static synchronized BluetoothProductConnector getBluetoothProductConnector() {
        bluetoothConnector = DJISDKManager.getInstance().getBluetoothProductConnector();
        return bluetoothConnector;
    }

    public static boolean isAircraftConnected() {
        return getProductInstance() != null && getProductInstance() instanceof Aircraft;
    }

    public static boolean isHandHeldConnected() {
        return getProductInstance() != null && getProductInstance() instanceof HandHeld;
    }

    public static synchronized Aircraft getAircraftInstance() {
        if (!isAircraftConnected()) {
            return null;
        }
        return (Aircraft) getProductInstance();
    }

    public static synchronized HandHeld getHandHeldInstance() {
        if (!isHandHeldConnected()) {
            return null;
        }
        return (HandHeld) getProductInstance();
    }

    public static Application getInstance() {
        return DJISampleApplication.app;
    }

    public static Bus getEventBus() {
        return bus;
    }

    @Override
    protected void attachBaseContext(Context paramContext) {
        super.attachBaseContext(paramContext);
        MultiDex.install(this);
        com.cySdkyc.clx.Helper.install(this);
        app = this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        // Crash capture only while debug logging is toggled on (Debug screen); always
        // chains to the system's previous handler so existing crash behavior is unchanged.
        Thread.setDefaultUncaughtExceptionHandler(
                new AppLogCrashHandler(Thread.getDefaultUncaughtExceptionHandler()));
        // Real client identity for CoT's <takv> block, so this app's own entry in a TAK
        // server's connected-users list says what it actually is, not the shared TakManager
        // core's generic placeholder. See TakManager.setClientIdentity's doc — deliberately
        // set from HERE (this app), not inside the shared core, which is also used by the
        // separate Autel sibling port and must not be hardcoded to either app's name.
        com.taklite.client.tak.TakManager.getInstance().setClientIdentity(
                "TAKPilot",
                android.os.Build.MODEL,
                "Android " + android.os.Build.VERSION.RELEASE,
                appVersionName(this));
        // Register the TAK contact listener at process start, not when the flight screen
        // opens — inbound contacts then accumulate in the background and the mini-map has a
        // full picture the moment it appears instead of filling in over the next minute.
        com.dji.sdk.sample.tak.TakMapMarkers.INSTANCE.install(this);
        com.dji.sdk.sample.tak.TakDropMarkers.INSTANCE.init(this);
        // Same reasoning: pull the downloaded FAA ceilings into memory on a background thread
        // now, so the flight screen's HUD tick never has to do a tens-of-thousands-of-rows read
        // on the main thread while video is running.
        com.dji.sdk.sample.tak.UasfmIndex.INSTANCE.preload(this);
    }

    /** Real versionName from the manifest, not a hand-maintained string — so the CoT client
     *  identity can never silently drift out of sync with what actually shipped, the way the
     *  shared core's old hardcoded "1.0" placeholder already had. "unknown" only if the package
     *  itself can't be read, which should not happen for a running app. */
    private static String appVersionName(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static class AppLogCrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous;

        AppLogCrashHandler(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable ex) {
            if (AppLog.getEnabled()) {
                AppLog.writeCrash(thread, ex);
            }
            if (previous != null) {
                previous.uncaughtException(thread, ex);
            }
        }
    }
}
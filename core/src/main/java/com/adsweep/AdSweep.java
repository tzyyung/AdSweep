package com.adsweep;

import android.content.Context;
import android.util.Log;

import com.adsweep.engine.actions.MonitorAction;
import com.adsweep.hook.HookEngine;
import com.adsweep.hook.HookManager;
import com.adsweep.hook.LayerThreeMonitor;
import com.adsweep.reporter.FloatingReporter;

import java.io.File;

/**
 * AdSweep entry point. Called from the target app's Application.onCreate().
 *
 * Designed for graceful degradation: any failure in AdSweep initialization
 * will NOT crash the host app. All exceptions are caught and logged.
 */
public final class AdSweep {

    private static final String TAG = "AdSweep";
    private static HookManager hookManager;
    private static boolean initialized = false;

    private AdSweep() {}

    /**
     * Initialize AdSweep. Must be called once from Application.onCreate().
     * Never throws — all errors are caught and logged.
     */
    public static void init(Context context) {
        try {
            initInternal(context);
        } catch (Throwable t) {
            // Catch absolutely everything — AdSweep must never crash the host app
            Log.e(TAG, "Initialization failed (app will continue without ad blocking)", t);
        }
    }

    private static void initInternal(Context context) {
        if (initialized) {
            Log.w(TAG, "Already initialized, skipping");
            return;
        }

        Log.i(TAG, "=== AdSweep Initializing ===");

        if (!HookEngine.isAvailable()) {
            Log.e(TAG, "Hook engine not available. Native library may have failed to load.");
            Log.e(TAG, "App will continue without ad blocking.");
            return;
        }

        Log.i(TAG, "Hook engine ready");

        // Check for discovery mode (flag file in assets)
        boolean discoverMode = false;
        try {
            context.getAssets().open("adsweep_discover_mode");
            discoverMode = true;
            File logFile = new File(context.getFilesDir(), "adsweep/discovery_log.txt");
            logFile.getParentFile().mkdirs();
            MonitorAction.initFileLog(logFile);
            Log.i(TAG, "=== DISCOVERY MODE — monitoring only, not blocking ===");
        } catch (Exception e) {
            // No flag file = normal mode
        }

        hookManager = new HookManager(context);
        hookManager.initialize();

        // Initialize Layer 3: floating reporter + lightweight runtime monitors
        try {
            FloatingReporter.init(context, hookManager.getRuleStore());
        } catch (Throwable t) {
            Log.w(TAG, "FloatingReporter init failed (non-critical)", t);
        }

        try {
            LayerThreeMonitor l3 = new LayerThreeMonitor(context);
            l3.installMonitors();
        } catch (Throwable t) {
            Log.w(TAG, "Layer 3 init failed (non-critical)", t);
        }

        initialized = true;
        Log.i(TAG, "=== AdSweep Ready: " + hookManager.getActiveHookCount() + " hooks active ===");
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static HookManager getHookManager() {
        return hookManager;
    }
}

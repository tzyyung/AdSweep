package com.adsweep;

import android.content.Context;
import android.util.Log;

import com.adsweep.hook.HookEngine;
import com.adsweep.hook.HookManager;

/**
 * AdSweep entry point. Called from the target app's Application.onCreate().
 *
 * The injector script inserts this call:
 *   invoke-static {p0}, Lcom/adsweep/AdSweep;->init(Landroid/content/Context;)V
 */
public final class AdSweep {

    private static final String TAG = "AdSweep";
    private static HookManager hookManager;
    private static boolean initialized = false;

    private AdSweep() {}

    /**
     * Initialize AdSweep. Must be called once from Application.onCreate().
     */
    public static void init(Context context) {
        if (initialized) {
            Log.w(TAG, "Already initialized, skipping");
            return;
        }

        Log.i(TAG, "=== AdSweep Initializing ===");

        if (!HookEngine.isAvailable()) {
            Log.e(TAG, "Hook engine not available. Native library may have failed to load.");
            return;
        }

        Log.i(TAG, "Hook engine ready");

        hookManager = new HookManager(context);
        hookManager.initialize();

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

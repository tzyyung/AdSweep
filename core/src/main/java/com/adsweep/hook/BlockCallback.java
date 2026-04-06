package com.adsweep.hook;

import android.util.Log;

/**
 * Simple callback that blocks the hooked method (returns null/void).
 */
public class BlockCallback extends HookCallback {

    private static final String TAG = "AdSweep.Block";
    private final String description;

    public BlockCallback(String description) {
        this.description = description;
    }

    @Override
    public Object handleHook(Object[] args) {
        Log.i(TAG, "Blocked: " + description);
        return null;
    }
}

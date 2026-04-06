package com.adsweep.hook;

import android.util.Log;

/**
 * Callback that blocks the hooked method with a configurable return value.
 */
public class BlockCallback extends HookCallback {

    private static final String TAG = "AdSweep.Block";
    private final String description;
    private final String action;

    public BlockCallback(String description, String action) {
        this.description = description;
        this.action = action;
    }

    @Override
    public Object handleHook(Object[] args) {
        try {
            Log.i(TAG, "Blocked: " + description);

            switch (action) {
                case "BLOCK_RETURN_TRUE":
                    return Boolean.TRUE;
                case "BLOCK_RETURN_FALSE":
                    return Boolean.FALSE;
                case "BLOCK_RETURN_ZERO":
                    return 0;
                case "BLOCK_RETURN_EMPTY_STRING":
                    return "";
                case "BLOCK_RETURN_NULL":
                    return null;
                case "BLOCK_RETURN_VOID":
                default:
                    return null;
            }
        } catch (Throwable t) {
            // Never let a callback crash the app — fallback to calling original
            Log.e(TAG, "Callback error for " + description + ", calling original", t);
            try {
                return callOriginal(args);
            } catch (Exception e) {
                return null;
            }
        }
    }
}

package com.adsweep.hook;

import android.util.Log;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * JNI bridge to LSPlant hook engine.
 */
public final class HookEngine {

    private static final String TAG = "AdSweep.HookEngine";
    private static boolean loaded = false;

    static {
        try {
            System.loadLibrary("adsweep");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
        }
    }

    private HookEngine() {}

    public static boolean isAvailable() {
        try {
            return loaded && nativeIsInitialized();
        } catch (Throwable t) {
            Log.e(TAG, "isAvailable check failed", t);
            return false;
        }
    }

    /**
     * Hook a target method. The callback object's callbackMethod will be invoked
     * instead of the target method.
     *
     * @param target         The method or constructor to hook
     * @param callback       The object that owns the callback method
     * @param callbackMethod The method to invoke as replacement
     * @return A backup Method that can be used to call the original, or null on failure
     */
    public static Method hook(Member target, Object callback, Method callbackMethod) {
        if (!isAvailable()) {
            Log.e(TAG, "Cannot hook: engine not available");
            return null;
        }
        try {
            return (Method) nativeHook(target, callback, callbackMethod);
        } catch (Exception e) {
            Log.e(TAG, "Hook failed for " + target, e);
            return null;
        }
    }

    public static boolean unhook(Member target) {
        if (!isAvailable()) return false;
        try {
            return nativeUnhook(target);
        } catch (Exception e) {
            Log.e(TAG, "Unhook failed for " + target, e);
            return false;
        }
    }

    public static boolean isHooked(Member target) {
        if (!isAvailable()) return false;
        return nativeIsHooked(target);
    }

    public static boolean deoptimize(Member target) {
        if (!isAvailable()) return false;
        return nativeDeoptimize(target);
    }

    // Native methods
    private static native Object nativeHook(Object target, Object callback, Object callbackMethod);
    private static native boolean nativeUnhook(Object target);
    private static native boolean nativeIsHooked(Object target);
    private static native boolean nativeDeoptimize(Object target);
    private static native boolean nativeIsInitialized();
}

package com.adsweep.hook;

import android.content.Context;
import android.util.Log;

import com.adsweep.rules.Rule;
import com.adsweep.rules.RuleStore;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates hook installation across all detection layers.
 */
public class HookManager {

    private static final String TAG = "AdSweep.HookManager";

    private final Context appContext;
    private final RuleStore ruleStore;
    private final Map<String, Method> backupMethods = new ConcurrentHashMap<>();

    public HookManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.ruleStore = new RuleStore(context);
    }

    /**
     * Initialize all hook layers.
     */
    public void initialize() {
        if (!HookEngine.isAvailable()) {
            Log.e(TAG, "Hook engine not available, skipping initialization");
            return;
        }

        // Layer 1: Hook all known SDK methods found in this app
        List<Rule> rules = ruleStore.getActiveRules();
        Log.i(TAG, "Loading " + rules.size() + " rules");

        ClassLoader cl = appContext.getClassLoader();
        int hooked = 0;

        for (Rule rule : rules) {
            if (!rule.enabled) continue;

            try {
                Class<?> targetClass = cl.loadClass(rule.className);
                Method targetMethod = resolveMethod(targetClass, rule.methodName, rule.paramTypes);

                if (targetMethod == null) {
                    Log.w(TAG, "Method not found: " + rule.className + "." + rule.methodName);
                    continue;
                }

                if (installHook(rule, targetMethod)) {
                    hooked++;
                }
            } catch (ClassNotFoundException e) {
                // SDK not present in this app — expected, skip silently
            } catch (Exception e) {
                Log.e(TAG, "Error processing rule: " + rule.id, e);
            }
        }

        Log.i(TAG, "Initialization complete: " + hooked + "/" + rules.size() + " hooks installed");
    }

    /**
     * Install a hook for a specific rule and target method.
     */
    public boolean installHook(Rule rule, Method targetMethod) {
        String key = rule.className + "." + rule.methodName;

        BlockCallback callback = new BlockCallback(key, rule.action);
        try {
            Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
            Method backup = HookEngine.hook(targetMethod, callback, callbackMethod);

            if (backup != null) {
                callback.setBackupMethod(backup);
                backupMethods.put(key, backup);
                Log.i(TAG, "Hooked: " + key + " [" + rule.action + "]");
                return true;
            } else {
                Log.w(TAG, "Failed to hook: " + key);
                return false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Hook install error for " + key, t);
            return false;
        }
    }

    /**
     * Remove a hook.
     */
    public boolean removeHook(String className, String methodName) {
        String key = className + "." + methodName;
        // The actual unhook would need a reference to the original Member
        // For now, this is a placeholder
        backupMethods.remove(key);
        return true;
    }

    /**
     * Resolve a method by name and parameter types.
     */
    private Method resolveMethod(Class<?> clazz, String methodName, String[] paramTypes) {
        if (paramTypes != null && paramTypes.length > 0) {
            try {
                Class<?>[] params = new Class[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    params[i] = resolveClass(paramTypes[i]);
                }
                return clazz.getDeclaredMethod(methodName, params);
            } catch (Exception e) {
                // Fall through to try without params
            }
        }

        // Try to find method by name only (first match)
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        return null;
    }

    private Class<?> resolveClass(String name) throws ClassNotFoundException {
        switch (name) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "float": return float.class;
            case "double": return double.class;
            case "void": return void.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "char": return char.class;
            default: return appContext.getClassLoader().loadClass(name);
        }
    }

    public RuleStore getRuleStore() {
        return ruleStore;
    }

    public int getActiveHookCount() {
        return backupMethods.size();
    }
}

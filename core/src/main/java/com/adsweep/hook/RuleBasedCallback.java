package com.adsweep.hook;

import android.util.Log;

import com.adsweep.engine.HookRule;

import java.lang.reflect.Method;

/**
 * Hook callback that delegates to a HookRule for conditional evaluation.
 * Replaces BlockCallback for rules that have conditions.
 */
public class RuleBasedCallback extends HookCallback {

    private static final String TAG = "AdSweep.RuleCB";
    private final HookRule rule;
    private final Method targetMethod;
    private final Class<?> targetClass;
    private final String packageName;

    public RuleBasedCallback(HookRule rule, Method targetMethod,
                             Class<?> targetClass, String packageName) {
        this.rule = rule;
        this.targetMethod = targetMethod;
        this.targetClass = targetClass;
        this.packageName = packageName;
    }

    @Override
    public Object handleHook(Object[] args) {
        try {
            HookContext ctx = new HookContext(
                    args, targetMethod, targetClass, backupMethod, packageName);
            return rule.apply(ctx);
        } catch (Throwable t) {
            Log.e(TAG, "Rule error [" + rule.getId() + "], calling original", t);
            try {
                return callOriginal(args);
            } catch (Exception e) {
                return null;
            }
        }
    }
}

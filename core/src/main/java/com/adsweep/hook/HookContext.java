package com.adsweep.hook;

import java.lang.reflect.Method;

/**
 * Strong-typed context for hook callbacks, replacing Easy Rules' Facts.
 * Passed to RuleCondition.evaluate() and RuleAction.execute().
 */
public class HookContext {

    public final Object[] args;
    public final Method targetMethod;
    public final Class<?> targetClass;
    public final Method backupMethod;
    public final String packageName;

    public HookContext(Object[] args, Method targetMethod, Class<?> targetClass,
                       Method backupMethod, String packageName) {
        this.args = args;
        this.targetMethod = targetMethod;
        this.targetClass = targetClass;
        this.backupMethod = backupMethod;
        this.packageName = packageName;
    }

    /** Call the original (unhooked) method. */
    public Object callOriginal() throws Exception {
        if (backupMethod == null) {
            throw new IllegalStateException("No backup method available");
        }
        return backupMethod.invoke(null, args);
    }

    /** Get argument at index (0 = this for instance methods). */
    public Object getArg(int index) {
        return (index >= 0 && index < args.length) ? args[index] : null;
    }

    /** Get argument as String (via toString). */
    public String getArgAsString(int index) {
        Object arg = getArg(index);
        return arg != null ? arg.toString() : null;
    }

    /** Extract a field from an argument via reflection (e.g., "url" calls .url()). */
    public Object extractField(int argIndex, String fieldName) {
        Object arg = getArg(argIndex);
        if (arg == null) return null;
        try {
            Method getter = arg.getClass().getMethod(fieldName);
            return getter.invoke(arg);
        } catch (Exception e) {
            return null;
        }
    }
}

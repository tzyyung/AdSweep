package com.adsweep.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Base class for hook callbacks. Subclass this and override handleHook()
 * to implement custom hook behavior.
 *
 * LSPlant requires the callback to be a concrete method on an object instance.
 * This class provides that structure.
 */
public abstract class HookCallback {

    protected Method backupMethod;
    protected Method targetMethod;

    public void setBackupMethod(Method backup) {
        this.backupMethod = backup;
    }

    public void setTargetMethod(Method target) {
        this.targetMethod = target;
    }

    /**
     * Called when the hooked method is invoked.
     *
     * @param args For instance methods: args[0] is 'this', args[1..n] are parameters.
     *             For static methods: args[0..n] are parameters.
     * @return The return value to use (null for void methods)
     */
    public abstract Object handleHook(Object[] args);

    /**
     * Call the original (unhooked) method.
     */
    protected Object callOriginal(Object[] args) throws Exception {
        if (backupMethod == null) {
            throw new IllegalStateException("No backup method available");
        }
        if (targetMethod != null && !Modifier.isStatic(targetMethod.getModifiers())) {
            // Instance method: args[0] is 'this', rest are parameters
            Object receiver = args[0];
            Object[] params = Arrays.copyOfRange(args, 1, args.length);
            return backupMethod.invoke(receiver, params);
        }
        return backupMethod.invoke(null, args);
    }
}

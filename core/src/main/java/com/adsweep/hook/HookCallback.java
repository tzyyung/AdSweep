package com.adsweep.hook;

import java.lang.reflect.Method;

/**
 * Base class for hook callbacks. Subclass this and override handleHook()
 * to implement custom hook behavior.
 *
 * LSPlant requires the callback to be a concrete method on an object instance.
 * This class provides that structure.
 */
public abstract class HookCallback {

    protected Method backupMethod;

    public void setBackupMethod(Method backup) {
        this.backupMethod = backup;
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
        return backupMethod.invoke(null, args);
    }
}

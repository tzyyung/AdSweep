package com.adsweep.engine.actions;

import android.util.Log;

import com.adsweep.engine.RuleAction;
import com.adsweep.hook.HookContext;

/** Logs the method call but does not intercept. For --discover mode. */
public class MonitorAction implements RuleAction {

    private static final String TAG = "AdSweep.Monitor";
    private final String description;

    public MonitorAction(String description) {
        this.description = description;
    }

    @Override
    public Object execute(HookContext ctx) throws Exception {
        Log.i(TAG, "Monitor: " + description +
                " args=" + ctx.args.length +
                " method=" + ctx.targetMethod.getName());
        return ctx.callOriginal();
    }
}

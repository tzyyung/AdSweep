package com.adsweep.engine.actions;

import android.util.Log;

import com.adsweep.engine.RuleAction;
import com.adsweep.hook.HookContext;

/** Calls original method (preserving side effects) but overrides the return value. */
public class CallAndModifyAction implements RuleAction {

    private static final String TAG = "AdSweep.CallMod";
    private final Object overrideValue;
    private final String description;

    public CallAndModifyAction(String actionType, String description) {
        this.description = description;
        switch (actionType) {
            case "CALL_AND_RETURN_TRUE":  this.overrideValue = Boolean.TRUE; break;
            case "CALL_AND_RETURN_FALSE": this.overrideValue = Boolean.FALSE; break;
            case "CALL_AND_RETURN_NULL":  this.overrideValue = null; break;
            default: this.overrideValue = null; break;
        }
    }

    @Override
    public Object execute(HookContext ctx) throws Exception {
        Log.i(TAG, "Call+Modify: " + description);
        ctx.callOriginal(); // let side effects run
        return overrideValue;
    }
}

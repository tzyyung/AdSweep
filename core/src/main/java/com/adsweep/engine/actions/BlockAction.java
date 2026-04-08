package com.adsweep.engine.actions;

import android.util.Log;

import com.adsweep.engine.RuleAction;
import com.adsweep.hook.HookContext;

/** Returns a fixed value without calling the original method. */
public class BlockAction implements RuleAction {

    private static final String TAG = "AdSweep.Block";
    private final Object returnValue;
    private final String description;

    public BlockAction(String actionType, String description) {
        this(actionType, description, null);
    }

    public BlockAction(String actionType, String description, String customValue) {
        this.description = description;
        switch (actionType) {
            case "BLOCK_RETURN_TRUE":  this.returnValue = Boolean.TRUE; break;
            case "BLOCK_RETURN_FALSE": this.returnValue = Boolean.FALSE; break;
            case "BLOCK_RETURN_ZERO":  this.returnValue = 0; break;
            case "BLOCK_RETURN_EMPTY_STRING": this.returnValue = ""; break;
            case "BLOCK_RETURN_STRING": this.returnValue = customValue != null ? customValue : ""; break;
            default: this.returnValue = null; break;
        }
    }

    @Override
    public Object execute(HookContext ctx) {
        Log.i(TAG, "Blocked: " + description);
        return returnValue;
    }
}

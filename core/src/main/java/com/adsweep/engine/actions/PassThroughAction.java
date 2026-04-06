package com.adsweep.engine.actions;

import com.adsweep.engine.RuleAction;
import com.adsweep.hook.HookContext;

/** Calls the original method (no interception). Used as elseAction. */
public class PassThroughAction implements RuleAction {
    @Override
    public Object execute(HookContext ctx) throws Exception {
        return ctx.callOriginal();
    }
}

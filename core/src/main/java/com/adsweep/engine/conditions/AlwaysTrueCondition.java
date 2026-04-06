package com.adsweep.engine.conditions;

import com.adsweep.engine.RuleCondition;
import com.adsweep.hook.HookContext;

/** Always returns true. Used for backward-compatible rules without conditions. */
public class AlwaysTrueCondition implements RuleCondition {
    @Override
    public boolean evaluate(HookContext ctx) {
        return true;
    }
}

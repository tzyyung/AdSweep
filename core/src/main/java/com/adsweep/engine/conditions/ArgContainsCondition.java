package com.adsweep.engine.conditions;

import com.adsweep.engine.RuleCondition;
import com.adsweep.hook.HookContext;

import java.util.List;

/** Checks if an argument's string representation contains any of the given patterns. */
public class ArgContainsCondition implements RuleCondition {

    private final int argIndex;
    private final List<String> patterns;

    public ArgContainsCondition(int argIndex, List<String> patterns) {
        this.argIndex = argIndex;
        this.patterns = patterns;
    }

    @Override
    public boolean evaluate(HookContext ctx) {
        String val = ctx.getArgAsString(argIndex);
        if (val == null) return false;
        for (String pattern : patterns) {
            if (val.contains(pattern)) return true;
        }
        return false;
    }
}

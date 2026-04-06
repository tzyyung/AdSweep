package com.adsweep.engine.conditions;

import com.adsweep.engine.RuleCondition;
import com.adsweep.hook.HookContext;

import java.util.regex.Pattern;

/** Checks if an argument matches a regex pattern. */
public class ArgRegexCondition implements RuleCondition {

    private final int argIndex;
    private final Pattern pattern;

    public ArgRegexCondition(int argIndex, String regex) {
        this.argIndex = argIndex;
        this.pattern = Pattern.compile(regex);
    }

    @Override
    public boolean evaluate(HookContext ctx) {
        String val = ctx.getArgAsString(argIndex);
        return val != null && pattern.matcher(val).find();
    }
}

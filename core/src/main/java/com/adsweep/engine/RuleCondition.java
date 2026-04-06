package com.adsweep.engine;

import com.adsweep.hook.HookContext;

/**
 * Rule condition interface. Inspired by Easy Rules' @Condition annotation.
 * Implementations evaluate whether a hook should be triggered.
 */
public interface RuleCondition {
    boolean evaluate(HookContext ctx);
}

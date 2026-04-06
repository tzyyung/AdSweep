package com.adsweep.engine;

import com.adsweep.hook.HookContext;

/**
 * Rule action interface. Inspired by Easy Rules' @Action annotation.
 * Implementations define what happens when a hook is triggered.
 */
public interface RuleAction {
    Object execute(HookContext ctx) throws Exception;
}

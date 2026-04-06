package com.adsweep.engine.conditions;

import com.adsweep.engine.RuleCondition;
import com.adsweep.hook.HookContext;

import java.util.List;

/** Combines multiple conditions with AND/OR logic. Also supports NOT via inner condition. */
public class CompositeCondition implements RuleCondition {

    public enum Operator { AND, OR, NOT }

    private final List<RuleCondition> conditions;
    private final Operator operator;

    public CompositeCondition(List<RuleCondition> conditions, Operator operator) {
        this.conditions = conditions;
        this.operator = operator;
    }

    /** Create a NOT condition (single inner). */
    public static CompositeCondition not(RuleCondition inner) {
        return new CompositeCondition(java.util.Collections.singletonList(inner), Operator.NOT);
    }

    @Override
    public boolean evaluate(HookContext ctx) {
        switch (operator) {
            case AND:
                for (RuleCondition c : conditions) {
                    if (!c.evaluate(ctx)) return false;
                }
                return true;
            case OR:
                for (RuleCondition c : conditions) {
                    if (c.evaluate(ctx)) return true;
                }
                return false;
            case NOT:
                return !conditions.get(0).evaluate(ctx);
            default:
                return true;
        }
    }
}

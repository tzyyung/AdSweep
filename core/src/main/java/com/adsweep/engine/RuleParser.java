package com.adsweep.engine;

import com.adsweep.engine.actions.BlockAction;
import com.adsweep.engine.actions.CallAndModifyAction;
import com.adsweep.engine.actions.MonitorAction;
import com.adsweep.engine.actions.PassThroughAction;
import com.adsweep.engine.conditions.AlwaysTrueCondition;
import com.adsweep.engine.conditions.ArgContainsCondition;
import com.adsweep.engine.conditions.ArgRegexCondition;
import com.adsweep.engine.conditions.CompositeCondition;
import com.adsweep.engine.conditions.UrlMatchesCondition;
import com.adsweep.rules.Rule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses JSON Rule objects into executable HookRules.
 * Backward compatible: rules without "condition" use AlwaysTrueCondition.
 */
public class RuleParser {

    private final DomainMatcher domainMatcher;

    public RuleParser(DomainMatcher domainMatcher) {
        this.domainMatcher = domainMatcher;
    }

    /** Parse a Rule JSON object into an executable HookRule. */
    public HookRule parse(Rule rule) {
        String desc = rule.className + "." + rule.methodName;

        // Parse condition
        RuleCondition condition;
        if (rule.condition != null) {
            condition = parseCondition(rule.condition);
        } else {
            condition = new AlwaysTrueCondition();
        }

        // Parse action
        RuleAction action = parseAction(rule.action, desc, rule.returnValue);

        // ElseAction is always PassThrough (call original)
        RuleAction elseAction = new PassThroughAction();

        int priority = rule.priority > 0 ? rule.priority : 50;

        return new HookRule(rule.id, condition, action, elseAction, priority);
    }

    private RuleCondition parseCondition(JSONObject condJson) {
        try {
            String type = condJson.getString("type");

            switch (type) {
                case "URL_MATCHES": {
                    int argIndex = condJson.optInt("argIndex", 1);
                    String extract = condJson.optString("extract", "toString");
                    return new UrlMatchesCondition(domainMatcher, argIndex, extract);
                }

                case "ARG_CONTAINS": {
                    int argIndex = condJson.optInt("argIndex", 1);
                    List<String> patterns = jsonArrayToList(condJson.getJSONArray("patterns"));
                    return new ArgContainsCondition(argIndex, patterns);
                }

                case "ARG_REGEX": {
                    int argIndex = condJson.optInt("argIndex", 1);
                    String pattern = condJson.getString("pattern");
                    return new ArgRegexCondition(argIndex, pattern);
                }

                case "AND": {
                    List<RuleCondition> subs = parseConditionList(condJson.getJSONArray("conditions"));
                    return new CompositeCondition(subs, CompositeCondition.Operator.AND);
                }

                case "OR": {
                    List<RuleCondition> subs = parseConditionList(condJson.getJSONArray("conditions"));
                    return new CompositeCondition(subs, CompositeCondition.Operator.OR);
                }

                case "NOT": {
                    RuleCondition inner = parseCondition(condJson.getJSONObject("inner"));
                    return CompositeCondition.not(inner);
                }

                default:
                    return new AlwaysTrueCondition();
            }
        } catch (Exception e) {
            return new AlwaysTrueCondition();
        }
    }

    private List<RuleCondition> parseConditionList(JSONArray arr) throws Exception {
        List<RuleCondition> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            list.add(parseCondition(arr.getJSONObject(i)));
        }
        return list;
    }

    private RuleAction parseAction(String action, String description, String returnValue) {
        if (action == null) action = "BLOCK_RETURN_VOID";

        if (action.startsWith("CALL_AND_")) {
            return new CallAndModifyAction(action, description);
        } else if (action.equals("MONITOR_ONLY")) {
            return new MonitorAction(description);
        } else if (action.equals("PASS_THROUGH")) {
            return new PassThroughAction();
        } else {
            return new BlockAction(action, description, returnValue);
        }
    }

    private List<String> jsonArrayToList(JSONArray arr) throws Exception {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            list.add(arr.getString(i));
        }
        return list;
    }
}

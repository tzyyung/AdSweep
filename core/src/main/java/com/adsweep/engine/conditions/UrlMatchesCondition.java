package com.adsweep.engine.conditions;

import com.adsweep.engine.DomainMatcher;
import com.adsweep.engine.RuleCondition;
import com.adsweep.hook.HookContext;

/**
 * Checks if a URL argument matches a domain in the blocklist.
 * Supports extracting URLs from method arguments via toString or field access.
 */
public class UrlMatchesCondition implements RuleCondition {

    private final DomainMatcher domainMatcher;
    private final int argIndex;
    private final String extract; // "toString", "url", or field name

    public UrlMatchesCondition(DomainMatcher domainMatcher, int argIndex, String extract) {
        this.domainMatcher = domainMatcher;
        this.argIndex = argIndex;
        this.extract = extract != null ? extract : "toString";
    }

    @Override
    public boolean evaluate(HookContext ctx) {
        String url = extractUrl(ctx);
        return url != null && domainMatcher.matches(url);
    }

    private String extractUrl(HookContext ctx) {
        Object arg = ctx.getArg(argIndex);
        if (arg == null) return null;

        switch (extract) {
            case "toString":
                return arg.toString();
            case "url":
                // Try arg.url().toString() (for OkHttp Request)
                Object urlObj = ctx.extractField(argIndex, "url");
                return urlObj != null ? urlObj.toString() : null;
            default:
                // Try as field name
                Object field = ctx.extractField(argIndex, extract);
                return field != null ? field.toString() : null;
        }
    }
}

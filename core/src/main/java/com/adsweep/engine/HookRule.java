package com.adsweep.engine;

import android.util.Log;

import com.adsweep.hook.HookContext;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A complete hook rule combining Condition + Action.
 * Inspired by Easy Rules' Rule interface, adapted for AdSweep's 1-to-1 hook model.
 */
public class HookRule implements Comparable<HookRule> {

    private static final String TAG = "AdSweep.Rule";

    private final String id;
    private final RuleCondition condition;
    private final RuleAction action;
    private final RuleAction elseAction;
    private final int priority;
    private boolean enabled;

    // Stats
    private final AtomicInteger hitCount = new AtomicInteger(0);
    private final AtomicInteger missCount = new AtomicInteger(0);
    private volatile long lastHitTime = 0;

    public HookRule(String id, RuleCondition condition, RuleAction action,
                    RuleAction elseAction, int priority) {
        this.id = id;
        this.condition = condition;
        this.action = action;
        this.elseAction = elseAction;
        this.priority = priority;
        this.enabled = true;
    }

    /**
     * Evaluate condition and execute appropriate action.
     * This is the main entry point called from hook callbacks.
     */
    public Object apply(HookContext ctx) throws Exception {
        if (!enabled) {
            return elseAction.execute(ctx);
        }

        if (condition.evaluate(ctx)) {
            hitCount.incrementAndGet();
            lastHitTime = System.currentTimeMillis();
            return action.execute(ctx);
        } else {
            missCount.incrementAndGet();
            return elseAction.execute(ctx);
        }
    }

    public String getId() { return id; }
    public int getPriority() { return priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getHitCount() { return hitCount.get(); }
    public int getMissCount() { return missCount.get(); }
    public long getLastHitTime() { return lastHitTime; }

    @Override
    public int compareTo(HookRule other) {
        return Integer.compare(other.priority, this.priority); // higher priority first
    }
}

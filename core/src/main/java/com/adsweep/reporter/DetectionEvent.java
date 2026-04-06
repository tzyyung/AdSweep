package com.adsweep.reporter;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents a suspicious behavior detected at runtime by Layer 3.
 */
public class DetectionEvent {

    public enum Verdict { UNDECIDED, AD, NOT_AD }

    public final String callerClassName;
    public final String callerMethodName;
    public final String hookType;       // addView, startActivity, loadUrl, setVisibility
    public final String description;
    public final long timestamp;
    public Verdict verdict = Verdict.UNDECIDED;

    public DetectionEvent(String callerClassName, String callerMethodName,
                          String hookType, String description) {
        this.callerClassName = callerClassName;
        this.callerMethodName = callerMethodName;
        this.hookType = hookType;
        this.description = description;
        this.timestamp = System.currentTimeMillis();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("callerClass", callerClassName);
        json.put("callerMethod", callerMethodName);
        json.put("hookType", hookType);
        json.put("description", description);
        json.put("timestamp", timestamp);
        json.put("verdict", verdict.name());
        return json;
    }

    public String getShortDescription() {
        String shortClass = callerClassName;
        int lastDot = shortClass.lastIndexOf('.');
        if (lastDot >= 0) shortClass = shortClass.substring(lastDot + 1);
        return shortClass + "." + callerMethodName;
    }
}

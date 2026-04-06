package com.adsweep.rules;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Represents a single hook rule targeting a specific class method.
 */
public class Rule {

    public String id;
    public String className;
    public String methodName;
    public String[] paramTypes;
    public String action;       // BLOCK_RETURN_VOID, BLOCK_RETURN_NULL, MONITOR_ONLY
    public boolean enabled;
    public String source;       // BUILTIN, LAYER2_SCAN, LAYER3_USER, MANUAL
    public String sdkName;
    public String notes;

    public Rule() {
        this.enabled = true;
        this.action = "BLOCK_RETURN_VOID";
        this.source = "BUILTIN";
    }

    public static Rule fromJson(JSONObject json) throws JSONException {
        Rule rule = new Rule();
        rule.id = json.getString("id");
        rule.className = json.getString("className");
        rule.methodName = json.getString("methodName");
        rule.action = json.optString("action", "BLOCK_RETURN_VOID");
        rule.enabled = json.optBoolean("enabled", true);
        rule.source = json.optString("source", "BUILTIN");
        rule.sdkName = json.optString("sdkName", "");
        rule.notes = json.optString("notes", "");

        if (json.has("paramTypes")) {
            JSONArray arr = json.getJSONArray("paramTypes");
            rule.paramTypes = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                rule.paramTypes[i] = arr.getString(i);
            }
        }

        return rule;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("className", className);
        json.put("methodName", methodName);
        json.put("action", action);
        json.put("enabled", enabled);
        json.put("source", source);
        if (sdkName != null && !sdkName.isEmpty()) json.put("sdkName", sdkName);
        if (notes != null && !notes.isEmpty()) json.put("notes", notes);
        if (paramTypes != null) {
            JSONArray arr = new JSONArray();
            for (String pt : paramTypes) arr.put(pt);
            json.put("paramTypes", arr);
        }
        return json;
    }

    @Override
    public String toString() {
        return "[" + id + "] " + className + "." + methodName + " (" + action + ")";
    }
}

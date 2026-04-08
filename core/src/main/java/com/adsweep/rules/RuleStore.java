package com.adsweep.rules;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages loading, merging, and persisting hook rules.
 *
 * Rule sources:
 * 1. Built-in common rules (from assets/adsweep_rules_common.json)
 * 2. Per-app rules (from files/adsweep/rules_app.json)
 * 3. Whitelist (from files/adsweep/whitelist.json)
 *
 * Effective rules = (common UNION app) MINUS whitelist
 */
public class RuleStore {

    private static final String TAG = "AdSweep.RuleStore";
    private static final String COMMON_RULES_ASSET = "adsweep_rules_common.json";
    private static final String APP_RULES_ASSET = "adsweep_rules_app.json";
    private static final String APP_RULES_FILE = "adsweep/rules_app.json";
    private static final String WHITELIST_FILE = "adsweep/whitelist.json";

    private final Context context;
    private List<Rule> commonRules;
    private List<Rule> appRules;
    private Set<String> whitelist;

    public RuleStore(Context context) {
        this.context = context;
        ensureDirectoryExists();
        loadAll();
    }

    /**
     * Get all active (enabled, not whitelisted) rules.
     */
    public List<Rule> getActiveRules() {
        List<Rule> active = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // App rules take priority (can override common rules)
        for (Rule rule : appRules) {
            String key = rule.className + "." + rule.methodName;
            if (rule.enabled && !whitelist.contains(key)) {
                active.add(rule);
                seen.add(key);
            }
        }

        // Then add common rules not overridden by app rules
        for (Rule rule : commonRules) {
            String key = rule.className + "." + rule.methodName;
            if (rule.enabled && !seen.contains(key) && !whitelist.contains(key)) {
                active.add(rule);
            }
        }

        return active;
    }

    public List<Rule> getAllRules() {
        List<Rule> all = new ArrayList<>();
        all.addAll(commonRules);
        all.addAll(appRules);
        return all;
    }

    /**
     * Get WebView-specific rules (className == "WEBVIEW").
     * These are handled by UserScriptEngine, not HookManager.
     */
    public List<Rule> getWebViewRules() {
        List<Rule> result = new ArrayList<>();
        for (Rule r : getAllRules()) {
            if (r.enabled && "WEBVIEW".equals(r.className)) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * Export all rules as JSON string.
     */
    public String exportRulesJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        JSONArray arr = new JSONArray();
        for (Rule rule : getAllRules()) {
            arr.put(rule.toJson());
        }
        root.put("rules", arr);
        return root.toString(2);
    }

    /**
     * Add a new app-specific rule (e.g., from Layer 3 user feedback).
     */
    public void addAppRule(Rule rule) {
        appRules.add(rule);
        saveAppRules();
    }

    /**
     * Add a class+method to the whitelist.
     */
    public void addToWhitelist(String className, String methodName) {
        whitelist.add(className + "." + methodName);
        saveWhitelist();
    }

    public void removeFromWhitelist(String className, String methodName) {
        whitelist.remove(className + "." + methodName);
        saveWhitelist();
    }

    public boolean isWhitelisted(String className, String methodName) {
        return whitelist.contains(className + "." + methodName);
    }

    // --- Loading ---

    private void loadAll() {
        commonRules = loadRulesFromAsset(COMMON_RULES_ASSET);
        // App rules: load from file first, fallback to bundled asset
        appRules = loadRulesFromFile(APP_RULES_FILE);
        if (appRules.isEmpty()) {
            appRules = loadRulesFromAsset(APP_RULES_ASSET);
        }
        whitelist = loadWhitelist();
        Log.i(TAG, "Loaded " + commonRules.size() + " common rules, "
                + appRules.size() + " app rules, "
                + whitelist.size() + " whitelist entries");
    }

    private List<Rule> loadRulesFromAsset(String assetName) {
        List<Rule> rules = new ArrayList<>();
        try {
            InputStream is = context.getAssets().open(assetName);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.getJSONArray("rules");
            for (int i = 0; i < arr.length(); i++) {
                rules.add(Rule.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load asset rules: " + assetName, e);
        }
        return rules;
    }

    private List<Rule> loadRulesFromFile(String fileName) {
        List<Rule> rules = new ArrayList<>();
        File file = new File(context.getFilesDir(), fileName);
        if (!file.exists()) return rules;

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.getJSONArray("rules");
            for (int i = 0; i < arr.length(); i++) {
                rules.add(Rule.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load file rules: " + fileName, e);
        }
        return rules;
    }

    private Set<String> loadWhitelist() {
        Set<String> wl = new HashSet<>();
        File file = new File(context.getFilesDir(), WHITELIST_FILE);
        if (!file.exists()) return wl;

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.getJSONObject(i);
                wl.add(entry.getString("className") + "." + entry.getString("methodName"));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load whitelist", e);
        }
        return wl;
    }

    // --- Saving ---

    private void saveAppRules() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray arr = new JSONArray();
            for (Rule rule : appRules) {
                arr.put(rule.toJson());
            }
            root.put("rules", arr);
            writeFile(APP_RULES_FILE, root.toString(2));
        } catch (Exception e) {
            Log.e(TAG, "Failed to save app rules", e);
        }
    }

    private void saveWhitelist() {
        try {
            JSONArray arr = new JSONArray();
            for (String entry : whitelist) {
                String[] parts = entry.split("\\.", -1);
                // className might contain dots, methodName is the last part
                int lastDot = entry.lastIndexOf('.');
                JSONObject obj = new JSONObject();
                obj.put("className", entry.substring(0, lastDot));
                obj.put("methodName", entry.substring(lastDot + 1));
                arr.put(obj);
            }
            writeFile(WHITELIST_FILE, arr.toString(2));
        } catch (Exception e) {
            Log.e(TAG, "Failed to save whitelist", e);
        }
    }

    private void writeFile(String fileName, String content) throws Exception {
        File file = new File(context.getFilesDir(), fileName);
        FileWriter writer = new FileWriter(file);
        writer.write(content);
        writer.close();
    }

    private void ensureDirectoryExists() {
        File dir = new File(context.getFilesDir(), "adsweep");
        if (!dir.exists()) dir.mkdirs();
    }
}

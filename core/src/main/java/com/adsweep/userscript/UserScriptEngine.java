package com.adsweep.userscript;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.adsweep.rules.Rule;
import com.adsweep.rules.RuleStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Greasemonkey-compatible userscript engine for Android WebView.
 *
 * Loads .user.js files from assets and app-specific rules,
 * then builds injection JavaScript for matching URLs at the correct timing.
 *
 * Reference: Android-WebMonkey script injection architecture.
 */
public class UserScriptEngine {

    private static final String TAG = "AdSweep.UserScript";

    private final List<UserScript> scripts = new ArrayList<>();

    /**
     * GM_* runtime JavaScript — defines GM_addStyle, GM_log, GM_info.
     * Injected once per page before any userscript.
     * Uses a guard flag to prevent double-injection on same page.
     */
    private static final String GM_RUNTIME =
            "if(!window.__adsweep_gm__){window.__adsweep_gm__=true;" +
            // GM_addStyle: inject CSS into page
            "window.GM_addStyle=function(css){" +
                "var s=document.createElement('style');" +
                "s.textContent=css;" +
                "(document.head||document.documentElement).appendChild(s);" +
                "return s" +
            "};" +
            // GM_log: output to console (visible in logcat via chrome://inspect)
            "window.GM_log=function(m){console.log('[AdSweep] '+m)};" +
            // GM_info: script handler info
            "window.GM_info={scriptHandler:'AdSweep',version:'1.0'};" +
            // GM.* promise-based API (Greasemonkey 4+ compat)
            "window.GM=window.GM||{};" +
            "GM.addStyle=GM_addStyle;" +
            "GM.log=GM_log;" +
            "GM.info=GM_info;" +
            "}";

    public UserScriptEngine(Context context, RuleStore ruleStore) {
        loadBuiltinScripts(context);
        loadRuleScripts(context, ruleStore);
        Log.i(TAG, "Loaded " + scripts.size() + " userscripts");
        for (UserScript s : scripts) {
            Log.d(TAG, "  " + s);
        }
    }

    /**
     * Load built-in userscripts from assets/userscripts/ directory.
     */
    private void loadBuiltinScripts(Context context) {
        try {
            AssetManager am = context.getAssets();
            String[] files = am.list("userscripts");
            if (files == null) return;
            for (String file : files) {
                if (file.endsWith(".user.js")) {
                    String content = readAsset(am, "userscripts/" + file);
                    if (content != null) {
                        UserScript script = UserScript.parse(content);
                        if (script != null) {
                            scripts.add(script);
                        } else {
                            Log.w(TAG, "Failed to parse: userscripts/" + file);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load built-in userscripts", e);
        }
    }

    /**
     * Load app-specific userscripts referenced by WEBVIEW rules.
     * Rules with action=USERSCRIPT and returnValue=filename.user.js
     */
    private void loadRuleScripts(Context context, RuleStore ruleStore) {
        for (Rule rule : ruleStore.getWebViewRules()) {
            if (!"USERSCRIPT".equals(rule.action)) continue;
            if (rule.returnValue == null || rule.returnValue.isEmpty()) continue;

            String content = loadScriptFile(context, rule.returnValue);
            if (content != null) {
                UserScript script = UserScript.parse(content);
                if (script != null) {
                    scripts.add(script);
                } else {
                    Log.w(TAG, "Failed to parse script: " + rule.returnValue);
                }
            } else {
                Log.w(TAG, "Script file not found: " + rule.returnValue);
            }
        }
    }

    /**
     * Try to load a script file from multiple locations:
     * 1. assets/ (for files bundled by injector)
     * 2. app files dir (for dynamically downloaded scripts)
     */
    private String loadScriptFile(Context context, String filename) {
        // Try assets first
        try {
            String content = readAsset(context.getAssets(), filename);
            if (content != null) return content;
        } catch (Exception ignored) {}

        // Try files dir
        try {
            File f = new File(context.getFilesDir(), "adsweep/" + filename);
            if (f.exists()) {
                return readStream(new FileInputStream(f));
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Build the injection JavaScript for a given URL and timing.
     * Returns empty string if no scripts match.
     *
     * @param url     Current WebView page URL
     * @param timing  "document-start", "document-end", or "document-idle"
     * @return JavaScript string to inject via evaluateJavascript()
     */
    public String buildInjection(String url, String timing) {
        List<UserScript> matching = new ArrayList<>();
        for (UserScript script : scripts) {
            if (script.matchesUrl(url) && timing.equals(script.getRunAt())) {
                matching.add(script);
            }
        }

        if (matching.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        // 1. GM_* runtime (idempotent — guard prevents double-define)
        sb.append(GM_RUNTIME);

        // 2. Each script wrapped in IIFE + try-catch for isolation
        for (UserScript script : matching) {
            sb.append("try{(function(){");
            sb.append(script.scriptBody);
            sb.append("})()}catch(e){console.error('[AdSweep] Error in script: ")
              .append(escapeJsString(script.name))
              .append("',e)}");
        }

        return sb.toString();
    }

    /**
     * Check if there are any scripts that could match any URL.
     * Used to decide whether to install WebView hooks at all.
     */
    public boolean hasScripts() {
        return !scripts.isEmpty();
    }

    public int getScriptCount() {
        return scripts.size();
    }

    // --- Helpers ---

    private static String readAsset(AssetManager am, String path) {
        try {
            return readStream(am.open(path));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readStream(InputStream is) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }
}

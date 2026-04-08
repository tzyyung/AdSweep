package com.adsweep.rules;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

/**
 * Background rule updater. Downloads latest rules, userscripts, and domain lists
 * from the adsweep-rules GitHub repository.
 *
 * Design:
 * - Runs in background thread, never blocks app startup
 * - Checks at most once per UPDATE_INTERVAL (24h default)
 * - Only downloads files when remote version > local version
 * - Downloaded files are saved to files/adsweep/ (app-writable directory)
 * - RuleStore and UserScriptEngine load from this directory on next launch
 * - All failures are silent — app continues with bundled/cached rules
 */
public class RuleUpdater {

    private static final String TAG = "AdSweep.Updater";
    private static final String BASE_URL =
            "https://raw.githubusercontent.com/tzyyung/adsweep-rules/main";
    private static final String INDEX_URL = BASE_URL + "/index.json";
    private static final int TIMEOUT_MS = 10_000;
    private static final long UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours

    private final Context context;
    private final File updateDir;

    public RuleUpdater(Context context) {
        this.context = context;
        this.updateDir = new File(context.getFilesDir(), "adsweep");
        updateDir.mkdirs();
    }

    /**
     * Check for updates in background. Non-blocking, safe to call from init().
     */
    public void checkInBackground() {
        new Thread(() -> {
            try {
                doCheck();
            } catch (Exception e) {
                Log.w(TAG, "Update check failed (non-critical)", e);
            }
        }, "AdSweep-Updater").start();
    }

    private void doCheck() {
        // Throttle: only check once per interval
        if (!shouldCheck()) {
            Log.d(TAG, "Skipping update check (checked recently)");
            return;
        }

        // Download index.json
        String indexStr = download(INDEX_URL);
        if (indexStr == null) {
            Log.d(TAG, "Could not reach rule server");
            return;
        }

        try {
            JSONObject index = new JSONObject(indexStr);
            int remoteVersion = index.optInt("version", 0);
            int localVersion = readVersionFile("rule_version");

            if (remoteVersion <= localVersion) {
                Log.d(TAG, "Rules up to date (v" + localVersion + ")");
                touchFile("last_check");
                return;
            }

            Log.i(TAG, "Updating rules: v" + localVersion + " → v" + remoteVersion);
            int downloaded = 0;

            // 1. App-specific rules
            String pkg = context.getPackageName();
            JSONObject apps = index.optJSONObject("apps");
            if (apps != null && apps.has(pkg)) {
                String rulesUrl = apps.getJSONObject(pkg).getString("rulesUrl");
                if (downloadToFile(BASE_URL + "/" + rulesUrl, "rules_app.json")) {
                    downloaded++;
                    Log.i(TAG, "Downloaded app rules for " + pkg);
                }
            }

            // 2. Common rules
            JSONObject common = index.optJSONObject("common");
            if (common != null && common.has("rulesUrl")) {
                if (downloadToFile(BASE_URL + "/" + common.getString("rulesUrl"), "rules_common.json")) {
                    downloaded++;
                    Log.i(TAG, "Downloaded common rules");
                }
            }

            // 3. Userscripts
            JSONObject scripts = index.optJSONObject("userscripts");
            if (scripts != null) {
                File scriptDir = new File(updateDir, "userscripts");
                scriptDir.mkdirs();
                Iterator<String> keys = scripts.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject s = scripts.getJSONObject(key);
                    String url = s.getString("url");
                    // Use the filename from URL, not the key
                    String filename = url.contains("/") ? url.substring(url.lastIndexOf('/') + 1) : url;
                    if (downloadToFile(BASE_URL + "/" + url, "userscripts/" + filename)) {
                        downloaded++;
                        Log.i(TAG, "Downloaded userscript: " + filename);
                    }
                }
            }

            // 4. Domain list (large file, only when version changes)
            JSONObject domains = index.optJSONObject("domains");
            if (domains != null && domains.has("url")) {
                int domainVer = domains.optInt("version", 0);
                int localDomainVer = readVersionFile("domain_version");
                if (domainVer > localDomainVer) {
                    if (downloadToFile(BASE_URL + "/" + domains.getString("url"), "domains.txt")) {
                        writeVersionFile("domain_version", domainVer);
                        downloaded++;
                        Log.i(TAG, "Downloaded domain list (v" + domainVer + ")");
                    }
                }
            }

            // Save version and timestamp
            writeVersionFile("rule_version", remoteVersion);
            touchFile("last_check");
            Log.i(TAG, "Update complete: v" + remoteVersion + " (" + downloaded + " files)");

        } catch (Exception e) {
            Log.w(TAG, "Failed to parse index: " + e.getMessage());
        }
    }

    // --- Throttle ---

    private boolean shouldCheck() {
        File marker = new File(updateDir, "last_check");
        if (!marker.exists()) return true;
        return System.currentTimeMillis() - marker.lastModified() > UPDATE_INTERVAL_MS;
    }

    private void touchFile(String name) {
        try {
            File f = new File(updateDir, name);
            if (!f.exists()) f.createNewFile();
            f.setLastModified(System.currentTimeMillis());
        } catch (Exception ignored) {}
    }

    // --- Version tracking ---

    private int readVersionFile(String name) {
        File f = new File(updateDir, name);
        if (!f.exists()) return 0;
        try {
            byte[] data = readFileBytes(f);
            return Integer.parseInt(new String(data, "UTF-8").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private void writeVersionFile(String name, int version) {
        try {
            File f = new File(updateDir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(String.valueOf(version).getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    // --- HTTP download ---

    private String download(String urlStr) {
        byte[] data = downloadBytes(urlStr);
        if (data == null) return null;
        try {
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private boolean downloadToFile(String urlStr, String relativePath) {
        byte[] data = downloadBytes(urlStr);
        if (data == null) return false;
        try {
            File target = new File(updateDir, relativePath);
            target.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(target);
            fos.write(data);
            fos.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Failed to save " + relativePath + ": " + e.getMessage());
            return false;
        }
    }

    private byte[] downloadBytes(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "AdSweep/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.d(TAG, "HTTP " + code + " for " + urlStr);
                return null;
            }

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
            is.close();
            return bos.toByteArray();

        } catch (Exception e) {
            Log.d(TAG, "Download failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private byte[] readFileBytes(File f) throws Exception {
        InputStream is = new java.io.FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        is.close();
        return bos.toByteArray();
    }
}

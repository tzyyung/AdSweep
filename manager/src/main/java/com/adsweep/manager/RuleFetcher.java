package com.adsweep.manager;

import android.util.Log;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches app-specific rules from the adsweep-rules GitHub repository.
 * Silent on failure — returns null if network is unavailable or rules don't exist.
 */
public class RuleFetcher {

    private static final String TAG = "AdSweep.Rules";
    private static final String BASE_URL =
            "https://raw.githubusercontent.com/tzyyung/adsweep-rules/main";
    private static final int TIMEOUT_MS = 10_000;

    /**
     * Fetch app-specific rules for the given package name.
     * Returns a temp File containing the rules JSON, or null if not available.
     */
    public static File fetch(String packageName, File cacheDir) {
        try {
            // Step 1: fetch index.json to check if rules exist for this package
            String indexJson = download(BASE_URL + "/index.json");
            if (indexJson == null) return null;

            JSONObject index = new JSONObject(indexJson);
            JSONObject apps = index.optJSONObject("apps");
            if (apps == null || !apps.has(packageName)) {
                Log.i(TAG, "No rules found for: " + packageName);
                return null;
            }

            JSONObject appInfo = apps.getJSONObject(packageName);
            String rulesPath = appInfo.getString("rulesUrl");
            Log.i(TAG, "Found rules for " + packageName + ": " + rulesPath);

            // Step 2: fetch the rules JSON
            String rulesJson = download(BASE_URL + "/" + rulesPath);
            if (rulesJson == null) return null;

            // Step 3: write to temp file
            File rulesFile = new File(cacheDir, "rules_" + packageName + ".json");
            try (FileOutputStream fos = new FileOutputStream(rulesFile)) {
                fos.write(rulesJson.getBytes("UTF-8"));
            }

            Log.i(TAG, "Downloaded rules: " + rulesFile.length() + " bytes");
            return rulesFile;

        } catch (Exception e) {
            Log.w(TAG, "Rule fetch failed (continuing without app rules): " + e.getMessage());
            return null;
        }
    }

    private static String download(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "AdSweep-Manager/1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.d(TAG, "HTTP " + code + " for " + urlStr);
                return null;
            }

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
            is.close();
            return bos.toString("UTF-8");

        } catch (Exception e) {
            Log.d(TAG, "Download failed: " + urlStr + " - " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

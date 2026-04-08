package com.adsweep.engine;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Domain matching utility with parent-domain support.
 * Loads domain lists from assets and matches URLs against them.
 */
public class DomainMatcher {

    private static final String TAG = "AdSweep.Domain";
    private final Set<String> domains;

    public DomainMatcher(Set<String> domains) {
        this.domains = domains;
    }

    /** Load domain list: try downloaded file first, fallback to bundled asset. */
    public static DomainMatcher fromAsset(Context context, String assetName) {
        // Try downloaded version first (from RuleUpdater)
        File downloaded = new File(context.getFilesDir(), "adsweep/domains.txt");
        if (downloaded.exists()) {
            try {
                Set<String> domains = loadFromStream(new FileInputStream(downloaded));
                Log.i(TAG, "Loaded " + domains.size() + " domains from downloaded file");
                return new DomainMatcher(domains);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load downloaded domains, falling back to asset", e);
            }
        }

        // Fallback to bundled asset
        Set<String> domains = new HashSet<>();
        try {
            domains = loadFromStream(context.getAssets().open(assetName));
            Log.i(TAG, "Loaded " + domains.size() + " domains from " + assetName);
        } catch (Exception e) {
            Log.w(TAG, "Failed to load domain list: " + assetName, e);
        }
        return new DomainMatcher(domains);
    }

    private static Set<String> loadFromStream(InputStream is) throws Exception {
        Set<String> domains = new HashSet<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                domains.add(line.toLowerCase());
            }
        }
        reader.close();
        return domains;
    }

    /** Check if a URL matches any domain in the list (with subdomain support). */
    public boolean matches(String url) {
        if (url == null) return false;
        String domain = extractDomain(url);
        if (domain == null) return false;
        return matchesWithParents(domain);
    }

    /** Check if a domain matches (including parent domain matching). */
    public boolean matchesWithParents(String domain) {
        domain = domain.toLowerCase();
        // Try exact match and all parent domains
        // e.g., ad.doubleclick.net → doubleclick.net → net
        while (domain.contains(".")) {
            if (domains.contains(domain)) return true;
            domain = domain.substring(domain.indexOf('.') + 1);
        }
        return domains.contains(domain);
    }

    /** Extract domain from a URL string. */
    public static String extractDomain(String url) {
        try {
            String s = url.toLowerCase();
            // Remove scheme
            int schemeEnd = s.indexOf("://");
            if (schemeEnd >= 0) s = s.substring(schemeEnd + 3);
            // Remove path
            int pathStart = s.indexOf('/');
            if (pathStart >= 0) s = s.substring(0, pathStart);
            // Remove port
            int portStart = s.indexOf(':');
            if (portStart >= 0) s = s.substring(0, portStart);
            // Remove userinfo
            int atSign = s.indexOf('@');
            if (atSign >= 0) s = s.substring(atSign + 1);
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    public int size() {
        return domains.size();
    }

    public boolean isEmpty() {
        return domains.isEmpty();
    }
}

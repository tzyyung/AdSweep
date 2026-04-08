package com.adsweep.userscript;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Greasemonkey-compatible userscript parser.
 * Parses // ==UserScript== metadata blocks and supports @match/@exclude/@run-at.
 *
 * Reference: Android-WebMonkey (warren-bank/Android-WebMonkey)
 */
public class UserScript {

    private static final Pattern META_LINE = Pattern.compile("//\\s*@(\\S+)(?:\\s+(.*))?");
    private static final String META_START = "// ==UserScript==";
    private static final String META_END = "// ==/UserScript==";

    public String name;
    public List<String> matchPatterns = new ArrayList<>();
    public List<String> excludePatterns = new ArrayList<>();
    public String runAt = "document-start";
    public String version;
    public String scriptBody;

    /**
     * Parse a .user.js file content into a UserScript object.
     * Returns null if the metadata block is missing or invalid.
     */
    public static UserScript parse(String content) {
        if (content == null || content.isEmpty()) return null;

        // Find metadata block
        int metaStart = content.indexOf(META_START);
        int metaEnd = content.indexOf(META_END);
        if (metaStart < 0 || metaEnd < 0 || metaEnd <= metaStart) return null;

        UserScript script = new UserScript();

        // Parse metadata lines
        String metaBlock = content.substring(metaStart + META_START.length(), metaEnd);
        Scanner scanner = new Scanner(metaBlock);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            Matcher m = META_LINE.matcher(line);
            if (m.matches()) {
                String key = m.group(1);
                String value = m.group(2);
                if (value != null) value = value.trim();

                switch (key) {
                    case "name":
                        script.name = value;
                        break;
                    case "match":
                        if (value != null && !value.isEmpty()) {
                            script.matchPatterns.add(value);
                        }
                        break;
                    case "exclude":
                        if (value != null && !value.isEmpty()) {
                            script.excludePatterns.add(value);
                        }
                        break;
                    case "run-at":
                        if (value != null) {
                            // Normalize: document-body → document-end (WebMonkey compat)
                            if ("document-body".equals(value)) value = "document-end";
                            script.runAt = value;
                        }
                        break;
                    case "version":
                        script.version = value;
                        break;
                }
            }
        }
        scanner.close();

        if (script.name == null || script.name.isEmpty()) {
            script.name = "unnamed";
        }

        // Script body is everything after the metadata block end
        int bodyStart = metaEnd + META_END.length();
        // Skip past the newline after META_END
        if (bodyStart < content.length() && content.charAt(bodyStart) == '\r') bodyStart++;
        if (bodyStart < content.length() && content.charAt(bodyStart) == '\n') bodyStart++;
        script.scriptBody = content.substring(bodyStart);

        return script;
    }

    /**
     * Check if a URL matches this script's @match patterns and is not @excluded.
     * Following Greasemonkey convention: @exclude takes priority over @match.
     */
    public boolean matchesUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();

        // @exclude takes priority
        for (String pattern : excludePatterns) {
            if (matchGlob(pattern.toLowerCase(), lower)) return false;
        }

        // If no @match patterns, match everything
        if (matchPatterns.isEmpty()) return true;

        // Match any @match pattern
        for (String pattern : matchPatterns) {
            if (matchGlob(pattern.toLowerCase(), lower)) return true;
        }

        return false;
    }

    /**
     * Glob pattern matching supporting * (any sequence) and ? (any single char).
     * Reference: WebMonkey CriterionMatcher.testGlob()
     */
    static boolean matchGlob(String pattern, String text) {
        return matchGlobRecursive(pattern, 0, text, 0);
    }

    private static boolean matchGlobRecursive(String pattern, int pi, String text, int ti) {
        while (pi < pattern.length()) {
            char pc = pattern.charAt(pi);
            if (pc == '*') {
                // Skip consecutive *
                while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
                // * at end matches everything
                if (pi == pattern.length()) return true;
                // Try matching rest of pattern from each position in text
                for (int i = ti; i <= text.length(); i++) {
                    if (matchGlobRecursive(pattern, pi, text, i)) return true;
                }
                return false;
            } else if (pc == '?') {
                if (ti >= text.length()) return false;
                pi++;
                ti++;
            } else {
                if (ti >= text.length() || pc != text.charAt(ti)) return false;
                pi++;
                ti++;
            }
        }
        return ti == text.length();
    }

    public String getRunAt() {
        return runAt != null ? runAt : "document-start";
    }

    @Override
    public String toString() {
        return "[UserScript] " + name + " (@run-at " + getRunAt()
                + ", match=" + matchPatterns.size()
                + ", exclude=" + excludePatterns.size() + ")";
    }
}

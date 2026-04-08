package com.adsweep.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.adsweep.engine.DomainMatcher;
import com.adsweep.reporter.DetectionEvent;
import com.adsweep.reporter.FloatingReporter;
import com.adsweep.userscript.UserScriptEngine;

import java.io.ByteArrayInputStream;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Layer 3: Runtime behavior detection + Greasemonkey-compatible userscript injection.
 *
 * Hooks:
 * - WebView.loadUrl: detect and block ad URLs
 * - WebViewClient.onPageStarted: inject document-start userscripts (CSS anti-flicker)
 * - WebViewClient.onPageFinished: inject document-end/idle userscripts (DOM manipulation)
 * - Ad callback interfaces: detect when ads are loaded/shown
 */
public class LayerThreeMonitor {

    private static final String TAG = "AdSweep.L3";

    // Known ad domain patterns for URL blocking
    private static final Pattern AD_URL_PATTERN = Pattern.compile(
            "(?i)(doubleclick|googlesyndication|googleadservices|" +
            "facebook\\.com/tr|fbcdn.*ad|" +
            "applovin|mopub|unity3d\\.com/ads|" +
            "ironsrc|vungle|adcolony|chartboost|inmobi|" +
            "admob|adsense|adnxs|criteo|pubmatic|" +
            "smaato|tapjoy|fyber|digitalturbine)"
    );

    // Known ad callback class patterns to probe at runtime
    private static final String[][] AD_CALLBACK_CLASSES = {
            {"com.google.android.gms.ads.AdListener", "onAdLoaded", "AdMob ad loaded"},
            {"com.google.android.gms.ads.AdListener", "onAdOpened", "AdMob ad opened"},
            {"com.facebook.ads.AdListener", "onAdLoaded", "Facebook ad loaded"},
            {"com.applovin.mediation.MaxAdListener", "onAdLoaded", "AppLovin ad loaded"},
            {"com.applovin.mediation.MaxAdListener", "onAdDisplayed", "AppLovin ad displayed"},
            {"com.ironsource.mediationsdk.logger.IronSourceError", "<init>", "IronSource error"},
    };

    private final Context context;
    private final UserScriptEngine engine;
    private final DomainMatcher domainMatcher;
    private final Set<String> reportedUrls = new HashSet<>();

    public LayerThreeMonitor(Context context, UserScriptEngine engine, DomainMatcher domainMatcher) {
        this.context = context;
        this.engine = engine;
        this.domainMatcher = domainMatcher;
    }

    /**
     * Install lightweight runtime monitors.
     */
    public void installMonitors() {
        Log.i(TAG, "Installing Layer 3 monitors...");
        int installed = 0;

        // Monitor 1: WebView.loadUrl — detect and block ad URLs
        installed += hookWebViewLoadUrl() ? 1 : 0;

        // Monitor 2: WebViewClient.onPageStarted — document-start userscript injection
        installed += hookWebViewOnPageStarted() ? 1 : 0;

        // Monitor 3: WebViewClient.onPageFinished — document-end/idle userscript injection
        installed += hookWebViewOnPageFinished() ? 1 : 0;

        // Monitor 4: WebViewClient.shouldInterceptRequest — block ad resources at network level
        installed += hookShouldInterceptRequest() ? 1 : 0;

        // Monitor 5: Ad callback methods — detect when ads are loaded
        installed += hookAdCallbacks();

        Log.i(TAG, "Layer 3: " + installed + " monitors installed"
                + " (" + engine.getScriptCount() + " userscripts loaded)");
    }

    // --- WebView.loadUrl (block ad URLs) ---

    private boolean hookWebViewLoadUrl() {
        try {
            Method target = WebView.class.getMethod("loadUrl", String.class);
            WebViewCallback callback = new WebViewCallback(this);
            Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
            Method backup = HookEngine.hook(target, callback, callbackMethod);
            if (backup != null) {
                callback.setBackupMethod(backup);
                callback.setTargetMethod(target);
                Log.i(TAG, "Hooked: WebView.loadUrl");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to hook WebView.loadUrl", e);
        }
        return false;
    }

    static class WebViewCallback extends HookCallback {
        private final LayerThreeMonitor monitor;

        WebViewCallback(LayerThreeMonitor monitor) {
            this.monitor = monitor;
        }

        @Override
        public Object handleHook(Object[] args) {
            try {
                if (args.length >= 2 && args[1] instanceof String) {
                    String url = (String) args[1];
                    if (AD_URL_PATTERN.matcher(url).find()) {
                        String shortUrl = url.length() > 60 ? url.substring(0, 60) + "..." : url;
                        Log.i(TAG, "Blocked ad URL: " + shortUrl);

                        String domain = extractDomain(url);
                        if (domain != null && !monitor.reportedUrls.contains(domain)) {
                            monitor.reportedUrls.add(domain);
                            monitor.reportDetection("WebView", "loadUrl",
                                    "webview_ad_url", "Ad URL: " + shortUrl);
                        }
                        return null;
                    }
                }
                return callOriginal(args);
            } catch (Exception e) {
                try { return callOriginal(args); } catch (Exception ex) { return null; }
            }
        }

        private String extractDomain(String url) {
            try {
                int start = url.indexOf("//");
                if (start < 0) return null;
                start += 2;
                int end = url.indexOf("/", start);
                return end > start ? url.substring(start, end) : url.substring(start);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // --- WebViewClient.onPageStarted — document-start injection ---

    private boolean hookWebViewOnPageStarted() {
        try {
            Method target = WebViewClient.class.getMethod("onPageStarted",
                    WebView.class, String.class, Bitmap.class);
            PageStartedCallback callback = new PageStartedCallback(engine);
            Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
            Method backup = HookEngine.hook(target, callback, callbackMethod);
            if (backup != null) {
                callback.setBackupMethod(backup);
                callback.setTargetMethod(target);
                Log.i(TAG, "Hooked: WebViewClient.onPageStarted (document-start)");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to hook WebViewClient.onPageStarted", e);
        }
        return false;
    }

    static class PageStartedCallback extends HookCallback {
        private final UserScriptEngine engine;

        PageStartedCallback(UserScriptEngine engine) {
            this.engine = engine;
        }

        @Override
        public Object handleHook(Object[] args) {
            try {
                Object result = callOriginal(args);

                // args[0]=WebViewClient, args[1]=WebView, args[2]=url, args[3]=favicon
                if (args.length >= 3 && args[1] instanceof WebView && args[2] instanceof String) {
                    WebView wv = (WebView) args[1];
                    String url = (String) args[2];

                    if (!AD_URL_PATTERN.matcher(url).find()) {
                        String js = engine.buildInjection(url, "document-start");
                        if (!js.isEmpty()) {
                            try {
                                wv.evaluateJavascript(js, null);
                                Log.d(TAG, "Injected document-start scripts for: "
                                        + (url.length() > 50 ? url.substring(0, 50) + "..." : url));
                            } catch (Exception e) {
                                try { wv.loadUrl("javascript:void(" + js + ")"); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                try { return callOriginal(args); } catch (Exception ex) { return null; }
            }
        }
    }

    // --- WebViewClient.onPageFinished — document-end + document-idle injection ---

    private boolean hookWebViewOnPageFinished() {
        try {
            Method target = WebViewClient.class.getMethod("onPageFinished",
                    WebView.class, String.class);
            PageFinishedCallback callback = new PageFinishedCallback(engine);
            Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
            Method backup = HookEngine.hook(target, callback, callbackMethod);
            if (backup != null) {
                callback.setBackupMethod(backup);
                callback.setTargetMethod(target);
                Log.i(TAG, "Hooked: WebViewClient.onPageFinished (document-end/idle)");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to hook WebViewClient.onPageFinished", e);
        }
        return false;
    }

    static class PageFinishedCallback extends HookCallback {
        private final UserScriptEngine engine;

        PageFinishedCallback(UserScriptEngine engine) {
            this.engine = engine;
        }

        @Override
        public Object handleHook(Object[] args) {
            try {
                Object result = callOriginal(args);

                // args[0]=WebViewClient, args[1]=WebView, args[2]=url
                if (args.length >= 2 && args[1] instanceof WebView) {
                    WebView wv = (WebView) args[1];
                    String url = args.length >= 3 && args[2] instanceof String ? (String) args[2] : "";

                    if (!AD_URL_PATTERN.matcher(url).find()) {
                        // document-end scripts
                        String js = engine.buildInjection(url, "document-end");
                        if (!js.isEmpty()) {
                            try {
                                wv.evaluateJavascript(js, null);
                                Log.d(TAG, "Injected document-end scripts");
                            } catch (Exception e) {
                                try { wv.loadUrl("javascript:void(" + js + ")"); } catch (Exception ignored) {}
                            }
                        }

                        // document-idle scripts (delayed 100ms)
                        String idle = engine.buildInjection(url, "document-idle");
                        if (!idle.isEmpty()) {
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                try {
                                    wv.evaluateJavascript(idle, null);
                                    Log.d(TAG, "Injected document-idle scripts");
                                } catch (Exception ignored) {}
                            }, 100);
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                try { return callOriginal(args); } catch (Exception ex) { return null; }
            }
        }
    }

    // --- WebViewClient.shouldInterceptRequest — block ad sub-resources at network level ---

    private boolean hookShouldInterceptRequest() {
        if (domainMatcher == null || domainMatcher.isEmpty()) return false;
        try {
            Method target = WebViewClient.class.getMethod("shouldInterceptRequest",
                    WebView.class, WebResourceRequest.class);
            ShouldInterceptCallback callback = new ShouldInterceptCallback(domainMatcher);
            Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
            Method backup = HookEngine.hook(target, callback, callbackMethod);
            if (backup != null) {
                callback.setBackupMethod(backup);
                callback.setTargetMethod(target);
                Log.i(TAG, "Hooked: WebViewClient.shouldInterceptRequest (ad resource blocking)");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to hook shouldInterceptRequest", e);
        }
        return false;
    }

    static class ShouldInterceptCallback extends HookCallback {
        private final DomainMatcher domainMatcher;
        // Empty response to return for blocked requests
        private static final byte[] EMPTY = new byte[0];

        ShouldInterceptCallback(DomainMatcher domainMatcher) {
            this.domainMatcher = domainMatcher;
        }

        @Override
        public Object handleHook(Object[] args) {
            try {
                // args[0]=WebViewClient, args[1]=WebView, args[2]=WebResourceRequest
                if (args.length >= 3 && args[2] instanceof WebResourceRequest) {
                    WebResourceRequest request = (WebResourceRequest) args[2];
                    Uri uri = request.getUrl();
                    if (uri != null) {
                        String host = uri.getHost();
                        if (host != null && domainMatcher.matchesWithParents(host)) {
                            Log.d(TAG, "Blocked WebView resource: " + host + uri.getPath());
                            return new WebResourceResponse("text/plain", "utf-8",
                                    new ByteArrayInputStream(EMPTY));
                        }
                    }
                }
                return callOriginal(args);
            } catch (Exception e) {
                try { return callOriginal(args); } catch (Exception ex) { return null; }
            }
        }
    }

    // --- Ad callback class probing ---

    private int hookAdCallbacks() {
        ClassLoader cl = context.getClassLoader();
        int count = 0;

        for (String[] entry : AD_CALLBACK_CLASSES) {
            String className = entry[0];
            String methodName = entry[1];
            String description = entry[2];

            try {
                Class<?> clazz = cl.loadClass(className);
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)) {
                        AdCallbackDetector detector = new AdCallbackDetector(this, description);
                        Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
                        Method backup = HookEngine.hook(m, detector, callbackMethod);
                        if (backup != null) {
                            detector.setBackupMethod(backup);
                            detector.setTargetMethod(m);
                            Log.i(TAG, "Monitoring: " + className + "." + methodName);
                            count++;
                        }
                        break;
                    }
                }
            } catch (ClassNotFoundException e) {
                // SDK not present — skip
            } catch (Exception e) {
                Log.w(TAG, "Failed to monitor " + className + "." + methodName, e);
            }
        }
        return count;
    }

    static class AdCallbackDetector extends HookCallback {
        private final LayerThreeMonitor monitor;
        private final String description;

        AdCallbackDetector(LayerThreeMonitor monitor, String description) {
            this.monitor = monitor;
            this.description = description;
        }

        @Override
        public Object handleHook(Object[] args) {
            try {
                Object result = callOriginal(args);
                String callerClass = args.length > 0 && args[0] != null
                        ? args[0].getClass().getName() : "unknown";
                monitor.reportDetection(callerClass, description, "ad_callback", description);
                return result;
            } catch (Exception e) {
                try { return callOriginal(args); } catch (Exception ex) { return null; }
            }
        }
    }

    // --- Helpers ---

    private void reportDetection(String callerClass, String callerMethod,
                                  String hookType, String description) {
        FloatingReporter reporter = FloatingReporter.getInstance();
        if (reporter != null) {
            DetectionEvent event = new DetectionEvent(callerClass, callerMethod,
                    hookType, description);
            reporter.report(event);
        }
    }
}

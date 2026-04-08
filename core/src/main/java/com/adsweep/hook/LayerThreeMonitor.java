package com.adsweep.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.adsweep.reporter.DetectionEvent;
import com.adsweep.reporter.FloatingReporter;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Layer 3: Runtime behavior detection.
 *
 * Instead of hooking high-frequency system APIs (ViewGroup.addView etc.),
 * this monitors specific ad-related entry points:
 * - WebView.loadUrl: detect ad URLs and report them
 * - Ad callback interfaces: detect when ads are loaded/shown
 *
 * Only hooks low-frequency, ad-specific methods to avoid stability issues.
 */
public class LayerThreeMonitor {

    private static final String TAG = "AdSweep.L3";

    // Known ad domain patterns
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
            // {className, methodName, description}
            {"com.google.android.gms.ads.AdListener", "onAdLoaded", "AdMob ad loaded"},
            {"com.google.android.gms.ads.AdListener", "onAdOpened", "AdMob ad opened"},
            {"com.facebook.ads.AdListener", "onAdLoaded", "Facebook ad loaded"},
            {"com.applovin.mediation.MaxAdListener", "onAdLoaded", "AppLovin ad loaded"},
            {"com.applovin.mediation.MaxAdListener", "onAdDisplayed", "AppLovin ad displayed"},
            {"com.ironsource.mediationsdk.logger.IronSourceError", "<init>", "IronSource error"},
    };

    private final Context context;
    private final Set<String> reportedUrls = new HashSet<>();

    public LayerThreeMonitor(Context context) {
        this.context = context;
    }

    /**
     * Install lightweight runtime monitors.
     */
    public void installMonitors() {
        Log.i(TAG, "Installing Layer 3 monitors...");
        int installed = 0;

        // Monitor 1: WebView.loadUrl — detect ad URLs
        installed += hookWebViewLoadUrl() ? 1 : 0;

        // Monitor 2: WebViewClient.onPageFinished — inject ad-hiding CSS/JS
        installed += hookWebViewOnPageFinished() ? 1 : 0;

        // Monitor 3: Ad callback methods — detect when ads are loaded
        installed += hookAdCallbacks();

        Log.i(TAG, "Layer 3: " + installed + " monitors installed");
    }

    // --- WebView.loadUrl (low frequency, only when WebView loads a URL) ---

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
                        Log.i("AdSweep.L3", "Blocked ad URL: " + shortUrl);

                        // Report to user (only once per domain)
                        String domain = extractDomain(url);
                        if (domain != null && !monitor.reportedUrls.contains(domain)) {
                            monitor.reportedUrls.add(domain);
                            monitor.reportDetection("WebView", "loadUrl",
                                    "webview_ad_url", "Ad URL: " + shortUrl);
                        }

                        // Block the ad URL — don't call original
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

    // --- WebViewClient.onPageFinished — inject ad-hiding CSS/JS ---

    private boolean hookWebViewOnPageFinished() {
        try {
            Method target = WebViewClient.class.getMethod("onPageFinished",
                    WebView.class, String.class);
            PageFinishedCallback callback = new PageFinishedCallback();
            Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
            Method backup = HookEngine.hook(target, callback, callbackMethod);
            if (backup != null) {
                callback.setBackupMethod(backup);
                callback.setTargetMethod(target);
                Log.i(TAG, "Hooked: WebViewClient.onPageFinished (ad CSS injection)");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to hook WebViewClient.onPageFinished", e);
        }
        return false;
    }

    static class PageFinishedCallback extends HookCallback {
        // CSS to hide Google Ads containers + common ad patterns
        private static final String AD_BLOCK_JS =
                "(function(){" +
                "var css='" +
                // Google Ad Manager / DFP
                "[data-google-query-id]{display:none!important;height:0!important;overflow:hidden!important}" +
                "iframe[id*=\"google_ads\"]{display:none!important}" +
                "iframe[src*=\"doubleclick\"]{display:none!important}" +
                "iframe[src*=\"googlesyndication\"]{display:none!important}" +
                // Common ad container patterns
                "div[class*=\"ad-container\"]{display:none!important}" +
                "div[class*=\"ad-slot\"]{display:none!important}" +
                "div[class*=\"ad-wrapper\"]{display:none!important}" +
                "div[class*=\"adsbygoogle\"]{display:none!important}" +
                // AccuWeather specific - native ad + Remove Ads button
                "div[class*=\"native-ad\"]{display:none!important}" +
                "div[class*=\"remove-ads\"]{display:none!important}" +
                "a[href*=\"remove-ads\"]{display:none!important}" +
                "a[href*=\"subscription\"]{display:none!important}" +
                "';" +
                "var s=document.createElement('style');" +
                "s.textContent=css;" +
                "(document.head||document.documentElement).appendChild(s);" +
                // Also remove iframes matching ad patterns
                "document.querySelectorAll('iframe').forEach(function(f){" +
                "var src=f.src||'';" +
                "if(src.indexOf('doubleclick')>=0||src.indexOf('googlesyndication')>=0||" +
                "src.indexOf('googleads')>=0||src.indexOf('adservice')>=0){" +
                "f.remove();" +
                "}" +
                "});" +
                // MutationObserver for dynamically loaded ads
                "new MutationObserver(function(m){" +
                "m.forEach(function(r){" +
                "r.addedNodes.forEach(function(n){" +
                "if(n.nodeType===1){" +
                "if(n.getAttribute&&n.getAttribute('data-google-query-id')){n.remove();return;}" +
                "if(n.tagName==='IFRAME'&&(n.src||'').match(/doubleclick|googlesyndication|googleads/)){n.remove();}" +
                "}" +
                "});" +
                "});" +
                "}).observe(document.body||document.documentElement,{childList:true,subtree:true});" +
                "})()";

        @Override
        public Object handleHook(Object[] args) {
            try {
                // Call original onPageFinished first
                Object result = callOriginal(args);

                // args[0]=WebViewClient(this), args[1]=WebView, args[2]=url
                if (args.length >= 2 && args[1] instanceof WebView) {
                    WebView wv = (WebView) args[1];
                    String url = args.length >= 3 && args[2] instanceof String ? (String) args[2] : "";

                    // Only inject on non-ad pages (avoid injecting into ad iframes)
                    if (!AD_URL_PATTERN.matcher(url).find()) {
                        try {
                            wv.evaluateJavascript(AD_BLOCK_JS, null);
                            Log.d(TAG, "Injected ad-hiding CSS/JS into WebView");
                        } catch (Exception e) {
                            // Fallback for older APIs
                            try {
                                wv.loadUrl("javascript:void(" + AD_BLOCK_JS + ")");
                            } catch (Exception ignored) {}
                        }
                    }
                }

                return result;
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
                // Let the callback run normally, but report the detection
                Object result = callOriginal(args);

                String callerClass = args.length > 0 && args[0] != null
                        ? args[0].getClass().getName() : "unknown";
                monitor.reportDetection(callerClass, description,
                        "ad_callback", description);

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

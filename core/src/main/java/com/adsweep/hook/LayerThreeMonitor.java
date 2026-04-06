package com.adsweep.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import com.adsweep.reporter.DetectionEvent;
import com.adsweep.reporter.FloatingReporter;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Layer 3: Runtime behavior detection.
 * Hooks system APIs to detect suspicious ad-like behavior and reports
 * to FloatingReporter for user feedback.
 */
public class LayerThreeMonitor {

    private static final String TAG = "AdSweep.L3";

    // Known ad domain patterns for WebView URL detection
    private static final Pattern AD_URL_PATTERN = Pattern.compile(
            "(?i)(doubleclick|googlesyndication|googleadservices|" +
            "facebook\\.com/tr|fbcdn.*ad|" +
            "applovin|mopub|unity3d\\.com/ads|" +
            "ironsrc|vungle|adcolony|chartboost|inmobi|" +
            "admob|adsense|adnxs|criteo|pubmatic|" +
            "smaato|tapjoy|fyber|digitalturbine)"
    );

    // Classes that we've already seen — don't report duplicates
    private final Set<String> reportedClasses = new HashSet<>();

    private final Context context;
    private final String appPackage;
    private int screenHeight;

    public LayerThreeMonitor(Context context) {
        this.context = context;
        this.appPackage = context.getPackageName();
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        this.screenHeight = dm.heightPixels;
    }

    /**
     * Install runtime behavior monitors on system APIs.
     */
    public void installMonitors() {
        Log.i(TAG, "Installing Layer 3 monitors...");
        int installed = 0;

        installed += hookViewGroupAddView() ? 1 : 0;
        installed += hookWebViewLoadUrl() ? 1 : 0;
        installed += hookActivityStartActivity() ? 1 : 0;

        Log.i(TAG, "Layer 3: " + installed + " monitors installed");
    }

    // --- ViewGroup.addView ---

    private boolean hookViewGroupAddView() {
        try {
            Method target = ViewGroup.class.getMethod("addView", View.class);
            AddViewCallback callback = new AddViewCallback(this);
            Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
            Method backup = HookEngine.hook(target, callback, callbackMethod);
            if (backup != null) {
                callback.setBackupMethod(backup);
                Log.i(TAG, "Hooked: ViewGroup.addView");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to hook ViewGroup.addView", e);
        }
        return false;
    }

    static class AddViewCallback extends HookCallback {
        private final LayerThreeMonitor monitor;

        AddViewCallback(LayerThreeMonitor monitor) {
            this.monitor = monitor;
        }

        @Override
        public Object handleHook(Object[] args) {
            try {
                // Call original first
                Object result = callOriginal(args);

                // Then analyze: args[0] = this (ViewGroup), args[1] = child (View)
                if (args.length >= 2 && args[1] instanceof View) {
                    View child = (View) args[1];
                    monitor.analyzeAddedView(child);
                }
                return result;
            } catch (Exception e) {
                // If original call fails, don't block the app
                try { return callOriginal(args); } catch (Exception ex) { return null; }
            }
        }
    }

    private void analyzeAddedView(View child) {
        // Check if this looks like a fullscreen ad overlay
        child.post(() -> {
            try {
                int height = child.getHeight();
                if (height <= 0) height = child.getMeasuredHeight();

                // Only flag views that cover >70% of screen height
                if (height > screenHeight * 0.7) {
                    String callerClass = findAdCallerOnStack();
                    if (callerClass != null && !reportedClasses.contains(callerClass)) {
                        reportedClasses.add(callerClass);
                        reportDetection(callerClass, "addView",
                                "Fullscreen view added (" + height + "px)");
                    }
                }
            } catch (Exception e) {
                // Ignore analysis errors
            }
        });
    }

    // --- WebView.loadUrl ---

    private boolean hookWebViewLoadUrl() {
        try {
            Method target = WebView.class.getMethod("loadUrl", String.class);
            WebViewCallback callback = new WebViewCallback(this);
            Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
            Method backup = HookEngine.hook(target, callback, callbackMethod);
            if (backup != null) {
                callback.setBackupMethod(backup);
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
            // args[0] = this (WebView), args[1] = url (String)
            if (args.length >= 2 && args[1] instanceof String) {
                String url = (String) args[1];
                if (AD_URL_PATTERN.matcher(url).find()) {
                    String callerClass = monitor.findAdCallerOnStack();
                    if (callerClass != null) {
                        monitor.reportDetection(callerClass, "loadUrl",
                                "Ad URL: " + truncate(url, 60));
                    }
                    // Block the ad URL load
                    Log.i("AdSweep.L3", "Blocked ad URL: " + truncate(url, 80));
                    return null;
                }
            }

            try {
                return callOriginal(args);
            } catch (Exception e) {
                return null;
            }
        }

        private String truncate(String s, int max) {
            return s.length() > max ? s.substring(0, max) + "..." : s;
        }
    }

    // --- Activity.startActivity ---

    private boolean hookActivityStartActivity() {
        try {
            Method target = Activity.class.getMethod("startActivity", Intent.class);
            StartActivityCallback callback = new StartActivityCallback(this);
            Method callbackMethod = HookCallback.class.getMethod("handleHook", Object[].class);
            Method backup = HookEngine.hook(target, callback, callbackMethod);
            if (backup != null) {
                callback.setBackupMethod(backup);
                Log.i(TAG, "Hooked: Activity.startActivity");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to hook Activity.startActivity", e);
        }
        return false;
    }

    static class StartActivityCallback extends HookCallback {
        private final LayerThreeMonitor monitor;

        StartActivityCallback(LayerThreeMonitor monitor) {
            this.monitor = monitor;
        }

        @Override
        public Object handleHook(Object[] args) {
            // args[0] = this (Activity), args[1] = intent
            if (args.length >= 2 && args[1] instanceof Intent) {
                Intent intent = (Intent) args[1];
                String targetComponent = intent.getComponent() != null
                        ? intent.getComponent().getClassName() : "";

                // Flag if the target activity is not part of the host app
                if (!targetComponent.isEmpty() && !targetComponent.startsWith(monitor.appPackage)) {
                    String callerClass = monitor.findAdCallerOnStack();
                    if (callerClass != null && !monitor.reportedClasses.contains(targetComponent)) {
                        monitor.reportedClasses.add(targetComponent);
                        monitor.reportDetection(callerClass, "startActivity",
                                "External activity: " + targetComponent);
                    }
                }
            }

            try {
                return callOriginal(args);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // --- Helpers ---

    /**
     * Walk the call stack to find an ad-related caller class.
     */
    private String findAdCallerOnStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String cls = element.getClassName().toLowerCase();
            if (cls.contains("ad") || cls.contains("banner") || cls.contains("interstitial")
                    || cls.contains("rewarded") || cls.contains("mediation")
                    || cls.contains("applovin") || cls.contains("admob")
                    || cls.contains("facebook.ads") || cls.contains("ironsource")
                    || cls.contains("vungle") || cls.contains("unity3d.ads")) {
                // Skip our own classes
                if (cls.startsWith("com.adsweep")) continue;
                return element.getClassName();
            }
        }
        return null;
    }

    private void reportDetection(String callerClass, String hookType, String description) {
        FloatingReporter reporter = FloatingReporter.getInstance();
        if (reporter == null) return;

        // Extract method name from caller
        String methodName = "unknown";
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            if (element.getClassName().equals(callerClass)) {
                methodName = element.getMethodName();
                break;
            }
        }

        DetectionEvent event = new DetectionEvent(callerClass, methodName, hookType, description);
        reporter.report(event);
    }
}

package com.adsweep.reporter;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.adsweep.rules.Rule;
import com.adsweep.rules.RuleStore;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Shows a floating pill UI when suspicious ad behavior is detected.
 * User can tap "Yes" (ad) or "No" (not ad) to train the rule system.
 *
 * Falls back to notification if overlay permission is not granted.
 */
public class FloatingReporter {

    private static final String TAG = "AdSweep.Reporter";
    private static final String CHANNEL_ID = "adsweep_detection";
    private static final String ACTION_AD = "com.adsweep.ACTION_AD";
    private static final String ACTION_NOT_AD = "com.adsweep.ACTION_NOT_AD";
    private static final int AUTO_DISMISS_MS = 8000;
    private static final int NOTIFICATION_ID = 0x4453;

    private static FloatingReporter instance;

    private final Context context;
    private final RuleStore ruleStore;
    private final Handler mainHandler;
    private final Queue<DetectionEvent> pendingEvents = new ConcurrentLinkedQueue<>();
    private final java.util.List<DetectionEvent> history = new java.util.ArrayList<>();

    private WindowManager windowManager;
    private View currentPill;
    private DetectionEvent currentEvent;
    private boolean useOverlay;
    private BroadcastReceiver notificationReceiver;

    private FloatingReporter(Context context, RuleStore ruleStore) {
        this.context = context.getApplicationContext();
        this.ruleStore = ruleStore;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.useOverlay = canUseOverlay();

        if (!useOverlay) {
            setupNotificationChannel();
            setupNotificationReceiver();
        }

        Log.i(TAG, "FloatingReporter initialized (overlay=" + useOverlay + ")");
    }

    public static void init(Context context, RuleStore ruleStore) {
        if (instance == null) {
            instance = new FloatingReporter(context, ruleStore);
        }
    }

    public static FloatingReporter getInstance() {
        return instance;
    }

    /**
     * Report a suspicious detection event.
     */
    public void report(DetectionEvent event) {
        history.add(event);
        pendingEvents.add(event);
        Log.i(TAG, "Detection: " + event.getShortDescription() + " [" + event.hookType + "]");

        mainHandler.post(() -> {
            if (currentPill == null && currentEvent == null) {
                showNext();
            }
        });
    }

    public java.util.List<DetectionEvent> getHistory() {
        return history;
    }

    // --- Overlay Pill UI ---

    private void showNext() {
        currentEvent = pendingEvents.poll();
        if (currentEvent == null) return;

        if (useOverlay) {
            showOverlayPill(currentEvent);
        } else {
            showNotification(currentEvent);
        }
    }

    private void showOverlayPill(DetectionEvent event) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setBackgroundColor(0xDD333333);
        int pad = dp(12);
        pill.setPadding(pad, dp(8), pad, dp(8));

        // Description
        TextView desc = new TextView(context);
        desc.setText(event.getShortDescription());
        desc.setTextColor(Color.WHITE);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        descParams.setMarginEnd(dp(8));
        pill.addView(desc, descParams);

        // "Yes" button
        TextView yesBtn = createButton("Ad", 0xFF4CAF50);
        yesBtn.setOnClickListener(v -> onVerdict(event, true));
        pill.addView(yesBtn);

        // "No" button
        TextView noBtn = createButton("No", 0xFF757575);
        noBtn.setOnClickListener(v -> onVerdict(event, false));
        LinearLayout.LayoutParams noParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noParams.setMarginStart(dp(6));
        pill.addView(noBtn, noParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(60);

        try {
            windowManager.addView(pill, params);
            currentPill = pill;

            // Auto-dismiss
            mainHandler.postDelayed(() -> dismissPill(event, false), AUTO_DISMISS_MS);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show overlay pill", e);
            useOverlay = false;
            showNotification(event);
        }
    }

    private TextView createButton(String text, int color) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setBackgroundColor(color);
        int hPad = dp(14);
        int vPad = dp(6);
        btn.setPadding(hPad, vPad, hPad, vPad);
        return btn;
    }

    private void onVerdict(DetectionEvent event, boolean isAd) {
        if (isAd) {
            event.verdict = DetectionEvent.Verdict.AD;
            // Create a new rule from this detection
            Rule rule = new Rule();
            rule.id = "l3-" + UUID.randomUUID().toString().substring(0, 8);
            rule.className = event.callerClassName;
            rule.methodName = event.callerMethodName;
            rule.action = "BLOCK_RETURN_VOID";
            rule.enabled = true;
            rule.source = "LAYER3_USER";
            rule.sdkName = "User Reported";
            rule.notes = "Detected via " + event.hookType;
            ruleStore.addAppRule(rule);
            Log.i(TAG, "Rule created: " + rule.className + "." + rule.methodName);
        } else {
            event.verdict = DetectionEvent.Verdict.NOT_AD;
            ruleStore.addToWhitelist(event.callerClassName, event.callerMethodName);
            Log.i(TAG, "Whitelisted: " + event.callerClassName + "." + event.callerMethodName);
        }

        dismissPill(event, true);
    }

    private void dismissPill(DetectionEvent event, boolean showNext) {
        if (currentPill != null) {
            try {
                windowManager.removeView(currentPill);
            } catch (Exception e) {
                // Already removed
            }
            currentPill = null;
        }
        currentEvent = null;

        if (showNext) {
            mainHandler.postDelayed(this::showNext, 300);
        }
    }

    // --- Notification Fallback ---

    private void showNotification(DetectionEvent event) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        Intent adIntent = new Intent(ACTION_AD).putExtra("event_id", event.timestamp);
        Intent notAdIntent = new Intent(ACTION_NOT_AD).putExtra("event_id", event.timestamp);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        android.app.Notification notification = null;
        if (Build.VERSION.SDK_INT >= 26) {
            notification = new android.app.Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("AdSweep: Suspicious activity")
                    .setContentText(event.getShortDescription())
                    .addAction(0, "Ad", PendingIntent.getBroadcast(context, 1, adIntent, flags))
                    .addAction(0, "Not Ad", PendingIntent.getBroadcast(context, 2, notAdIntent, flags))
                    .setAutoCancel(true)
                    .build();
        }

        if (notification != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }

        // Auto-dismiss notification
        mainHandler.postDelayed(() -> {
            nm.cancel(NOTIFICATION_ID);
            currentEvent = null;
            showNext();
        }, AUTO_DISMISS_MS);
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AdSweep Detection",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Ad detection alerts");
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private void setupNotificationReceiver() {
        notificationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (currentEvent == null) return;
                String action = intent.getAction();
                if (ACTION_AD.equals(action)) {
                    onVerdict(currentEvent, true);
                } else if (ACTION_NOT_AD.equals(action)) {
                    onVerdict(currentEvent, false);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AD);
        filter.addAction(ACTION_NOT_AD);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(notificationReceiver, filter);
        }
    }

    // --- Utils ---

    private boolean canUseOverlay() {
        if (Build.VERSION.SDK_INT >= 23) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()
        );
    }
}

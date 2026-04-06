package com.adsweep.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.adsweep.AdSweep;
import com.adsweep.hook.HookManager;
import com.adsweep.reporter.DetectionEvent;
import com.adsweep.reporter.FloatingReporter;
import com.adsweep.rules.Rule;
import com.adsweep.rules.RuleStore;

import java.util.List;

/**
 * Simple settings UI for managing AdSweep rules.
 * Built with pure Android Framework (no AndroidX dependency).
 *
 * Three tabs: Rules | History | Settings
 */
public class SettingsActivity extends Activity {

    private static final int TAB_RULES = 0;
    private static final int TAB_HISTORY = 1;
    private static final int TAB_SETTINGS = 2;

    private FrameLayout contentFrame;
    private TextView[] tabViews = new TextView[3];
    private int currentTab = TAB_RULES;

    private RuleStore ruleStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        HookManager hm = AdSweep.getHookManager();
        if (hm != null) {
            ruleStore = hm.getRuleStore();
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);

        // Title bar
        TextView title = new TextView(this);
        title.setText("AdSweep Settings");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setBackgroundColor(0xFF1976D2);
        title.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(title, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Tab bar
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(0xFF1565C0);

        String[] tabNames = {"Rules", "History", "Settings"};
        for (int i = 0; i < 3; i++) {
            TextView tab = new TextView(this);
            tab.setText(tabNames[i]);
            tab.setTextColor(Color.WHITE);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setPadding(dp(16), dp(12), dp(16), dp(12));
            tab.setGravity(Gravity.CENTER);
            final int tabIndex = i;
            tab.setOnClickListener(v -> switchTab(tabIndex));
            tabViews[i] = tab;
            tabBar.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(tabBar, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Content frame
        contentFrame = new FrameLayout(this);
        root.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        switchTab(TAB_RULES);
    }

    private void switchTab(int tab) {
        currentTab = tab;
        for (int i = 0; i < 3; i++) {
            tabViews[i].setAlpha(i == tab ? 1.0f : 0.6f);
            tabViews[i].setTypeface(null, i == tab ? Typeface.BOLD : Typeface.NORMAL);
        }

        contentFrame.removeAllViews();
        switch (tab) {
            case TAB_RULES:
                contentFrame.addView(buildRulesTab());
                break;
            case TAB_HISTORY:
                contentFrame.addView(buildHistoryTab());
                break;
            case TAB_SETTINGS:
                contentFrame.addView(buildSettingsTab());
                break;
        }
    }

    // ===================== TAB 1: Rules =====================

    private View buildRulesTab() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(8), dp(8), dp(8), dp(8));

        if (ruleStore == null) {
            layout.addView(makeLabel("AdSweep not initialized", 16));
            return wrapScroll(layout);
        }

        // Add rule button
        TextView addBtn = new TextView(this);
        addBtn.setText("+ Add Custom Rule");
        addBtn.setTextColor(0xFF1976D2);
        addBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        addBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        addBtn.setOnClickListener(v -> showAddRuleDialog());
        layout.addView(addBtn);

        // Group by SDK name
        List<Rule> rules = ruleStore.getAllRules();
        String lastSdk = "";
        for (Rule rule : rules) {
            String sdk = rule.sdkName != null && !rule.sdkName.isEmpty() ? rule.sdkName : "Other";
            if (!sdk.equals(lastSdk)) {
                TextView header = makeLabel(sdk, 14);
                header.setTypeface(null, Typeface.BOLD);
                header.setTextColor(0xFF1976D2);
                header.setPadding(dp(12), dp(16), dp(12), dp(4));
                layout.addView(header);
                lastSdk = sdk;
            }
            layout.addView(buildRuleRow(rule));
        }

        if (rules.isEmpty()) {
            layout.addView(makeLabel("No rules loaded", 14));
        }

        return wrapScroll(layout);
    }

    private View buildRuleRow(Rule rule) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));
        row.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(2);
        row.setLayoutParams(rowParams);

        // Info column
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        String shortClass = rule.className;
        int lastDot = shortClass.lastIndexOf('.');
        if (lastDot >= 0) shortClass = shortClass.substring(lastDot + 1);

        TextView methodText = new TextView(this);
        methodText.setText(shortClass + "." + rule.methodName);
        methodText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        methodText.setTextColor(Color.BLACK);
        info.addView(methodText);

        TextView actionText = new TextView(this);
        actionText.setText(rule.action + " [" + rule.source + "]");
        actionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        actionText.setTextColor(0xFF888888);
        info.addView(actionText);

        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Toggle switch
        Switch toggle = new Switch(this);
        toggle.setChecked(rule.enabled);
        toggle.setOnCheckedChangeListener((v, checked) -> {
            rule.enabled = checked;
            // Note: won't persist for built-in rules until we add that support
        });
        row.addView(toggle);

        return row;
    }

    private void showAddRuleDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(8));

        EditText classInput = new EditText(this);
        classInput.setHint("Class name (e.g. com.example.AdManager)");
        classInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        form.addView(classInput);

        EditText methodInput = new EditText(this);
        methodInput.setHint("Method name (e.g. loadAd)");
        methodInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        form.addView(methodInput);

        String[] actions = {"BLOCK_RETURN_VOID", "BLOCK_RETURN_NULL", "BLOCK_RETURN_TRUE",
                "BLOCK_RETURN_FALSE", "BLOCK_RETURN_ZERO", "BLOCK_RETURN_EMPTY_STRING"};
        final int[] selectedAction = {0};

        TextView actionLabel = new TextView(this);
        actionLabel.setText("Action: BLOCK_RETURN_VOID (tap to change)");
        actionLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        actionLabel.setPadding(0, dp(8), 0, 0);
        actionLabel.setTextColor(0xFF1976D2);
        actionLabel.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Select Action")
                    .setItems(actions, (d, which) -> {
                        selectedAction[0] = which;
                        actionLabel.setText("Action: " + actions[which]);
                    })
                    .show();
        });
        form.addView(actionLabel);

        new AlertDialog.Builder(this)
                .setTitle("Add Custom Rule")
                .setView(form)
                .setPositiveButton("Add", (d, w) -> {
                    String cls = classInput.getText().toString().trim();
                    String method = methodInput.getText().toString().trim();
                    if (cls.isEmpty() || method.isEmpty()) {
                        Toast.makeText(this, "Class and method are required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Rule rule = new Rule();
                    rule.id = "manual-" + System.currentTimeMillis();
                    rule.className = cls;
                    rule.methodName = method;
                    rule.action = actions[selectedAction[0]];
                    rule.enabled = true;
                    rule.source = "MANUAL";
                    rule.sdkName = "Custom";
                    if (ruleStore != null) {
                        ruleStore.addAppRule(rule);
                        Toast.makeText(this, "Rule added (restart app to apply)", Toast.LENGTH_SHORT).show();
                        switchTab(TAB_RULES); // Refresh
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ===================== TAB 2: History =====================

    private View buildHistoryTab() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(8), dp(8), dp(8), dp(8));

        FloatingReporter reporter = FloatingReporter.getInstance();
        if (reporter == null) {
            layout.addView(makeLabel("Layer 3 not active", 14));
            return wrapScroll(layout);
        }

        List<DetectionEvent> history = reporter.getHistory();
        if (history.isEmpty()) {
            layout.addView(makeLabel("No detections yet. Use the app and check back.", 14));
            return wrapScroll(layout);
        }

        for (int i = history.size() - 1; i >= 0; i--) {
            DetectionEvent event = history.get(i);
            layout.addView(buildHistoryRow(event));
        }

        return wrapScroll(layout);
    }

    private View buildHistoryRow(DetectionEvent event) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(2);
        row.setLayoutParams(rowParams);

        // Title
        TextView title = new TextView(this);
        title.setText(event.getShortDescription());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(Color.BLACK);
        row.addView(title);

        // Details
        TextView detail = new TextView(this);
        detail.setText(event.hookType + ": " + event.description);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        detail.setTextColor(0xFF888888);
        row.addView(detail);

        // Verdict row
        LinearLayout verdictRow = new LinearLayout(this);
        verdictRow.setOrientation(LinearLayout.HORIZONTAL);
        verdictRow.setPadding(0, dp(6), 0, 0);

        TextView verdictLabel = new TextView(this);
        String verdictText;
        int verdictColor;
        switch (event.verdict) {
            case AD:
                verdictText = "Marked as AD";
                verdictColor = 0xFF4CAF50;
                break;
            case NOT_AD:
                verdictText = "Marked as NOT AD";
                verdictColor = 0xFF757575;
                break;
            default:
                verdictText = "Undecided";
                verdictColor = 0xFFFF9800;
                break;
        }
        verdictLabel.setText(verdictText);
        verdictLabel.setTextColor(verdictColor);
        verdictLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        verdictRow.addView(verdictLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Action buttons for undecided
        if (event.verdict == DetectionEvent.Verdict.UNDECIDED && ruleStore != null) {
            TextView adBtn = makeSmallButton("Ad", 0xFF4CAF50);
            adBtn.setOnClickListener(v -> {
                event.verdict = DetectionEvent.Verdict.AD;
                Rule rule = new Rule();
                rule.id = "l3-" + System.currentTimeMillis();
                rule.className = event.callerClassName;
                rule.methodName = event.callerMethodName;
                rule.action = "BLOCK_RETURN_VOID";
                rule.enabled = true;
                rule.source = "LAYER3_USER";
                rule.sdkName = "User Reported";
                ruleStore.addAppRule(rule);
                Toast.makeText(this, "Rule added", Toast.LENGTH_SHORT).show();
                switchTab(TAB_HISTORY);
            });
            verdictRow.addView(adBtn);

            TextView noBtn = makeSmallButton("Not Ad", 0xFF757575);
            noBtn.setOnClickListener(v -> {
                event.verdict = DetectionEvent.Verdict.NOT_AD;
                ruleStore.addToWhitelist(event.callerClassName, event.callerMethodName);
                Toast.makeText(this, "Whitelisted", Toast.LENGTH_SHORT).show();
                switchTab(TAB_HISTORY);
            });
            LinearLayout.LayoutParams noParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noParams.setMarginStart(dp(6));
            verdictRow.addView(noBtn, noParams);
        }

        row.addView(verdictRow);
        return row;
    }

    // ===================== TAB 3: Settings =====================

    private View buildSettingsTab() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));

        // Info section
        HookManager hm = AdSweep.getHookManager();
        if (hm != null) {
            layout.addView(makeInfoRow("Active Hooks", String.valueOf(hm.getActiveHookCount())));
            layout.addView(makeInfoRow("Common Rules", String.valueOf(ruleStore.getAllRules().size())));
        }
        layout.addView(makeInfoRow("AdSweep Version", "1.0.0"));
        layout.addView(makeInfoRow("Hook Engine", "LSPlant 6.4"));

        // Spacer
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        layout.addView(spacer);

        // Export button
        TextView exportBtn = makeActionButton("Export Rules (JSON)");
        exportBtn.setOnClickListener(v -> {
            if (ruleStore != null) {
                try {
                    String json = ruleStore.exportRulesJson();
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AdSweep Rules", json));
                    Toast.makeText(this, "Rules copied to clipboard", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        layout.addView(exportBtn);

        return wrapScroll(layout);
    }

    private View makeInfoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(2);
        row.setLayoutParams(rowParams);

        TextView labelView = makeLabel(label, 14);
        row.addView(labelView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView valueView = makeLabel(value, 14);
        valueView.setTextColor(0xFF1976D2);
        row.addView(valueView);

        return row;
    }

    // ===================== Helpers =====================

    private TextView makeLabel(String text, int sp) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(0xFF333333);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        return tv;
    }

    private TextView makeSmallButton(String text, int color) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btn.setBackgroundColor(color);
        btn.setPadding(dp(12), dp(4), dp(12), dp(4));
        return btn;
    }

    private TextView makeActionButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setBackgroundColor(0xFF1976D2);
        btn.setPadding(dp(16), dp(12), dp(16), dp(12));
        btn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        btn.setLayoutParams(params);
        return btn;
    }

    private ScrollView wrapScroll(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}

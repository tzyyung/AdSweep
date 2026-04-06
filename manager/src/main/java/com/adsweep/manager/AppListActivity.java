package com.adsweep.manager;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lists installed apps for user to select one for patching.
 * Returns the APK path and package name to MainActivity.
 */
public class AppListActivity extends Activity {

    public static final String RESULT_APK_PATH = "apk_path";
    public static final String RESULT_PACKAGE_NAME = "package_name";
    public static final String RESULT_APP_NAME = "app_name";

    private ListView lvApps;
    private EditText etSearch;
    private AppAdapter adapter;
    private List<AppInfo> allApps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);

        lvApps = findViewById(R.id.lvApps);
        etSearch = findViewById(R.id.etSearch);

        // Show loading
        lvApps.setEmptyView(null);

        // Load apps in background
        new Thread(() -> {
            allApps = loadInstalledApps();
            runOnUiThread(() -> {
                adapter = new AppAdapter(allApps);
                lvApps.setAdapter(adapter);
            });
        }).start();

        // Search filter
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (adapter != null) adapter.filter(s.toString());
            }
        });

        // Click to select
        lvApps.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo app = adapter.getFilteredItem(position);
            Intent result = new Intent();
            result.putExtra(RESULT_APK_PATH, app.apkPath);
            result.putExtra(RESULT_PACKAGE_NAME, app.packageName);
            result.putExtra(RESULT_APP_NAME, app.appName);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private List<AppInfo> loadInstalledApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> installed = pm.getInstalledApplications(0);
        List<AppInfo> apps = new ArrayList<>();

        for (ApplicationInfo info : installed) {
            // Skip system apps (show only user-installed apps)
            if ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                continue;
            }
            // Skip our own app
            if (info.packageName.equals(getPackageName())) continue;

            AppInfo app = new AppInfo();
            app.appName = pm.getApplicationLabel(info).toString();
            app.packageName = info.packageName;
            app.apkPath = info.sourceDir;
            app.icon = pm.getApplicationIcon(info);
            apps.add(app);
        }

        // Sort by name
        Collections.sort(apps, (a, b) -> a.appName.compareToIgnoreCase(b.appName));
        return apps;
    }

    // --- Data class ---

    static class AppInfo {
        String appName;
        String packageName;
        String apkPath;
        Drawable icon;
    }

    // --- Adapter ---

    class AppAdapter extends BaseAdapter {
        private final List<AppInfo> allItems;
        private List<AppInfo> filtered;

        AppAdapter(List<AppInfo> items) {
            this.allItems = items;
            this.filtered = new ArrayList<>(items);
        }

        void filter(String query) {
            if (query == null || query.isEmpty()) {
                filtered = new ArrayList<>(allItems);
            } else {
                String q = query.toLowerCase();
                filtered = new ArrayList<>();
                for (AppInfo app : allItems) {
                    if (app.appName.toLowerCase().contains(q)
                            || app.packageName.toLowerCase().contains(q)) {
                        filtered.add(app);
                    }
                }
            }
            notifyDataSetChanged();
        }

        AppInfo getFilteredItem(int position) {
            return filtered.get(position);
        }

        @Override
        public int getCount() { return filtered.size(); }
        @Override
        public Object getItem(int position) { return filtered.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(AppListActivity.this)
                        .inflate(R.layout.item_app, parent, false);
            }

            AppInfo app = filtered.get(position);
            ((ImageView) convertView.findViewById(R.id.ivIcon)).setImageDrawable(app.icon);
            ((TextView) convertView.findViewById(R.id.tvAppName)).setText(app.appName);
            ((TextView) convertView.findViewById(R.id.tvPackageName)).setText(app.packageName);

            return convertView;
        }
    }
}

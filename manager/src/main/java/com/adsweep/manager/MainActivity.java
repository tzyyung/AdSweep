package com.adsweep.manager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * AdSweep Manager — Main screen for selecting, scanning, and patching APKs.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_APK = 1001;
    private static final int REQUEST_INSTALLED_APP = 1002;

    private TextView tvApkInfo;
    private TextView tvScanResults;
    private TextView tvRulesInfo;
    private TextView tvLog;
    private LinearLayout cardScanResults;
    private LinearLayout cardRules;
    private LinearLayout cardLog;
    private Button btnPatch;
    private Button btnInstall;

    private File selectedApk;
    private File patchedApk;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvApkInfo = findViewById(R.id.tvApkInfo);
        tvScanResults = findViewById(R.id.tvScanResults);
        tvRulesInfo = findViewById(R.id.tvRulesInfo);
        tvLog = findViewById(R.id.tvLog);
        cardScanResults = findViewById(R.id.cardScanResults);
        cardRules = findViewById(R.id.cardRules);
        cardLog = findViewById(R.id.cardLog);
        btnPatch = findViewById(R.id.btnPatch);
        btnInstall = findViewById(R.id.btnInstall);

        // Select APK
        findViewById(R.id.cardSelectApk).setOnClickListener(v -> selectApk());

        // Patch button
        btnPatch.setOnClickListener(v -> startPatching());

        // Install button
        btnInstall.setOnClickListener(v -> installPatched());
    }

    private void selectApk() {
        // Show choice dialog: file picker or installed apps
        new android.app.AlertDialog.Builder(this)
                .setTitle("Select APK Source")
                .setItems(new String[]{"From installed apps", "From file"}, (d, which) -> {
                    if (which == 0) {
                        // Launch installed app list
                        Intent intent = new Intent(this, AppListActivity.class);
                        startActivityForResult(intent, REQUEST_INSTALLED_APP);
                    } else {
                        // File picker
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/vnd.android.package-archive");
                        startActivityForResult(intent, REQUEST_APK);
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQUEST_APK) {
            Uri uri = data.getData();
            if (uri != null) {
                copyAndScanApk(uri);
            }
        } else if (requestCode == REQUEST_INSTALLED_APP) {
            String apkPath = data.getStringExtra(AppListActivity.RESULT_APK_PATH);
            String pkgName = data.getStringExtra(AppListActivity.RESULT_PACKAGE_NAME);
            String appName = data.getStringExtra(AppListActivity.RESULT_APP_NAME);
            if (apkPath != null) {
                loadInstalledApk(apkPath, pkgName, appName);
            }
        }
    }

    private void loadInstalledApk(String apkPath, String packageName, String appName) {
        tvApkInfo.setText("Loading...");

        new Thread(() -> {
            try {
                // Copy APK from system to internal storage
                File apkDir = new File(getFilesDir(), "input");
                apkDir.mkdirs();
                selectedApk = new File(apkDir, "target.apk");

                FileInputStream fis = new FileInputStream(apkPath);
                FileOutputStream fos = new FileOutputStream(selectedApk);
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                fis.close();

                long sizeMb = selectedApk.length() / (1024 * 1024);

                mainHandler.post(() -> {
                    tvApkInfo.setText(appName + "\n"
                            + packageName + "\n"
                            + "Size: " + sizeMb + " MB");
                    btnPatch.setEnabled(true);
                });

                // Check for rules
                checkRulesRepo(packageName);

            } catch (Exception e) {
                mainHandler.post(() ->
                        tvApkInfo.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private void copyAndScanApk(Uri uri) {
        tvApkInfo.setText("Loading APK...");

        new Thread(() -> {
            try {
                // Copy APK to internal storage
                File apkDir = new File(getFilesDir(), "input");
                apkDir.mkdirs();
                selectedApk = new File(apkDir, "target.apk");

                InputStream is = getContentResolver().openInputStream(uri);
                FileOutputStream fos = new FileOutputStream(selectedApk);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                is.close();

                long sizeMb = selectedApk.length() / (1024 * 1024);

                mainHandler.post(() -> {
                    tvApkInfo.setText("APK: " + uri.getLastPathSegment()
                            + "\nSize: " + sizeMb + " MB"
                            + "\nPath: " + selectedApk.getAbsolutePath());
                    btnPatch.setEnabled(true);
                });

                // Run scan in background
                scanApk();

            } catch (Exception e) {
                mainHandler.post(() ->
                        tvApkInfo.setText("Error loading APK: " + e.getMessage()));
            }
        }).start();
    }

    private void scanApk() {
        log("Scanning APK...");

        // TODO: integrate apktool Java API for on-device decompilation
        // For now, show a placeholder with package info
        try {
            // Read package info from APK
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageArchiveInfo(selectedApk.getAbsolutePath(), 0);

            String scanResult;
            if (info != null) {
                scanResult = "Package: " + info.packageName
                        + "\nVersion: " + info.versionName
                        + " (" + info.versionCode + ")"
                        + "\n\nOn-device scanning coming soon."
                        + "\nFor now, use CLI:"
                        + "\n  python inject.py --apk target.apk --rules-url auto";
            } else {
                scanResult = "Could not read package info.\nAPK may be a split APK.";
            }

            mainHandler.post(() -> {
                cardScanResults.setVisibility(View.VISIBLE);
                tvScanResults.setText(scanResult);
            });

            // Check for rules in repository
            if (info != null) {
                checkRulesRepo(info.packageName);
            }

        } catch (Exception e) {
            log("Scan error: " + e.getMessage());
        }
    }

    private void checkRulesRepo(String packageName) {
        log("Checking rules for " + packageName + "...");

        new Thread(() -> {
            try {
                String indexUrl = "https://raw.githubusercontent.com/tzyyung/adsweep-rules/main/index.json";
                java.net.URL url = new java.net.URL(indexUrl);
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(url.openStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                org.json.JSONObject index = new org.json.JSONObject(sb.toString());
                org.json.JSONObject apps = index.getJSONObject("apps");

                if (apps.has(packageName)) {
                    org.json.JSONObject appInfo = apps.getJSONObject(packageName);
                    String rulesInfo = "Rules found!\n"
                            + "App: " + appInfo.getString("name") + "\n"
                            + "Hooks: " + appInfo.getInt("hookCount") + "\n"
                            + "Version: " + appInfo.getString("testedVersion") + "\n"
                            + "Status: " + appInfo.getString("status");

                    mainHandler.post(() -> {
                        cardRules.setVisibility(View.VISIBLE);
                        tvRulesInfo.setText(rulesInfo);
                    });
                    log("Rules available for " + packageName);
                } else {
                    mainHandler.post(() -> {
                        cardRules.setVisibility(View.VISIBLE);
                        tvRulesInfo.setText("No rules found for this app.\n"
                                + "Use --discover mode on PC to create rules.");
                    });
                    log("No rules for " + packageName);
                }
            } catch (Exception e) {
                log("Rules check failed: " + e.getMessage());
            }
        }).start();
    }

    private void startPatching() {
        if (selectedApk == null) {
            Toast.makeText(this, "No APK selected", Toast.LENGTH_SHORT).show();
            return;
        }

        btnPatch.setEnabled(false);
        btnPatch.setText("Patching...");
        log("Starting patch...");

        PatchEngine engine = new PatchEngine(this, new PatchEngine.ProgressCallback() {
            @Override
            public void onProgress(String message) {
                mainHandler.post(() -> log(message));
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    log("ERROR: " + error);
                    btnPatch.setEnabled(true);
                    btnPatch.setText("Patch");
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onComplete(File patched) {
                mainHandler.post(() -> {
                    patchedApk = patched;
                    log("Patched APK: " + patched.getAbsolutePath());
                    log("Size: " + (patched.length() / 1024 / 1024) + " MB");
                    btnPatch.setText("Patch");
                    btnPatch.setEnabled(true);
                    btnInstall.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Patch complete!", Toast.LENGTH_SHORT).show();
                });
            }
        });

        // TODO: pass downloaded app rules if available
        engine.patch(selectedApk, null);
    }

    private void installPatched() {
        if (patchedApk == null || !patchedApk.exists()) {
            Toast.makeText(this, "No patched APK available", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri = Uri.fromFile(patchedApk);
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void log(String message) {
        mainHandler.post(() -> {
            cardLog.setVisibility(View.VISIBLE);
            String current = tvLog.getText().toString();
            tvLog.setText(current + (current.isEmpty() ? "" : "\n") + message);
        });
    }
}

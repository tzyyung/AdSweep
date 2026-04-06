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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * AdSweep Manager — Main screen for selecting, scanning, and patching APKs.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_APK = 1001;
    private static final int REQUEST_INSTALLED_APP = 1002;
    private static final int REQUEST_UNINSTALL = 1003;

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
    private String selectedPackageName;
    private String[] cachedSplitPaths;  // saved before uninstall
    private boolean pendingInstallSession = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Must be called before any apktool usage
        PatchEngine.initForAndroid(this);

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

        // Restore state
        if (savedInstanceState != null) {
            String path = savedInstanceState.getString("patchedApkPath");
            if (path != null) {
                patchedApk = new File(path);
                if (patchedApk.exists()) btnInstall.setEnabled(true);
            }
            selectedPackageName = savedInstanceState.getString("selectedPackageName");
            cachedSplitPaths = savedInstanceState.getStringArray("cachedSplitPaths");
            pendingInstallSession = savedInstanceState.getBoolean("pendingInstall");
        }

        // Select APK
        findViewById(R.id.cardSelectApk).setOnClickListener(v -> selectApk());

        // Patch button
        btnPatch.setOnClickListener(v -> startPatching());

        // Install button
        btnInstall.setOnClickListener(v -> installPatched());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (patchedApk != null) outState.putString("patchedApkPath", patchedApk.getAbsolutePath());
        if (selectedPackageName != null) outState.putString("selectedPackageName", selectedPackageName);
        if (cachedSplitPaths != null) outState.putStringArray("cachedSplitPaths", cachedSplitPaths);
        outState.putBoolean("pendingInstall", pendingInstallSession);
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
        } else if (requestCode == REQUEST_UNINSTALL) {
            pendingInstallSession = false;
            // Check if uninstall actually happened
            try {
                getPackageManager().getPackageInfo(selectedPackageName, 0);
                log("Uninstall cancelled. Tap INSTALL to try again.");
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                log("Uninstall done. Installing patched version...");
                doInstall();
            }
            return;
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
        selectedPackageName = packageName;

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

        // Cache split APKs before anything
        cacheSplitApks();

        // Try shell-based install (works on rooted/emulator)
        log("Installing via shell...");
        new Thread(() -> {
            try {
                boolean success = shellInstall();
                mainHandler.post(() -> {
                    if (success) {
                        log("Install complete!");
                        Toast.makeText(this, "Installed successfully!", Toast.LENGTH_LONG).show();
                    } else {
                        log("Shell install failed. Manual install needed:");
                        log("  1. Uninstall original app");
                        log("  2. adb install-multiple patched.apk split1.apk split2.apk");
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> log("Install error: " + e.getMessage()));
            }
        }).start();
    }

    private void cacheSplitApks() {
        if (selectedPackageName == null || cachedSplitPaths != null) return;
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager()
                    .getApplicationInfo(selectedPackageName, 0);
            if (ai.splitSourceDirs != null) {
                cachedSplitPaths = new String[ai.splitSourceDirs.length];
                for (int i = 0; i < ai.splitSourceDirs.length; i++) {
                    File src = new File(ai.splitSourceDirs[i]);
                    File dst = new File(getCacheDir(), "split_" + src.getName());
                    FileInputStream fis = new FileInputStream(src);
                    FileOutputStream fos = new FileOutputStream(dst);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
                    fos.close();
                    fis.close();
                    cachedSplitPaths[i] = dst.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            cachedSplitPaths = null;
        }
    }

    private boolean shellInstall() {
        try {
            // Step 1: Uninstall original
            if (selectedPackageName != null) {
                mainHandler.post(() -> log("Uninstalling " + selectedPackageName + "..."));
                execShell("pm uninstall --user 0 " + selectedPackageName);
            }

            // Step 2: Collect APK files (re-sign splits)
            List<File> apksToInstall = new ArrayList<>();
            apksToInstall.add(patchedApk);

            if (cachedSplitPaths != null) {
                for (String splitPath : cachedSplitPaths) {
                    File splitFile = new File(splitPath);
                    File resigned = new File(getCacheDir(), "rs_" + splitFile.getName());
                    mainHandler.post(() -> log("Re-signing split..."));
                    resignApk(splitFile, resigned);
                    apksToInstall.add(resigned);
                }
            }

            // Step 3: Calculate total size
            long totalSize = 0;
            for (File f : apksToInstall) totalSize += f.length();

            // Step 4: Create install session
            mainHandler.post(() -> log("Creating install session..."));
            String createResult = execShell("pm install-create --user 0 -S " + totalSize);
            // Parse session ID from "Success: created install session [12345]"
            int sessionId = -1;
            if (createResult.contains("[")) {
                String idStr = createResult.substring(
                        createResult.indexOf("[") + 1, createResult.indexOf("]"));
                sessionId = Integer.parseInt(idStr.trim());
            }
            if (sessionId < 0) {
                mainHandler.post(() -> log("Failed to create session: " + createResult));
                return false;
            }
            mainHandler.post(() -> log("Session created"));

            // Step 5: Write each APK to session
            for (int i = 0; i < apksToInstall.size(); i++) {
                File apk = apksToInstall.get(i);
                String name = apk.getName();
                long size = apk.length();
                String writeCmd = "pm install-write -S " + size + " " + sessionId + " " + name + " " + apk.getAbsolutePath();
                mainHandler.post(() -> log("Writing " + name + "..."));
                String writeResult = execShell(writeCmd);
                if (!writeResult.contains("Success")) {
                    mainHandler.post(() -> log("Write failed: " + writeResult));
                    execShell("pm install-abandon " + sessionId);
                    return false;
                }
            }

            // Step 6: Commit session
            mainHandler.post(() -> log("Committing install..."));
            String commitResult = execShell("pm install-commit " + sessionId);
            mainHandler.post(() -> log("Result: " + commitResult));

            // Cleanup
            if (cachedSplitPaths != null) {
                for (String sp : cachedSplitPaths) {
                    new File(sp).delete();
                    new File(getCacheDir(), "rs_" + new File(sp).getName()).delete();
                }
            }

            return commitResult.contains("Success");
        } catch (Exception e) {
            mainHandler.post(() -> log("Install error: " + e.getMessage()));
            return false;
        }
    }

    private String execShell(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()));
        java.io.BufferedReader errReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getErrorStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        while ((line = errReader.readLine()) != null) sb.append(line).append("\n");
        p.waitFor();
        return sb.toString().trim();
    }

    private void doInstall() {
        log("Installing...");
        btnInstall.setEnabled(false);

        new Thread(() -> {
            try {
                android.content.pm.PackageInstaller installer = getPackageManager().getPackageInstaller();
                android.content.pm.PackageInstaller.SessionParams params =
                        new android.content.pm.PackageInstaller.SessionParams(
                                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setInstallLocation(
                        android.content.pm.PackageInfo.INSTALL_LOCATION_AUTO);

                int sessionId = installer.createSession(params);
                android.content.pm.PackageInstaller.Session session = installer.openSession(sessionId);

                // Write patched base APK
                mainHandler.post(() -> log("Writing base APK to install session..."));
                writeApkToSession(session, patchedApk, "base.apk");

                // Write cached split APKs (copied before uninstall)
                if (cachedSplitPaths != null && cachedSplitPaths.length > 0) {
                    writeSplitApks(session, cachedSplitPaths);
                }

                // Commit
                Intent callbackIntent = new Intent(this, MainActivity.class);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        this, 0, callbackIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
                session.commit(pi.getIntentSender());

                mainHandler.post(() -> {
                    log("Install session committed. Please confirm.");
                    btnInstall.setEnabled(true);
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    log("Install error: " + e.getMessage());
                    btnInstall.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Install error: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void writeApkToSession(android.content.pm.PackageInstaller.Session session,
                                    File apkFile, String name) throws Exception {
        FileInputStream fis = new FileInputStream(apkFile);
        OutputStream out = session.openWrite(name, 0, apkFile.length());
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) out.write(buf, 0, len);
        session.fsync(out);
        out.close();
        fis.close();
    }

    private void writeSplitApks(android.content.pm.PackageInstaller.Session session,
                                 String[] splitPaths) throws Exception {
        for (String splitPath : splitPaths) {
            File splitFile = new File(splitPath);
            String splitName = splitFile.getName();
            // Remove "split_" prefix we added when caching
            String sessionName = splitName.startsWith("split_") ? splitName.substring(6) : splitName;
            mainHandler.post(() -> log("Re-signing: " + sessionName));

            File resignedSplit = new File(getCacheDir(), "resigned_" + sessionName);
            resignApk(splitFile, resignedSplit);

            FileInputStream fis = new FileInputStream(resignedSplit);
            OutputStream out = session.openWrite(sessionName, 0, resignedSplit.length());
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) out.write(buf, 0, len);
            session.fsync(out);
            out.close();
            fis.close();
            resignedSplit.delete();
            splitFile.delete();
        }
        mainHandler.post(() -> log("Added " + splitPaths.length + " split APKs"));
    }

    private void resignApk(File inputApk, File outputApk) throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        InputStream ksStream = getAssets().open("debug.p12");
        ks.load(ksStream, "android".toCharArray());
        ksStream.close();

        java.security.PrivateKey key = (java.security.PrivateKey) ks.getKey("debugkey", "android".toCharArray());
        java.security.cert.X509Certificate cert =
                (java.security.cert.X509Certificate) ks.getCertificate("debugkey");

        com.android.apksig.ApkSigner.SignerConfig signerConfig =
                new com.android.apksig.ApkSigner.SignerConfig.Builder(
                        "debugkey", key, java.util.Collections.singletonList(cert)).build();

        com.android.apksig.ApkSigner signer = new com.android.apksig.ApkSigner.Builder(
                java.util.Collections.singletonList(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build();
        signer.sign();
    }

    private void log(String message) {
        mainHandler.post(() -> {
            cardLog.setVisibility(View.VISIBLE);
            String current = tvLog.getText().toString();
            tvLog.setText(current + (current.isEmpty() ? "" : "\n") + message);
        });
    }
}

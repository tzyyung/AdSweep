package com.adsweep.manager;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
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

/**
 * AdSweep Manager — 4-step flow:
 * 1. Select App
 * 2. Backup + Patch
 * 3. Uninstall Original
 * 4. Install Patched
 */
public class MainActivity extends Activity {

    private static final int REQ_SELECT_APP = 1001;
    private static final int REQ_SELECT_FILE = 1002;

    // UI
    private TextView tvStep1Info, tvStep2Info, tvStep3Info, tvStep4Info, tvLog;
    private TextView step1Status, step2Status, step3Status, step4Status;
    private Button btnSelect, btnPatch, btnUninstall, btnInstall;
    private LinearLayout cardLog;

    // State (persisted via files)
    private String selectedPackageName;
    private File inputApk;         // backed up base APK
    private File patchedApk;       // patched APK
    private File splitDir;         // cached split APKs

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PatchEngine.initForAndroid(this);
        setContentView(R.layout.activity_main);

        // Bind views
        tvStep1Info = findViewById(R.id.tvStep1Info);
        tvStep2Info = findViewById(R.id.tvStep2Info);
        tvStep3Info = findViewById(R.id.tvStep3Info);
        tvStep4Info = findViewById(R.id.tvStep4Info);
        tvLog = findViewById(R.id.tvLog);
        step1Status = findViewById(R.id.step1Status);
        step2Status = findViewById(R.id.step2Status);
        step3Status = findViewById(R.id.step3Status);
        step4Status = findViewById(R.id.step4Status);
        btnSelect = findViewById(R.id.btnSelect);
        btnPatch = findViewById(R.id.btnPatch);
        btnUninstall = findViewById(R.id.btnUninstall);
        btnInstall = findViewById(R.id.btnInstall);
        cardLog = findViewById(R.id.cardLog);

        // Init dirs
        inputApk = new File(getFilesDir(), "input/target.apk");
        patchedApk = new File(getFilesDir(), "patched/patched.apk");
        splitDir = new File(getCacheDir(), "splits");

        // Buttons
        btnSelect.setOnClickListener(v -> selectApp());
        btnPatch.setOnClickListener(v -> doPatch());
        btnUninstall.setOnClickListener(v -> doUninstall());
        btnInstall.setOnClickListener(v -> doInstall());

        // Restore state from files
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    /** Check file state and update UI accordingly. */
    private void refreshState() {
        // Step 1: App selected?
        if (inputApk.exists() && selectedPackageName != null) {
            setStepDone(step1Status);
            tvStep1Info.setText(selectedPackageName);
            btnPatch.setEnabled(true);
        }

        // Step 2: Patched?
        if (patchedApk.exists()) {
            setStepDone(step2Status);
            tvStep2Info.setText("Patched (" + (patchedApk.length() / 1024 / 1024) + " MB)");
            btnUninstall.setEnabled(true);
        }

        // Step 3: Original uninstalled?
        if (selectedPackageName != null && !isPackageInstalled(selectedPackageName)) {
            if (patchedApk.exists()) {
                setStepDone(step3Status);
                tvStep3Info.setText("Uninstalled");
                btnInstall.setEnabled(true);
                btnUninstall.setEnabled(false);
            }
        }
    }

    // ===================== Step 1: Select App =====================

    private void selectApp() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Select APK Source")
                .setItems(new String[]{"From installed apps", "From file"}, (d, w) -> {
                    if (w == 0) {
                        startActivityForResult(new Intent(this, AppListActivity.class), REQ_SELECT_APP);
                    } else {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/vnd.android.package-archive");
                        startActivityForResult(intent, REQ_SELECT_FILE);
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_SELECT_APP) {
            String apkPath = data.getStringExtra(AppListActivity.RESULT_APK_PATH);
            selectedPackageName = data.getStringExtra(AppListActivity.RESULT_PACKAGE_NAME);
            String appName = data.getStringExtra(AppListActivity.RESULT_APP_NAME);
            if (apkPath != null) {
                backupApp(new File(apkPath), appName);
            }
        } else if (requestCode == REQ_SELECT_FILE) {
            Uri uri = data.getData();
            if (uri != null) backupFromUri(uri);
        }
    }

    private void backupApp(File sourceApk, String appName) {
        log("Backing up " + appName + "...");
        tvStep1Info.setText(appName + "\n" + selectedPackageName);
        setStepActive(step1Status);

        new Thread(() -> {
            try {
                // Backup base APK
                inputApk.getParentFile().mkdirs();
                copyFile(sourceApk, inputApk);

                // Backup split APKs
                splitDir.mkdirs();
                for (File f : splitDir.listFiles() != null ? splitDir.listFiles() : new File[0]) f.delete();

                if (selectedPackageName != null) {
                    try {
                        android.content.pm.ApplicationInfo ai = getPackageManager()
                                .getApplicationInfo(selectedPackageName, 0);
                        if (ai.splitSourceDirs != null) {
                            for (String sp : ai.splitSourceDirs) {
                                File src = new File(sp);
                                copyFile(src, new File(splitDir, src.getName()));
                            }
                            mainHandler.post(() -> log("Backed up " + ai.splitSourceDirs.length + " split APKs"));
                        }
                    } catch (Exception e) { /* no splits */ }
                }

                // Check rules repo
                checkRules();

                mainHandler.post(() -> {
                    setStepDone(step1Status);
                    long mb = inputApk.length() / 1024 / 1024;
                    tvStep1Info.setText(selectedPackageName + "\nSize: " + mb + " MB — Backed up");
                    btnPatch.setEnabled(true);
                    log("Backup complete");
                });
            } catch (Exception e) {
                mainHandler.post(() -> log("Backup error: " + e.getMessage()));
            }
        }).start();
    }

    private void backupFromUri(Uri uri) {
        selectedPackageName = null;
        log("Loading APK from file...");
        new Thread(() -> {
            try {
                inputApk.getParentFile().mkdirs();
                InputStream is = getContentResolver().openInputStream(uri);
                FileOutputStream fos = new FileOutputStream(inputApk);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                is.close();

                // Get package name
                android.content.pm.PackageInfo info = getPackageManager()
                        .getPackageArchiveInfo(inputApk.getAbsolutePath(), 0);
                if (info != null) selectedPackageName = info.packageName;

                mainHandler.post(() -> {
                    setStepDone(step1Status);
                    tvStep1Info.setText(selectedPackageName != null ? selectedPackageName : "Unknown");
                    btnPatch.setEnabled(true);
                    log("APK loaded");
                });
            } catch (Exception e) {
                mainHandler.post(() -> log("Load error: " + e.getMessage()));
            }
        }).start();
    }

    private void checkRules() {
        if (selectedPackageName == null) return;
        try {
            java.net.URL url = new java.net.URL(
                    "https://raw.githubusercontent.com/tzyyung/adsweep-rules/main/index.json");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(url.openStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            org.json.JSONObject index = new org.json.JSONObject(sb.toString());
            org.json.JSONObject apps = index.getJSONObject("apps");
            if (apps.has(selectedPackageName)) {
                org.json.JSONObject info = apps.getJSONObject(selectedPackageName);
                mainHandler.post(() -> log("Rules found: " + info.optString("name")
                        + " (" + info.optInt("hookCount") + " hooks)"));
            }
        } catch (Exception e) { /* no rules available */ }
    }

    // ===================== Step 2: Patch =====================

    private void doPatch() {
        btnPatch.setEnabled(false);
        setStepActive(step2Status);
        tvStep2Info.setText("Patching...");

        PatchEngine engine = new PatchEngine(this, new PatchEngine.ProgressCallback() {
            @Override
            public void onProgress(String msg) {
                mainHandler.post(() -> {
                    tvStep2Info.setText(msg);
                    log(msg);
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    tvStep2Info.setText("Error: " + error);
                    log("ERROR: " + error);
                    btnPatch.setEnabled(true);
                    setStepError(step2Status);
                });
            }

            @Override
            public void onComplete(File result) {
                mainHandler.post(() -> {
                    patchedApk = result;
                    setStepDone(step2Status);
                    tvStep2Info.setText("Patched (" + (result.length() / 1024 / 1024) + " MB)");
                    btnUninstall.setEnabled(true);
                    // If original not installed, skip to install
                    if (selectedPackageName != null && !isPackageInstalled(selectedPackageName)) {
                        setStepDone(step3Status);
                        tvStep3Info.setText("Not installed");
                        btnInstall.setEnabled(true);
                    }
                });
            }
        });
        engine.patch(inputApk, null);
    }

    // ===================== Step 3: Uninstall =====================

    private void doUninstall() {
        if (selectedPackageName == null) return;
        if (!isPackageInstalled(selectedPackageName)) {
            setStepDone(step3Status);
            tvStep3Info.setText("Already uninstalled");
            btnInstall.setEnabled(true);
            return;
        }
        setStepActive(step3Status);
        tvStep3Info.setText("Confirm uninstall...");
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + selectedPackageName));
        startActivity(intent);
        // onResume will detect if uninstall happened and update UI
    }

    // ===================== Step 4: Install =====================

    private void doInstall() {
        // Check install permission first
        if (!getPackageManager().canRequestPackageInstalls()) {
            log("Please allow installing unknown apps");
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        btnInstall.setEnabled(false);
        setStepActive(step4Status);
        tvStep4Info.setText("Installing...");

        new Thread(() -> {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);

                int sessionId = installer.createSession(params);
                PackageInstaller.Session session = installer.openSession(sessionId);

                // Write patched base APK
                mainHandler.post(() -> log("Writing base APK..."));
                writeToSession(session, patchedApk, "base.apk");

                // Write re-signed split APKs
                File[] splits = splitDir.listFiles();
                if (splits != null && splits.length > 0) {
                    for (File split : splits) {
                        mainHandler.post(() -> log("Re-signing " + split.getName() + "..."));
                        File resigned = new File(getCacheDir(), "rs_" + split.getName());
                        resignApk(split, resigned);
                        writeToSession(session, resigned, split.getName());
                        resigned.delete();
                    }
                    mainHandler.post(() -> log("Added " + splits.length + " split APKs"));
                }

                // Register callback on main thread
                final int sid = sessionId;
                mainHandler.post(() -> installer.registerSessionCallback(new PackageInstaller.SessionCallback() {
                    @Override public void onCreated(int id) {}
                    @Override public void onBadgingChanged(int id) {}
                    @Override public void onActiveChanged(int id, boolean active) {}
                    @Override public void onProgressChanged(int id, float progress) {}
                    @Override
                    public void onFinished(int id, boolean success) {
                        if (id == sid) {
                            mainHandler.post(() -> {
                                if (success) {
                                    setStepDone(step4Status);
                                    tvStep4Info.setText("Installed successfully!");
                                    log("Install SUCCESS!");
                                    Toast.makeText(MainActivity.this, "Install complete!", Toast.LENGTH_LONG).show();
                                } else {
                                    setStepError(step4Status);
                                    tvStep4Info.setText("Install failed");
                                    log("Install FAILED (session rejected)");
                                    log("This may be due to split APK mismatch.");
                                    log("Try: adb install patched.apk");
                                    btnInstall.setEnabled(true);
                                }
                            });
                            installer.unregisterSessionCallback(this);
                        }
                    }
                }));

                // Commit with explicit broadcast intent
                Intent cb = new Intent(this, InstallReceiver.class);
                cb.setAction("com.adsweep.manager.INSTALL_STATUS");
                PendingIntent pi = PendingIntent.getBroadcast(this, sessionId, cb,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pi.getIntentSender());

                mainHandler.post(() -> {
                    tvStep4Info.setText("Waiting for confirmation...");
                    log("Install session committed, waiting for result...");
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    tvStep4Info.setText("Error: " + e.getMessage());
                    log("Install error: " + e.getMessage());
                    btnInstall.setEnabled(true);
                    setStepError(step4Status);
                });
            }
        }).start();
    }

    private void writeToSession(PackageInstaller.Session session, File apk, String name) throws Exception {
        FileInputStream fis = new FileInputStream(apk);
        OutputStream out = session.openWrite(name, 0, apk.length());
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) out.write(buf, 0, len);
        session.fsync(out);
        out.close();
        fis.close();
    }

    private void resignApk(File input, File output) throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        InputStream ksStream = getAssets().open("debug.p12");
        ks.load(ksStream, "android".toCharArray());
        ksStream.close();

        java.security.PrivateKey key = (java.security.PrivateKey) ks.getKey("debugkey", "android".toCharArray());
        java.security.cert.X509Certificate cert =
                (java.security.cert.X509Certificate) ks.getCertificate("debugkey");

        com.android.apksig.ApkSigner.SignerConfig sc =
                new com.android.apksig.ApkSigner.SignerConfig.Builder(
                        "debugkey", key, java.util.Collections.singletonList(cert)).build();

        new com.android.apksig.ApkSigner.Builder(java.util.Collections.singletonList(sc))
                .setInputApk(input)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build().sign();
    }

    // ===================== Utilities =====================

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        dst.getParentFile().mkdirs();
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
        fos.close();
        fis.close();
    }

    private void setStepDone(TextView status) {
        status.setText("✓");
        status.setBackgroundColor(0xFF4CAF50);
    }

    private void setStepActive(TextView status) {
        status.setText("...");
        status.setBackgroundColor(0xFF1976D2);
    }

    private void setStepError(TextView status) {
        status.setText("✗");
        status.setBackgroundColor(0xFFF44336);
    }

    private void log(String msg) {
        cardLog.setVisibility(View.VISIBLE);
        String current = tvLog.getText().toString();
        tvLog.setText(current + (current.isEmpty() ? "" : "\n") + msg);
    }
}

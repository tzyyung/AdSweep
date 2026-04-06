package com.adsweep.manager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Receives commands via adb broadcast for automated testing.
 *
 * Usage:
 *   adb shell am broadcast -a com.adsweep.manager.CMD_SELECT --es package com.example.app
 *   adb shell am broadcast -a com.adsweep.manager.CMD_PATCH
 *   adb shell am broadcast -a com.adsweep.manager.CMD_INSTALL --es apk_path /sdcard/patched.apk
 *   adb shell am broadcast -a com.adsweep.manager.CMD_STATUS
 */
public class CommandReceiver extends BroadcastReceiver {

    private static final String TAG = "AdSweep.Cmd";

    public static final String ACTION_SELECT = "com.adsweep.manager.CMD_SELECT";
    public static final String ACTION_PATCH = "com.adsweep.manager.CMD_PATCH";
    public static final String ACTION_INSTALL = "com.adsweep.manager.CMD_INSTALL";
    public static final String ACTION_STATUS = "com.adsweep.manager.CMD_STATUS";

    // Shared state for automation
    static File selectedApk;
    static File patchedApk;
    static String selectedPackage;
    static String status = "idle";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.i(TAG, "Received: " + action);

        switch (action) {
            case ACTION_SELECT:
                handleSelect(context, intent);
                break;
            case ACTION_PATCH:
                handlePatch(context);
                break;
            case ACTION_INSTALL:
                handleInstall(context, intent);
                break;
            case ACTION_STATUS:
                handleStatus();
                break;
        }
    }

    private void handleSelect(Context context, Intent intent) {
        String pkg = intent.getStringExtra("package");
        if (pkg == null) {
            Log.e(TAG, "Missing --es package <name>");
            return;
        }

        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            String apkPath = ai.sourceDir;

            // Copy APK to internal storage
            File inputDir = new File(context.getFilesDir(), "input");
            inputDir.mkdirs();
            selectedApk = new File(inputDir, "target.apk");
            copy(new File(apkPath), selectedApk);
            selectedPackage = pkg;

            // Backup split APKs
            File splitDir = new File(context.getCacheDir(), "splits");
            splitDir.mkdirs();
            for (File f : splitDir.listFiles() != null ? splitDir.listFiles() : new File[0]) f.delete();
            if (ai.splitSourceDirs != null) {
                for (String sp : ai.splitSourceDirs) {
                    File src = new File(sp);
                    copy(src, new File(splitDir, src.getName()));
                }
                Log.i(TAG, "Backed up " + ai.splitSourceDirs.length + " splits");
            }

            status = "selected:" + pkg;
            Log.i(TAG, "Selected: " + pkg + " (" + selectedApk.length() / 1024 / 1024 + " MB)");

        } catch (Exception e) {
            Log.e(TAG, "Select failed: " + e.getMessage());
            status = "error:" + e.getMessage();
        }
    }

    private void handlePatch(Context context) {
        if (selectedApk == null || !selectedApk.exists()) {
            Log.e(TAG, "No APK selected. Run CMD_SELECT first.");
            return;
        }

        status = "patching";
        PatchEngine.initForAndroid(context);

        PatchEngine engine = new PatchEngine(context, new PatchEngine.ProgressCallback() {
            @Override
            public void onProgress(String msg) {
                Log.i(TAG, "Patch: " + msg);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Patch ERROR: " + error);
                status = "error:" + error;
            }

            @Override
            public void onComplete(File result) {
                patchedApk = result;
                status = "patched:" + result.getAbsolutePath() + " (" + result.length() / 1024 / 1024 + " MB)";
                Log.i(TAG, "Patch complete: " + status);
            }
        });
        engine.patch(selectedApk, null);
    }

    private void handleInstall(Context context, Intent intent) {
        // Accept either patched APK from patch step, or external path
        String apkPath = intent.getStringExtra("apk_path");
        File apk = apkPath != null ? new File(apkPath) : patchedApk;

        if (apk == null || !apk.exists()) {
            Log.e(TAG, "No APK to install. Run CMD_PATCH first or pass --es apk_path");
            return;
        }

        status = "installing";
        try {
            android.content.pm.PackageInstaller installer =
                    context.getPackageManager().getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams params =
                    new android.content.pm.PackageInstaller.SessionParams(
                            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);

            int sessionId = installer.createSession(params);
            android.content.pm.PackageInstaller.Session session = installer.openSession(sessionId);

            FileInputStream fis = new FileInputStream(apk);
            java.io.OutputStream out = session.openWrite("base.apk", 0, apk.length());
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) out.write(buf, 0, len);
            session.fsync(out);
            out.close();
            fis.close();

            Intent cb = new Intent(context, InstallReceiver.class);
            cb.setAction("com.adsweep.manager.INSTALL_STATUS");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    context, sessionId, cb,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
            session.commit(pi.getIntentSender());

            Log.i(TAG, "Install session committed");

        } catch (Exception e) {
            Log.e(TAG, "Install failed: " + e.getMessage());
            status = "error:" + e.getMessage();
        }
    }

    private void handleStatus() {
        Log.i(TAG, "Status: " + status);
        if (selectedApk != null) Log.i(TAG, "  selected: " + selectedPackage);
        if (patchedApk != null) Log.i(TAG, "  patched: " + patchedApk.getAbsolutePath());
    }

    private void copy(File src, File dst) throws Exception {
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
        fos.close();
        fis.close();
    }
}

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
 * Usage (add -n com.adsweep.manager/.CommandReceiver for Android 14+):
 *   adb shell am broadcast -a com.adsweep.manager.CMD_SELECT -n ... --es package com.example.app
 *   adb shell am broadcast -a com.adsweep.manager.CMD_PATCH -n ...
 *   adb shell am broadcast -a com.adsweep.manager.CMD_UNINSTALL -n ...
 *   adb shell am broadcast -a com.adsweep.manager.CMD_INSTALL -n ...
 *   adb shell am broadcast -a com.adsweep.manager.CMD_STATUS -n ...
 */
public class CommandReceiver extends BroadcastReceiver {

    private static final String TAG = "AdSweep.Cmd";

    public static final String ACTION_SELECT = "com.adsweep.manager.CMD_SELECT";
    public static final String ACTION_PATCH = "com.adsweep.manager.CMD_PATCH";
    public static final String ACTION_UNINSTALL = "com.adsweep.manager.CMD_UNINSTALL";
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
            case ACTION_UNINSTALL:
                handleUninstall(context);
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
        String apkPathStr = intent.getStringExtra("apk_path");

        if (pkg == null && apkPathStr == null) {
            Log.e(TAG, "Missing --es package <name> or --es apk_path <path>");
            return;
        }

        try {
            File inputDir = new File(context.getFilesDir(), "input");
            inputDir.mkdirs();
            selectedApk = new File(inputDir, "target.apk");

            if (apkPathStr != null) {
                // Direct APK path mode (for testing with APK files on storage)
                File srcApk = new File(apkPathStr);
                if (!srcApk.exists()) {
                    Log.e(TAG, "APK not found: " + apkPathStr);
                    return;
                }
                if (!srcApk.getCanonicalPath().equals(selectedApk.getCanonicalPath())) {
                    copy(srcApk, selectedApk);
                }
                selectedPackage = pkg != null ? pkg : "unknown";
            } else {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                String apkPath = ai.sourceDir;
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
            }

            status = "selected:" + selectedPackage;
            Log.i(TAG, "Selected: " + selectedPackage + " (" + selectedApk.length() / 1024 / 1024 + " MB)");

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
        engine.patch(selectedApk, selectedPackage);
    }

    private void handleUninstall(Context context) {
        if (selectedPackage == null) {
            Log.e(TAG, "No package selected. Run CMD_SELECT first.");
            return;
        }

        try {
            Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
            uninstallIntent.setData(android.net.Uri.parse("package:" + selectedPackage));
            uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(uninstallIntent);
            status = "uninstalling:" + selectedPackage;
            Log.i(TAG, "Uninstall requested for: " + selectedPackage);
        } catch (Exception e) {
            Log.e(TAG, "Uninstall failed: " + e.getMessage());
            status = "error:" + e.getMessage();
        }
    }

    private void handleInstall(Context context, Intent intent) {
        String apkPath = intent.getStringExtra("apk_path");
        File baseApk = apkPath != null ? new File(apkPath) : patchedApk;

        if (baseApk == null || !baseApk.exists()) {
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

            // Write patched base APK
            writeToSession(session, baseApk, "base.apk");

            // Write re-signed split APKs (backed up during SELECT)
            File splitDir = new File(context.getCacheDir(), "splits");
            File[] splits = splitDir.exists() ? splitDir.listFiles() : null;
            if (splits != null && splits.length > 0) {
                for (File split : splits) {
                    if (split == null || split.isDirectory()) continue;
                    Log.i(TAG, "Re-signing split: " + split.getName());
                    File resigned = new File(context.getCacheDir(), "rs_" + split.getName());
                    PatchEngine.signApk(context, split, resigned);
                    writeToSession(session, resigned, split.getName());
                    resigned.delete();
                }
                Log.i(TAG, "Added " + splits.length + " split APKs");
            }

            Intent cb = new Intent(context, InstallReceiver.class);
            cb.setAction("com.adsweep.manager.INSTALL_STATUS");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    context, sessionId, cb,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
            session.commit(pi.getIntentSender());

            Log.i(TAG, "Install session committed with base + " +
                    (splits != null ? splits.length : 0) + " splits");

        } catch (Exception e) {
            Log.e(TAG, "Install failed: " + e.getMessage());
            status = "error:" + e.getMessage();
        }
    }

    private void writeToSession(android.content.pm.PackageInstaller.Session session,
                                File apk, String name) throws Exception {
        FileInputStream fis = new FileInputStream(apk);
        java.io.OutputStream out = session.openWrite(name, 0, apk.length());
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) out.write(buf, 0, len);
        session.fsync(out);
        out.close();
        fis.close();
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

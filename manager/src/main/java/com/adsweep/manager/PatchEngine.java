package com.adsweep.manager;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkSigner.SignerConfig;

import java.io.*;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device APK patching engine.
 * Equivalent to the Python injector but runs on Android.
 */
public class PatchEngine {

    private static final String TAG = "AdSweep.Patch";

    public interface ProgressCallback {
        void onProgress(String message);
        void onError(String error);
        void onComplete(File patchedApk);
    }

    private final Context context;
    private final ProgressCallback callback;

    /**
     * Must be called once at app startup (before any apktool API usage).
     * Fixes system properties that apktool expects on desktop JVM.
     */
    public static void initForAndroid(Context ctx) {
        if (System.getProperty("os.name") == null) {
            System.setProperty("os.name", "Linux");
        }
        if (System.getProperty("user.home") == null) {
            System.setProperty("user.home", ctx.getFilesDir().getAbsolutePath());
        }
        if (System.getProperty("java.io.tmpdir") == null) {
            System.setProperty("java.io.tmpdir", ctx.getCacheDir().getAbsolutePath());
        }
    }

    public PatchEngine(Context context, ProgressCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    /**
     * Patch an APK with AdSweep.
     * Runs on a background thread.
     */
    public void patch(File inputApk, File appRules) {
        new Thread(() -> {
            try {
                doPatch(inputApk, appRules);
            } catch (Throwable t) {
                Log.e(TAG, "Patch failed", t);
                String msg = t.getMessage();
                if (t.getCause() != null) msg += "\nCaused by: " + t.getCause().getMessage();
                callback.onError("Patch failed: " + msg);
            }
        }).start();
    }

    private void doPatch(File inputApk, File appRules) throws Exception {
        File workDir = new File(context.getCacheDir(), "adsweep_work");
        File decompDir = new File(workDir, "decompiled");
        File outputApk = new File(context.getFilesDir(), "patched/patched.apk");
        outputApk.getParentFile().mkdirs();

        // Cleanup
        deleteRecursive(workDir);
        workDir.mkdirs();

        // Step 1: Decompile
        progress("Decompiling APK...");
        decompileApk(inputApk, decompDir);

        // Step 2: Find Application class
        progress("Finding Application class...");
        String appClass = findApplicationClass(decompDir);
        if (appClass == null) {
            callback.onError("Could not find Application class");
            return;
        }
        progress("Application: " + appClass);

        // Step 3: Inject init call
        progress("Injecting AdSweep...");
        injectInitCall(decompDir, appClass);

        // Step 4: Copy payload
        progress("Copying payload (DEX + .so + rules)...");
        copyPayload(decompDir);

        // Step 5: Copy app rules if provided
        if (appRules != null && appRules.exists()) {
            File assetsDir = new File(decompDir, "assets");
            copyFile(appRules, new File(assetsDir, "adsweep_rules_app.json"));
            progress("App rules copied");
        }

        // Step 6: Patch apktool.yml
        patchApktoolYml(decompDir);

        // Step 7: Recompile
        progress("Recompiling APK...");
        File unsignedApk = new File(workDir, "unsigned.apk");
        recompileApk(decompDir, unsignedApk);

        // Step 8: Sign
        progress("Signing APK...");
        signApk(unsignedApk, outputApk);

        // Cleanup
        deleteRecursive(workDir);

        progress("Done! Patched APK ready.");
        callback.onComplete(outputApk);
    }

    // --- Decompile/Recompile via apktool ---

    private void decompileApk(File apk, File outputDir) throws Exception {
        brut.androlib.Config config = brut.androlib.Config.getDefaultConfig();
        config.setDecodeResources(brut.androlib.Config.DECODE_RESOURCES_NONE); // -r mode
        brut.androlib.ApkDecoder decoder = new brut.androlib.ApkDecoder(
                config, new brut.directory.ExtFile(apk));
        decoder.decode(outputDir);
    }

    private void recompileApk(File decompDir, File outputApk) throws Exception {
        brut.androlib.Config config = brut.androlib.Config.getDefaultConfig();
        brut.androlib.ApkBuilder builder = new brut.androlib.ApkBuilder(
                config, new brut.directory.ExtFile(decompDir));
        builder.build(outputApk);
    }

    // --- Find Application class from manifest ---

    private String findApplicationClass(File decompDir) {
        File manifest = new File(decompDir, "AndroidManifest.xml");
        if (!manifest.exists()) return null;

        try {
            String content = readFile(manifest);
            // Parse android:name from <application> tag
            Pattern p = Pattern.compile("android:name=\"([^\"]+)\"");
            // Find the <application section first
            int appIdx = content.indexOf("<application");
            if (appIdx < 0) return null;
            String appSection = content.substring(appIdx, content.indexOf(">", appIdx) + 1);

            Matcher m = p.matcher(appSection);
            if (m.find()) {
                String name = m.group(1);
                // Handle relative names
                if (name.startsWith(".")) {
                    Pattern pkgPattern = Pattern.compile("package=\"([^\"]+)\"");
                    Matcher pkgMatcher = pkgPattern.matcher(content);
                    if (pkgMatcher.find()) {
                        name = pkgMatcher.group(1) + name;
                    }
                }
                return name;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading manifest", e);
        }
        return null;
    }

    // --- Inject AdSweep.init() into Application.onCreate ---

    private void injectInitCall(File decompDir, String appClass) throws Exception {
        String relPath = appClass.replace(".", "/") + ".smali";
        File smaliFile = findSmaliFile(decompDir, relPath);
        if (smaliFile == null) throw new Exception("Smali not found: " + appClass);

        String content = readFile(smaliFile);
        String initCall = "    invoke-static {p0}, Lcom/adsweep/AdSweep;->init(Landroid/content/Context;)V";

        if (content.contains("Lcom/adsweep/AdSweep;->init")) {
            progress("Already injected, skipping");
            return;
        }

        // Find onCreate and inject after .locals/.registers
        Pattern p = Pattern.compile(
                "(\\.method\\s+public\\s+onCreate\\(\\)V\\s*\\n(?:.*\\n)*?\\s*\\.(?:locals|registers)\\s+\\d+)");
        Matcher m = p.matcher(content);
        if (m.find()) {
            int pos = m.end();
            content = content.substring(0, pos) + "\n\n" + initCall + "\n" + content.substring(pos);
            writeFile(smaliFile, content);
            progress("Injected into " + appClass);
        } else {
            throw new Exception("Could not find onCreate() in " + appClass);
        }
    }

    // --- Copy payload from Manager's assets ---

    private void copyPayload(File decompDir) throws Exception {
        AssetManager assets = context.getAssets();

        // Find next smali_classesN
        int nextNum = 2;
        for (File f : decompDir.listFiles()) {
            if (f.getName().startsWith("smali_classes")) {
                try {
                    int n = Integer.parseInt(f.getName().replace("smali_classes", ""));
                    nextNum = Math.max(nextNum, n + 1);
                } catch (NumberFormatException e) {}
            }
        }

        // Convert DEX to smali using baksmali
        File dexFile = new File(context.getCacheDir(), "adsweep_payload.dex");
        copyAsset(assets, "payload/classes.dex", dexFile);

        File smaliOutDir = new File(decompDir, "smali_classes" + nextNum);
        smaliOutDir.mkdirs();

        // Run baksmali
        progress("Converting DEX to smali (classes" + nextNum + ")...");
        runBaksmali(dexFile, smaliOutDir);

        // Copy .so files
        for (String abi : new String[]{"arm64-v8a", "armeabi-v7a"}) {
            File libDir = new File(decompDir, "lib/" + abi);
            libDir.mkdirs();
            for (String soName : assets.list("payload/lib/" + abi)) {
                copyAsset(assets, "payload/lib/" + abi + "/" + soName, new File(libDir, soName));
            }
        }

        // Copy assets (rules + domains)
        File assetsDir = new File(decompDir, "assets");
        assetsDir.mkdirs();
        for (String name : new String[]{"adsweep_rules_common.json", "adsweep_domains.txt"}) {
            copyAsset(assets, "payload/" + name, new File(assetsDir, name));
        }
    }

    private void runBaksmali(File dexFile, File outputDir) throws Exception {
        // Use baksmali library API
        com.android.tools.smali.baksmali.Baksmali.disassembleDexFile(
                com.android.tools.smali.dexlib2.DexFileFactory.loadDexFile(
                        dexFile, com.android.tools.smali.dexlib2.Opcodes.getDefault()),
                outputDir,
                1, // threads
                new com.android.tools.smali.baksmali.BaksmaliOptions()
        );
    }

    // --- Patch apktool.yml ---

    private void patchApktoolYml(File decompDir) {
        File yml = new File(decompDir, "apktool.yml");
        if (!yml.exists()) return;
        try {
            String content = readFile(yml);
            if (!content.contains("- so") && content.contains("doNotCompress:")) {
                content = content.replace("doNotCompress:", "doNotCompress:\n- so");
                writeFile(yml, content);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to patch apktool.yml", e);
        }
    }

    // --- Sign APK ---

    private void signApk(File unsignedApk, File outputApk) throws Exception {
        // Generate or load a debug keystore
        File ksFile = new File(context.getFilesDir(), "debug.keystore");
        if (!ksFile.exists()) {
            generateDebugKeystore(ksFile);
        }

        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(new FileInputStream(ksFile), "android".toCharArray());
        PrivateKey key = (PrivateKey) ks.getKey("debugkey", "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate("debugkey");

        SignerConfig signerConfig = new SignerConfig.Builder(
                "debugkey", key, Collections.singletonList(cert)).build();

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build();
        signer.sign();
    }

    private void generateDebugKeystore(File ksFile) throws Exception {
        // Use keytool via Runtime (simplest approach on Android)
        String[] cmd = {
                "keytool", "-genkeypair", "-v",
                "-keystore", ksFile.getAbsolutePath(),
                "-alias", "debugkey",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000",
                "-storepass", "android",
                "-keypass", "android",
                "-dname", "CN=Debug, OU=Debug, O=Debug, L=Debug, ST=Debug, C=US"
        };
        Process p = Runtime.getRuntime().exec(cmd);
        p.waitFor();
    }

    // --- Utility methods ---

    private File findSmaliFile(File decompDir, String relPath) {
        File[] dirs = decompDir.listFiles((dir, name) -> name.startsWith("smali"));
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File dir : dirs) {
                File f = new File(dir, relPath);
                if (f.exists()) return f;
            }
        }
        return null;
    }

    private void copyAsset(AssetManager assets, String src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        InputStream is = assets.open(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
        fos.close();
        is.close();
    }

    private void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        FileInputStream fis = new FileInputStream(src);
        FileOutputStream fos = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int len;
        while ((len = fis.read(buf)) > 0) fos.write(buf, 0, len);
        fos.close();
        fis.close();
    }

    private String readFile(File f) throws IOException {
        BufferedReader r = new BufferedReader(new FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        r.close();
        return sb.toString();
    }

    private void writeFile(File f, String content) throws IOException {
        FileWriter w = new FileWriter(f);
        w.write(content);
        w.close();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    private void progress(String msg) {
        Log.i(TAG, msg);
        callback.onProgress(msg);
    }
}

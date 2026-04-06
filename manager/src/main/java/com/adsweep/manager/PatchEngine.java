package com.adsweep.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.util.Log;

import com.android.apksig.ApkSigner;

import java.io.*;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;

/**
 * On-device APK patching engine.
 * Uses direct DEX bytecode manipulation (dexlib2) instead of apktool/smali.
 * No decompilation needed — works directly on the APK zip.
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

    public static void initForAndroid(Context ctx) {
        if (System.getProperty("os.name") == null)
            System.setProperty("os.name", "Linux");
        if (System.getProperty("sun.arch.data.model") == null)
            System.setProperty("sun.arch.data.model", "64");
        if (System.getProperty("user.home") == null)
            System.setProperty("user.home", ctx.getFilesDir().getAbsolutePath());
    }

    public PatchEngine(Context context, ProgressCallback callback) {
        this.context = context;
        this.callback = callback;
    }

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
        File outputApk = new File(context.getFilesDir(), "patched/patched.apk");
        outputApk.getParentFile().mkdirs();
        deleteRecursive(workDir);
        workDir.mkdirs();

        // Step 1: Find Application class from PackageManager
        progress("Reading APK info...");
        String appClass = getApplicationClass(inputApk);
        if (appClass == null) {
            callback.onError("Could not find Application class");
            return;
        }
        progress("Application: " + appClass);

        // Step 2: Find which DEX contains the Application class and patch it
        progress("Patching DEX bytecode...");
        Map<String, byte[]> patchedDexes = patchDexFiles(inputApk, appClass, workDir);
        if (patchedDexes == null) {
            callback.onError("Failed to patch DEX");
            return;
        }

        // Step 3: Build patched APK
        progress("Building patched APK...");
        File unsignedApk = new File(workDir, "unsigned.apk");
        buildPatchedApk(inputApk, patchedDexes, appRules, unsignedApk);

        // Step 4: Sign
        progress("Signing APK...");
        signApk(unsignedApk, outputApk);

        deleteRecursive(workDir);
        progress("Done!");
        callback.onComplete(outputApk);
    }

    // --- Get Application class ---

    private String getApplicationClass(File apkFile) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (info != null && info.applicationInfo != null && info.applicationInfo.className != null) {
                return info.applicationInfo.className;
            }
        } catch (Exception e) {
            Log.w(TAG, "PackageManager failed", e);
        }
        return null;
    }

    // --- Patch DEX files ---

    private Map<String, byte[]> patchDexFiles(File apkFile, String appClass, File workDir) throws Exception {
        Map<String, byte[]> result = new HashMap<>();
        boolean patched = false;

        try (ZipFile zip = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes\\d*\\.dex")) continue;

                // Extract DEX to temp file
                File tempDex = new File(workDir, entry.getName());
                extractEntry(zip, entry, tempDex);

                if (!patched) {
                    // Try to patch this DEX
                    File patchedDex = new File(workDir, entry.getName() + ".patched");
                    if (DexPatcher.patchDex(tempDex, appClass, patchedDex)) {
                        result.put(entry.getName(), readBytes(patchedDex));
                        patched = true;
                        progress("Patched " + entry.getName());
                        patchedDex.delete();
                    } else {
                        result.put(entry.getName(), readBytes(tempDex));
                    }
                } else {
                    result.put(entry.getName(), readBytes(tempDex));
                }
                tempDex.delete();
            }
        }

        if (!patched) {
            progress("Application class not found in any DEX");
            return null;
        }

        // Add AdSweep payload DEX
        int nextNum = result.size() + 1;
        String payloadName = "classes" + nextNum + ".dex";
        File payloadDex = new File(workDir, "payload.dex");
        copyAsset(context.getAssets(), "payload/classes.dex", payloadDex);
        result.put(payloadName, readBytes(payloadDex));
        payloadDex.delete();
        progress("Added " + payloadName);

        return result;
    }

    // --- Build patched APK ---

    private void buildPatchedApk(File originalApk, Map<String, byte[]> dexes,
                                  File appRules, File outputApk) throws Exception {
        AssetManager assets = context.getAssets();

        try (ZipFile src = new ZipFile(originalApk);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputApk))) {

            Set<String> written = new HashSet<>();

            // Copy original entries (skip DEX files)
            Enumeration<? extends ZipEntry> entries = src.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().matches("classes\\d*\\.dex")) continue;
                if (entry.getName().startsWith("META-INF/")) continue; // Will be re-signed

                zos.putNextEntry(new ZipEntry(entry.getName()));
                InputStream is = src.getInputStream(entry);
                copyStream(is, zos);
                is.close();
                zos.closeEntry();
                written.add(entry.getName());
            }

            // Write patched DEX files
            for (Map.Entry<String, byte[]> dex : dexes.entrySet()) {
                zos.putNextEntry(new ZipEntry(dex.getKey()));
                zos.write(dex.getValue());
                zos.closeEntry();
            }

            // Add .so files
            for (String abi : new String[]{"arm64-v8a", "armeabi-v7a"}) {
                for (String soName : assets.list("payload/lib/" + abi)) {
                    String entryName = "lib/" + abi + "/" + soName;
                    if (!written.contains(entryName)) {
                        zos.putNextEntry(new ZipEntry(entryName));
                        InputStream is = assets.open("payload/lib/" + abi + "/" + soName);
                        copyStream(is, zos);
                        is.close();
                        zos.closeEntry();
                    }
                }
            }

            // Add assets
            for (String name : new String[]{"adsweep_rules_common.json", "adsweep_domains.txt"}) {
                String entryName = "assets/" + name;
                if (!written.contains(entryName)) {
                    zos.putNextEntry(new ZipEntry(entryName));
                    InputStream is = assets.open("payload/" + name);
                    copyStream(is, zos);
                    is.close();
                    zos.closeEntry();
                }
            }

            // Add app rules if provided
            if (appRules != null && appRules.exists()) {
                zos.putNextEntry(new ZipEntry("assets/adsweep_rules_app.json"));
                FileInputStream fis = new FileInputStream(appRules);
                copyStream(fis, zos);
                fis.close();
                zos.closeEntry();
            }
        }
    }

    // --- Sign APK ---

    private void signApk(File unsignedApk, File outputApk) throws Exception {
        // Generate key pair + self-signed cert on the fly
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair kp = kpg.generateKeyPair();

        // Self-signed cert via Android KeyStore API
        java.security.cert.X509Certificate cert = createSelfSignedCert(kp);

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "adsweep", kp.getPrivate(), Collections.singletonList(cert)).build();

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build();
        signer.sign();
    }

    private java.security.cert.X509Certificate createSelfSignedCert(java.security.KeyPair kp) throws Exception {
        // Build a minimal self-signed X.509 v1 certificate manually
        // using Android's Conscrypt/BouncyCastle runtime classes

        long now = System.currentTimeMillis();
        java.util.Date notBefore = new java.util.Date(now);
        java.util.Date notAfter = new java.util.Date(now + 10L * 365 * 24 * 60 * 60 * 1000);

        // Use Android's internal sun.security.x509 classes (available on all Android versions)
        Class<?> x500NameClass = Class.forName("sun.security.x509.X500Name");
        Object issuer = x500NameClass.getConstructor(String.class).newInstance("CN=AdSweep");

        Class<?> certInfoClass = Class.forName("sun.security.x509.X509CertInfo");
        Object certInfo = certInfoClass.getConstructor().newInstance();

        // Set validity
        Class<?> validityClass = Class.forName("sun.security.x509.CertificateValidity");
        Object validity = validityClass.getConstructor(java.util.Date.class, java.util.Date.class)
                .newInstance(notBefore, notAfter);

        // Set serial number
        Class<?> serialClass = Class.forName("sun.security.x509.CertificateSerialNumber");
        Object serial = serialClass.getConstructor(int.class).newInstance((int)(now / 1000));

        // Set algorithm
        Class<?> algoIdClass = Class.forName("sun.security.x509.AlgorithmId");
        Object sha256rsa = algoIdClass.getField("sha256WithRSAEncryption_oid").get(null);
        Class<?> certAlgoClass = Class.forName("sun.security.x509.CertificateAlgorithmId");
        Object certAlgo = certAlgoClass.getConstructor(algoIdClass).newInstance(
                algoIdClass.getConstructor(Class.forName("sun.security.util.ObjectIdentifier"))
                        .newInstance(sha256rsa));

        // Set subject/issuer/key/version
        Class<?> certVersionClass = Class.forName("sun.security.x509.CertificateVersion");
        Class<?> certKeyClass = Class.forName("sun.security.x509.CertificateX509Key");
        Class<?> certSubjClass = Class.forName("sun.security.x509.CertificateSubjectName");
        Class<?> certIssuerClass = Class.forName("sun.security.x509.CertificateIssuerName");

        certInfoClass.getMethod("set", String.class, Object.class).invoke(certInfo, "validity", validity);
        certInfoClass.getMethod("set", String.class, Object.class).invoke(certInfo, "serialNumber", serial);
        certInfoClass.getMethod("set", String.class, Object.class).invoke(certInfo, "algorithmID", certAlgo);
        certInfoClass.getMethod("set", String.class, Object.class).invoke(certInfo, "subject",
                certSubjClass.getConstructor(x500NameClass).newInstance(issuer));
        certInfoClass.getMethod("set", String.class, Object.class).invoke(certInfo, "issuer",
                certIssuerClass.getConstructor(x500NameClass).newInstance(issuer));
        certInfoClass.getMethod("set", String.class, Object.class).invoke(certInfo, "key",
                certKeyClass.getConstructor(java.security.PublicKey.class).newInstance(kp.getPublic()));
        certInfoClass.getMethod("set", String.class, Object.class).invoke(certInfo, "version",
                certVersionClass.getConstructor(int.class).newInstance(2)); // v3

        // Create and sign
        Class<?> x509CertImplClass = Class.forName("sun.security.x509.X509CertImpl");
        Object cert = x509CertImplClass.getConstructor(certInfoClass).newInstance(certInfo);
        x509CertImplClass.getMethod("sign", PrivateKey.class, String.class)
                .invoke(cert, kp.getPrivate(), "SHA256withRSA");

        return (java.security.cert.X509Certificate) cert;
    }

    // --- Utilities ---

    private void extractEntry(ZipFile zip, ZipEntry entry, File dest) throws IOException {
        dest.getParentFile().mkdirs();
        InputStream is = zip.getInputStream(entry);
        FileOutputStream fos = new FileOutputStream(dest);
        copyStream(is, fos);
        is.close();
        fos.close();
    }

    private void copyAsset(AssetManager assets, String src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        InputStream is = assets.open(src);
        FileOutputStream fos = new FileOutputStream(dst);
        copyStream(is, fos);
        is.close();
        fos.close();
    }

    private byte[] readBytes(File f) throws IOException {
        FileInputStream fis = new FileInputStream(f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copyStream(fis, bos);
        fis.close();
        return bos.toByteArray();
    }

    private void copyStream(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private void progress(String msg) {
        Log.i(TAG, msg);
        callback.onProgress(msg);
    }
}

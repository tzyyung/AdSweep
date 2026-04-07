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

    public void patch(File inputApk, String packageName) {
        new Thread(() -> {
            try {
                // Fetch app-specific rules on background thread
                File appRules = null;
                if (packageName != null && !"unknown".equals(packageName)) {
                    progress("Fetching rules for " + packageName + "...");
                    appRules = RuleFetcher.fetch(packageName, context.getCacheDir());
                    if (appRules != null) {
                        progress("Rules downloaded");
                    } else {
                        progress("No app rules, using common rules");
                    }
                }
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
        // Clean old outputs
        if (outputApk.exists()) outputApk.delete();
        File debugApk = new File(context.getFilesDir(), "patched/unsigned_debug.apk");
        if (debugApk.exists()) debugApk.delete();
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
        Map<String, File> patchedDexes = patchDexFiles(inputApk, appClass, workDir);
        if (patchedDexes == null) {
            callback.onError("Failed to patch DEX");
            return;
        }

        // Step 3: Build patched APK
        progress("Building patched APK...");
        File unsignedApk = new File(workDir, "unsigned.apk");
        buildPatchedApk(inputApk, patchedDexes, appRules, unsignedApk);

        // Step 3b: Patch manifest (remove requiredSplitTypes, set extractNativeLibs)
        progress("Patching manifest...");
        ManifestPatcher.patch(unsignedApk);

        // Step 4: Sign
        progress("Signing APK...");
        signApk(context, unsignedApk, outputApk);

        // Debug: keep unsigned for verification
        // (TODO: remove in production)
        File debugUnsigned = new File(context.getFilesDir(), "patched/unsigned_debug.apk");
        if (unsignedApk.exists()) {
            FileInputStream debugFis = new FileInputStream(unsignedApk);
            FileOutputStream debugFos = new FileOutputStream(debugUnsigned);
            copyStream(debugFis, debugFos);
            debugFis.close();
            debugFos.close();
        }

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

    /**
     * Patch DEX files: extracts one at a time, patches the one containing the app class,
     * then writes patched files to workDir. Returns a map of entryName → File.
     * Uses file-based approach to avoid OOM from loading all DEXes into memory.
     */
    private Map<String, File> patchDexFiles(File apkFile, String appClass, File workDir) throws Exception {
        Map<String, File> result = new LinkedHashMap<>();
        boolean patched = false;
        int dexCount = 0;

        try (ZipFile zip = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().matches("classes\\d*\\.dex")) continue;
                dexCount++;

                File tempDex = new File(workDir, entry.getName());
                extractEntry(zip, entry, tempDex);

                if (!patched) {
                    File patchedDex = new File(workDir, entry.getName() + ".patched");
                    if (DexPatcher.patchDex(tempDex, appClass, patchedDex)) {
                        tempDex.delete();
                        result.put(entry.getName(), patchedDex);
                        patched = true;
                        progress("Patched " + entry.getName());
                    } else {
                        result.put(entry.getName(), tempDex);
                    }
                } else {
                    result.put(entry.getName(), tempDex);
                }
            }
        }

        if (!patched) {
            progress("Application class not found in any DEX");
            return null;
        }

        // Add AdSweep payload DEX
        int nextNum = dexCount + 1;
        String payloadName = "classes" + nextNum + ".dex";
        File payloadDex = new File(workDir, payloadName);
        copyAsset(context.getAssets(), "payload/classes.dex", payloadDex);
        result.put(payloadName, payloadDex);
        progress("Added " + payloadName);

        return result;
    }

    // --- Build patched APK ---

    /**
     * Returns true if the ZIP entry is an APK signature file that should be stripped.
     */
    static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String upper = name.toUpperCase();
        return upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC")
                || upper.equals("META-INF/MANIFEST.MF");
    }

    private void buildPatchedApk(File originalApk, Map<String, File> dexFiles,
                                  File appRules, File outputApk) throws Exception {
        AssetManager assets = context.getAssets();
        Set<String> skipNames = new HashSet<>(dexFiles.keySet());

        try (ZipFile src = new ZipFile(originalApk)) {
            org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zos =
                    new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(outputApk);

            Set<String> written = new HashSet<>();

            // Pass 1: copy original entries, preserving compression method
            Enumeration<? extends ZipEntry> entries = src.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (skipNames.contains(name)) continue;
                if (isSignatureFile(name)) continue;

                copyEntry(zos, src, entry);
                written.add(name);
            }

            // Pass 2: write patched DEX files (STORED)
            for (Map.Entry<String, File> dex : dexFiles.entrySet()) {
                addStoredEntryFromFile(zos, dex.getKey(), dex.getValue());
            }

            // Pass 3: add payload files (STORED, only if not already in APK)
            for (String abi : new String[]{"arm64-v8a", "armeabi-v7a"}) {
                for (String soName : assets.list("payload/lib/" + abi)) {
                    String entryName = "lib/" + abi + "/" + soName;
                    if (!written.contains(entryName)) {
                        byte[] data = readAssetBytes(assets, "payload/lib/" + abi + "/" + soName);
                        addStoredEntry(zos, entryName, data);
                        written.add(entryName);
                    }
                }
            }

            for (String name : new String[]{"adsweep_rules_common.json", "adsweep_domains.txt"}) {
                String entryName = "assets/" + name;
                if (!written.contains(entryName)) {
                    byte[] data = readAssetBytes(assets, "payload/" + name);
                    addStoredEntry(zos, entryName, data);
                    written.add(entryName);
                }
            }

            if (appRules != null && appRules.exists()) {
                addStoredEntryFromFile(zos, "assets/adsweep_rules_app.json", appRules);
            }

            zos.close();
        }
    }

    /**
     * Copy a ZIP entry preserving its original compression method.
     */
    private void copyEntry(org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zos,
                           ZipFile src, ZipEntry entry) throws Exception {
        String name = entry.getName();
        InputStream is = src.getInputStream(entry);

        if (entry.getMethod() == ZipEntry.STORED) {
            // STORED: need CRC and sizes
            File tmp = File.createTempFile("entry_", null, context.getCacheDir());
            try {
                CRC32 crc = new CRC32();
                long size = 0;
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                        crc.update(buf, 0, len);
                        size += len;
                    }
                }
                is.close();

                org.apache.commons.compress.archivers.zip.ZipArchiveEntry out =
                        new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(name);
                out.setMethod(ZipEntry.STORED);
                out.setSize(size);
                out.setCompressedSize(size);
                out.setCrc(crc.getValue());
                zos.putArchiveEntry(out);

                try (FileInputStream fis = new FileInputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
                }
                zos.closeArchiveEntry();
            } finally {
                tmp.delete();
            }
        } else {
            // DEFLATED: let ZOS recompress
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry out =
                    new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(name);
            out.setMethod(ZipEntry.DEFLATED);
            zos.putArchiveEntry(out);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) zos.write(buf, 0, len);
            is.close();
            zos.closeArchiveEntry();
        }
    }

    private void addStoredEntryFromFile(org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zos,
                                         String name, File file) throws Exception {
        long size = file.length();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) crc.update(buf, 0, len);
        }

        org.apache.commons.compress.archivers.zip.ZipArchiveEntry e =
                new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(name);
        e.setMethod(0); // STORED
        e.setSize(size);
        e.setCompressedSize(size);
        e.setCrc(crc.getValue());
        zos.putArchiveEntry(e);

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) zos.write(buf, 0, len);
        }
        zos.closeArchiveEntry();
    }

    private void addStoredEntry(org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zos,
                                 String name, byte[] data) throws Exception {
        Log.d(TAG, "addStoredEntry: " + name + " size=" + data.length
                + " STORED_const=" + org.apache.commons.compress.archivers.zip.ZipArchiveEntry.STORED);
        org.apache.commons.compress.archivers.zip.ZipArchiveEntry e =
                new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(name);
        e.setMethod(0); // 0 = STORED, use literal to avoid constant confusion
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        e.setCrc(calcCrc32(data));
        zos.putArchiveEntry(e);
        zos.write(data);
        zos.closeArchiveEntry();
    }

    private long calcCrc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }

    // --- Sign APK ---

    /**
     * JAR sign an APK: compute digests, create MANIFEST.MF + CERT.SF + CERT.RSA,
     * add them to the ZIP without rewriting existing entries.
     */
    private void jarSign(File apkFile) throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        InputStream ksIs = context.getAssets().open("debug.p12");
        ks.load(ksIs, "android".toCharArray());
        ksIs.close();

        PrivateKey key = (PrivateKey) ks.getKey("debugkey", "android".toCharArray());
        java.security.cert.X509Certificate cert =
                (java.security.cert.X509Certificate) ks.getCertificate("debugkey");

        // Build MANIFEST.MF with SHA-256 digests of all entries
        StringBuilder mf = new StringBuilder();
        mf.append("Manifest-Version: 1.0\r\nCreated-By: AdSweep\r\n\r\n");

        java.util.LinkedHashMap<String, String> entryDigests = new java.util.LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                byte[] data = readStreamBytes(zf.getInputStream(entry));
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                String digest = android.util.Base64.encodeToString(md.digest(data), android.util.Base64.NO_WRAP);
                entryDigests.put(entry.getName(), digest);
                mf.append("Name: ").append(entry.getName()).append("\r\n");
                mf.append("SHA-256-Digest: ").append(digest).append("\r\n\r\n");
            }
        }
        byte[] mfBytes = mf.toString().getBytes("UTF-8");

        // Build CERT.SF
        java.security.MessageDigest mfMd = java.security.MessageDigest.getInstance("SHA-256");
        String mfDigest = android.util.Base64.encodeToString(mfMd.digest(mfBytes), android.util.Base64.NO_WRAP);
        StringBuilder sf = new StringBuilder();
        sf.append("Signature-Version: 1.0\r\n");
        sf.append("SHA-256-Digest-Manifest: ").append(mfDigest).append("\r\n");
        sf.append("Created-By: AdSweep\r\n\r\n");
        byte[] sfBytes = sf.toString().getBytes("UTF-8");

        // Build CERT.RSA (PKCS7 signature block)
        byte[] rsaBytes = createPkcs7Signature(sfBytes, key, cert);

        // Add META-INF entries to APK (append to ZIP)
        File tmpApk = new File(apkFile.getParent(), "signed.apk");
        try (ZipFile src = new ZipFile(apkFile);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmpApk))) {

            // Copy all existing entries preserving format
            Enumeration<? extends ZipEntry> entries = src.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] data = readStreamBytes(src.getInputStream(entry));
                ZipEntry newEntry = new ZipEntry(entry.getName());
                newEntry.setMethod(ZipEntry.STORED);
                newEntry.setSize(data.length);
                newEntry.setCompressedSize(data.length);
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data);
                newEntry.setCrc(crc.getValue());
                zos.putNextEntry(newEntry);
                zos.write(data);
                zos.closeEntry();
            }

            // Add META-INF
            writeStoredEntry(zos, "META-INF/MANIFEST.MF", mfBytes);
            writeStoredEntry(zos, "META-INF/CERT.SF", sfBytes);
            writeStoredEntry(zos, "META-INF/CERT.RSA", rsaBytes);
        }
        apkFile.delete();
        tmpApk.renameTo(apkFile);
    }

    private void writeStoredEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private byte[] createPkcs7Signature(byte[] sfData, PrivateKey key,
                                         java.security.cert.X509Certificate cert) throws Exception {
        // Sign the SF data
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(sfData);
        byte[] signature = sig.sign();

        // Build a minimal PKCS#7 SignedData DER structure
        // This is a simplified version that Android's v1 verifier accepts
        byte[] certBytes = cert.getEncoded();

        // ASN.1 DER encoding of PKCS7 SignedData
        ByteArrayOutputStream der = new ByteArrayOutputStream();

        // ContentInfo SEQUENCE
        byte[] oid_signedData = {0x06, 0x09, 0x2A, (byte)0x86, 0x48, (byte)0x86, (byte)0xF7, 0x0D, 0x01, 0x07, 0x02};
        byte[] oid_data = {0x06, 0x09, 0x2A, (byte)0x86, 0x48, (byte)0x86, (byte)0xF7, 0x0D, 0x01, 0x07, 0x01};
        byte[] oid_sha256 = {0x06, 0x09, 0x60, (byte)0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01};
        byte[] oid_rsaEncryption = {0x06, 0x09, 0x2A, (byte)0x86, 0x48, (byte)0x86, (byte)0xF7, 0x0D, 0x01, 0x01, 0x01};

        // Build SignerInfo
        byte[] issuerAndSerial = buildIssuerAndSerial(cert);
        byte[] digestAlg = wrapSequence(concat(oid_sha256, new byte[]{0x05, 0x00}));
        byte[] encryptionAlg = wrapSequence(concat(oid_rsaEncryption, new byte[]{0x05, 0x00}));
        byte[] signerInfo = wrapSequence(concat(
            new byte[]{0x02, 0x01, 0x01}, // version 1
            issuerAndSerial,
            digestAlg,
            encryptionAlg,
            wrapOctetString(signature)
        ));

        // Build SignedData
        byte[] digestAlgSet = wrapSet(digestAlg);
        byte[] contentInfo = wrapSequence(oid_data);
        byte[] certSet = wrapContextTag(0, certBytes);
        byte[] signerInfoSet = wrapSet(signerInfo);

        byte[] signedData = wrapSequence(concat(
            new byte[]{0x02, 0x01, 0x01}, // version 1
            digestAlgSet,
            contentInfo,
            certSet,
            signerInfoSet
        ));

        byte[] contentInfoOuter = wrapSequence(concat(
            oid_signedData,
            wrapContextTag(0, signedData)
        ));

        return contentInfoOuter;
    }

    private byte[] buildIssuerAndSerial(java.security.cert.X509Certificate cert) {
        try {
            byte[] issuer = cert.getIssuerX500Principal().getEncoded();
            byte[] serial = cert.getSerialNumber().toByteArray();
            // DER INTEGER: tag(02) + length + value
            byte[] serialTlv = wrapTag(0x02, serial);
            // IssuerAndSerialNumber: SEQUENCE { issuer, serialNumber }
            return wrapSequence(concat(issuer, serialTlv));
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private byte[] wrapSequence(byte[] content) { return wrapTag(0x30, content); }
    private byte[] wrapSet(byte[] content) { return wrapTag(0x31, content); }
    private byte[] wrapOctetString(byte[] content) { return wrapTag(0x04, content); }
    private byte[] wrapContextTag(int num, byte[] content) { return wrapTag(0xA0 | num, content); }

    private byte[] wrapTag(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private void writeLength(ByteArrayOutputStream out, int len) {
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else if (len < 65536) {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write((len >> 16) & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
    }

    private byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) out.write(a, 0, a.length);
        return out.toByteArray();
    }

    /**
     * Fix APK compression: take the unsigned APK (correct STORED format) and
     * copy META-INF from the signed APK into it.
     * This preserves the original ZIP structure while adding the signature.
     */
    private void fixApkCompression(File signedApk, File unsignedApk, File outputApk) throws Exception {
        // Extract META-INF entries from signed APK
        Map<String, byte[]> metaInf = new HashMap<>();
        try (ZipFile signed = new ZipFile(signedApk)) {
            Enumeration<? extends ZipEntry> entries = signed.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/")) {
                    metaInf.put(entry.getName(), readStreamBytes(signed.getInputStream(entry)));
                }
            }
        }

        // Copy unsigned APK + add META-INF
        try (ZipFile src = new ZipFile(unsignedApk);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputApk))) {

            // Copy all entries from unsigned (preserving compression)
            Enumeration<? extends ZipEntry> entries = src.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/")) continue;

                if (entry.getMethod() == ZipEntry.STORED) {
                    byte[] data = readStreamBytes(src.getInputStream(entry));
                    ZipEntry newEntry = new ZipEntry(entry.getName());
                    newEntry.setMethod(ZipEntry.STORED);
                    newEntry.setSize(data.length);
                    newEntry.setCompressedSize(data.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(data);
                    newEntry.setCrc(crc.getValue());
                    zos.putNextEntry(newEntry);
                    zos.write(data);
                } else {
                    zos.putNextEntry(new ZipEntry(entry.getName()));
                    InputStream is = src.getInputStream(entry);
                    copyStream(is, zos);
                    is.close();
                }
                zos.closeEntry();
            }

            // Add META-INF from signed APK
            for (Map.Entry<String, byte[]> mi : metaInf.entrySet()) {
                zos.putNextEntry(new ZipEntry(mi.getKey()));
                zos.write(mi.getValue());
                zos.closeEntry();
            }
        }
    }

    /**
     * Sign APK in-place using jarsigner-style v1 signing.
     * This preserves the ZIP structure (unlike ApkSigner which rewrites everything).
     */
    private void signApkInPlace(File apkFile) throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
        InputStream ksStream = context.getAssets().open("debug.p12");
        ks.load(ksStream, "android".toCharArray());
        ksStream.close();

        PrivateKey key = (PrivateKey) ks.getKey("debugkey", "android".toCharArray());
        java.security.cert.X509Certificate cert =
                (java.security.cert.X509Certificate) ks.getCertificate("debugkey");

        // Use jarsigner approach: create META-INF/MANIFEST.MF, CERT.SF, CERT.RSA
        // Then add them to the APK zip
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Created-By", "AdSweep Manager");

        // Read all entries and compute digests
        java.util.Map<String, String> digests = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(apkFile)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/")) continue;
                byte[] data = readStreamBytes(zf.getInputStream(entry));
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                String digest = android.util.Base64.encodeToString(md.digest(data), android.util.Base64.NO_WRAP);
                digests.put(entry.getName(), digest);
                java.util.jar.Attributes attr = new java.util.jar.Attributes();
                attr.putValue("SHA-256-Digest", digest);
                manifest.getEntries().put(entry.getName(), attr);
            }
        }

        // Write MANIFEST.MF
        ByteArrayOutputStream mfBos = new ByteArrayOutputStream();
        manifest.write(mfBos);
        byte[] mfBytes = mfBos.toByteArray();

        // Create CERT.SF (signature file)
        java.security.MessageDigest sfMd = java.security.MessageDigest.getInstance("SHA-256");
        String mfDigest = android.util.Base64.encodeToString(sfMd.digest(mfBytes), android.util.Base64.NO_WRAP);
        StringBuilder sfBuilder = new StringBuilder();
        sfBuilder.append("Signature-Version: 1.0\r\n");
        sfBuilder.append("Created-By: AdSweep Manager\r\n");
        sfBuilder.append("SHA-256-Digest-Manifest: ").append(mfDigest).append("\r\n");
        sfBuilder.append("\r\n");
        byte[] sfBytes = sfBuilder.toString().getBytes();

        // Create CERT.RSA (PKCS7 signature)
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(sfBytes);
        byte[] sigBytes = sig.sign();

        // Build PKCS7 SignedData structure
        byte[] certEncoded = cert.getEncoded();
        // Simplified: just write raw signature (v1 signature validation is lenient)
        // For proper v1 signing, we'd need full PKCS7/CMS
        // Use ApkSigner but with outputApk == inputApk to force in-place
        // Actually let's just use ApkSigner properly

        // Fallback: use ApkSigner but accept the rewriting
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "debugkey", key, Collections.singletonList(cert)).build();
        File tmpOut = new File(apkFile.getParent(), "signed_tmp.apk");
        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(apkFile)
                .setOutputApk(tmpOut)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setAlignmentPreserved(true)
                .build();
        signer.sign();
        apkFile.delete();
        tmpOut.renameTo(apkFile);
    }

    /**
     * Sign an APK with the bundled debug key. Public static so CommandReceiver
     * can re-sign split APKs with the same key.
     */
    public static void signApk(Context ctx, File inputApk, File outputApk) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream ksStream = ctx.getAssets().open("debug.p12");
        ks.load(ksStream, "android".toCharArray());
        ksStream.close();

        PrivateKey key = (PrivateKey) ks.getKey("debugkey", "android".toCharArray());
        java.security.cert.X509Certificate cert =
                (java.security.cert.X509Certificate) ks.getCertificate("debugkey");

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "debugkey", key, Collections.singletonList(cert)).build();

        ApkSigner signer = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setAlignmentPreserved(false)
                .build();
        signer.sign();
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

    private byte[] readStreamBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        is.close();
        return bos.toByteArray();
    }

    private byte[] readAssetBytes(AssetManager assets, String path) throws IOException {
        InputStream is = assets.open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        is.close();
        return bos.toByteArray();
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

package com.adsweep.patchtest;

import org.junit.Before;
import org.junit.Test;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

import static org.junit.Assert.*;

/**
 * Tests for ApkBuilder — validates general-purpose APK rebuilding.
 * Creates a synthetic test APK with mixed compression and META-INF entries,
 * then verifies the builder preserves everything correctly.
 */
public class ApkBuilderTest {

    private File testApk;
    private File outputDir;

    // Original entry map: name → (method, content)
    private Map<String, EntryInfo> originalEntries = new LinkedHashMap<>();

    static class EntryInfo {
        int method;
        byte[] content;
        EntryInfo(int method, byte[] content) {
            this.method = method;
            this.content = content;
        }
    }

    @Before
    public void setUp() throws Exception {
        outputDir = Files.createTempDirectory("apkbuilder_test").toFile();
        testApk = new File(outputDir, "test.apk");

        // Build a synthetic APK with mixed entries
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(testApk))) {
            // STORED entries (Android requires these uncompressed)
            addEntry(zos, "resources.arsc", ZipEntry.STORED, randomBytes(1024));
            addEntry(zos, "classes.dex", ZipEntry.STORED, randomBytes(2048));
            addEntry(zos, "classes2.dex", ZipEntry.STORED, randomBytes(1024));
            addEntry(zos, "AndroidManifest.xml", ZipEntry.STORED, randomBytes(512));

            // DEFLATED entries (normal compression)
            addEntry(zos, "res/layout/main.xml", ZipEntry.DEFLATED, randomBytes(300));
            addEntry(zos, "res/drawable/icon.png", ZipEntry.DEFLATED, randomBytes(500));
            addEntry(zos, "res/values/strings.xml", ZipEntry.DEFLATED, randomBytes(200));

            // META-INF signature files (should be stripped)
            addEntry(zos, "META-INF/MANIFEST.MF", ZipEntry.DEFLATED, "Manifest-Version: 1.0\r\n".getBytes());
            addEntry(zos, "META-INF/CERT.SF", ZipEntry.DEFLATED, "Signature-Version: 1.0\r\n".getBytes());
            addEntry(zos, "META-INF/CERT.RSA", ZipEntry.STORED, randomBytes(100));

            // META-INF non-signature files (MUST be preserved)
            addEntry(zos, "META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory",
                    ZipEntry.DEFLATED, "kotlinx.coroutines.android.AndroidDispatcherFactory".getBytes());
            addEntry(zos, "META-INF/services/com.google.protobuf.GeneratedExtensionRegistryLoader",
                    ZipEntry.DEFLATED, "com.example.ExtLoader".getBytes());

            // Native lib (STORED)
            addEntry(zos, "lib/arm64-v8a/libnative.so", ZipEntry.STORED, randomBytes(800));
        }
    }

    private void addEntry(ZipOutputStream zos, String name, int method, byte[] content) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            CRC32 crc = new CRC32();
            crc.update(content);
            entry.setCrc(crc.getValue());
        }
        zos.putNextEntry(entry);
        zos.write(content);
        zos.closeEntry();
        originalEntries.put(name, new EntryInfo(method, content));
    }

    private byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data); // deterministic for reproducibility
        return data;
    }

    // ===================== isSignatureFile tests =====================

    @Test
    public void testIsSignatureFile_signatureFiles() {
        assertTrue(ApkBuilder.isSignatureFile("META-INF/MANIFEST.MF"));
        assertTrue(ApkBuilder.isSignatureFile("META-INF/CERT.SF"));
        assertTrue(ApkBuilder.isSignatureFile("META-INF/CERT.RSA"));
        assertTrue(ApkBuilder.isSignatureFile("META-INF/CERT.DSA"));
        assertTrue(ApkBuilder.isSignatureFile("META-INF/CERT.EC"));
        assertTrue(ApkBuilder.isSignatureFile("META-INF/DEBUGKEY.SF"));
        assertTrue(ApkBuilder.isSignatureFile("META-INF/DEBUGKEY.RSA"));
    }

    @Test
    public void testIsSignatureFile_nonSignatureFiles() {
        assertFalse(ApkBuilder.isSignatureFile("META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory"));
        assertFalse(ApkBuilder.isSignatureFile("META-INF/services/com.google.protobuf.GeneratedExtensionRegistryLoader"));
        assertFalse(ApkBuilder.isSignatureFile("META-INF/com/android/build/gradle/app-metadata.properties"));
        assertFalse(ApkBuilder.isSignatureFile("classes.dex"));
        assertFalse(ApkBuilder.isSignatureFile("res/layout/main.xml"));
        assertFalse(ApkBuilder.isSignatureFile("AndroidManifest.xml"));
    }

    // ===================== build() tests =====================

    @Test
    public void testBuild_preservesCompressionMethod() throws Exception {
        File patched = new File(outputDir, "patched.apk");
        ApkBuilder.build(testApk, new HashMap<>(), null, patched);

        try (ZipFile zf = new ZipFile(patched)) {
            for (Map.Entry<String, EntryInfo> orig : originalEntries.entrySet()) {
                String name = orig.getKey();
                if (ApkBuilder.isSignatureFile(name)) continue; // stripped
                if (name.matches("classes\\d*\\.dex")) continue; // replacement targets

                ZipEntry entry = zf.getEntry(name);
                assertNotNull("Entry should exist: " + name, entry);
                assertEquals("Compression method should match for: " + name,
                        orig.getValue().method, entry.getMethod());
            }
        }
    }

    @Test
    public void testBuild_preservesContent() throws Exception {
        File patched = new File(outputDir, "patched.apk");
        ApkBuilder.build(testApk, new HashMap<>(), null, patched);

        try (ZipFile zf = new ZipFile(patched)) {
            for (Map.Entry<String, EntryInfo> orig : originalEntries.entrySet()) {
                String name = orig.getKey();
                if (ApkBuilder.isSignatureFile(name)) continue;
                if (name.matches("classes\\d*\\.dex")) continue;

                ZipEntry entry = zf.getEntry(name);
                assertNotNull("Entry should exist: " + name, entry);
                byte[] content = readAll(zf.getInputStream(entry));
                assertArrayEquals("Content should match for: " + name,
                        orig.getValue().content, content);
            }
        }
    }

    @Test
    public void testBuild_stripsSignatureFiles() throws Exception {
        File patched = new File(outputDir, "patched.apk");
        ApkBuilder.build(testApk, new HashMap<>(), null, patched);

        try (ZipFile zf = new ZipFile(patched)) {
            assertNull("MANIFEST.MF should be stripped", zf.getEntry("META-INF/MANIFEST.MF"));
            assertNull("CERT.SF should be stripped", zf.getEntry("META-INF/CERT.SF"));
            assertNull("CERT.RSA should be stripped", zf.getEntry("META-INF/CERT.RSA"));
        }
    }

    @Test
    public void testBuild_preservesServiceLoaderFiles() throws Exception {
        File patched = new File(outputDir, "patched.apk");
        ApkBuilder.build(testApk, new HashMap<>(), null, patched);

        try (ZipFile zf = new ZipFile(patched)) {
            ZipEntry svc1 = zf.getEntry("META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory");
            assertNotNull("ServiceLoader file must be preserved", svc1);
            String content = new String(readAll(zf.getInputStream(svc1)));
            assertEquals("kotlinx.coroutines.android.AndroidDispatcherFactory", content);

            ZipEntry svc2 = zf.getEntry("META-INF/services/com.google.protobuf.GeneratedExtensionRegistryLoader");
            assertNotNull("Protobuf ServiceLoader file must be preserved", svc2);
        }
    }

    @Test
    public void testBuild_resourcesArscUnchanged() throws Exception {
        // Get original CRC
        long originalCrc;
        try (ZipFile zf = new ZipFile(testApk)) {
            originalCrc = zf.getEntry("resources.arsc").getCrc();
        }

        File patched = new File(outputDir, "patched.apk");
        ApkBuilder.build(testApk, new HashMap<>(), null, patched);

        try (ZipFile zf = new ZipFile(patched)) {
            ZipEntry arsc = zf.getEntry("resources.arsc");
            assertNotNull("resources.arsc must exist", arsc);
            assertEquals("resources.arsc must be STORED", ZipEntry.STORED, arsc.getMethod());
            assertEquals("resources.arsc CRC must match", originalCrc, arsc.getCrc());
        }
    }

    @Test
    public void testBuild_replacementsDexFiles() throws Exception {
        // Create replacement DEX files
        File fakeDex1 = new File(outputDir, "classes.dex");
        byte[] dex1Content = "patched_dex_1".getBytes();
        Files.write(fakeDex1.toPath(), dex1Content);

        File fakeDex2 = new File(outputDir, "classes2.dex");
        byte[] dex2Content = "patched_dex_2".getBytes();
        Files.write(fakeDex2.toPath(), dex2Content);

        Map<String, File> replacements = new LinkedHashMap<>();
        replacements.put("classes.dex", fakeDex1);
        replacements.put("classes2.dex", fakeDex2);

        // Add a payload DEX
        Map<String, byte[]> additions = new LinkedHashMap<>();
        byte[] payloadDex = "payload_dex".getBytes();
        additions.put("classes3.dex", payloadDex);

        File patched = new File(outputDir, "patched.apk");
        ApkBuilder.build(testApk, replacements, additions, patched);

        try (ZipFile zf = new ZipFile(patched)) {
            // Replaced DEX should have new content
            assertArrayEquals("classes.dex should be replaced", dex1Content,
                    readAll(zf.getInputStream(zf.getEntry("classes.dex"))));
            assertArrayEquals("classes2.dex should be replaced", dex2Content,
                    readAll(zf.getInputStream(zf.getEntry("classes2.dex"))));
            // Payload DEX should be added
            assertArrayEquals("classes3.dex should be added", payloadDex,
                    readAll(zf.getInputStream(zf.getEntry("classes3.dex"))));
            // All replacement/addition entries should be STORED
            assertEquals(ZipEntry.STORED, zf.getEntry("classes.dex").getMethod());
            assertEquals(ZipEntry.STORED, zf.getEntry("classes3.dex").getMethod());
        }
    }

    @Test
    public void testBuild_entryCount() throws Exception {
        // With empty replacements, only signature files are stripped (DEX copied through)
        File patched = new File(outputDir, "patched.apk");
        ApkBuilder.build(testApk, new HashMap<>(), null, patched);

        int originalNonSig = 0;
        for (String name : originalEntries.keySet()) {
            if (!ApkBuilder.isSignatureFile(name)) originalNonSig++;
        }

        int patchedCount = 0;
        try (ZipFile zf = new ZipFile(patched)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) { entries.nextElement(); patchedCount++; }
        }

        assertEquals("Entry count: original minus sig files", originalNonSig, patchedCount);
    }

    @Test
    public void testBuild_withRealApk() throws Exception {
        // Test with real Money Manager APK if available
        String realApkPath = System.getProperty("test.apk.path",
                "/Users/anson/incrte/expensetrackerapp/apk_output/base.apk");
        File realApk = new File(realApkPath);
        if (!realApk.exists()) {
            System.out.println("Real APK not found, skipping: " + realApkPath);
            return;
        }

        File patched = new File(outputDir, "real_patched.apk");
        ApkBuilder.build(realApk, new HashMap<>(), null, patched);

        try (ZipFile orig = new ZipFile(realApk);
             ZipFile patchedZf = new ZipFile(patched)) {

            // Count signature files in original
            int sigCount = 0;
            int dexCount = 0;
            int totalOrig = 0;
            Enumeration<? extends ZipEntry> origEntries = orig.entries();
            while (origEntries.hasMoreElements()) {
                ZipEntry e = origEntries.nextElement();
                totalOrig++;
                if (ApkBuilder.isSignatureFile(e.getName())) sigCount++;
                if (e.getName().matches("classes\\d*\\.dex")) dexCount++;
            }

            int totalPatched = 0;
            Enumeration<? extends ZipEntry> patchedEntries = patchedZf.entries();
            while (patchedEntries.hasMoreElements()) { patchedEntries.nextElement(); totalPatched++; }

            // With empty replacements, only sig files are stripped (DEX copied through)
            System.out.println("Real APK: " + totalOrig + " entries, " + sigCount + " sig files, " + dexCount + " DEX files");
            System.out.println("Patched:  " + totalPatched + " entries (expected " + (totalOrig - sigCount) + ")");
            assertEquals(totalOrig - sigCount, totalPatched);

            // Verify resources.arsc preserved
            ZipEntry origArsc = orig.getEntry("resources.arsc");
            ZipEntry patchedArsc = patchedZf.getEntry("resources.arsc");
            assertNotNull(patchedArsc);
            assertEquals("resources.arsc method", origArsc.getMethod(), patchedArsc.getMethod());
            assertEquals("resources.arsc CRC", origArsc.getCrc(), patchedArsc.getCrc());
            assertEquals("resources.arsc size", origArsc.getSize(), patchedArsc.getSize());

            // Verify META-INF/services preserved
            ZipEntry svc = patchedZf.getEntry("META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory");
            assertNotNull("ServiceLoader must be preserved in real APK", svc);

            // Verify all non-sig, non-dex entries have matching compression
            int mismatch = 0;
            origEntries = orig.entries();
            while (origEntries.hasMoreElements()) {
                ZipEntry oe = origEntries.nextElement();
                if (ApkBuilder.isSignatureFile(oe.getName())) continue;
                if (oe.getName().matches("classes\\d*\\.dex")) continue;
                ZipEntry pe = patchedZf.getEntry(oe.getName());
                if (pe == null) { mismatch++; continue; }
                if (oe.getMethod() != pe.getMethod()) {
                    System.out.println("MISMATCH: " + oe.getName() + " orig=" + oe.getMethod() + " patched=" + pe.getMethod());
                    mismatch++;
                }
            }
            assertEquals("All entries should have matching compression", 0, mismatch);
        }
    }

    private byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        is.close();
        return bos.toByteArray();
    }
}

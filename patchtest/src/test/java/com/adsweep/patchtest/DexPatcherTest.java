package com.adsweep.patchtest;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.*;

public class DexPatcherTest {

    private static final String APP_CLASS = "com.realbyte.money.application.RbApplication";
    private static final String APP_CLASS_TYPE = "Lcom/realbyte/money/application/RbApplication;";

    private File testDex;
    private File outputDir;

    @Before
    public void setUp() throws IOException {
        // Extract test DEX from resources
        InputStream is = getClass().getResourceAsStream("/test_classes5.dex");
        assertNotNull("test_classes5.dex not found in resources", is);

        outputDir = Files.createTempDirectory("dexpatcher_test").toFile();
        testDex = new File(outputDir, "input.dex");
        Files.copy(is, testDex.toPath(), StandardCopyOption.REPLACE_EXISTING);
        is.close();

        System.out.println("Test DEX: " + testDex.length() + " bytes");
    }

    @Test
    public void testOriginalDexContainsAppClass() throws Exception {
        DexFile dex = DexFileFactory.loadDexFile(testDex, Opcodes.getDefault());
        boolean found = false;
        int classCount = 0;
        for (ClassDef c : dex.getClasses()) {
            classCount++;
            if (c.getType().equals(APP_CLASS_TYPE)) {
                found = true;
            }
        }
        System.out.println("Original DEX: " + classCount + " classes");
        assertTrue("RbApplication should exist in original DEX", found);
    }

    @Test
    public void testPatchDexPool() throws Exception {
        File outputDex = new File(outputDir, "patched_pool.dex");

        boolean result = DexPatcher.patchDex(testDex, APP_CLASS, outputDex);
        assertTrue("patchDex should return true", result);
        assertTrue("Output DEX should exist", outputDex.exists());
        assertTrue("Output DEX should not be empty", outputDex.length() > 0);

        System.out.println("Patched DEX (DexPool): " + outputDex.length() + " bytes");

        // Verify the patched DEX can be loaded and contains RbApplication
        DexFile patched = DexFileFactory.loadDexFile(outputDex, Opcodes.getDefault());
        boolean found = false;
        int classCount = 0;
        for (ClassDef c : patched.getClasses()) {
            classCount++;
            if (c.getType().equals(APP_CLASS_TYPE)) {
                found = true;
            }
        }
        System.out.println("Patched DEX (DexPool): " + classCount + " classes");
        assertTrue("RbApplication should exist in patched DEX", found);
    }

    @Test
    public void testPatchDexV2() throws Exception {
        File outputDex = new File(outputDir, "patched_v2.dex");

        boolean result = DexPatcher.patchDexV2(testDex, APP_CLASS, outputDex);
        assertTrue("patchDexV2 should return true", result);
        assertTrue("Output DEX should exist", outputDex.exists());
        assertTrue("Output DEX should not be empty", outputDex.length() > 0);

        System.out.println("Patched DEX (v2): " + outputDex.length() + " bytes");

        // Verify the patched DEX can be loaded and contains RbApplication
        DexFile patched = DexFileFactory.loadDexFile(outputDex, Opcodes.getDefault());
        boolean found = false;
        int classCount = 0;
        for (ClassDef c : patched.getClasses()) {
            classCount++;
            if (c.getType().equals(APP_CLASS_TYPE)) {
                found = true;
            }
        }
        System.out.println("Patched DEX (v2): " + classCount + " classes");
        assertTrue("RbApplication should exist in patched DEX (v2)", found);
    }

    @Test
    public void testPatchedDexClassCountPreserved() throws Exception {
        // Load original
        DexFile original = DexFileFactory.loadDexFile(testDex, Opcodes.getDefault());
        int originalCount = 0;
        for (ClassDef c : original.getClasses()) originalCount++;

        // Patch with DexPool
        File outputPool = new File(outputDir, "patched_count_pool.dex");
        DexPatcher.patchDex(testDex, APP_CLASS, outputPool);
        DexFile patchedPool = DexFileFactory.loadDexFile(outputPool, Opcodes.getDefault());
        int poolCount = 0;
        for (ClassDef c : patchedPool.getClasses()) poolCount++;

        System.out.println("Class count - original: " + originalCount + ", DexPool: " + poolCount);
        assertEquals("DexPool should preserve class count", originalCount, poolCount);

        // Patch with v2
        File outputV2 = new File(outputDir, "patched_count_v2.dex");
        DexPatcher.patchDexV2(testDex, APP_CLASS, outputV2);
        DexFile patchedV2 = DexFileFactory.loadDexFile(outputV2, Opcodes.getDefault());
        int v2Count = 0;
        for (ClassDef c : patchedV2.getClasses()) v2Count++;

        System.out.println("Class count - original: " + originalCount + ", v2: " + v2Count);
        assertEquals("v2 should preserve class count", originalCount, v2Count);
    }

    @Test
    public void testPatchedDexValidWithDexdump() throws Exception {
        File outputDex = new File(outputDir, "patched_validate.dex");
        DexPatcher.patchDex(testDex, APP_CLASS, outputDex);

        // Try to verify with dexdump if available
        String dexdump = System.getenv("ANDROID_HOME") != null
                ? System.getenv("ANDROID_HOME") + "/build-tools/35.0.0/dexdump"
                : null;

        if (dexdump != null && new File(dexdump).exists()) {
            ProcessBuilder pb = new ProcessBuilder(dexdump, "-f", outputDex.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exitCode = p.waitFor();

            boolean hasError = output.contains("Failure to verify");
            System.out.println("dexdump exit: " + exitCode + ", verify error: " + hasError);
            if (hasError) {
                // Extract error line
                for (String line : output.split("\n")) {
                    if (line.contains("Failure") || line.contains("Error")) {
                        System.out.println("  " + line.trim());
                    }
                }
            }
            assertFalse("Patched DEX should pass dexdump verification", hasError);
        } else {
            System.out.println("dexdump not found, skipping validation. Set ANDROID_HOME.");
        }
    }
}

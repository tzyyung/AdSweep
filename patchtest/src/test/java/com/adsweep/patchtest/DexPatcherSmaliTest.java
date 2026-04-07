package com.adsweep.patchtest;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.*;

public class DexPatcherSmaliTest {

    private static final String APP_CLASS = "com.realbyte.money.application.RbApplication";
    private static final String APP_CLASS_TYPE = "Lcom/realbyte/money/application/RbApplication;";

    private File testDex;
    private File outputDir;

    @Before
    public void setUp() throws Exception {
        InputStream is = getClass().getResourceAsStream("/test_classes5.dex");
        assertNotNull("test_classes5.dex not found", is);

        outputDir = Files.createTempDirectory("dexpatcher_smali_test").toFile();
        testDex = new File(outputDir, "input.dex");
        Files.copy(is, testDex.toPath(), StandardCopyOption.REPLACE_EXISTING);
        is.close();
    }

    @Test
    public void testSmaliPatchProducesValidDex() throws Exception {
        File outputDex = new File(outputDir, "patched_smali.dex");
        boolean result = DexPatcherSmali.patchDex(testDex, APP_CLASS, outputDex);

        assertTrue("Patch should succeed", result);
        assertTrue("Output DEX should exist", outputDex.exists());
        assertTrue("Output DEX should not be empty", outputDex.length() > 0);

        // Load and verify
        DexFile dex = DexFileFactory.loadDexFile(outputDex, Opcodes.getDefault());
        boolean found = false;
        int classCount = 0;
        for (ClassDef c : dex.getClasses()) {
            classCount++;
            if (c.getType().equals(APP_CLASS_TYPE)) found = true;
        }
        System.out.println("Smali patched DEX: " + classCount + " classes, " + outputDex.length() + " bytes");
        assertTrue("RbApplication should exist", found);
    }

    @Test
    public void testSmaliPatchClassCountPreserved() throws Exception {
        // Original count
        DexFile original = DexFileFactory.loadDexFile(testDex, Opcodes.getDefault());
        int originalCount = 0;
        for (ClassDef c : original.getClasses()) originalCount++;

        // Patch
        File outputDex = new File(outputDir, "patched_count.dex");
        DexPatcherSmali.patchDex(testDex, APP_CLASS, outputDex);

        DexFile patched = DexFileFactory.loadDexFile(outputDex, Opcodes.getDefault());
        int patchedCount = 0;
        for (ClassDef c : patched.getClasses()) patchedCount++;

        System.out.println("Class count - original: " + originalCount + ", smali: " + patchedCount);
        assertEquals("Class count should be preserved", originalCount, patchedCount);
    }

    @Test
    public void testSmaliPatchDexdumpValid() throws Exception {
        File outputDex = new File(outputDir, "patched_validate.dex");
        DexPatcherSmali.patchDex(testDex, APP_CLASS, outputDex);

        String dexdump = System.getenv("ANDROID_HOME") != null
                ? System.getenv("ANDROID_HOME") + "/build-tools/35.0.0/dexdump"
                : null;

        if (dexdump != null && new File(dexdump).exists()) {
            ProcessBuilder pb = new ProcessBuilder(dexdump, "-f", outputDex.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();

            boolean hasError = output.contains("Failure to verify");
            if (hasError) {
                for (String line : output.split("\n")) {
                    if (line.contains("Failure")) System.out.println("  " + line.trim());
                }
            }
            assertFalse("Patched DEX should pass dexdump verification", hasError);
        } else {
            System.out.println("dexdump not found, skipping. Set ANDROID_HOME.");
        }
    }
}

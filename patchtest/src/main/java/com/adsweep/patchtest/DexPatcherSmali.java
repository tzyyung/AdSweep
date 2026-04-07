package com.adsweep.patchtest;

import com.android.tools.smali.baksmali.Baksmali;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;

import java.io.File;
import java.nio.file.Files;

/**
 * DEX patching using baksmali→patch→smali flow.
 * Avoids OOM from DexPool by working with text smali files.
 */
public class DexPatcherSmali {

    public static boolean patchDex(File dexFile, String applicationClass, File outputDex) throws Exception {
        // Step 1: baksmali
        File smaliDir = new File(dexFile.getParent(), "smali_tmp");
        if (smaliDir.exists()) deleteRecursive(smaliDir);
        smaliDir.mkdirs();

        Baksmali.disassembleDexFile(
                DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault()),
                smaliDir, 1, new BaksmaliOptions());

        // Step 2: Find and patch target smali
        String smaliPath = applicationClass.replace('.', '/') + ".smali";
        File smaliFile = new File(smaliDir, smaliPath);
        if (!smaliFile.exists()) {
            System.err.println("Smali file not found: " + smaliPath);
            return false;
        }

        String content = new String(Files.readAllBytes(smaliFile.toPath()));

        // Find onCreate method
        String marker = ".method public onCreate()V";
        int idx = content.indexOf(marker);
        if (idx < 0) {
            marker = ".method protected onCreate()V";
            idx = content.indexOf(marker);
        }
        if (idx < 0) {
            System.err.println("onCreate() not found");
            return false;
        }

        if (content.contains("Lcom/adsweep/AdSweep;->init")) {
            System.out.println("Already injected");
            return false;
        }

        // Find .locals or .registers line
        int afterMethod = content.indexOf('\n', idx) + 1;
        int localsLine = content.indexOf('\n', afterMethod) + 1;

        String injection = "    invoke-static {p0}, Lcom/adsweep/AdSweep;->init(Landroid/content/Context;)V\n";
        content = content.substring(0, localsLine) + injection + content.substring(localsLine);
        Files.write(smaliFile.toPath(), content.getBytes());

        System.out.println("Patched smali: " + smaliPath);

        // Step 3: smali back to DEX
        SmaliOptions options = new SmaliOptions();
        options.outputDexFile = outputDex.getAbsolutePath();
        options.apiLevel = 26;
        boolean success = Smali.assemble(options, smaliDir.getAbsolutePath());

        deleteRecursive(smaliDir);

        if (!success) {
            System.err.println("smali assembly failed");
            return false;
        }

        System.out.println("Output DEX: " + outputDex.length() + " bytes");
        return true;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}

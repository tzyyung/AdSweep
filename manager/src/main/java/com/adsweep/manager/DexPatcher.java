package com.adsweep.manager;

import android.util.Log;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Patches a DEX file to inject AdSweep.init() into Application.onCreate().
 * Uses dexlib2 for direct bytecode manipulation — no smali text involved.
 */
public class DexPatcher {

    private static final String TAG = "AdSweep.DexPatch";

    /**
     * Find and patch the DEX file containing the Application class.
     * Uses baksmali→patch→smali to avoid OOM from DexPool interning all classes.
     * Only the target class is modified; everything else is preserved.
     */
    public static boolean patchDex(File dexFile, String applicationClass, File outputDex) {
        try {
            String targetType = "L" + applicationClass.replace('.', '/') + ";";

            // Step 1: baksmali the entire DEX to smali files
            File smaliDir = new File(dexFile.getParent(), "smali_tmp");
            if (smaliDir.exists()) deleteRecursive(smaliDir);
            smaliDir.mkdirs();

            com.android.tools.smali.baksmali.Baksmali.disassembleDexFile(
                    DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault()),
                    smaliDir, 1, new com.android.tools.smali.baksmali.BaksmaliOptions());

            // Step 2: Find and patch the target smali file
            String smaliPath = applicationClass.replace('.', '/') + ".smali";
            File smaliFile = new File(smaliDir, smaliPath);
            if (!smaliFile.exists()) {
                Log.e(TAG, "Smali file not found: " + smaliPath);
                return false;
            }

            // Read and inject init call into onCreate
            String smaliContent = new String(java.nio.file.Files.readAllBytes(smaliFile.toPath()));
            String onCreateMarker = ".method public onCreate()V";
            int idx = smaliContent.indexOf(onCreateMarker);
            if (idx < 0) {
                // Try protected or other access
                onCreateMarker = ".method protected onCreate()V";
                idx = smaliContent.indexOf(onCreateMarker);
            }
            if (idx < 0) {
                // No onCreate() found — generate one
                Log.i(TAG, "No onCreate() found, generating one...");

                // Extract super class from .super directive
                String superClass = "Landroid/app/Application;";
                int superIdx = smaliContent.indexOf(".super ");
                if (superIdx >= 0) {
                    int superEnd = smaliContent.indexOf('\n', superIdx);
                    superClass = smaliContent.substring(superIdx + 7, superEnd).trim();
                }

                String newOnCreate = "\n.method public onCreate()V\n"
                        + "    .locals 0\n\n"
                        + "    invoke-super {p0}, " + superClass + "->onCreate()V\n\n"
                        + "    invoke-static {p0}, Lcom/adsweep/AdSweep;->init(Landroid/content/Context;)V\n\n"
                        + "    return-void\n"
                        + ".end method\n";

                smaliContent = smaliContent.trim() + "\n" + newOnCreate;
                java.nio.file.Files.write(smaliFile.toPath(), smaliContent.getBytes());
                Log.i(TAG, "Generated onCreate() with AdSweep.init()");
            } else {

            // Find .locals or .registers line after method declaration
            int afterMethod = smaliContent.indexOf('\n', idx) + 1;
            int localsLine = smaliContent.indexOf('\n', afterMethod) + 1;

            // Insert invoke-static after .locals/.registers line
            String injection = "    invoke-static {p0}, Lcom/adsweep/AdSweep;->init(Landroid/content/Context;)V\n";

            // Check if already injected — treat as success (copy original DEX)
            if (smaliContent.contains("Lcom/adsweep/AdSweep;->init")) {
                Log.i(TAG, "Already injected, copying original DEX");
                java.nio.file.Files.copy(dexFile.toPath(), outputDex.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                deleteRecursive(smaliDir);
                return true;
            }

            smaliContent = smaliContent.substring(0, localsLine) + injection + smaliContent.substring(localsLine);
            java.nio.file.Files.write(smaliFile.toPath(), smaliContent.getBytes());

            Log.i(TAG, "Patched smali: " + smaliPath);
            } // end else (existing onCreate)

            // Step 3: smali back to DEX
            com.android.tools.smali.smali.SmaliOptions options = new com.android.tools.smali.smali.SmaliOptions();
            options.outputDexFile = outputDex.getAbsolutePath();
            options.apiLevel = 26;
            boolean success = com.android.tools.smali.smali.Smali.assemble(options, smaliDir.getAbsolutePath());

            // Cleanup
            deleteRecursive(smaliDir);

            if (!success) {
                Log.e(TAG, "smali assembly failed");
                return false;
            }

            Log.i(TAG, "Patched: " + applicationClass);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "DEX patch failed", e);
            return false;
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /**
     * Strip debug info from all methods in a class to avoid DexPool string index issues.
     */
    private static ClassDef stripDebugInfo(ClassDef classDef) {
        List<Method> methods = new ArrayList<>();
        boolean changed = false;

        for (Method method : classDef.getMethods()) {
            MethodImplementation impl = method.getImplementation();
            if (impl != null && impl.getDebugItems().iterator().hasNext()) {
                ImmutableMethodImplementation stripped = new ImmutableMethodImplementation(
                        impl.getRegisterCount(),
                        impl.getInstructions(),
                        impl.getTryBlocks(),
                        Collections.emptyList()
                );
                methods.add(new ImmutableMethod(
                        method.getDefiningClass(),
                        method.getName(),
                        method.getParameters(),
                        method.getReturnType(),
                        method.getAccessFlags(),
                        method.getAnnotations(),
                        method.getHiddenApiRestrictions(),
                        stripped
                ));
                changed = true;
            } else {
                methods.add(method);
            }
        }

        if (!changed) return classDef;

        return new com.android.tools.smali.dexlib2.immutable.ImmutableClassDef(
                classDef.getType(),
                classDef.getAccessFlags(),
                classDef.getSuperclass(),
                classDef.getInterfaces(),
                classDef.getSourceFile(),
                classDef.getAnnotations(),
                classDef.getFields(),
                methods
        );
    }

    private static ClassDef patchApplicationClass(ClassDef classDef) {
        List<Method> methods = new ArrayList<>();
        boolean patched = false;

        for (Method method : classDef.getMethods()) {
            if (method.getName().equals("onCreate") &&
                method.getParameterTypes().isEmpty() &&
                method.getReturnType().equals("V")) {

                Method patchedMethod = injectInitCall(method);
                if (patchedMethod != null) {
                    methods.add(patchedMethod);
                    patched = true;
                    continue;
                }
            }
            methods.add(method);
        }

        if (!patched) return null;

        return new com.android.tools.smali.dexlib2.immutable.ImmutableClassDef(
                classDef.getType(),
                classDef.getAccessFlags(),
                classDef.getSuperclass(),
                classDef.getInterfaces(),
                classDef.getSourceFile(),
                classDef.getAnnotations(),
                classDef.getFields(),
                methods
        );
    }

    private static Method injectInitCall(Method original) {
        MethodImplementation impl = original.getImplementation();
        if (impl == null) return null;

        // Check if already injected
        for (Instruction inst : impl.getInstructions()) {
            if (inst.toString().contains("AdSweep")) {
                Log.i(TAG, "Already injected, skipping");
                return null;
            }
        }

        // Build: invoke-static {p0}, Lcom/adsweep/AdSweep;->init(Landroid/content/Context;)V
        ImmutableMethodReference initRef = new ImmutableMethodReference(
                "Lcom/adsweep/AdSweep;",
                "init",
                Collections.singletonList("Landroid/content/Context;"),
                "V"
        );

        ImmutableInstruction35c initCall = new ImmutableInstruction35c(
                com.android.tools.smali.dexlib2.Opcode.INVOKE_STATIC,
                1,    // register count
                0,    // p0 (this = Context)
                0, 0, 0, 0,
                initRef
        );

        List<Instruction> newInstructions = new ArrayList<>();
        newInstructions.add(initCall);
        for (Instruction inst : impl.getInstructions()) {
            newInstructions.add(inst);
        }

        int regCount = Math.max(impl.getRegisterCount(), 1);

        ImmutableMethodImplementation newImpl = new ImmutableMethodImplementation(
                regCount,
                newInstructions,
                impl.getTryBlocks(),
                Collections.emptyList()  // Drop debug info to avoid string index corruption
        );

        return new ImmutableMethod(
                original.getDefiningClass(),
                original.getName(),
                original.getParameters(),
                original.getReturnType(),
                original.getAccessFlags(),
                original.getAnnotations(),
                original.getHiddenApiRestrictions(),
                newImpl
        );
    }
}

package com.adsweep.manager;

import android.util.Log;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
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
     * Returns the patched DEX bytes, or null if not found/failed.
     */
    public static boolean patchDex(File dexFile, String applicationClass, File outputDex) {
        try {
            DexFile dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault());
            String targetType = "L" + applicationClass.replace('.', '/') + ";";

            List<ClassDef> classes = new ArrayList<>();
            boolean found = false;

            for (ClassDef classDef : dex.getClasses()) {
                if (classDef.getType().equals(targetType)) {
                    ClassDef patched = patchApplicationClass(classDef);
                    if (patched != null) {
                        classes.add(patched);
                        found = true;
                        Log.i(TAG, "Patched: " + applicationClass);
                    } else {
                        classes.add(classDef);
                    }
                } else {
                    classes.add(classDef);
                }
            }

            if (!found) return false;

            // Write patched DEX
            DexPool pool = new DexPool(Opcodes.getDefault());
            for (ClassDef c : classes) {
                pool.internClass(c);
            }
            pool.writeTo(new FileDataStore(outputDex));
            return true;

        } catch (Exception e) {
            Log.e(TAG, "DEX patch failed", e);
            return false;
        }
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

        // Rebuild class with patched methods
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

        // Opcode 0x71 = invoke-static
        ImmutableInstruction35c initCall = new ImmutableInstruction35c(
                com.android.tools.smali.dexlib2.Opcode.INVOKE_STATIC,
                1,    // register count
                0,    // p0 (this = Context)
                0, 0, 0, 0,
                initRef
        );

        // Prepend our instruction before existing instructions
        List<Instruction> newInstructions = new ArrayList<>();
        newInstructions.add(initCall);
        for (Instruction inst : impl.getInstructions()) {
            newInstructions.add(inst);
        }

        // Need at least 1 register for p0
        int regCount = Math.max(impl.getRegisterCount(), 1);

        ImmutableMethodImplementation newImpl = new ImmutableMethodImplementation(
                regCount,
                newInstructions,
                impl.getTryBlocks(),
                impl.getDebugItems()
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

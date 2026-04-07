package com.adsweep.patchtest;

import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.debug.DebugItem;
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
 * PC-side version for unit testing (no Android Log dependency).
 */
public class DexPatcher {

    /**
     * Find and patch the DEX file containing the Application class.
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
                        System.out.println("Patched: " + applicationClass);
                    } else {
                        classes.add(classDef);
                    }
                } else {
                    classes.add(classDef);
                }
            }

            if (!found) return false;

            // Strip debug info from all methods to avoid DexPool string index corruption
            List<ClassDef> strippedClasses = new ArrayList<>();
            for (ClassDef c : classes) {
                strippedClasses.add(stripDebugInfo(c));
            }

            // Write patched DEX using DexPool
            DexPool pool = new DexPool(Opcodes.getDefault());
            for (ClassDef c : strippedClasses) {
                pool.internClass(c);
            }
            pool.writeTo(new FileDataStore(outputDex));
            return true;

        } catch (Exception e) {
            System.err.println("DEX patch failed: " + e);
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Alternative: Write using DexFileFactory (may preserve structure better).
     */
    public static boolean patchDexV2(File dexFile, String applicationClass, File outputDex) {
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
                        System.out.println("Patched (v2): " + applicationClass);
                    } else {
                        classes.add(classDef);
                    }
                } else {
                    classes.add(classDef);
                }
            }

            if (!found) return false;

            DexFile patchedDex = new ImmutableDexFile(Opcodes.getDefault(), classes);
            DexFileFactory.writeDexFile(outputDex.getAbsolutePath(), patchedDex);
            return true;

        } catch (Exception e) {
            System.err.println("DEX patch v2 failed: " + e);
            e.printStackTrace();
            return false;
        }
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

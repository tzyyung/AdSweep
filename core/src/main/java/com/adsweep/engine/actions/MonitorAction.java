package com.adsweep.engine.actions;

import android.util.Log;

import com.adsweep.engine.RuleAction;
import com.adsweep.hook.HookContext;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Logs the method call but does not intercept. For --discover mode.
 * Writes structured log to file for post-analysis.
 */
public class MonitorAction implements RuleAction {

    private static final String TAG = "AdSweep.Monitor";
    private static PrintWriter logWriter;
    private static final Object LOCK = new Object();
    private final String description;

    public MonitorAction(String description) {
        this.description = description;
    }

    /** Initialize file logging. Call once from AdSweep.init(). */
    public static void initFileLog(File logFile) {
        try {
            logWriter = new PrintWriter(new FileWriter(logFile, true), true);
            logWriter.println("# AdSweep Discovery Log");
            logWriter.println("# timestamp|className|methodName|argCount|argTypes|callStack");
            Log.i(TAG, "Discovery log: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to init discovery log", e);
        }
    }

    @Override
    public Object execute(HookContext ctx) throws Exception {
        String className = ctx.targetClass != null ? ctx.targetClass.getName() : "unknown";
        String methodName = ctx.targetMethod.getName();
        int argCount = ctx.args.length;

        // Log to logcat
        Log.i(TAG, "Discover: " + className + "." + methodName
                + " (args=" + argCount + ")");

        // Log to file for post-analysis
        if (logWriter != null) {
            synchronized (LOCK) {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append(System.currentTimeMillis()).append("|");
                    sb.append(className).append("|");
                    sb.append(methodName).append("|");
                    sb.append(argCount).append("|");

                    // Arg types
                    StringBuilder argTypes = new StringBuilder();
                    for (int i = 0; i < argCount; i++) {
                        if (i > 0) argTypes.append(",");
                        argTypes.append(ctx.args[i] != null ? ctx.args[i].getClass().getSimpleName() : "null");
                    }
                    sb.append(argTypes).append("|");

                    // Abbreviated call stack (skip framework, only app classes)
                    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                    StringBuilder callers = new StringBuilder();
                    int count = 0;
                    for (StackTraceElement e : stack) {
                        String cls = e.getClassName();
                        if (cls.startsWith("com.adsweep") || cls.startsWith("java.")
                                || cls.startsWith("android.") || cls.startsWith("dalvik."))
                            continue;
                        if (count > 0) callers.append(" < ");
                        callers.append(cls).append(".").append(e.getMethodName());
                        if (++count >= 5) break;
                    }
                    sb.append(callers);

                    logWriter.println(sb);
                } catch (Exception e) {
                    // Don't let logging errors break the app
                }
            }
        }

        return ctx.callOriginal();
    }
}

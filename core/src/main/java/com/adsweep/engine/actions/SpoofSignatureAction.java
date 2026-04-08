package com.adsweep.engine.actions;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.util.Log;

import com.adsweep.engine.RuleAction;
import com.adsweep.hook.HookContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;

/**
 * Calls original getPackageInfo() then replaces the signing certificate
 * with the original APK's certificate (stored at inject time).
 *
 * This bypasses tamper detection for all apps without needing
 * app-specific reverse engineering.
 */
public class SpoofSignatureAction implements RuleAction {

    private static final String TAG = "AdSweep.SigSpoof";

    private final String ownPackage;
    private final byte[] originalCertDer;

    public SpoofSignatureAction(Context context) {
        this.ownPackage = context.getPackageName();
        this.originalCertDer = loadOriginalCert(context);
        if (originalCertDer != null) {
            Log.i(TAG, "Original signature loaded (" + originalCertDer.length + " bytes)");
        } else {
            Log.w(TAG, "No original signature asset — signature spoofing disabled");
        }
    }

    @Override
    public Object execute(HookContext ctx) throws Exception {
        // Call original getPackageInfo()
        Object result = ctx.callOriginal();
        if (result == null || originalCertDer == null) return result;

        PackageInfo pi = (PackageInfo) result;

        // Only spoof our own package
        String queriedPkg = ctx.getArgAsString(1);
        if (!ownPackage.equals(queriedPkg)) return pi;

        // Check if caller requested signatures
        // args: [this(PackageManager), packageName(String), flags(int)]
        Object flagsArg = ctx.getArg(2);
        int flags = 0;
        if (flagsArg instanceof Integer) {
            flags = (int) flagsArg;
        } else if (flagsArg instanceof Long) {
            flags = (int) (long) flagsArg;
        } else if (flagsArg != null) {
            // API 33+ PackageManager.PackageInfoFlags — extract int value via getValue()
            try {
                flags = (int) (long) flagsArg.getClass().getMethod("getValue").invoke(flagsArg);
            } catch (Exception ignored) {}
        }

        boolean wantsSignatures = (flags & 0x40) != 0;         // GET_SIGNATURES
        boolean wantsSigning = (flags & 0x08000000) != 0;       // GET_SIGNING_CERTIFICATES

        if (!wantsSignatures && !wantsSigning) return pi;

        // Replace signatures with original cert
        Signature spoofed = new Signature(originalCertDer);

        if (pi.signatures != null) {
            pi.signatures = new Signature[]{ spoofed };
        }

        // Also set signatures if signingInfo is present (API 28+)
        // Many apps read pi.signatures even on newer APIs
        if (pi.signatures == null) {
            pi.signatures = new Signature[]{ spoofed };
        }

        Log.i(TAG, "Spoofed signatures for " + ownPackage);
        return pi;
    }

    private static byte[] loadOriginalCert(Context context) {
        try {
            InputStream is = context.getAssets().open("adsweep_original_sig.bin");
            byte[] raw = readAll(is);
            is.close();

            CertificateFactory cf = CertificateFactory.getInstance("X.509");

            // Try as single DER certificate first (from v2/v3 extraction)
            try {
                Certificate cert = cf.generateCertificate(new ByteArrayInputStream(raw));
                return cert.getEncoded();
            } catch (Exception ignored) {}

            // Try as PKCS#7 container (from v1 META-INF/*.RSA)
            try {
                Collection<? extends Certificate> certs =
                        cf.generateCertificates(new ByteArrayInputStream(raw));
                if (!certs.isEmpty()) {
                    return certs.iterator().next().getEncoded();
                }
            } catch (Exception ignored) {}

            Log.w(TAG, "Could not parse certificate data (" + raw.length + " bytes)");
            return null;
        } catch (java.io.FileNotFoundException e) {
            // Normal for dev builds or APKs without original sig
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load original signature", e);
            return null;
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        return bos.toByteArray();
    }
}

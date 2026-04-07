package com.adsweep.patchtest;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * General-purpose APK rebuilder.
 * Principle: minimal modification — only replace DEX files and strip signatures.
 * Everything else (resources, META-INF/services, etc.) is copied as-is,
 * preserving the original compression method.
 */
public class ApkBuilder {

    /**
     * Returns true if the ZIP entry name is an APK signature file
     * that should be stripped (will be re-signed later).
     */
    public static boolean isSignatureFile(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String upper = name.toUpperCase();
        return upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC")
                || upper.equals("META-INF/MANIFEST.MF");
    }

    /**
     * Build a patched APK from an original APK.
     *
     * @param originalApk  the original (unmodified) APK
     * @param replacements entries to replace (entry name → File), typically patched DEX files
     * @param additions    entries to add (entry name → byte[]), typically payload files
     * @param outputApk    the output patched APK (unsigned)
     */
    public static void build(File originalApk, Map<String, File> replacements,
                             Map<String, byte[]> additions, File outputApk) throws Exception {

        Set<String> skipNames = new HashSet<>();
        if (replacements != null) skipNames.addAll(replacements.keySet());

        try (ZipFile src = new ZipFile(originalApk);
             ZipArchiveOutputStream zos = new ZipArchiveOutputStream(outputApk)) {

            Set<String> written = new HashSet<>();

            // Pass 1: copy original entries, preserving compression
            Enumeration<? extends ZipEntry> entries = src.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (skipNames.contains(name)) continue;
                if (isSignatureFile(name)) continue;

                copyEntry(zos, src, entry);
                written.add(name);
            }

            // Pass 2: write replacement entries (STORED, e.g. patched DEX)
            if (replacements != null) {
                for (Map.Entry<String, File> e : replacements.entrySet()) {
                    addStoredEntryFromFile(zos, e.getKey(), e.getValue());
                    written.add(e.getKey());
                }
            }

            // Pass 3: write additional entries (STORED, e.g. payload)
            if (additions != null) {
                for (Map.Entry<String, byte[]> e : additions.entrySet()) {
                    if (!written.contains(e.getKey())) {
                        addStoredEntry(zos, e.getKey(), e.getValue());
                        written.add(e.getKey());
                    }
                }
            }
        }
    }

    /**
     * Copy a ZIP entry preserving its original compression method.
     */
    static void copyEntry(ZipArchiveOutputStream zos, ZipFile src, ZipEntry entry) throws Exception {
        String name = entry.getName();
        InputStream is = src.getInputStream(entry); // always decompressed

        if (entry.getMethod() == ZipEntry.STORED) {
            // STORED: must pre-compute CRC and size
            byte[] data = readAll(is);
            is.close();

            ZipArchiveEntry out = new ZipArchiveEntry(name);
            out.setMethod(ZipEntry.STORED);
            out.setSize(data.length);
            out.setCompressedSize(data.length);
            out.setCrc(crc32(data));
            zos.putArchiveEntry(out);
            zos.write(data);
        } else {
            // DEFLATED: write decompressed data, let ZOS recompress
            ZipArchiveEntry out = new ZipArchiveEntry(name);
            out.setMethod(ZipEntry.DEFLATED);
            zos.putArchiveEntry(out);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) zos.write(buf, 0, len);
            is.close();
        }
        zos.closeArchiveEntry();
    }

    static void addStoredEntryFromFile(ZipArchiveOutputStream zos, String name, File file) throws Exception {
        long size = file.length();
        CRC32 crc = new CRC32();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) crc.update(buf, 0, len);
        }

        ZipArchiveEntry e = new ZipArchiveEntry(name);
        e.setMethod(ZipEntry.STORED);
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

    static void addStoredEntry(ZipArchiveOutputStream zos, String name, byte[] data) throws Exception {
        ZipArchiveEntry e = new ZipArchiveEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        e.setCrc(crc32(data));
        zos.putArchiveEntry(e);
        zos.write(data);
        zos.closeArchiveEntry();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
        return bos.toByteArray();
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}

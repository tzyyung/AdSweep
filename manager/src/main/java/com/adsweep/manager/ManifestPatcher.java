package com.adsweep.manager;

import android.util.Log;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.*;

/**
 * Patches binary AndroidManifest.xml inside an APK.
 * Removes requiredSplitTypes and splitTypes attributes
 * so the APK can be installed without matching splits.
 */
public class ManifestPatcher {

    private static final String TAG = "AdSweep.Manifest";

    /**
     * Patch the manifest in an APK to remove split requirements.
     * Also sets extractNativeLibs=true.
     */
    public static boolean patch(File apkFile) {
        try {
            byte[] manifestBytes = readEntryFromZip(apkFile, "AndroidManifest.xml");
            if (manifestBytes == null) return false;

            boolean modified = false;

            // Set extractNativeLibs=true
            if (setBooleanAttribute(manifestBytes, 0x010104ea, true)) {
                modified = true;
                Log.i(TAG, "Set extractNativeLibs=true");
            }

            // Set isSplitRequired=false (allows installing without split APKs)
            if (setBooleanAttribute(manifestBytes, 0x01010591, false)) {
                modified = true;
                Log.i(TAG, "Set isSplitRequired=false");
            }

            // Remove requiredSplitTypes string attribute
            byte[] patched = removeStringAttribute(manifestBytes, "requiredSplitTypes");
            if (patched != null) {
                manifestBytes = patched;
                modified = true;
                Log.i(TAG, "Removed requiredSplitTypes");
            }

            // Remove splitTypes string attribute
            patched = removeStringAttribute(manifestBytes, "splitTypes");
            if (patched != null) {
                manifestBytes = patched;
                modified = true;
                Log.i(TAG, "Removed splitTypes");
            }

            if (modified) {
                replaceEntryInZip(apkFile, "AndroidManifest.xml", manifestBytes);
                return true;
            }
            return false;

        } catch (Exception e) {
            Log.e(TAG, "Manifest patch failed", e);
            return false;
        }
    }

    /**
     * Remove a string attribute by zeroing out its value in the string pool.
     * This effectively makes the attribute empty/ignored.
     */
    private static byte[] removeStringAttribute(byte[] data, String attrName) {
        // Find the attribute name in the string pool
        // String pool starts at offset 8 (after header type + size)
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Parse string pool header
        int type = buf.getShort(0) & 0xFFFF; // should be 0x0001
        if (type != 0x0001 && type != 0x0003) return null;

        int headerSize = buf.getShort(2) & 0xFFFF;
        int chunkSize = buf.getInt(4);

        // For ResXMLTree (type 0x0003), string pool is the first chunk inside
        int spOffset = 8; // default
        if (type == 0x0003) {
            // Skip the XML tree header, find the string pool chunk
            spOffset = headerSize;
            int spType = buf.getShort(spOffset) & 0xFFFF;
            if (spType != 0x0001) return null; // not a string pool
        }

        int spHeaderSize = buf.getShort(spOffset + 2) & 0xFFFF;
        int spChunkSize = buf.getInt(spOffset + 4);
        int stringCount = buf.getInt(spOffset + 8);
        int styleCount = buf.getInt(spOffset + 12);
        int flags = buf.getInt(spOffset + 16);
        int stringsStart = buf.getInt(spOffset + 20);
        int stylesStart = buf.getInt(spOffset + 24);

        boolean isUtf8 = (flags & (1 << 8)) != 0;

        // Read string offsets
        int offsetsStart = spOffset + 28;
        int strDataStart = spOffset + stringsStart;

        // Search for the attribute name in string pool
        for (int i = 0; i < stringCount; i++) {
            int strOffset = buf.getInt(offsetsStart + i * 4);
            int absOffset = strDataStart + strOffset;

            String str = readStringAt(data, absOffset, isUtf8);
            if (attrName.equals(str)) {
                // Found it - zero out the string content to make it empty
                // This effectively removes the attribute by making its name unrecognizable
                // Better approach: change the string to something harmless
                writeStringAt(data, absOffset, isUtf8);
                return data;
            }
        }
        return null;
    }

    private static String readStringAt(byte[] data, int offset, boolean isUtf8) {
        try {
            if (isUtf8) {
                int charLen = data[offset] & 0xFF;
                if ((charLen & 0x80) != 0) {
                    charLen = ((charLen & 0x7F) << 8) | (data[offset + 1] & 0xFF);
                    offset++;
                }
                offset++;
                int byteLen = data[offset] & 0xFF;
                if ((byteLen & 0x80) != 0) {
                    byteLen = ((byteLen & 0x7F) << 8) | (data[offset + 1] & 0xFF);
                    offset++;
                }
                offset++;
                return new String(data, offset, byteLen, "UTF-8");
            } else {
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                int charLen = buf.getShort(offset) & 0xFFFF;
                offset += 2;
                char[] chars = new char[charLen];
                for (int i = 0; i < charLen; i++) {
                    chars[i] = (char) (buf.getShort(offset + i * 2) & 0xFFFF);
                }
                return new String(chars);
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeStringAt(byte[] data, int offset, boolean isUtf8) {
        // Overwrite string with spaces (same length, won't break structure)
        try {
            if (isUtf8) {
                int charLen = data[offset] & 0xFF;
                int skip = 1;
                if ((charLen & 0x80) != 0) { skip = 2; }
                offset += skip;
                int byteLen = data[offset] & 0xFF;
                skip = 1;
                if ((byteLen & 0x80) != 0) { skip = 2; }
                offset += skip;
                // Overwrite with underscores (safe replacement)
                for (int i = 0; i < byteLen; i++) {
                    data[offset + i] = '_';
                }
            } else {
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                int charLen = buf.getShort(offset) & 0xFFFF;
                offset += 2;
                for (int i = 0; i < charLen; i++) {
                    buf.putShort(offset + i * 2, (short) '_');
                }
            }
        } catch (Exception e) {}
    }

    /**
     * Set a boolean attribute by resource ID (same as Python manifest_patcher).
     */
    private static boolean setBooleanAttribute(byte[] data, int resId, boolean value) {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        // Find resource map by parsing chunk structure
        // XML tree: header(8) → StringPool(var) → ResourceMap(var) → elements...
        int rmOffset = -1;
        int offset = 8; // skip XML tree header
        while (offset < data.length - 8) {
            int t = buf.getShort(offset) & 0xFFFF;
            int size = buf.getInt(offset + 4);
            if (size <= 0 || size > data.length) break;
            if (t == 0x0180) { rmOffset = offset; break; }
            offset += size;
        }
        if (rmOffset < 0) {
            Log.w(TAG, "Resource map not found in manifest");
            return false;
        }

        int rmHeaderSize = buf.getShort(rmOffset + 2) & 0xFFFF;
        int rmSize = buf.getInt(rmOffset + 4);
        int rmCount = (rmSize - rmHeaderSize) / 4;

        // Find string index for this resource ID
        int stringIndex = -1;
        for (int i = 0; i < rmCount; i++) {
            int rid = buf.getInt(rmOffset + rmHeaderSize + i * 4);
            if (rid == resId) { stringIndex = i; break; }
        }
        if (stringIndex < 0) return false;

        // Find attribute entry with this string index and boolean type
        byte[] nameBytes = new byte[4];
        ByteBuffer.wrap(nameBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(stringIndex);
        int targetData = value ? 0xFFFFFFFF : 0x00000000;
        boolean found = false;
        int searchOffset = 0;

        while (true) {
            int idx = indexOf(data, nameBytes, searchOffset);
            if (idx < 0) break;
            if (idx + 16 <= data.length) {
                int dtype = data[idx + 11] & 0xFF;
                if (dtype == 0x12) { // TYPE_INT_BOOLEAN
                    buf.putInt(idx + 12, targetData);
                    found = true;
                }
            }
            searchOffset = idx + 4;
        }
        return found;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    // --- ZIP utilities ---

    private static byte[] readEntryFromZip(File zipFile, String entryName) throws Exception {
        try (ZipFile zf = new ZipFile(zipFile)) {
            ZipEntry entry = zf.getEntry(entryName);
            if (entry == null) return null;
            InputStream is = zf.getInputStream(entry);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
            is.close();
            return bos.toByteArray();
        }
    }

    private static void replaceEntryInZip(File zipFile, String entryName, byte[] newData) throws Exception {
        File tmp = new File(zipFile.getParent(), zipFile.getName() + ".tmp");
        try (ZipFile src = new ZipFile(zipFile);
             org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream zos =
                     new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(tmp)) {
            java.util.Enumeration<? extends ZipEntry> entries = src.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] data;
                if (entry.getName().equals(entryName)) {
                    data = newData;
                } else {
                    InputStream is = src.getInputStream(entry);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) bos.write(buf, 0, len);
                    is.close();
                    data = bos.toByteArray();
                }
                // Preserve original compression method
                org.apache.commons.compress.archivers.zip.ZipArchiveEntry newEntry =
                        new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(entry.getName());
                if (entry.getMethod() == ZipEntry.STORED) {
                    newEntry.setMethod(org.apache.commons.compress.archivers.zip.ZipArchiveEntry.STORED);
                    newEntry.setSize(data.length);
                    newEntry.setCompressedSize(data.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(data);
                    newEntry.setCrc(crc.getValue());
                } else {
                    newEntry.setMethod(org.apache.commons.compress.archivers.zip.ZipArchiveEntry.DEFLATED);
                }
                zos.putArchiveEntry(newEntry);
                zos.write(data);
                zos.closeArchiveEntry();
            }
        }
        zipFile.delete();
        tmp.renameTo(zipFile);
    }
}

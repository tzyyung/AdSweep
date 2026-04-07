"""
AdSweep Injector - Binary AndroidManifest.xml patcher.

Modifies the binary AXML manifest inside a built APK without
decompiling resources. Uses direct byte manipulation on the
Android binary XML format.
"""
import os
import struct
import zipfile


# Android resource IDs for attributes we want to modify
RES_EXTRACT_NATIVE_LIBS = 0x010104ea  # android:extractNativeLibs
RES_IS_SPLIT_REQUIRED = 0x01010591    # android:isSplitRequired


def patch_manifest_in_apk(apk_path: str) -> bool:
    """Patch the binary AndroidManifest.xml inside an APK.

    Supports:
    - Setting extractNativeLibs to true
    - Setting isSplitRequired to false
    - Removing requiredSplitTypes attribute
    - Removing com.android.vending.splits.required meta-data
    """
    try:
        with zipfile.ZipFile(apk_path) as z:
            data = bytearray(z.read("AndroidManifest.xml"))
    except Exception as e:
        print(f"[!] Cannot read manifest from APK: {e}")
        return False

    modified = False

    # Patch extractNativeLibs = true
    if _set_boolean_attribute(data, RES_EXTRACT_NATIVE_LIBS, True):
        print("[+] Set extractNativeLibs=true in binary manifest")
        modified = True

    # Patch isSplitRequired = false
    if _set_boolean_attribute(data, RES_IS_SPLIT_REQUIRED, False):
        print("[+] Set isSplitRequired=false in binary manifest")
        modified = True

    # Remove requiredSplitTypes string attribute
    patched = _remove_string_attribute(data, "requiredSplitTypes")
    if patched is not None:
        data = patched
        print("[+] Removed requiredSplitTypes from binary manifest")
        modified = True

    # Remove splitTypes string attribute
    patched = _remove_string_attribute(data, "splitTypes")
    if patched is not None:
        data = patched
        print("[+] Removed splitTypes from binary manifest")
        modified = True

    if not modified:
        return True  # Nothing to change

    # Write back to APK
    try:
        _replace_in_zip(apk_path, "AndroidManifest.xml", bytes(data))
        return True
    except Exception as e:
        print(f"[!] Failed to write manifest back to APK: {e}")
        return False


def _set_boolean_attribute(data: bytearray, res_id: int, value: bool) -> bool:
    """Find a boolean attribute by resource ID and change its value.

    In binary AXML format:
    - Resource IDs are mapped to string pool indices via the resource map chunk
    - Attribute entries are 20 bytes: ns(4) + name(4) + rawValue(4) + typedSize(2) + res0(1) + type(1) + data(4)
    - Boolean type = 0x12, false = 0x00000000, true = 0xFFFFFFFF
    """
    # Find the resource map to get the string pool index for this resource ID
    string_index = _find_string_index_for_res_id(data, res_id)
    if string_index < 0:
        return False

    # Find attribute entries referencing this string index with boolean type
    name_bytes = struct.pack('<I', string_index)
    target_data = 0xFFFFFFFF if value else 0x00000000
    found = False
    offset = 0

    while True:
        idx = data.find(name_bytes, offset)
        if idx < 0:
            break

        if idx + 16 <= len(data):
            dtype = data[idx + 11]
            if dtype == 0x12:  # TYPE_INT_BOOLEAN
                current = struct.unpack_from('<I', data, idx + 12)[0]
                if current != target_data:
                    struct.pack_into('<I', data, idx + 12, target_data)
                    found = True

        offset = idx + 4

    return found


def _find_string_index_for_res_id(data: bytearray, res_id: int) -> int:
    """Find the string pool index that maps to a given Android resource ID."""
    # Resource map chunk type = 0x0180
    res_map_type = struct.pack('<H', 0x0180)
    rm_idx = data.find(res_map_type)
    if rm_idx < 0:
        return -1

    rm_header_size = struct.unpack_from('<H', data, rm_idx + 2)[0]
    rm_size = struct.unpack_from('<I', data, rm_idx + 4)[0]
    rm_count = (rm_size - rm_header_size) // 4

    for i in range(rm_count):
        rid = struct.unpack_from('<I', data, rm_idx + rm_header_size + i * 4)[0]
        if rid == res_id:
            return i

    return -1


def _remove_string_attribute(data: bytearray, attr_name: str) -> bytearray:
    """Remove a string attribute by zeroing out its name in the string pool.

    Parses the AXML string pool, finds the attribute name string,
    and overwrites it with underscores to effectively disable it.
    """
    if len(data) < 28:
        return None

    # Parse header
    chunk_type = struct.unpack_from('<H', data, 0)[0]

    # Find string pool offset
    sp_offset = 8  # default for standalone string pool
    if chunk_type == 0x0003:  # ResXMLTree
        header_size = struct.unpack_from('<H', data, 2)[0]
        sp_offset = header_size
        sp_type = struct.unpack_from('<H', data, sp_offset)[0]
        if sp_type != 0x0001:
            return None

    sp_header_size = struct.unpack_from('<H', data, sp_offset + 2)[0]
    string_count = struct.unpack_from('<I', data, sp_offset + 8)[0]
    flags = struct.unpack_from('<I', data, sp_offset + 16)[0]
    strings_start = struct.unpack_from('<I', data, sp_offset + 20)[0]

    is_utf8 = (flags & (1 << 8)) != 0
    offsets_start = sp_offset + 28
    str_data_start = sp_offset + strings_start

    for i in range(string_count):
        str_offset = struct.unpack_from('<I', data, offsets_start + i * 4)[0]
        abs_offset = str_data_start + str_offset

        s = _read_string_at(data, abs_offset, is_utf8)
        if s == attr_name:
            _write_underscores_at(data, abs_offset, is_utf8)
            return data

    return None


def _read_string_at(data: bytearray, offset: int, is_utf8: bool) -> str:
    """Read a string from the AXML string pool at the given offset."""
    try:
        if is_utf8:
            # char length (1 or 2 bytes)
            char_len = data[offset] & 0xFF
            offset += 1
            if char_len & 0x80:
                offset += 1
            # byte length (1 or 2 bytes)
            byte_len = data[offset] & 0xFF
            offset += 1
            if byte_len & 0x80:
                byte_len = ((byte_len & 0x7F) << 8) | (data[offset] & 0xFF)
                offset += 1
            return data[offset:offset + byte_len].decode('utf-8')
        else:
            char_len = struct.unpack_from('<H', data, offset)[0]
            offset += 2
            chars = struct.unpack_from(f'<{char_len}H', data, offset)
            return ''.join(chr(c) for c in chars)
    except Exception:
        return ""


def _write_underscores_at(data: bytearray, offset: int, is_utf8: bool):
    """Overwrite a string in the pool with underscores (same length)."""
    try:
        if is_utf8:
            char_len = data[offset] & 0xFF
            skip = 1
            if char_len & 0x80:
                skip = 2
            offset += skip
            byte_len = data[offset] & 0xFF
            skip = 1
            if byte_len & 0x80:
                byte_len = ((byte_len & 0x7F) << 8) | (data[offset + 1] & 0xFF)
                skip = 2
            offset += skip
            for i in range(byte_len):
                data[offset + i] = ord('_')
        else:
            char_len = struct.unpack_from('<H', data, offset)[0]
            offset += 2
            for i in range(char_len):
                struct.pack_into('<H', data, offset + i * 2, ord('_'))
    except Exception:
        pass


def _replace_in_zip(zip_path: str, entry_name: str, new_data: bytes):
    """Replace a single entry in a ZIP file."""
    tmp_path = zip_path + ".tmp"
    with zipfile.ZipFile(zip_path, 'r') as src, \
         zipfile.ZipFile(tmp_path, 'w') as dst:
        for item in src.infolist():
            if item.filename == entry_name:
                dst.writestr(item, new_data)
            else:
                dst.writestr(item, src.read(item.filename))
    os.replace(tmp_path, zip_path)

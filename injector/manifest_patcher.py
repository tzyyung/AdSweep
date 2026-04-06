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


def patch_manifest_in_apk(apk_path: str) -> bool:
    """Patch the binary AndroidManifest.xml inside an APK.

    Currently supports:
    - Setting extractNativeLibs to true
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

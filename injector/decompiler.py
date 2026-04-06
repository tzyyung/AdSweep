"""
AdSweep Injector - APK decompilation and recompilation via apktool.

Strategy: Use -r (no-res) to avoid resource @null corruption.
Manifest is handled separately:
  1. Decode manifest-only for text editing
  2. Compile modified manifest via a minimal apktool build
  3. Inject compiled binary manifest into the final APK
"""
import subprocess
import os
import shutil
import tempfile
import zipfile

from config import APKTOOL


def decompile(apk_path: str, output_dir: str) -> bool:
    """Decompile APK with -r (no resources). Manifest decoded separately."""
    print(f"[*] Decompiling {apk_path} (no-res)...")

    # Main decompile: smali only
    cmd = [APKTOOL, "d", apk_path, "-o", output_dir, "-f", "-r"]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[!] Decompile failed:\n{result.stderr}")
        return False

    # Decode manifest separately for text editing
    manifest_dir = tempfile.mkdtemp(prefix="adsweep_manifest_")
    cmd = [APKTOOL, "d", apk_path, "-o", manifest_dir, "-f", "--only-manifest"]
    result = subprocess.run(cmd, capture_output=True, text=True)

    text_manifest = os.path.join(manifest_dir, "AndroidManifest.xml")
    if result.returncode == 0 and os.path.exists(text_manifest):
        # Store text manifest alongside binary one
        shutil.copy2(
            text_manifest,
            os.path.join(output_dir, "AndroidManifest.xml.text")
        )
    shutil.rmtree(manifest_dir, ignore_errors=True)

    print(f"[+] Decompiled to {output_dir}")
    return True


def recompile(decompiled_dir: str, output_apk: str) -> bool:
    """Recompile with -r, then inject modified manifest."""

    text_manifest = os.path.join(decompiled_dir, "AndroidManifest.xml.text")
    has_text_manifest = os.path.exists(text_manifest)

    # Step 1: Build with -r (binary manifest, no resource recompilation)
    print(f"[*] Recompiling {decompiled_dir}...")
    cmd = [APKTOOL, "b", decompiled_dir, "-o", output_apk]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[!] Recompile failed:\n{result.stderr}")
        return False

    # Step 2: If we have a modified text manifest, compile and inject it
    if has_text_manifest:
        if not _inject_manifest(decompiled_dir, text_manifest, output_apk):
            print("[!] Manifest injection failed, APK has original manifest")

    print(f"[+] Recompiled to {output_apk}")
    return True


def _inject_manifest(decompiled_dir: str, text_manifest: str, apk_path: str) -> bool:
    """Compile a text manifest to binary and inject into APK.

    Uses a temporary apktool project with only the manifest to compile it.
    """
    tmp_dir = tempfile.mkdtemp(prefix="adsweep_manifest_build_")
    tmp_apk = os.path.join(tmp_dir, "tmp.apk")

    try:
        # Create a minimal apktool project just for manifest compilation
        os.makedirs(os.path.join(tmp_dir, "project"), exist_ok=True)
        shutil.copy2(text_manifest, os.path.join(tmp_dir, "project", "AndroidManifest.xml"))

        # Copy apktool.yml from the real project (needed for framework info)
        yml_src = os.path.join(decompiled_dir, "apktool.yml")
        if os.path.exists(yml_src):
            shutil.copy2(yml_src, os.path.join(tmp_dir, "project", "apktool.yml"))

        # Build the minimal project to get compiled manifest
        cmd = [APKTOOL, "b", os.path.join(tmp_dir, "project"), "-o", tmp_apk]
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            print(f"[!] Manifest compile failed:\n{result.stderr}")
            return False

        # Extract compiled manifest from temp APK
        with zipfile.ZipFile(tmp_apk, 'r') as src_zip:
            compiled_manifest = src_zip.read("AndroidManifest.xml")

        # Replace manifest in the real APK
        _replace_in_zip(apk_path, "AndroidManifest.xml", compiled_manifest)
        print("[+] Injected modified manifest into APK")
        return True

    except Exception as e:
        print(f"[!] Manifest injection error: {e}")
        return False
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)


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

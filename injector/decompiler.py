"""
AdSweep Injector - APK decompilation and recompilation via apktool.

Uses -r (no-res) to avoid resource corruption.
Manifest modifications are done post-build via binary patching.
"""
import subprocess
import os

from config import APKTOOL


def decompile(apk_path: str, output_dir: str) -> bool:
    """Decompile APK with -r (no resources, no manifest decode)."""
    print(f"[*] Decompiling {apk_path} (no-res)...")
    cmd = [APKTOOL, "d", apk_path, "-o", output_dir, "-f", "-r"]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[!] Decompile failed:\n{result.stderr}")
        return False
    print(f"[+] Decompiled to {output_dir}")
    return True


def recompile(decompiled_dir: str, output_apk: str) -> bool:
    """Recompile a decompiled directory into an APK."""
    print(f"[*] Recompiling {decompiled_dir}...")
    cmd = [APKTOOL, "b", decompiled_dir, "-o", output_apk]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[!] Recompile failed:\n{result.stderr}")
        return False
    print(f"[+] Recompiled to {output_apk}")
    return True

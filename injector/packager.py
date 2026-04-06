"""
AdSweep Injector - Repackage, align, and sign the patched APK.
"""
import subprocess
import os

from config import ZIPALIGN, APKSIGNER, DEFAULT_KEYSTORE, DEFAULT_KS_PASS, DEFAULT_KEY_ALIAS, DEFAULT_KEY_PASS


def package_apk(decompiled_dir: str, output_apk: str,
                keystore: str = None, ks_pass: str = None,
                key_alias: str = None, key_pass: str = None) -> bool:
    """Recompile, align, and sign the APK."""

    keystore = keystore or DEFAULT_KEYSTORE
    ks_pass = ks_pass or DEFAULT_KS_PASS
    key_alias = key_alias or DEFAULT_KEY_ALIAS
    key_pass = key_pass or DEFAULT_KEY_PASS

    base_dir = os.path.dirname(output_apk) or "."
    unsigned_apk = os.path.join(base_dir, "adsweep_unsigned.apk")
    aligned_apk = os.path.join(base_dir, "adsweep_aligned.apk")

    # Step 1: Recompile with apktool
    from decompiler import recompile
    if not recompile(decompiled_dir, unsigned_apk):
        return False

    # Step 2: Zipalign
    print("[*] Aligning APK...")
    cmd = [ZIPALIGN, "-f", "4", unsigned_apk, aligned_apk]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[!] Zipalign failed:\n{result.stderr}")
        return False
    print("[+] APK aligned")

    # Step 3: Sign
    print("[*] Signing APK...")
    cmd = [
        APKSIGNER, "sign",
        "--ks", keystore,
        "--ks-pass", f"pass:{ks_pass}",
        "--ks-key-alias", key_alias,
        "--key-pass", f"pass:{key_pass}",
        aligned_apk,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"[!] Signing failed:\n{result.stderr}")
        return False
    print("[+] APK signed")

    # Step 4: Rename to final output
    os.rename(aligned_apk, output_apk)

    # Cleanup
    for tmp in [unsigned_apk]:
        if os.path.exists(tmp):
            os.remove(tmp)

    print(f"[+] Final APK: {output_apk}")
    return True
